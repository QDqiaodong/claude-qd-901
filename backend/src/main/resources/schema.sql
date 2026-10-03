-- 城市集中供热 · 热源与管网调度管理
-- 库名 heat_supply；application.yml 里 ddl-auto: none，表结构全部由本文件在 MySQL 镜像构建期导入。
-- 关键：镜像构建期跑 initdb 时 mysql 客户端默认 latin1，会把 UTF-8 中文存成双编码，
--       因此每个 .sql 文件的第一句必须是 SET NAMES utf8mb4。
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET character_set_client = utf8mb4;
SET character_set_connection = utf8mb4;
SET character_set_results = utf8mb4;

CREATE DATABASE IF NOT EXISTS heat_supply
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE heat_supply;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS isolation_step;
DROP TABLE IF EXISTS isolation_plan;
DROP TABLE IF EXISTS heating_fee;
DROP TABLE IF EXISTS repair_order;
DROP TABLE IF EXISTS room_temp_sample;
DROP TABLE IF EXISTS resident;
DROP TABLE IF EXISTS valve_well;
DROP TABLE IF EXISTS pipe_segment;
DROP TABLE IF EXISTS heat_station;
DROP TABLE IF EXISTS heat_source;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------- 热源厂 ----------------
CREATE TABLE heat_source (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_name VARCHAR(64) NOT NULL UNIQUE,
    capacity_mw DECIMAL(10, 2) COMMENT '装机容量 MW',
    fuel_type VARCHAR(16) COMMENT '燃煤 / 燃气 / 热电联产',
    source_status VARCHAR(16) COMMENT '运行 / 检修 / 停用',
    manager VARCHAR(32),
    address VARCHAR(128),
    commission_date DATE,
    outlet_well_no VARCHAR(32) COMMENT '出厂井号（热源接入管网的节点，环网可达性计算起点之一）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------- 换热站 ----------------
CREATE TABLE heat_station (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    station_code VARCHAR(32) NOT NULL UNIQUE,
    station_name VARCHAR(64),
    heat_source_id BIGINT,
    area_wan_m2 DECIMAL(10, 2) COMMENT '供热面积 万㎡',
    primary_supply_temp DECIMAL(5, 1) COMMENT '一次侧供水温度 ℃',
    primary_return_temp DECIMAL(5, 1) COMMENT '一次侧回水温度 ℃',
    secondary_supply_temp DECIMAL(5, 1) COMMENT '二次侧供水温度 ℃',
    secondary_return_temp DECIMAL(5, 1) COMMENT '二次侧回水温度 ℃',
    design_delta_t DECIMAL(5, 1) DEFAULT 25.0 COMMENT '一次侧设计供回温差 ℃',
    pump_count INT COMMENT '循环泵装机台数',
    running_pump_count INT COMMENT '当前运行循环泵台数',
    station_status VARCHAR(16) COMMENT '运行 / 检修 / 停用',
    feed_well_no VARCHAR(32) COMMENT '一次侧接入井号（换热站挂接管网的节点，停暖判定依据）',
    manager VARCHAR(32),
    address VARCHAR(128),
    last_maintain_date DATE,
    KEY idx_station_source (heat_source_id),
    CONSTRAINT fk_station_source FOREIGN KEY (heat_source_id) REFERENCES heat_source (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------- 管网段 ----------------
CREATE TABLE pipe_segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    seg_code VARCHAR(32) NOT NULL UNIQUE,
    start_well_no VARCHAR(32) COMMENT '起点井号',
    end_well_no VARCHAR(32) COMMENT '终点井号',
    diameter_dn INT COMMENT '管径 DN',
    length_m DECIMAL(10, 1) COMMENT '长度 m',
    laying_type VARCHAR(16) COMMENT '直埋 / 架空 / 管沟',
    commission_year INT COMMENT '投运年份',
    insulation_status VARCHAR(16) COMMENT '完好 / 破损',
    segment_status VARCHAR(16) COMMENT '运行 / 检修 / 停用',
    map_points VARCHAR(255) COMMENT '平面示意图折线坐标',
    remark VARCHAR(128)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------- 阀门井 ----------------
-- 一口阀门井 = 一台阀门 + 其所在井节点（node_no）。同一井节点可有多台分属不同管段的阀（分段阀 / 联络阀），
-- 因此拓扑以 node_no 为节点、以管段为边，阀门只封堵其所属管段（边）。
CREATE TABLE valve_well (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    well_no VARCHAR(32) NOT NULL UNIQUE,
    node_no VARCHAR(32) COMMENT '井节点号：该阀门所在拓扑节点（同节点可有多台阀）',
    well_location VARCHAR(128),
    valve_caliber_dn INT COMMENT '阀门口径 DN',
    open_state VARCHAR(16) COMMENT '全开 / 半开 / 关闭',
    repair_status VARCHAR(16) COMMENT '正常 / 检修中',
    operable TINYINT(1) DEFAULT 1 COMMENT '现场是否可操作：0 不可操作（阀杆抱死 / 检修拆装 / 无法到位）',
    egress_exit TINYINT(1) DEFAULT 0 COMMENT '是否为井下作业人员安全撤离通道口',
    segment_id BIGINT,
    last_check_date DATE,
    remark VARCHAR(128),
    map_x INT COMMENT '平面示意图横坐标',
    map_y INT COMMENT '平面示意图纵坐标',
    KEY idx_well_segment (segment_id),
    CONSTRAINT fk_well_segment FOREIGN KEY (segment_id) REFERENCES pipe_segment (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------- 热用户 ----------------
CREATE TABLE resident (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    community VARCHAR(64) COMMENT '小区',
    building_no VARCHAR(32) COMMENT '楼栋',
    unit_no VARCHAR(32) COMMENT '单元',
    room_no VARCHAR(32) COMMENT '房号',
    area_m2 DECIMAL(8, 2) COMMENT '建筑面积 ㎡',
    meter_no VARCHAR(32) UNIQUE COMMENT '热表编号',
    total_heat_gj DECIMAL(10, 2) COMMENT '累计用热量 GJ',
    station_id BIGINT,
    supply_status VARCHAR(16) COMMENT '正常供热 / 已报停',
    contact_phone VARCHAR(24),
    KEY idx_resident_station (station_id),
    CONSTRAINT fk_resident_station FOREIGN KEY (station_id) REFERENCES heat_station (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------- 室温抽测 ----------------
CREATE TABLE room_temp_sample (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    resident_id BIGINT NOT NULL,
    sample_date DATE COMMENT '抽测日期',
    temperature DECIMAL(4, 1) COMMENT '室温 ℃',
    qualified TINYINT(1) COMMENT '是否达标：低于 18℃ 判不达标',
    household_name VARCHAR(32) COMMENT '住户姓名',
    surveyor VARCHAR(32) COMMENT '抽测人',
    remark VARCHAR(128),
    KEY idx_sample_resident (resident_id),
    CONSTRAINT fk_sample_resident FOREIGN KEY (resident_id) REFERENCES resident (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------- 报修工单 ----------------
CREATE TABLE repair_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(32) NOT NULL UNIQUE,
    reported_date DATE,
    station_id BIGINT,
    resident_id BIGINT,
    fault_type VARCHAR(16) COMMENT '不热 / 漏水 / 异响 / 气堵',
    assignee VARCHAR(32) COMMENT '派工人',
    handle_result VARCHAR(255) COMMENT '处理结果',
    order_status VARCHAR(16) COMMENT '待派工 / 处理中 / 已闭环',
    closed_date DATE,
    KEY idx_order_station (station_id),
    KEY idx_order_resident (resident_id),
    CONSTRAINT fk_order_station FOREIGN KEY (station_id) REFERENCES heat_station (id),
    CONSTRAINT fk_order_resident FOREIGN KEY (resident_id) REFERENCES resident (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------- 采暖费收缴 ----------------
CREATE TABLE heating_fee (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    fee_year INT COMMENT '收费年度',
    resident_id BIGINT NOT NULL,
    billing_area DECIMAL(8, 2) COMMENT '计费面积 ㎡',
    unit_price DECIMAL(6, 2) COMMENT '单价 元/㎡',
    receivable DECIMAL(12, 2) COMMENT '应收金额',
    paid DECIMAL(12, 2) COMMENT '实收金额',
    arrears DECIMAL(12, 2) COMMENT '欠费金额',
    pay_status VARCHAR(16) COMMENT '已缴 / 部分 / 欠费',
    last_pay_date DATE,
    remark VARCHAR(128),
    KEY idx_fee_resident (resident_id),
    CONSTRAINT fk_fee_resident FOREIGN KEY (resident_id) REFERENCES resident (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------- 管网隔离方案（故障管段关断闭环主表） ----------------
-- 状态机：草稿(试算) -> 已启用 -> 执行中 -> 已隔离 / 失败待处置
--   失败收口：EXPAND 扩大隔离后回到 执行中；ROLLBACK 已安全恢复 -> 已撤回；HOLD 保持现场等待处置
--   已隔离 -> 现场修复完成 -> 复供试算(RESTORING) -> 已复供；复供后仍不可达的换热站留在异常清单
CREATE TABLE isolation_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_no VARCHAR(32) NOT NULL UNIQUE COMMENT '方案编号',
    plan_type VARCHAR(16) NOT NULL DEFAULT 'ISOLATE' COMMENT 'ISOLATE 故障隔离 / RESTORE 复供',
    parent_plan_id BIGINT COMMENT '复供方案来源的隔离方案',
    fault_segment_id BIGINT COMMENT '故障管段',
    fault_reason VARCHAR(128) COMMENT '故障 / 隔离原因',
    plan_status VARCHAR(16) NOT NULL DEFAULT '草稿' COMMENT '草稿/已启用/执行中/已隔离/失败待处置/已撤回/复供试算/已复供',
    strategy VARCHAR(16) COMMENT 'EXPAND 扩大停暖确保安全 / WAIT_ALT 拒绝启用等待替代路径',
    proof_passed TINYINT(1) DEFAULT 0 COMMENT '最近一次拓扑证明：故障段是否已与运行管网隔开',
    proof_detail VARCHAR(1024) COMMENT '证明结论与未通过原因（端点可达性、违规项）',
    supply_paths_json TEXT COMMENT '可保供路径（源 -> 换热站路径）',
    affected_json TEXT COMMENT '受影响换热站清单快照（与阀门实况同步重算）',
    valve_snapshot_json TEXT COMMENT '方案各阀门：原态/目标态/分类（计划关闭/原本关闭/必须开启）',
    baseline_snapshot_json TEXT COMMENT '试算时刻阀门实况（用于现场比对他人改动）',
    deviation_json TEXT COMMENT '偏差记录：步骤偏差、现场另行调整的阀门',
    failure_outcome VARCHAR(16) COMMENT 'EXPAND / ROLLBACK / HOLD',
    failure_note VARCHAR(255),
    failure_time DATETIME,
    restore_proof_detail VARCHAR(1024) COMMENT '复供后仍不可达换热站异常清单说明',
    created_by VARCHAR(32),
    enabled_time DATETIME,
    isolated_time DATETIME,
    restored_time DATETIME,
    created_time DATETIME NOT NULL,
    updated_time DATETIME NOT NULL,
    KEY idx_plan_segment (fault_segment_id),
    KEY idx_plan_parent (parent_plan_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------- 隔离/复供操作步骤（阀门关断序列） ----------------
CREATE TABLE isolation_step (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    step_no INT NOT NULL COMMENT '顺序号：关断顺序不得切断人员安全退出路径',
    valve_well_id BIGINT COMMENT '阀门（撤离确认步骤为空）',
    well_no VARCHAR(32),
    node_no VARCHAR(32),
    action_type VARCHAR(16) NOT NULL COMMENT 'CLOSE 关闭 / OPEN 开启 / CHECK_CLOSED 确认原本关闭 / EVACUATE 人员撤离确认 / PROOF 隔离验证',
    valve_category VARCHAR(16) COMMENT 'PLANNED_CLOSE 计划关闭 / ALREADY_CLOSED 原本关闭 / MUST_OPEN 必须开启 / 人工操作',
    planned_state VARCHAR(16) COMMENT '计划到位状态：关闭 / 全开 / 半开',
    state_before VARCHAR(16) COMMENT '步骤下发前阀门实况',
    observed_state VARCHAR(16) COMMENT '现场反馈的阀门状态',
    step_status VARCHAR(16) NOT NULL DEFAULT '待执行' COMMENT '待执行/已完成/已跳过/已暂停/偏差挂起',
    egress_required TINYINT(1) DEFAULT 0 COMMENT '是否撤离前提步骤',
    deviation_reason VARCHAR(255) COMMENT '偏差原因（反馈与计划不符 / 他人已改变 / 前一步未完成）',
    completed_by VARCHAR(32),
    completed_time DATETIME,
    remark VARCHAR(255),
    UNIQUE KEY uk_step (plan_id, step_no),
    KEY idx_step_valve (valve_well_id),
    CONSTRAINT fk_step_plan FOREIGN KEY (plan_id) REFERENCES isolation_plan (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- =====================================================================
-- 种子数据
-- =====================================================================

-- 热源厂 5 座
INSERT INTO heat_source (id, source_name, capacity_mw, fuel_type, source_status, manager, address, commission_date,
                         outlet_well_no) VALUES
    (1, '城东热电厂', 1450.00, '热电联产', '运行', '陈国栋', '东环路 118 号', '1998-11-15', 'J101'),
    (2, '城北热源厂', 860.00, '燃煤', '运行', '李柏林', '北三环路 26 号', '2004-10-28', 'J113'),
    (3, '西城燃气热源厂', 420.00, '燃气', '运行', '王海涛', '西山路 9 号', '2012-11-01', 'J108'),
    (4, '南岭调峰热源厂', 320.00, '燃气', '检修', '赵鹏', '南岭工业区 3 号', '2016-12-20', NULL),
    (5, '向阳路备用热源', 180.00, '燃煤', '停用', '孙立军', '向阳路 47 号', '2001-10-15', NULL);

-- 换热站 10 座（一次侧温差 / 设计温差 = 负荷率，覆盖低负荷、正常、超负荷）
INSERT INTO heat_station (id, station_code, station_name, heat_source_id, area_wan_m2, primary_supply_temp,
                          primary_return_temp, secondary_supply_temp, secondary_return_temp, design_delta_t,
                          pump_count, running_pump_count, station_status, feed_well_no, manager, address,
                          last_maintain_date) VALUES
    (1,  'HS-01', '朝阳里换热站',   1, 26.50, 94.0, 68.0, 52.0, 42.0, 25.0, 4, 3, '运行', 'J101', '张涛', '朝阳里小区东门南侧', '2025-10-08'),
    (2,  'HS-02', '和平新村换热站', 1, 18.20, 90.0, 71.0, 48.0, 40.0, 25.0, 3, 2, '运行', 'J103', '刘敏', '和平新村 5 号楼旁', '2025-09-22'),
    (3,  'HS-03', '滨河家园换热站', 1, 21.60, 86.0, 73.0, 46.0, 39.0, 25.0, 3, 2, '运行', 'J102', '周正阳', '滨河家园地下车库旁', '2025-11-03'),
    (4,  'HS-04', '兴安里换热站',   2, 15.80, 92.0, 70.0, 50.0, 41.0, 25.0, 3, 3, '运行', 'J106', '吴国庆', '兴安里小区西配房', '2025-10-19'),
    (5,  'HS-05', '北苑小区换热站', 2, 12.40, 78.0, 76.0, 41.0, 38.0, 25.0, 2, 1, '检修', 'J104', '郑立群', '北苑小区锅炉房旧址', '2025-12-11'),
    (6,  'HS-06', '站前广场换热站', 2,  9.60,  0.0,  0.0,  0.0,  0.0, 25.0, 2, 0, '停用', 'J110', '马晓东', '站前广场地下夹层', '2025-08-30'),
    (7,  'HS-07', '加州花园换热站', 3, 23.30, 91.0, 67.0, 51.0, 42.0, 25.0, 4, 3, '运行', 'J108', '李成志', '加州花园 12 号楼后', '2025-10-25'),
    (8,  'HS-08', '西港新居换热站', 3, 17.90, 85.0, 69.0, 47.0, 40.0, 25.0, 3, 2, '运行', 'J109', '何雅丽', '西港新居泵房', '2025-11-17'),
    (9,  'HS-09', '南岭公建换热站', 4, 14.20, 80.0, 72.5, 43.0, 38.5, 25.0, 2, 1, '检修', 'J111', '赵鹏', '南岭公建裙楼一层', '2025-12-28'),
    (10, 'HS-10', '向阳街换热站',   5,  8.30,  0.0,  0.0,  0.0,  0.0, 25.0, 2, 0, '停用', NULL,  '孙立军', '向阳街 47 号院内', '2025-07-14');

-- 管网段 12 段（含 map_points 供平面示意图绘制；PS-11 环网联络线、PS-12 城北热源联络线构成多路径环网）
INSERT INTO pipe_segment (id, seg_code, start_well_no, end_well_no, diameter_dn, length_m, laying_type,
                          commission_year, insulation_status, segment_status, map_points, remark) VALUES
    (1,  'PS-01', 'J101', 'J102', 800, 486.0, '直埋', 2010, '完好', '运行', '80,120 320,120', '城东电厂出厂主干线，双管敷设'),
    (2,  'PS-02', 'J102', 'J103', 800, 512.0, '直埋', 2010, '完好', '运行', '320,120 560,120', '沿东环路北侧绿化带敷设'),
    (3,  'PS-03', 'J103', 'J104', 600, 438.0, '架空', 2013, '破损', '运行', '560,120 800,120', '架空段保温层外护破损待修'),
    (4,  'PS-04', 'J104', 'J105', 600, 356.0, '管沟', 2013, '完好', '检修', '800,120 960,120', '计划性检修：更换 DN600 主管蝶阀'),
    (5,  'PS-05', 'J105', 'J106', 500, 402.0, '直埋', 2016, '完好', '运行', '960,120 960,300', '向南转输支线'),
    (6,  'PS-06', 'J106', 'J107', 500, 465.0, '直埋', 2016, '破损', '运行', '960,300 720,300', '破损段已列入年度大修'),
    (7,  'PS-07', 'J107', 'J108', 400, 428.0, '管沟', 2018, '完好', '运行', '720,300 480,300', '西城老旧管网改造段'),
    (8,  'PS-08', 'J108', 'J109', 300, 396.0, '直埋', 2018, '完好', '运行', '480,300 240,300', '二次管网环网联络线'),
    (9,  'PS-09', 'J109', 'J110', 300, 288.0, '架空', 2019, '完好', '停用', '240,300 80,300', '停用待并网'),
    (10, 'PS-10', 'J110', 'J111', 200, 352.0, '直埋', 2021, '破损', '运行', '80,300 80,440 300,440', '末端支线，DN200 接加州花园'),
    (11, 'PS-11', 'J103', 'J109', 400, 620.0, '管沟', 2022, '完好', '运行', '560,120 400,210 240,300', '东-西环网联络线，联络阀 J112 常态关闭，隔离时可作保供替代路径'),
    (12, 'PS-12', 'J113', 'J106', 500, 538.0, '直埋', 2023, '完好', '运行', '1080,210 960,300', '城北热源厂进城联络线，J106 联络阀现场不可操作（阀杆抱死待修）');

-- 阀门井 14 座（坐标落在管段折线端点；J103 节点有 J103/J112/J115 三台分属不同管段的阀）
INSERT INTO valve_well (id, well_no, node_no, well_location, valve_caliber_dn, open_state, repair_status,
                        operable, egress_exit, segment_id, last_check_date, remark, map_x, map_y) VALUES
    (1,  'J101', 'J101', '东环路与朝阳路交口东北角', 800, '全开', '正常',   1, 1, 1,  '2026-02-05', '出厂总阀门，调节一次侧总流量；地面安全出口',   80,  120),
    (2,  'J102', 'J102', '东环路中段绿化带',         800, '全开', '正常',   1, 0, 2,  '2026-02-05', '主干分段阀',                    320, 120),
    (3,  'J103', 'J103', '东环路南段人行道',         600, '半开', '正常',   1, 1, 3,  '2026-02-08', '架空管补偿器前阀门；路口地面安全出口', 560, 120),
    (4,  'J104', 'J104', '兴安里街北侧',             600, '关闭', '检修中', 1, 0, 4,  '2026-02-12', '随 PS-04 同步检修',             800, 120),
    (5,  'J105', 'J105', '南岭工业区入口',           600, '半开', '正常',   1, 0, 4,  '2026-02-12', '检修段末端隔离阀（PS-04 端）', 960, 120),
    (6,  'J106', 'J106', '西山路与南环路交口',       500, '全开', '检修中', 0, 1, 12, '2026-02-09', '城北联络阀，阀杆抱死现场不可操作；地面安全出口', 960, 300),
    (7,  'J107', 'J107', '西山路中段消防通道',       500, '关闭', '正常',   1, 0, 6,  '2026-02-15', '破损管段待大修，已隔离',        720, 300),
    (8,  'J108', 'J108', '西港新居停车场入口',       400, '全开', '正常',   1, 1, 7,  '2026-02-10', '西城热源出厂/环网联络阀；地面安全出口', 480, 300),
    (9,  'J109', 'J109', '和平新村南侧巷道',         300, '全开', '正常',   1, 0, 8,  '2026-02-10', '低区回水调节',                  240, 300),
    (10, 'J110', 'J110', '向阳街西侧便道',           300, '关闭', '正常',   1, 0, 9,  '2026-01-28', '停用管段隔离阀',                80,  300),
    (11, 'J111', 'J111', '加州花园 12 号楼前',       200, '半开', '正常',   1, 0, 10, '2026-02-18', '末端支线调节，防止远端不热',    300, 440),
    (12, 'J112', 'J103', '东环路南段联络阀井',       400, '关闭', '正常',   1, 0, 11, '2026-02-20', '环网联络线分段阀，常态关闭，故障隔离时按试算结论开启保供', 560, 142),
    (13, 'J113', 'J113', '北三环进城计量站',         500, '全开', '正常',   1, 0, 12, '2026-02-22', '城北热源厂出厂分段阀',          1080, 210),
    (14, 'J115', 'J103', '东环路南段东侧分阀井',     800, '全开', '正常',   1, 0, 2,  '2026-02-22', 'PS-02 东侧分段阀（与 J102 构成管段两端关断点）', 584, 100);

-- 热用户 15 户（朝阳里小区 3 栋 × 4 单元构成 12 格矩阵）
INSERT INTO resident (id, community, building_no, unit_no, room_no, area_m2, meter_no, total_heat_gj,
                      station_id, supply_status, contact_phone) VALUES
    (1,  '朝阳里小区', '1', '1', '101', 89.60,  'RB20260001', 42.35, 1, '正常供热', '13805310001'),
    (2,  '朝阳里小区', '1', '2', '101', 68.40,  'RB20260002', 33.18, 1, '正常供热', '13805310002'),
    (3,  '朝阳里小区', '1', '3', '101', 112.50, 'RB20260003', 55.62, 1, '正常供热', '13805310003'),
    (4,  '朝阳里小区', '1', '4', '101', 76.20,  'RB20260004', 37.90, 1, '正常供热', '13805310004'),
    (5,  '朝阳里小区', '2', '1', '102', 94.80,  'RB20260005', 46.12, 1, '正常供热', '13805310005'),
    (6,  '朝阳里小区', '2', '2', '102', 130.00, 'RB20260006', 61.44, 1, '正常供热', '13805310006'),
    (7,  '朝阳里小区', '2', '3', '102', 105.00, 'RB20260007', 52.08, 2, '正常供热', '13805310007'),
    (8,  '朝阳里小区', '2', '4', '102', 82.50,  'RB20260008', 40.66, 2, '正常供热', '13805310008'),
    (9,  '朝阳里小区', '3', '1', '201', 77.30,  'RB20260009', 36.22, 2, '正常供热', '13805310009'),
    (10, '朝阳里小区', '3', '2', '201', 120.00, 'RB20260010', 58.90, 2, '正常供热', '13805310010'),
    (11, '朝阳里小区', '3', '3', '201', 65.00,  'RB20260011', 31.05, 2, '正常供热', '13805310011'),
    (12, '朝阳里小区', '3', '4', '201', 99.20,  'RB20260012', 48.77, 2, '正常供热', '13805310012'),
    (13, '滨河家园',   '5', '1', '301', 88.00,  'RB20260013', 41.20, 3, '正常供热', '13805310013'),
    (14, '滨河家园',   '5', '1', '401', 71.60,  'RB20260014', 34.55, 3, '正常供热', '13805310014'),
    (15, '滨河家园',   '6', '1', '302', 108.40, 'RB20260015', 52.31, 3, '已报停',   '13805310015');

-- 室温抽测 15 条（含低于 18℃ 的不达标记录与高于 22℃ 的过热记录）
INSERT INTO room_temp_sample (id, resident_id, sample_date, temperature, qualified, household_name, surveyor, remark) VALUES
    (1,  1,  '2026-02-05', 21.4, 1, '张建平', '刘敏', NULL),
    (2,  1,  '2026-01-22', 20.8, 1, '张建平', '刘敏', NULL),
    (3,  2,  '2026-02-05', 19.6, 1, '刘桂芳', '刘敏', NULL),
    (4,  3,  '2026-02-05', 17.2, 0, '王志远', '张涛', '末端用户，二次侧流量偏低'),
    (5,  3,  '2026-01-22', 18.9, 1, '王志远', '张涛', NULL),
    (6,  4,  '2026-02-05', 23.1, 1, '陈淑兰', '张涛', '顶楼层，室温偏高'),
    (7,  5,  '2026-02-05', 16.8, 0, '李国强', '刘敏', '回水温度偏低'),
    (8,  6,  '2026-02-05', 20.2, 1, '赵秀英', '刘敏', NULL),
    (9,  7,  '2026-02-05', 17.5, 0, '孙明海', '周正阳', '疑似气堵'),
    (10, 7,  '2026-01-22', 19.1, 1, '孙明海', '周正阳', NULL),
    (11, 8,  '2026-02-05', 22.4, 1, '周玉梅', '周正阳', '阳光房，散热量偏大'),
    (12, 9,  '2026-02-05', 19.0, 1, '吴德明', '吴国庆', NULL),
    (13, 10, '2026-02-05', 21.8, 1, '郑丽华', '吴国庆', NULL),
    (14, 11, '2026-02-05', 18.4, 1, '马春生', '郑立群', NULL),
    (15, 12, '2026-02-05', 17.9, 0, '胡永康', '郑立群', '入户过滤器堵塞待清');

-- 报修工单 9 条（待派工 / 处理中 / 已闭环 各若干，HS-01 留有未闭环工单）
INSERT INTO repair_order (id, order_no, reported_date, station_id, resident_id, fault_type, assignee,
                          handle_result, order_status, closed_date) VALUES
    (1, 'BX20260202001', '2026-02-02', 1, NULL, '漏水', '',       '',                                 '待派工', NULL),
    (2, 'BX20260203001', '2026-02-03', 1, 4,    '气堵', '牟继伟', '',                                 '处理中', NULL),
    (3, 'BX20260205001', '2026-02-05', 1, 6,    '异响', '孙鹏',   '紧固循环泵地脚螺栓，异响消失',     '已闭环', '2026-02-06'),
    (4, 'BX20260206001', '2026-02-06', 2, 8,    '不热', '赵立春', '',                                 '处理中', NULL),
    (5, 'BX20260208001', '2026-02-08', 2, NULL, '漏水', '',       '',                                 '待派工', NULL),
    (6, 'BX20260210001', '2026-02-10', 3, 11,   '气堵', '孙鹏',   '顶层排气阀放气后恢复',             '已闭环', '2026-02-11'),
    (7, 'BX20260212001', '2026-02-12', 4, NULL, '异响', '牟继伟', '',                                 '待派工', NULL),
    (8, 'BX20260215001', '2026-02-15', 7, 13,   '不热', '赵立春', '清洗除污器，二次侧流量回升',       '已闭环', '2026-02-16'),
    (9, 'BX20260218001', '2026-02-18', 7, 14,   '不热', '',       '',                                 '待派工', NULL);

-- 采暖费收缴 12 条（2026 收费年度，含已缴 / 部分 / 欠费）
INSERT INTO heating_fee (id, fee_year, resident_id, billing_area, unit_price, receivable, paid, arrears,
                         pay_status, last_pay_date, remark) VALUES
    (1,  2026, 1,  89.60,  25.40, 2275.84, 2275.84,    0.00, '已缴', '2026-01-12', NULL),
    (2,  2026, 2,  68.40,  25.40, 1737.36,  800.00,  937.36, '部分', '2026-02-03', '用户承诺采暖季结清'),
    (3,  2026, 3,  112.50, 25.40, 2857.50,    0.00, 2857.50, '欠费', NULL,         '拒缴：认为室温不达标'),
    (4,  2026, 4,  76.20,  25.40, 1935.48, 1935.48,    0.00, '已缴', '2026-01-08', NULL),
    (5,  2026, 5,  94.80,  25.40, 2407.92, 1000.00, 1407.92, '部分', '2026-01-25', NULL),
    (6,  2026, 6,  130.00, 25.40, 3302.00,    0.00, 3302.00, '欠费', NULL,         '长期空置，催缴中'),
    (7,  2026, 7,  105.00, 25.40, 2667.00,  500.00, 2167.00, '部分', '2026-02-06', NULL),
    (8,  2026, 8,  82.50,  25.40, 2095.50, 2095.50,    0.00, '已缴', '2026-01-16', NULL),
    (9,  2026, 9,  77.30,  25.40, 1963.42, 1500.00,  463.42, '部分', '2026-02-09', NULL),
    (10, 2026, 10, 120.00, 25.40, 3048.00,    0.00, 3048.00, '欠费', NULL,         '产权纠纷待核实'),
    (11, 2026, 11, 65.00,  25.40, 1651.00, 1651.00,    0.00, '已缴', '2026-01-20', NULL),
    (12, 2026, 12, 99.20,  25.40, 2519.68, 2519.68,    0.00, '已缴', '2026-01-27', NULL);
