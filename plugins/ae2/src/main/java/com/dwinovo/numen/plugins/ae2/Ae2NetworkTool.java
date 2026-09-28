package com.dwinovo.numen.plugins.ae2;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Query tool(裸 {@link NumenTool}):报出某一格所在的 AE2 ME 网络的整体状态。
 *
 * <p>同伴没有客户端屏幕,AE2 的频道仪表、控制器状态、电量条都看不见。这里从服务端把同一份网络状态读出来:
 * 用了几条频道、有没有控制器、存了多少 AE、网上有多少设备,供它判断"能不能再加一台机器"。
 * 实现见 {@link Ae2NetworkOps};由 {@link Ae2Adapter#install} 经插件那扇门登记。
 */
public final class Ae2NetworkTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final Ae2NetworkOps impl = new Ae2NetworkOps();

    private record Args(Integer x, Integer y, Integer z) {}

    @Override
    public String name() {
        return "ae2_network";
    }

    @Override
    public String description() {
        return "Inspect the AE2 ME network a cable or device is on — the server-side view of what the smart "
                + "cable / network tool would show. Give the integer x/y/z of ANY cable-connected block (a "
                + "cable, or a machine/part that is on a grid). It reports: channels used vs the ad-hoc limit, "
                + "whether a controller is present and its state (NO_CONTROLLER / CONTROLLER_ONLINE / "
                + "CONTROLLER_CONFLICT), stored and maximum AE energy plus average usage and whether the network "
                + "is powered, how many devices are on the grid and their ids, and whether the network is "
                + "ad-hoc (a controllerless network — what a subnetwork hangs off as). Reports cleanly when the "
                + "block is not on any AE2 grid.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("x", "Block X of any AE2 cable-connected block.")
                .integer("y", "Block Y.")
                .integer("z", "Block Z.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        reply.accept(impl.inspect(a.x(), a.y(), a.z(), self));
    }
}
