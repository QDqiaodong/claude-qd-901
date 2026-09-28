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
    private String location;
    private Integer valveCaliberDn;
    private String openState;
    private String repairStatus;
    private Long segmentId;
    private String segCode;
    private String segmentStatus;
    private LocalDate lastCheckDate;
    private String remark;
    private Integer mapX;
    private Integer mapY;
    private Boolean segmentEagerLoaded;

    public ValveWellVO(Long id, String wellNo, String location, Integer valveCaliberDn, String openState,
                       String repairStatus, Long segmentId, String segCode, String segmentStatus,
                       LocalDate lastCheckDate, String remark, Integer mapX, Integer mapY,
                       Boolean segmentEagerLoaded) {
        this.id = id;
        this.wellNo = wellNo;
        this.location = location;
        this.valveCaliberDn = valveCaliberDn;
        this.openState = openState;
        this.repairStatus = repairStatus;
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
