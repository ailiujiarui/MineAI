package com.dwinovo.numen.core.tools.kb;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.core.kb.KnowledgeBase;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;

import java.util.List;
import java.util.Optional;

/**
 * {@code numen.kb}:搜整合包<b>自带</b>的离线说明文字——各模组的 GuideME 指南、Patchouli 手册、FTB Quests 任务文本。
 *
 * <p>数据就是包里的书本身({@link KnowledgeBase}):服务端读得到、不联网、不要主人客户端那块指南按钮。索引一次冻结成只读快照,
 * 查不到是空表而不是失败。与已移除的 {@code kb_query} 工具是同一份能力,只是按上游的 api 组模型重新表达。
 */
public final class KbApi {

    /** 不写 limit 时回几条,和旧工具一致。 */
    private static final int DEFAULT_LIMIT = 5;

    private KbApi() {}

    public static void install(NumenApi numen) {
        numen.api("kb", "Search this pack's own offline books and quests: every mod's GuideME guide pages, "
                + "Patchouli book text and the FTB Quests files on this server.", KbApi.class);
    }

    /** 查什么、要几条。 */
    public record Search(@Doc("What to look up, e.g. \"wireless terminal\", \"energy acceptor\", or a "
            + "quest/machine name. Matched case-insensitively against the pack's books and quests.") String query,
                         @Doc("How many snippets to return (1-20).")
                         @Omitted("use 5") Optional<Integer> limit) {}

    /** 一段命中:来源标签 + 片段文字 + 得分。 */
    @Doc("One snippet from the pack's own text.")
    public record Hit(@Doc("Where it comes from, e.g. guide:ae2 | items-blocks-machines/wireless_terminals, "
            + "patchouli:mod | entry, or quests | chapters/getting_started.snbt.") String source,
                      @Doc("The passage itself.") String text,
                      @Doc("Match score; higher is a better match.") int score) {}

    @Fn("Search this pack's own offline books and quests — every mod's GuideME guide pages, Patchouli book "
            + "text and the FTB Quests files on this server — for a word or phrase. Use it to learn how a mod's "
            + "machine works, what a quest asks for, or where the pack's own text mentions something.")
    @Example("for _, h in ipairs(numen.kb.search(\"energy acceptor\")) do print(h.source, h.text) end")
    @Example("numen.kb.search(\"wireless terminal\", {limit = 3})")
    @Note("Instant and read-only, server-side: it reads the pack's GuideME guides, Patchouli books and FTB "
            + "Quests files, then answers from that snapshot. No owner client and no network are needed.")
    @Note("A query that matches nothing returns an empty table, not an error. The first search builds the index "
            + "(it may take a moment on a big pack); later searches are instant.")
    @SeeAlso("numen.inv.recipes")
    public static List<Hit> search(ServerCall call, Search args) {
        if (args.query() == null || args.query().isBlank()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "give me something to look up: the query is empty", null);
        }
        return KnowledgeBase.get(call.her().getServer())
                .search(args.query(), args.limit().orElse(DEFAULT_LIMIT))
                .stream()
                .map(hit -> new Hit(hit.source(), hit.text(), hit.score()))
                .toList();
    }
}
