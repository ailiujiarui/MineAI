package com.dwinovo.numen.adapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 首次启动时铺进 {@code config/numen/adapters/} 的示例适配文件。
 *
 * <p>它们只演示 schema,不保证对得上真实物品 id;照着改一份最快。目录里已有任何
 * {@code *.json} 就不再铺——用户清空/删文件是明确意图。
 */
final class ExampleAdapters {

    private ExampleAdapters() {}

    private static final String CURIOS = """
            {
              "id": "curios",
              "targetMod": "curios",
              "side": "server",
              "equipRoutes": [
                { "item": "curios:ring", "container": "curios", "slot": "ring" }
              ],
              "slotMaps": [
                { "name": "ring", "container": "curios", "item": "curios:ring", "index": 46 }
              ]
            }
            """;

    private static final String TACZ = """
            {
              "id": "tacz",
              "targetMod": "tacz",
              "side": "both",
              "useRoutes": [
                { "item": "tacz:*", "intent": "tacz_fire" }
              ]
            }
            """;

    private static final String BEYOND_DIMENSIONS = """
            {
              "id": "beyonddimensions",
              "targetMod": "beyonddimensions",
              "side": "both",
              "guis": [
                { "menu": "beyonddimensions:storage", "read": "client", "serverIndex": -1, "source": "bd_storage" }
              ]
            }
            """;

    static void seedIfEmpty(Path dir) throws IOException {
        if (AdapterManager.hasAnyAdapter(dir)) {
            return;
        }
        write(dir.resolve("example-curios.json"), CURIOS);
        write(dir.resolve("example-tacz.json"), TACZ);
        write(dir.resolve("example-beyonddimensions.json"), BEYOND_DIMENSIONS);
    }

    private static void write(Path file, String json) throws IOException {
        if (!Files.exists(file)) {
            Files.writeString(file, json, StandardCharsets.UTF_8);
        }
    }
}
