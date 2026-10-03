package com.heat.supply.dto;

import lombok.Data;

/** 方案中的阀门：试算要明确区分计划关闭 / 原本关闭 / 必须开启 */
@Data
public class ValvePlanVO {

    private Long valveId;
    private String wellNo;
    private String nodeNo;
    private Long segmentId;
    private String segCode;
    private Integer caliberDn;

    /** 试算时刻实况：全开 / 半开 / 关闭 */
    private String baselineState;
    /** 方案目标态：关闭 / 全开 / 半开 */
    private String targetState;
    /** PLANNED_CLOSE 计划关闭 / ALREADY_CLOSED 原本关闭 / MUST_OPEN 必须开启 */
    private String category;
    /** 现场是否可操作 */
    private Boolean operable;
    /** 是否安全撤离通道口 */
    private Boolean egressExit;
    /** 是否人工调整加入 / 剔除 */
    private Boolean manual;
    private Integer boundaryLayer;
    private String remark;
}
