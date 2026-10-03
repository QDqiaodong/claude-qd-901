package com.heat.supply.dto;

import java.util.List;

import lombok.Data;

/** 可保供路径：运行热源出厂井 → … → 换热站接入井 */
@Data
public class SupplyPathVO {

    private Long stationId;
    private String stationCode;
    private String stationName;
    private String sourceName;
    /** 井节点序列 */
    private List<String> nodes;
    /** 该路径上需要开启的阀门（必须开启类） */
    private List<String> mustOpenValves;
    /** 是否替代（环网绕行）路径 */
    private boolean alternative;
}
