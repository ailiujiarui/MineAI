package com.dwinovo.numen.agent.decision;

/**
 * 判断层配置。判不出来、端点不可用、没配 key,都该退回兜底而不是阻塞。
 *
 * <p>纯 JVM,不碰 Minecraft。
 *
 * @param enabled       总开关
 * @param baseUrl       端点根,拼 {@code /v1/systemone}
 * @param apiKey        密钥
 * @param model         模型名
 * @param minConfidence 低于这个置信度就别信判断
 * @param timeoutSeconds 超时
 */
public record DecisionConfig(boolean enabled, String baseUrl, String apiKey, String model,
                             double minConfidence, int timeoutSeconds) {

    public static final String DEFAULT_BASE = "https://api.typesafe.ai";
    public static final String DEFAULT_MODEL = "jev-latest";
    public static final String ENV_API_KEY = "NUMEN_DECISION_API_KEY";

    public DecisionConfig {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE : baseUrl;
        model = model == null || model.isBlank() ? DEFAULT_MODEL : model;
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 45;
        }
        minConfidence = Math.max(0.0D, Math.min(1.0D, minConfidence));
    }

    public static DecisionConfig disabled() {
        return new DecisionConfig(false, DEFAULT_BASE, "", DEFAULT_MODEL, 0.5D, 45);
    }

    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean usable() {
        return enabled && hasKey();
    }

    /**
     * 从系统属性读:{@code <prefix>.enabled/.base_url/.api_key/.model/.min_confidence/.timeout}。
     * api_key 先看属性,再看环境变量 {@value #ENV_API_KEY}。
     */
    public static DecisionConfig fromSystemProperties(String prefix) {
        String p = prefix == null || prefix.isBlank() ? "numen.decision" : prefix;
        boolean enabled = Boolean.parseBoolean(System.getProperty(p + ".enabled", "false"));
        String base = System.getProperty(p + ".base_url", DEFAULT_BASE);
        String key = System.getProperty(p + ".api_key", "");
        if (key.isBlank()) {
            key = System.getenv(ENV_API_KEY);
        }
        String model = System.getProperty(p + ".model", DEFAULT_MODEL);
        double minConfidence = readDouble(System.getProperty(p + ".min_confidence"), 0.5D);
        int timeout = readInt(System.getProperty(p + ".timeout"), 45);
        return new DecisionConfig(enabled, base, key == null ? "" : key, model, minConfidence, timeout);
    }

    /**
     * 从 JSON 文件读(给客户端用)。兼容两种形状:顶层直接是字段,或像 MineAI 那样包在
     * {@code "decision"} 对象里。读不到/解析失败一律回 {@link #disabled()},不抛。
     */
    public static DecisionConfig fromFile(java.nio.file.Path file) {
        if (file == null || !java.nio.file.Files.isRegularFile(file)) {
            return disabled();
        }
        try {
            String json = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            if (o.has("decision") && o.get("decision").isJsonObject()) {
                o = o.getAsJsonObject("decision");
            }
            boolean enabled = o.has("enabled") && o.get("enabled").isJsonPrimitive() && o.get("enabled").getAsBoolean();
            String base = string(o, "base_url", DEFAULT_BASE);
            String key = string(o, "api_key", "");
            if (key.isBlank()) {
                String env = System.getenv(ENV_API_KEY);
                key = env == null ? "" : env;
            }
            String model = string(o, "model", DEFAULT_MODEL);
            double minConfidence = o.has("min_confidence") && o.get("min_confidence").isJsonPrimitive()
                    ? o.get("min_confidence").getAsDouble() : 0.5D;
            int timeout = o.has("timeout") && o.get("timeout").isJsonPrimitive()
                    ? o.get("timeout").getAsInt() : 45;
            return new DecisionConfig(enabled, base, key, model, minConfidence, timeout);
        } catch (Exception notUsable) {
            return disabled();
        }
    }

    private static String string(com.google.gson.JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }

    private static double readDouble(String value, double fallback) {
        try {
            return value == null ? fallback : Double.parseDouble(value.strip());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private static int readInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.strip());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }
}
