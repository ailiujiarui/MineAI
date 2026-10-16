package com.dwinovo.numen.api.client.skin;

import net.minecraft.client.resources.language.I18n;

/**
 * 皮肤失败({@link SkinError})翻成当前语言的话,界面里说"皮肤为什么没成"的唯一一处:召唤与编辑时借正版皮肤、
 * 设置里 MineSkin 代签都用它。文案只在语言文件里({@code numen.skin.error.<码>}),这里只按码挑键、把原话填进去;
 * 对方有原话的接在后面({@code numen.skin.error.detail})。
 */
public final class SkinWords {

    private SkinWords() {}

    public static String text(Throwable error) {
        SkinError e = SkinError.fromError(error);
        String base = I18n.get("numen.skin.error." + e.code(), e.arg(0), e.arg(1));
        String reply = e.code().equals(SkinError.HTTP) ? e.arg(2) : "";
        return reply.isBlank() ? base : I18n.get("numen.skin.error.detail", base, reply);
    }
}
