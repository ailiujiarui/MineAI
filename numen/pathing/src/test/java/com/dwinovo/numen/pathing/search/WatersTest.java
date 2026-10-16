package com.dwinovo.numen.pathing.search;

import java.util.List;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.plan.BodySnapshot;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.EditedView;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Kinematics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 水里的额外代价:泡在水里的格走到目标,路上至少还要在水柱里挪几格、每格按水里一步比疾跑底价多出来的差价计;
 * 进水和出水不收费,水面上空不另收钱,水底之下的干洞、架在水上的桥面都不算水;目标没交终点格、或水里不比疾跑
 * 慢时不算。沿搜出的路线,估价(水平底价加埋深加升降加水里)从不超过这条路剩下的价钱。
 *
 * <p>场景限在原点区块里({@link TestWorld#loadedWithin}),超出的格不算;定格的预算给足,场把整片干地也定完
 * 再定水的格。
 */
class WatersTest {

    private static final int Y = 64;
    /** 东岸上的目标。 */
    private static final BlockPos GOAL = new BlockPos(9, Y, 5);
    /** 塘心的水面节点。 */
    private static final BlockPos SURFACE = new BlockPos(3, Y, 5);
    /** 塘心的水底节点。 */
    private static final BlockPos BOTTOM = new BlockPos(3, Y - 2, 5);
    /** 定格的预算给足:场里的干地要全定完,水的格才轮到。 */
    private static final int CAP = 1_000_000;
    private static BlockState STONE;
    private static BlockState WATER;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
        STONE = Blocks.STONE.defaultBlockState();
        WATER = Blocks.WATER.defaultBlockState();
    }

    /** 一道五格宽(x 1..5)、七格长(z 3..9)的水塘,水只到 Y-1 那一层,水面节点在 Y。 */
    private static TestWorld pond() {
        return new TestWorld().ground(Y - 1, STONE).fill(1, Y - 1, 3, 5, Y - 1, 9, WATER).loadedWithin(0);
    }

    /** 同一道塘,水深两层(Y-2、Y-1)。 */
    private static TestWorld deepPond() {
        return new TestWorld().ground(Y - 1, STONE).fill(1, Y - 2, 3, 5, Y - 1, 9, WATER).loadedWithin(0);
    }

    /** 浅塘底下新掏一层干洞。 */
    private static TestWorld tunnel() {
        return pond().fill(1, Y - 2, 3, 5, Y - 2, 9, Blocks.AIR.defaultBlockState());
    }

    /** 深塘上头(z 5..7)架一条一格厚的石桥面。 */
    private static TestWorld bridge() {
        return deepPond().fill(1, Y, 5, 5, Y, 7, STONE);
    }

    private static CostModel model() {
        return Fixtures.model(RouteSpec.defaults());
    }

    /** 水里走一步比疾跑底价多出来的差价,与 {@link Waters} 里定下的一致。 */
    private static double wr(CostModel model) {
        return Math.max(0, model.waterStep() - Kinematics.sprintTicksPerBlock(model.body().stats()) * Math.sqrt(2));
    }

    private static Waters waters(TestWorld world, CostModel model, Goal goal, BlockPos start) {
        return Waters.of(world, model, goal, start, CAP);
    }

    /** 水下移动效率拉满的身体:水里与陆上一样快,差价为零。 */
    private static CostModel swift() {
        BodySnapshot body = new BodySnapshot(Vanilla.SURVIVAL, GameType.SURVIVAL, 20, 3, 1, 20, 1.0, List.of(),
                Fixtures.MINING, Fixtures.BREATH);
        return CostModel.of(RouteSpec.defaults(), body, Fixtures.ALLOW_ALL, Fixtures.NO_MATERIALS, Threats.NONE);
    }

    /** 横穿水塘:水面一格接一格地收,贴着岸的水面那一步是出水,不收费;水面上空一样按格算。 */
    @Test
    void crossingThePondChargesEveryStrideBetweenTheWaterColumns() {
        CostModel model = model();
        Waters waters = waters(pond(), model, Goals.at(GOAL), SURFACE);
        double wr = wr(model);
        assertEquals(0, waters.floor(6, Y, 5), 1e-9, "东岸");
        assertEquals(0, waters.floor(5, Y, 5), 1e-9, "贴着岸的水面一步是出水");
        assertEquals(wr, waters.floor(4, Y, 5), 1e-9, "横穿一格水");
        assertEquals(2 * wr, waters.floor(3, Y, 5), 1e-9, "起点自己还要穿两格水");
        assertEquals(wr, waters.floor(4, Y + 5, 5), 1e-9, "水面上空一样按格算");
    }

    /** 水上一层水下一层:水面以上的空气不另收钱,往下多沉一层多穿一格水。 */
    @Test
    void theAirAboveAndTheDepthBelowAreBothTheSameWater() {
        CostModel model = model();
        Waters waters = waters(deepPond(), model, Goals.at(GOAL), BOTTOM);
        double wr = wr(model);
        assertEquals(wr, waters.floor(4, Y, 5), 1e-9, "水面上过一格");
        assertEquals(wr, waters.floor(4, Y + 5, 5), 1e-9, "水面上空不另收钱");
        assertEquals(wr, waters.floor(4, Y - 1, 5), 1e-9, "水面下一层同样过一格");
        assertEquals(2 * wr, waters.floor(4, Y - 2, 5), 1e-9, "再深一层多一格");
        assertEquals(2 * wr, waters.floor(3, Y - 1, 5), 1e-9);
        assertEquals(3 * wr, waters.floor(3, Y - 2, 5), 1e-9, "起点自己还要穿三格水");
    }

    /** 水底之下新掏的干洞不算水:从洞里穿过去不收这一笔(挖的价钱在埋深里另算)。 */
    @Test
    void aDryTunnelUnderTheBedIsNotWater() {
        CostModel model = model();
        Waters waters = waters(tunnel(), model, Goals.at(GOAL), SURFACE);
        assertEquals(0, waters.floor(4, Y - 2, 5), 1e-9, "水底下面的干洞");
        assertEquals(wr(model), waters.floor(4, Y, 5), 1e-9, "水面上照收");
    }

    /** 水面上架起来的桥面不算水:桥面上走过去不收,桥底下贴着水面的水柱也不要钱。 */
    @Test
    void aBridgeOverTheWaterIsNotWater() {
        CostModel model = model();
        Waters waters = waters(bridge(), model, Goals.at(GOAL), BOTTOM);
        double wr = wr(model);
        assertEquals(0, waters.floor(3, Y + 1, 5), 1e-9, "桥面之上");
        assertEquals(0, waters.floor(3, Y, 5), 1e-9, "桥面那一格是挡格");
        assertEquals(0, waters.floor(4, Y, 5), 1e-9);
        assertEquals(0, waters.floor(4, Y - 1, 5), 1e-9, "桥底下浮出水面不要钱");
        assertEquals(wr, waters.floor(4, Y, 4), 1e-9, "桥旁边的水面上空照收一格");
    }

    /** 目标没交终点格时不算。 */
    @Test
    void withoutEndCellsThereIsNoWaterCharge() {
        CostModel model = model();
        Waters waters = waters(deepPond(), model, Goals.column(GOAL.getX(), GOAL.getZ()), BOTTOM);
        assertEquals(0, waters.floor(3, Y - 2, 5), 1e-9);
        assertEquals(0, waters.floor(4, Y, 5), 1e-9);
        assertEquals(0, waters.floor(4, Y + 5, 5), 1e-9);
    }

    /** 水里不比疾跑慢时不算。 */
    @Test
    void noWaterChargeWhenWaterIsNoSlowerThanSprinting() {
        Waters waters = waters(deepPond(), swift(), Goals.at(GOAL), BOTTOM);
        assertEquals(0, waters.floor(3, Y - 2, 5), 1e-9);
        assertEquals(0, waters.floor(4, Y, 5), 1e-9);
    }

    /**
     * 从水底游到岸上、从水底浮到水面、从东岸的终点往回:沿搜出来的路线,每一个节点上目标的估价加水里的代价,
     * 都不超过这条路从那里往后还要付的价钱——它是下界,不会把更便宜的路挤掉。
     */
    @Test
    void theEstimateWithWaterNeverExceedsWhatTheRouteStillCosts() {
        TestWorld world = deepPond();
        CostModel model = model();
        for (Goal goal : List.of(Goals.at(GOAL), Goals.at(SURFACE), Goals.at(new BlockPos(0, Y, 5)))) {
            SearchResult result = Fixtures.search(world, model, BOTTOM, goal, CAP);
            assertTrue(result.arrived(), goal + " " + result.stop());
            Route route = result.route();
            Estimate estimate = Estimate.of(world, model, goal, BOTTOM, CAP, TripMemory.Frozen.NONE, 1);
            List<BlockPos> nodes = route.nodes();
            BlockPos end = route.end();
            List<Route.Leg> legs = route.legs();
            var there = EditedView.after(world, legs.get(legs.size() - 1).maneuver().edits());
            double left = goal.arrival(there, end.getX(), end.getY(), end.getZ(), route.endStance());
            for (int i = nodes.size() - 1; i >= 0; i--) {
                if (i < nodes.size() - 1) {
                    left += legs.get(i).cost();
                }
                BlockPos n = nodes.get(i);
                assertTrue(estimate.at(n) <= left + 1e-9, goal + " 在 " + n + " 估 " + estimate.at(n) + ",还要付 " + left);
            }
        }
    }
}
