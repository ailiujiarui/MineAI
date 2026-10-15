package com.dwinovo.numen.task.move;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.world.Kinematics;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.api.NavRequest;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.search.SearchResult;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 走到物品旁边是走到捡得到它的地方:贴着台沿的物品坐标取整落在悬空的格里,从它脚下那一格就捡得到。 */
class ItemReachTest {

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    /** 一块物品静止在台沿:中心 x = 10.977,盒子宽 0.25,底在 y = -57,取整的格是 (10, -57)。 */
    private static final AABB LIP = new AABB(10.977 - 0.125, -57, 0.5 - 0.125, 10.977 + 0.125, -57 + 0.25, 0.5 + 0.125);

    private static Stance ground(int feetCellY) {
        return new Stance(Stance.Kind.GROUND, feetCellY, feetCellY - 1);
    }

    @Test
    void theCellBelowAnItemOnALedgeReachesIt() {
        ItemReach reach = new ItemReach(LIP);
        assertTrue(reach.contains(10, -58, 0, ground(-58)), "物品所在格的脚下那一格站得进去,捡得到");
        assertFalse(reach.contains(8, -58, 0, ground(-58)), "再远一格捡不到");
    }

    @Test
    void anItemOnTheFloorOfTheCellIsReachedFromItsNeighbourOnlyWhenTheWholeCellIsInReach() {
        // 物品在格 (0,-58,0) 靠近 x = 1 的那一边:邻格 (1,...) 整格都碰得上,隔一格的 (2,...) 有一端够不着
        AABB item = new AABB(0.9, -58, 0.4, 1.15, -57.75, 0.65);
        ItemReach reach = new ItemReach(item);
        assertTrue(reach.contains(1, -58, 0, ground(-58)));
        assertFalse(reach.contains(-1, -58, 0, ground(-58)));
    }

    @Test
    void theEstimateIsZeroWhereItIsReachedAndNeverMoreThanTheWayThere() {
        ItemReach reach = new ItemReach(LIP);
        Kinematics.Pace pace = new Kinematics.Pace(4, 3);
        assertEquals(0, reach.estimate(10, -58, 0, pace), 1e-9);
        double far = reach.estimate(20, -58, 0, pace);
        assertTrue(far > 0 && far <= pace.sprint() * (20 - 10), "估价不高过走过去的格数");
    }

    /**
     * 掉在正下方十五格石头里一个小空腔底的东西:终点格与高度范围让估价知道要往下挖。捡得到的格水平上有三格宽,搜索在这一片里
     * 找最便宜的下法,展开几千个节点;终点格与高度范围交 null 时是二十几万。
     */
    @Test
    void anItemAtTheBottomOfACavityDeepBelowIsDugDownToWithoutSpreadingOverTheGround() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        TestWorld ground = new TestWorld().ground(63, stone).loadedWithin(6)
                .fill(0, 48, 0, 0, 49, 0, Blocks.AIR.defaultBlockState());
        CostModel model = CostModel.of(Fixtures.natural(), Fixtures.carrying(0, new ItemStack(Items.IRON_PICKAXE)),
                Fixtures.ALLOW_ALL, Fixtures.NO_MATERIALS, Threats.NONE);
        AABB item = new AABB(0.375, 48, 0.375, 0.625, 48.25, 0.625);
        SearchResult result = Fixtures.search(ground, model, new BlockPos(0, 64, 0), new ItemReach(item), NavRequest.DEFAULT_BUDGET);
        assertTrue(result.arrived(), result.stop() + ",展开了 " + result.expanded());
        assertTrue(result.expanded() < 10000, "展开了 " + result.expanded());
    }
}
