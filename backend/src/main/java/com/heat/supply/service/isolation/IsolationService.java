package com.heat.supply.service.isolation;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heat.supply.common.BizException;
import com.heat.supply.domain.HeatSource;
import com.heat.supply.domain.HeatStation;
import com.heat.supply.domain.IsolationPlan;
import com.heat.supply.domain.IsolationStep;
import com.heat.supply.domain.PipeSegment;
import com.heat.supply.domain.Resident;
import com.heat.supply.domain.ValveWell;
import com.heat.supply.dto.IsolationFailureRequest;
import com.heat.supply.dto.IsolationPlanVO;
import com.heat.supply.dto.IsolationStepConfirmRequest;
import com.heat.supply.dto.IsolationStepVO;
import com.heat.supply.dto.IsolationTrialRequest;
import com.heat.supply.dto.StationImpactVO;
import com.heat.supply.dto.SupplyPathVO;
import com.heat.supply.dto.ValvePlanVO;
import com.heat.supply.repository.HeatSourceRepository;
import com.heat.supply.repository.HeatStationRepository;
import com.heat.supply.repository.IsolationPlanRepository;
import com.heat.supply.repository.IsolationStepRepository;
import com.heat.supply.repository.PipeSegmentRepository;
import com.heat.supply.repository.ResidentRepository;
import com.heat.supply.repository.ValveWellRepository;
import com.heat.supply.service.isolation.TopologyEngine.BoundaryResult;
import com.heat.supply.service.isolation.TopologyEngine.BoundaryValve;
import com.heat.supply.service.isolation.TopologyEngine.Graph;
import com.heat.supply.service.isolation.TopologyEngine.Proof;
import com.heat.supply.service.isolation.TopologyEngine.Traversal;
import com.heat.supply.service.isolation.TopologyEngine.Valve;

/**
 * 管网隔离方案闭环服务。
 *
 * <pre>
 * 选故障管段 → 拓扑试算（关断序列 / 可保供路径 / 受影响换热站，区分计划关闭·原本关闭·必须开启）
 * → 人工调整 → 重新证明（故障段必须与运行管网隔开、不得切断撤离路径、不得误判仍有路径的换热站）
 * → 启用 → 现场逐步确认（前一步未完成 / 反馈不符 / 阀门被他人改变则暂停后续）
 * → 失败三收口（扩大隔离 / 安全顺序恢复 / 保持现场等待）→ 修复完成 → 按当前阀门实况复供试算
 * </pre>
 */
@Service
public class IsolationService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final PipeSegmentRepository segmentRepository;
    private final ValveWellRepository valveWellRepository;
    private final HeatSourceRepository sourceRepository;
    private final HeatStationRepository stationRepository;
    private final ResidentRepository residentRepository;
    private final IsolationPlanRepository planRepository;
    private final IsolationStepRepository stepRepository;
    private final ObjectMapper objectMapper;

    public IsolationService(PipeSegmentRepository segmentRepository,
                            ValveWellRepository valveWellRepository,
                            HeatSourceRepository sourceRepository,
                            HeatStationRepository stationRepository,
                            ResidentRepository residentRepository,
                            IsolationPlanRepository planRepository,
                            IsolationStepRepository stepRepository,
                            ObjectMapper objectMapper) {
        this.segmentRepository = segmentRepository;
        this.valveWellRepository = valveWellRepository;
        this.sourceRepository = sourceRepository;
        this.stationRepository = stationRepository;
        this.residentRepository = residentRepository;
        this.planRepository = planRepository;
        this.stepRepository = stepRepository;
        this.objectMapper = objectMapper;
    }

    // ==================================================================
    // 试算
    // ==================================================================

    @Transactional
    public IsolationPlanVO trial(IsolationTrialRequest request) {
        String strategy = "WAIT_ALT".equals(request.getStrategy()) ? "WAIT_ALT" : "EXPAND";
        TopologyData data = loadData();
        mustFault(data, request.getFaultSegmentId());

        TrialResult trial = computeTrial(data, request.getFaultSegmentId(), strategy,
                idSet(request.getExtraCloseValveIds()), idSet(request.getExtraOpenValveIds()),
                idSet(request.getRemovedValveIds()), new HashMap<>());

        IsolationPlan plan = new IsolationPlan();
        plan.setPlanNo(generateNo("GL"));
        plan.setPlanType("ISOLATE");
        plan.setFaultSegmentId(request.getFaultSegmentId());
        plan.setFaultReason(request.getFaultReason());
        plan.setPlanStatus("草稿");
        plan.setStrategy(strategy);
        plan.setCreatedBy(request.getCreatedBy());
        plan.setDeviationJson("[]");
        plan.setCreatedTime(LocalDateTime.now());
        plan.setUpdatedTime(LocalDateTime.now());
        fillPlanFromTrial(plan, trial, data);
        plan = planRepository.save(plan);
        saveSteps(plan.getId(), trial.steps);
        return toVO(plan, loadData(), trial);
    }

    /** 人工调整后重新试算：覆盖阀门取舍，重新做隔离证明（未通过证明不得启用） */
    @Transactional
    public IsolationPlanVO reTrial(Long planId, IsolationTrialRequest request) {
        IsolationPlan plan = mustPlan(planId);
        if (!List.of("草稿", "失败待处置").contains(plan.getPlanStatus())) {
            throw new BizException("当前方案状态为「" + plan.getPlanStatus() + "」，仅草稿 / 失败待处置方案可重新试算");
        }
        String strategy = request.getStrategy() == null
                ? (plan.getStrategy() == null ? "EXPAND" : plan.getStrategy())
                : ("WAIT_ALT".equals(request.getStrategy()) ? "WAIT_ALT" : "EXPAND");
        Long faultId = request.getFaultSegmentId() != null ? request.getFaultSegmentId() : plan.getFaultSegmentId();
        TopologyData data = loadData();
        mustFault(data, faultId);

        // 失败后重算：以当前阀门实况为基线（保留现场已完成动作），叠加人工取舍
        Map<Long, String> liveOverride = new HashMap<>();
        for (ValveWell w : data.wells) {
            liveOverride.put(w.getId(), w.getOpenState());
        }
        TrialResult trial = computeTrial(data, faultId, strategy,
                idSet(request.getExtraCloseValveIds()), idSet(request.getExtraOpenValveIds()),
                idSet(request.getRemovedValveIds()), liveOverride);

        plan.setFaultSegmentId(faultId);
        if (request.getFaultReason() != null) {
            plan.setFaultReason(request.getFaultReason());
        }
        plan.setStrategy(strategy);
        if (request.getCreatedBy() != null) {
            plan.setCreatedBy(request.getCreatedBy());
        }
        fillPlanFromTrial(plan, trial, data);
        plan.setUpdatedTime(LocalDateTime.now());
        stepRepository.deleteByPlanId(plan.getId());
        plan = planRepository.save(plan);
        saveSteps(plan.getId(), trial.steps);
        return toVO(plan, loadData(), trial);
    }

    /** 启用方案：证明不通过、或 WAIT_ALT 且边界不闭合时拒绝启用 */
    @Transactional
    public IsolationPlanVO enable(Long planId, String operator) {
        IsolationPlan plan = mustPlan(planId);
        if (!"草稿".equals(plan.getPlanStatus())) {
            throw new BizException("仅草稿状态方案可启用，当前：" + plan.getPlanStatus());
        }
        TopologyData data = loadData();
        TrialResult trial = computeFromLive(data, plan);
        if (!trial.proof.passed) {
            throw new BizException("隔离证明未通过，禁止启用：" + trial.proof.detail);
        }
        if ("WAIT_ALT".equals(plan.getStrategy()) && !trial.boundary.boundaryClosed) {
            throw new BizException("现场存在不可操作阀，当前选择为「拒绝启用、等待替代路径」，"
                    + "请改选扩大停暖或排除阀门故障后重新试算");
        }
        // 启用时用当前实况重刷快照，避免草稿期间现场阀门已变化
        fillPlanFromTrial(plan, trial, data);
        plan.setPlanStatus("已启用");
        plan.setEnabledTime(LocalDateTime.now());
        plan.setUpdatedTime(LocalDateTime.now());
        planRepository.save(plan);
        stepRepository.deleteByPlanId(plan.getId());
        saveSteps(plan.getId(), trial.steps);
        return toVO(plan, loadData(), trial);
    }

    // ==================================================================
    // 现场逐步确认
    // ==================================================================

    @Transactional
    public IsolationPlanVO confirmStep(Long planId, Long stepId, IsolationStepConfirmRequest request) {
        IsolationPlan plan = mustPlan(planId);
        if (!List.of("已启用", "执行中", "复供试算").contains(plan.getPlanStatus())) {
            throw new BizException("方案当前为「" + plan.getPlanStatus() + "」，不可进行现场确认");
        }
        List<IsolationStep> steps = stepRepository.findByPlanIdOrderByStepNoAsc(planId);
        IsolationStep current = steps.stream().filter(s -> s.getId().equals(stepId)).findFirst()
                .orElseThrow(() -> new BizException("步骤不存在：id=" + stepId));
        if (!IsolationStep.ST_PENDING.equals(current.getStepStatus())) {
            throw new BizException("步骤 " + current.getStepNo() + " 当前状态为「" + current.getStepStatus()
                    + "」，仅待执行步骤可确认");
        }

        // 规则一：前一步未完成 → 本步及后续暂停
        for (IsolationStep s : steps) {
            if (s.getStepNo() < current.getStepNo()
                    && !List.of(IsolationStep.ST_DONE, IsolationStep.ST_SKIPPED).contains(s.getStepStatus())) {
                current.setStepStatus(IsolationStep.ST_PAUSED);
                current.setDeviationReason("前序步骤 " + s.getStepNo() + "（" + actionLabel(s)
                        + "）尚未完成，本步骤及后续暂停");
                pauseRemaining(steps, current.getStepNo() + 1);
                stepRepository.saveAll(steps);
                freezeToFailure(plan, "HOLD", "前序步骤未完成即尝试操作，已暂停，请选择收口方式");
                return buildVO(plan);
            }
        }

        // 非阀门步骤：撤离确认 / 隔离验证
        if (current.getValveWellId() == null) {
            if (IsolationStep.TYPE_PROOF.equals(current.getActionType())) {
                return completeProofStep(plan, steps, current, request);
            }
            current.setStepStatus(IsolationStep.ST_DONE);
            current.setCompletedBy(request.getOperator());
            current.setCompletedTime(LocalDateTime.now());
            if (request.getRemark() != null) {
                current.setRemark(appendText(current.getRemark(), request.getRemark()));
            }
            stepRepository.save(current);
            if ("已启用".equals(plan.getPlanStatus())) {
                plan.setPlanStatus("执行中");
                plan.setUpdatedTime(LocalDateTime.now());
                planRepository.save(plan);
            }
            return maybeFinish(plan, steps);
        }

        TopologyData data = loadData();
        ValveWell live = valveWellRepository.findById(current.getValveWellId())
                .orElseThrow(() -> new BizException("阀门已不存在：" + current.getWellNo()));
        Map<Long, String> baseline = readStateMap(plan.getBaselineSnapshotJson());
        String stateAtTrial = baseline.getOrDefault(live.getId(), live.getOpenState());
        current.setStateBefore(stateAtTrial);

        String observed = request.getObservedState() == null ? live.getOpenState() : request.getObservedState();

        // 规则二：同一阀门已被他人改变 —— 实况既非试算时状态、也非计划目标态
        boolean drift = !Objects.equals(live.getOpenState(), stateAtTrial)
                && !Objects.equals(live.getOpenState(), current.getPlannedState());
        if (drift) {
            current.setStepStatus(IsolationStep.ST_PAUSED);
            current.setObservedState(live.getOpenState());
            current.setDeviationReason("阀门 " + current.getWellNo() + " 已被他人改变：试算时为「"
                    + stateAtTrial + "」，现场实况为「" + live.getOpenState() + "」，计划目标为「"
                    + current.getPlannedState() + "」；后续步骤已暂停");
            pauseRemaining(steps, current.getStepNo() + 1);
            stepRepository.saveAll(steps);
            appendDeviation(plan, current.getDeviationReason());
            freezeToFailure(plan, "HOLD", "现场阀门状态与方案不一致，等待处置");
            return buildVO(plan);
        }

        // 规则三：反馈与计划不符
        boolean conform = feedbackConforms(current, observed, live);
        if (!conform) {
            current.setStepStatus(IsolationStep.ST_DEVIATED);
            current.setObservedState(observed);
            current.setDeviationReason(request.getDeviationReason() == null
                    ? ("现场反馈「" + observed + "」与计划「" + current.getPlannedState() + "」不符")
                    : request.getDeviationReason());
            current.setCompletedBy(request.getOperator());
            pauseRemaining(steps, current.getStepNo() + 1);
            stepRepository.saveAll(steps);
            appendDeviation(plan, "步骤" + current.getStepNo() + " " + current.getWellNo() + "："
                    + current.getDeviationReason());
            freezeToFailure(plan, "HOLD", "反馈与计划不符，等待处置（扩大隔离 / 安全恢复 / 保持现场）");
            return buildVO(plan);
        }

        // 反馈合格：把阀门实况落库（CHECK_CLOSED 只核对、不改变现场）
        if (!IsolationStep.TYPE_CHECK_CLOSED.equals(current.getActionType())) {
            live.setOpenState(current.getPlannedState());
            valveWellRepository.save(live);
        }
        current.setObservedState(observed);
        current.setStepStatus(IsolationStep.ST_DONE);
        current.setCompletedBy(request.getOperator());
        current.setCompletedTime(LocalDateTime.now());
        if (request.getRemark() != null) {
            current.setRemark(appendText(current.getRemark(), request.getRemark()));
        }
        stepRepository.save(current);
        return maybeFinish(plan, steps);
    }

    private boolean feedbackConforms(IsolationStep step, String observed, ValveWell live) {
        switch (step.getActionType()) {
            case IsolationStep.TYPE_CLOSE:
                return "关闭".equals(observed);
            case IsolationStep.TYPE_OPEN:
                // 全开 / 半开均视为已导通（半开足以保供时现场可注明）
                return "全开".equals(observed) || "半开".equals(observed);
            case IsolationStep.TYPE_CHECK_CLOSED:
                return "关闭".equals(observed) && "关闭".equals(live.getOpenState());
            default:
                return Objects.equals(observed, step.getPlannedState());
        }
    }

    private IsolationPlanVO completeProofStep(IsolationPlan plan, List<IsolationStep> steps,
                                              IsolationStep proofStep, IsolationStepConfirmRequest request) {
        TopologyData data = loadData();
        boolean restore = "RESTORE".equals(plan.getPlanType());
        Traversal live = new Traversal();
        if (!restore && plan.getFaultSegmentId() != null) {
            live.faultSegmentId = plan.getFaultSegmentId();
        }
        Proof proof = restore ? null
                : TopologyEngine.proveIsolation(data.graph, live, plan.getFaultSegmentId());
        if (!restore && !proof.passed) {
            proofStep.setStepStatus(IsolationStep.ST_DEVIATED);
            proofStep.setDeviationReason("终态证明未通过：" + proof.detail);
            stepRepository.save(proofStep);
            pauseRemaining(steps, proofStep.getStepNo() + 1);
            appendDeviation(plan, "终态隔离证明未通过：" + proof.detail);
            freezeToFailure(plan, "HOLD", "阀门全部到位但故障段仍未与运行管网隔开：" + proof.detail);
            return buildVO(plan);
        }
        Set<String> reach = TopologyEngine.reachable(data.graph, live);
        Map<Long, Boolean> baseSupplied = new HashMap<>();
        for (StationImpactVO v : readList(plan.getAffectedJson(), StationImpactVO.class)) {
            baseSupplied.put(v.getStationId(), v.isBaselineSupplied());
        }
        List<StationImpactVO> impacts = buildImpacts(data, live,
                !restore && plan.getFaultSegmentId() != null ? plan.getFaultSegmentId() : null, baseSupplied);
        plan.setAffectedJson(write(impacts));
        if (restore) {
            List<HeatStation> stillBad = impacts.stream()
                    .filter(v -> "运行".equals(v.getStatus()) && !v.isTargetSupplied())
                    .map(v -> data.stationById.get(v.getStationId())).filter(Objects::nonNull)
                    .collect(Collectors.toList());
            String note = stillBad.isEmpty()
                    ? "复供证明通过：所有运行换热站均已恢复供热路径"
                    : "复供后仍不可达的换热站（留异常清单）："
                            + stillBad.stream().map(HeatStation::getCode).collect(Collectors.joining("、"))
                            + "；请现场排查阀门与管段";
            plan.setRestoreProofDetail(note);
            plan.setProofDetail(note);
            plan.setProofPassed(true);
        } else {
            plan.setProofPassed(true);
            plan.setProofDetail(proof.detail);
        }
        proofStep.setStepStatus(IsolationStep.ST_DONE);
        proofStep.setRemark(restore ? plan.getRestoreProofDetail() : plan.getProofDetail());
        proofStep.setCompletedBy(request.getOperator());
        proofStep.setCompletedTime(LocalDateTime.now());
        stepRepository.save(proofStep);
        return maybeFinish(plan, steps);
    }

    /** 全部步骤完成后的方案收口；同时按实况刷新受影响清单保证三方一致 */
    private IsolationPlanVO maybeFinish(IsolationPlan plan, List<IsolationStep> steps) {
        boolean allDone = steps.stream()
                .allMatch(s -> List.of(IsolationStep.ST_DONE, IsolationStep.ST_SKIPPED).contains(s.getStepStatus()));
        if (!allDone) {
            if ("已启用".equals(plan.getPlanStatus())) {
                plan.setPlanStatus("执行中");
            }
            plan.setUpdatedTime(LocalDateTime.now());
            planRepository.save(plan);
            return buildVO(plan);
        }
        if ("RESTORE".equals(plan.getPlanType())) {
            plan.setPlanStatus("已复供");
            plan.setRestoredTime(LocalDateTime.now());
        } else {
            plan.setPlanStatus("已隔离");
            plan.setIsolatedTime(LocalDateTime.now());
        }
        plan.setUpdatedTime(LocalDateTime.now());
        planRepository.save(plan);
        return buildVO(plan);
    }

    // ==================================================================
    // 中途失败：扩大隔离 / 安全顺序恢复 / 保持现场
    // ==================================================================

    @Transactional
    public IsolationPlanVO resolveFailure(Long planId, IsolationFailureRequest request) {
        IsolationPlan plan = mustPlan(planId);
        if (!"失败待处置".equals(plan.getPlanStatus())) {
            throw new BizException("仅「失败待处置」方案可做失败收口，当前：" + plan.getPlanStatus());
        }
        String outcome = request.getOutcome();
        if (!List.of("EXPAND", "ROLLBACK", "HOLD").contains(outcome)) {
            throw new BizException("收口方式只能是：EXPAND 扩大隔离 / ROLLBACK 安全顺序恢复 / HOLD 保持现场");
        }
        TopologyData data = loadData();

        if ("HOLD".equals(outcome)) {
            plan.setFailureOutcome("HOLD");
            plan.setFailureNote(request.getNote() == null ? "保持现场状态，等待人工处置（方案状态与阀门实况冻结）"
                    : request.getNote());
            plan.setFailureTime(LocalDateTime.now());
            plan.setUpdatedTime(LocalDateTime.now());
            planRepository.save(plan);
            return buildVO(plan);
        }

        List<IsolationStep> steps = stepRepository.findByPlanIdOrderByStepNoAsc(planId);

        if ("ROLLBACK".equals(outcome)) {
            // 安全恢复：按完成顺序的逆序生成恢复动作；开阀先于关阀（先充压再隔断），
            // 原本关闭只核对过的阀不动；撤离确认类步骤不恢复。
            List<IsolationStep> rollback = new ArrayList<>();
            int no = 1;
            List<IsolationStep> done = steps.stream()
                    .filter(s -> IsolationStep.ST_DONE.equals(s.getStepStatus()) && s.getValveWellId() != null)
                    .sorted(Comparator.comparingInt(IsolationStep::getStepNo).reversed())
                    .collect(Collectors.toList());
            // 先逆序恢复「被关的阀」→ 再逆序关回「原本关闭、为保供临时开启的阀」
            for (IsolationStep s : done) {
                if (!IsolationStep.TYPE_CLOSE.equals(s.getActionType())) {
                    continue;
                }
                String target = s.getStateBefore();
                if (target == null || "关闭".equals(target)) {
                    target = "全开"; // 无原态记录时按全开恢复
                }
                rollback.add(reverseStep(plan.getId(), no++, s, IsolationStep.TYPE_OPEN, target,
                        "安全恢复：将隔离时关闭的 " + s.getWellNo() + " 逆序开回"));
            }
            for (IsolationStep s : done) {
                if (!IsolationStep.TYPE_OPEN.equals(s.getActionType())
                        || !"关闭".equals(s.getStateBefore())) {
                    continue; // 原本就是开的阀无需关回
                }
                rollback.add(reverseStep(plan.getId(), no++, s, IsolationStep.TYPE_CLOSE,
                        s.getStateBefore(),
                        "安全恢复：将保供时开启的 " + s.getWellNo() + " 逆序恢复原状"));
            }
            IsolationStep proof = new IsolationStep();
            proof.setPlanId(plan.getId());
            proof.setStepNo(no);
            proof.setActionType(IsolationStep.TYPE_PROOF);
            proof.setStepStatus(IsolationStep.ST_PENDING);
            proof.setRemark("恢复动作完成后验证管网恢复到安全状态");
            rollback.add(proof);

            stepRepository.deleteByPlanId(plan.getId());
            stepRepository.saveAll(rollback);
            plan.setPlanStatus("已撤回");
            plan.setFailureOutcome("ROLLBACK");
            plan.setFailureNote(request.getNote() == null
                    ? "已按安全顺序生成恢复序列（先开后关、逆序恢复），请现场逐项确认；方案撤回"
                    : request.getNote());
            plan.setFailureTime(LocalDateTime.now());
            plan.setUpdatedTime(LocalDateTime.now());
            planRepository.save(plan);
            return buildVO(plan);
        }

        // EXPAND：以当前阀门实况为基线重新试算（扩大隔离），偏差与已完成记录保留
        Map<Long, String> liveOverride = new HashMap<>();
        for (ValveWell w : data.wells) {
            liveOverride.put(w.getId(), w.getOpenState());
        }
        TrialResult trial = computeTrial(data, plan.getFaultSegmentId(), "EXPAND",
                new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), liveOverride);

        // 旧步骤：未完成 / 暂停 / 偏差的作废；已完成的保留为记录
        int baseNo = 0;
        for (IsolationStep s : steps) {
            baseNo = Math.max(baseNo, s.getStepNo());
            if (!IsolationStep.ST_DONE.equals(s.getStepStatus())) {
                s.setStepStatus(IsolationStep.ST_SKIPPED);
                s.setRemark(appendText(s.getRemark(), "扩大隔离重算后旧步骤作废"));
            }
        }
        stepRepository.saveAll(steps);
        List<IsolationStep> fresh = new ArrayList<>();
        for (IsolationStep s : trial.steps) {
            s.setPlanId(plan.getId());
            s.setStepNo(s.getStepNo() + baseNo);
            fresh.add(s);
        }
        stepRepository.saveAll(fresh);

        fillPlanFromTrial(plan, trial, data);
        plan.setPlanStatus("执行中");
        plan.setStrategy("EXPAND");
        plan.setFailureOutcome("EXPAND");
        plan.setFailureNote(request.getNote() == null
                ? "已按扩大停暖方式重新试算并生成新关断序列，继续现场执行" : request.getNote());
        plan.setFailureTime(LocalDateTime.now());
        plan.setUpdatedTime(LocalDateTime.now());
        planRepository.save(plan);
        return toVO(plan, loadData(), trial);
    }

    private IsolationStep reverseStep(Long planId, int no, IsolationStep origin, String action,
                                      String targetState, String remark) {
        IsolationStep s = new IsolationStep();
        s.setPlanId(planId);
        s.setStepNo(no);
        s.setValveWellId(origin.getValveWellId());
        s.setWellNo(origin.getWellNo());
        s.setNodeNo(origin.getNodeNo());
        s.setActionType(action);
        s.setPlannedState(targetState);
        s.setStateBefore(origin.getObservedState());
        s.setStepStatus(IsolationStep.ST_PENDING);
        s.setRemark(remark);
        return s;
    }

    // ==================================================================
    // 复供：按当前阀门实况重新生成顺序；现场另行调整的阀不被旧方案覆盖
    // ==================================================================

    @Transactional
    public IsolationPlanVO restoreTrial(Long parentId, String operator) {
        IsolationPlan parent = mustPlan(parentId);
        if (!"已隔离".equals(parent.getPlanStatus())) {
            throw new BizException("仅「已隔离」方案可发起复供试算，当前：" + parent.getPlanStatus());
        }
        TopologyData data = loadData();
        Map<Long, String> baseline = readStateMap(parent.getBaselineSnapshotJson());

        // 旧方案计划过的阀及其目标态（从步骤推导）
        Map<Long, String> oldPlanTarget = new LinkedHashMap<>();
        for (IsolationStep s : stepRepository.findByPlanIdOrderByStepNoAsc(parentId)) {
            if (s.getValveWellId() != null && s.getPlannedState() != null
                    && IsolationStep.ST_DONE.equals(s.getStepStatus())) {
                oldPlanTarget.put(s.getValveWellId(), s.getPlannedState());
            }
        }

        List<IsolationStep> steps = new ArrayList<>();
        List<ValvePlanVO> valveVos = new ArrayList<>();
        List<Map<String, String>> kept = new ArrayList<>();
        Set<Long> openIds = new LinkedHashSet<>();
        Set<Long> closeIds = new LinkedHashSet<>();
        int no = 1;

        for (Map.Entry<Long, String> en : baseline.entrySet()) {
            ValveWell w = data.wellById.get(en.getKey());
            if (w == null) {
                continue;
            }
            String original = en.getValue();
            String live = w.getOpenState();
            if (Objects.equals(original, live)) {
                continue;
            }
            boolean fieldAdjusted = oldPlanTarget.containsKey(w.getId())
                    && !Objects.equals(live, oldPlanTarget.get(w.getId()));
            ValvePlanVO vo = new ValvePlanVO();
            vo.setValveId(w.getId());
            vo.setWellNo(w.getWellNo());
            vo.setNodeNo(w.getNodeNo());
            vo.setSegmentId(w.getSegment() == null ? null : w.getSegment().getId());
            vo.setCaliberDn(w.getValveCaliberDn());
            vo.setBaselineState(original);
            vo.setTargetState(original);
            vo.setOperable(!Boolean.FALSE.equals(w.getOperable()));
            vo.setEgressExit(Boolean.TRUE.equals(w.getEgressExit()));
            if (fieldAdjusted) {
                // 现场另行调整：保留实况，绝不生成操作步骤
                vo.setCategory("现场保留");
                vo.setTargetState(live);
                vo.setManual(true);
                vo.setRemark("现场另行调整，复供不予覆盖，保持「" + live + "」");
                Map<String, String> kv = new LinkedHashMap<>();
                kv.put("wellNo", w.getWellNo());
                kv.put("reason", vo.getRemark());
                kept.add(kv);
                valveVos.add(vo);
                continue;
            }
            if ("关闭".equals(original)) {
                vo.setCategory("PLANNED_CLOSE");
                closeIds.add(w.getId());
            } else {
                vo.setCategory("MUST_OPEN");
                openIds.add(w.getId());
            }
            valveVos.add(vo);
        }

        // 先开后关
        for (Long id : byWellNo(openIds, data)) {
            ValveWell w = data.wellById.get(id);
            IsolationStep s = new IsolationStep();
            s.setStepNo(no++);
            s.setValveWellId(w.getId());
            s.setWellNo(w.getWellNo());
            s.setNodeNo(w.getNodeNo());
            s.setActionType(IsolationStep.TYPE_OPEN);
            s.setValveCategory(IsolationStep.CAT_MUST_OPEN);
            s.setPlannedState(baseline.get(id));
            s.setStateBefore(w.getOpenState());
            s.setStepStatus(IsolationStep.ST_PENDING);
            s.setRemark("复供：按隔离前状态开启（先开后关）");
            steps.add(s);
        }
        for (Long id : byWellNo(closeIds, data)) {
            ValveWell w = data.wellById.get(id);
            IsolationStep s = new IsolationStep();
            s.setStepNo(no++);
            s.setValveWellId(w.getId());
            s.setWellNo(w.getWellNo());
            s.setNodeNo(w.getNodeNo());
            s.setActionType(IsolationStep.TYPE_CLOSE);
            s.setValveCategory(IsolationStep.CAT_PLANNED_CLOSE);
            s.setPlannedState("关闭");
            s.setStateBefore(w.getOpenState());
            s.setStepStatus(IsolationStep.ST_PENDING);
            s.setRemark("复供：关回隔离前即关闭的阀");
            steps.add(s);
        }

        // 复供可达性：故障段已修复重新投入，不摘除；基线取隔离前保供情况
        Traversal restoreT = new Traversal();
        closeIds.forEach(restoreT::close);
        openIds.forEach(restoreT::open);
        Map<Long, Boolean> parentBaseline = new HashMap<>();
        for (StationImpactVO v : readList(parent.getAffectedJson(), StationImpactVO.class)) {
            parentBaseline.put(v.getStationId(), v.isBaselineSupplied());
        }
        List<StationImpactVO> impacts = buildImpacts(data, restoreT, null, parentBaseline);
        List<HeatStation> stillBad = impacts.stream()
                .filter(v -> "运行".equals(v.getStatus()) && !v.isTargetSupplied())
                .map(v -> data.stationById.get(v.getStationId())).filter(Objects::nonNull)
                .collect(Collectors.toList());
        String restoreNote = stillBad.isEmpty()
                ? "复供证明通过：所有运行换热站均已恢复供热路径"
                : "复供后仍不可达的换热站（留异常清单）："
                        + stillBad.stream().map(HeatStation::getCode).collect(Collectors.joining("、"))
                        + "；请现场排查阀门与管段";

        IsolationStep proof = new IsolationStep();
        proof.setStepNo(no);
        proof.setActionType(IsolationStep.TYPE_PROOF);
        proof.setStepStatus(IsolationStep.ST_PENDING);
        proof.setRemark(restoreNote);
        steps.add(proof);

        Map<Long, String> liveNow = new LinkedHashMap<>();
        for (ValveWell w : data.wells) {
            liveNow.put(w.getId(), w.getOpenState());
        }

        IsolationPlan restore = new IsolationPlan();
        restore.setPlanNo(generateNo("FG"));
        restore.setPlanType("RESTORE");
        restore.setParentPlanId(parent.getId());
        restore.setFaultSegmentId(parent.getFaultSegmentId());
        restore.setFaultReason("复供：" + parent.getPlanNo());
        restore.setPlanStatus("复供试算");
        restore.setStrategy(null);
        restore.setProofPassed(true);
        restore.setProofDetail(restoreNote);
        restore.setRestoreProofDetail(restoreNote);
        restore.setCreatedBy(operator);
        restore.setBaselineSnapshotJson(write(liveNow));
        restore.setValveSnapshotJson(write(valveVos));
        restore.setAffectedJson(write(impacts));
        restore.setSupplyPathsJson(write(buildSupplyPaths(data.graph, data, restoreT,
                TopologyEngine.reachable(data.graph, restoreT))));
        restore.setDeviationJson(write(kept));
        restore.setCreatedTime(LocalDateTime.now());
        restore.setUpdatedTime(LocalDateTime.now());
        restore = planRepository.save(restore);
        saveSteps(restore.getId(), steps);
        return buildVO(restore);
    }

    // ==================================================================
    // 查询
    // ==================================================================

    @Transactional(readOnly = true)
    public List<IsolationPlanVO> list() {
        TopologyData data = loadData();
        List<IsolationPlanVO> result = new ArrayList<>();
        for (IsolationPlan plan : planRepository.findAllByOrderByCreatedTimeDesc()) {
            result.add(toVO(plan, data, null));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public IsolationPlanVO detail(Long id) {
        return buildVO(mustPlan(id));
    }

    @Transactional(readOnly = true)
    public IsolationPlanVO buildVO(IsolationPlan plan) {
        return toVO(plan, loadData(), null);
    }

    // ==================================================================
    // 试算核心
    // ==================================================================

    /**
     * @param liveOverride 基线时刻阀门状态覆盖（key=阀门id，value=开关状态），用于失败重算 /
     *                     启用时以现场实况为基线；空 map 表示用数据库实况
     */
    private TrialResult computeTrial(TopologyData data, long faultSegmentId, String strategy,
                                     Set<Long> manualClose, Set<Long> manualOpen, Set<Long> removed,
                                     Map<Long, String> liveOverride) {
        // 人工取舍冲突：同一阀不得既要求关闭又要求开启
        Set<Long> conflict = new LinkedHashSet<>(manualClose);
        conflict.retainAll(manualOpen);
        if (!conflict.isEmpty()) {
            throw new BizException("同一阀门不能同时要求关闭与开启："
                    + conflict.stream().map(id -> {
                        ValveWell w = data.wellById.get(id);
                        return w == null ? String.valueOf(id) : w.getWellNo();
                    }).collect(Collectors.joining("、")));
        }
        Graph g = buildGraphWithOverride(data, liveOverride);
        // 基线（无任何方案动作）可达
        Set<String> baseReach = TopologyEngine.reachable(g, new Traversal());

        BoundaryResult boundary = TopologyEngine.searchBoundary(g, faultSegmentId, strategy,
                manualClose, manualOpen);

        Set<Long> closeIds = new LinkedHashSet<>(manualClose);
        Set<Long> openIds = new LinkedHashSet<>(manualOpen);
        Map<Long, Integer> layerMap = new HashMap<>();
        Set<Long> alreadyClosed = new LinkedHashSet<>();
        // 关断阀（计划关闭 / 原本关闭）与必须开启阀（环网保供，边界试算阶段 2/4 得出）
        for (BoundaryValve bv : boundary.valves) {
            if (removed.contains(bv.valve.id)) {
                continue;
            }
            layerMap.put(bv.valve.id, bv.layer);
            if (bv.alreadyClosed) {
                alreadyClosed.add(bv.valve.id);
            } else {
                closeIds.add(bv.valve.id);
            }
        }
        for (Valve mv : boundary.mustOpenValves) {
            if (!removed.contains(mv.id)) {
                openIds.add(mv.id);
                layerMap.putIfAbsent(mv.id, 0);
            }
        }
        closeIds.removeAll(removed);
        alreadyClosed.removeAll(removed);
        openIds.removeAll(removed);

        Traversal target = new Traversal();
        target.faultSegmentId = faultSegmentId;
        union(closeIds, alreadyClosed).forEach(target::close);
        openIds.forEach(target::open);
        Proof proof = TopologyEngine.proveIsolation(g, target, faultSegmentId);
        Set<String> targetReach = TopologyEngine.reachable(g, target);

        TrialResult r = new TrialResult();
        r.graph = g;
        r.boundary = boundary;
        r.proof = proof;
        r.closeIds = closeIds;
        r.alreadyClosed = alreadyClosed;
        r.openIds = openIds;
        r.layerMap = layerMap;
        r.target = target;
        r.baseReach = baseReach;
        r.impacts = buildImpacts(g, data, baseReach, targetReach);
        r.supplyPaths = buildSupplyPaths(g, data, target, targetReach);
        r.steps = buildSteps(g, faultSegmentId, closeIds, alreadyClosed, openIds);

        Set<String> crew = TopologyEngine.defaultCrewNodes(g, faultSegmentId);
        Set<Long> allClose = union(closeIds, alreadyClosed);
        boolean egressOk = TopologyEngine.egressStillOpen(g, faultSegmentId, allClose, crew);
        r.egressNote = egressOk ? "关断序列保留了井下作业人员撤至地面安全出口的路径"
                : "存在关断阀会切断撤离路径，已在该阀前插入人员撤离确认步骤";

        List<String> lost = r.impacts.stream()
                .filter(v -> "新停暖".equals(v.getImpactType()))
                .map(v -> v.getStationCode() + " " + v.getStationName() + "（约 " + v.getHouseholdCount()
                        + " 户、" + area(v.getTotalAreaM2()) + " ㎡）")
                .collect(Collectors.toList());
        r.expandConsequence = lost.isEmpty() ? "扩大隔离后无运行换热站新增停暖"
                : "扩大停暖将影响：" + String.join("、", lost);
        if (!boundary.blockingInoperable.isEmpty()) {
            r.expandConsequence += "；现场不可操作阀 "
                    + boundary.blockingInoperable.stream().map(v -> v.wellNo).distinct()
                            .collect(Collectors.joining("、"))
                    + " 已绕过，关断边界向外扩大";
        }
        String blocked = boundary.blockingDisabledEdges.stream().map(e -> e.segCode)
                .distinct().collect(Collectors.joining("、"));
        if (boundary.boundaryClosed) {
            r.waitAltConsequence = "边界可以闭合" + (lost.isEmpty() ? "，无需等待替代路径"
                    : "，等待期间停暖保持：" + String.join("、", lost));
        } else {
            r.waitAltConsequence = "等待替代路径 / 阀门恢复可操作期间方案不予启用，故障段维持现场管控"
                    + (lost.isEmpty() ? "" : "，停暖保持：" + String.join("、", lost));
        }
        if (!blocked.isEmpty()) {
            r.waitAltConsequence += "；停用管段 " + blocked + " 无法作为绕行路径";
        }
        return r;
    }

    private Graph buildGraphWithOverride(TopologyData data, Map<Long, String> override) {
        if (override == null || override.isEmpty()) {
            return data.graph;
        }
        List<ValveWell> copy = new ArrayList<>();
        for (ValveWell w : data.wells) {
            if (override.containsKey(w.getId())) {
                ValveWell v = new ValveWell();
                v.setId(w.getId());
                v.setWellNo(w.getWellNo());
                v.setNodeNo(w.getNodeNo());
                v.setOpenState(override.get(w.getId()));
                v.setRepairStatus(w.getRepairStatus());
                v.setOperable(w.getOperable());
                v.setEgressExit(w.getEgressExit());
                v.setValveCaliberDn(w.getValveCaliberDn());
                v.setSegment(w.getSegment());
                copy.add(v);
            } else {
                copy.add(w);
            }
        }
        return TopologyEngine.build(data.segments, copy, data.sources, data.stations);
    }

    private TrialResult computeFromLive(TopologyData data, IsolationPlan plan) {
        Map<Long, String> live = new HashMap<>();
        for (ValveWell w : data.wells) {
            live.put(w.getId(), w.getOpenState());
        }
        return computeTrial(data, plan.getFaultSegmentId(),
                plan.getStrategy() == null ? "EXPAND" : plan.getStrategy(),
                new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), live);
    }

    private List<IsolationStep> buildSteps(Graph g, long faultSegmentId, Set<Long> closeIds,
                                           Set<Long> alreadyClosed, Set<Long> openIds) {
        List<IsolationStep> steps = new ArrayList<>();
        int no = 1;

        // 1) 先核对原本关闭的阀（只核对、不操作）
        for (Long id : byWellNo(alreadyClosed, g)) {
            Valve v = g.valveById.get(id);
            if (v == null) {
                continue;
            }
            IsolationStep s = valveStep(v, IsolationStep.TYPE_CHECK_CLOSED,
                    IsolationStep.CAT_ALREADY_CLOSED, "关闭");
            s.setStepNo(no++);
            s.setStateBefore(v.state);
            s.setRemark("方案试算时该阀已关闭，现场核对即可，不计入关阀动作");
            steps.add(s);
        }
        // 2) 必须开启的阀先开（先开保供路径再关）
        for (Long id : byWellNo(openIds, g)) {
            Valve v = g.valveById.get(id);
            if (v == null || alreadyClosed.contains(id)) {
                continue;
            }
            IsolationStep s = valveStep(v, IsolationStep.TYPE_OPEN, IsolationStep.CAT_MUST_OPEN, "全开");
            s.setStepNo(no++);
            s.setStateBefore(v.state);
            s.setRemark("必须开启：环网替代保供路径上的阀门，先开后关");
            steps.add(s);
        }

        // 3) 计划关闭：识别「切断撤离路径的最后一台阀」，其前插撤离确认；其余阀先关
        Set<String> crew = TopologyEngine.defaultCrewNodes(g, faultSegmentId);
        List<Valve> closeOrdered = closeIds.stream().map(g.valveById::get).filter(Objects::nonNull)
                .sorted(Comparator.comparingInt((Valve v) ->
                        g.edgeBySegment.get(v.segmentId).segmentId == faultSegmentId ? 0 : 1)
                        .thenComparing(v -> v.wellNo))
                .collect(Collectors.toList());
        Valve breaker = null;
        Set<Long> accumulated = new HashSet<>(alreadyClosed);
        for (Valve v : closeOrdered) {
            Set<Long> trial = new HashSet<>(accumulated);
            trial.add(v.id);
            if (!TopologyEngine.egressStillOpen(g, faultSegmentId, trial, crew)) {
                breaker = v;
                break;
            }
            accumulated.add(v.id);
        }
        for (Valve v : closeOrdered) {
            if (breaker != null && v.id == breaker.id) {
                continue;
            }
            IsolationStep s = valveStep(v, IsolationStep.TYPE_CLOSE,
                    IsolationStep.CAT_PLANNED_CLOSE, "关闭");
            s.setStepNo(no++);
            s.setStateBefore(v.state);
            steps.add(s);
        }
        if (breaker != null) {
            IsolationStep evac = new IsolationStep();
            evac.setStepNo(no++);
            evac.setActionType(IsolationStep.TYPE_EVACUATE);
            evac.setNodeNo(breaker.nodeNo);
            evac.setWellNo(breaker.wellNo);
            evac.setStepStatus(IsolationStep.ST_PENDING);
            evac.setEgressRequired(true);
            evac.setRemark("关闭 " + breaker.wellNo + " 将切断故障侧撤至地面的唯一通道，"
                    + "必须先确认井下作业人员全部撤离");
            steps.add(evac);

            IsolationStep s = valveStep(breaker, IsolationStep.TYPE_CLOSE,
                    IsolationStep.CAT_PLANNED_CLOSE, "关闭");
            s.setStepNo(no++);
            s.setStateBefore(breaker.state);
            steps.add(s);
        }

        // 4) 隔离验证
        IsolationStep proof = new IsolationStep();
        proof.setStepNo(no);
        proof.setActionType(IsolationStep.TYPE_PROOF);
        proof.setStepStatus(IsolationStep.ST_PENDING);
        proof.setRemark("全部阀门到位后，重新证明故障段已与运行管网隔开");
        steps.add(proof);
        return steps;
    }

    private IsolationStep valveStep(Valve v, String action, String category, String plannedState) {
        IsolationStep s = new IsolationStep();
        s.setValveWellId(v.id);
        s.setWellNo(v.wellNo);
        s.setNodeNo(v.nodeNo);
        s.setActionType(action);
        s.setValveCategory(category);
        s.setPlannedState(plannedState);
        s.setStepStatus(IsolationStep.ST_PENDING);
        return s;
    }

    // ==================================================================
    // 受影响换热站 / 保供路径
    // ==================================================================

    private List<StationImpactVO> buildImpacts(Graph g, TopologyData data,
                                               Set<String> baseReach, Set<String> targetReach) {
        Set<Long> baseSupplied = TopologyEngine.suppliedStationIds(g, baseReach);
        Set<Long> targetSupplied = TopologyEngine.suppliedStationIds(g, targetReach);
        Map<Long, long[]> stats = residentStats();
        List<StationImpactVO> result = new ArrayList<>();
        for (HeatStation st : data.stations) {
            if (st.getFeedWellNo() == null) {
                continue;
            }
            StationImpactVO vo = new StationImpactVO();
            vo.setStationId(st.getId());
            vo.setStationCode(st.getCode());
            vo.setStationName(st.getName());
            vo.setFeedWellNo(st.getFeedWellNo());
            vo.setStatus(st.getStatus());
            vo.setAreaWanM2(st.getAreaWanM2());
            boolean running = "运行".equals(st.getStatus());
            boolean before = baseSupplied.contains(st.getId());
            boolean after = targetSupplied.contains(st.getId());
            vo.setBaselineSupplied(before);
            vo.setTargetSupplied(after);
            long[] arr = stats.getOrDefault(st.getId(), new long[]{0L, 0L});
            vo.setHouseholdCount(arr[0]);
            vo.setTotalAreaM2(BigDecimal.valueOf(arr[1]).movePointLeft(2));
            if (!running) {
                vo.setImpactType("原本即停");
                vo.setNote("换热站状态为「" + st.getStatus() + "」，与本次隔离无关");
            } else if (before && after) {
                vo.setImpactType("维持保供");
                vo.setNote("环网多路径仍有供热路径，不得判定为停暖");
            } else if (before && !after) {
                vo.setImpactType("新停暖");
                vo.setNote("关断后失去全部运行热源路径");
            } else if (!before && after) {
                vo.setImpactType("恢复保供");
                vo.setNote("替代路径开启后恢复供热");
            } else {
                vo.setImpactType("原本即停");
                vo.setNote("试算前即无供热路径");
            }
            result.add(vo);
        }
        result.sort(Comparator.comparing(StationImpactVO::getStationCode));
        return result;
    }

    /** 复供 / 现场一致性用：基线取方案快照 */
    private List<StationImpactVO> buildImpacts(TopologyData data, Traversal live, Long removedFaultId,
                                               Map<Long, Boolean> baselineSupplied) {
        Graph g = data.graph;
        Traversal t = new Traversal();
        t.forceClosed.addAll(live.forceClosed);
        t.forceOpen.addAll(live.forceOpen);
        t.faultSegmentId = removedFaultId;
        Set<String> reach = TopologyEngine.reachable(g, t);
        Set<Long> supplied = TopologyEngine.suppliedStationIds(g, reach);
        Map<Long, long[]> stats = residentStats();
        List<StationImpactVO> result = new ArrayList<>();
        for (HeatStation st : data.stations) {
            if (st.getFeedWellNo() == null) {
                continue;
            }
            StationImpactVO vo = new StationImpactVO();
            vo.setStationId(st.getId());
            vo.setStationCode(st.getCode());
            vo.setStationName(st.getName());
            vo.setFeedWellNo(st.getFeedWellNo());
            vo.setStatus(st.getStatus());
            vo.setAreaWanM2(st.getAreaWanM2());
            boolean before = baselineSupplied.getOrDefault(st.getId(), false);
            boolean after = supplied.contains(st.getId());
            vo.setBaselineSupplied(before);
            vo.setTargetSupplied(after);
            long[] arr = stats.getOrDefault(st.getId(), new long[]{0L, 0L});
            vo.setHouseholdCount(arr[0]);
            vo.setTotalAreaM2(BigDecimal.valueOf(arr[1]).movePointLeft(2));
            if (!"运行".equals(st.getStatus())) {
                vo.setImpactType("原本即停");
            } else if (before && after) {
                vo.setImpactType("维持保供");
            } else if (before && !after) {
                vo.setImpactType("新停暖");
            } else if (!before && after) {
                vo.setImpactType("恢复保供");
            } else {
                vo.setImpactType("原本即停");
            }
            result.add(vo);
        }
        result.sort(Comparator.comparing(StationImpactVO::getStationCode));
        return result;
    }

    private List<SupplyPathVO> buildSupplyPaths(Graph g, TopologyData data, Traversal target,
                                                Set<String> targetReach) {
        List<SupplyPathVO> paths = new ArrayList<>();
        Set<Long> supplied = TopologyEngine.suppliedStationIds(g, targetReach);
        Map<String, String> sourceName = new HashMap<>();
        for (HeatSource s : data.sources) {
            if (s.getOutletWellNo() != null) {
                sourceName.put(s.getOutletWellNo(), s.getName());
            }
        }
        List<String> opens = target.forceOpen.stream()
                .map(id -> g.valveById.get(id)).filter(Objects::nonNull).map(v -> v.wellNo)
                .sorted().collect(Collectors.toList());
        for (HeatStation st : data.stations) {
            if (!"运行".equals(st.getStatus()) || st.getFeedWellNo() == null
                    || !supplied.contains(st.getId())) {
                continue;
            }
            List<String> nodes = TopologyEngine.shortestPath(g, target, st.getFeedWellNo());
            if (nodes == null || nodes.isEmpty()) {
                continue;
            }
            SupplyPathVO vo = new SupplyPathVO();
            vo.setStationId(st.getId());
            vo.setStationCode(st.getCode());
            vo.setStationName(st.getName());
            vo.setNodes(nodes);
            vo.setSourceName(sourceName.getOrDefault(nodes.get(0), nodes.get(0)));
            vo.setMustOpenValves(opens);
            vo.setAlternative(!opens.isEmpty());
            paths.add(vo);
        }
        paths.sort(Comparator.comparing(SupplyPathVO::getStationCode));
        return paths;
    }

    /** @return stationId -> [正常供热户数, 面积（0.01㎡）] */
    private Map<Long, long[]> residentStats() {
        Map<Long, long[]> map = new HashMap<>();
        for (Resident r : residentRepository.findAll()) {
            if (r.getStation() == null || !"正常供热".equals(r.getSupplyStatus())) {
                continue;
            }
            long[] arr = map.computeIfAbsent(r.getStation().getId(), k -> new long[]{0L, 0L});
            arr[0] += 1;
            if (r.getAreaM2() != null) {
                arr[1] += r.getAreaM2().multiply(BigDecimal.valueOf(100)).longValue();
            }
        }
        return map;
    }

    // ==================================================================
    // 快照 / VO
    // ==================================================================

    private void fillPlanFromTrial(IsolationPlan plan, TrialResult r, TopologyData data) {
        plan.setProofPassed(r.proof.passed);
        plan.setProofDetail(r.proof.detail);
        plan.setSupplyPathsJson(write(r.supplyPaths));
        plan.setAffectedJson(write(r.impacts));

        Map<Long, String> baseline = new LinkedHashMap<>();
        List<ValvePlanVO> vos = new ArrayList<>();
        Set<Long> all = new LinkedHashSet<>();
        all.addAll(r.closeIds);
        all.addAll(r.alreadyClosed);
        all.addAll(r.openIds);
        for (Long id : byWellNo(all, r.graph)) {
            Valve v = r.graph.valveById.get(id);
            if (v == null) {
                continue;
            }
            ValvePlanVO vo = new ValvePlanVO();
            vo.setValveId(id);
            vo.setWellNo(v.wellNo);
            vo.setNodeNo(v.nodeNo);
            vo.setSegmentId(v.segmentId);
            ValveWell well = data.wellById.get(id);
            vo.setCaliberDn(well == null ? null : well.getValveCaliberDn());
            PipeSegment segOf = data.segmentById.get(v.segmentId);
            vo.setSegCode(segOf == null ? null : segOf.getSegCode());
            vo.setBaselineState(v.state);
            vo.setOperable(v.operable);
            vo.setEgressExit(v.egressExit);
            vo.setBoundaryLayer(r.layerMap.get(id));
            baseline.put(id, v.state);
            if (r.closeIds.contains(id)) {
                vo.setCategory("PLANNED_CLOSE");
                vo.setTargetState("关闭");
            } else if (r.alreadyClosed.contains(id)) {
                vo.setCategory("ALREADY_CLOSED");
                vo.setTargetState("关闭");
            } else {
                vo.setCategory("MUST_OPEN");
                vo.setTargetState("全开");
            }
            vos.add(vo);
        }
        plan.setValveSnapshotJson(write(vos));
        plan.setBaselineSnapshotJson(write(baseline));
        if (plan.getDeviationJson() == null) {
            plan.setDeviationJson("[]");
        }
    }

    private IsolationPlanVO toVO(IsolationPlan plan, TopologyData data, TrialResult fresh) {
        IsolationPlanVO vo = new IsolationPlanVO();
        vo.setId(plan.getId());
        vo.setPlanNo(plan.getPlanNo());
        vo.setPlanType(plan.getPlanType());
        vo.setParentPlanId(plan.getParentPlanId());
        vo.setFaultSegmentId(plan.getFaultSegmentId());
        PipeSegment seg = data.segmentById.get(plan.getFaultSegmentId());
        if (seg != null) {
            vo.setFaultSegCode(seg.getSegCode());
            vo.setFaultSegmentDesc(seg.getStartWellNo() + "→" + seg.getEndWellNo()
                    + " DN" + seg.getDiameterDn() + " " + seg.getLengthM() + "m");
        }
        vo.setFaultReason(plan.getFaultReason());
        vo.setPlanStatus(plan.getPlanStatus());
        vo.setStrategy(plan.getStrategy());
        vo.setProofPassed(plan.getProofPassed());
        vo.setProofDetail(plan.getProofDetail());
        vo.setRestoreProofDetail(plan.getRestoreProofDetail());
        vo.setFailureOutcome(plan.getFailureOutcome());
        vo.setFailureNote(plan.getFailureNote());
        vo.setFailureTime(plan.getFailureTime());
        vo.setCreatedBy(plan.getCreatedBy());
        vo.setCreatedTime(plan.getCreatedTime());
        vo.setEnabledTime(plan.getEnabledTime());
        vo.setIsolatedTime(plan.getIsolatedTime());
        vo.setRestoredTime(plan.getRestoredTime());

        List<ValvePlanVO> valveVos = readList(plan.getValveSnapshotJson(), ValvePlanVO.class);
        for (ValvePlanVO v : valveVos) {
            ValveWell w = data.wellById.get(v.getValveId());
            if (w != null && v.getCaliberDn() == null) {
                v.setCaliberDn(w.getValveCaliberDn());
            }
            if (w != null && v.getSegmentId() == null) {
                v.setSegmentId(w.getSegment() == null ? null : w.getSegment().getId());
            }
        }
        vo.setValves(valveVos);
        vo.setSupplyPaths(readList(plan.getSupplyPathsJson(), SupplyPathVO.class));
        vo.setDeviations(readList(plan.getDeviationJson(), String.class));
        vo.setSteps(stepRepository.findByPlanIdOrderByStepNoAsc(plan.getId()).stream()
                .map(this::stepVo).collect(Collectors.toList()));

        if (fresh != null) {
            vo.setAffectedStations(fresh.impacts);
            vo.setExpandConsequence(fresh.expandConsequence);
            vo.setWaitAltConsequence(fresh.waitAltConsequence);
            vo.setEgressNote(fresh.egressNote);
            vo.setInoperableValves(fresh.boundary.blockingInoperable.stream()
                    .map(v -> v.wellNo).distinct().collect(Collectors.toList()));
        } else {
            vo.setAffectedStations(readList(plan.getAffectedJson(), StationImpactVO.class));
        }

        Consistency c = checkLiveConsistency(plan, data);
        vo.setLiveConsistent(c.consistent);
        vo.setLiveConsistentNote(c.note);
        if (c.impacts != null) {
            vo.setAffectedStations(c.impacts);
        }
        return vo;
    }

    private IsolationStepVO stepVo(IsolationStep s) {
        IsolationStepVO vo = new IsolationStepVO();
        vo.setId(s.getId());
        vo.setStepNo(s.getStepNo());
        vo.setValveId(s.getValveWellId());
        vo.setWellNo(s.getWellNo());
        vo.setNodeNo(s.getNodeNo());
        vo.setActionType(s.getActionType());
        vo.setActionLabel(actionLabel(s));
        vo.setValveCategory(s.getValveCategory());
        vo.setPlannedState(s.getPlannedState());
        vo.setStateBefore(s.getStateBefore());
        vo.setObservedState(s.getObservedState());
        vo.setStepStatus(s.getStepStatus());
        vo.setEgressRequired(s.getEgressRequired());
        vo.setDeviationReason(s.getDeviationReason());
        vo.setCompletedBy(s.getCompletedBy());
        vo.setCompletedTime(s.getCompletedTime());
        vo.setRemark(s.getRemark());
        return vo;
    }

    static String actionLabel(IsolationStep s) {
        if (s.getActionType() == null) {
            return "";
        }
        switch (s.getActionType()) {
            case IsolationStep.TYPE_CLOSE: return "关闭阀门 " + s.getWellNo();
            case IsolationStep.TYPE_OPEN: return "开启阀门 " + s.getWellNo();
            case IsolationStep.TYPE_CHECK_CLOSED: return "核对原关阀 " + s.getWellNo();
            case IsolationStep.TYPE_EVACUATE: return "确认井下人员撤离";
            case IsolationStep.TYPE_PROOF: return "隔离 / 复供验证";
            default: return s.getActionType();
        }
    }

    // ==================================================================
    // 方案状态 / 阀门实况 / 受影响清单 三方一致性
    // ==================================================================

    private static final class Consistency {
        boolean consistent = true;
        String note;
        List<StationImpactVO> impacts;
    }

    private Consistency checkLiveConsistency(IsolationPlan plan, TopologyData data) {
        Consistency c = new Consistency();
        c.note = "草稿状态：启用后按现场实况进行三方一致性校验";
        if (List.of("草稿").contains(plan.getPlanStatus())) {
            return c;
        }
        boolean restore = "RESTORE".equals(plan.getPlanType());
        Traversal live = new Traversal();
        if (!restore && plan.getFaultSegmentId() != null) {
            live.faultSegmentId = plan.getFaultSegmentId();
        }
        // 快照里的基线保供标记
        Map<Long, Boolean> baseSupplied = new HashMap<>();
        for (StationImpactVO v : readList(plan.getAffectedJson(), StationImpactVO.class)) {
            baseSupplied.put(v.getStationId(), v.isBaselineSupplied());
        }
        List<StationImpactVO> impacts = buildImpacts(data, live, live.faultSegmentId, baseSupplied);
        c.impacts = impacts;

        // 已完成步骤对应的阀，实况应等于计划到位态（否则就是被他人改动，方案与实况不一致）
        List<String> drift = new ArrayList<>();
        for (IsolationStep s : stepRepository.findByPlanIdOrderByStepNoAsc(plan.getId())) {
            if (s.getValveWellId() == null || s.getPlannedState() == null
                    || !IsolationStep.ST_DONE.equals(s.getStepStatus())
                    || IsolationStep.TYPE_CHECK_CLOSED.equals(s.getActionType())) {
                continue;
            }
            ValveWell w = data.wellById.get(s.getValveWellId());
            if (w != null && !Objects.equals(w.getOpenState(), s.getPlannedState())) {
                drift.add(s.getWellNo() + " 现「" + w.getOpenState() + "」≠ 方案到位态「"
                        + s.getPlannedState() + "」");
            }
        }
        if (drift.isEmpty()) {
            c.consistent = true;
            c.note = "方案状态、阀门实况与受影响换热站清单一致";
        } else {
            c.consistent = false;
            c.note = "存在现场偏差：" + String.join("；", drift) + "；受影响清单已按实况重算，可重新试算";
        }
        return c;
    }

    // ==================================================================
    // 杂项
    // ==================================================================

    private void pauseRemaining(List<IsolationStep> steps, int fromNoInclusive) {
        for (IsolationStep s : steps) {
            if (s.getStepNo() >= fromNoInclusive && IsolationStep.ST_PENDING.equals(s.getStepStatus())) {
                s.setStepStatus(IsolationStep.ST_PAUSED);
                if (s.getDeviationReason() == null) {
                    s.setDeviationReason("前序步骤未完成 / 出现偏差，后续步骤暂停");
                }
            }
        }
    }

    private void freezeToFailure(IsolationPlan plan, String outcome, String note) {
        plan.setPlanStatus("失败待处置");
        plan.setFailureOutcome(outcome);
        plan.setFailureNote(note);
        plan.setFailureTime(LocalDateTime.now());
        plan.setUpdatedTime(LocalDateTime.now());
        planRepository.save(plan);
    }

    private void appendDeviation(IsolationPlan plan, String text) {
        List<String> deviations = readList(plan.getDeviationJson(), String.class);
        deviations.add(LocalDateTime.now().withNano(0).toString() + " " + text);
        plan.setDeviationJson(write(deviations));
    }

    private void saveSteps(Long planId, List<IsolationStep> steps) {
        for (IsolationStep s : steps) {
            s.setPlanId(planId);
        }
        stepRepository.saveAll(steps);
    }

    private IsolationPlan mustPlan(Long id) {
        return planRepository.findById(id)
                .orElseThrow(() -> new BizException("隔离方案不存在：id=" + id));
    }

    private PipeSegment mustFault(TopologyData data, Long id) {
        PipeSegment seg = data.segmentById.get(id);
        if (seg == null) {
            throw new BizException("故障管段不存在：id=" + id);
        }
        return seg;
    }

    private Set<Long> idSet(List<Long> list) {
        return list == null ? new LinkedHashSet<>() : new LinkedHashSet<>(list);
    }

    private Set<Long> union(Set<Long> a, Set<Long> b) {
        Set<Long> s = new LinkedHashSet<>(a);
        s.addAll(b);
        return s;
    }

    private List<Long> byWellNo(Set<Long> ids, Graph g) {
        List<Long> list = new ArrayList<>(ids);
        list.sort(Comparator.comparing(id -> {
            Valve v = g.valveById.get(id);
            return v == null ? "" : v.wellNo;
        }));
        return list;
    }

    private List<Long> byWellNo(Set<Long> ids, TopologyData data) {
        List<Long> list = new ArrayList<>(ids);
        list.sort(Comparator.comparing(id -> {
            ValveWell w = data.wellById.get(id);
            return w == null ? "" : w.getWellNo();
        }));
        return list;
    }

    private String generateNo(String prefix) {
        return prefix + LocalDateTime.now().format(NO_FMT)
                + String.format("%03d", (int) (Math.random() * 900) + 100);
    }

    private String area(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private String appendText(String origin, String add) {
        return origin == null ? add : origin + "；" + add;
    }

    private String write(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new BizException("方案快照序列化失败：" + e.getMessage());
        }
    }

    private <T> List<T> readList(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, type));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Map<Long, String> readStateMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, String> raw = objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
            Map<Long, String> map = new LinkedHashMap<>();
            raw.forEach((k, v) -> {
                try {
                    map.put(Long.parseLong(k), v);
                } catch (NumberFormatException ignore) {
                    // 非 Long->String 形态的快照（阀门 VO 列表）忽略
                }
            });
            return map;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private TopologyData loadData() {
        TopologyData data = new TopologyData();
        data.segments = segmentRepository.findAll();
        data.wells = valveWellRepository.findAll();
        data.sources = sourceRepository.findAll();
        data.stations = stationRepository.findAll();
        for (PipeSegment s : data.segments) {
            data.segmentById.put(s.getId(), s);
        }
        for (ValveWell w : data.wells) {
            data.wellById.put(w.getId(), w);
        }
        for (HeatSource s : data.sources) {
            data.sourceById.put(s.getId(), s);
        }
        for (HeatStation s : data.stations) {
            data.stationById.put(s.getId(), s);
        }
        data.graph = TopologyEngine.build(data.segments, data.wells, data.sources, data.stations);
        return data;
    }

    private static final class TopologyData {
        List<PipeSegment> segments = new ArrayList<>();
        List<ValveWell> wells = new ArrayList<>();
        List<HeatSource> sources = new ArrayList<>();
        List<HeatStation> stations = new ArrayList<>();
        final Map<Long, PipeSegment> segmentById = new LinkedHashMap<>();
        final Map<Long, ValveWell> wellById = new LinkedHashMap<>();
        final Map<Long, HeatSource> sourceById = new LinkedHashMap<>();
        final Map<Long, HeatStation> stationById = new LinkedHashMap<>();
        Graph graph;
    }

    private static final class TrialResult {
        Graph graph;
        BoundaryResult boundary;
        Proof proof;
        Set<Long> closeIds;
        Set<Long> alreadyClosed;
        Set<Long> openIds;
        Map<Long, Integer> layerMap;
        Traversal target;
        Set<String> baseReach;
        List<StationImpactVO> impacts;
        List<SupplyPathVO> supplyPaths;
        List<IsolationStep> steps;
        String expandConsequence;
        String waitAltConsequence;
        String egressNote;
    }
}
