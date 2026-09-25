package com.dwinovo.numen.adapter;

import com.dwinovo.numen.api.gear.GearSource;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 适配器的"处理器"登记处——数据声明绑定,代码实现访问。
 *
 * <p>适配文件只能声明"这类物品归哪个容器管""这个菜单用哪个来源读",真正读世界、调模组 API
 * 的那一下必须有代码。数据里写的是<b>处理器名</b>,这里把名字映射到实现。于是社区/插件
 * 只要实现一个处理器、再写一份适配文件,就能给整合包补兼容,而不用改本体。
 *
 * <p>四类处理器:
 * <ul>
 *   <li>{@link GearSource}(装备/饰品,复用现有穿戴来源接口);</li>
 *   <li>{@link UseHandler}(物品右键的意图,如 TaCZ 开火);</li>
 *   <li>{@link GuiHandler}(专用菜单的读取,如 BD 客户端渲染的数据);</li>
 *   <li>{@link ContainerHandler}(方块容器的读写)。</li>
 * </ul>
 *
 * <p>处理器缺失时对应规则等于没生效(fail-soft);任何一处抛异常都不该拖垮调用方。
 */
public final class AdapterHandlers {

    /** 物品右键意图。{@code true} = 已处理,别再走原版。 */
    @FunctionalInterface
    public interface UseHandler {
        boolean act(NumenPlayer body, String itemId);
    }

    /** 读一个专用菜单,返回它自己的现场 JSON(直接交给模型)。 */
    @FunctionalInterface
    public interface GuiHandler {
        JsonObject read(NumenPlayer body, AbstractContainerMenu menu, String source);
    }

    /** 读一个方块容器的内容。 */
    @FunctionalInterface
    public interface ContainerHandler {
        JsonObject read(NumenPlayer body, BlockPos pos, String access);
    }

    private static final Map<String, GearSource> GEAR = new ConcurrentHashMap<>();
    private static final Map<String, UseHandler> USE = new ConcurrentHashMap<>();
    private static final Map<String, GuiHandler> GUI = new ConcurrentHashMap<>();
    private static final Map<String, ContainerHandler> CONTAINER = new ConcurrentHashMap<>();

    private AdapterHandlers() {}

    public static void registerGear(String name, GearSource source) {
        if (name != null && !name.isBlank() && source != null) {
            GEAR.put(name, source);
        }
    }

    public static GearSource gear(String name) {
        return name == null ? null : GEAR.get(name);
    }

    public static void registerUse(String intent, UseHandler handler) {
        if (intent != null && !intent.isBlank() && handler != null) {
            USE.put(intent, handler);
        }
    }

    public static UseHandler use(String intent) {
        return intent == null ? null : USE.get(intent);
    }

    public static void registerGui(String source, GuiHandler handler) {
        if (source != null && !source.isBlank() && handler != null) {
            GUI.put(source, handler);
        }
    }

    public static GuiHandler gui(String source) {
        return source == null ? null : GUI.get(source);
    }

    public static void registerContainer(String access, ContainerHandler handler) {
        if (access != null && !access.isBlank() && handler != null) {
            CONTAINER.put(access, handler);
        }
    }

    public static ContainerHandler container(String access) {
        return access == null ? null : CONTAINER.get(access);
    }

    public static void clear() {
        GEAR.clear();
        USE.clear();
        GUI.clear();
        CONTAINER.clear();
    }
}
