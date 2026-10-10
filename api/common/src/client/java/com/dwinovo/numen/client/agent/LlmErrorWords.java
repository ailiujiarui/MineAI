package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.http.LlmHttpException;
import net.minecraft.network.chat.Component;

/**
 * LLM 失败 → 分类人话的唯一真源。话术纪律:说人话 + 说下一步,不甩堆栈
 * (堆栈的去处是日志,传输层已经记全了)。设置屏的检测按钮与回合失败的
 * HUD/聊天栏播报共用这一张表——同一种错在哪里看到都是同一句话。
 */
import com.dwinovo.numen.data.ModLanguageData;

public final class LlmErrorWords {

    private LlmErrorWords() {}

    public static String classify(Throwable error) {
        LlmHttpException http = unwrapHttp(error);
        if (http != null) {
            if (http.isUnauthorized()) return t(ModLanguageData.Keys.GUI_PROVIDERS_CHECK_UNAUTHORIZED);
            if (http.statusCode() == 404) return t(ModLanguageData.Keys.GUI_PROVIDERS_CHECK_NOT_FOUND);
            if (http.isRateLimited()) return t(ModLanguageData.Keys.GUI_PROVIDERS_CHECK_RATE_LIMITED);
            if (http.statusCode() >= 500) return t(ModLanguageData.Keys.GUI_PROVIDERS_CHECK_SERVER_ERROR);
            return t(ModLanguageData.Keys.GUI_PROVIDERS_CHECK_BAD_REQUEST) + " (HTTP " + http.statusCode() + ")";
        }
        return t(ModLanguageData.Keys.GUI_PROVIDERS_CHECK_NETWORK);
    }

    /**
     * 这次失败重发一次有没有意义:拿不到 HTTP 响应(网络、超时)和一过性状态码值得重试,其余确定性错误不值。
     */
    public static boolean retryable(Throwable error) {
        LlmHttpException http = unwrapHttp(error);
        return http == null || http.isTransient();
    }

    private static LlmHttpException unwrapHttp(Throwable error) {
        Throwable cause = error;
        while (cause != null && !(cause instanceof LlmHttpException) && cause.getCause() != null
                && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause instanceof LlmHttpException http ? http : null;
    }

    private static String t(String key) {
        return Component.translatable(key).getString();
    }
}
