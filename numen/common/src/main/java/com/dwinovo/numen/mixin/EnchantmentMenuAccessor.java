package com.dwinovo.numen.mixin;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * 原版"这一档附上什么"的那一趟,private,外面调不到。
 *
 * <p>它按 {@code 采种 + 档位} 自己重设随机源,返回的就是 {@code clickMenuButton} 点击后会附到物品上的整张表;
 * 报价的 {@code enchantClue} 只是从它里面随机挑的一条。我们读报价、点完核对,都用同一趟,不重抄随机逻辑。
 */
@Mixin(EnchantmentMenu.class)
public interface EnchantmentMenuAccessor {

    @Invoker("getEnchantmentList")
    List<EnchantmentInstance> numen$offers(RegistryAccess registry, ItemStack stack, int offer, int cost);
}
