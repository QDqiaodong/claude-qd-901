package com.heat.supply.dto;

import java.time.LocalDateTime;

import lombok.Data;

/** 关断 / 复供序列中的一步 */
@Data
public class IsolationStepVO {

    private Long id;
    private Integer stepNo;
    private Long valveId;
    private String wellNo;
    private String nodeNo;
    /** CLOSE / OPEN / CHECK_CLOSED / EVACUATE / PROOF */
    private String actionType;
    private String actionLabel;
    /** PLANNED_CLOSE / ALREADY_CLOSED / MUST_OPEN */
    private String valveCategory;
    private String plannedState;
    private String stateBefore;
    private String observedState;
    /** 待执行 / 已完成 / 已跳过 / 已暂停 / 偏差挂起 */
    private String stepStatus;
    private Boolean egressRequired;
    private String deviationReason;
    private String completedBy;
    private LocalDateTime completedTime;
    private String remark;
}
