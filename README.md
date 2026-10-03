# claude-qd-901 · 城市集中供热 · 热源与管网调度管理

以「换热站 + 管网 + 用户室温」为主线的集中供热运行管理系统：热源厂产出热量，经管网与阀门井输送到换热站，
换热站把热量送到用户端，运维侧用报修工单兜住故障、财务侧用采暖费台账兜住欠费。

## 技术栈

| 层 | 选型 |
| --- | --- |
| 后端 | Spring Boot 3.3.0 / Java 17 / Spring Data JPA(Hibernate 6.5) / MySQL 8 / Redis 7 |
| 前端 | Vue 3 / Element Plus / Vite（只允许 vue、vue-router、element-plus、axios） |
| 端口 | 前端 8291、后端 8391、MySQL 3591、Redis 6591（全部走 `.env`） |

包名 `com.heat.supply`，库名 `heat_supply`，接口统一 `/api/xxx` 前缀、统一 `ApiResponse` 包装。

## 五个模块

1. **热源厂与换热站** —— 热源厂（名称、装机容量 MW、燃料类型、状态）与换热站（编号、所属热源、供热面积万㎡、
   一次侧/二次侧供回温、循环泵台数、状态）。
2. **管网段与阀门井** —— 管段（起止井号、管径 DN、长度、敷设方式、投运年份、保温状况）与阀门井
   （井号、位置、阀门口径、开关状态、检修状态）。
3. **用户与室温抽测** —— 用户（小区/楼栋/单元/面积/热表编号/累计用热量 GJ）与室温抽测
   （抽测日期、室温 ℃、是否达标、住户姓名）。
4. **报修工单** —— 工单号、报修换热站/用户、故障类型（不热/漏水/异响/气堵）、派工人、处理结果、
   状态（待派工/处理中/已闭环）。
5. **采暖费收缴** —— 收费年度、用户、计费面积、单价、应收/实收/欠费、缴费状态（已缴/部分/欠费）。

## 数据访问路线：@FetchProfile

实体上声明命名抓取档案，用 `@FetchProfile.FetchOverride` 把关联属性改成 `FetchMode.JOIN`，
service 里 `entityManager.unwrap(Session.class).enableFetchProfile("xxx")` 启用、查完 `disableFetchProfile`：

| 档案名 | 实体 · 关联属性 | 启用入口 |
| --- | --- | --- |
| `with-stations` | `HeatSource.stations` | `GET /api/sources?profile=with-stations` |
| `with-valves` | `PipeSegment.valves` | `GET /api/pipe/segments?profile=with-valves` |
| `with-segment` | `ValveWell.segment` | `GET /api/pipe/valve-wells?profile=with-segment` |
| `with-resident` | `RoomTempSample.resident` | `GET /api/monitor/room-temps?profile=with-resident` |

对照接口：`GET /api/sources/profile-compare` 在两个独立事务里分别「不启用」与「启用 with-stations」查同一批数据，
返回的 `associationEagerLoaded` 即为「关联集合是否在实体装载时就被 JOIN 预取」的差异信号。

手写 JPQL：

- `HeatSourceRepository#summaryBySource`：按热源汇总装机容量、换热站数、供热面积、一次侧平均温差。
- `HeatStationRepository#tempStatGroupByStation`：按换热站统计抽测户数、抽测次数与不达标次数。
- `HeatingFeeRepository#arrearsRank`：欠费金额排行（结果缓存在 Redis，缴费后失效）。
- `RoomTempSampleRepository#findLatestPerResident`：每户最近一次室温（热力格子染色）。

## 业务规则（service 抛 `BizException`，统一 400 + 中文提示）

- 室温低于 18℃ 判不达标，可一键生成报修工单；同一用户已有未闭环的「不热」工单时重复生成会被拦截。
- 换热站存在未闭环报修工单时不允许停用；所属热源厂停用时该站不能投运。
- 欠费用户不允许办理报停 / 恢复供热。
- 管段处于检修状态时，禁止把所属阀门井改为全开（管段转检修时会把全开阀下调为半开）。
- 缴费金额不得为负、不得超过欠费金额；报停用户不能登记缴费；报停用户不能录入室温抽测。
- 工单只能按「待派工 → 处理中 → 已闭环」推进，跨级 / 回退拦截；派工、闭环需先填派工人与处理结果。

## 管网隔离方案闭环（`#/isolation`）

选故障管段后由纯内存拓扑引擎 `TopologyEngine` 试算（节点=井节点 `node_no`，边=管段，阀门只封堵所属边，
不做整节点封堵，因此同井节点联络阀关闭时主干仍可经其他边保供）：

1. **四阶段关断边界试算**：① 先关故障段自身两端分段阀（物理隔离边界）；② 试探开启运行管段上可操作的
   环网保供阀（先不做证明门控）；③ 仅对仍未封住的故障端、沿「目标态仍通向运行热源」的跨界边定向外扩，
   环网替代路径侧不被误关；④ 终态隔离证明门控，剔除任何会破坏证明的试探开阀。
2. **隔离证明**：证明图摘除故障段，端点安全 = 故障段自身在该点的阀全部关闭（故障侧封堵）或该点自任一运行
   热源不可达；人工调整（加关 / 加开 / 剔除）后必须重新证明，未通过禁止启用。
3. **阀门三分类**：计划关闭（PLANNED_CLOSE）、原本关闭（ALREADY_CLOSED，只现场核对、不操作）、必须开启
   （MUST_OPEN，先开保供后关）。关断顺序：核对原关阀 → 必须开启 → 计划关闭；切断井下撤离路径的最后一台阀
   之前插入 EVACUATE 人员撤离确认步骤。
4. **两种策略**：EXPAND 遇不可操作阀绕过外扩（扩大停暖、给出受影响换热站与户数/面积后果）；
   WAIT_ALT 遇不可操作阀即拒绝启用、等待替代路径。仍有供热路径的换热站判「维持保供」，不得误判停暖。
5. **现场逐步确认**：前一步未完成、反馈与计划不符、同一阀门实况已被他人改变（既非试算态也非计划态）时
   后续步骤全部暂停，方案转「失败待处置」，保留完成记录与偏差原因，可一键重新试算。
6. **失败三收口**：EXPAND 以当前阀门实况为基线扩大隔离重算；ROLLBACK 按安全顺序（先开后关、逆序）生成
   恢复序列并撤回；HOLD 冻结方案与现场等待处置。方案状态、阀门实况、受影响清单按实况实时重算保持一致。
7. **复供**：故障修复后按当前阀门实况重新生成顺序；现场另行调整（实况≠旧方案目标态）的阀标记「现场保留」
   绝不被旧方案覆盖；仍不可达的运行换热站留在异常清单。

接口前缀 `/api/isolation`：`POST /trials`、`POST /plans/{id}/re-trial`、`POST /plans/{id}/enable`、
`POST /plans/{id}/steps/{stepId}/confirm`、`POST /plans/{id}/resolve-failure`、
`POST /plans/{id}/restore-trial`、`GET /plans`、`GET /plans/{id}`。

## 前端五个页面

| 路由 | 页面 | 操作范式 |
| --- | --- | --- |
| `#/stations` | 换热站负载环阵列 | SVG 环显示一次侧供回温差 / 设计温差，环色随负荷（蓝/绿/红），环心写供热面积，点环右侧抽屉登记运行参数 |
| `#/network` | 管网平面示意图 | SVG 主干折线 + 阀门井节点，线宽按管径、颜色按保温（绿/橙），顶部管径筛选条，点阀门井弹就地操作浮层改开关与检修状态，点管段可直接发起隔离试算 |
| `#/isolation` | 管网隔离方案闭环 | 选故障管段后拓扑试出关断序列 / 可保供路径 / 受影响换热站，区分计划关闭·原本关闭·必须开启，现场逐步确认与失败三收口 |
| `#/temperature` | 室温抽测热力格 | 楼栋 × 单元格子矩阵，按最近一次室温染色（<18 蓝 / 18~22 绿 / >22 红），不达标格子闪烁，点格子录入抽测，底部汇总不达标率与一键生成报修 |
| `#/service` | 报修工单看板 + 收费台账 | 三列看板用 HTML5 原生拖拽搬卡片推进状态；下半台账按欠费降序、欠费行标红，行内登记缴费（超额报错） |

左侧导航是「竖向管道式」：一条带法兰的粗管主干串起五个阀门节点，当前节点的阀门手轮高亮并持续旋转，
点阀门展开该模块的子功能项。

> 说明：`main.js` 属于模板固定文件（不注册 vue-router），因此 `src/router/index.js` 里的 ACL（模块 / 子功能项）
> 由 `App.vue` 通过哈希变化直接消费，好处是零安装副作用且刷新后状态不丢。

## 启动 / 停止

```sh
./start.sh            # 镜像已存在时直接起
./start.sh --build    # 改过代码后必须用这个重建
./stop.sh             # 停止
./clean.sh            # down -v，连匿名卷一起清干净
```

健康检查：`curl http://127.0.0.1:8391/actuator/health`；前端：`http://127.0.0.1:8291/`。
