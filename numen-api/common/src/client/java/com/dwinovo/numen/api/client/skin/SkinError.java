package com.dwinovo.numen.api.client.skin;

import java.util.List;

/**
 * 皮肤这一块(查正版档案、MineSkin 代签)的失败:原因码 + 现场的原话。产生错误的那一层只说"是哪一种、对方说了什么",
 * 不写给人看的话;界面({@link SkinWords})把码按当前语言翻出来再接上原话。{@link #getMessage()} 是给日志的诊断
 * (码 + 原话,不分语言)。
 *
 * <p>{@code args} 按码约定的顺序:
 * <ul>
 *   <li>{@link #HTTP}:{@code [服务, 状态码, 对方的原话]},原话可为空;</li>
 *   <li>{@link #RATE_LIMITED}、{@link #REPLY_BAD}、{@link #REPLY_MISSING}、{@link #NETWORK}:{@code [服务]};</li>
 *   <li>{@link #NO_SKIN}、{@link #INTERRUPTED}:没有;</li>
 *   <li>{@link #UNKNOWN}:{@code [异常类型, 异常信息]},不是我们认得的错。</li>
 * </ul>
 * 服务名是域名或商标,不翻译。
 */
public final class SkinError extends IllegalStateException {

    public static final String HTTP = "http";
    public static final String RATE_LIMITED = "rate_limited";
    public static final String REPLY_BAD = "reply_bad";
    public static final String REPLY_MISSING = "reply_missing";
    public static final String NETWORK = "network";
    public static final String NO_SKIN = "no_skin";
    public static final String INTERRUPTED = "interrupted";
    public static final String UNKNOWN = "unknown";

    private final String code;
    private final List<String> args;

    private SkinError(String code, List<String> args, Throwable cause) {
        super(code + (args.isEmpty() ? "" : " " + args), cause);
        this.code = code;
        this.args = args;
    }

    public static SkinError of(String code, String... args) {
        return new SkinError(code, norm(args), null);
    }

    /** 带着底下的异常(网络层抛的)一起抛,日志里能看到原因。 */
    public static SkinError of(Throwable cause, String code, String... args) {
        return new SkinError(code, norm(args), cause);
    }

    public static SkinError http(String service, int status, String reply) {
        return of(HTTP, service, String.valueOf(status), reply);
    }

    /** 沿着原因链找我们自己的错;找不到按 {@link #UNKNOWN} 带上类型与信息。 */
    public static SkinError fromError(Throwable error) {
        Throwable cur = error;
        while (cur != null) {
            if (cur instanceof SkinError se) return se;
            if (cur.getCause() == null || cur.getCause() == cur) break;
            cur = cur.getCause();
        }
        return of(UNKNOWN, cur.getClass().getSimpleName(), cur.getMessage());
    }

    public String code() {
        return code;
    }

    /** 第 {@code i} 条原话;没有是空串。 */
    public String arg(int i) {
        return i < args.size() ? args.get(i) : "";
    }

    private static List<String> norm(String[] args) {
        String[] out = new String[args.length];
        for (int i = 0; i < args.length; i++) out[i] = args[i] == null ? "" : args[i];
        return List.of(out);
    }
}
