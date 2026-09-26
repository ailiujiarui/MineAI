package com.dwinovo.numen.plugins.mekanism;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.nio.file.Path;

/**
 * Mekanism 适配(独立联动插件):把 Mekanism 机器的槽位/配置翻译成人能读懂的角色。
 *
 * <p>它<b>不引用 Mekanism 任何一个类</b>:机器的槽位角色写在槽类名里
 * ({@code InputInventorySlot} / {@code OutputInventorySlot} / {@code EnergyInventorySlot} /
 * {@code InfusionInventorySlot} / {@code ChemicalInventorySlot} / {@code FuelInventorySlot} /
 * {@code UpgradeInventorySlot} / {@code SecurityInventorySlot} …),这里按名字翻译成
 * {@code [input]} / {@code [output]} / {@code [energy]} …;方块的 {@code active} 等 blockstate
 * 属性、以及标准 capability(物品/流体/能量)也一并报出来。
 *
 * <p>它注册两个处理器(gui/container,名 {@code mekanism})并自带一份适配 JSON
 * ({@code plugins/mekanism/adapters/mekanism.json},把 {@code mekanism:*} 的菜单/方块路由到这里),
 * 由 {@code Builtin} 在确认 Mekanism 在场后调用 {@link #install}。
 */
public final class MekanismAdapter {

    private MekanismAdapter() {}

    /** 由 Builtin 在确认 Mekanism 在场后调用;{@code adaptersRoot} 是插件 jar 内 /adapters 目录(可空)。 */
    public static void install(Path adaptersRoot) {
        NumenPlugins.register(numen -> {
            numen.registerGuiHandler("mekanism", MekanismAdapter::readGui);
            numen.registerContainerHandler("mekanism", MekanismAdapter::readContainer);
            if (adaptersRoot != null) {
                numen.bundleAdapters(adaptersRoot);
            }
        });
    }

    /** {@code inspect_gui}:逐个槽位标出角色。处理器拥有整份回执,所以这里直接回一个工具结果 JSON。 */
    private static String readGui(NumenPlayer body, AbstractContainerMenu menu, String source) {
        StringBuilder sb = new StringBuilder("GUI: ").append(menu.getClass().getSimpleName()).append(" (Mekanism)\n");
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            sb.append("  ").append(i).append(": ").append(item(slot.getItem()));
            String role = roleOf(slot);
            if (role != null) {
                sb.append(" [").append(role).append("]");
            }
            sb.append("\n");
        }
        sb.append("role hints: [input] main input · [infusion]/[chemical]/[fluid]/[gas] the extra input · [output] take results · "
                + "[energy] put power here · [upgrade]/[security] modules");
        return TaskResult.ok(sb.toString()).toJson();
    }

    /** {@code inspect_block_storage}:报方块 id、方块状态(active 之类)、以及标准 capability 内容。 */
    private static String readContainer(NumenPlayer body, BlockPos pos, String access) {
        BlockState state = body.level().getBlockState(pos);
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        StringBuilder sb = new StringBuilder(id).append(" at ")
                .append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ()).append("\n");
        StringBuilder props = new StringBuilder();
        for (Property<?> property : state.getProperties()) {
            props.append(property.getName()).append('=').append(propValue(state, property)).append(' ');
        }
        if (props.length() > 0) {
            sb.append("state: ").append(props).append("\n");
        }
        String caps = Services.CAPS.describe(body.level(), pos);
        if (caps != null && !caps.isBlank()) {
            sb.append(caps);
        }
        return TaskResult.ok(sb.toString()).toJson();
    }

    private static <T extends Comparable<T>> String propValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /** 槽类名 → 角色。Mekanism 的槽类名自带语义,不需要它的编译依赖。 */
    private static String roleOf(Slot slot) {
        String name = slot.getClass().getSimpleName();
        if (name.contains("FactoryInput")) return "input";
        if (name.contains("Output")) return "output";
        if (name.contains("Input")) return "input";
        if (name.contains("Infusion")) return "infusion";
        if (name.contains("Chemical")) return "chemical";
        if (name.contains("Fluid")) return "fluid";
        if (name.contains("Fuel")) return "fuel";
        if (name.contains("Energy")) return "energy";
        if (name.contains("Upgrade")) return "upgrade";
        if (name.contains("Security")) return "security";
        if (name.contains("Bin")) return "bin";
        return null;
    }

    private static String item(ItemStack stack) {
        return stack.isEmpty() ? "-"
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath() + " x" + stack.getCount();
    }
}
