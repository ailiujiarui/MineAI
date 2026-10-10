package com.dwinovo.numen.agent.http;

/**
 * Non-2xx HTTP response from an LLM endpoint. Carries both the status code
 * (for branching: 401 vs 429 vs 5xx) and the raw response body (for the
 * specific provider error message — DeepSeek 400s in particular contain
 * the actual problem in plain prose).
 *
 * <p>{@link java.io.IOException}-level failures (DNS, connection refused, TLS)
 * are surfaced via the wrapped {@code IOException} on the future, not this class.
 */
public final class LlmHttpException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    public LlmHttpException(int statusCode, String responseBody) {
        super("HTTP " + statusCode + ": " + truncate(responseBody, 500));
        this.statusCode = statusCode;
        this.responseBody = responseBody == null ? "" : responseBody;
    }

    public int statusCode() { return statusCode; }
    public String responseBody() { return responseBody; }

    public boolean isRateLimited()    { return statusCode == 429; }
    public boolean isUnauthorized()   { return statusCode == 401 || statusCode == 403; }
    public boolean isClientError()    { return statusCode >= 400 && statusCode < 500; }
    public boolean isServerError()    { return statusCode >= 500; }

    /**
     * 这一口重发可能就成:限流、请求超时、冲突,以及服务端自己出的错。其余 4xx(400 参数/上下文超限、
     * 401 密钥、404 路径)重发多少次都是同样的答复,重试只会把同一句话问两遍。
     */
    public boolean isTransient() {
        return statusCode == 408 || statusCode == 409 || statusCode == 429 || statusCode >= 500;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "... (truncated)";
    }
}
