package com.dwinovo.numen.plugins.jei;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.task.TaskResult;

import java.util.Map;

/**
 * 客户端工具 {@code jei_recipe}:问 JEI 一个物品由什么做出来、又被用在什么配方里。
 *
 * <p>它在<b>主人的客户端上</b>执行(重写 {@link #invoke},不往服务端发),因为 JEI 的配方注册表只在客户端。
 * 覆盖的范围比服务端的原版配方本广:装了模组的话,机器配方/自定义合成类别也在这里。
 */
public final class JeiRecipeTool implements NumenTool {

    @Override
    public String name() {
        return "jei_recipe";
    }

    @Override
    public String description() {
        return "Ask JEI (Just Enough Items) the recipe for an item: what makes it, and what it is used in. "
                + "Runs on the owner's client against JEI's own registry, so it also covers mod-added recipes "
                + "and custom machine categories that the server recipe book does not expose. Use it when "
                + "`lookup_recipe` finds nothing or the recipe comes from a mod machine.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Namespaced item id, e.g. mekanismgenerators:wind_generator.")
                .build();
    }

    @Override
    public void invoke(ToolCall call) {
        String itemId;
        try {
            itemId = call.args().get("item_id").getAsString();
        } catch (RuntimeException missing) {
            call.complete(TaskResult.fail("item_id is required").toJson());
            return;
        }
        call.complete(NumenJei.lookup(itemId));
    }
}
