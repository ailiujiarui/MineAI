package com.dwinovo.numen.core.plan;

import com.dwinovo.numen.agent.plan.Counts;
import com.dwinovo.numen.core.PlayerInv;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;

/**
 * 同伴背包的只读账:某个物品现在有多少。纯 JVM 的规划层要的 {@link Counts} 由这里供,
 * 数量口径走 {@link PlayerInv#count}(整个背包:快捷栏 + 主背包 + 盔甲 + 副手,戴在身上也算持有)。
 */
public final class CompanionCounts implements Counts {

    private final Inventory inv;

    public CompanionCounts(Inventory inv) {
        this.inv = inv;
    }

    @Override
    public int count(String item) {
        ResourceLocation id = ResourceLocation.tryParse(item);
        if (id == null) {
            return 0;
        }
        Item known = BuiltInRegistries.ITEM.get(id);
        return known == null ? 0 : PlayerInv.count(inv, known);
    }
}
