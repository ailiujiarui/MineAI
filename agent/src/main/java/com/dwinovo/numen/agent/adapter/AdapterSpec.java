package com.dwinovo.numen.agent.adapter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 一个模组适配器:纯声明式的"翻译表",不含代码。
 *
 * <p>它只回答几类问题——"这件物品该进哪个槽""这个方块/菜单怎么读""这个物品的右键是什么意思"。
 * 目标 mod 在场时由加载器装载,失败/缺字段就当这条规则不存在(fail-soft)。因为全是数据,
 * 改完重读文件即可,不用重编译、不用重启。
 *
 * <p>纯 JVM,不碰 Minecraft。
 *
 * @param id          适配器 id(文件内唯一)
 * @param targetMod   目标模组 id;不在场则整条跳过
 * @param side        跑在哪一侧
 * @param slotMaps    槽位映射(饰品/装备栏)
 * @param equipRoutes equip_item 的路由
 * @param containers  方块容器怎么访问
 * @param guis        菜单(GUI)怎么读
 * @param useRoutes   物品右键(开火/换弹等)的意图
 */
public record AdapterSpec(String id, String targetMod, Side side,
                          List<SlotMap> slotMaps, List<EquipRoute> equipRoutes,
                          List<ContainerRoute> containers, List<GuiRoute> guis,
                          List<UseRoute> useRoutes) {

    public AdapterSpec {
        targetMod = targetMod == null ? "" : targetMod;
        side = side == null ? Side.BOTH : side;
        slotMaps = List.copyOf(slotMaps);
        equipRoutes = List.copyOf(equipRoutes);
        containers = List.copyOf(containers);
        guis = List.copyOf(guis);
        useRoutes = List.copyOf(useRoutes);
    }

    /** 槽位映射:某类物品落在某个容器的第几号槽。 */
    public record SlotMap(String name, String container, ItemSelector item, int index) {
        static SlotMap fromJson(JsonObject o) {
            return new SlotMap(str(o, "name"), str(o, "container"), selector(o, "item"), num(o, "index"));
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("name", name);
            o.addProperty("container", container);
            o.add("item", item.toJson());
            o.addProperty("index", index);
            return o;
        }
    }

    /** equip_item 路由:某类物品该装备到哪个容器的哪个槽。 */
    public record EquipRoute(ItemSelector item, String container, String slot) {
        static EquipRoute fromJson(JsonObject o) {
            return new EquipRoute(selector(o, "item"), str(o, "container"), str(o, "slot"));
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.add("item", item.toJson());
            o.addProperty("container", container);
            o.addProperty("slot", slot);
            return o;
        }
    }

    /** 方块容器怎么访问:{@code access} 是宿主认的处理名(vanilla / curios / bd-storage …)。 */
    public record ContainerRoute(String block, String access) {
        static ContainerRoute fromJson(JsonObject o) {
            return new ContainerRoute(str(o, "block"), str(o, "access"));
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("block", block);
            o.addProperty("access", access);
            return o;
        }
    }

    /** 菜单怎么读:跑在哪一侧、服务端索引、数据来源名。 */
    public record GuiRoute(String menu, Side readSide, int serverIndex, String source) {
        static GuiRoute fromJson(JsonObject o) {
            return new GuiRoute(str(o, "menu"), Side.from(strOr(o, "read", "server")),
                    num(o, "serverIndex"), str(o, "source"));
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("menu", menu);
            o.addProperty("read", readSide.name().toLowerCase());
            o.addProperty("serverIndex", serverIndex);
            o.addProperty("source", source);
            return o;
        }
    }

    /** 物品右键的意图:宿主把 {@code intent} 映射到一个具体动作。 */
    public record UseRoute(ItemSelector item, String intent) {
        static UseRoute fromJson(JsonObject o) {
            return new UseRoute(selector(o, "item"), str(o, "intent"));
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.add("item", item.toJson());
            o.addProperty("intent", intent);
            return o;
        }
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("targetMod", targetMod);
        o.addProperty("side", side.name().toLowerCase());
        addAll(o, "slotMaps", slotMaps, SlotMap::toJson);
        addAll(o, "equipRoutes", equipRoutes, EquipRoute::toJson);
        addAll(o, "containers", containers, ContainerRoute::toJson);
        addAll(o, "guis", guis, GuiRoute::toJson);
        addAll(o, "useRoutes", useRoutes, UseRoute::toJson);
        return o;
    }

    public static AdapterSpec fromJson(JsonObject o) {
        String id = str(o, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("adapter 缺少 id");
        }
        return new AdapterSpec(id, str(o, "targetMod"), Side.from(strOr(o, "side", "both")),
                list(o, "slotMaps", SlotMap::fromJson),
                list(o, "equipRoutes", EquipRoute::fromJson),
                list(o, "containers", ContainerRoute::fromJson),
                list(o, "guis", GuiRoute::fromJson),
                list(o, "useRoutes", UseRoute::fromJson));
    }

    // ---- 小工具 ----

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static String strOr(JsonObject o, String key, String fallback) {
        String v = str(o, key);
        return v.isBlank() ? fallback : v;
    }

    private static int num(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : -1;
    }

    private static ItemSelector selector(JsonObject o, String key) {
        if (!o.has(key)) {
            return ItemSelector.of("");
        }
        JsonElement el = o.get(key);
        if (el.isJsonPrimitive()) {
            return ItemSelector.of(el.getAsString());
        }
        if (el.isJsonObject() && el.getAsJsonObject().has("item")) {
            return ItemSelector.of(el.getAsJsonObject().get("item").getAsString());
        }
        return ItemSelector.of("");
    }

    private static <T> List<T> list(JsonObject o, String key, Function<JsonObject, T> parse) {
        List<T> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray(key)) {
                if (el.isJsonObject()) {
                    out.add(parse.apply(el.getAsJsonObject()));
                }
            }
        }
        return out;
    }

    private static <T> void addAll(JsonObject o, String key, List<T> values, Function<T, JsonObject> toJson) {
        JsonArray arr = new JsonArray();
        for (T value : values) {
            arr.add(toJson.apply(value));
        }
        o.add(key, arr);
    }
}
