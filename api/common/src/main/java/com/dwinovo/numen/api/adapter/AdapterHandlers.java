package com.dwinovo.numen.api.adapter;

import com.dwinovo.numen.api.gear.GearSlot;
import com.dwinovo.numen.api.gear.GearSource;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 适配器的"处理器"登记处——数据声明绑定,代码实现访问。这是落在瘦 jar 里的插件 SPI:
 * 第三方模组 {@code compileOnly} 它、实现一个处理器,再写一份适配文件,就能给整合包补兼容,
 * 不用改本体。
 *
 * <p>四类处理器:{@link GearSource}(装备/饰品)、{@link UseHandler}(物品右键意图)、
 * {@link GuiHandler}(专用菜单读取)、{@link ContainerHandler}(方块容器读取)。
 *
 * <h2>故障隔离,不是吞异常</h2>
 * 处理器由第三方提供,一抛异常不该顺着任务链打穿本体。所以<b>包装发生在登记这一刻</b>
 * (不是四个调用点各包一层):处理器抛 {@link RuntimeException} 或 {@link LinkageError} 时,
 * 记一条日志、把这次路由当作"没生效"返回中性值(装备空集 / 右键 false / 读 null),
 * 并把原因存进 {@link #failures()} 供 {@code numen adapter list} 查询。
 *
 * <p>只兜这两类:模组 API 变更、第三方代码出错都在其中;而 {@link Error}(OOM、栈溢出)
 * 是 JVM 濒死,该往上走,不该被当成"路由失效"。
 */
public final class AdapterHandlers {

    private static final Logger LOG = LoggerFactory.getLogger("numen-adapter");

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
    private static final Map<String, String> FAILURES = new ConcurrentHashMap<>();

    private AdapterHandlers() {}

    public static void registerGear(String name, GearSource source) {
        if (isName(name) && source != null) {
            GEAR.put(name, new GuardedGear(name, source));
        }
    }

    public static GearSource gear(String name) {
        return name == null ? null : GEAR.get(name);
    }

    public static void registerUse(String intent, UseHandler handler) {
        if (isName(intent) && handler != null) {
            USE.put(intent, (body, itemId) -> {
                try {
                    return handler.act(body, itemId);
                } catch (RuntimeException | LinkageError failure) {
                    note(intent, failure);
                    return false;
                }
            });
        }
    }

    public static UseHandler use(String intent) {
        return intent == null ? null : USE.get(intent);
    }

    public static void registerGui(String source, GuiHandler handler) {
        if (isName(source) && handler != null) {
            GUI.put(source, (body, menu, key) -> {
                try {
                    return handler.read(body, menu, key);
                } catch (RuntimeException | LinkageError failure) {
                    note(source, failure);
                    return null;
                }
            });
        }
    }

    public static GuiHandler gui(String source) {
        return source == null ? null : GUI.get(source);
    }

    public static void registerContainer(String access, ContainerHandler handler) {
        if (isName(access) && handler != null) {
            CONTAINER.put(access, (body, pos, key) -> {
                try {
                    return handler.read(body, pos, key);
                } catch (RuntimeException | LinkageError failure) {
                    note(access, failure);
                    return null;
                }
            });
        }
    }

    public static ContainerHandler container(String access) {
        return access == null ? null : CONTAINER.get(access);
    }

    /** 处理器名 → 最近一次故障的一句话;供 {@code numen adapter list} 解释"为什么没生效"。 */
    public static Map<String, String> failures() {
        return Map.copyOf(FAILURES);
    }

    /** 某个名字有没有登记处理器(四类任一)。供适配文件 {@code requires} 判定。 */
    public static boolean has(String name) {
        return name != null && (GEAR.containsKey(name) || USE.containsKey(name)
                || GUI.containsKey(name) || CONTAINER.containsKey(name));
    }

    public static void clear() {
        GEAR.clear();
        USE.clear();
        GUI.clear();
        CONTAINER.clear();
        FAILURES.clear();
    }

    private static boolean isName(String name) {
        return name != null && !name.isBlank();
    }

    private static void note(String name, Throwable failure) {
        FAILURES.put(name, failure.getClass().getSimpleName() + ": " + failure.getMessage());
        LOG.warn("[numen-adapter] handler '{}' failed: {}", name, failure.toString());
    }

    /** 装备来源没有函数式接口,单独包一层。 */
    private record GuardedGear(String name, GearSource delegate) implements GearSource {
        @Override
        public List<GearSlot> slots(NumenPlayer body) {
            try {
                return delegate.slots(body);
            } catch (RuntimeException | LinkageError failure) {
                note(name, failure);
                return List.of();
            }
        }

        @Override
        public Set<String> kindsOf(NumenPlayer body, ItemStack stack) {
            try {
                return delegate.kindsOf(body, stack);
            } catch (RuntimeException | LinkageError failure) {
                note(name, failure);
                return Set.of();
            }
        }
    }
}
