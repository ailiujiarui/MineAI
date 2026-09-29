package com.dwinovo.numen.core.tools.verify;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 查询工具(原生 NumenTool):拿模型的宣称去量权威状态,给一句确定的判词。
 *
 * <p>四个 kind 对应四种实体宣称:{@code have} 背包里的物品数、{@code block} 某一格的方块、
 * {@code near} 半径内的某种方块、{@code machine} 机器上的某一档配置。业务全在 {@link VerifyOps},
 * 这里只做参数拆包与入口。参数不合规由 Ops 抛 {@link IllegalArgumentException},框架转成参数错。
 */
public final class VerifyTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final VerifyOps impl = new VerifyOps();

    private record Args(String kind, String item, Integer count, String block,
                        Integer x, Integer y, Integer z, Integer radius,
                        String setting, String value, String side, String claim) {}

    @Override
    public String name() {
        return "verify";
    }

    @Override
    public String description() {
        return "Deterministically check whether ONE concrete physical claim is actually true against the "
                + "authoritative server state — the ground truth, not your memory. Call this BEFORE you tell "
                + "the owner a physical goal is done, for the exact claim you are about to make: which item(s) "
                + "and how many are on you (kind=have), that a specific block is at specific coordinates "
                + "(kind=block), that some block is nearby (kind=near), or that a machine is set to a value "
                + "(kind=machine). The reply has verified:true when the claim holds; only then may you say the "
                + "goal is finished. If verified:false, the reply names what is really there (actual) — say "
                + "that instead and keep working; never report success a verify has not confirmed. "
                + "kind=have needs item (optional count, default 1); kind=block needs block + x/y/z; "
                + "kind=near needs block (optional radius, default 16, max 32); kind=machine needs x/y/z + "
                + "setting + value (optional side for an AE2 part mounted on a cable).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .enumStr("kind", "What to check: have = items in your inventory; block = the block at x/y/z; "
                        + "near = any such block within radius of you; machine = a machine's server-side "
                        + "setting equals a value.", "have", "block", "near", "machine")
                .optionalString("item", "kind=have: namespaced item id, e.g. minecraft:diamond or diamond.")
                .optionalInteger("count", "kind=have: how many the claim says you should hold (default 1).",
                        1, 1000000)
                .optionalString("block", "kind=block/near: namespaced block id, e.g. minecraft:stone.")
                .optionalInteger("x", "kind=block/machine: block X.")
                .optionalInteger("y", "kind=block/machine: block Y.")
                .optionalInteger("z", "kind=block/machine: block Z.")
                .optionalInteger("radius", "kind=near: search radius in blocks (default 16, max 32).",
                        1, 32)
                .optionalString("setting", "kind=machine: the config setting name to check, e.g. "
                        + "INSCRIBER_SEPARATE_SIDES.")
                .optionalString("value", "kind=machine: the setting value the claim says is set.")
                .optionalString("side", "kind=machine: the face an AE2 part is on (up/down/north/south/"
                        + "east/west) when the cable hosts several configurable parts.")
                .optionalString("claim", "One-shot free-text claim instead of the structured fields, in the "
                        + "same syntax the long-term goal judge emits: 'have <item> [count]', "
                        + "'block <block> <x> <y> <z>', or 'machine <x> <y> <z> <setting> <value> [side]'.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        if (a.claim() != null && !a.claim().isBlank()) {
            // 判官给的那行原样量:宿主不必自己拆再拼成结构化字段。
            reply.accept(impl.verify(a.claim(), self));
            return;
        }
        reply.accept(impl.verify(new VerifyOps.Claim(a.kind(), a.item(), a.count(), a.block(),
                a.x(), a.y(), a.z(), a.radius(), a.setting(), a.value(), a.side()), self));
    }
}
