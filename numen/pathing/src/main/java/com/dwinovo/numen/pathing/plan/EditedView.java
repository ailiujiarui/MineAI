package com.dwinovo.numen.pathing.plan;

import java.util.Arrays;
import java.util.List;

import com.dwinovo.numen.pathing.world.Recall;
import com.dwinovo.numen.pathing.world.Semantics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;

/**
 * 做了一些改动之后的世界:把改动叠在一个只读视图上,自己也是只读视图。一件改动让世界变成什么样只写在这里——挖掉的格
 * 只剩它原来含着的液体,放下的格是那种方块的默认状态,开关过的门连同另一半一起翻转。
 *
 * <p>规划一步时,草稿在它上面叠这一步设想的改动;搜索展开一个节点时,在快照上叠"走到这个节点的那一步"做过的改动,
 * 下一步的前提看到的就是身体此刻真正面对的世界。叠在另一份改动之上时,直接读它底下的视图、改过的格共用那份的表:
 * 前提函数每一步要读几百格,读一格只经一层。表在自己第一次要改时才抄一份(写时复制)——一个节点展开的几十次前提判断,
 * 绝大多数一格也不改,不必每次抄一遍。
 */
public class EditedView implements WorldView, Recall.Source {

    private final WorldView base;
    /** 改过的格;一件改动都没有时不建表(规划一步时大多数草稿一件也不改)。 */
    private Changes changed;
    /** 表还有另一份视图在读(叠在这一份之上的,或这一份叠在它之上的):要改先抄一份。 */
    private boolean shared;

    EditedView(WorldView base) {
        if (base instanceof EditedView under) {
            this.base = under.base;
            this.changed = under.changed;
            if (changed != null) {
                this.shared = true;
                under.shared = true;
            }
        } else {
            this.base = base;
        }
    }

    /** {@code base} 做完 {@code edits} 之后的样子;没有改动就是 {@code base} 本身。 */
    public static WorldView after(WorldView base, List<Edit> edits) {
        if (edits.isEmpty()) {
            return base;
        }
        EditedView view = new EditedView(base);
        for (Edit edit : edits) {
            switch (edit) {
                case Edit.Dig dig -> view.dig(dig.pos());
                case Edit.Place place -> view.place(place.pos(), place.block());
                case Edit.Door door -> view.toggle(door.pos());
                // 倒下的水在这一步里就收回了
                case Edit.Catch caught -> {
                }
            }
        }
        return view;
    }

    // ==================== 改动 ====================

    /** 挖掉:只剩这一格原来含着的液体。 */
    void dig(BlockPos pos) {
        set(pos, getBlockState(pos).getFluidState().createLegacyBlock());
    }

    /** 放下一块 {@code block}。 */
    void place(BlockPos pos, Block block) {
        set(pos, block.defaultBlockState());
    }

    /** 倒一桶水:这一格成了水源。 */
    void pour(BlockPos pos) {
        set(pos, Blocks.WATER.defaultBlockState());
    }

    /** 开关一扇门;门的另一半照原版一起翻转。 */
    void toggle(BlockPos pos) {
        BlockState state = getBlockState(pos);
        set(pos, Semantics.toggled(state));
        if (state.getBlock() instanceof DoorBlock) {
            BlockPos other = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            BlockState half = getBlockState(other);
            if (half.is(state.getBlock())) {
                set(other, Semantics.toggled(half));
            }
        }
    }

    final void set(BlockPos pos, BlockState state) {
        if (changed == null) {
            changed = new Changes();
        } else if (shared) {
            changed = changed.copy();
            shared = false;
        }
        changed.put(pos.asLong(), state);
    }

    /** 记事本是底下那份视图的:记的是没叠改动的快照,叠了改动的格子由 {@link #changed(int, int, int, int)} 告诉结论哪些不能用。 */
    @Override
    public Recall recall() {
        return base instanceof Recall.Source source ? source.recall() : null;
    }

    @Override
    public boolean changed(int x, int yMin, int yMax, int z) {
        return changed != null && changed.within(x, yMin, yMax, z);
    }

    /** 这一格改过。 */
    boolean changed(BlockPos pos) {
        return changed != null && changed.get(pos.asLong()) != null;
    }

    // ==================== 视图 ====================

    @Override
    public BlockState getBlockState(BlockPos pos) {
        if (changed == null) {
            return base.getBlockState(pos);
        }
        BlockState state = changed.get(pos.asLong());
        return state != null ? state : base.getBlockState(pos);
    }

    /** 这一段里改过哪一格,就不敢说整段是空气;没改过的照底下的视图答。 */
    @Override
    public boolean airSection(int x, int y, int z) {
        if (changed != null) {
            long section = SectionPos.asLong(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(y),
                    SectionPos.blockToSectionCoord(z));
            if (changed.within(section)) {
                return false;
            }
        }
        return base.airSection(x, y, z);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return changed(pos) ? null : base.getBlockEntity(pos);
    }

    @Override
    public int getHeight() {
        return base.getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return base.getMinBuildHeight();
    }

    @Override
    public WorldBorder border() {
        return base.border();
    }

    @Override
    public boolean ultraWarm() {
        return base.ultraWarm();
    }

    /**
     * 改过的格与改成了什么。一步的改动不过几格(挖两格、放一块、开一扇门),并排的两个数组线性找比哈希表快——
     * 每读一格方块都要先问它,读得比改得多几个数量级。
     */
    private static final class Changes {

        private long[] cells = new long[4];
        private BlockState[] states = new BlockState[4];
        private int size;

        BlockState get(long cell) {
            for (int i = 0; i < size; i++) {
                if (cells[i] == cell) {
                    return states[i];
                }
            }
            return null;
        }

        void put(long cell, BlockState state) {
            for (int i = 0; i < size; i++) {
                if (cells[i] == cell) {
                    states[i] = state;
                    return;
                }
            }
            if (size == cells.length) {
                cells = Arrays.copyOf(cells, size * 2);
                states = Arrays.copyOf(states, size * 2);
            }
            cells[size] = cell;
            states[size++] = state;
        }

        Changes copy() {
            Changes copy = new Changes();
            copy.cells = cells.clone();
            copy.states = states.clone();
            copy.size = size;
            return copy;
        }

        /** 改过的格里有没有在 {@code (x, z)} 这一列 {@code yMin} 到 {@code yMax} 之间的。 */
        boolean within(int x, int yMin, int yMax, int z) {
            for (int i = 0; i < size; i++) {
                long cell = cells[i];
                int y = BlockPos.getY(cell);
                if (y >= yMin && y <= yMax && BlockPos.getX(cell) == x && BlockPos.getZ(cell) == z) {
                    return true;
                }
            }
            return false;
        }

        /** 改过的格里有没有落在区段 {@code section}({@link SectionPos#asLong} 编码)里的。 */
        boolean within(long section) {
            for (int i = 0; i < size; i++) {
                if (SectionPos.blockToSection(cells[i]) == section) {
                    return true;
                }
            }
            return false;
        }
    }
}
