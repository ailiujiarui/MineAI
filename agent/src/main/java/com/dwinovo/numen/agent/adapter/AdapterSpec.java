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
 * 目标 mod 在场、schema 认得、依赖的处理器都在,才装载;否则记一条<b>带原因</b>的 skipped,
 * 不影响其余(fail-soft)。因为全是数据,改完重读文件即可。
 *
 * <p>纯 JVM,不碰 Minecraft。
 *
 * @param id          适配器 id(文件内唯一)
 * @param targetMod   目标模组 id;不在场则整条跳过
 * @param side        跑在哪一侧;v1 只支持 {@link Side#SERVER}
 * @param schema      声明格式版本;比引擎认得的新就跳过(否则旧 JSON 会被静默按默认值解析)
 * @param enabled     关掉就跳过,不用删文件
 * @param priority    多条规则命中同一物品时,分高者胜;同分按 id 字典序(确定性)
 * @param requires    依赖的处理器名;缺一个就跳过并写明
 * @param slotMaps    槽位映射(饰品/装备栏)
 * @param equipRoutes equip_item 的路由
 * @param containers  方块容器怎么访问
 * @param guis        菜单(GUI)怎么读
 * @param useRoutes   物品右键(开火/换弹等)的意图
 */
public record AdapterSpec(String id, String targetMod, Side side, int schema, boolean enabled, int priority,
                          List<String> requires,
                          List<SlotMap> slotMaps, List<EquipRoute> equipRoutes,
                          List<ContainerRoute> containers, List<GuiRoute> guis,
                          List<UseRoute> useRoutes) {

    /** 引擎当前认得的声明格式版本。 */
    public static final int CURRENT_SCHEMA = 1;

    public AdapterSpec {
        targetMod = targetMod == null ? "" : targetMod;
        side = side == null ? Side.SERVER : side;
        requires = List.copyOf(requires);
        slotMaps = List.copyOf(slotMaps);
        equipRoutes = List.copyOf(equipRoutes);
        containers = List.copyOf(containers);
        guis = List.copyOf(guis);
        useRoutes = List.copyOf(useRoutes);
    }

    /** 槽位映射:某类物品归某个容器管。第几号槽由处理器决定,不写死在数据里。 */
    public record SlotMap(String name, String container, ItemSelector item) {
        static SlotMap fromJson(JsonObject o) {
            return new SlotMap(str(o, "name"), str(o, "container"), selector(o, "item"));
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("name", name);
            o.addProperty("container", container);
            o.add("item", item.toJson());
            return o;
        }
    }

    /** equip_item 路由:某类物品归哪个容器管。具体槽位由处理器决定。 */
    public record EquipRoute(ItemSelector item, String container) {
        static EquipRoute fromJson(JsonObject o) {
            return new EquipRoute(selector(o, "item"), str(o, "container"));
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.add("item", item.toJson());
            o.addProperty("container", container);
            return o;
        }
    }

    /** 方块容器怎么访问:{@code access} 是宿主认的处理器名(vanilla / curios / bd-storage …)。 */
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

    /** 菜单怎么读:菜单 id → 处理器名。v1 只走服务端侧。 */
    public record GuiRoute(String menu, String source) {
        static GuiRoute fromJson(JsonObject o) {
            return new GuiRoute(str(o, "menu"), str(o, "source"));
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("menu", menu);
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
        o.addProperty("schema", schema);
        o.addProperty("enabled", enabled);
        o.addProperty("priority", priority);
        JsonArray req = new JsonArray();
        requires.forEach(req::add);
        o.add("requires", req);
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
        return new AdapterSpec(id, str(o, "targetMod"), Side.from(strOr(o, "side", "server")),
                numOr(o, "schema", CURRENT_SCHEMA), boolOr(o, "enabled", true), numOr(o, "priority", 0),
                strings(o, "requires"),
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

    private static int numOr(JsonObject o, String key, int fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fallback;
    }

    private static boolean boolOr(JsonObject o, String key, boolean fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : fallback;
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray(key)) {
                if (el.isJsonPrimitive()) {
                    out.add(el.getAsString());
                }
            }
        }
        return out;
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
