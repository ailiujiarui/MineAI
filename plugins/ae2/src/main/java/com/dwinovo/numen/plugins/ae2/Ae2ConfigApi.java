package com.dwinovo.numen.plugins.ae2;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.ServerCall;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code ae2.config}:读/写 AE2 机器的服务端配置(模式、面的输入/输出、访问限制、阻挡、模糊……)以及线缆上
 * part 自己的配置。同伴没有客户端屏幕,这些控件它点不到;这里从服务端读同一份配置,而且能改。
 *
 * <p>AE2 的线缆/总线是 part host:配置不在方块实体上,而在插在某一面的 part 自己的 {@code IConfigManager} 里。
 * 多个可配 part 时,读列出全部,写用 {@code side} 指定哪一个。
 *
 * <p>全程按接口名/方法名反射,不加载任何 AE2 类(core 与插件都不为它背编译依赖),每一步包 {@code Throwable}。
 * 写不改世界方块,不占身体。
 */
public final class Ae2ConfigApi {

    private static final String CONFIGURABLE = "appeng.api.util.IConfigurableObject";
    private static final String CONFIG_MANAGER = "appeng.api.util.IConfigManager";
    private static final String AE_POWER = "appeng.api.networking.energy.IAEPowerStorage";
    private static final String PART_HOST = "appeng.api.parts.IPartHost";

    private Ae2ConfigApi() {}

    public static void install(NumenApi numen) {
        numen.api("config", "Read or change an AE2 machine's server-side settings (mode, faces, access, "
                + "blocking, fuzzy...) and any configurable part on a cable.", Ae2ConfigApi.class);
    }

    public record ReadArgs(@Doc("Block X.") int x, @Doc("Block Y.") int y, @Doc("Block Z.") int z,
                           @Doc("For a part on a cable: the face it is on (up/down/north/south/east/west).")
                           @Omitted("read every configurable part") Optional<String> side) {}

    /** 一档配置:{@code name=value},能拿到可选值表时给出 {@code allowed}。 */
    public record Setting(@Doc("Setting name, e.g. io_direction.") String name,
                          @Doc("Current value.") String value,
                          @Doc("Values this setting accepts, if known.") List<String> allowed) {}

    /** 线缆上一面 part 的配置。 */
    public record PartEntry(@Doc("Face it is on.") String side,
                           @Doc("Part item id.") String part,
                           @Doc("Its settings.") List<Setting> settings) {}

    /** 一台机器此刻的服务端配置。{@code part}/{@code side} 为空串表示读的是方块实体自己。 */
    public record Config(@Doc("Block id.") String block,
                         @Doc("Part item id, if config came from a part.") String part,
                         @Doc("Face, if config came from a part.") String side,
                         @Doc("The entity's own settings.") List<Setting> settings,
                         @Doc("Stored AE, if it is a power storage.") long aeCurrent,
                         @Doc("Max AE, if it is a power storage.") long aeMax,
                         @Doc("Configurable parts on a cable, when no side was given.") List<PartEntry> parts) {}

    public record WriteArgs(@Doc("Block X.") int x, @Doc("Block Y.") int y, @Doc("Block Z.") int z,
                            @Doc("Setting name, case-insensitive.") String setting,
                            @Doc("New value.") String value,
                            @Doc("For a part on a cable: the face it is on.") @Omitted("the block entity itself")
                            Optional<String> side) {}

    /** 一次改动的前后值。 */
    public record Changed(@Doc("Block id.") String block, @Doc("Part item id, if any.") String part,
                          @Doc("Face, if a part.") String side, @Doc("Setting name.") String setting,
                          @Doc("Value before.") String oldValue, @Doc("Value after.") String newValue,
                          @Doc("Accepted values, if known.") List<String> allowed) {}

    @Fn("Read an AE2 machine's server-side settings; on a cable, list every configurable part.")
    @Example("local c = ae2.config.read(120, 64, -3)\nfor _, s in ipairs(c.settings) do print(s.name, s.value) end")
    @Example("ae2.config.read(120, 64, -3, \"north\")")
    public static Config read(ServerCall call, ReadArgs args) {
        BlockPos pos = new BlockPos(args.x(), args.y(), args.z());
        BlockEntity be = entity(call, pos);
        String id = blockId(call, pos);
        String side = args.side().orElse("");
        Object manager = configManagerOf(be);
        if (manager != null) {
            return new Config(id, "", "", readSettings(manager), aeCurrent(be), aeMax(be), List.of());
        }
        if (interfaceNamed(be.getClass(), PART_HOST) != null) {
            List<PartRef> parts = partsOf(be);
            if (!side.isBlank()) {
                PartRef ref = bySide(parts, side);
                if (ref == null) {
                    throw new ApiError(ErrorKind.NOT_FOUND, "no configurable part on side '" + side + "' of " + id
                            + " at " + coord(pos) + " — it has parts on: " + sideList(parts), null);
                }
                return partConfig(ref);
            }
            List<PartEntry> entries = new ArrayList<>();
            for (PartRef ref : parts) {
                Object partManager = configManagerOf(ref.part());
                entries.add(new PartEntry(ref.sideLabel(), partId(ref.part()),
                        partManager == null ? List.of() : readSettings(partManager)));
            }
            if (entries.isEmpty()) {
                throw new ApiError(ErrorKind.NOT_FOUND, id + " at " + coord(pos) + " is an AE2 part host but has no "
                        + "part with server-side config (cables, anchors and toggle busses keep no settings).", null);
            }
            return new Config(id, "", "", List.of(), 0, 0, entries);
        }
        throw new ApiError(ErrorKind.NOT_FOUND, id + " at " + coord(pos) + " exposes no readable server-side config "
                + "(it is not an AE2-style configurable machine).", null);
    }

    @Fn("Change one server-side setting on an AE2 machine or a part on a cable.")
    @Example("ae2.config.write(120, 64, -3, \"io_direction\", \"LEFT\")")
    @Example("ae2.config.write(120, 64, -3, \"blocking_mode\", \"YES\", \"north\")")
    public static Changed write(ServerCall call, WriteArgs args) {
        BlockPos pos = new BlockPos(args.x(), args.y(), args.z());
        BlockEntity be = entity(call, pos);
        String id = blockId(call, pos);
        String side = args.side().orElse("");
        Object target = be;
        String part = "";
        String sideLabel = "";
        if (configManagerOf(be) == null && interfaceNamed(be.getClass(), PART_HOST) != null) {
            List<PartRef> parts = partsOf(be);
            if (side.isBlank()) {
                throw new ApiError(ErrorKind.BAD_ARGUMENT, id + " at " + coord(pos) + " has " + parts.size()
                        + " configurable part(s) — pass side to choose one (parts on: " + sideList(parts) + ").", null);
            }
            PartRef ref = bySide(parts, side);
            if (ref == null) {
                throw new ApiError(ErrorKind.NOT_FOUND, "no configurable part on side '" + side + "' of " + id
                        + " at " + coord(pos) + " — it has parts on: " + sideList(parts), null);
            }
            target = ref.part();
            part = partId(ref.part());
            sideLabel = ref.sideLabel();
        }
        Object manager = configManagerOf(target);
        if (manager == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, id + " at " + coord(pos) + " exposes no server-side config to "
                    + "change (it is not an AE2-style configurable machine).", null);
        }
        Object setting = findSetting(manager, args.setting());
        if (setting == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "no setting named '" + args.setting() + "' on " + id + " at "
                    + coord(pos) + settingsHint(manager), null);
        }
        List<Object> allowed = settingValues(setting);
        Object parsed = parse(setting, args.value(), allowed);
        if (parsed == null) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "'" + args.value() + "' is not a valid value for "
                    + nameOf(setting) + allowedHint(allowed), null);
        }
        Object before = readValue(setting, manager);
        if (!putSetting(manager, setting, parsed)) {
            throw new ApiError(ErrorKind.FAILED, "the config manager on " + id + " at " + coord(pos)
                    + " exposes no setter for " + nameOf(setting) + " — it can only be read.", null);
        }
        be.setChanged();
        Object after = readValue(setting, manager);
        return new Changed(id, part, sideLabel, nameOf(setting), nameOf(before), nameOf(after), namesOf(allowed));
    }

    // ---- read/write helpers ----

    private static Config partConfig(PartRef ref) {
        Object manager = configManagerOf(ref.part());
        return new Config("", partId(ref.part()), ref.sideLabel(),
                manager == null ? List.of() : readSettings(manager), aeCurrent(ref.part()), aeMax(ref.part()), List.of());
    }

    private static List<Setting> readSettings(Object manager) {
        List<Setting> out = new ArrayList<>();
        for (Object setting : settingsOf(manager)) {
            try {
                out.add(new Setting(nameOf(setting), nameOf(readValue(setting, manager)), namesOf(settingValues(setting))));
            } catch (Throwable ignored) {
                // 某一项坏掉只丢它自己
            }
        }
        return out;
    }

    private static Object findSetting(Object manager, String wanted) {
        for (Object setting : settingsOf(manager)) {
            if (nameOf(setting).equalsIgnoreCase(wanted)) {
                return setting;
            }
        }
        return null;
    }

    private static String settingsHint(Object manager) {
        List<String> names = new ArrayList<>();
        for (Object setting : settingsOf(manager)) {
            names.add(nameOf(setting));
        }
        return names.isEmpty() ? " — it has no settings." : " — available: " + String.join(", ", names);
    }

    private static String allowedHint(List<Object> allowed) {
        List<String> names = namesOf(allowed);
        return names.isEmpty() ? "." : " — allowed: " + String.join(", ", names);
    }

    private static Object parse(Object setting, String value, List<Object> allowed) {
        String wanted = value == null ? "" : value.strip();
        for (Object candidate : allowed) {
            if (nameOf(candidate).equalsIgnoreCase(wanted) || String.valueOf(candidate).equalsIgnoreCase(wanted)) {
                return candidate;
            }
        }
        if (!allowed.isEmpty()) {
            return null;
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

    private static long aeCurrent(Object target) {
        return aeNumber(target, "getAECurrentPower");
    }

    private static long aeMax(Object target) {
        return aeNumber(target, "getAEMaxPower");
    }

    private static long aeNumber(Object target, String method) {
        if (target == null || interfaceNamed(target.getClass(), AE_POWER) == null) {
            return 0;
        }
        Method getter = zeroArg(target.getClass(), method);
        if (getter == null) {
            return 0;
        }
        try {
            Object value = getter.invoke(target);
            return value instanceof Number n ? Math.round(n.doubleValue()) : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    // ---- AE2 parts on a cable ----

    private record PartRef(Direction side, Object part) {
        String sideLabel() {
            return side == null ? "center" : side.getName();
        }
    }

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
            if (part != null && configManagerOf(part) != null) {
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

    private static PartRef bySide(List<PartRef> parts, String raw) {
        Direction wanted = directionOf(raw);
        if (wanted == null) {
            return null;
        }
        for (PartRef ref : parts) {
            if (ref.side() == wanted) {
                return ref;
            }
        }
        return null;
    }

    private static Direction directionOf(String raw) {
        String wanted = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
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
            out.add(ref.sideLabel() + "=" + partId(ref.part()));
        }
        return String.join(", ", out);
    }

    private static String partId(Object part) {
        try {
            Method getter = zeroArg(part.getClass(), "getPartItem");
            if (getter == null) {
                return "";
            }
            Object item = getter.invoke(part);
            if (item instanceof net.minecraft.world.level.ItemLike like) {
                return BuiltInRegistries.ITEM.getKey(like.asItem()).toString();
            }
        } catch (Throwable ignored) {
            // 认不出是哪种 part
        }
        return "";
    }

    // ---- reflection ----

    private static Object configManagerOf(Object target) {
        if (target == null) {
            return null;
        }
        if (interfaceNamed(target.getClass(), CONFIG_MANAGER) != null) {
            return target;
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
            if (!method.getName().equals("getValue") || method.getParameterCount() != 1
                    || !method.getParameterTypes()[0].isInstance(manager)) {
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

    private static Method zeroArg(Class<?> type, String name) {
        try {
            Method method = type.getMethod(name);
            return method.getParameterCount() == 0 && method.getReturnType() != void.class ? method : null;
        } catch (NoSuchMethodException | SecurityException absent) {
            return null;
        }
    }

    private static Method methodNamed(Class<?> type, String name, int parameters) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters) {
                return method;
            }
        }
        return null;
    }

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

    // ---- world plumbing ----

    private static BlockEntity entity(ServerCall call, BlockPos pos) {
        BlockState state = call.her().serverLevel().getBlockState(pos);
        if (state.isAir()) {
            throw new ApiError(ErrorKind.NOT_FOUND, "block at " + coord(pos) + " is air — nothing to read or "
                    + "configure.", null);
        }
        BlockEntity be = call.her().serverLevel().getBlockEntity(pos);
        if (be == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, blockId(call, pos) + " at " + coord(pos)
                    + " is not a block entity — it exposes no server-side config.", null);
        }
        return be;
    }

    private static String blockId(ServerCall call, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(call.her().serverLevel().getBlockState(pos).getBlock()).toString();
    }

    private static String coord(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
