package com.dwinovo.numen.pathing.search.baritone.bridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.ToDoubleFunction;

import com.dwinovo.numen.pathing.plan.Breath;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.EditedView;
import com.dwinovo.numen.pathing.plan.Heading;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.Moves;
import com.dwinovo.numen.pathing.plan.Premise;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.plan.WorldView;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.search.Search;
import com.dwinovo.numen.pathing.search.SearchResult;
import com.dwinovo.numen.pathing.search.SearchView;
import com.dwinovo.numen.pathing.search.baritone.ActionCosts;
import com.dwinovo.numen.pathing.search.baritone.BetterBlockPos;
import com.dwinovo.numen.pathing.search.baritone.CalculationContext;
import com.dwinovo.numen.pathing.search.baritone.Favoring;
import com.dwinovo.numen.pathing.search.baritone.MutableMoveResult;
import com.dwinovo.numen.pathing.search.baritone.PathCalculationResult;
import com.dwinovo.numen.pathing.search.baritone.calc.AStarPathFinder;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Bounds;
import com.dwinovo.numen.pathing.world.Recall;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;

/**
 * The bridge that lets Numen's live search run on the vendored Baritone A*.
 *
 * <p>Numen's world, move set and node identity are richer than the vendored
 * finder knows about, so this class hides the difference behind
 * {@link CalculationContext}/{@code baritone.Goal}/<code>baritone.Move</code>:
 *
 * <ul>
 *   <li><b>State in the coordinates.</b> Numen nodes are (cell, altered count,
 *       breath band). The vendored finder keys nodes only by three ints, so
 *       {@link Coord} folds the whole Numen state into them and the bridge's
 *       {@link Goal} decodes it. World queries and move offsets are neutralised
 *       so the finder never misreads the mangled coordinates.</li>
 *   <li><b>Moves.</b> Every Numen move times every heading becomes one vendored
 *       {@code Move} whose {@code apply} recomputes Numen's premise from the
 *       source node's real stance and world and writes the encoded destination
 *       and price.</li>
 *   <li><b>Goal.</b> The vendored goal decodes a node and asks Numen two
 *       things: whether the body there has arrived, and whether stopping is
 *       cheap enough to accept now. A reachable stop whose arrival price is
 *       dearer than the remaining estimate is not a goal yet, so the finder
 *       keeps searching past it (exactly the old loop's accept rule); the
 *       cheapest such stop is kept as the fallback when every stop is
 *       dear. The heuristic stays Numen's own estimate, never zeroed on a
 *       goal cell, and the arrival price is never folded into a move so a
 *       goal cell can be crossed without paying to stop there.</li>
 *   <li><b>Budget and partials.</b> The finder has no node budget, so the
 *       bridge cancels it once Numen's expansion budget (or early hand-over
 *       count) is reached, and keeps Numen's own coefficient table to pick the
 *       partial route exactly as the old loop did.</li>
 * </ul>
 */
public final class BridgeSearch implements CalculationContext {

    /** 小于这个改进不松弛,与旧搜索一致。 */
    private static final double MIN_IMPROVEMENT = 0.01;
    /** 半程路线的几档折算系数,与旧搜索一致。 */
    private static final double[] COEFFICIENTS = {1.5, 2, 2.5, 3, 4, 5, 10, Double.POSITIVE_INFINITY};
    /** 半程路线的终点至少要离起点这么远,与旧搜索一致({@code AStar.MIN_PARTIAL})。 */
    private static final int MIN_PARTIAL = 5;
    /** 计算不会超时:停不停由节点预算与叫停决定。 */
    private static final long TIMEOUT = Long.MAX_VALUE / 4;

    private final Search search;
    private final CostModel model;
    private final BodyStats body;
    private final Breath breath;
    /**
     * Numen's own estimate for a real cell is the search's guide: the goal's
     * {@code estimate} plus the digging floor. The ported Baritone goal
     * heuristic is deliberately not used here: it can disagree with Numen's,
     * and the produced legs are priced by Numen's own cost model regardless.
     */
    private final BurialFloor burial;
    private final ToDoubleFunction<BlockPos> favoring;
    private final boolean budgeted;
    private final int alterBudget;
    private final Stance startStance;
    private final int startX;
    private final int startY;
    private final int startZ;

    private final SearchView view;
    private final Recall recall;
    private final Probe probe;
    private final StateStore nodes = new StateStore();
    private final List<com.dwinovo.numen.pathing.search.baritone.Move> moves = new ArrayList<>();
    private final com.dwinovo.numen.pathing.search.baritone.Goal goal;

    private final SearchNode[] best = new SearchNode[COEFFICIENTS.length];
    private final double[] bestScore = new double[COEFFICIENTS.length];

    private BooleanSupplier cancelled = () -> false;
    private AStarPathFinder finder;
    private int expanded;
    private boolean breathless;
    private boolean skippedUnloaded;
    private boolean callerCancelled;
    private boolean budgetStop;
    private long lastBase = Long.MIN_VALUE;
    private int lastSt = Integer.MIN_VALUE;

    private SearchNode start;
    private boolean startInGoal;
    private double startArrival;
    /**
     * The cheapest stop the finder reached ({@code g + arrival}), even one it
     * was not allowed to stop at because stopping there is dearer than the
     * goal estimate says the rest should cost ({@link BridgeGoal#isInGoal}).
     * The old loop would pop such a node again after raising its price and
     * accept it then; this is that fallback, picked once the finder is done.
     */
    private SearchNode bestStop;
    private double bestStopCost = Double.POSITIVE_INFINITY;
    /** Set by {@link BridgeGoal#isInGoal} when the finder's frontier has reached {@link #bestStopCost}: stop and use {@link #bestStop}. */
    private boolean preferBestStop;

    public BridgeSearch(Search search, CostModel model, BodyStats body, Breath breath, Stance startStance,
                        BurialFloor burial, ToDoubleFunction<BlockPos> favoring) {
        this.search = search;
        this.model = model;
        this.body = body;
        this.breath = breath;
        this.startStance = startStance;
        this.burial = burial;
        this.favoring = favoring;
        this.budgeted = model.spec().budgeted();
        this.alterBudget = model.spec().alterBudget();
        this.view = search.view();
        this.recall = new Recall(body);
        this.probe = new Probe();
        this.startX = search.start().getX();
        this.startY = search.start().getY();
        this.startZ = search.start().getZ();
        this.goal = new BridgeGoal();
        for (com.dwinovo.numen.pathing.plan.Move move : Moves.ALL) {
            for (Heading heading : move.headings()) {
                this.moves.add(new MoveAdapter(move, heading));
            }
        }
    }

    /** The goal's own estimate for a real cell plus the digging floor, exactly as the live search used it before the swap. */
    private double heuristicAt(int x, int y, int z) {
        return search.goal().estimate(x, y, z) + burial.at(x, y, z);
    }

    public SearchResult run(BooleanSupplier cancelled) {
        this.cancelled = cancelled;
        seedStart();
        if (startInGoal && startArrival <= start.h + MIN_IMPROVEMENT) {
            return new SearchResult(SearchResult.Stop.ARRIVED, emptyRoute(), 0, false);
        }
        // 起点本身是停点但到达价不便宜:不当它是目标({@link BridgeGoal#isInGoal} 会拒),照常往外搜;
        // 搜完没有更便宜的停点时由 {@link #bestStop} 退回它——与旧搜索"按总价放回堆里、再出堆才收"同义。
        int ex = Coord.ex(start.base);
        int ey = Coord.ey(start.base);
        AStarPathFinder pathFinder = new AStarPathFinder(new BetterBlockPos(ex, ey, start.st), ex, ey, start.st,
                goal, Favoring.NONE, this);
        this.finder = pathFinder;
        PathCalculationResult result = pathFinder.calculate(TIMEOUT, TIMEOUT);
        if (preferBestStop) {
            // 搜到 frontier 的估价够到最便宜停点的总价:再没有更便宜的停点了,收下它。
            if (bestStop != null && bestStop.inGoal) {
                return new SearchResult(SearchResult.Stop.ARRIVED, buildRoute(bestStop), expanded, breathless);
            }
        }
        if (result.type() == PathCalculationResult.Type.SUCCESS_TO_GOAL && result.path() != null) {
            SearchNode arrived = nodes.find(Coord.base(result.path().getDest().x, result.path().getDest().y),
                    result.path().getDest().z);
            if (arrived != null) {
                return new SearchResult(SearchResult.Stop.ARRIVED, buildRoute(arrived), expanded, breathless);
            }
        }
        if (callerCancelled) {
            return new SearchResult(SearchResult.Stop.CANCELLED, null, expanded, breathless);
        }
        if (budgetStop) {
            return new SearchResult(SearchResult.Stop.BUDGET, partialRoute(), expanded, breathless);
        }
        if (bestStop != null && bestStop.inGoal) {
            // 搜遍了:够了但到达价不便宜的停点,旧搜索会在它再次出堆时收下它;这里收下最便宜的那个。
            return new SearchResult(SearchResult.Stop.ARRIVED, buildRoute(bestStop), expanded, breathless);
        }
        SearchResult.Stop stop = skippedUnloaded ? SearchResult.Stop.UNLOADED : SearchResult.Stop.EXHAUSTED;
        return new SearchResult(stop, partialRoute(), expanded, breathless);
    }

    private void seedStart() {
        BlockPos pos = search.start();
        Breath.Air air = search.air();
        Maneuver arrival = search.arrival();
        int band = breath.band(air);
        long base = Coord.base(startX, startY, startZ);
        int st = Coord.st(band, 0);
        SearchNode node = nodes.create(base, st, startX, startY, startZ, 0, band, heuristicAt(startX, startY, startZ));
        node.g = 0;
        node.stance = startStance;
        node.air = air;
        node.via = arrival;
        node.here = arrival == null ? probe : EditedView.after(probe, arrival.edits());
        boolean contains = search.goal().contains(startX, startY, startZ, startStance);
        double arrivalCost = contains ? search.goal().arrival(node.here, startX, startY, startZ, startStance)
                : Double.POSITIVE_INFINITY;
        if (Double.isFinite(arrivalCost)) {
            node.inGoal = true;
            node.arrival = arrivalCost;
            startArrival = arrivalCost;
            startInGoal = true;
            considerStop(node);
        }
        this.start = node;
        Arrays.fill(best, node);
        Arrays.fill(bestScore, node.h);
    }

    /**
     * Record the cheapest stop reached so far. Its priority is the old loop's:
     * {@code g + max(estimate, arrival)} — a stop is accepted when the finder
     * reaches that total, whether the arrival was below the estimate (accepted
     * at once) or above it (rejected, expanded, accepted on the re-pop).
     */
    private void considerStop(SearchNode node) {
        double total = node.g + Math.max(node.h, node.arrival);
        if (total < bestStopCost - MIN_IMPROVEMENT) {
            bestStopCost = total;
            bestStop = node;
        }
    }

    // ==================== CalculationContext ====================

    @Override
    public int minY() {
        return Integer.MIN_VALUE;
    }

    @Override
    public int height() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isLoaded(int x, int z) {
        return true;
    }

    @Override
    public boolean entirelyContains(int x, int z) {
        return true;
    }

    @Override
    public List<com.dwinovo.numen.pathing.search.baritone.Move> moves() {
        return moves;
    }

    // ==================== One Numen move, one vendored move ====================

    /** {@code apply} 从源节点解出真实状态,重算 Numen 的前提,把编码后的落点与价钱写回。 */
    private final class MoveAdapter implements com.dwinovo.numen.pathing.search.baritone.Move {

        private final com.dwinovo.numen.pathing.plan.Move move;
        private final Heading heading;

        MoveAdapter(com.dwinovo.numen.pathing.plan.Move move, Heading heading) {
            this.move = move;
            this.heading = heading;
        }

        @Override
        public int xOffset() {
            return 0;
        }

        @Override
        public int zOffset() {
            return 0;
        }

        @Override
        public int yOffset() {
            return 0;
        }

        @Override
        public boolean dynamicXZ() {
            return true;
        }

        @Override
        public boolean dynamicY() {
            return true;
        }

        @Override
        public void apply(CalculationContext context, int ex, int ey, int ez, MutableMoveResult result) {
            applyMove(move, heading, ex, ey, ez, result);
        }
    }

    // ==================== Expansion ====================

    private void applyMove(com.dwinovo.numen.pathing.plan.Move move, Heading heading, int ex, int ey, int ez,
                           MutableMoveResult result) {
        result.cost = ActionCosts.COST_INF;
        if (callerCancelled || budgetStop) {
            return;
        }
        long base = Coord.base(ex, ey);
        int st = ez;
        if (newSource(base, st)) {
            return;
        }
        SearchNode src = nodes.find(base, st);
        if (src == null) {
            return;
        }
        BlockPos from = new BlockPos(src.x, src.y, src.z);
        recall.clearUnloaded();
        Premise premise = move.premise(model, src.here, from, src.stance, heading);
        if (recall.unloaded()) {
            skippedUnloaded = true;
            return;
        }
        if (!(premise instanceof Premise.Holds holds)) {
            return;
        }
        Maneuver maneuver = holds.maneuver();
        BlockPos to = maneuver.to();
        if (!Bounds.holdsBody(view.border(), body, to.getX(), to.getZ())) {
            return;
        }
        int used = src.used + maneuver.alterations();
        if (budgeted && used > alterBudget) {
            return;
        }
        // The live route is priced by Numen's own cost model, exactly as before the swap; Baritone's movement prices
        // were only ever meant to guide the search, and the ported value never differed from this one anyway.
        double numen = move.cost(model, maneuver);
        if (!(numen > 0) || Double.isInfinite(numen)) {
            throw new IllegalStateException(move.kind() + " 从 " + from + " 算出了非法的代价 " + numen);
        }
        Breath.Air air = src.air;
        if (maneuver.submerged() || !breath.rested(air)) {
            air = breath.after(air, maneuver.submerged(), move.ticks(model, maneuver));
            if (!breath.lasts(air)) {
                breathless = true;
                return;
            }
        }
        WorldView here = EditedView.after(probe, maneuver.edits());
        boolean inGoal = search.goal().contains(to.getX(), to.getY(), to.getZ(), maneuver.landing());
        double arrivalCost = 0;
        if (inGoal) {
            arrivalCost = search.goal().arrival(here, to.getX(), to.getY(), to.getZ(), maneuver.landing());
            if (!Double.isFinite(arrivalCost)) {
                inGoal = false;
                arrivalCost = 0;
            }
        }
        double walk = numen * favoring.applyAsDouble(to);

        int band = breath.band(air);
        long base2 = Coord.base(to.getX(), to.getY(), to.getZ());
        int usedKey = budgeted ? used : 0;
        int st2 = Coord.st(band, usedKey);
        SearchNode dst = nodes.find(base2, st2);
        double g = src.g + walk;
        if (dst == null || dst.g - g > MIN_IMPROVEMENT) {
            if (dst == null) {
                dst = nodes.create(base2, st2, to.getX(), to.getY(), to.getZ(), usedKey, band,
                        heuristicAt(to.getX(), to.getY(), to.getZ()));
            }
            dst.g = g;
            dst.stance = maneuver.landing();
            dst.air = air;
            dst.via = maneuver;
            dst.viaCost = numen;
            dst.inGoal = inGoal;
            dst.arrival = arrivalCost;
            dst.here = here;
            dst.parent = src;
            if (inGoal) {
                considerStop(dst);
            }
            if (air.held() == 0) {
                updateBest(dst);
            }
        }
        result.x = Coord.ex(base2);
        result.y = Coord.ey(base2);
        result.z = st2;
        // The arrival price is paid only when the body stops, never for merely stepping through a goal cell: the
        // edge the finder sees is the plain movement cost, and the stop is chosen by {@link BridgeGoal}.
        result.cost = walk;
    }

    /** 记一次展开;到了先交半程的节点数或展开预算就收回这次搜索。返回是否已经叫停。 */
    private boolean newSource(long base, int st) {
        if (base == lastBase && st == lastSt) {
            return false;
        }
        lastBase = base;
        lastSt = st;
        if (cancelled.getAsBoolean()) {
            callerCancelled = true;
            finder.cancel();
            return true;
        }
        if (expanded >= search.budget()) {
            budgetStop = true;
            finder.cancel();
            return true;
        }
        if (search.handOver() < search.budget() && expanded >= search.handOver() && partial() != null) {
            budgetStop = true;
            finder.cancel();
            return true;
        }
        expanded++;
        return false;
    }

    private void updateBest(SearchNode node) {
        for (int i = 0; i < COEFFICIENTS.length; i++) {
            double score = node.h + node.g / COEFFICIENTS[i];
            if (bestScore[i] - score > MIN_IMPROVEMENT) {
                bestScore[i] = score;
                best[i] = node;
            }
        }
    }

    // ==================== Routes ====================

    private SearchNode partial() {
        for (SearchNode node : best) {
            if (node == null) {
                continue;
            }
            double dx = node.x - startX;
            double dy = node.y - startY;
            double dz = node.z - startZ;
            if (dx * dx + dy * dy + dz * dz > MIN_PARTIAL * MIN_PARTIAL) {
                return node;
            }
        }
        return null;
    }

    private Route partialRoute() {
        SearchNode end = partial();
        return end == null ? null : buildRoute(end);
    }

    private Route emptyRoute() {
        return new Route(search.start(), startStance, List.<Route.Leg>of());
    }

    private Route buildRoute(SearchNode end) {
        List<Route.Leg> legs = new ArrayList<>();
        for (SearchNode node = end; node.parent != null; node = node.parent) {
            Maneuver maneuver = node.via;
            if (maneuver == null) {
                maneuver = recover(node.parent, node);
            }
            if (maneuver == null) {
                throw new IllegalStateException("节点 " + node.x + " " + node.y + " " + node.z + " 少了走到它的那一步");
            }
            legs.add(new Route.Leg(maneuver, node.viaCost, node.air));
        }
        Collections.reverse(legs);
        return new Route(search.start(), startStance, legs);
    }

    /** 结点上的那一步丢了时,按父节点的落点与身体姿态重算一遍找回来。 */
    private Maneuver recover(SearchNode parent, SearchNode child) {
        BlockPos from = new BlockPos(parent.x, parent.y, parent.z);
        BlockPos to = new BlockPos(child.x, child.y, child.z);
        for (com.dwinovo.numen.pathing.plan.Move move : Moves.ALL) {
            for (Heading heading : move.headings()) {
                Premise premise = move.premise(model, parent.here, from, parent.stance, heading);
                if (premise instanceof Premise.Holds holds && holds.maneuver().to().equals(to)) {
                    return holds.maneuver();
                }
            }
        }
        return null;
    }

    // ==================== Vendored goal ====================

    private final class BridgeGoal implements com.dwinovo.numen.pathing.search.baritone.Goal {

        /**
         * A node counts as the goal only when stopping here is not dearer than
         * how much the estimate still expects the rest to cost. That is the
         * old loop's rule exactly: a stop whose arrival price exceeds its
         * remaining estimate is put back at the arrival price and expanded
         * further, which is how a body starting inside a dear goal member
         * walks out to a cheaper one ({@link #bestStop} is the fallback when
         * every reachable stop is dear).
         */
        @Override
        public boolean isInGoal(int ex, int ey, int ez) {
            long base = Coord.base(ex, ey);
            SearchNode node = nodes.find(base, ez);
            if (node == null) {
                return false;
            }
            // The finder's frontier (g + estimate) has reached the cheapest stop's total: nothing left can beat it.
            if (bestStop != null && bestStopCost <= node.g + node.h + MIN_IMPROVEMENT) {
                preferBestStop = true;
                return true;
            }
            return node.inGoal && node.arrival <= node.h + MIN_IMPROVEMENT;
        }

        /** Numen's own estimate, never zeroed on a goal cell: the finder needs it to keep searching past dear stops. */
        @Override
        public double heuristic(int ex, int ey, int ez) {
            long base = Coord.base(ex, ey);
            SearchNode node = nodes.find(base, ez);
            if (node != null) {
                return node.h;
            }
            return heuristicAt(Coord.x(base), Coord.y(base), Coord.z(base));
        }
    }

    // ==================== Node store ====================

    /** 真实格到它那串状态节点的链;同一格上不同(已改格数,憋气档)各一个。 */
    private static final class StateStore {

        private final Long2ObjectOpenHashMap<SearchNode> byBase = new Long2ObjectOpenHashMap<>();

        SearchNode find(long base, int st) {
            for (SearchNode node = byBase.get(base); node != null; node = node.next) {
                if (node.st == st) {
                    return node;
                }
            }
            return null;
        }

        /** 这一格上的任意一个节点;没有就空。给姿态回退用。 */
        SearchNode head(long base) {
            return byBase.get(base);
        }

        SearchNode create(long base, int st, int x, int y, int z, int used, int band, double h) {
            SearchNode node = new SearchNode(base, st, x, y, z, used, band, h);
            node.next = byBase.get(base);
            byBase.put(base, node);
            return node;
        }
    }

    // ==================== World view ====================

    /** 照读快照,读到没加载的列就记一笔;这次搜索的记事本挂在这里。 */
    private final class Probe implements WorldView, Recall.Source {

        @Override
        public Recall recall() {
            return recall;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            if (!view.isLoaded(pos.getX(), pos.getZ())) {
                recall.touchUnloaded();
            }
            return view.getBlockState(pos);
        }

        @Override
        public boolean airSection(int x, int y, int z) {
            if (!view.isLoaded(x, z)) {
                recall.touchUnloaded();
            }
            return view.airSection(x, y, z);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return view.getBlockEntity(pos);
        }

        @Override
        public int getHeight() {
            return view.getHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return view.getMinBuildHeight();
        }

        @Override
        public WorldBorder border() {
            return view.border();
        }

        @Override
        public boolean ultraWarm() {
            return view.ultraWarm();
        }
    }
}
