package com.dwinovo.numen.core.adapter;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.FurnaceFuelSlot;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * 通用槽位角色分类:把一个菜单槽翻成 {@code input/output/fuel/energy/extra/upgrade/security/
 * fluid/gas/chemical} 之一,翻不出来给 null。机器槽的角色藏在槽类里(原版熔炉的
 * {@link FurnaceFuelSlot}、Mekanism 的 {@code getSlotType()}、各种模组按类名命名),这里按
 * 先具体后笼统的顺序问一遍——所以 {@code use gui} 和自学习适配都不必认识某个具体模组。
 *
 * <p>玩家自己的背包/快捷栏槽位返回 {@link #PLAYER} 这个哨兵值;调用方问机器时应当把它们排除。
 *
 * <p>反射拿 {@code getSlotType()} 的整段都吞掉任何 {@code Throwable}:目标槽类可能被混淆、
 * 可能没有这个方法、也可能调用了抛出异常,任何一种都只是"问不出来",按后面的启发式继续。
 */
public final class SlotRoles {

    /** 玩家背包/快捷栏的槽位哨兵:不属于任何机器角色,调用方须排除。 */
    public static final String PLAYER = "player";

    private SlotRoles() {}

    /** 这个槽是干什么的;{@link #PLAYER} = 玩家自己的槽,{@code null} = 完全认不出。 */
    public static String roleOf(Slot slot, Inventory playerInv) {
        if (slot == null) {
            return null;
        }
        if (playerInv != null && slot.container == playerInv) {
            return PLAYER;
        }
        String typed = slotType(slot);
        if (typed != null) {
            return typed;
        }
        if (slot instanceof ResultSlot) {
            return "output";
        }
        if (slot instanceof FurnaceFuelSlot) {
            return "fuel";
        }
        String name = slot.getClass().getSimpleName();
        if (name.contains("FactoryInput")) return "input";
        if (name.contains("Output") || name.contains("Result")) return "output";
        if (name.contains("Input")) return "input";
        if (name.contains("Fuel")) return "fuel";
        if (name.contains("Energy") || name.contains("Power")) return "energy";
        if (name.contains("Infusion")) return "extra";
        if (name.contains("Chemical") || name.contains("Gas")) return "chemical";
        if (name.contains("Fluid")) return "fluid";
        if (name.contains("Upgrade")) return "upgrade";
        if (name.contains("Security")) return "security";
        return null;
    }

    /**
     * 反射问槽位的 no-arg {@code getSlotType()}。Mekanism 的 {@code InventoryContainerSlot} 用它
     * 报 {@code INPUT/OUTPUT/EXTRA/POWER};按枚举名翻成角色,其余有意义的枚举值原样小写,
     * 已知的非角色值(IGNORED/NORMAL/VALIDITY)给 null。
     */
    private static String slotType(Slot slot) {
        try {
            Method method = slot.getClass().getMethod("getSlotType");
            if (method.getParameterCount() != 0) {
                return null;
            }
            Object type = method.invoke(slot);
            if (type == null) {
                return null;
            }
            String name = type instanceof Enum<?> value ? value.name() : String.valueOf(type);
            return switch (name.toUpperCase(Locale.ROOT)) {
                case "INPUT" -> "input";
                case "OUTPUT" -> "output";
                case "EXTRA" -> "extra";
                case "POWER", "ENERGY" -> "energy";
                // Mekanism 给玩家背包槽也套 getSlotType(),报的是 IGNORED/NORMAL/VALIDITY:
                // 这些不是角色,返回 null 才能让调用方把它们当"没有角色"丢掉(否则玩家背包会混进机器槽位表)。
                case "IGNORED", "NORMAL", "VALIDITY" -> null;
                default -> name.toLowerCase(Locale.ROOT);
            };
        } catch (Throwable unavailable) {
            return null;
        }
    }
}
