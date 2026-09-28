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
              "schema": 1,
              "requires": ["curios"],
              "equipRoutes": [
                { "item": "curios:ring", "container": "curios" }
              ],
              "slotMaps": [
                { "name": "ring", "container": "curios", "item": "curios:ring" }
              ]
            }
            """;

    private static final String TACZ = """
            {
              "id": "tacz",
              "targetMod": "tacz",
              "side": "server",
              "schema": 1,
              "requires": ["tacz_fire"],
              "useRoutes": [
                { "item": "tacz:*", "intent": "tacz_fire" }
              ]
            }
            """;

    private static final String BEYOND_DIMENSIONS = """
            {
              "id": "beyonddimensions",
              "targetMod": "beyonddimensions",
              "side": "server",
              "schema": 1,
              "requires": ["bd_storage"],
              "guis": [
                { "menu": "beyonddimensions:storage", "source": "bd_storage" }
              ]
            }
            """;

    /** 无目标模组(targetMod 空)即"原版"适配:机器契约的参考,不依赖任何模组,照常装载。 */
    private static final String FURNACE = """
            {
              "id": "furnace",
              "targetMod": "",
              "side": "server",
              "schema": 2,
              "machines": [
                {
                  "id": "furnace",
                  "block": "minecraft:furnace",
                  "menu": "minecraft:furnace",
                  "recipeType": "minecraft:smelting",
                  "slots": { "input": [0], "fuel": [1], "output": [2] },
                  "note": "Vanilla furnace: one input, one fuel, one output."
                }
              ]
            }
            """;

    /** AE2 没有独立的插件模块,机器契约随示例铺开。槽位取自真实菜单:四个受限输入 + 一个输出。 */
    private static final String AE2 = """
            {
              "id": "ae2",
              "targetMod": "ae2",
              "side": "server",
              "schema": 2,
              "machines": [
                {
                  "id": "inscriber",
                  "block": "ae2:inscriber",
                  "menu": "ae2:inscriber",
                  "recipeType": "ae2:inscriber",
                  "slots": { "input": [0, 1, 2, 3], "output": [43] },
                  "note": "AE2 inscriber: four restricted input slots, the printed item comes out slot 43."
                }
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
        write(dir.resolve("example-furnace.json"), FURNACE);
        write(dir.resolve("example-ae2.json"), AE2);
    }

    private static void write(Path file, String json) throws IOException {
        if (!Files.exists(file)) {
            Files.writeString(file, json, StandardCharsets.UTF_8);
        }
    }
}
