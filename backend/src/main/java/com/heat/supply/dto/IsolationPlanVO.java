package com.heat.supply.dto;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

/** 隔离 / 复供方案完整视图：试算结论、阀门分类、关断序列、保供路径、受影响站点、偏差、现场实况一致性 */
@Data
public class IsolationPlanVO {

    private Long id;
    private String planNo;
    /** ISOLATE / RESTORE */
    private String planType;
    private Long parentPlanId;

    private Long faultSegmentId;
    private String faultSegCode;
    private String faultSegmentDesc;
    private String faultReason;

    /** 草稿 / 已启用 / 执行中 / 已隔离 / 失败待处置 / 已撤回 / 复供试算 / 已复供 */
    private String planStatus;
    /** EXPAND / WAIT_ALT */
    private String strategy;

    private Boolean proofPassed;
    private String proofDetail;

    private List<ValvePlanVO> valves;
    private List<IsolationStepVO> steps;
    private List<SupplyPathVO> supplyPaths;
    private List<StationImpactVO> affectedStations;

    /** 扩大停暖后果摘要 */
    private String expandConsequence;
    /** 等待替代路径结论摘要 */
    private String waitAltConsequence;
    /** 现场不可操作阀清单 */
    private List<String> inoperableValves;
    /** 撤离安全结论 */
    private String egressNote;

    // ---- 现场执行 / 偏差 ----
    private List<String> deviations;
    private String failureOutcome;
    private String failureNote;
    private LocalDateTime failureTime;
    /** 当前阀门实况与方案目标态是否一致（方案状态 / 阀门实况 / 受影响清单一致性信号） */
    private Boolean liveConsistent;
    private String liveConsistentNote;

    /** 复供试算后仍不可达的换热站说明 */
    private String restoreProofDetail;

    private String createdBy;
    private LocalDateTime createdTime;
    private LocalDateTime enabledTime;
    private LocalDateTime isolatedTime;
    private LocalDateTime restoredTime;
}
