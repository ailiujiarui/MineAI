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
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code ae2_network} 的服务端实现:从一格出发,走 AE2 的公开 API 把整个 ME 网络的状态读出来。
 *
 * <p>取节点的路子与 AE2 自己的测试夹具一致({@code GridHelper.getNodeHost} → 逐面
 * {@link IInWorldGridNodeHost#getGridNode(Direction)}):线缆、总线、机器都从同一张 capability 表拿节点。
 * 拿到 {@link IGrid} 后,频道/控制器走 {@link IPathingService},电量走 {@link IEnergyService},设备清单遍历
 * {@code grid.getNodes()} 按所有者归类。
 *
 * <p>全程包 {@code Throwable}:AE2 的 API 变了、某个附加模组的节点实现不全,都不能把这条回执炸到游戏里——
 * 读不出来就照实说读不出来。
 */
final class Ae2NetworkOps {

    String inspect(Integer x, Integer y, Integer z, NumenPlayer self) {
        if (x == null || y == null || z == null) {
            throw new IllegalArgumentException("x, y, z are required: an AE2 cable-connected block");
        }
        if (!(self.level() instanceof ServerLevel level)) {
            return TaskResult.fail("ae2_network needs a server level.").toJson();
        }
        BlockPos pos = new BlockPos(x, y, z);
        String coord = x + "," + y + "," + z;
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return TaskResult.fail("block at " + coord + " is air — nothing on a grid there.").toJson();
        }
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        try {
            IGridNode node = resolveNode(level, pos);
            if (node == null) {
                return TaskResult.ok(id + " at " + coord + " is not on an AE2 grid (no grid node — it is not a "
                        + "cable or an AE2 machine/part).", noGrid(id)).toJson();
            }
            IGrid grid = node.getGrid();
            if (grid == null) {
                return TaskResult.ok(id + " at " + coord + " has an AE2 node but its grid is not booted yet — "
                        + "retry in a tick.", noGrid(id)).toJson();
            }
            return describe(id, pos, node, grid);
        } catch (Throwable broken) {
            return TaskResult.fail("ae2_network could not read the grid at " + coord + ": "
                    + broken.getClass().getSimpleName() + (broken.getMessage() == null ? "" : " " + broken.getMessage()))
                    .toJson();
        }
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

    /**
     * 这一格自己的节点状态,不涉及整张网:{@code scan_storage} 与 {@code ae2_network} 共用这一份。
     * 拿到就报 {@code on_grid/node_active/node_channels_used/node_channels_max/grid_powered};
     * 没有节点只有 {@code on_grid=false}。
     */
    private static Map<String, Object> nodeStatus(IGridNode node) {
        Map<String, Object> status = new LinkedHashMap<>();
        if (node == null) {
            status.put("on_grid", false);
            return status;
        }
        status.put("on_grid", true);
        status.put("node_active", nodeActive(node));
        status.put("node_channels_used", nodeChannels(node, true));
        status.put("node_channels_max", nodeChannels(node, false));
        status.put("grid_powered", gridPowered(node));
        return status;
    }

    /** 从坐标解析节点再取状态;解析不出来就是 off-grid。给 {@code scan_storage} 用。 */
    private static Map<String, Object> blockNodeStatus(Level level, BlockPos pos) {
        try {
            return nodeStatus(resolveNode(level, pos));
        } catch (Throwable broken) {
            return nodeStatus(null);
        }
    }

    /**
     * {@code scan_storage} 里的一行网格状态:这一格自己的节点在不在网上、活没活、用了几条频道、整张网有没有电。
     * 离线时给一句人话提示。整份取数与 {@code ae2_network} 同一套,不重复实现。
     */
    static String gridStatusLine(Level level, BlockPos pos) {
        Map<String, Object> status = blockNodeStatus(level, pos);
        if (!Boolean.TRUE.equals(status.get("on_grid"))) {
            return "grid: on_grid=no — not connected to an ME network; "
                    + "check cable contact / the side it connects on";
        }
        return "grid: on_grid=yes node_active=" + yesNo(status.get("node_active"))
                + " node_channels=" + status.get("node_channels_used") + "/" + status.get("node_channels_max")
                + " grid_powered=" + yesNo(status.get("grid_powered"));
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
            if (grid == null) {
                return false;
            }
            IEnergyService energy = grid.getEnergyService();
            return energy != null && energy.isNetworkPowered();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String yesNo(Object flag) {
        return Boolean.TRUE.equals(flag) ? "yes" : "no";
    }

    private static String describe(String id, BlockPos pos, IGridNode node, IGrid grid) {
        String coord = pos.getX() + "," + pos.getY() + "," + pos.getZ();
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

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("block", id);
        data.put("x", pos.getX());
        data.put("y", pos.getY());
        data.put("z", pos.getZ());
        // 这一格自己的节点状态与 scan_storage 共用同一套取数(on_grid / node_active / 频道 / grid_powered)。
        data.putAll(nodeStatus(node));
        String controllerName = controller == null ? "UNKNOWN" : controller.name();
        data.put("controller_state", controllerName);
        // CONFLICT 也是有控制器但摆错了,所以"存在"是"不是 NO_CONTROLLER"。
        data.put("controller_present", controller != null && controller != ControllerState.NO_CONTROLLER);
        data.put("controller_blocks", controllerBlocks);
        // AE2 没有公开的"子网"标记;无控制器的 ad-hoc 网络正是子网挂靠的那一类,用它当代理。
        data.put("subnetwork", noController);
        if (mode != null) {
            data.put("channel_mode", mode.name());
        }
        data.put("channels_used", channelsUsed);
        if (noController) {
            data.put("channels_limit", adHocLimit);
        }
        data.put("device_count", grid.size());
        data.put("devices", deviceList(devices));

        IEnergyService energy = grid.getEnergyService();
        if (energy != null) {
            data.put("energy_stored", round(energy.getStoredPower()));
            data.put("energy_max", round(energy.getMaxStoredPower()));
            data.put("energy_avg_usage", round(energy.getAvgPowerUsage()));
            data.put("energy_avg_injection", round(energy.getAvgPowerInjection()));
            data.put("energy_idle", round(energy.getIdlePowerUsage()));
            data.put("energy_powered", energy.isNetworkPowered());
        }

        StringBuilder message = new StringBuilder("AE2 network at ").append(coord).append(" (")
                .append(id).append("): controller ").append(controllerName);
        if (noController) {
            message.append(", ad-hoc — channels ").append(channelsUsed).append('/').append(adHocLimit);
        } else {
            message.append(" — channels ").append(channelsUsed).append(" used (controller supplies them)");
        }
        if (energy != null) {
            message.append("; energy ").append(round(energy.getStoredPower())).append('/')
                    .append(round(energy.getMaxStoredPower())).append(" AE (avg use ")
                    .append(round(energy.getAvgPowerUsage())).append(')');
        }
        message.append("; ").append(grid.size()).append(" device(s)");
        if (!devices.isEmpty()) {
            message.append(": ").append(deviceSummary(devices));
        }
        return TaskResult.ok(message.toString(), data).toJson();
    }

    private static Map<String, Object> noGrid(String id) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("block", id);
        data.put("on_grid", false);
        return data;
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

    /** 设备 id 计数,多的在前,最多 32 条(够看清一张网,不至于把回执撑爆)。 */
    private static List<Map<String, Object>> deviceList(Map<String, Integer> devices) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(devices.entrySet());
        entries.sort((a, b) -> {
            int byCount = Integer.compare(b.getValue(), a.getValue());
            return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
        });
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : entries) {
            if (out.size() >= 32) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", entry.getKey());
            row.put("count", entry.getValue());
            out.add(row);
        }
        return out;
    }

    private static String deviceSummary(Map<String, Integer> devices) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Integer> entry : devices.entrySet()) {
            if (shown++ == 8) {
                sb.append(", …");
                break;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
