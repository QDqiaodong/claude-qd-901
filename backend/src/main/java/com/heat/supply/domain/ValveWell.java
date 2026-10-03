package com.heat.supply.domain;

import java.time.LocalDate;

import org.hibernate.annotations.FetchMode;
import org.hibernate.annotations.FetchProfile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 阀门井：井号、位置、阀门口径、开关状态、检修状态，挂接所属管段。
 * 抓取档案 {@link #FETCH_WITH_SEGMENT} 把 segment 改成 JOIN 抓取。
 */
@Entity
@Table(name = "valve_well")
@Getter
@Setter
@FetchProfile(name = ValveWell.FETCH_WITH_SEGMENT,
        fetchOverrides = @FetchProfile.FetchOverride(entity = ValveWell.class,
                association = "segment", mode = FetchMode.JOIN))
public class ValveWell {

    /** 命名抓取档案：查阀门井时把所属管段改成 JOIN 抓取 */
    public static final String FETCH_WITH_SEGMENT = "with-segment";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 井号 */
    @Column(name = "well_no", nullable = false, unique = true, length = 32)
    private String wellNo;

    /** 井节点号：该阀门所在拓扑节点（同节点可挂多台分属不同管段的阀） */
    @Column(name = "node_no", length = 32)
    private String nodeNo;

    @Column(name = "well_location", length = 128)
    private String location;

    /** 阀门口径 DN */
    @Column(name = "valve_caliber_dn")
    private Integer valveCaliberDn;

    /** 开关状态：全开 / 半开 / 关闭 */
    @Column(name = "open_state", length = 16)
    private String openState;

    /** 检修状态：正常 / 检修中 */
    @Column(name = "repair_status", length = 16)
    private String repairStatus;

    /** 现场是否可操作：false 表示阀杆抱死 / 检修拆装 / 无法到位，方案不得安排其操作 */
    @Column(name = "operable")
    private Boolean operable = Boolean.TRUE;

    /** 是否为井下作业人员安全撤离通道口 */
    @Column(name = "egress_exit")
    private Boolean egressExit = Boolean.FALSE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "segment_id")
    private PipeSegment segment;

    @Column(name = "last_check_date")
    private LocalDate lastCheckDate;

    @Column(name = "remark", length = 128)
    private String remark;

    /** 平面示意图坐标 */
    @Column(name = "map_x")
    private Integer mapX;

    @Column(name = "map_y")
    private Integer mapY;
}
