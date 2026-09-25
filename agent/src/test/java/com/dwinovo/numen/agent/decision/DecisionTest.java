package com.dwinovo.numen.agent.decision;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionTest {

    private static DecisionResult answers(String json) {
        return new DecisionResult(JsonParser.parseString(json).getAsJsonObject(), "jev");
    }

    @Test
    void choiceQuestionCarriesCriteria() {
        JsonObject q = DecisionQuestion.choice("which wood?", Map.of("oak", "have oak", "spruce", "near spruce"));
        assertEquals("choice", q.get("type").getAsString());
        assertTrue(q.getAsJsonObject("criteria").has("oak"));
    }

    @Test
    void noulAndScoreQuestionsHaveTheirShapes() {
        assertEquals("noul", DecisionQuestion.noul("done?").get("type").getAsString());
        JsonObject score = DecisionQuestion.score("how good?", java.util.List.of("progress", "safety"));
        assertEquals("score", score.get("type").getAsString());
        assertEquals(2, score.getAsJsonArray("criteria").size());
    }

    @Test
    void resultReadsEveryTypedAnswer() {
        DecisionResult r = answers("""
                {"pick":{"choice":"oak","confidence":0.9},
                 "done":{"noul":0.8,"confidence":0.7},
                 "quality":{"score":0.42,"confidence":0.6}}""");
        assertEquals("oak", r.choice("pick"));
        assertEquals(0.9, r.confidence("pick"), 1e-9);
        assertEquals(0.8, r.noul("done"), 1e-9);
        assertEquals(0.42, r.score("quality"), 1e-9);
        assertTrue(r.has("pick"));
        assertFalse(r.has("missing"));
    }

    @Test
    void missingAnswersReadAsAbsentNotZero() {
        DecisionResult r = DecisionResult.empty();
        assertEquals(-1.0, r.noul("done"), 1e-9);
        assertEquals(-1.0, r.score("x"), 1e-9);
        assertEquals(null, r.choice("x"));
    }

    @Test
    void mockProviderScriptsAnswersInOrder() {
        MockDecisionProvider mock = new MockDecisionProvider()
                .enqueue(answers("{\"pick\":{\"choice\":\"a\",\"confidence\":1}}"))
                .onEmpty(answers("{\"pick\":{\"choice\":\"z\",\"confidence\":1}}"));
        JsonObject q = new JsonObject();
        q.add("pick", DecisionQuestion.choice("choose", Map.of("a", "x", "z", "y")));
        assertEquals("a", mock.decide("state-1", q).join().choice("pick"));
        assertEquals("z", mock.decide("state-2", q).join().choice("pick"));
        assertEquals("state-2", mock.lastState());
    }
}
