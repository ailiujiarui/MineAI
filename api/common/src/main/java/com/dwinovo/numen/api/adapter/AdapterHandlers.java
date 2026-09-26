package com.dwinovo.numen.api.adapter;

import com.dwinovo.numen.api.gear.GearSlot;
import com.dwinovo.numen.api.gear.GearSource;
import com.dwinovo.numen.entity.NumenPlayer;
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
 * 处理器由第三方提供,一抛异常不该顺着任务链打穿本体。隔离收口在<b>注册这一刻</b>
 * (不是四个消费点各包一层):处理器抛异常时,把这次路由当作"没生效"返回中性值
 * (装备空集 / 右键 false / 读 null),并记一次日志。
 *
 * <p>对齐 {@code core/plugins/Gate.install} 的 {@code catch(Throwable)}:模组 API 变更、
 * 第三方代码出错都在其中。日志按 {@code joinFragments} 的 {@code FAILING} 语义——<b>首次失败记一条,
 * 之后静默,恢复时再记一条</b>,避免每 tick 刷屏。{@link #failures()} 给 {@code numen adapter list}
 * 回答"哪个处理器此刻是坏的"。
 */
public final class AdapterHandlers {

    private static final Logger LOG = LoggerFactory.getLogger("numen-adapter");

    /** 物品右键意图。{@code true} = 已处理,别再走原版。 */
    @FunctionalInterface
    public interface UseHandler {
        boolean act(NumenPlayer body, String itemId);
    }

    /** 读一个专用菜单,返回**完整的工具结果 JSON**(即 {@code TaskResult.*.toJson()} 那样的一整份);null = 回落通用转储。 */
    @FunctionalInterface
    public interface GuiHandler {
        String read(NumenPlayer body, AbstractContainerMenu menu, String source);
    }

    /** 读一个方块容器,返回**完整的工具结果 JSON**;null = 回落。 */
    @FunctionalInterface
    public interface ContainerHandler {
        String read(NumenPlayer body, BlockPos pos, String access);
    }

    private static final Map<String, GearSource> GEAR = new ConcurrentHashMap<>();
    private static final Map<String, UseHandler> USE = new ConcurrentHashMap<>();
    private static final Map<String, GuiHandler> GUI = new ConcurrentHashMap<>();
    private static final Map<String, ContainerHandler> CONTAINER = new ConcurrentHashMap<>();
    /** 当前处于失败态的处理器名 → 最近一次失败的一句话。 */
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
                    boolean handled = handler.act(body, itemId);
                    recovered(intent);
                    return handled;
                } catch (Throwable failure) {
                    failed(intent, failure);
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
                    String read = handler.read(body, menu, key);
                    recovered(source);
                    return read;
                } catch (Throwable failure) {
                    failed(source, failure);
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
                    String read = handler.read(body, pos, key);
                    recovered(access);
                    return read;
                } catch (Throwable failure) {
                    failed(access, failure);
                    return null;
                }
            });
        }
    }

    public static ContainerHandler container(String access) {
        return access == null ? null : CONTAINER.get(access);
    }

    /** 处理器名 → 此刻失败的原因;给 {@code numen adapter list} 解释"哪个处理器坏了"。 */
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

    private static void failed(String name, Throwable failure) {
        // 首次失败记一条,之后静默——同一次坏掉不每 tick 刷屏
        if (FAILURES.putIfAbsent(name, failure.getClass().getSimpleName() + ": " + failure.getMessage()) == null) {
            LOG.warn("[numen-adapter] handler '{}' failed: {}", name, failure.toString());
        }
    }

    private static void recovered(String name) {
        if (FAILURES.remove(name) != null) {
            LOG.info("[numen-adapter] handler '{}' recovered", name);
        }
    }

    /** 装备来源没有函数式接口,单独包一层。 */
    private record GuardedGear(String name, GearSource delegate) implements GearSource {
        @Override
        public List<GearSlot> slots(NumenPlayer body) {
            try {
                List<GearSlot> slots = delegate.slots(body);
                recovered(name);
                return slots;
            } catch (Throwable failure) {
                failed(name, failure);
                return List.of();
            }
        }

        @Override
        public Set<String> kindsOf(NumenPlayer body, ItemStack stack) {
            try {
                Set<String> kinds = delegate.kindsOf(body, stack);
                recovered(name);
                return kinds;
            } catch (Throwable failure) {
                failed(name, failure);
                return Set.of();
            }
        }
    }
}
