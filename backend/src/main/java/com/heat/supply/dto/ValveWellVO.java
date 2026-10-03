package com.heat.supply.dto;

import java.time.LocalDate;

import lombok.Data;
import lombok.NoArgsConstructor;

/** 阀门井视图对象 */
@Data
@NoArgsConstructor
public class ValveWellVO {

    private Long id;
    private String wellNo;
    private String nodeNo;
    private String location;
    private Integer valveCaliberDn;
    private String openState;
    private String repairStatus;
    /** 现场是否可操作（阀杆抱死 / 检修拆装 / 无法到位） */
    private Boolean operable;
    /** 是否为井下作业人员安全撤离通道口 */
    private Boolean egressExit;
    private Long segmentId;
    private String segCode;
    private String segmentStatus;
    private LocalDate lastCheckDate;
    private String remark;
    private Integer mapX;
    private Integer mapY;
    private Boolean segmentEagerLoaded;

    public ValveWellVO(Long id, String wellNo, String nodeNo, String location, Integer valveCaliberDn,
                       String openState, String repairStatus, Boolean operable, Boolean egressExit,
                       Long segmentId, String segCode, String segmentStatus,
                       LocalDate lastCheckDate, String remark, Integer mapX, Integer mapY,
                       Boolean segmentEagerLoaded) {
        this.id = id;
        this.wellNo = wellNo;
        this.nodeNo = nodeNo;
        this.location = location;
        this.valveCaliberDn = valveCaliberDn;
        this.openState = openState;
        this.repairStatus = repairStatus;
        this.operable = operable;
        this.egressExit = egressExit;
        this.segmentId = segmentId;
        this.segCode = segCode;
        this.segmentStatus = segmentStatus;
        this.lastCheckDate = lastCheckDate;
        this.remark = remark;
        this.mapX = mapX;
        this.mapY = mapY;
        this.segmentEagerLoaded = segmentEagerLoaded;
    }
}
