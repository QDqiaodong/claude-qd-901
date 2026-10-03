package com.heat.supply.dto;

import lombok.Data;

/** 阀门井开关 / 检修状态变更 */
@Data
public class ValveWellUpdateRequest {

    private String openState;
    private String repairStatus;
    /** 井节点号（同节点可挂多台分属不同管段的阀） */
    private String nodeNo;
    /** 现场是否可操作 */
    private Boolean operable;
    /** 是否为安全撤离通道口 */
    private Boolean egressExit;
    private String remark;
}
