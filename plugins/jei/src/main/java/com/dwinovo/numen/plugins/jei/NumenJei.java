package com.dwinovo.numen.plugins.jei;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.task.TaskResult;

/**
 * JEI 联动的门面:注册客户端工具、转交查询。
 *
 * <p><b>为什么不直接把查询写在这里</b>:core 的 {@code Builtin} 会引用本类,而编译 core 时类路径上没有 JEI
 * ——所以本类<b>一个 JEI 类型都不许出现</b>(否则"无法访问 IModPlugin")。带 JEI 类型的查询实现在
 * {@link NumenJeiPlugin}({@code @JeiPlugin},只在装了 JEI 的客户端被 JEI 加载),经 {@link Bridge} 接进来。
 */
public final class NumenJei {

    /** 查询口。实现见 {@link NumenJeiPlugin},这里只有 Java 类型,core 引用本类不会牵出 JEI。 */
    public interface Bridge {
        String lookup(String itemId);
    }

    private static volatile Bridge bridge;

    private NumenJei() {}

    /** 由 Builtin 在确认 JEI 在场后调用:注册客户端工具。 */
    public static void install() {
        NumenPlugins.register(numen -> numen.registerTool(new JeiRecipeTool()));
    }

    /** 由 {@link NumenJeiPlugin} 在 JEI 运行期就绪时接上。 */
    public static void bridge(Bridge implementation) {
        bridge = implementation;
    }

    /** 查一个物品的 JEI 配方。 */
    static String lookup(String itemId) {
        Bridge current = bridge;
        if (current == null) {
            return TaskResult.fail("JEI is not ready on this client yet.").toJson();
        }
        return current.lookup(itemId);
    }
}
