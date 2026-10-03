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
 * 管网隔离方案：选定故障管段后，依据管段—阀门井连通关系试算出关断序列、
 * 可保供路径与受影响换热站；人工调整后必须重新通过拓扑证明才能启用。
 *
 * <p>状态机：
 * <pre>
 * 草稿(试算) → 已启用 → 执行中 → 已隔离 → 复供试算 → 已复供
 *                     ↘ 失败待处置（EXPAND 扩大隔离 / ROLLBACK 安全恢复 / HOLD 保持现场）
 * 草稿被放弃 → 已撤回（失败收口 ROLLBACK）
 * </pre>
 */
@Entity
@Table(name = "isolation_plan")
@Getter
@Setter
public class IsolationPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 方案编号 */
    @Column(name = "plan_no", nullable = false, unique = true, length = 32)
    private String planNo;

    /** ISOLATE 故障隔离 / RESTORE 复供 */
    @Column(name = "plan_type", nullable = false, length = 16)
    private String planType = "ISOLATE";

    /** 复供方案来源的隔离方案 */
    @Column(name = "parent_plan_id")
    private Long parentPlanId;

    /** 故障管段 */
    @Column(name = "fault_segment_id")
    private Long faultSegmentId;

    /** 故障 / 隔离原因 */
    @Column(name = "fault_reason", length = 128)
    private String faultReason;

    /** 草稿 / 已启用 / 执行中 / 已隔离 / 失败待处置 / 已撤回 / 复供试算 / 已复供 */
    @Column(name = "plan_status", nullable = false, length = 16)
    private String planStatus = "草稿";

    /** EXPAND 扩大停暖确保安全 / WAIT_ALT 拒绝启用等待替代路径 */
    @Column(name = "strategy", length = 16)
    private String strategy;

    /** 最近一次拓扑证明：故障段是否已与运行管网隔开 */
    @Column(name = "proof_passed")
    private Boolean proofPassed = Boolean.FALSE;

    /** 证明结论与未通过原因 */
    @Column(name = "proof_detail", length = 1024)
    private String proofDetail;

    /** 可保供路径（源 → 换热站路径） */
    @Column(name = "supply_paths_json", columnDefinition = "TEXT")
    private String supplyPathsJson;

    /** 受影响换热站清单快照（与阀门实况同步重算） */
    @Column(name = "affected_json", columnDefinition = "TEXT")
    private String affectedJson;

    /** 方案各阀门：原态 / 目标态 / 分类（计划关闭 / 原本关闭 / 必须开启） */
    @Column(name = "valve_snapshot_json", columnDefinition = "TEXT")
    private String valveSnapshotJson;

    /** 试算时刻阀门实况（用于现场比对他人改动） */
    @Column(name = "baseline_snapshot_json", columnDefinition = "TEXT")
    private String baselineSnapshotJson;

    /** 偏差记录：步骤偏差、现场另行调整的阀门 */
    @Column(name = "deviation_json", columnDefinition = "TEXT")
    private String deviationJson;

    /** 失败收口：EXPAND / ROLLBACK / HOLD */
    @Column(name = "failure_outcome", length = 16)
    private String failureOutcome;

    @Column(name = "failure_note", length = 255)
    private String failureNote;

    @Column(name = "failure_time")
    private LocalDateTime failureTime;

    /** 复供后仍不可达换热站异常清单说明 */
    @Column(name = "restore_proof_detail", length = 1024)
    private String restoreProofDetail;

    @Column(name = "created_by", length = 32)
    private String createdBy;

    @Column(name = "enabled_time")
    private LocalDateTime enabledTime;

    @Column(name = "isolated_time")
    private LocalDateTime isolatedTime;

    @Column(name = "restored_time")
    private LocalDateTime restoredTime;

    @Column(name = "created_time", nullable = false)
    private LocalDateTime createdTime;

    @Column(name = "updated_time", nullable = false)
    private LocalDateTime updatedTime;
}
