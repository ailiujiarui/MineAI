package com.dwinovo.numen.plugins.mekanism;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code mekanism.machine}:从任意距离读一台 Mekanism 机器的状态——朝向、各面每种传输的输入/输出
 * (side config)、物品/流体/能量(标准 capability)、气体/浆液/灌注等化学罐、热量。
 *
 * <p>这是旧适配器里 {@code scan_storage} 那一半的搬家:同伴没有客户端屏幕,点不到侧配界面,
 * 但这些控件的数据住在服务端,读出来的是值,脚本原样能接着用;只读,不动世界。
 *
 * <p>朝向与 side config 走的是 Mekanism 自己的实现类({@code mekanism.common.tile.*}),它们与旧模块
 * 编译时用的 {@code maven.modrinth:mekanism:10.7.19.85} 同一份 artifact,只 {@code compileOnly}、不携带。
 * 化学罐优先读方块实体上的公开 {@link IChemicalTank} 字段(名称带语义,如 {@code infusionTank}),
 * 没有再看 {@link Capabilities#CHEMICAL} capability。Mekanism 不在场时这个类不会被加载。
 */
public final class MekanismApi {

    private MekanismApi() {}

    public static void install(NumenApi numen) {
        numen.api("machine", "Read a Mekanism machine from any distance: its facing, which side carries "
                + "what, stored items/fluids/energy, chemical tanks and heat.", MekanismApi.class);
    }

    /** 哪一格机器。 */
    public record At(@Doc("Block X of the Mekanism machine.") int x,
                     @Doc("Block Y.") int y,
                     @Doc("Block Z.") int z) {}

    /** 一个相对面此刻做什么。 */
    public record SideEntry(@Doc("Side relative to the machine's own front: front/left/right/back/top/bottom.")
                            String side,
                            @Doc("What that side does: input/output/input_output/energy/extra/none.")
                            String type) {}

    /** 一种传输类型的各面配置。 */
    public record Transmission(@Doc("What travels: item/fluid/chemical/energy/heat.") String transmission,
                               @Doc("One entry per configured side; sides left at NONE are omitted.")
                               List<SideEntry> sides) {}

    /** 一个化学罐(气体、浆液、灌注材料……)。 */
    public record Tank(@Doc("Tank name: the machine's public field, or 'tank N (sides: …)' from the capability.")
                       String name,
                       @Doc("Chemical id, e.g. mekanism:oxygen; empty when the tank is empty.") String chemical,
                       @Doc("Amount in mB.") long amount,
                       @Doc("Capacity in mB.") long capacity) {}

    /** 一台 Mekanism 机器此刻的状态。非 Mekanism 机器会失败,不会静默返回空表。 */
    public record Machine(@Doc("Block id at that position.") String block,
                          @Doc("Its blockstate properties, e.g. active=true, facing=north.")
                          Map<String, String> properties,
                          @Doc("The machine's facing direction, when it is side-configurable.")
                          Optional<String> facing,
                          @Doc("Per-transmission side configuration (what each face carries).")
                          List<Transmission> sideConfig,
                          @Doc("Items, fluids and energy it holds (standard capabilities), one line each.")
                          List<String> storage,
                          @Doc("Mekanism chemical tanks (gases, slurries, infusions...).") List<Tank> chemicals,
                          @Doc("Temperature in K, when it has a heat capacitor (ambient is ~300 K).")
                          Optional<Double> temperature,
                          @Doc("A one-line human summary.") String summary) {}

    @Fn("Read a Mekanism machine from any distance: facing, per-side input/output config, stored "
            + "items/fluids/energy, chemical tanks and heat.")
    @Example("local m = mekanism.machine.inspect(120, 64, -3)\nprint(m.block, m.facing, m.temperature)")
    @Example("for _, t in ipairs(mekanism.machine.inspect(120, 64, -3).side_config) do print(t.transmission) end")
    @Note("Instant and read-only, from any distance; nothing is opened or moved. A block that is not a "
            + "Mekanism machine fails with the reason.")
    @SeeAlso({"numen.scan.block", "numen.scan.container"})
    public static Machine inspect(ServerCall call, At args) {
        Level level = call.her().serverLevel();
        BlockPos pos = new BlockPos(args.x(), args.y(), args.z());
        BlockState state = level.getBlockState(pos);
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (state.isAir()) {
            throw new ApiError(ErrorKind.NOT_FOUND, "block at " + coord(pos) + " is air — nothing to read", null);
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, id + " at " + coord(pos)
                    + " is not a block entity — no Mekanism machine state to read", null);
        }
        return describe(level, pos, state, id, be);
    }

    private static Machine describe(Level level, BlockPos pos, BlockState state, String id, BlockEntity be) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (Property<?> property : state.getProperties()) {
            properties.put(property.getName(), propValue(state, property));
        }

        Optional<String> facing = Optional.empty();
        List<Transmission> sideConfig = List.of();
        if (be instanceof ISideConfiguration side) {
            facing = Optional.of(String.valueOf(side.getDirection()));
            sideConfig = readSideConfig(side);
        }

        List<String> storage = Services.CAPS.describe(level, pos);
        if (storage == null) {
            storage = List.of();
        }
        List<Tank> chemicals = readChemicals(level, pos, be);
        Optional<Double> temperature = readTemperature(level, pos);

        if (!(be instanceof ISideConfiguration) && chemicals.isEmpty() && temperature.isEmpty()) {
            throw new ApiError(ErrorKind.NOT_FOUND, id + " at " + coord(pos) + " exposes no readable Mekanism "
                    + "machine state (no side config, chemical tank or heat)", null);
        }
        return new Machine(id, properties, facing, sideConfig, storage, chemicals, temperature,
                summary(id, pos, facing, chemicals, temperature));
    }

    /** 朝向 + 每个面每种传输的输入/输出。相对面以机器自己的正面为准。 */
    private static List<Transmission> readSideConfig(ISideConfiguration side) {
        TileComponentConfig config = side.getConfig();
        if (config == null) {
            return List.of();
        }
        List<Transmission> out = new ArrayList<>();
        for (TransmissionType type : config.getTransmissions()) {
            List<SideEntry> entries = new ArrayList<>();
            for (RelativeSide rs : RelativeSide.values()) {
                DataType dt = config.getDataType(type, rs);
                if (dt == null || dt == DataType.NONE) {
                    continue;
                }
                entries.add(new SideEntry(rs.getSerializedName(), dt.getSerializedName()));
            }
            if (!entries.isEmpty()) {
                out.add(new Transmission(type.getName().toLowerCase(Locale.ROOT), entries));
            }
        }
        return out;
    }

    /** 命名化学罐优先(名字带语义),没有再看 capability。 */
    private static List<Tank> readChemicals(Level level, BlockPos pos, BlockEntity be) {
        List<Tank> named = readNamedChemicalTanks(be);
        return named.isEmpty() ? readChemicalCapability(level, pos) : named;
    }

    /**
     * 读方块实体上的公开 {@link IChemicalTank} 字段:灌注机的 {@code infusionTank} 就是这样一个,
     * 字段名直接说明罐里装的是什么。capability 未必暴露这些内部罐。
     */
    private static List<Tank> readNamedChemicalTanks(BlockEntity be) {
        List<Tank> out = new ArrayList<>();
        for (Field field : be.getClass().getFields()) {
            if (!IChemicalTank.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                IChemicalTank tank = (IChemicalTank) field.get(be);
                if (tank == null) {
                    continue;
                }
                ChemicalStack stack = tank.getStack();
                out.add(new Tank(field.getName(), chemicalId(stack), stack.getAmount(), tank.getCapacity()));
            } catch (Throwable ignored) {
                // 反射不到就当这个字段没有
            }
        }
        return out;
    }

    /** 气体储罐(Mekanism 的 chemical capability,标准 capability 读不到),按 handler 去重。 */
    private static List<Tank> readChemicalCapability(Level level, BlockPos pos) {
        Map<IChemicalHandler, List<String>> byHandler = new IdentityHashMap<>();
        collect(byHandler, level.getCapability(Capabilities.CHEMICAL.block(), pos, null), "all");
        for (Direction d : Direction.values()) {
            collect(byHandler, level.getCapability(Capabilities.CHEMICAL.block(), pos, d), d.getName());
        }
        List<Tank> out = new ArrayList<>();
        for (Map.Entry<IChemicalHandler, List<String>> e : byHandler.entrySet()) {
            IChemicalHandler handler = e.getKey();
            String where = String.join(",", e.getValue());
            for (int t = 0; t < handler.getChemicalTanks(); t++) {
                ChemicalStack stack = handler.getChemicalInTank(t);
                out.add(new Tank("tank " + t + " (sides: " + where + ")", chemicalId(stack), stack.getAmount(),
                        handler.getChemicalTankCapacity(t)));
            }
        }
        return out;
    }

    /** 热量(Mekanism 的 heat capability),没有就是空。 */
    private static Optional<Double> readTemperature(Level level, BlockPos pos) {
        IHeatHandler heat = level.getCapability(Capabilities.HEAT, pos, null);
        if (heat == null) {
            for (Direction d : Direction.values()) {
                heat = level.getCapability(Capabilities.HEAT, pos, d);
                if (heat != null) {
                    break;
                }
            }
        }
        return heat != null && heat.getHeatCapacitorCount() > 0 ? Optional.of(round(heat.getTotalTemperature()))
                : Optional.empty();
    }

    private static String chemicalId(ChemicalStack stack) {
        if (stack.isEmpty()) {
            return "";
        }
        try {
            return stack.getChemicalHolder().unwrapKey().map(key -> key.location().toString()).orElse("?");
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static <T> void collect(Map<T, List<String>> byHandler, T handler, String side) {
        if (handler == null) {
            return;
        }
        byHandler.computeIfAbsent(handler, h -> new ArrayList<>()).add(side);
    }

    private static String summary(String id, BlockPos pos, Optional<String> facing, List<Tank> chemicals,
                                  Optional<Double> temperature) {
        StringBuilder sb = new StringBuilder("Mekanism machine ").append(id).append(" at ").append(coord(pos));
        facing.ifPresent(f -> sb.append(", facing ").append(f));
        sb.append("; ").append(chemicals.size()).append(" chemical tank(s)");
        if (!chemicals.isEmpty()) {
            Tank first = chemicals.get(0);
            sb.append(": ").append(first.name()).append('=')
                    .append(first.chemical().isEmpty() ? "empty" : first.chemical())
                    .append(' ').append(first.amount()).append('/').append(first.capacity()).append(" mB");
        }
        temperature.ifPresent(k -> sb.append("; ").append(k).append(" K"));
        return sb.toString();
    }

    private static <T extends Comparable<T>> String propValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static String coord(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
