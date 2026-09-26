package com.dwinovo.numen.plugins.mekanism;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.task.TaskResult;
import mekanism.api.RelativeSide;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.IChemicalHandler;
import mekanism.api.chemical.IChemicalTank;
import mekanism.api.heat.IHeatHandler;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.interfaces.ISideConfiguration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mekanism 适配(独立联动插件):把 Mekanism 机器的**完整状态**翻译成人能读懂的样子。
 *
 * <ul>
 *   <li>{@code inspect_gui}(菜单):每个槽位的角色(槽类名:Input/Output/Energy/…);</li>
 *   <li>{@code inspect_block_storage}(方块):
 *       <b>朝向</b>({@link ISideConfiguration#getDirection()})、
 *       <b>各面每种传输的输入/输出</b>({@link TileComponentConfig#getDataType(TransmissionType, RelativeSide)} → {@link DataType})、
 *       <b>物品/流体/能量</b>(标准 capability)、
 *       <b>气体储罐</b>(Mekanism {@link Capabilities#CHEMICAL})、
 *       <b>热量</b>(Mekanism {@link Capabilities#HEAT})、
 *       以及方块状态({@code active} 等)。</li>
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
                + "[energy] put power here · [upgrade]/[security] modules\n");
        sb.append(dataSlots(menu));
        return TaskResult.ok(sb.toString()).toJson();
    }

    /**
     * 菜单的 data slots —— 那排"平行于物品槽"的同步整数,真实屏幕用它画进度条/燃料/能量条。
     * 意义随菜单而变(灌注机的那几个整数就是加工进度和灌注量),所以原样给出、不猜含义;
     * Mekanism 不把"当前配方/进度百分比"挂在菜单上,这里是能拿到的最接近的东西。
     * 走反射读 {@code AbstractContainerMenu.dataSlots}(游戏里是私有字段,引擎那侧有 mixin accessor,
     * 插件只拿得到瘦 jar、够不到它)。
     */
    private static String dataSlots(AbstractContainerMenu menu) {
        try {
            java.lang.reflect.Field field = AbstractContainerMenu.class.getDeclaredField("dataSlots");
            field.setAccessible(true);
            Object value = field.get(menu);
            if (!(value instanceof List<?> slots) || slots.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("data values (machine progress/fuel/energy ints — meaning is GUI-specific): [");
            for (int i = 0; i < slots.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(((net.minecraft.world.inventory.DataSlot) slots.get(i)).get());
            }
            return sb.append("]\n").toString();
        } catch (Throwable reflectionUnavailable) {
            return "";
        }
    }

    /** {@code inspect_block_storage}:机器的完整状态。 */
    private static String readContainer(NumenPlayer body, BlockPos pos, String access) {
        Level level = body.level();
        BlockState state = level.getBlockState(pos);
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
        appendSideConfig(level, pos, sb);
        String caps = Services.CAPS.describe(level, pos);
        if (caps != null && !caps.isBlank()) {
            sb.append(caps);   // items / fluids / energy(标准 capability)
        }
        // 命名化学罐优先(灌注机的 infusionTank 是 public 字段,标准 capability 未必暴露它);没有再看 capability
        if (!appendNamedChemicalTanks(level, pos, sb)) {
            appendChemicals(level, pos, sb);
        }
        appendHeat(level, pos, sb);
        return TaskResult.ok(sb.toString()).toJson();
    }

    /**
     * 反射读方块实体上的 {@code IChemicalTank} 公开字段(灌注机的 {@code infusionTank} 就是这样一个)。
     * Mekanism 把"当前灌注的是哪种材料、还剩多少"放在这种字段里,标准 {@code Capabilities.CHEMICAL} 未必暴露,
     * 只有电脑集成/内部字段能拿到——这是能读到"灌注类型 + 液体量"的现实办法。
     *
     * @return 读到了没有
     */
    private static boolean appendNamedChemicalTanks(Level level, BlockPos pos, StringBuilder sb) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return false;
        }
        boolean header = false;
        for (java.lang.reflect.Field field : be.getClass().getFields()) {
            if (!IChemicalTank.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                IChemicalTank tank = (IChemicalTank) field.get(be);
                if (tank == null) {
                    continue;
                }
                if (!header) {
                    sb.append("chemical tanks:\n");
                    header = true;
                }
                ChemicalStack stack = tank.getStack();
                sb.append("  ").append(field.getName()).append(": ");
                if (stack.isEmpty()) {
                    sb.append("empty");
                } else {
                    sb.append(chemicalId(stack)).append(' ').append(stack.getAmount());
                }
                sb.append('/').append(tank.getCapacity()).append(" mB\n");
            } catch (Throwable ignored) {
                // 反射不到就当这个字段没有
            }
        }
        return header;
    }

    /** 朝向 + 每个面每种传输的输入/输出 —— Mekanism 的 side config(相对朝向)。 */
    private static void appendSideConfig(Level level, BlockPos pos, StringBuilder sb) {
        BlockEntity be = level.getBlockEntity(pos);
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
                sb.append("  ").append(type.getName().toLowerCase(Locale.ROOT)).append(": ").append(line).append("\n");
            }
        }
    }

    /** 气体储罐(Mekanism 的 chemical capability,标准 capability 读不到)。 */
    private static void appendChemicals(Level level, BlockPos pos, StringBuilder sb) {
        Map<IChemicalHandler, List<String>> byHandler = new IdentityHashMap<>();
        collect(byHandler, level.getCapability(Capabilities.CHEMICAL.block(), pos, null), "all");
        for (Direction d : Direction.values()) {
            collect(byHandler, level.getCapability(Capabilities.CHEMICAL.block(), pos, d), d.getName());
        }
        if (byHandler.isEmpty()) {
            return;
        }
        sb.append("chemicals:\n");
        for (Map.Entry<IChemicalHandler, List<String>> e : byHandler.entrySet()) {
            IChemicalHandler handler = e.getKey();
            for (int t = 0; t < handler.getChemicalTanks(); t++) {
                ChemicalStack stack = handler.getChemicalInTank(t);
                sb.append("  tank ").append(t).append(" (sides: ").append(String.join(",", e.getValue())).append("): ");
                if (stack.isEmpty()) {
                    sb.append("empty");
                } else {
                    sb.append(chemicalId(stack)).append(' ').append(stack.getAmount());
                }
                sb.append('/').append(handler.getChemicalTankCapacity(t)).append(" mB\n");
            }
        }
    }

    /** 热量(Mekanism 的 heat capability)。 */
    private static void appendHeat(Level level, BlockPos pos, StringBuilder sb) {
        IHeatHandler heat = level.getCapability(Capabilities.HEAT, pos, null);
        if (heat == null) {
            for (Direction d : Direction.values()) {
                heat = level.getCapability(Capabilities.HEAT, pos, d);
                if (heat != null) {
                    break;
                }
            }
        }
        if (heat != null && heat.getHeatCapacitorCount() > 0) {
            sb.append("heat: ").append(String.format(Locale.ROOT, "%.1f", heat.getTotalTemperature()))
                    .append(" K (ambient ~300)\n");
        }
    }

    private static String chemicalId(ChemicalStack stack) {
        return stack.getChemicalHolder().unwrapKey().map(key -> key.location().toString()).orElse("?");
    }

    private static <T> void collect(Map<T, List<String>> byHandler, T handler, String side) {
        if (handler == null) {
            return;
        }
        byHandler.computeIfAbsent(handler, h -> new ArrayList<>()).add(side);
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
