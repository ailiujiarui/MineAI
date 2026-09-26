package com.dwinovo.numen.agent.adapter;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 适配器登记处:从目录读声明式适配文件,提供查询;失败/缺文件一律跳过,不影响其余。
 *
 * <p>装载时的六道闸,每道跳过都带原因:
 * <ol>
 *   <li>解析失败 → errors;</li>
 *   <li>{@code enabled:false} → skipped;</li>
 *   <li>{@code schema} 比引擎认得的新 → skipped(否则旧 JSON 会被静默按默认值解析);</li>
 *   <li>不是 {@link Side#SERVER} → skipped(v1 只支持服务端);</li>
 *   <li>{@code targetMod} 不在场 → skipped;</li>
 *   <li>{@code requires} 里有没登记的处理器 → skipped;</li>
 *   <li>id 重复 → errors。</li>
 * </ol>
 *
 * <p>查询按 {@code priority} 降序、同分按 id 字典序——不再由文件顺序决定胜负。
 *
 * <p><b>生效点</b>:reload 只换"下一次查询"看到的那张表,<b>不回溯</b>正在跑的任务——
 * 已经派出去、正在按旧表走的动作不会中途改道。运行中任务要换规则,得先停下再重派。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class AdapterRegistry {

    private final Map<String, AdapterSpec> active = new LinkedHashMap<>();

    public synchronized ReloadReport reload(Path dir) {
        return reload(dir, mod -> true, handler -> true);
    }

    public synchronized ReloadReport reload(Path dir, Predicate<String> modPresent) {
        return reload(dir, modPresent, handler -> true);
    }

    /**
     * 重读目录。
     *
     * @param modPresent     目标模组在不在场
     * @param handlerPresent 某个处理器名登记了没有(供 {@code requires} 判定)
     */
    public synchronized ReloadReport reload(Path dir, Predicate<String> modPresent,
                                            Predicate<String> handlerPresent) {
        long at = System.currentTimeMillis();
        Map<String, AdapterSpec> next = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        List<ReloadReport.Skipped> skipped = new ArrayList<>();
        int loaded = 0;

        for (Path file : jsonFiles(dir, errors)) {
            String name = file.getFileName().toString();
            AdapterSpec spec;
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                spec = AdapterSpec.fromJson(JsonParser.parseString(json).getAsJsonObject());
            } catch (Exception failure) {
                errors.add(name + ": " + failure.getMessage());
                continue;
            }
            String blocked = skipReason(spec, modPresent, handlerPresent);
            if (blocked != null) {
                skipped.add(new ReloadReport.Skipped(spec.id(), blocked));
                continue;
            }
            if (next.putIfAbsent(spec.id(), spec) != null) {
                errors.add(name + ": 适配器 id 重复 '" + spec.id() + "'");
                continue;
            }
            loaded++;
        }

        Map<String, AdapterSpec> previous = new LinkedHashMap<>(active);
        active.clear();
        active.putAll(next);
        return ReloadReport.of(at, loaded, errors.size(), skipped, errors, previous, next);
    }

    private static String skipReason(AdapterSpec spec, Predicate<String> modPresent,
                                     Predicate<String> handlerPresent) {
        if (!spec.enabled()) {
            return "disabled";
        }
        if (spec.schema() > AdapterSpec.CURRENT_SCHEMA) {
            return "schema " + spec.schema() + " is newer than " + AdapterSpec.CURRENT_SCHEMA;
        }
        if (spec.side() != Side.SERVER) {
            return "side " + spec.side().name().toLowerCase() + " not supported yet (server only)";
        }
        if (!spec.targetMod().isBlank() && modPresent != null && !modPresent.test(spec.targetMod())) {
            return "mod '" + spec.targetMod() + "' not loaded";
        }
        for (String required : spec.requires()) {
            if (handlerPresent == null || !handlerPresent.test(required)) {
                return "missing handler '" + required + "'";
            }
        }
        return null;
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

    /** 生效集合,按优先顺序(priority 降序、id 升序)。 */
    public synchronized List<AdapterSpec> ordered() {
        return active.values().stream()
                .sorted(Comparator.comparingInt(AdapterSpec::priority).reversed()
                        .thenComparing(AdapterSpec::id))
                .toList();
    }

    public synchronized AdapterSpec get(String id) {
        return active.get(id);
    }

    public synchronized List<AdapterSpec> on(Side side) {
        return ordered().stream().filter(spec -> spec.side().runsOn(side)).toList();
    }

    public synchronized Optional<AdapterSpec.EquipRoute> equip(String itemId) {
        for (AdapterSpec spec : ordered()) {
            for (AdapterSpec.EquipRoute route : spec.equipRoutes()) {
                if (route.item().matches(itemId)) {
                    return Optional.of(route);
                }
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<AdapterSpec.UseRoute> use(String itemId) {
        for (AdapterSpec spec : ordered()) {
            for (AdapterSpec.UseRoute route : spec.useRoutes()) {
                if (route.item().matches(itemId)) {
                    return Optional.of(route);
                }
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<AdapterSpec.ContainerRoute> container(String blockId) {
        for (AdapterSpec spec : ordered()) {
            for (AdapterSpec.ContainerRoute route : spec.containers()) {
                if (route.block().equals(blockId)) {
                    return Optional.of(route);
                }
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<AdapterSpec.GuiRoute> gui(String menuId) {
        for (AdapterSpec spec : ordered()) {
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
        for (AdapterSpec spec : ordered()) {
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
