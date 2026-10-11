package com.dwinovo.numen.pathing.api;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.plan.Breath;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.search.AStar;
import com.dwinovo.numen.pathing.search.Favoring;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.search.Search;
import com.dwinovo.numen.pathing.search.SearchResult;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 没路时的诊断:气息、规格与宿主材料端口分别决定真实原因。 */
class DiagnosisTest {

    private static final int Y = 64;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    /**
     * 两间干屋之间只有一条三十格长的封顶水道(地、四壁与顶都是基岩):搜完无路,诊断说憋得住就有路,点出那一段水下从踏进水里
     * 的那一格到走出水的那一格、要憋约 286 刻(31 步水里的工夫加走出来那一步),满氧气能安全憋 240 刻。
     */
    @Test
    void aWayOnlyUnderWaterTooLongToHoldIsToldAsSuch() {
        TestWorld world = new TestWorld().fill(-4, Y - 1, -1, 34, Y + 2, 1, Blocks.BEDROCK.defaultBlockState());
        world.fill(-3, Y, 0, -1, Y + 1, 0, Blocks.AIR.defaultBlockState());
        world.fill(0, Y, 0, 30, Y + 1, 0, Blocks.WATER.defaultBlockState());
        world.fill(31, Y, 0, 33, Y + 1, 0, Blocks.AIR.defaultBlockState());
        Search search = new Search(world, Fixtures.model(RouteSpec.defaults()), new BlockPos(-2, Y, 0),
                Goals.at(new BlockPos(32, Y, 0)), Fixtures.BUDGET, Favoring.NONE);
        SearchResult failed = AStar.run(search, () -> false);
        assertEquals(SearchResult.Stop.EXHAUSTED, failed.stop());
        Outcome outcome = Diagnosis.of(failed.stop(), failed.breathless(), search, () -> false);
        Outcome.Breathless breathless = assertInstanceOf(Outcome.Breathless.class, outcome);
        assertEquals(new BlockPos(-1, Y, 0), breathless.from());
        assertEquals(new BlockPos(31, Y, 0), breathless.to());
        assertTrue(breathless.held() > 280 && breathless.held() < 295, "要憋的刻数:" + breathless.held());
        assertEquals(300 - (int) Breath.RESERVE, breathless.spare());
    }

    /**
     * 唯一的去路被一格"悬而未决"的墙堵着:这一格不是被拒——诊断不能把放行它当成"有路",于是既不是
     * {@link Outcome.Denied},也不是说"许的改动不够",而是 {@link Outcome.NoRoute}。
     */
    @Test
    void aPathBlockedOnlyByAPendingCellIsNoRouteNotDenied() {
        TestWorld world = new TestWorld().fill(-2, Y - 1, -1, 13, Y + 2, 1, Blocks.BEDROCK.defaultBlockState());
        world.fill(-1, Y, 0, 12, Y + 1, 0, Blocks.AIR.defaultBlockState())
                .fill(5, Y, 0, 5, Y + 1, 0, Blocks.DIRT.defaultBlockState());
        BlockPos pending = new BlockPos(5, Y, 0);
        TerrainPolicy policy = (change, pos, state, view) ->
                pos.equals(pending) || pos.equals(pending.above()) ? Permit.pending("主人不在场") : Permit.ALLOW;
        CostModel model = CostModel.of(RouteSpec.defaults().edit().changes(true).consent(false).build(),
                Fixtures.body(), policy, Materials.NONE, Threats.NONE);
        Search search = new Search(world, model, new BlockPos(0, Y, 0), Goals.at(new BlockPos(10, Y, 0)),
                Fixtures.BUDGET, Favoring.NONE);
        SearchResult failed = AStar.run(search, () -> false);
        assertEquals(SearchResult.Stop.EXHAUSTED, failed.stop());
        Outcome outcome = Diagnosis.of(failed.stop(), failed.breathless(), search, () -> false);
        assertInstanceOf(Outcome.NoRoute.class, outcome);
    }

    /** 四格宽的沟摔不起、两边基岩挖不动:许搭桥但宿主没交出材料,诊断才是没有料。 */
    @Test
    void aDitchWithoutMaterialsReportsNoMaterials() {
        TestWorld world = new TestWorld().floor(0, 0, 39, 39, Y - 5)
                .fill(0, Y - 4, 0, 9, Y - 1, 39, Blocks.BEDROCK.defaultBlockState())
                .fill(14, Y - 4, 0, 39, Y - 1, 39, Blocks.BEDROCK.defaultBlockState());
        Search search = new Search(world, Fixtures.model(Fixtures.natural()), new BlockPos(6, Y, 5),
                Goals.at(new BlockPos(17, Y, 5)), Fixtures.BUDGET, Favoring.NONE);
        SearchResult failed = AStar.run(search, () -> false);

        assertEquals(SearchResult.Stop.EXHAUSTED, failed.stop());
        assertInstanceOf(Outcome.NoMaterials.class,
                Diagnosis.of(failed.stop(), failed.breathless(), search, () -> false));
        assertTrue(world.getBlockState(new BlockPos(9, Y - 1, 5)).is(Blocks.BEDROCK));
        for (int x = 10; x <= 13; x++) {
            assertTrue(world.getBlockState(new BlockPos(x, Y - 1, 5)).isAir(), "诊断不能真的搭桥");
        }
    }

    /** 不许改地形时,有料与没料都先报需要放块,交出跨沟的真实放置清单而不是没有料。 */
    @Test
    void aDitchWithChangesDisabledReportsTheBridgeWithAndWithoutMaterials() {
        for (Materials materials : List.of(Materials.NONE, Fixtures.COBBLE)) {
            TestWorld world = new TestWorld().floor(0, 0, 39, 39, Y - 5)
                    .fill(0, Y - 4, 0, 9, Y - 1, 39, Blocks.BEDROCK.defaultBlockState())
                    .fill(14, Y - 4, 0, 39, Y - 1, 39, Blocks.BEDROCK.defaultBlockState());
            CostModel model = CostModel.of(RouteSpec.defaults(), Fixtures.body(), TerrainPolicy.ALLOW_ALL,
                    materials, Threats.NONE);
            Search search = new Search(world, model, new BlockPos(6, Y, 5),
                    Goals.at(new BlockPos(17, Y, 5)), Fixtures.BUDGET, Favoring.NONE);
            SearchResult failed = AStar.run(search, () -> false);

            assertEquals(SearchResult.Stop.EXHAUSTED, failed.stop());
            Outcome.NeedsChanges needs = assertInstanceOf(Outcome.NeedsChanges.class,
                    Diagnosis.of(failed.stop(), failed.breathless(), search, () -> false));
            assertTrue(needs.places() && !needs.digs() && !needs.asks(), "过沟缺的是放块许可");
            assertTrue(!needs.changes().isEmpty(), "需要改动的诊断必须交出那几格");
            for (Edit change : needs.changes()) {
                Edit.Place place = assertInstanceOf(Edit.Place.class, change);
                assertEquals(Blocks.COBBLESTONE, place.block());
                assertTrue(place.pos().getX() >= 10 && place.pos().getX() <= 13
                        && place.pos().getY() == Y - 1, "放置清单应当在两块台子间的沟上");
                assertTrue(place.replaced().isAir() && world.getBlockState(place.pos()).isAir(),
                        "诊断清单是计划,不能真的改世界");
            }
            assertTrue(world.getBlockState(new BlockPos(9, Y - 1, 5)).is(Blocks.BEDROCK));
        }
    }

    /** 背包深处有圆石而宿主不准用:材料只认端口;同一身体由端口交出圆石才真的有桥路。 */
    @Test
    void materialAvailabilityComesFromTheHostRatherThanTheInventorySnapshot() {
        TestWorld world = new TestWorld().floor(0, 0, 39, 39, Y - 5)
                .fill(0, Y - 4, 0, 9, Y - 1, 39, Blocks.BEDROCK.defaultBlockState())
                .fill(14, Y - 4, 0, 39, Y - 1, 39, Blocks.BEDROCK.defaultBlockState());
        var body = Fixtures.carrying(20, new ItemStack(Items.COBBLESTONE, 16));
        CostModel model = CostModel.of(Fixtures.natural(), body, TerrainPolicy.ALLOW_ALL, Materials.NONE,
                Threats.NONE);
        Search search = new Search(world, model, new BlockPos(6, Y, 5),
                Goals.at(new BlockPos(17, Y, 5)), Fixtures.BUDGET, Favoring.NONE);
        SearchResult failed = AStar.run(search, () -> false);

        assertEquals(SearchResult.Stop.EXHAUSTED, failed.stop());
        assertInstanceOf(Outcome.NoMaterials.class,
                Diagnosis.of(failed.stop(), failed.breathless(), search, () -> false));
        CostModel permitted = CostModel.of(Fixtures.natural(), body, TerrainPolicy.ALLOW_ALL, Fixtures.COBBLE,
                Threats.NONE);
        SearchResult bridge = Fixtures.search(world, permitted, search.start(), search.goal());
        assertEquals(SearchResult.Stop.ARRIVED, bridge.stop(), "同一身体、地形与规格,只有宿主交出的材料端口变了");
        assertTrue(bridge.route().edits().stream().anyMatch(edit -> edit instanceof Edit.Place));
        assertEquals(16, body.inventory().get(20).getCount(), "规划与诊断不能消耗背包里的圆石");
        for (int x = 10; x <= 13; x++) {
            assertTrue(world.getBlockState(new BlockPos(x, Y - 1, 5)).isAir(), "规划不能真的搭桥");
        }
    }
}
