package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Block-config tool implementation — the business half of {@code MachineConfigTool}.
 *
 * <p>机器的一档配置(模式、面的输入/输出、访问限制……)只画在客户端的 GUI 上,而同伴是没有客户端屏幕的假
 * 玩家:它开得出菜单,却看不见也点不动那些控件。这里从服务端的方块实体把同一份配置读出来,而且能改——
 * 不必点 GUI。
 *
 * <p>AE2 的<b>线缆总线</b>是另一回事:配置不在方块实体上,而在插在某一面上的 part(输入/输出总线、存储总线、
 * 接口、等级发射器……)自己的 {@code IConfigManager} 里。按接口名认出 {@code IPartHost},逐面 {@code getPart}
 * 取出 part,读/写它的设置;回执带上这一档设置在哪个面。多个可配 part 时,读列出全部,写要用 {@code side}
 * 指定是哪一个。
 *
 * <p><b>反射优先,不编译依赖任何模组</b>。方块实体认的是 AE2 那套公开接口
 * ({@code IConfigurableObject} / {@code IConfigManager} / {@code Setting}),但 core 不该为它背上一个编译
 * 依赖:<b>按接口名与方法名辨认</b>({@code getConfigManager} → {@code getSettings} → 每项
 * {@code getName}/{@code getValue}/{@code getValues};写用 {@code putSetting}/{@code setSetting}),
 * 类由方块实体自己实例化,这里一个 foreign 类都不加载。每一步都包 {@code Throwable}——某个模组扩展的机器
 * 实现不全时,坏一段不能把整次读取带走。
 *
 * <p>读在没有任何可读配置时干净失败(方块没有实体、实体不认这套接口),绝不抛。写没有走权限层:工具与任务
 * 都不自己判"能不能"(见 AGENTS §24,世界的动作只由权限层裁决),而这里是一次直接的服务端配置写入,
 * 结果照实回给模型。
 */
public final class MachineConfigOps {

    /** AE2 公开接口的全名;只按名字认,不加载类。 */
    private static final String CONFIGURABLE = "appeng.api.util.IConfigurableObject";
    private static final String CONFIG_MANAGER = "appeng.api.util.IConfigManager";
    private static final String AE_POWER = "appeng.api.networking.energy.IAEPowerStorage";
    private static final String UPGRADEABLE = "appeng.api.upgrades.IUpgradeableObject";
    /** AE2 的 part host:线缆/总线/面板这类方块,配置挂在某一面上的 part 上,不在方块实体自己身上。 */
    private static final String PART_HOST = "appeng.api.parts.IPartHost";

    /**
     * 读或写一个方块的配置。
     *
     * @param side    AE2 part 所在的面(up/down/north/south/east/west);方块机器不需要,part host 上多个
     *                可配 part 时用来选一个
     * @param setting {@code null}/空 = 读全部;给了名字 = 改这一档
     * @param value   改时给新值;只给 setting 不给 value 是干净失败(避免猜要不要读)
     */
    public String machineConfig(Integer x, Integer y, Integer z, String side, String setting, String value,
                                NumenPlayer self) {
        if (x == null || y == null || z == null) {
            throw new IllegalArgumentException("x, y, z are required: the block to read or configure");
        }
        if (!(self.level() instanceof ServerLevel level)) {
            return TaskResult.fail("machine config needs a server level.").toJson();
        }
        BlockPos pos = new BlockPos(x, y, z);
        String coord = x + "," + y + "," + z;
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return TaskResult.fail("block at " + coord + " is air — nothing to read or configure.").toJson();
        }
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return TaskResult.fail(id + " at " + coord + " is not a block entity — it exposes no server-side "
                    + "config. If it has a GUI, open it with interact_at then inspect_gui.").toJson();
        }
        boolean writing = setting != null && !setting.isBlank();
        if (writing && value == null) {
            return TaskResult.fail("give value to change setting '" + setting + "', or omit setting to read "
                    + "every setting on " + id + ".").toJson();
        }
        Object manager = configManagerOf(be);
        // 方块实体自己就是那台机器(压印器、驱动器……)。
        if (manager != null) {
            return writing ? set(be, manager, id, coord, setting, value, null, null)
                    : read(be, manager, id, pos, null, null);
        }
        // 不是机器,可能是 AE2 的 part host(线缆/总线/面板):配置在某一面的 part 上。
        if (interfaceNamed(be.getClass(), PART_HOST) != null) {
            return partConfig(be, id, coord, side, writing, setting, value);
        }
        if (writing) {
            return TaskResult.fail(id + " at " + coord + " exposes no server-side config to change (it is not an "
                    + "AE2-style configurable machine).").toJson();
        }
        return read(be, null, id, pos, null, null);
    }

    // ---- read ----

    private static String read(Object target, Object manager, String id, BlockPos pos, String side, String part) {
        String coord = pos.getX() + "," + pos.getY() + "," + pos.getZ();
        List<Map<String, Object>> settings = manager == null ? List.of() : readSettings(manager);
        Map<String, Object> power = readPower(target);
        List<Map<String, Object>> upgrades = readUpgrades(target);
        if (settings.isEmpty() && power == null && upgrades.isEmpty()) {
            return TaskResult.fail(describe(id, part, side) + " at " + coord
                    + " exposes no readable server-side config (it is not an AE2-style configurable machine; "
                    + "its settings may live only in a client GUI).").toJson();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("block", id);
        if (part != null) {
            data.put("part", part);
        }
        if (side != null) {
            data.put("side", side);
        }
        data.put("x", pos.getX());
        data.put("y", pos.getY());
        data.put("z", pos.getZ());
        if (!settings.isEmpty()) {
            data.put("settings", settings);
        }
        if (power != null) {
            data.put("ae_power", power);
        }
        if (!upgrades.isEmpty()) {
            data.put("upgrades", upgrades);
        }

        StringBuilder message = new StringBuilder(describe(id, part, side));
        if (settings.isEmpty()) {
            message.append(" exposes no config settings");
        } else {
            message.append(" config: ");
            for (int i = 0; i < settings.size(); i++) {
                Map<String, Object> entry = settings.get(i);
                if (i > 0) {
                    message.append("; ");
                }
                message.append(entry.get("name")).append('=').append(entry.get("value"));
            }
        }
        if (power != null) {
            message.append(" · AE power ").append(power.get("current")).append('/').append(power.get("max"));
        }
        if (!upgrades.isEmpty()) {
            message.append(" · ").append(upgrades.size()).append(" upgrade(s)");
        }
        return TaskResult.ok(message.toString(), data).toJson();
    }

    /** 配置管理器里的每一项:{@code name=value},能便宜地拿到就给上 allowed。 */
    private static List<Map<String, Object>> readSettings(Object manager) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object setting : settingsOf(manager)) {
            try {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", nameOf(setting));
                entry.put("value", nameOf(readValue(setting, manager)));
                List<String> allowed = namesOf(settingValues(setting));
                if (!allowed.isEmpty()) {
                    entry.put("allowed", allowed);
                }
                out.add(entry);
            } catch (Throwable ignored) {
                // 某一项坏掉只丢它自己
            }
        }
        return out;
    }

    private static Map<String, Object> readPower(Object be) {
        if (interfaceNamed(be.getClass(), AE_POWER) == null) {
            return null;
        }
        try {
            Method current = zeroArg(be.getClass(), "getAECurrentPower");
            Method max = zeroArg(be.getClass(), "getAEMaxPower");
            if (current == null || max == null) {
                return null;
            }
            Map<String, Object> power = new LinkedHashMap<>();
            power.put("current", number(current.invoke(be)));
            power.put("max", number(max.invoke(be)));
            Method flow = zeroArg(be.getClass(), "getPowerFlow");
            if (flow != null) {
                power.put("flow", nameOf(flow.invoke(be)));
            }
            return power;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<Map<String, Object>> readUpgrades(Object be) {
        if (interfaceNamed(be.getClass(), UPGRADEABLE) == null) {
            return List.of();
        }
        try {
            Method getter = zeroArg(be.getClass(), "getUpgrades");
            if (getter == null) {
                return List.of();
            }
            Object inventory = getter.invoke(be);
            if (inventory == null) {
                return List.of();
            }
            Method size = zeroArg(inventory.getClass(), "size");
            Method stack = methodNamed(inventory.getClass(), "getStackInSlot", 1);
            if (size == null || stack == null) {
                return List.of();
            }
            int slots = (int) size.invoke(inventory);
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = 0; i < slots; i++) {
                Object value = stack.invoke(inventory, i);
                if (value instanceof ItemStack item && !item.isEmpty()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("slot", i);
                    entry.put("item", BuiltInRegistries.ITEM.getKey(item.getItem()).toString());
                    entry.put("count", item.getCount());
                    out.add(entry);
                }
            }
            return out;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    // ---- write ----

    private static String set(BlockEntity be, Object manager, String id, String coord, String settingName,
                              String value, String side, String part) {
        if (manager == null) {
            return TaskResult.fail(describe(id, part, side) + " at " + coord + " exposes no server-side config "
                    + "to change (it is not an AE2-style configurable machine).").toJson();
        }
        Object setting = findSetting(manager, settingName);
        if (setting == null) {
            List<String> names = new ArrayList<>();
            for (Object candidate : settingsOf(manager)) {
                names.add(nameOf(candidate));
            }
            return TaskResult.fail("no setting named '" + settingName + "' on " + describe(id, part, side) + " at "
                    + coord
                    + (names.isEmpty() ? " — it has no settings." : " — available: " + String.join(", ", names)))
                    .toJson();
        }
        List<Object> allowed = settingValues(setting);
        Object parsed = parse(setting, value, allowed);
        if (parsed == null) {
            List<String> names = namesOf(allowed);
            return TaskResult.fail("'" + value + "' is not a valid value for " + nameOf(setting)
                    + (names.isEmpty() ? "." : " — allowed: " + String.join(", ", names))).toJson();
        }
        Object before = readValue(setting, manager);
        if (!putSetting(manager, setting, parsed)) {
            return TaskResult.fail("the config manager on " + describe(id, part, side) + " exposes no setter for "
                    + nameOf(setting) + " — it can only be read.").toJson();
        }
        // 配置改变了方块实体的活状态,标脏让它按普通存盘路径落盘;模组自己的监听器会处理网络同步。
        be.setChanged();
        Object after = readValue(setting, manager);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("block", id);
        if (part != null) {
            data.put("part", part);
        }
        if (side != null) {
            data.put("side", side);
        }
        data.put("setting", nameOf(setting));
        data.put("old_value", nameOf(before));
        data.put("new_value", nameOf(after));
        List<String> allowedNames = namesOf(allowed);
        if (!allowedNames.isEmpty()) {
            data.put("allowed", allowedNames);
        }
        return TaskResult.ok(describe(id, part, side) + " at " + coord + " " + nameOf(setting) + ": "
                + nameOf(before) + " -> " + nameOf(after), data).toJson();
    }

    private static Object findSetting(Object manager, String wanted) {
        for (Object setting : settingsOf(manager)) {
            if (nameOf(setting).equalsIgnoreCase(wanted)) {
                return setting;
            }
        }
        return null;
    }

    /** 把用户给的值对到 setting 的某个枚举常量上:认 {@code name()} 与 {@code toString()} 两种写法。 */
    private static Object parse(Object setting, String value, List<Object> allowed) {
        String wanted = value.strip();
        for (Object candidate : allowed) {
            if (nameOf(candidate).equalsIgnoreCase(wanted)
                    || String.valueOf(candidate).equalsIgnoreCase(wanted)) {
                return candidate;
            }
        }
        if (!allowed.isEmpty()) {
            return null;   // 拿得到允许表就以它为准,别放进表外的值
        }
        Method enumClass = zeroArg(setting.getClass(), "getEnumClass");
        if (enumClass != null) {
            try {
                Object type = enumClass.invoke(setting);
                if (type instanceof Class<?> cls && cls.isEnum()) {
                    for (Object constant : cls.getEnumConstants()) {
                        if (nameOf(constant).equalsIgnoreCase(wanted)) {
                            return constant;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 退回枚举表失败就当值不合法
            }
        }
        return null;
    }

    // ---- reflection ----

    /**
     * 方块实体或 part 背后的配置管理器:它自己就是管理器,或它有 {@code getConfigManager()}。认不出给 null。
     * 不加载任何模组类——只按名字看接口、按名字取方法。
     */
    private static Object configManagerOf(Object target) {
        if (interfaceNamed(target.getClass(), CONFIG_MANAGER) != null) {
            return target;
        }
        if (interfaceNamed(target.getClass(), CONFIGURABLE) == null
                && zeroArg(target.getClass(), "getConfigManager") == null) {
            return null;
        }
        Method getter = zeroArg(target.getClass(), "getConfigManager");
        if (getter == null) {
            return null;
        }
        try {
            Object manager = getter.invoke(target);
            return manager != null && zeroArg(manager.getClass(), "getSettings") != null ? manager : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---- AE2 parts on a cable/part host ----

    /** 一个 part 和它在 host 上的面;{@code side} 为 null 表示 host 中心。 */
    private record PartRef(Direction side, Object part) {
        String sideLabel() {
            return side == null ? "center" : side.getName();
        }
    }

    /**
     * part host 上一个方块上的 part 没有 IConfigManager:挑出配置挂在某一面的 part,读全部或按 {@code side}
     * 指定一个来读/写。多个可配 part 而不给 side 时,读列出全部,写要求指定面。
     */
    private static String partConfig(BlockEntity be, String id, String coord, String side, boolean writing,
                                     String setting, String value) {
        List<PartRef> parts = new ArrayList<>();
        for (PartRef ref : partsOf(be)) {
            if (configManagerOf(ref.part()) != null) {
                parts.add(ref);
            }
        }
        if (parts.isEmpty()) {
            return TaskResult.fail(id + " at " + coord + " is an AE2 part host but has no part with server-side "
                    + "config (cables, anchors and toggle busses keep no settings).").toJson();
        }
        if (side != null && !side.isBlank()) {
            Direction wanted = directionOf(side);
            for (PartRef ref : parts) {
                if (ref.side() == wanted) {
                    return readOrSet(be, ref, id, coord, writing, setting, value);
                }
            }
            return TaskResult.fail("no configurable part on side '" + side + "' of " + id + " at " + coord
                    + " — it has parts on: " + sideList(parts) + ".").toJson();
        }
        if (parts.size() == 1) {
            return readOrSet(be, parts.get(0), id, coord, writing, setting, value);
        }
        if (writing) {
            return TaskResult.fail(id + " at " + coord + " has " + parts.size() + " configurable parts — pass side "
                    + "to choose one (parts on: " + sideList(parts) + ").").toJson();
        }
        return readParts(be, id, coord, parts);
    }

    private static String readOrSet(BlockEntity be, PartRef ref, String id, String coord, boolean writing,
                                    String setting, String value) {
        Object manager = configManagerOf(ref.part());
        String partId = partId(ref.part());
        return writing
                ? set(be, manager, id, coord, setting, value, ref.sideLabel(), partId)
                : read(ref.part(), manager, id, be.getBlockPos(), ref.sideLabel(), partId);
    }

    /** 一个 host 上的所有 part,先中心后六面;读不出 part 的一面跳过。 */
    private static List<PartRef> partsOf(Object host) {
        List<PartRef> out = new ArrayList<>();
        Method getPart = methodNamed(host.getClass(), "getPart", 1);
        if (getPart == null) {
            return out;
        }
        Object center = invokePart(getPart, host, null);
        if (center != null) {
            out.add(new PartRef(null, center));
        }
        for (Direction side : Direction.values()) {
            Object part = invokePart(getPart, host, side);
            if (part != null) {
                out.add(new PartRef(side, part));
            }
        }
        return out;
    }

    private static Object invokePart(Method getPart, Object host, Direction side) {
        try {
            return getPart.invoke(host, side);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 多个可配 part 的读:每个 part 一份,带上它在哪个面。 */
    private static String readParts(BlockEntity be, String id, String coord, List<PartRef> parts) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PartRef ref : parts) {
            Object manager = configManagerOf(ref.part());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("side", ref.sideLabel());
            String partId = partId(ref.part());
            if (partId != null) {
                entry.put("part", partId);
            }
            List<Map<String, Object>> settings = manager == null ? List.of() : readSettings(manager);
            if (!settings.isEmpty()) {
                entry.put("settings", settings);
            }
            Map<String, Object> power = readPower(ref.part());
            if (power != null) {
                entry.put("ae_power", power);
            }
            List<Map<String, Object>> upgrades = readUpgrades(ref.part());
            if (!upgrades.isEmpty()) {
                entry.put("upgrades", upgrades);
            }
            out.add(entry);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("block", id);
        data.put("x", be.getBlockPos().getX());
        data.put("y", be.getBlockPos().getY());
        data.put("z", be.getBlockPos().getZ());
        data.put("parts", out);
        return TaskResult.ok(id + " at " + coord + " has " + parts.size() + " configurable part(s) on "
                + sideList(parts) + "; read one by side, or pass side + setting + value to change it.", data).toJson();
    }

    /** part 的物品 id({@code getPartItem()} → {@link net.minecraft.world.level.ItemLike});认不出给 null。 */
    private static String partId(Object part) {
        try {
            Method getter = zeroArg(part.getClass(), "getPartItem");
            if (getter == null) {
                return null;
            }
            Object item = getter.invoke(part);
            if (item instanceof net.minecraft.world.level.ItemLike like) {
                return BuiltInRegistries.ITEM.getKey(like.asItem()).toString();
            }
        } catch (Throwable ignored) {
            // 认不出是哪种 part 就当没有 id
        }
        return null;
    }

    private static Direction directionOf(String raw) {
        String wanted = raw.strip().toLowerCase(Locale.ROOT);
        for (Direction side : Direction.values()) {
            if (side.getName().equals(wanted)) {
                return side;
            }
        }
        return null;
    }

    private static String sideList(List<PartRef> parts) {
        List<String> out = new ArrayList<>();
        for (PartRef ref : parts) {
            String partId = partId(ref.part());
            out.add(ref.sideLabel() + (partId == null ? "" : "=" + partId));
        }
        return String.join(", ", out);
    }

    private static String describe(String id, String part, String side) {
        if (part != null) {
            return part + (side == null ? "" : " on " + side);
        }
        return id;
    }

    private static List<Object> settingsOf(Object manager) {
        Method method = zeroArg(manager.getClass(), "getSettings");
        if (method == null) {
            return List.of();
        }
        try {
            return flatten(method.invoke(manager));
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    /** {@code Setting.getValues()} 的返回值;拿不到给空表,由 {@link #parse} 退回枚举常量表。 */
    private static List<Object> settingValues(Object setting) {
        Method method = zeroArg(setting.getClass(), "getValues");
        if (method == null) {
            return List.of();
        }
        try {
            return flatten(method.invoke(setting));
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private static Object readValue(Object setting, Object manager) {
        for (Method method : setting.getClass().getMethods()) {
            if (!method.getName().equals("getValue") || method.getParameterCount() != 1) {
                continue;
            }
            if (!method.getParameterTypes()[0].isInstance(manager)) {
                continue;
            }
            try {
                return method.invoke(setting, manager);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /** 写一档。AE2 20 是 {@code putSetting};老实现叫 {@code setSetting},两个名字都认。 */
    private static boolean putSetting(Object manager, Object setting, Object value) {
        for (String name : new String[]{"putSetting", "setSetting"}) {
            for (Method method : manager.getClass().getMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 2) {
                    continue;
                }
                Class<?>[] types = method.getParameterTypes();
                if (!types[0].isInstance(setting) || !types[1].isInstance(value)) {
                    continue;
                }
                try {
                    method.invoke(manager, setting, value);
                    return true;
                } catch (Throwable broken) {
                    // 同名的另一个口也许能用
                }
            }
        }
        return false;
    }

    /** 无参、有返回值的公开方法;没有给 null。 */
    private static Method zeroArg(Class<?> type, String name) {
        try {
            Method method = type.getMethod(name);
            return method.getParameterCount() == 0 && method.getReturnType() != void.class ? method : null;
        } catch (NoSuchMethodException | SecurityException absent) {
            return null;
        }
    }

    /** 按名字与参数个数取公开方法;没有给 null。 */
    private static Method methodNamed(Class<?> type, String name, int parameters) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters) {
                return method;
            }
        }
        return null;
    }

    /** 类(含父类)或它实现的接口里,名叫 {@code name} 的那个;没有给 null。 */
    private static Class<?> interfaceNamed(Class<?> type, String name) {
        if (type == null) {
            return null;
        }
        for (Class<?> iface : type.getInterfaces()) {
            if (iface.getName().equals(name)) {
                return iface;
            }
            Class<?> deeper = interfaceNamed(iface, name);
            if (deeper != null) {
                return deeper;
            }
        }
        return interfaceNamed(type.getSuperclass(), name);
    }

    private static List<Object> flatten(Object value) {
        List<Object> out = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                out.add(element);
            }
        } else if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                out.add(Array.get(value, i));
            }
        }
        return out;
    }

    private static List<String> namesOf(List<Object> values) {
        List<String> out = new ArrayList<>();
        for (Object value : values) {
            String name = nameOf(value);
            if (!out.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    private static String nameOf(Object value) {
        if (value == null) {
            return "";
        }
        return value instanceof Enum<?> e ? e.name() : String.valueOf(value);
    }

    private static String number(Object value) {
        return value instanceof Number n ? String.format(Locale.ROOT, "%.1f", n.doubleValue())
                : String.valueOf(value);
    }
}
