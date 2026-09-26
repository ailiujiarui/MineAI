package com.dwinovo.numen.core.adapter;

import com.dwinovo.numen.adapter.AdapterManager;
import com.dwinovo.numen.agent.adapter.AdapterSpec;
import com.dwinovo.numen.agent.adapter.ReloadReport;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.ServerSource;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * {@code numen adapter}:模组适配器的加载/热重载,以及查看当前生效集合。
 *
 * <p>适配文件是纯数据,改完 {@code reload} 即可,不必重编译、重启。目标模组不在场的适配器
 * 会被跳过(见 {@link AdapterManager#reload()})。
 */
public final class AdapterCommands {

    private AdapterCommands() {}

    /** 经插件那扇门登记这一组。 */
    public static void install(NumenApi numen) {
        numen.registerCommands("adapter", "Hot-reloadable mod adapters: re-read them from disk.",
                AdapterCommands::actions);
    }

    private static void actions(CommandGroup adapter) {
        adapter.server("reload", "Re-read config/numen/adapters/ and swap the active set.",
                AdapterCommands::reload)
                .example("numen adapter reload")
                .note("Instant; no restart or rebuild. A bad file is reported and skipped, never fatal.");
        adapter.server("list", "List the adapters currently active.",
                AdapterCommands::list)
                .example("numen adapter list");
    }

    private static void reload(ServerSource source, CommandArgs args) {
        ReloadReport report = AdapterManager.reload();
        source.reply(report.toJson().toString());
    }

    private static void list(ServerSource source, CommandArgs args) {
        JsonArray adapters = new JsonArray();
        for (AdapterSpec spec : AdapterManager.registry().ordered()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", spec.id());
            entry.addProperty("targetMod", spec.targetMod());
            entry.addProperty("side", spec.side().name().toLowerCase());
            entry.addProperty("priority", spec.priority());
            entry.addProperty("slotMaps", spec.slotMaps().size());
            entry.addProperty("equipRoutes", spec.equipRoutes().size());
            entry.addProperty("containers", spec.containers().size());
            entry.addProperty("guis", spec.guis().size());
            entry.addProperty("useRoutes", spec.useRoutes().size());
            adapters.add(entry);
        }
        ReloadReport last = AdapterManager.lastReport();
        JsonArray skipped = new JsonArray();
        for (ReloadReport.Skipped skip : last.skipped()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", skip.id());
            entry.addProperty("reason", skip.reason());
            skipped.add(entry);
        }
        JsonObject failures = new JsonObject();
        for (var entry : com.dwinovo.numen.api.adapter.AdapterHandlers.failures().entrySet()) {
            failures.addProperty(entry.getKey(), entry.getValue());
        }
        JsonObject root = new JsonObject();
        root.addProperty("dir", AdapterManager.dir().toString());
        root.add("adapters", adapters);
        root.add("skipped", skipped);
        root.add("handlerFailures", failures);
        source.reply(root.toString());
    }
}
