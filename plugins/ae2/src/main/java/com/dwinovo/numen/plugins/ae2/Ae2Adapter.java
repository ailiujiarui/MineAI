package com.dwinovo.numen.plugins.ae2;

import appeng.api.config.Setting;
import appeng.api.networking.energy.IAEPowerStorage;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.util.IConfigManager;
import appeng.api.util.IConfigurableObject;
import appeng.menu.AEBaseMenu;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.nio.file.Path;
import java.util.Locale;

/**
 * AE2 适配(独立联动插件):把 Applied Energistics 2 机器的**配置与状态**翻译成人能读懂的样子。
 *
 * <ul>
 *   <li>{@code use gui}(菜单):逐个槽位标出角色(AE2 的 Input/Output/Pattern/Config 等),
 *       并从菜单的 target 读出这台机器的配置/升级/AE 电量;</li>
 *   <li>{@code scan_storage}(方块):方块状态、
 *       <b>配置管理器</b>({@link IConfigurableObject#getConfigManager()} → {@link IConfigManager},
 *       逐项 {@link Setting#getName()} / {@link Setting#getValue(IConfigManager)}:I/O 方向、访问限制、
 *       阻挡模式等)、
 *       <b>升级卡</b>({@link IUpgradeableObject#getUpgrades()} → {@link IUpgradeInventory})、
 *       <b>AE 电量</b>({@link IAEPowerStorage},AE2 自己的能量单位,标准 FE capability 读不到)、
 *       以及标准物品/流体/能量 capability;线缆方块再逐面列出插着的 part({@link IPartHost#getPart})及其配置;</li>
 *   <li>{@code ae2_network}:从一格出发走整张 ME 网格,报频道用量、控制器状态、电量与设备清单
 *       (见 {@link Ae2NetworkOps})。</li>
 * </ul>
 *
 * <p>由 {@code Builtin} 在确认 AE2 在场后调用 {@link #install};注册 gui/container 两个处理器(名 {@code ae2})、
 * 自带 {@code plugins/ae2/adapters/ae2.json} 把 {@code ae2:*} 路由过来,并带上 {@code plugins/ae2/skills} 那篇
 * 搭产线的技能。
 */
public final class Ae2Adapter {

    private Ae2Adapter() {}

    /**
     * 由 Builtin 在确认 AE2 在场后调用;{@code adaptersRoot} 是插件 jar 内 /adapters 目录,
     * {@code skillsRoot} 是 /skills 目录,两个都可空。
     */
    public static void install(Path adaptersRoot, Path skillsRoot) {
        NumenPlugins.register(numen -> {
            numen.registerGuiHandler("ae2", Ae2Adapter::readGui);
            numen.registerContainerHandler("ae2", Ae2Adapter::readContainer);
            numen.registerTool(new Ae2NetworkTool());
            if (adaptersRoot != null) {
                numen.bundleAdapters(adaptersRoot);
            }
            if (skillsRoot != null) {
                numen.bundleSkills(skillsRoot);
            }
        });
    }

    /** {@code use gui}:逐个槽位标出角色,并附上菜单 target 这台机器的配置/升级/电量。 */
    private static String readGui(NumenPlayer body, AbstractContainerMenu menu, String source) {
        StringBuilder sb = new StringBuilder("GUI: ").append(menu.getClass().getSimpleName()).append(" (AE2)\n");
        Inventory playerInv = body.getInventory();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            sb.append("  ").append(i).append(": ").append(item(slot.getItem()));
            if (slot.container != playerInv) {
                String role = roleOf(slot);
                if (role != null) {
                    sb.append(" [").append(role).append("]");
                }
            }
            sb.append("\n");
        }
        sb.append("role hints: [input] feed the machine · [output] take results · [pattern] encoded pattern · "
                + "[crafting] crafting grid · [config] filter/partition slot · [upgrade] upgrades\n");
        // AE2 的菜单把被操作的方块实体挂在 target 上;机器配置/升级/电量从这里读,而不是从槽位猜。
        if (menu instanceof AEBaseMenu ae) {
            try {
                if (ae.getTarget() instanceof BlockEntity be) {
                    appendMachine(be, sb);
                }
            } catch (Throwable ignored) {
                // target 读不出来就只给槽位,不影响回执
            }
        }
        return TaskResult.ok(sb.toString()).toJson();
    }

    /** {@code scan_storage}:机器的完整状态。 */
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
        // 标准 capability:AE2 的多数方块实体把内部库存经 NeoForge item handler 暴露出来,
        // 物品/流体/能量这一段先读它。
        java.util.List<String> caps = Services.CAPS.describe(level, pos);
        if (caps != null && !caps.isEmpty()) {
            for (String line : caps) {
                sb.append(line).append('\n');
            }
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            appendMachine(be, sb);
            // 线缆/总线方块自己的配置不在方块实体上,在某一面插着的 part 里。
            if (be instanceof IPartHost host) {
                appendParts(host, sb);
            }
        }
        return TaskResult.ok(sb.toString()).toJson();
    }

    /** 逐面列出线缆上的 part,每段带它在哪个面,再给这一面上的配置/升级。 */
    private static void appendParts(IPartHost host, StringBuilder sb) {
        for (Direction side : Direction.values()) {
            try {
                IPart part = host.getPart(side);
                if (part == null) {
                    continue;
                }
                sb.append("part on ").append(side.getName()).append(": ")
                        .append(partItem(part)).append("\n");
                if (part instanceof IConfigurableObject configurable) {
                    appendConfig(configurable.getConfigManager(), sb);
                }
                if (part instanceof IUpgradeableObject upgradeable) {
                    appendUpgrades(upgradeable.getUpgrades(), sb);
                }
            } catch (Throwable ignored) {
                // 这一面读不出来只跳过这一面
            }
        }
    }

    private static String partItem(IPart part) {
        try {
            return BuiltInRegistries.ITEM.getKey(part.getPartItem().asItem()).toString();
        } catch (Throwable ignored) {
            return "part";
        }
    }

    /**
     * 反射之外的直读部分:方块实体自己声明的 AE2 接口。逐段包 {@code Throwable}——
     * 某个模组扩展的机器实现不完全时,坏一段不能连着槽位/其余段一起丢。
     */
    private static void appendMachine(BlockEntity be, StringBuilder sb) {
        if (be instanceof IConfigurableObject configurable) {
            appendConfig(configurable.getConfigManager(), sb);
        }
        if (be instanceof IUpgradeableObject upgradeable) {
            appendUpgrades(upgradeable.getUpgrades(), sb);
        }
        if (be instanceof IAEPowerStorage power) {
            appendAePower(power, sb);
        }
    }

    /** AE2 配置管理器里的每一项:{@code name=value}。I/O 方向、访问限制、阻挡模式等都在这。 */
    private static void appendConfig(IConfigManager manager, StringBuilder sb) {
        if (manager == null) {
            return;
        }
        try {
            StringBuilder line = new StringBuilder();
            for (Setting<?> setting : manager.getSettings()) {
                Object value = setting.getValue(manager);
                String text = value instanceof Enum<?> e ? e.name() : String.valueOf(value);
                if (line.length() > 0) {
                    line.append("; ");
                }
                line.append(setting.getName()).append('=').append(text);
            }
            if (line.length() > 0) {
                sb.append("config: ").append(line).append("\n")
                        .append("  (read/change these server-side with machine_config "
                                + "{x,y,z[,side],setting,value})\n");
            }
        } catch (Throwable ignored) {
            // 配置管理器的实现因机器而异,读不出来当没有
        }
    }

    /** 升级卡槽:列出装了什么,没装就不出这段。 */
    private static void appendUpgrades(IUpgradeInventory upgrades, StringBuilder sb) {
        if (upgrades == null) {
            return;
        }
        try {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < upgrades.size(); i++) {
                ItemStack stack = upgrades.getStackInSlot(i);
                if (stack.isEmpty()) {
                    continue;
                }
                if (line.length() > 0) {
                    line.append(", ");
                }
                line.append(item(stack));
            }
            if (line.length() > 0) {
                sb.append("upgrades: ").append(line).append("\n");
            }
        } catch (Throwable ignored) {
            // 升级栏读不出来当没有
        }
    }

    /** AE2 自己的能量单位;标准 FE capability 读不到它。 */
    private static void appendAePower(IAEPowerStorage power, StringBuilder sb) {
        try {
            sb.append("AE power: ").append(number(power.getAECurrentPower())).append('/')
                    .append(number(power.getAEMaxPower())).append(" AE")
                    .append(" (flow: ").append(power.getPowerFlow()).append(")\n");
        } catch (Throwable ignored) {
            // 电量读不出来当没有
        }
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static <T extends Comparable<T>> String propValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /**
     * 槽类名 → 角色。AE2 的槽类名自带语义;认不出的再走一套通用启发式
     * (与 core 的 {@code SlotRoles} 同一形状——插件只拿得到瘦 jar,够不到 core 的类)。
     */
    private static String roleOf(Slot slot) {
        String name = slot.getClass().getSimpleName();
        if (name.contains("PatternOutput")) return "output";
        if (name.contains("RestrictedInput")) return "input";
        if (name.contains("Output")) return "output";
        if (name.contains("Pattern")) return "pattern";
        if (name.contains("Crafting")) return "crafting";
        if (name.contains("Partition") || name.contains("Fake")) return "config";
        if (name.contains("Disabled") || name.contains("Inaccessible")) return "unavailable";
        if (name.contains("Input")) return "input";
        if (name.contains("Energy") || name.contains("Power")) return "energy";
        if (name.contains("Fluid")) return "fluid";
        if (name.contains("Upgrade")) return "upgrade";
        if (name.contains("Security")) return "security";
        return null;
    }

    private static String item(ItemStack stack) {
        return stack.isEmpty() ? "-"
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath() + " x" + stack.getCount();
    }
}
