package com.dwinovo.numen.agent.adapter;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 适配器登记处:从目录读声明式适配文件,提供查询;失败/缺文件一律跳过,不影响其余。
 *
 * <p>它替代了"每个模组一个编译期插件"的固定耦合:适配从目录里来,改一处重读即可
 * ({@link #reload}),不需要重编译、不需要重启。目标模组不在场(加载器的在场判断为假)的
 * 整条跳过,和现有插件的 fail-soft 语义一致。
 *
 * <p>纯 JVM,不碰 Minecraft:世界操作由宿主按这里查出来的"翻译表"去做。
 */
public final class AdapterRegistry {

    private final Map<String, AdapterSpec> active = new LinkedHashMap<>();

    /** 重读目录;所有目标模组都当作在场。 */
    public synchronized ReloadReport reload(Path dir) {
        return reload(dir, mod -> true);
    }

    /**
     * 重读目录。
     *
     * @param modPresent 目标模组在不在场;不在场的适配器记进 {@code skipped},不装
     */
    public synchronized ReloadReport reload(Path dir, Predicate<String> modPresent) {
        long at = System.currentTimeMillis();
        Map<String, AdapterSpec> next = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int loaded = 0;

        for (Path file : jsonFiles(dir, errors)) {
            String name = file.getFileName().toString();
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                AdapterSpec spec = AdapterSpec.fromJson(JsonParser.parseString(json).getAsJsonObject());
                if (!spec.targetMod().isBlank() && modPresent != null && !modPresent.test(spec.targetMod())) {
                    skipped.add(spec.id());
                    continue;
                }
                if (next.putIfAbsent(spec.id(), spec) != null) {
                    errors.add(name + ": 适配器 id 重复 '" + spec.id() + "'");
                    continue;
                }
                loaded++;
            } catch (Exception failure) {
                errors.add(name + ": " + failure.getMessage());
            }
        }

        Map<String, AdapterSpec> previous = new LinkedHashMap<>(active);
        active.clear();
        active.putAll(next);
        return ReloadReport.of(at, loaded, errors.size(), skipped, errors, previous, next);
    }

    private static List<Path> jsonFiles(Path dir, List<String> errors) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (IOException failure) {
            errors.add("读目录失败: " + failure.getMessage());
            return List.of();
        }
    }

    // ---- 读 ----

    public synchronized List<AdapterSpec> active() {
        return List.copyOf(active.values());
    }

    public synchronized AdapterSpec get(String id) {
        return active.get(id);
    }

    /** 这一侧该装的适配器。 */
    public synchronized List<AdapterSpec> on(Side side) {
        return active.values().stream().filter(spec -> spec.side().runsOn(side)).toList();
    }

    public synchronized Optional<AdapterSpec.EquipRoute> equip(String itemId) {
        for (AdapterSpec spec : active.values()) {
            for (AdapterSpec.EquipRoute route : spec.equipRoutes()) {
                if (route.item().matches(itemId)) {
                    return Optional.of(route);
                }
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<AdapterSpec.UseRoute> use(String itemId) {
        for (AdapterSpec spec : active.values()) {
            for (AdapterSpec.UseRoute route : spec.useRoutes()) {
                if (route.item().matches(itemId)) {
                    return Optional.of(route);
                }
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<AdapterSpec.ContainerRoute> container(String blockId) {
        for (AdapterSpec spec : active.values()) {
            for (AdapterSpec.ContainerRoute route : spec.containers()) {
                if (route.block().equals(blockId)) {
                    return Optional.of(route);
                }
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<AdapterSpec.GuiRoute> gui(String menuId) {
        for (AdapterSpec spec : active.values()) {
            for (AdapterSpec.GuiRoute route : spec.guis()) {
                if (route.menu().equals(menuId)) {
                    return Optional.of(route);
                }
            }
        }
        return Optional.empty();
    }

    public synchronized List<AdapterSpec.SlotMap> slots(String containerId) {
        List<AdapterSpec.SlotMap> out = new ArrayList<>();
        for (AdapterSpec spec : active.values()) {
            for (AdapterSpec.SlotMap slot : spec.slotMaps()) {
                if (slot.container().equals(containerId)) {
                    out.add(slot);
                }
            }
        }
        return out;
    }

    public synchronized void clear() {
        active.clear();
    }
}
