package com.dwinovo.numen.plugins.ae2;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.pathing.ChannelMode;
import appeng.api.networking.pathing.ControllerState;
import appeng.api.networking.pathing.IPathingService;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartItem;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.ServerCall;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code ae2.network}:从一格出发走整张 ME 网络,把频道、控制器、电量、设备清单读出来交回脚本。
 *
 * <p>取节点的路子与 AE2 自己的测试夹具一致({@link GridHelper#getNodeHost} 逐面
 * {@link IInWorldGridNodeHost#getGridNode(Direction)}):线缆、总线、机器都从同一张 capability 表拿节点。
 * 读出来的是值,脚本原样能接着用;只读,不动世界。AE2 不在场时这个类不会被加载。
 */
public final class Ae2NetworkApi {

    private static final int MAX_DEVICES_SHOWN = 32;

    private Ae2NetworkApi() {}

    public static void install(NumenApi numen) {
        numen.api("network", "Inspect the AE2 ME network a cable or device is on: channels used, controller "
                + "state, stored AE energy and the devices on it.", Ae2NetworkApi.class);
    }

    public record Args(@Doc("Block X of any AE2 cable-connected block (a cable, or a machine/part on a grid).")
                       int x,
                       @Doc("Block Y.") int y,
                       @Doc("Block Z.") int z) {}

    /** 一张 ME 网络此刻的状态。离网时只有 {@code block} 与 {@code onGrid=false},其余为 0/false。 */
    public record Network(@Doc("Block id at that position.") String block,
                          @Doc("Whether it is on an AE2 grid.") boolean onGrid,
                          @Doc("Whether this node is active.") boolean nodeActive,
                          @Doc("Channels this node uses.") int nodeChannelsUsed,
                          @Doc("Channels this node can carry.") int nodeChannelsMax,
                          @Doc("Whether the network is powered.") boolean gridPowered,
                          @Doc("Controller state: NO_CONTROLLER / CONTROLLER_ONLINE / CONTROLLER_CONFLICT / UNKNOWN.")
                          String controllerState,
                          @Doc("Whether a controller is on the grid.") boolean controllerPresent,
                          @Doc("Channel mode.") String channelMode,
                          @Doc("Total channels in use on the grid.") int channelsUsed,
                          @Doc("Channel ceiling with no controller (ad-hoc network).") int channelsLimit,
                          @Doc("How many nodes the grid has.") int deviceCount,
                          @Doc("Block/item ids on the grid, most numerous first, e.g. \"3 x ae2:drive\".")
                          List<String> devices,
                          @Doc("Stored AE.") double energyStored,
                          @Doc("Maximum AE.") double energyMax,
                          @Doc("Average AE usage per tick.") double energyAvgUsage,
                          @Doc("Whether the energy service sees the network as powered.") boolean energyPowered,
                          @Doc("A one-line human summary.") String summary) {}

    @Fn("Inspect the AE2 ME network a cable or device is on: channels used, controller state, stored AE "
            + "energy and the devices on it.")
    @Example("local n = ae2.network.inspect(120, 64, -3)\nprint(n.controllerState, n.channelsUsed, n.deviceCount)")
    @Example("for _, d in ipairs(ae2.network.inspect(120, 64, -3).devices) do print(d) end")
    public static Network inspect(ServerCall call, Args args) {
        Level level = call.her().level();
        BlockPos pos = new BlockPos(args.x(), args.y(), args.z());
        BlockState state = level.getBlockState(pos);
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (state.isAir()) {
            return offGrid(id, id + " at " + coord(pos) + " is air — nothing on a grid there.");
        }
        IGridNode node = resolveNode(level, pos);
        if (node == null) {
            return offGrid(id, id + " at " + coord(pos) + " is not on an AE2 grid (no grid node — not a cable "
                    + "or an AE2 machine/part).");
        }
        IGrid grid = node.getGrid();
        if (grid == null) {
            return offGrid(id, id + " at " + coord(pos) + " has an AE2 node but its grid is not booted yet — retry "
                    + "in a tick.");
        }
        return describe(id, pos, node, grid);
    }

    private static Network describe(String id, BlockPos pos, IGridNode node, IGrid grid) {
        IPathingService pathing = grid.getPathingService();
        ControllerState controller = pathing == null ? null : pathing.getControllerState();
        ChannelMode mode = pathing == null ? null : pathing.getChannelMode();
        int channelsUsed = pathing == null ? 0 : pathing.getUsedChannels();
        int adHocLimit = mode == null ? 0 : mode.getAdHocNetworkChannels();
        boolean noController = controller == ControllerState.NO_CONTROLLER;

        Map<String, Integer> devices = new LinkedHashMap<>();
        int controllerBlocks = 0;
        for (IGridNode member : grid.getNodes()) {
            String deviceId = ownerId(member.getOwner());
            if (deviceId == null) {
                continue;
            }
            devices.merge(deviceId, 1, Integer::sum);
            if ("ae2:controller".equals(deviceId)) {
                controllerBlocks++;
            }
        }

        IEnergyService energy = grid.getEnergyService();
        double stored = energy == null ? 0 : round(energy.getStoredPower());
        double max = energy == null ? 0 : round(energy.getMaxStoredPower());
        double avg = energy == null ? 0 : round(energy.getAvgPowerUsage());
        boolean powered = energy != null && energy.isNetworkPowered();

        String controllerName = controller == null ? "UNKNOWN" : controller.name();
        StringBuilder summary = new StringBuilder("AE2 network at ").append(coord(pos)).append(" (").append(id)
                .append("): controller ").append(controllerName);
        if (noController) {
            summary.append(", ad-hoc — channels ").append(channelsUsed).append('/').append(adHocLimit);
        } else {
            summary.append(" — channels ").append(channelsUsed).append(" used");
        }
        if (energy != null) {
            summary.append("; energy ").append(stored).append('/').append(max).append(" AE");
        }
        summary.append("; ").append(grid.size()).append(" device(s)");

        return new Network(id, true, nodeActive(node), nodeChannels(node, true), nodeChannels(node, false),
                gridPowered(node), controllerName, controller != null && controller != ControllerState.NO_CONTROLLER,
                mode == null ? "" : mode.name(), channelsUsed, noController ? adHocLimit : 0, grid.size(),
                deviceList(devices), stored, max, avg, powered, summary.toString());
    }

    /** 先把中心节点试出来(裸线缆/机器),没有再逐面试(面上的 part)。 */
    private static IGridNode resolveNode(Level level, BlockPos pos) {
        IInWorldGridNodeHost host = GridHelper.getNodeHost(level, pos);
        if (host == null) {
            return null;
        }
        IGridNode center = tryNode(host, null);
        if (center != null) {
            return center;
        }
        for (Direction side : Direction.values()) {
            IGridNode node = tryNode(host, side);
            if (node != null) {
                return node;
            }
        }
        return null;
    }

    private static IGridNode tryNode(IInWorldGridNodeHost host, Direction side) {
        try {
            return host.getGridNode(side);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean nodeActive(IGridNode node) {
        try {
            return node.isActive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int nodeChannels(IGridNode node, boolean used) {
        try {
            return used ? node.getUsedChannels() : node.getMaxChannels();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean gridPowered(IGridNode node) {
        try {
            IGrid grid = node.getGrid();
            IEnergyService energy = grid == null ? null : grid.getEnergyService();
            return energy != null && energy.isNetworkPowered();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String ownerId(Object owner) {
        try {
            if (owner instanceof BlockEntity be) {
                return BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).toString();
            }
            if (owner instanceof IPart part) {
                IPartItem<?> item = part.getPartItem();
                if (item != null) {
                    return BuiltInRegistries.ITEM.getKey(item.asItem()).toString();
                }
            }
        } catch (Throwable ignored) {
            // 认不出所有者就当它不在清单里
        }
        return null;
    }

    /** 设备 id 计数,多的在前,最多 {@value #MAX_DEVICES_SHOWN} 条,写成 "3 x ae2:drive"。 */
    private static List<String> deviceList(Map<String, Integer> devices) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(devices.entrySet());
        entries.sort((a, b) -> {
            int byCount = Integer.compare(b.getValue(), a.getValue());
            return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
        });
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : entries) {
            if (out.size() >= MAX_DEVICES_SHOWN) {
                break;
            }
            out.add(entry.getValue() + " x " + entry.getKey());
        }
        return out;
    }

    private static Network offGrid(String id, String summary) {
        return new Network(id, false, false, 0, 0, false, "UNKNOWN", false, "", 0, 0, 0,
                List.of(), 0, 0, 0, false, summary);
    }

    private static String coord(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
