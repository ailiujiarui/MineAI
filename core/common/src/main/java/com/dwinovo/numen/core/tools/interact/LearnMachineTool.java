package com.dwinovo.numen.core.tools.interact;

import com.dwinovo.numen.adapter.AdapterManager;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.act.MenuOrigin;
import com.dwinovo.numen.core.adapter.LearnedAdapters;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Query tool (raw NumenTool): remember the machine whose GUI is open right now.
 *
 * <p>开一次机器菜单通常就会自动学下来(见 {@link MenuOrigin});这把工具是显式的第二入口——
 * 模型想确认"这台机器记住了没有"时可以再点一下。学到的东西写进
 * {@code config/numen/adapters/auto-learned.json} 并热重载适配器。
 */
public final class LearnMachineTool implements NumenTool {

    @Override
    public String name() {
        return "learn_machine";
    }

    @Override
    public String description() {
        return "Remember the machine whose GUI is open right now: learn its menu id and the role of "
                + "each slot (input / output / fuel / energy / upgrade / …) into an adapter file, so "
                + "machine_recipe and use gui recognise it later. Machines are usually learned "
                + "automatically when you interact with them (use block); call this to be sure, or to re-check. "
                + "Requires a block GUI to already be open (open one with use block first). No arguments.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        AbstractContainerMenu menu = self.containerMenu;
        if (menu == null || menu == self.inventoryMenu) {
            reply.accept(TaskResult.fail("no machine GUI open — use block the machine first, "
                    + "then call learn_machine.").toJson());
            return;
        }
        if (!(self.level() instanceof ServerLevel level)) {
            reply.accept(TaskResult.fail("the machine's menu is not on a server level.").toJson());
            return;
        }
        String menuId = BuiltInRegistries.MENU.getKey(menu.getType()) == null
                ? "" : BuiltInRegistries.MENU.getKey(menu.getType()).toString();
        BlockPos block = MenuOrigin.block(self);
        if (LearnedAdapters.learn(level, self, block)) {
            reply.accept(TaskResult.ok("learned machine " + menuId
                    + " — its slot roles are now saved in auto-learned.json and the adapters reloaded.").toJson());
            return;
        }
        if (!menuId.isBlank() && AdapterManager.registry().machineForMenu(menuId).isPresent()) {
            reply.accept(TaskResult.ok("already known: " + menuId
                    + " is in the active adapters, nothing to learn.").toJson());
            return;
        }
        reply.accept(TaskResult.fail("couldn't learn " + (menuId.isBlank() ? "this menu" : menuId)
                + " — it may have no machine slots, or the learned-adapters file is unreadable.").toJson());
    }
}
