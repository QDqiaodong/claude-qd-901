package com.heat.supply.dto;

import lombok.Data;

/** 现场逐步确认请求 */
@Data
public class IsolationStepConfirmRequest {

    /** 现场反馈状态：关闭 / 全开 / 半开 */
    private String observedState;

    /** 偏差原因：反馈与计划不符 / 阀门已被他人改变 等 */
    private String deviationReason;

    private String operator;

    private String remark;
}
