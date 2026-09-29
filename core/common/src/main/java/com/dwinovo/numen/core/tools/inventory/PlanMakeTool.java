package com.dwinovo.numen.core.tools.inventory;
import com.dwinovo.numen.core.tools.PlanOps;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Query tool (raw NumenTool): work out what it takes to make an item from what she carries. */
public final class PlanMakeTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final PlanOps impl = new PlanOps();

    private record Args(String item_id, Integer count) {}

    @Override
    public String name() {
        return "plan_make";
    }

    @Override
    public String description() {
        return "Plan how to obtain an item from what you already carry — a crafting-tree calculator. "
                + "Returns the raw materials still missing (with counts) and the ordered steps "
                + "(gather / craft, plus which station to have within reach), across crafting, "
                + "smelting / blasting / smoking, stonecutting and smithing. It only plans: nothing is "
                + "gathered or crafted. Use it before a multi-step job instead of guessing; if a recipe "
                + "is ambiguous or cyclic it says so and claims nothing. Then follow the steps with "
                + "work_mine/gather, inv craft, and the station tools (use block + use transfer).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Namespaced id of the target item, e.g. minecraft:wooden_pickaxe.")
                .optionalInteger("count", "How many of the item you want (default 1).", 1, 256)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        reply.accept(impl.plan(a.item_id(), a.count(), self));
    }
}
