package com.dwinovo.numen.plugins.mekanism;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.task.TaskResult;
import mekanism.api.RelativeSide;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.interfaces.ISideConfiguration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.nio.file.Path;

/**
 * Mekanism 适配(独立联动插件):把 Mekanism 机器的槽位、朝向、各面的输入/输出配置翻译成人能读懂的样子。
 *
 * <p>两条腿:
 * <ul>
 *   <li>{@code inspect_gui}(菜单):槽位的角色写在槽类名里
 *       ({@code InputInventorySlot}/{@code OutputInventorySlot}/{@code EnergyInventorySlot}/…),按类名翻译;</li>
 *   <li>{@code inspect_block_storage}(方块):走 Mekanism 的 {@link ISideConfiguration} API 读
 *       <b>朝向({@link ISideConfiguration#getDirection()})</b>和<b>每个面每种传输(物品/能量/流体/…)的输入还是输出</b>
 *       ({@link TileComponentConfig#getDataType(TransmissionType, RelativeSide)} → {@link DataType})。</li>
 * </ul>
 *
 * <p>由 {@code Builtin} 在确认 Mekanism 在场后调用 {@link #install};注册 gui/container 两个处理器(名 {@code mekanism})
 * 并自带 {@code plugins/mekanism/adapters/mekanism.json} 把 {@code mekanism:*} 路由过来。
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

    /** {@code inspect_block_storage}:方块 id、方块状态、朝向、各面输入/输出配置、以及标准 capability 内容。 */
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
        appendSideConfig(body, pos, sb);
        String caps = Services.CAPS.describe(body.level(), pos);
        if (caps != null && !caps.isBlank()) {
            sb.append(caps);
        }
        return TaskResult.ok(sb.toString()).toJson();
    }

    /** 朝向 + 每个面每种传输的输入/输出 —— Mekanism 的 side config(相对朝向)。 */
    private static void appendSideConfig(NumenPlayer body, BlockPos pos, StringBuilder sb) {
        BlockEntity be = body.level().getBlockEntity(pos);
        if (!(be instanceof ISideConfiguration side)) {
            return;
        }
        sb.append("facing: ").append(side.getDirection()).append("\n");
        TileComponentConfig config = side.getConfig();
        if (config == null) {
            return;
        }
        sb.append("side config (relative to facing; input=机器从这里收, output=从这里出):\n");
        for (TransmissionType type : config.getTransmissions()) {
            StringBuilder line = new StringBuilder();
            for (RelativeSide rs : RelativeSide.values()) {
                DataType dt = config.getDataType(type, rs);
                if (dt == null || dt == DataType.NONE) {
                    continue;
                }
                line.append(rs.getSerializedName()).append('=').append(dt.getSerializedName()).append(' ');
            }
            if (line.length() > 0) {
                sb.append("  ").append(type.getName().toLowerCase()).append(": ").append(line).append("\n");
            }
        }
    }

    private static <T extends Comparable<T>> String propValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /** 槽类名 → 角色。Mekanism 的槽类名自带语义。 */
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
