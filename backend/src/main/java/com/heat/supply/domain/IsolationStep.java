package com.heat.supply.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 隔离 / 复供操作步骤：关断序列的最小执行单元。
 *
 * <p>顺序约束：必须开启的阀先开，之后再关；关断顺序不得切断井下人员安全退出路径，
 * 切断退出路径的最后一台阀之前插入 {@link #TYPE_EVACUATE} 撤离确认。
 */
@Entity
@Table(name = "isolation_step")
@Getter
@Setter
public class IsolationStep {

    /** 关闭阀门 */
    public static final String TYPE_CLOSE = "CLOSE";
    /** 开启阀门 */
    public static final String TYPE_OPEN = "OPEN";
    /** 确认原本关闭的阀（方案试算时就已关闭，现场只需核对） */
    public static final String TYPE_CHECK_CLOSED = "CHECK_CLOSED";
    /** 井下作业人员撤离确认 */
    public static final String TYPE_EVACUATE = "EVACUATE";
    /** 全部阀门到位后的隔离验证 */
    public static final String TYPE_PROOF = "PROOF";

    /** 计划关闭（试算时未关闭，需要现场关闭） */
    public static final String CAT_PLANNED_CLOSE = "PLANNED_CLOSE";
    /** 原本关闭（试算时已关闭，只需核对，不计入关阀动作） */
    public static final String CAT_ALREADY_CLOSED = "ALREADY_CLOSED";
    /** 必须开启（替代保供路径上的阀，试算时处于关闭 / 半开） */
    public static final String CAT_MUST_OPEN = "MUST_OPEN";

    /** 待执行 */
    public static final String ST_PENDING = "待执行";
    /** 已完成 */
    public static final String ST_DONE = "已完成";
    /** 已跳过（人工调整 / 复供时现场另行调整） */
    public static final String ST_SKIPPED = "已跳过";
    /** 已暂停（前一步未完成 / 反馈不符 / 阀门被他人改变） */
    public static final String ST_PAUSED = "已暂停";
    /** 偏差挂起（反馈与计划不符，等待处置） */
    public static final String ST_DEVIATED = "偏差挂起";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    /** 顺序号，从 1 开始 */
    @Column(name = "step_no", nullable = false)
    private Integer stepNo;

    @Column(name = "valve_well_id")
    private Long valveWellId;

    @Column(name = "well_no", length = 32)
    private String wellNo;

    @Column(name = "node_no", length = 32)
    private String nodeNo;

    /** CLOSE / OPEN / CHECK_CLOSED / EVACUATE / PROOF */
    @Column(name = "action_type", nullable = false, length = 16)
    private String actionType;

    /** PLANNED_CLOSE / ALREADY_CLOSED / MUST_OPEN */
    @Column(name = "valve_category", length = 16)
    private String valveCategory;

    /** 计划到位状态：关闭 / 全开 / 半开 */
    @Column(name = "planned_state", length = 16)
    private String plannedState;

    /** 步骤下发前阀门实况 */
    @Column(name = "state_before", length = 16)
    private String stateBefore;

    /** 现场反馈的阀门状态 */
    @Column(name = "observed_state", length = 16)
    private String observedState;

    @Column(name = "step_status", nullable = false, length = 16)
    private String stepStatus = ST_PENDING;

    /** 是否撤离前提步骤（该步完成前不得执行后续关阀） */
    @Column(name = "egress_required")
    private Boolean egressRequired = Boolean.FALSE;

    /** 偏差原因 */
    @Column(name = "deviation_reason", length = 255)
    private String deviationReason;

    @Column(name = "completed_by", length = 32)
    private String completedBy;

    @Column(name = "completed_time")
    private LocalDateTime completedTime;

    @Column(name = "remark", length = 255)
    private String remark;
}
