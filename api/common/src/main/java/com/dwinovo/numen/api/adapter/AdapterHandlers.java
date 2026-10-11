package com.dwinovo.numen.api.adapter;

import com.dwinovo.numen.api.gear.GearSlot;
import com.dwinovo.numen.agent.adapter.HandlerKind;
import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.gear.GearSource;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
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
 * (不是四个消费点各包一层):装备处理器抛异常时返回空集,并记一次日志。
 * 右键与读取失败成为调用失败,不回落为一次成功的原版动作或通用读取。
 *
 * <p>对齐 {@code core/plugins/Gate.install} 的 {@code catch(Throwable)}:模组 API 变更、
 * 第三方代码出错都在其中。日志按 {@code joinFragments} 的 {@code FAILING} 语义——<b>首次失败记一条,
 * 之后静默,恢复时再记一条</b>,避免每 tick 刷屏。{@link #failures()} 给 {@code numen adapter list}
 * 回答"哪个处理器此刻是坏的"。
 */
public final class AdapterHandlers {

    private static final Logger LOG = LoggerFactory.getLogger("numen-adapter");

    /** 模型点名的这一声右键;准备只读,不换手、不改身体或世界。 */
    public record UseContext(String itemId, BlockPos aim, int holdTicks, boolean sneak) {
        public UseContext {
            aim = aim == null ? null : aim.immutable();
        }
    }

    /**
     * 一次有界的物品右键意图。先只读声明实际动作与目标,由任务送入权限层;
     * 授权之后才执行一次,只做传入的动作,实际事实随返回值报告(实体受伤、模组内部状态等)。
     * 原版 Action 表达不了的意图必须抛出 ApiError,不得拿别的动作代替。
     */
    public interface UseHandler {
        Action action(NumenPlayer body, UseContext context);

        List<String> act(NumenPlayer body, UseContext context, Action authorized);
    }

    /** 读专用菜单的状态行,随 Window 交回;不返回工具结果 JSON。 */
    @FunctionalInterface
    public interface GuiHandler {
        List<String> read(NumenPlayer body, AbstractContainerMenu menu, String source);
    }

    /** 读方块容器的内容行,作为 Storage 的值交回。 */
    @FunctionalInterface
    public interface ContainerHandler {
        List<String> read(NumenPlayer body, BlockPos pos, String access);
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
            USE.put(intent, new UseHandler() {
                @Override
                public Action action(NumenPlayer body, UseContext context) {
                    try {
                        Action action = handler.action(body, context);
                        requireConcreteAction(action);
                        recovered(intent);
                        return action;
                    } catch (Throwable failure) {
                        failed(intent, failure);
                        throw readFailure(intent, failure);
                    }
                }

                @Override
                public List<String> act(NumenPlayer body, UseContext context, Action authorized) {
                    try {
                        requireConcreteAction(authorized);
                        List<String> facts = List.copyOf(handler.act(body, context, authorized));
                        recovered(intent);
                        return facts;
                    } catch (Throwable failure) {
                        failed(intent, failure);
                        throw readFailure(intent, failure);
                    }
                }
            });
        }
    }

    /** 校验声明的完整性,不做许可裁决;所有合法动作仍必须经任务的 permit。 */
    private static void requireConcreteAction(Action action) {
        boolean concrete = action != null && action.kind() != null && switch (action.kind()) {
            case BREAK, USE_BLOCK -> action.pos() != null && action.state() != null;
            case PLACE, TAKE -> action.pos() != null && action.state() != null && action.item() != null;
            case ATTACK, USE_ENTITY -> action.entity() != null;
            case DROP -> action.item() != null;
            case COMMAND -> action.command() != null && !action.command().line().isBlank()
                    && !action.command().root().isBlank() && action.command().names().contains(action.command().root());
        };
        if (!concrete) {
            throw new ApiError(ErrorKind.FAILED, "adapter use requires a concrete Action and target; nothing executed", null);
        }
    }

    public static UseHandler use(String intent) {
        return intent == null ? null : USE.get(intent);
    }

    public static void registerGui(String source, GuiHandler handler) {
        if (isName(source) && handler != null) {
            GUI.put(source, (body, menu, key) -> {
                try {
                    List<String> read = List.copyOf(handler.read(body, menu, key));
                    recovered(source);
                    return read;
                } catch (Throwable failure) {
                    failed(source, failure);
                    throw readFailure(source, failure);
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
                    List<String> read = List.copyOf(handler.read(body, pos, key));
                    recovered(access);
                    return read;
                } catch (Throwable failure) {
                    failed(access, failure);
                    throw readFailure(access, failure);
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

    public static boolean has(HandlerKind kind, String name) {
        if (name == null) return false;
        return switch (kind) {
            case GEAR -> GEAR.containsKey(name);
            case USE -> USE.containsKey(name);
            case GUI -> GUI.containsKey(name);
            case CONTAINER -> CONTAINER.containsKey(name);
        };
    }

    private static ApiError readFailure(String name, Throwable failure) {
        if (failure instanceof ApiError error) return error;
        return new ApiError(ErrorKind.FAILED, "adapter handler '" + name + "' failed: "
                + failure.getClass().getSimpleName() + ": " + failure.getMessage(), null);
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
