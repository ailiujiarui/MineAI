package com.dwinovo.numen.agent.decision;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * TypeSafe JEV 判断层客户端——照搬 MineAI 的 {@code TypesafeDecisionProvider}。
 *
 * <p>{@code POST {base}/v1/systemone},body 是 {@code {model, state, questions}},回
 * {@code {model, answers}}。用 JDK 自带 {@link HttpClient},不引第三方 HTTP 库。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class JevDecisionProvider implements DecisionPort {

    private final DecisionConfig config;
    private final HttpClient client;

    public JevDecisionProvider(DecisionConfig config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(30, config.timeoutSeconds())))
                .build());
    }

    /** 测试可以塞一个假的 {@link HttpClient}。 */
    public JevDecisionProvider(DecisionConfig config, HttpClient client) {
        this.config = config;
        this.client = client;
    }

    @Override
    public String name() {
        return "jev";
    }

    @Override
    public CompletableFuture<DecisionResult> decide(String state, JsonObject questions) {
        if (!config.hasKey()) {
            return CompletableFuture.failedFuture(new IllegalStateException("jev: 没有 api key"));
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", config.model());
        body.addProperty("state", state);
        body.add("questions", questions);

        String endpoint = config.baseUrl().replaceAll("/+$", "") + "/v1/systemone";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(this::parse);
    }

    private DecisionResult parse(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("jev HTTP " + status + ": " + truncate(response.body()));
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        String model = root.has("model") ? root.get("model").getAsString() : config.model();
        JsonObject answers = root.has("answers") && root.get("answers").isJsonObject()
                ? root.getAsJsonObject("answers")
                : new JsonObject();
        return new DecisionResult(answers, model);
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 300 ? value.substring(0, 300) + "..." : value;
    }
}
