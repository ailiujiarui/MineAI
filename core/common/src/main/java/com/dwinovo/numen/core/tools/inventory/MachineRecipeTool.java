package com.dwinovo.numen.core.tools.inventory;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.core.tools.MachineRecipeOps;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Query tool (raw NumenTool): which declared machine makes an item (or consumes it), and with what exact inputs. */
public final class MachineRecipeTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final MachineRecipeOps impl = new MachineRecipeOps();

    private record Args(String item_id, Integer count, String role) {}

    @Override
    public String name() {
        return "machine_recipe";
    }

    @Override
    public String description() {
        return "Find which declared machine makes an item — the server-side counterpart to jei_recipe. "
                + "Machine recipes only exist where a mod adapter declares them (recipe type + input/output "
                + "menu slots), so this is authoritative for those machines: it reports the machine's "
                + "block/menu, the declared slot roles (input/output/fuel/energy), and the exact inputs and "
                + "output. A machine whose adapter left recipeType blank still works: it searches every "
                + "registered recipe type. Set role=input to go the other way — which machines consume an "
                + "item — which surfaces chemical-output machines (\"eats X → yields gas Y\"). If it says no "
                + "registered machine makes the item, fall back to lookup_recipe (vanilla crafting/smelting) "
                + "or jei_recipe (client-side JEI, covers more mod recipes).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Namespaced item id, e.g. minecraft:iron_ingot. By default the OUTPUT you "
                        + "want made; with role=input it is the INPUT you have and want to see consumed.")
                .optionalInteger("count", "How many you want; reports how many craft runs that takes.", 1, 1000000)
                .optionalEnum("role", "output (default) = machines that make item_id; input = machines that "
                        + "consume item_id.", "output", "input")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        int count = a.count() == null ? 1 : a.count();
        String role = a.role() == null ? "output" : a.role();
        reply.accept(impl.machineRecipe(a.item_id(), count, role, self));
    }
}
