package com.dwinovo.numen.plugins.ae2;

import appeng.api.config.Setting;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IAEPowerStorage;
import appeng.api.orientation.BlockOrientation;
import appeng.api.orientation.IOrientationStrategy;
import appeng.api.orientation.RelativeSide;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.util.AEColor;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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
 *       <b>朝向</b>({@link IOrientationStrategy}/{@link BlockOrientation},报 front 与机器自身坐标系里的
 *       back/left/right/up/down)、
 *       <b>配置管理器</b>({@link IConfigurableObject#getConfigManager()} → {@link IConfigManager},
 *       逐项 {@link Setting#getName()} / {@link Setting#getValue(IConfigManager)}:I/O 方向、访问限制、
 *       阻挡模式等)、
 *       <b>升级卡</b>({@link IUpgradeableObject#getUpgrades()} → {@link IUpgradeInventory})、
 *       <b>AE 电量</b>({@link IAEPowerStorage},AE2 自己的能量单位,标准 FE capability 读不到)、
 *       <b>这一格自己的节点状态</b>(在不在网上、活没活、用了几条频道、整张网有没有电,
 *       与 {@link Ae2NetworkOps} 同一套取数)、
 *       以及标准物品/流体/能量 capability;线缆方块再逐面列出插着的 part({@link IPartHost#getPart})、
 *       这一面通不通、邻居是什么方块;</li>
 *   <li>{@code ae2_network}:从一格出发走整张 ME 网格,报本节点状态(node_active 等)、频道用量、
 *       控制器状态、电量与设备清单(见 {@link Ae2NetworkOps})。</li>
 *   <li>{@code encode_pattern}:在打开的模式编码终端里把配方编成 AE2 模式——编码格是鬼影格,
 *       {@code use transfer} 放不进去,这里走服务端的 {@code PatternEncodingTermMenu.encode()}(见
 *       {@link Ae2EncodePatternTool})。</li>
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
            numen.registerTool(new Ae2EncodePatternTool());
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
        // 模式编码终端:那些 [input]/[output]/[crafting] 格是幽灵槽(FakeSlot),use transfer 放不进真实物品,
        // 也不需要客户端点按——在这里直接指路,免得模型对着槽位反复撞墙、误判成"只能主人上客户端"。
        if (menu instanceof appeng.menu.me.items.PatternEncodingTermMenu) {
            sb.append("PATTERN ENCODING TERMINAL — the [input]/[output]/[crafting] cells above are GHOST slots: "
                    + "`use transfer` cannot put real items in them, and no client click is needed. To encode a "
                    + "pattern: hold a blank pattern (ae2:blank_pattern), then call "
                    + "`encode_pattern {inputs:[{\"item\":\"<id>\",\"count\":1},...], "
                    + "outputs:[{\"item\":\"<id>\",\"count\":1},...], mode:\"processing\"}` "
                    + "(use mode:\"crafting\" and the exact 3x3/2x2 layout for a crafting-table recipe). It writes "
                    + "the ghosts, encodes, and puts the encoded pattern into your inventory; then `use shift` it "
                    + "into the pattern provider. Do NOT keep trying `use transfer` on these cells.\n");
        }
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
        // 朝向:AE2 的机器不都用方块状态 facing,统一走它自己的 orientation 策略。
        appendOrientation(state, sb);
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
            // 这一格自己的网格状态:和 ae2_network 共用一套取数,不必再发一次 ae2_network。
            try {
                sb.append(Ae2NetworkOps.gridStatusLine(level, pos)).append('\n');
            } catch (Throwable ignored) {
                // 网格状态读不出来不影响其余回执
            }
            // 线缆/总线方块自己的配置不在方块实体上,在某一面插着的 part 里。
            if (be instanceof IPartHost host) {
                appendParts(host, level, pos, sb);
            }
        }
        return TaskResult.ok(sb.toString()).toJson();
    }

    /**
     * 机器朝向的人话总结:{@code front} 是机器正面,其余是机器自身坐标系里的
     * back/left/right/up/down。AE2 的 {@code io_direction} 取值 LEFT/RIGHT/UP/DOWN 就是相对这个正面。
     * 没有朝向属性的方块(策略的 property 表为空)跳过这一行。
     */
    private static void appendOrientation(BlockState state, StringBuilder sb) {
        try {
            IOrientationStrategy strategy = IOrientationStrategy.get(state);
            if (strategy.getProperties().isEmpty()) {
                return;
            }
            BlockOrientation o = BlockOrientation.get(strategy, state);
            // 只有水平朝向前,up/down 才是世界绝对方向;带 spin 的整朝向下,up/down 也是相对的。
            String abs = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? " (absolute)" : "";
            sb.append("facing: ").append(o.getSide(RelativeSide.FRONT).getName()).append(" (front)")
                    .append(" — back=").append(o.getSide(RelativeSide.BACK).getName())
                    .append(" left=").append(o.getSide(RelativeSide.LEFT).getName())
                    .append(" right=").append(o.getSide(RelativeSide.RIGHT).getName())
                    .append(" up=").append(o.getSide(RelativeSide.TOP).getName()).append(abs)
                    .append(" down=").append(o.getSide(RelativeSide.BOTTOM).getName()).append(abs)
                    .append('\n');
            sb.append("  (AE2 setting io_direction LEFT/RIGHT/UP/DOWN is relative to this front)\n");
        } catch (Throwable ignored) {
            // 朝向读不出来就不写这一行
        }
    }

    /** 逐面列出线缆上的 part,每段带它在哪个面,再给这一面上的配置/升级。 */
    private static void appendParts(IPartHost host, Level level, BlockPos pos, StringBuilder sb) {
        sb.append("cable faces:\n");
        for (Direction side : Direction.values()) {
            try {
                IPart part = host.getPart(side);
                BlockState neighbour = level.getBlockState(pos.relative(side));
                sb.append("  face ").append(side.getName()).append(": ");
                if (part != null) {
                    String partId = partItemId(part);
                    sb.append("part=").append(partId);
                    String role = partRole(partId);
                    if (role != null) {
                        sb.append(" [").append(role).append("]");
                    }
                    sb.append(" | ");
                }
                sb.append("connected=").append(faceConnected(level, pos, side, part, neighbour) ? "yes" : "no")
                        .append(" | neighbour=").append(neighbourName(neighbour)).append("\n");
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

    /**
     * 这一面通不通。没有"查询邻居连接状态"的单一公开 API,这里按 AE2 内部建连接的两条规则近似:
     * <ul>
     *   <li><b>网格连接</b>:本格在 {@code side} 暴露了 AE2 节点,邻居在对面也暴露了节点且颜色相容
     *       ({@link GridHelper#getExposedNode} —— AE2 的 {@code InWorldGridNode} 就是用这一对来 {@code createConnection} 的);</li>
     *   <li><b>part 面向</b>:这一面插着 part、part 已在网格上、邻居不是空气
     *       (总线/面板对着的容器或机器就是它的工作面)。</li>
     * </ul>
     * 非 AE2 邻居(箱子、石头)没有节点,只有上面第二条能认出来。这是公开 API 能给到的最接近的答案。
     */
    private static boolean faceConnected(Level level, BlockPos pos, Direction side, IPart part, BlockState neighbour) {
        try {
            IGridNode here = GridHelper.getExposedNode(level, pos, side);
            if (here != null) {
                IGridNode there = GridHelper.getExposedNode(level, pos.relative(side), side.getOpposite());
                if (there != null && colorsCompatible(here, there)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 节点查询失败就退回 part 面向判断
        }
        try {
            return part != null && part.getGridNode() != null && !neighbour.isAir();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 同色或透明(fluix)才连,与 AE2 {@code InWorldGridNode.hasCompatibleColor} 一致。 */
    private static boolean colorsCompatible(IGridNode a, IGridNode b) {
        try {
            AEColor ca = a.getGridColor();
            AEColor cb = b.getGridColor();
            return ca == AEColor.TRANSPARENT || cb == AEColor.TRANSPARENT || ca == cb;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static String neighbourName(BlockState neighbour) {
        if (neighbour.isAir()) {
            return "air";
        }
        String id = BuiltInRegistries.BLOCK.getKey(neighbour.getBlock()).toString();
        return id.startsWith("ae2:") ? id : id + " (not AE2)";
    }

    private static String partItemId(IPart part) {
        try {
            return BuiltInRegistries.ITEM.getKey(part.getPartItem().asItem()).toString();
        } catch (Throwable ignored) {
            return "part";
        }
    }

    /** 电缆部件按功能给个角色标签,认不出就不标。 */
    private static String partRole(String partId) {
        if (partId == null) {
            return null;
        }
        String path = partId.contains(":") ? partId.substring(partId.indexOf(':') + 1) : partId;
        return switch (path) {
            case "import_bus", "annihilation_plane" -> "input";
            case "export_bus", "formation_plane" -> "output";
            case "storage_bus" -> "storage";
            case "level_emitter" -> "level emitter";
            case "toggle_bus" -> "toggle";
            case "cable_anchor" -> "anchor";
            case "quartz_fiber" -> "power only";
            default -> path.contains("terminal") ? "terminal" : (path.contains("p2p") ? "p2p" : null);
        };
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
