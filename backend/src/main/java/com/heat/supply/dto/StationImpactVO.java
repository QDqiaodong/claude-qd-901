package com.heat.supply.dto;

import java.math.BigDecimal;

import lombok.Data;

/** 受影响换热站：由当前阀门状态可达性实时推导，仍有路径的不得误判为停暖 */
@Data
public class StationImpactVO {

    private Long stationId;
    private String stationCode;
    private String stationName;
    private String feedWellNo;
    private String status;
    private BigDecimal areaWanM2;
    /** 基线（试算前）是否有供热路径 */
    private boolean baselineSupplied;
    /** 方案目标态是否仍有供热路径 */
    private boolean targetSupplied;
    /** 影响类型：新停暖 / 维持保供 / 原本即停（检修/停用/本来就不可达） */
    private String impactType;
    private long householdCount;
    private BigDecimal totalAreaM2;
    private String note;
}
