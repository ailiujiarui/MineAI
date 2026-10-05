package com.dwinovo.numen.permission;

import java.util.List;

/**
 * 一次征询的结论。
 *
 * @param decision 主人按的哪个键;主人不在、到点没答复按 {@link Decision#PENDING}(悬而未决,不是拒绝);
 *                 被顶替、任务先结束按 {@link Decision#DENY}
 * @param words    拒绝的理由:主人的附言原话(附言只随拒绝——主人要她换个做法才会说),没有附言时是登记处替
 *                 这次结局说的那句({@link ConsentDesk#OWNER_SAID_NO} 等);允许时,以及发起者已经不等它的撤回
 *                 ({@link ConsentDesk#release}、{@link ConsentDesk#withdraw}),为空串
 */
public record ConsentAnswer(Decision decision, String words) {

    /** 主人的答复,以及问不到主人时登记处替它说的那一种。 */
    public enum Decision {
        /** 允许:只在发起这次征询的任务里有效。 */
        ALLOW_ONCE,
        /** 允许并记住:本任务里放行,并把每一条的 {@link ConsentItem#remember} 写进主人的 allow 表。 */
        ALLOW_REMEMBER,
        /** 拒绝:主人按了拒绝。 */
        DENY,
        /** 悬而未决:主人不在、到点没答复,怎么都问不到同意。不是拒绝。 */
        PENDING
    }

    public ConsentAnswer {
        words = words == null ? "" : words;
    }

    /** 放行(本次或并记住)。{@link Decision#DENY} 与 {@link Decision#PENDING} 都是 false。 */
    public boolean allowed() {
        return decision == Decision.ALLOW_ONCE || decision == Decision.ALLOW_REMEMBER;
    }

    /** 悬而未决:没有拒绝,只是这一次没问到主人。 */
    public boolean pending() {
        return decision == Decision.PENDING;
    }

    /** 拒绝的回执正文:主人的原话,后面带上问的是什么。征询被拒的 {@code REFUSED} 理由只从这里出。 */
    public String refusal(List<ConsentItem> asked) {
        return "refused by the owner: " + words + " (asked: " + ConsentItem.listingText(asked) + ")";
    }

    /** 悬而未决的回执正文:没问到主人,这事没做;原样再问一次不改变世界。 */
    public String withholding(List<ConsentItem> asked) {
        return "the owner could not be reached: " + words + " (asked: " + ConsentItem.listingText(asked) + ")";
    }

    /**
     * 允许之后回执里交代的那一句:主人点了头;选的是"允许并记住"时再交代记下了哪几行规则,往后同类的事不再问。
     */
    public String allowance(List<ConsentItem> asked) {
        StringBuilder sb = new StringBuilder("the owner allowed: ").append(ConsentItem.listingText(asked));
        if (decision == Decision.ALLOW_REMEMBER) {
            sb.append("; and remembered it, so these will not be asked again: ")
                    .append(String.join("; ", ConsentItem.rememberedRows(asked)));
        }
        return sb.toString();
    }
}
