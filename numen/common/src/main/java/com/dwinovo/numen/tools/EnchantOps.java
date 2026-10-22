package com.dwinovo.numen.tools;

import com.dwinovo.numen.api.agent.script.ApiError;
import com.dwinovo.numen.api.agent.script.ErrorKind;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.spectator.SpectatorMenuBridge;
import com.dwinovo.numen.mixin.EnchantmentMenuAccessor;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.List;

/**
 * 附魔台的业务半边:{@code numen.gui.view} 里那三档报价({@link #state})与 {@code numen.gui.enchant} 按下一档
 * ({@link #enchant})。报价和点击后附上的东西都是原版 {@code EnchantmentMenu.getEnchantmentList} 那一趟算的
 * ({@link EnchantmentMenuAccessor}),这里不重抄随机逻辑。
 *
 * <p>点击走原版 {@code clickMenuButton}——客户端按下附魔按钮后服务端走的正是它:验等级与青金石、扣 1-3 级(档位就是扣
 * 几级,{@code costs} 只是等级门槛)、耗青金石、把整张附魔表附到物品上、推进附魔种子,台子随后重新报价。回执照实报这些。
 */
public final class EnchantOps {

    private EnchantOps() {}

    /** 附了一档:{@code numen.gui.enchant} 交回的值。 */
    @Doc("What one enchant offer did.")
    public record Enchanted(@Doc("Which offer, 1, 2 or 3.") int offer,
                            @Doc("The item it enchanted, minecraft:iron_sword.") String item,
                            @Doc("What is on it now, minecraft:sharpness 3.") List<String> enchantments,
                            @Doc("Levels paid.") int levels,
                            @Doc("Lapis paid.") int lapis,
                            @Doc("The table's three offers now: enchanting re-rolls them.") List<String> offers) {}

    /**
     * 附魔台界面此刻的三档报价,给 {@code numen.gui.view().state}:先说她有几级、台子的青金石槽里几颗,再每档一行——
     * 会附上的附魔(确切 id 与等级)、等级门槛、这一档要花的等级与青金石、够不够;没有的那档一行破折号。一档都没有时,
     * 最后一行说为什么。
     */
    public static List<String> state(NumenPlayer self, EnchantmentMenu table) {
        ItemStack item = table.slots.get(0).getItem();
        int lapis = table.slots.get(1).getItem().getCount();
        List<String> lines = new ArrayList<>();
        lines.add("you have " + self.experienceLevel + " levels; the table's lapis slot holds " + lapis + ".");
        boolean any = false;
        for (int i = 0; i < 3; i++) {
            List<EnchantmentInstance> list = table.costs[i] > 0 ? offers(self, table, i) : List.of();
            if (list.isEmpty()) {
                lines.add("offer " + (i + 1) + ": —");
                continue;
            }
            any = true;
            int price = i + 1;
            boolean ready = self.hasInfiniteMaterials()
                    || self.experienceLevel >= table.costs[i] && self.experienceLevel >= price && lapis >= price;
            lines.add("offer " + price + ": " + named(list)
                    + " — requires " + table.costs[i] + " levels; takes " + price + " level" + (price == 1 ? "" : "s")
                    + " + " + price + " lapis; " + (ready ? "ready" : "not yet"));
        }
        if (!any) {
            lines.add(item.isEmpty()
                    ? "no offers: put the item to enchant in the left slot, lapis in the right."
                    : "no offers: " + itemName(item) + " can't be enchanted at a table.");
        }
        return lines;
    }

    /**
     * 按下附魔台三档里的一档:前置与 {@code clickMenuButton} 同款,验过就调它,回执报附上了什么、花了多少、台子
     * 重新报价后的三档。
     */
    public static Enchanted enchant(int offer, NumenPlayer self) {
        if (!(self.containerMenu instanceof EnchantmentMenu table)) {
            throw new ApiError(ErrorKind.NOT_FOUND, "no enchanting table is open — stand by one and open it with "
                    + "numen.use.block", null);
        }
        if (offer < 1 || offer > 3) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "offer is 1, 2 or 3, the table's three buttons; got " + offer,
                    null);
        }
        int index = offer - 1;
        ItemStack item = table.slots.get(0).getItem();
        if (table.costs[index] <= 0) {
            throw new ApiError(ErrorKind.FAILED, item.isEmpty()
                    ? "the table's left slot is empty — put the item to enchant in it first (numen.gui.put)"
                    : itemName(item) + " has no offer there; the table takes plain, unenchanted items only", null);
        }
        int lapis = table.slots.get(1).getItem().getCount();
        if (lapis < offer && !self.hasInfiniteMaterials()) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "offer " + offer + " takes " + offer + " lapis; the table's lapis "
                    + "slot holds " + lapis, null);
        }
        if ((self.experienceLevel < offer || self.experienceLevel < table.costs[index])
                && !self.hasInfiniteMaterials()) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "offer " + offer + " requires " + table.costs[index]
                    + " levels (you have " + self.experienceLevel + ")", null);
        }

        ItemStack before = item.copy();
        int levelsBefore = self.experienceLevel;
        boolean clicked;
        try (var display = SpectatorMenuBridge.menuAction(self)) {
            clicked = table.clickMenuButton(self, index);
            SpectatorMenuBridge.captureActionStep(self);
        }
        if (!clicked) {
            throw new ApiError(ErrorKind.FAILED, "the table refused offer " + offer + " — numen.gui.view() shows what "
                    + "it offers now", null);
        }
        ItemStack after = table.slots.get(0).getItem();
        List<String> applied = namedOn(after);
        if (applied.isEmpty()) {
            throw new ApiError(ErrorKind.FAILED, "the table applied nothing to " + itemName(before)
                    + " — no enchantment came out of offer " + offer, null);
        }
        return new Enchanted(offer, itemName(after), applied, levelsBefore - self.experienceLevel,
                lapis - table.slots.get(1).getItem().getCount(), state(self, table));
    }

    /** 台子这一档会附上的整张表,就是原版点击时算的那一趟;档位与功率照菜单当前的数。 */
    private static List<EnchantmentInstance> offers(NumenPlayer self, EnchantmentMenu table, int index) {
        EnchantmentMenuAccessor access = (EnchantmentMenuAccessor) (Object) table;
        return access.numen$offers(self.serverLevel().registryAccess(), table.slots.get(0).getItem(), index,
                table.costs[index]);
    }

    /** 附魔一条写成 {@code minecraft:sharpness 3},按 id 排序。 */
    private static String named(List<EnchantmentInstance> list) {
        List<String> out = new ArrayList<>();
        for (EnchantmentInstance instance : list) {
            out.add(name(instance.enchantment) + " " + instance.level);
        }
        out.sort(null);
        return String.join(", ", out);
    }

    /** 物品身上现有的附魔,一处一条,按 id 排序。 */
    private static List<String> namedOn(ItemStack stack) {
        ItemEnchantments on = EnchantmentHelper.getEnchantmentsForCrafting(stack);
        List<String> out = new ArrayList<>();
        for (var entry : on.entrySet()) {
            out.add(name(entry.getKey()) + " " + entry.getIntValue());
        }
        out.sort(null);
        return out;
    }

    private static String name(Holder<Enchantment> enchantment) {
        return enchantment.unwrapKey().map(key -> key.location().toString()).orElse("unknown enchantment");
    }

    private static String itemName(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
