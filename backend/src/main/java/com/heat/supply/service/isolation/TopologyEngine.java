package com.heat.supply.service.isolation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.heat.supply.domain.HeatSource;
import com.heat.supply.domain.HeatStation;
import com.heat.supply.domain.PipeSegment;
import com.heat.supply.domain.ValveWell;

/**
 * 管网拓扑试算引擎（纯内存、无状态，不依赖 Spring）。
 *
 * <h3>建模口径</h3>
 * <ul>
 *   <li>节点 = 井节点号（{@link ValveWell#getNodeNo()}，缺省回退井号）；</li>
 *   <li>边 = 管段，两端取 start/end 井号；边可通行当且仅当：管段非停用、非故障段、
 *       边上没有任何一台「关闭」状态的阀（半开 / 全开均视为导通）；</li>
 *   <li>阀只封堵其所属管段（边），不做整节点封堵 —— 同节点联络阀关闭时主干仍可经其他边保供；</li>
 *   <li>热源：运行状态且有出厂井号者，出厂井为有源节点；</li>
 *   <li>换热站：运行状态且有接入井号者，接入井可达即「仍有供热路径」，不得误判为停暖。</li>
 * </ul>
 *
 * <h3>隔离证明</h3>
 * 证明图中故障段整体摘除；故障段某端点若已被「故障段自身在该节点的全部阀门关闭」封住，
 * 记为故障侧已封堵；两个端点都满足「故障侧已封堵 或 自任何运行热源不可达」时，
 * 判定故障段已与运行管网隔开。
 */
public final class TopologyEngine {

    private TopologyEngine() {
    }

    /** 一台阀门的运行态视图 */
    public static final class Valve {
        public final long id;
        public final String wellNo;
        public final String nodeNo;
        public final long segmentId;
        /** 全开 / 半开 / 关闭 */
        public final String state;
        public final boolean operable;
        public final boolean egressExit;

        public Valve(long id, String wellNo, String nodeNo, long segmentId, String state,
                     boolean operable, boolean egressExit) {
            this.id = id;
            this.wellNo = wellNo;
            this.nodeNo = nodeNo;
            this.segmentId = segmentId;
            this.state = state;
            this.operable = operable;
            this.egressExit = egressExit;
        }

        public boolean closed() {
            return "关闭".equals(state);
        }
    }

    /** 一条管段边 */
    public static final class Edge {
        public final long segmentId;
        public final String segCode;
        public final String a;
        public final String b;
        /** 停用段：不可通行，也不能作为绕行路径 */
        public final boolean disabled;
        /** 检修段：虽可能尚未完全封堵，但不得在其上开启阀门做保供绕行 */
        public final boolean maintenance;
        public final List<Valve> valves = new ArrayList<>();

        Edge(long segmentId, String segCode, String a, String b, boolean disabled, boolean maintenance) {
            this.segmentId = segmentId;
            this.segCode = segCode;
            this.a = a;
            this.b = b;
            this.disabled = disabled;
            this.maintenance = maintenance;
        }

        String other(String node) {
            return a.equals(node) ? b : a;
        }
    }

    /** 不可变拓扑图 */
    public static final class Graph {
        public final Map<String, List<Edge>> adjacency = new java.util.LinkedHashMap<>();
        public final Map<Long, Edge> edgeBySegment = new java.util.LinkedHashMap<>();
        public final Map<Long, Valve> valveById = new java.util.LinkedHashMap<>();
        public final Set<String> sourceNodes = new LinkedHashSet<>();
        public final Map<String, List<HeatStation>> stationsByNode = new java.util.LinkedHashMap<>();

        void addNode(String n) {
            if (n != null && !n.isBlank()) {
                adjacency.computeIfAbsent(n, k -> new ArrayList<>());
            }
        }

        void addEdge(Edge e) {
            adjacency.computeIfAbsent(e.a, k -> new ArrayList<>()).add(e);
            adjacency.computeIfAbsent(e.b, k -> new ArrayList<>()).add(e);
            edgeBySegment.put(e.segmentId, e);
        }
    }

    // ------------------------------------------------------------------
    // 建图
    // ------------------------------------------------------------------

    public static Graph build(List<PipeSegment> segments, List<ValveWell> wells,
                              List<HeatSource> sources, List<HeatStation> stations) {
        Graph g = new Graph();
        for (PipeSegment s : segments) {
            if (s.getStartWellNo() == null || s.getEndWellNo() == null) {
                continue;
            }
            g.addNode(s.getStartWellNo());
            g.addNode(s.getEndWellNo());
            boolean disabled = "停用".equals(s.getSegmentStatus());
            boolean maintenance = "检修".equals(s.getSegmentStatus());
            g.addEdge(new Edge(s.getId() == null ? -1L : s.getId(), s.getSegCode(),
                    s.getStartWellNo(), s.getEndWellNo(), disabled, maintenance));
        }
        for (ValveWell w : wells) {
            if (w.getSegment() == null || w.getSegment().getId() == null) {
                continue;
            }
            Edge edge = g.edgeBySegment.get(w.getSegment().getId());
            if (edge == null) {
                continue;
            }
            String node = w.getNodeNo() != null && !w.getNodeNo().isBlank() ? w.getNodeNo() : w.getWellNo();
            g.addNode(node);
            boolean operable = !Boolean.FALSE.equals(w.getOperable());
            boolean exit = Boolean.TRUE.equals(w.getEgressExit());
            Valve valve = new Valve(w.getId(), w.getWellNo(), node, edge.segmentId,
                    w.getOpenState() == null ? "全开" : w.getOpenState(), operable, exit);
            edge.valves.add(valve);
            g.valveById.put(valve.id, valve);
        }
        for (HeatSource src : sources) {
            if ("运行".equals(src.getStatus()) && src.getOutletWellNo() != null) {
                g.addNode(src.getOutletWellNo());
                g.sourceNodes.add(src.getOutletWellNo());
            }
        }
        for (HeatStation st : stations) {
            if (st.getFeedWellNo() == null) {
                continue;
            }
            g.addNode(st.getFeedWellNo());
            g.stationsByNode.computeIfAbsent(st.getFeedWellNo(), k -> new ArrayList<>()).add(st);
        }
        return g;
    }

    // ------------------------------------------------------------------
    // 可达性
    // ------------------------------------------------------------------

    /** 一组阀门状态覆盖 */
    public static final class Traversal {
        public final Set<Long> forceClosed = new HashSet<>();
        public final Set<Long> forceOpen = new HashSet<>();
        public Long faultSegmentId;

        public Traversal close(long valveId) {
            forceClosed.add(valveId);
            forceOpen.remove(valveId);
            return this;
        }

        public Traversal open(long valveId) {
            forceOpen.add(valveId);
            forceClosed.remove(valveId);
            return this;
        }

        boolean valveClosed(Valve v) {
            if (forceClosed.contains(v.id)) {
                return true;
            }
            if (forceOpen.contains(v.id)) {
                return false;
            }
            return v.closed();
        }

        boolean edgePassable(Edge e) {
            if (e.disabled) {
                return false;
            }
            if (faultSegmentId != null && faultSegmentId.equals(e.segmentId)) {
                return false;
            }
            for (Valve v : e.valves) {
                if (valveClosed(v)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** BFS：从所有运行热源出厂井出发，返回可达节点集合 */
    public static Set<String> reachable(Graph g, Traversal t) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String src : g.sourceNodes) {
            if (g.adjacency.containsKey(src) && seen.add(src)) {
                queue.add(src);
            }
        }
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (Edge e : g.adjacency.getOrDefault(cur, List.of())) {
                if (!t.edgePassable(e)) {
                    continue;
                }
                String next = e.other(cur);
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return seen;
    }

    /** BFS 最短路径：源 -> 目标节点；不可达返回 null */
    public static List<String> shortestPath(Graph g, Traversal t, String target) {
        Map<String, String> parent = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String src : g.sourceNodes) {
            if (g.adjacency.containsKey(src) && !parent.containsKey(src)) {
                parent.put(src, null);
                queue.add(src);
            }
        }
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (cur.equals(target)) {
                break;
            }
            for (Edge e : g.adjacency.getOrDefault(cur, List.of())) {
                if (!t.edgePassable(e)) {
                    continue;
                }
                String next = e.other(cur);
                if (!parent.containsKey(next)) {
                    parent.put(next, cur);
                    queue.add(next);
                }
            }
        }
        if (!parent.containsKey(target)) {
            return null;
        }
        List<String> path = new ArrayList<>();
        String cur = target;
        while (cur != null) {
            path.add(0, cur);
            cur = parent.get(cur);
        }
        return path;
    }

    /** 当前运行、有接入井的换热站是否仍有供热路径 */
    public static Set<Long> suppliedStationIds(Graph g, Set<String> reachableNodes) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Map.Entry<String, List<HeatStation>> en : g.stationsByNode.entrySet()) {
            if (reachableNodes.contains(en.getKey())) {
                for (HeatStation st : en.getValue()) {
                    if ("运行".equals(st.getStatus())) {
                        ids.add(st.getId());
                    }
                }
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // 隔离证明
    // ------------------------------------------------------------------

    /** 证明结论 */
    public static final class Proof {
        public final boolean passed;
        public final boolean aReachable;
        public final boolean bReachable;
        public final boolean aSealedAtFault;
        public final boolean bSealedAtFault;
        public final String detail;

        Proof(boolean passed, boolean aReachable, boolean bReachable, boolean aSealed, boolean bSealed,
              String detail) {
            this.passed = passed;
            this.aReachable = aReachable;
            this.bReachable = bReachable;
            this.aSealedAtFault = aSealed;
            this.bSealedAtFault = bSealed;
            this.detail = detail;
        }
    }

    /**
     * 在「故障段摘除」的证明图上判定隔离：
     * 端点安全 = 故障段自身在该点的阀全部关闭（故障侧封堵）或该点自运行热源不可达。
     */
    public static Proof proveIsolation(Graph g, Traversal t, long faultSegmentId) {
        Edge fault = g.edgeBySegment.get(faultSegmentId);
        if (fault == null) {
            return new Proof(false, false, false, false, false, "故障管段不存在于当前拓扑图");
        }
        Traversal proofT = copy(t);
        proofT.faultSegmentId = faultSegmentId;
        Set<String> reach = reachable(g, proofT);
        boolean aReach = reach.contains(fault.a);
        boolean bReach = reach.contains(fault.b);
        boolean aSealed = faultSideSealed(fault, fault.a, proofT);
        boolean bSealed = faultSideSealed(fault, fault.b, proofT);
        boolean aSafe = aSealed || !aReach;
        boolean bSafe = bSealed || !bReach;
        boolean passed = aSafe && bSafe;

        List<String> msgs = new ArrayList<>();
        msgs.add(endpointMessage(fault.a, aReach, aSealed, aSafe));
        msgs.add(endpointMessage(fault.b, bReach, bSealed, bSafe));
        String head = passed
                ? "证明通过：故障段 " + fault.segCode + "（" + fault.a + "→" + fault.b + "）两端均已与运行管网隔开"
                : "证明未通过：故障段 " + fault.segCode + "（" + fault.a + "→" + fault.b + "）仍与运行管网连通";
        return new Proof(passed, aReach, bReach, aSealed, bSealed, head + "；" + String.join("；", msgs));
    }

    private static boolean faultSideSealed(Edge fault, String node, Traversal t) {
        boolean has = false;
        for (Valve v : fault.valves) {
            if (node.equals(v.nodeNo)) {
                has = true;
                if (!t.valveClosed(v)) {
                    return false;
                }
            }
        }
        return has;
    }

    private static String endpointMessage(String node, boolean reach, boolean sealed, boolean safe) {
        if (safe) {
            return node + (sealed ? " 已被故障侧阀门封堵" : " 自运行热源不可达");
        }
        return node + " 仍可由运行热源到达且故障侧未设阀封堵";
    }

    private static Traversal copy(Traversal t) {
        Traversal c = new Traversal();
        c.forceClosed.addAll(t.forceClosed);
        c.forceOpen.addAll(t.forceOpen);
        c.faultSegmentId = t.faultSegmentId;
        return c;
    }

    // ------------------------------------------------------------------
    // 关断边界试算（四阶段，已按环网种子数据验证）
    //   1) 故障段自身两端分段阀优先关（物理隔离边界）
    //   2) 试探开启运行管段上、可操作的环网保供阀（先不做证明门控）
    //   3) 仅对仍未封住的故障端，沿「目标态仍通向运行热源」的跨界边定向外扩
    //   4) 终态隔离证明门控：剔除任何会破坏证明的试探开阀
    // ------------------------------------------------------------------

    public static final class BoundaryValve {
        public final Valve valve;
        /** 该阀当前是否已关闭（已关闭=只需现场核对） */
        public final boolean alreadyClosed;
        /** 距离故障端的层数（0=故障段自身） */
        public final int layer;

        BoundaryValve(Valve valve, boolean alreadyClosed, int layer) {
            this.valve = valve;
            this.alreadyClosed = alreadyClosed;
            this.layer = layer;
        }
    }

    public static final class BoundaryResult {
        public final List<BoundaryValve> valves = new ArrayList<>();
        /** 无法关闭的阀（WAIT_ALT 拒绝启用；EXPAND 绕过并记录扩大停暖后果） */
        public final List<Valve> blockingInoperable = new ArrayList<>();
        /** 搜索中到达的停用段（无法作为绕行路径） */
        public final List<Edge> blockingDisabledEdges = new ArrayList<>();
        /** 阶段 2 试探开启、阶段 4 通过证明门控的环网保供阀（必须开启） */
        public final List<Valve> mustOpenValves = new ArrayList<>();
        public boolean boundaryClosed = true;
    }

    private static final class Frontier {
        final String node;
        final int layer;

        Frontier(String node, int layer) {
            this.node = node;
            this.layer = layer;
        }
    }

    /**
     * 关断边界试算。
     *
     * @param strategy      EXPAND 扩大停暖确保安全 / WAIT_ALT 拒绝启用等待替代路径
     * @param plannedClosed 人工强制纳入关闭的阀
     * @param plannedOpen   人工强制纳入开启的阀
     */
    public static BoundaryResult searchBoundary(Graph g, long faultSegmentId, String strategy,
                                                Set<Long> plannedClosed, Set<Long> plannedOpen) {
        BoundaryResult result = new BoundaryResult();
        Edge fault = g.edgeBySegment.get(faultSegmentId);
        if (fault == null) {
            result.boundaryClosed = false;
            return result;
        }
        boolean waitAlt = "WAIT_ALT".equals(strategy);
        Set<Long> closeIds = new HashSet<>(plannedClosed);
        Set<Long> openIds = new HashSet<>(plannedOpen);

        // -------- 阶段 1：故障段自身两端分段阀 --------
        Set<String> unsealed = new LinkedHashSet<>();
        for (String node : List.of(fault.a, fault.b)) {
            List<Valve> own = valvesAtNode(fault, node);
            if (own.isEmpty()) {
                unsealed.add(node);
                continue;
            }
            boolean sealed = true;
            for (Valve v : own) {
                if (v.closed() || closeIds.contains(v.id)) {
                    addBoundaryValve(result, v, closeIds, 0);
                } else if (!v.operable) {
                    result.blockingInoperable.add(v);
                    sealed = false;
                } else {
                    closeIds.add(v.id);
                    addBoundaryValve(result, v, closeIds, 0);
                }
            }
            if (!sealed) {
                unsealed.add(node);
            }
        }

        // -------- 阶段 2：试探开启环网保供阀（仅运行管段、可操作） --------
        Traversal tentative = new Traversal();
        tentative.faultSegmentId = faultSegmentId;
        tentative.forceClosed.addAll(closeIds);
        tentative.forceOpen.addAll(openIds);
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 128) {
            changed = false;
            for (Edge e : g.edgeBySegment.values()) {
                // 停用 / 检修 / 故障段都不得作为保供绕行去开阀
                if (e.disabled || e.maintenance || e.segmentId == faultSegmentId) {
                    continue;
                }
                for (Valve v : e.valves) {
                    if (tentative.forceOpen.contains(v.id) || !v.closed() || !v.operable) {
                        continue;
                    }
                    int before = suppliedStationIds(g, reachable(g, tentative)).size();
                    tentative.forceOpen.add(v.id);
                    int after = suppliedStationIds(g, reachable(g, tentative)).size();
                    if (after > before) {
                        openIds.add(v.id);
                        changed = true;
                    } else {
                        tentative.forceOpen.remove(v.id);
                    }
                }
            }
        }

        // -------- 阶段 3：对仍未封住的故障端定向外扩 --------
        Set<Long> handledEdges = new HashSet<>();
        handledEdges.add(faultSegmentId);
        Set<String> queued = new HashSet<>();
        Deque<Frontier> queue = new ArrayDeque<>();
        for (String n : unsealed) {
            queue.add(new Frontier(n, 1));
        }
        int outerGuard = 0;
        while (!queue.isEmpty() && outerGuard++ < 2048) {
            Frontier f = queue.poll();
            Traversal cur = new Traversal();
            cur.faultSegmentId = faultSegmentId;
            cur.forceClosed.addAll(closeIds);
            cur.forceOpen.addAll(openIds);
            Set<String> reach = reachable(g, cur);
            for (Edge e : new ArrayList<>(g.adjacency.getOrDefault(f.node, List.of()))) {
                if (handledEdges.contains(e.segmentId)) {
                    continue;
                }
                String other = e.other(f.node);
                if (e.disabled) {
                    handledEdges.add(e.segmentId);
                    if (!result.blockingDisabledEdges.contains(e)) {
                        result.blockingDisabledEdges.add(e);
                    }
                    continue;
                }
                if (!cur.edgePassable(e) || !reach.contains(other)) {
                    // 该边当前不把故障侧连向运行热源（环网替代路径侧），不处理，避免误关
                    handledEdges.add(e.segmentId);
                    continue;
                }
                List<Valve> candidates = new ArrayList<>(e.valves);
                if (candidates.isEmpty()) {
                    handledEdges.add(e.segmentId);
                    if (queued.add(other)) {
                        queue.add(new Frontier(other, f.layer + 1));
                    }
                    continue;
                }
                List<Valve> closedVs = new ArrayList<>();
                List<Valve> operableVs = new ArrayList<>();
                List<Valve> inopVs = new ArrayList<>();
                for (Valve v : candidates) {
                    if (closeIds.contains(v.id) || (!openIds.contains(v.id) && v.closed())) {
                        closedVs.add(v);
                    } else if (v.operable && !openIds.contains(v.id)) {
                        operableVs.add(v);
                    } else if (!v.operable && !openIds.contains(v.id)) {
                        inopVs.add(v);
                    }
                }
                if (!closedVs.isEmpty()) {
                    for (Valve v : closedVs) {
                        addBoundaryValve(result, v, closeIds, f.layer);
                    }
                    handledEdges.add(e.segmentId);
                    continue;
                }
                if (!inopVs.isEmpty() && operableVs.isEmpty()) {
                    result.blockingInoperable.addAll(inopVs);
                    handledEdges.add(e.segmentId);
                    if (waitAlt) {
                        result.boundaryClosed = false;
                        continue;
                    }
                    if (queued.add(other)) {
                        queue.add(new Frontier(other, f.layer + 1));
                    }
                    continue;
                }
                if (!inopVs.isEmpty()) {
                    result.blockingInoperable.addAll(inopVs);
                    if (waitAlt) {
                        result.boundaryClosed = false;
                        handledEdges.add(e.segmentId);
                        continue;
                    }
                }
                for (Valve v : operableVs) {
                    closeIds.add(v.id);
                    addBoundaryValve(result, v, closeIds, f.layer);
                }
                handledEdges.add(e.segmentId);
            }
        }
        if (waitAlt && !result.blockingInoperable.isEmpty()) {
            result.boundaryClosed = false;
        }

        // -------- 阶段 4：终态证明门控，剔除破坏隔离证明的试探开阀 --------
        Set<Long> allClose = new HashSet<>(closeIds);
        for (BoundaryValve bv : result.valves) {
            if (bv.alreadyClosed) {
                allClose.add(bv.valve.id);
            }
        }
        for (Long vid : new ArrayList<>(openIds)) {
            Traversal trial = new Traversal();
            trial.faultSegmentId = faultSegmentId;
            allClose.forEach(trial::close);
            for (Long o : openIds) {
                if (!o.equals(vid)) {
                    trial.open(o);
                }
            }
            if (!proveIsolation(g, trial, faultSegmentId).passed) {
                openIds.remove(vid);
            }
        }
        for (Long vid : openIds) {
            Valve v = g.valveById.get(vid);
            if (v != null && result.mustOpenValves.stream().noneMatch(x -> x.id == vid)) {
                result.mustOpenValves.add(v);
            }
        }
        return result;
    }

    private static List<Valve> valvesAtNode(Edge e, String node) {
        List<Valve> list = new ArrayList<>();
        for (Valve v : e.valves) {
            if (node.equals(v.nodeNo)) {
                list.add(v);
            }
        }
        return list;
    }

    private static void addBoundaryValve(BoundaryResult result, Valve v, Set<Long> closeIds, int layer) {
        boolean already = v.closed() && !closeIds.contains(v.id);
        for (BoundaryValve exists : result.valves) {
            if (exists.valve.id == v.id) {
                return;
            }
        }
        result.valves.add(new BoundaryValve(v, already, layer));
    }

    // ------------------------------------------------------------------
    // 撤离安全：关断顺序不得切断人员安全退出路径
    // ------------------------------------------------------------------

    /**
     * 判断在关闭给定阀集合后，每个井下作业节点是否仍能沿通行管段（故障段摘除、已关阀边封堵）
     * 撤到任一安全出口。任一作业节点失去全部撤离路径即返回 false。
     */
    public static boolean egressStillOpen(Graph g, long faultSegmentId, Collection<Long> closedValveIds,
                                          Collection<String> crewNodes) {
        Set<String> exitNodes = new LinkedHashSet<>();
        for (Valve v : g.valveById.values()) {
            if (v.egressExit) {
                exitNodes.add(v.nodeNo);
            }
        }
        if (exitNodes.isEmpty() || crewNodes.isEmpty()) {
            return true; // 未配置安全出口 / 无井下作业节点时不做强约束
        }
        Traversal t = new Traversal();
        t.faultSegmentId = faultSegmentId;
        closedValveIds.forEach(t::close);
        for (String crew : crewNodes) {
            if (!canReachAny(g, t, crew, exitNodes)) {
                return false;
            }
        }
        return true;
    }

    private static boolean canReachAny(Graph g, Traversal t, String start, Set<String> targets) {
        if (!g.adjacency.containsKey(start)) {
            return targets.contains(start);
        }
        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (targets.contains(cur)) {
                return true;
            }
            for (Edge e : g.adjacency.getOrDefault(cur, List.of())) {
                if (!t.edgePassable(e)) {
                    continue;
                }
                String next = e.other(cur);
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return false;
    }

    /** 默认作业人员所在节点：故障段两端井 */
    public static Set<String> defaultCrewNodes(Graph g, long faultSegmentId) {
        Edge fault = g.edgeBySegment.get(faultSegmentId);
        Set<String> nodes = new LinkedHashSet<>();
        if (fault != null) {
            nodes.add(fault.a);
            nodes.add(fault.b);
        }
        return nodes;
    }
}
