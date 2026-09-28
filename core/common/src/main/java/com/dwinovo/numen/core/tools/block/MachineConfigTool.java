package com.dwinovo.numen.core.tools.block;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.core.tools.MachineConfigOps;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Query/action tool (raw NumenTool): read or change a machine's server-side config
 * (mode, per-side input/output, access limits) without opening its GUI.
 *
 * <p>同伴没有客户端屏幕,机器画在 GUI 上的模式/输入输出按钮它看不见也点不动。这里从方块实体把同一份
 * 配置读出来,并且能改——纯服务端、反射实现(见 {@link MachineConfigOps})。
 */
public final class MachineConfigTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final MachineConfigOps impl = new MachineConfigOps();

    private record Args(Integer x, Integer y, Integer z, String side, String setting, String value) {}

    @Override
    public String name() {
        return "machine_config";
    }

    @Override
    public String description() {
        return "Read or change a machine's CONFIG — its mode, each side's input/output, access limits, and "
                + "other settings — directly on the server, WITHOUT opening its GUI. Use this when a machine's "
                + "mode or side configuration is only shown in its GUI: you have no client screen, so those "
                + "client-only buttons are invisible and unclickable, but this tool reaches the same settings on "
                + "the block entity (AE2 and anything built on AE2's config managers; also reports AE power and "
                + "installed upgrades). AE2 import/export/storage buses, interfaces and level emitters are PARTS "
                + "mounted on a face of a cable block: give the CABLE's x/y/z, then `side` (up/down/north/south/"
                + "east/west) to pick the part when several are mounted; a single configurable part is picked "
                + "automatically. Give the integer x/y/z to READ every setting with its current and allowed "
                + "values (parts show which face each setting is on). To CHANGE one, also give `setting` (its "
                + "name) and `value` (one of the allowed values); you get the old and new value back. A block "
                + "with no server-side config fails cleanly. This changes world state and is reported as such.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("x", "Block X.")
                .integer("y", "Block Y.")
                .integer("z", "Block Z.")
                .optionalString("side", "The face a mounted AE2 part is on — up/down/north/south/east/west. "
                        + "Only needed for a cable block hosting several configurable parts; a lone part is "
                        + "picked for you.")
                .optionalString("setting", "Name of the setting to change, e.g. INSCRIBER_SEPARATE_SIDES, "
                        + "IO_DIRECTION, ACCESS, BLOCKING_MODE. Omit to READ every setting and its allowed "
                        + "values.")
                .optionalString("value", "New value for `setting` — one of the allowed values the read shows. "
                        + "Give it together with `setting` to WRITE; omit to read.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        reply.accept(impl.machineConfig(a.x(), a.y(), a.z(), a.side(), a.setting(), a.value(), self));
    }
}
