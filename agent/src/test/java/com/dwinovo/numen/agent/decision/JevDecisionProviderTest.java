package com.dwinovo.numen.agent.decision;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JevDecisionProviderTest {

    @Test
    void postsStateAndReadsTypedAnswers() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> received = new AtomicReference<>();
        server.createContext("/v1/systemone", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"model\":\"jev-latest\",\"answers\":{\"done\":{\"noul\":0.8,\"confidence\":0.9}}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            DecisionConfig config = new DecisionConfig(true, "http://127.0.0.1:" + server.getAddress().getPort(),
                    "secret", "jev-latest", 0.5D, 10);
            JevDecisionProvider provider = new JevDecisionProvider(config);

            JsonObject questions = new JsonObject();
            questions.add("done", DecisionQuestion.noul("is the objective met?"));

            DecisionResult result = provider.decide("inventory has 3 iron", questions)
                    .get(5, TimeUnit.SECONDS);

            assertEquals(0.8, result.noul("done"), 1e-9);
            assertEquals(0.9, result.confidence("done"), 1e-9);
            assertTrue(received.get().contains("inventory has 3 iron"));
            assertTrue(received.get().contains("is the objective met?"));
        } finally {
            server.stop(0);
        }
    }
}
