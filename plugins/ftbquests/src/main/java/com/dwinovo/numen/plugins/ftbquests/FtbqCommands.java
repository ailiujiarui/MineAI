package com.dwinovo.numen.plugins.ftbquests;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.NumenCli;
import com.dwinovo.numen.cli.Param;

/**
 * {@code numen ftbquests}:她自己点不了的任务书与组队按钮,在这里有一个入口。
 *
 * <p>读任务书({@code list}、{@code show})在主人的客户端上执行,见 {@link ClientBook};提交任务({@code submit})、
 * 领奖({@code claim})与接受邀请({@code join})在服务端执行,动的是她的背包与队伍。五个都是长尾,不提升为快捷工具——
 * 装了 FTB 的世界里也不是每几轮就用一次,常驻工具表不值。
 */
final class FtbqCommands {

    static final String GROUP = "ftbquests";
    static final String LIST = NumenCli.ROOT + " " + GROUP + " list";
    static final String SHOW = NumenCli.ROOT + " " + GROUP + " show";
    static final String SUBMIT = NumenCli.ROOT + " " + GROUP + " submit";
    static final String CLAIM = NumenCli.ROOT + " " + GROUP + " claim";

    /** 客户端按主人的语言认标题,所以 show 编号、标题都收;标题可以带空格,吃掉余下整行。 */
    private static final Param<String> QUEST_NAMED = Param.required("quest", ArgType.text(),
            "Which quest.")
            .values("its id, or its full title as " + LIST + " prints it");
    /**
     * submit 只收编号:它在服务端执行,服务端的任务书是回退语言,主人语言里的标题在那边对不上。
     * 编号是 FTB 的对象编号,两侧一样。
     */
    static final Param<String> QUEST_ID = Param.required("quest", ArgType.word(),
            "The quest's id, as list and show print it.");
    /**
     * claim 在服务端领奖,标题只能按服务端那本任务书解析(整合包的默认语言),所以编号优先;带空格的标题写成
     * {@code --quest "Getting Started"}。不写就领此刻能领的全部。
     */
    static final Param<String> CLAIM_QUEST = Param.optional("quest", ArgType.string(),
            "Which quest's rewards to claim.")
            .values("its id, or its title as the server reads it (the pack's default language); quote a title with spaces")
            .whenOmitted("claim every reward of yours that is ready, in any completed quest");

    /** 短名是 FTB Teams 给队伍起的写法(显示名里的非字母数字换成下划线,再接 {@code #} 与编号前八位)。 */
    static final Param<String> TEAM = Param.optional("team", ArgType.string(),
            "Which party's invitation to accept.")
            .values("the party's short name, as the team_invite event gives it, e.g. Dwin_Party#1a2b3c4d")
            .whenOmitted("accept your only pending invitation; with several pending, name one");

    private FtbqCommands() {}

    static void install(NumenApi numen) {
        numen.registerCommands(GROUP,
                "FTB Quests: your owner's quest book, handing in quests, claiming rewards, accepting a party invitation.",
                FtbqCommands::actions);
    }

    private static void actions(CommandGroup quests) {
        quests.client("list", "The quests you can work on now, what each still needs and who can do it.",
                ClientBook::list, Listing.PAGE)
                .example(LIST)
                .example(LIST + " --page 2")
                .note("Reads your owner's book: their team's progress, in their language. It changes nothing.")
                .note("The first line says whether you are in that team; if not, what you do does not count "
                        + "for this book.")
                .seeAlso(SHOW, SUBMIT, CLAIM);
        quests.client("show", "One quest in full: description, dependencies, tasks, rewards.",
                (src, args) -> ClientBook.show(src, args.get(QUEST_NAMED)), QUEST_NAMED)
                .example(SHOW + " 15CDF6A098B95FDA")
                .example(SHOW + " Getting Started")
                .seeAlso(SUBMIT, CLAIM);
        quests.server("submit", "Hand in a quest's items, experience or checkmarks from your own inventory.",
                QuestSubmit::submit, QUEST_ID)
                .example(SUBMIT + " 15CDF6A098B95FDA")
                .note("Takes the items from YOUR inventory and they do not come back; FTB decides what counts. "
                        + "It does not ask your owner, so hand in only when they want you to.")
                .note("Observation tasks are not supported. Completion and rewards arrive as quest_completed "
                        + "and quest_reward_auto events.")
                .seeAlso(LIST, SHOW);
        quests.server("claim", "Take the quest rewards that are waiting for you or your team.",
                QuestClaim::claim, CLAIM_QUEST)
                .example(CLAIM)
                .example(CLAIM + " --quest 15CDF6A098B95FDA")
                .example(CLAIM + " --quest \"Getting Started\"")
                .note("Some packs mark rewards to be claimed by hand in the book (\"" + SHOW + "\" says "
                        + "\"claimed by hand in the book\"). FTB will not hand those out on its own and you cannot "
                        + "click the book, so this takes them exactly as the book's claim button would, into YOUR "
                        + "inventory.")
                .note("Without --quest it takes every reward of yours that is ready; team rewards belong to the "
                        + "whole team and FTB gives each one to whoever claims it first, so claim it only when your "
                        + "owner is fine with you taking it.")
                .seeAlso(LIST, SHOW, SUBMIT);
        quests.server("join", "Accept a party invitation you have pending.", PartyJoin::join, TEAM)
                .example(NumenCli.ROOT + " " + GROUP + " join")
                .example(NumenCli.ROOT + " " + GROUP + " join --team Dwin_Party#1a2b3c4d")
                .note("It does not ask your owner: join only when they agree. You cannot join while you are "
                        + "in another party.")
                .note("Your quest progress merges into the party's; from then on what you do counts for it.")
                .seeAlso(LIST);
    }
}
