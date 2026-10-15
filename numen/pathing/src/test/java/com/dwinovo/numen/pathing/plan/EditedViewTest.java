package com.dwinovo.numen.pathing.plan;

import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 叠了改动的世界。 */
class EditedViewTest {

    private static final int Y = 64;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    /** 叠在另一份改动之上的视图第一次要改才抄表:改自己不动底下那份,底下那份后来再改也不动它。 */
    @Test
    void aViewOverAnEditedViewCopiesTheChangesOnlyWhenItWritesItsOwn() {
        TestWorld base = new TestWorld().floor(-4, -4, 4, 4, Y - 1);
        BlockPos a = new BlockPos(0, Y, 0);
        BlockPos b = new BlockPos(1, Y, 0);
        BlockPos c = new BlockPos(2, Y, 0);
        EditedView under = new EditedView(base);
        under.place(a, Blocks.STONE);

        EditedView over = new EditedView(under);
        assertEquals(Blocks.STONE, over.getBlockState(a).getBlock(), "没改之前读到底下那份的改动");
        over.place(b, Blocks.DIRT);
        assertEquals(Blocks.DIRT, over.getBlockState(b).getBlock());
        assertTrue(under.getBlockState(b).isAir(), "上面改自己的,不改底下那份");

        under.place(c, Blocks.GRAVEL);
        assertEquals(Blocks.GRAVEL, under.getBlockState(c).getBlock());
        assertTrue(over.getBlockState(c).isAir(), "底下那份后来再改,上面的看到的不变");
        assertEquals(Blocks.STONE, over.getBlockState(a).getBlock());
    }
}
