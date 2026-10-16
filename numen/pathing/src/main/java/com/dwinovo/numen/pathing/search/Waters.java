package com.dwinovo.numen.pathing.search;

import java.util.Arrays;

import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.world.Kinematics;
import com.dwinovo.numen.pathing.world.Semantics;
import com.dwinovo.numen.pathing.world.Semantics.Kind;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 水里的额外代价:从一个节点走到目标,路上至少还要在水柱里挪几格,每格按水里挪一步比疾跑底价多出来的部分计价。
 * 估价({@link Estimate})把它加在水平底价、埋深与升降的额外代价上。
 *
 * <p>为什么要它:水平底价按最快的走法给每格定价——疾跑一格({@link Kinematics#sprintTicksPerBlock},约 3.6 刻),泡在水里
 * 挪一格却要 {@link CostModel#waterStep}(约 10 刻),是它的三倍上下。没有这一笔,水面和岸上一样便宜,A* 要把整片湖、
 * 整条河连同水面上下的节点全展开一遍,才肯认从岸边绕过去的那条路:横渡一片海,几万个节点也搜不到头。
 *
 * <p>怎么算:一个放宽了的问题的精确解。从目标的每个终点({@link Goal#endCells})往外做一次 Dijkstra,一步要么是八个水平
 * 方向之一,要么是在原地升降一层,每格都能过。水里走的差价按步收:横着挪一步,两头都在水柱里({@code waterish})才收
 * {@code wr};竖直挪一层,两头都是真水({@code water})才收 {@code wr}。所谓水柱:从这一格往下扫,第一格水或挡格说了算
 * ——是水就算在,是挡格就算不在,中途的空气、半砖这些都不挡;挡格是有碰撞箱({@link BlockState#getCollisionShape} 不空)
 * 或可攀爬({@link BlockTags#CLIMBABLE})的格,没加载的列也算挡格。于是泡在水里、贴着水面游、水面上空都算,踩在实心地面、
 * 荷叶、栅栏、梯子上都不算;进水和出水都不收钱,横着穿水收钱,从水底之下、旱地之上穿过去不花钱;往下跳进水里不收钱,
 * 在水柱里上上下下按挪过的格数收钱,"从海床底下钻过去"这类放宽就走不通了。
 *
 * <p>为什么是下界:真实的每一步在水里挪过一段,至少付疾跑底价加 {@code wr}({@code wr} 是水里一格减去这一步最多摊到的
 * 疾跑底价,{@link #of} 里定下);放宽的场只在两头都在水里时按一步 {@code wr} 收,进水、出水、跳进水里都不收,真实里只会
 * 更慢,所以场上收的钱只少不多。加到估价上,A* 仍然搜出最便宜的路,只是不必再展开那些横渡绕不开、却按走路估得很便宜的
 * 水面节点。
 *
 * <p>算到哪里停:按价钱从终点往外一格格定下,定到起点那一格就停,还没定下的格至少是停下时那一档;同一档里先定离起点近
 * 的(与埋深一样,不必铺满整片水);最多定下 {@code cap} 格,到了也停,没定下的格同样取停下时那一档,仍是下界。水里不比
 * 疾跑慢({@code wr} 为零)或目标没交终点格时不算。
 */
final class Waters {

    /** 不加。 */
    static final Waters NONE = new Waters(new Long2DoubleOpenHashMap(), 0);

    private final Long2DoubleOpenHashMap settled;
    /** 没定下的格至少还要多游这么多钱(刻)。 */
    private final double rest;

    private Waters(Long2DoubleOpenHashMap settled, double rest) {
        this.settled = settled;
        this.rest = rest;
    }

    /** 节点 {@code (x, y, z)} 走进目标至少还要多游多少钱(刻)。 */
    double floor(int x, int y, int z) {
        return settled.getOrDefault(BlockPos.asLong(x, y, z), rest);
    }

    /** 这次搜索的水里额外代价:在 {@code view} 上从 {@code goal} 的终点往外算,到 {@code start} 为止,最多定下 {@code cap} 格。 */
    static Waters of(SearchView view, CostModel model, Goal goal, BlockPos start, int cap) {
        double wr = Math.max(0, model.waterStep()
                - Kinematics.sprintTicksPerBlock(model.body().stats()) * Math.sqrt(2));
        if (wr <= 0) {
            return NONE;
        }
        LongSet ends = goal.endCells();
        return ends == null ? NONE : new Field(view, model, start, wr).run(ends, cap);
    }

    /** 一次 Dijkstra 的工作区。 */
    private static final class Field {

        private final SearchView view;
        private final CostModel model;
        private final BlockPos start;
        private final long startKey;
        /** 水里挪一步比疾跑底价多出来的部分。 */
        private final double wr;
        private final int minY;
        private final int maxY;
        /** 每列的水柱边界,问到哪一列才记哪一列。 */
        private final Long2ObjectOpenHashMap<Column> columns = new Long2ObjectOpenHashMap<>();
        private final Long2DoubleOpenHashMap best = new Long2DoubleOpenHashMap();
        private final Long2DoubleOpenHashMap settled = new Long2DoubleOpenHashMap();
        private final CostHeap queue = new CostHeap();

        Field(SearchView view, CostModel model, BlockPos start, double wr) {
            this.view = view;
            this.model = model;
            this.start = start;
            this.startKey = start.asLong();
            this.wr = wr;
            this.minY = view.getMinBuildHeight();
            this.maxY = view.getMaxBuildHeight();
            best.defaultReturnValue(Double.POSITIVE_INFINITY);
        }

        Waters run(LongSet ends, int cap) {
            for (long end : ends) {
                best.put(end, 0);
                queue.push(0, distance(end), end);
            }
            double reached = 0;
            while (!queue.isEmpty()) {
                double cost = queue.peekCost();
                long cell = queue.pop();
                if (cost > best.get(cell) || settled.containsKey(cell)) {
                    continue;
                }
                reached = cost;
                if (cell == startKey || settled.size() >= cap) {
                    break;
                }
                settled.put(cell, cost);
                relax(cell, cost);
            }
            return new Waters(settled, reached);
        }

        /** 从刚定下的 {@code cell} 往回松弛:能一步挪进它的每一格,到它的价钱加上挪进那一格要付的水里差价。 */
        private void relax(long cell, double cost) {
            int x = BlockPos.getX(cell);
            int y = BlockPos.getY(cell);
            int z = BlockPos.getZ(cell);
            boolean hereWater = water(x, y, z);
            boolean here = waterish(x, y, z);
            // 八个水平方向:两头都在水柱里才收
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dz != 0) {
                        offer(x + dx, y, z + dz, cost + (here && waterish(x + dx, y, z + dz) ? wr : 0));
                    }
                }
            }
            // 原地升降一层:两头都是真水才收,跳进水里、浮出水面都不收
            offer(x, y - 1, z, cost + (hereWater && water(x, y - 1, z) ? wr : 0));
            offer(x, y + 1, z, cost + (hereWater && water(x, y + 1, z) ? wr : 0));
        }

        private void offer(int x, int y, int z, double cost) {
            if (y < minY || y >= maxY || !view.isLoaded(x, z)) {
                return;
            }
            long cell = BlockPos.asLong(x, y, z);
            if (cost < best.get(cell) && !settled.containsKey(cell)) {
                best.put(cell, cost);
                queue.push(cost, distance(cell), cell);
            }
        }

        /** 这一格是不是路线没排除的水:静水与流水都算;没加载的列不算。 */
        private boolean water(int x, int y, int z) {
            if (!view.isLoaded(x, z)) {
                return false;
            }
            BlockPos pos = new BlockPos(x, y, z);
            int kinds = Semantics.mask(view, pos) & (Semantics.bit(Kind.WATER) | Semantics.bit(Kind.FLOWING_WATER));
            return kinds != 0 && !model.excludes(kinds);
        }

        /** 这一格在不在水柱里:从这一格往下扫,第一格水或挡格说了算——是水就在,是挡格就不在;没加载的列算挡格。 */
        private boolean waterish(int x, int y, int z) {
            long key = BlockPos.asLong(x, 0, z);
            Column column = columns.get(key);
            if (column == null) {
                column = new Column();
                columns.put(key, column);
            }
            for (int c = y; c >= minY; c--) {
                int known = column.valueAt(c);
                if (known >= 0) {
                    return known == 1;
                }
                BlockPos pos = new BlockPos(x, c, z);
                BlockState state = view.loadedBlockState(pos);
                boolean value;
                if (state == null) {
                    value = false;
                } else if (water(x, c, z)) {
                    value = true;
                } else if (!state.getCollisionShape(view, pos).isEmpty() || state.is(BlockTags.CLIMBABLE)) {
                    value = false;
                } else {
                    continue;
                }
                column.add(c, y, value);
                return value;
            }
            column.add(minY, y, false);
            return false;
        }

        /** 同一档里的先后:离起点的曼哈顿距离。 */
        private int distance(long cell) {
            return Math.abs(BlockPos.getX(cell) - start.getX()) + Math.abs(BlockPos.getY(cell) - start.getY())
                    + Math.abs(BlockPos.getZ(cell) - start.getZ());
        }

        /**
         * 一列的水柱边界,查到哪一格才补到哪一格:一条边界记的是第一个决定水柱走向的格(水、挡格或没加载的列)与问出它的
         * 那一层——只作证这两层之间那段:段里既不是水也不是挡格,水柱值同这一条。问层比它高时不能拿它作答:那段之上可能
         * 另有一格水或挡格。
         */
        private static final class Column {
            private int[] ys = new int[4];
            private int[] tops = new int[4];
            private boolean[] values = new boolean[4];
            private int size;

            /** {@code y} 有边界作证就答 0 或 1,没有答 -1。 */
            int valueAt(int y) {
                for (int i = 0; i < size; i++) {
                    if (ys[i] <= y && y <= tops[i]) {
                        return values[i] ? 1 : 0;
                    }
                }
                return -1;
            }

            void add(int y, int top, boolean value) {
                if (size == ys.length) {
                    ys = Arrays.copyOf(ys, size * 2);
                    tops = Arrays.copyOf(tops, size * 2);
                    values = Arrays.copyOf(values, size * 2);
                }
                ys[size] = y;
                tops[size] = top;
                values[size] = value;
                size++;
            }
        }
    }
}
