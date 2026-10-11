package com.dwinovo.numen.experiment;

import com.google.gson.*;
import java.nio.file.*;
import java.util.Set;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** 显式请求才安装的补测入口;三个模式各自写终局并停服。 */
@Mod("numen_experiment")
public final class MeasurementExperiment {
    private JsonObject request;
    private Path output;
    private boolean failed;
    private long began;

    public MeasurementExperiment() {
        if (System.getProperty("numen.measurement.request") == null) return;
        if (System.getProperty("numen.experiment.request") != null)
            throw new IllegalArgumentException("paired and supplemental requests are mutually exclusive");
        NeoForge.EVENT_BUS.addListener(this::pre);
        NeoForge.EVENT_BUS.addListener(this::post);
    }

    private void pre(ServerTickEvent.Pre event) { RuntimeMeasurements.pre(); }

    private void post(ServerTickEvent.Post event) {
        RuntimeMeasurements.post();
        if (failed) return;
        try {
            if (request == null) {
                request = JsonParser.parseString(Files.readString(Path.of(System.getProperty("numen.measurement.request"))))
                        .getAsJsonObject();
                output = Path.of(request.get("output").getAsString());
                Files.createDirectories(output);
                began = System.nanoTime();
                Set<String> allowed = Set.of("minecraft", "neoforge", "numen", "numen_api", "numen_experiment");
                for (var mod : net.neoforged.fml.ModList.get().getMods())
                    if (!allowed.contains(mod.getModId())) throw new IllegalStateException("unexpected mod: " + mod.getModId());
            }
            if (System.nanoTime() - began > request.get("deadline_seconds").getAsLong() * 1_000_000_000L)
                throw new IllegalStateException("measurement server watchdog expired");
            switch (request.get("mode").getAsString()) {
                case "runtime" -> RuntimeMeasurements.tick(event.getServer(), request, output);
                case "restart" -> RestartMeasurements.tick(event.getServer(), request, output);
                case "permission" -> PermissionMeasurements.tick(event.getServer(), request, output);
                default -> throw new IllegalArgumentException("unknown measurement mode");
            }
        } catch (Exception error) {
            failed = true;
            if (output != null) {
                JsonObject result = new JsonObject();
                result.addProperty("kind", "harness_error");
                result.addProperty("success", false);
                result.addProperty("exception", error.getClass().getName());
                // 入口不打印配置或异常原文;运行日志由启动器在落盘前脱敏。
                result.addProperty("message", RuntimeMeasurements.clean(error.toString()));
                try { Files.writeString(output.resolve("result.json"), new Gson().toJson(result)); }
                catch (java.io.IOException writeError) { throw new java.io.UncheckedIOException(writeError); }
            }
            event.getServer().halt(false);
        }
    }
}
