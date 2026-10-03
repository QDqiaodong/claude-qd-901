package com.heat.supply.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 故障管段隔离试算请求 */
@Data
public class IsolationTrialRequest {

    /** 故障管段 id */
    @NotNull(message = "故障管段不能为空")
    private Long faultSegmentId;

    /** 故障 / 隔离原因 */
    private String faultReason;

    /** EXPAND 扩大停暖确保安全 / WAIT_ALT 拒绝启用等待替代路径，默认 EXPAND */
    private String strategy = "EXPAND";

    /** 人工调整：强制纳入关闭的阀门 id（计划关闭） */
    private java.util.List<Long> extraCloseValveIds;

    /** 人工调整：强制纳入开启的阀门 id（必须开启） */
    private java.util.List<Long> extraOpenValveIds;

    /** 人工调整：从关断序列中剔除的阀门 id */
    private java.util.List<Long> removedValveIds;

    private String createdBy;
}
