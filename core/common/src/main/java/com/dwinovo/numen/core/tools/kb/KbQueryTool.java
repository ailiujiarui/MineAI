package com.dwinovo.numen.core.tools.kb;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.kb.KnowledgeBase;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 查询工具(原生 NumenTool):搜整合包<b>自带</b>的说明文字。
 *
 * <p>数据就是包里的书本身——各模组的 GuideME 指南、Patchouli 手册、FTB Quests 任务文本,
 * 服务端离线可读,不需要客户端那个指南按钮,也不联网。回执给命中片段加来源标签
 * ({@code guide:<mod> | <页>}、{@code patchouli:<mod> | <条目>}、{@code quests | <文件>});
 * 查不到就干净地说"没有",绝不硬失败。
 */
public final class KbQueryTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private static final int DEFAULT_LIMIT = 5;

    private record Args(String query, Integer limit) {}

    @Override
    public String name() {
        return "kb_query";
    }

    @Override
    public String description() {
        return "Search this pack's own offline knowledge — every mod's GuideME guide pages, Patchouli "
                + "book text, and the FTB Quests files on this server. Read-only and server-side: no guide "
                + "button on the owner's client, no network. Use it to learn how a mod's machine/block works, "
                + "what a quest asks for, or where the pack's own text mentions something. Each hit comes "
                + "with a source label like [guide:ae2 | items-blocks-machines/wireless_terminals] or "
                + "[quests | chapters/getting_started.snbt]. If nothing in the pack's books or quests mentions "
                + "the query, it says so plainly rather than failing.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("query", "What to look up, e.g. \"wireless terminal\", \"energy acceptor\", or a "
                        + "quest/machine name. Matched case-insensitively against the pack's books and quests.")
                .optionalInteger("limit", "How many snippets to return (default " + DEFAULT_LIMIT
                        + ", max 20).", 1, 20)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        if (a.query() == null || a.query().isBlank()) {
            reply.accept(TaskResult.fail("give me something to look up: the query argument is required.").toJson());
            return;
        }
        int limit = a.limit() == null ? DEFAULT_LIMIT : a.limit();
        List<KnowledgeBase.Hit> hits = KnowledgeBase.get(self.getServer()).search(a.query(), limit);
        if (hits.isEmpty()) {
            reply.accept(TaskResult.ok("This pack's own books and quests have nothing on \""
                    + a.query() + "\".").toJson());
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(hits.size()).append(hits.size() == 1 ? " snippet" : " snippets")
                .append(" from this pack's own books and quests for \"").append(a.query()).append("\":");
        for (KnowledgeBase.Hit hit : hits) {
            sb.append("\n\n[").append(hit.source()).append("]\n").append(hit.text());
        }
        reply.accept(TaskResult.ok(sb.toString()).toJson());
    }
}
