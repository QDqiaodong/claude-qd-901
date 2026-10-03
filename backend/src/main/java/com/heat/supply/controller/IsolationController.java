package com.heat.supply.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.heat.supply.common.ApiResponse;
import com.heat.supply.dto.IsolationFailureRequest;
import com.heat.supply.dto.IsolationPlanVO;
import com.heat.supply.dto.IsolationStepConfirmRequest;
import com.heat.supply.dto.IsolationTrialRequest;
import com.heat.supply.service.isolation.IsolationService;

import jakarta.validation.Valid;

/**
 * 管网隔离方案闭环：
 * 试算 / 人工调整再证明 / 启用 / 现场逐步确认 / 失败三收口 / 复供试算。
 */
@RestController
@RequestMapping("/api/isolation")
public class IsolationController {

    private final IsolationService isolationService;

    public IsolationController(IsolationService isolationService) {
        this.isolationService = isolationService;
    }

    /** 选定故障管段后试算（EXPAND / WAIT_ALT），生成草稿方案 */
    @PostMapping("/trials")
    public ApiResponse<IsolationPlanVO> trial(@Valid @RequestBody IsolationTrialRequest request) {
        return ApiResponse.success(isolationService.trial(request));
    }

    /** 人工调整阀门取舍后重新试算并重新证明 */
    @PostMapping("/plans/{id}/re-trial")
    public ApiResponse<IsolationPlanVO> reTrial(@PathVariable Long id,
                                                @RequestBody IsolationTrialRequest request) {
        return ApiResponse.success(isolationService.reTrial(id, request));
    }

    /** 启用方案（证明未通过 / 等待替代路径策略下边界不闭合 → 拒绝） */
    @PostMapping("/plans/{id}/enable")
    public ApiResponse<IsolationPlanVO> enable(@PathVariable Long id,
                                               @RequestParam(required = false) String operator) {
        return ApiResponse.success(isolationService.enable(id, operator));
    }

    /** 方案列表 */
    @GetMapping("/plans")
    public ApiResponse<List<IsolationPlanVO>> list() {
        return ApiResponse.success(isolationService.list());
    }

    /** 方案详情：阀门分类、关断序列、保供路径、受影响站点、偏差、三方一致性 */
    @GetMapping("/plans/{id}")
    public ApiResponse<IsolationPlanVO> detail(@PathVariable Long id) {
        return ApiResponse.success(isolationService.detail(id));
    }

    /** 现场按序列逐步确认；前一步未完成 / 反馈不符 / 阀门被他人改变 → 后续暂停 */
    @PostMapping("/plans/{planId}/steps/{stepId}/confirm")
    public ApiResponse<IsolationPlanVO> confirmStep(@PathVariable Long planId,
                                                    @PathVariable Long stepId,
                                                    @RequestBody(required = false) IsolationStepConfirmRequest request) {
        return ApiResponse.success(isolationService.confirmStep(planId, stepId,
                request == null ? new IsolationStepConfirmRequest() : request));
    }

    /** 中途失败收口：EXPAND 扩大隔离 / ROLLBACK 安全顺序恢复 / HOLD 保持现场 */
    @PostMapping("/plans/{id}/resolve-failure")
    public ApiResponse<IsolationPlanVO> resolveFailure(@PathVariable Long id,
                                                       @RequestBody IsolationFailureRequest request) {
        return ApiResponse.success(isolationService.resolveFailure(id, request));
    }

    /** 故障修复后按当前阀门实况发起复供试算（现场另行调整的阀不被旧方案覆盖） */
    @PostMapping("/plans/{id}/restore-trial")
    public ApiResponse<IsolationPlanVO> restoreTrial(@PathVariable Long id,
                                                     @RequestParam(required = false) String operator) {
        return ApiResponse.success(isolationService.restoreTrial(id, operator));
    }
}
