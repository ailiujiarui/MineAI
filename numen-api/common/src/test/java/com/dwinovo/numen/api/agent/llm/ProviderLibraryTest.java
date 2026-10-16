package com.dwinovo.numen.api.agent.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 档案库的存取:档案的每个字段落盘再读回来,落盘时带上“厂家 id 已是 models.dev id”的标记。 */
class ProviderLibraryTest {

    @TempDir
    Path dir;

    private ProviderLibrary fresh() {
        return new ProviderLibrary(dir.resolve("providers.json"), dir.resolve("cache").resolve("models-dev.json"));
    }

    @Test
    void everyProfileFieldSurvivesAReload() {
        JsonObject compat = JsonParser.parseString("{\"thinking_format\":\"none\"}").getAsJsonObject();
        ConnectionProfile profile = new ConnectionProfile("deepseek", "chat", "deepseek-v4-flash", "sk-1",
                "https://x.example/v1", "127.0.0.1:7890", "high", 32_000, 0.4, 2048, compat);
        ProviderLibrary.Entry e = fresh().create("我的 DeepSeek", profile);

        ProviderLibrary.Entry back = fresh().get(e.id());
        assertEquals("我的 DeepSeek", back.name());
        assertEquals(profile.provider(), back.profile().provider());
        assertEquals(profile.protocol(), back.profile().protocol());
        assertEquals(profile.model(), back.profile().model());
        assertEquals(profile.apiKey(), back.profile().apiKey());
        assertEquals(profile.baseUrl(), back.profile().baseUrl());
        assertEquals(profile.proxy(), back.profile().proxy());
        assertEquals(profile.reasoningEffort(), back.profile().reasoningEffort());
        assertEquals(32_000, back.profile().ctx());
        assertEquals(0.4, back.profile().temperature());
        assertEquals(2048, back.profile().maxTokens());
        assertEquals(compat, back.profile().compat());
    }

    @Test
    void anOldStyleEntryReadsWithTheNewFieldsUnset() throws IOException {
        Files.writeString(dir.resolve("providers.json"), """
                {"entries":[{"id":"p1","name":"旧档案","provider":"deepseek","model":"deepseek-chat",
                  "api_key":"sk-old","reasoning_effort":"off","ctx":64000}]}""");
        ConnectionProfile p = fresh().get("p1").profile();
        assertEquals("deepseek", p.provider());
        assertEquals("off", p.reasoningEffort());
        assertEquals(64_000, p.ctx());
        assertNull(p.protocol());
        assertNull(p.temperature());
        assertEquals(0, p.maxTokens());
        assertNull(p.compat());
    }

    @Test
    void savingMarksTheIdsAsModelsDevIds() throws IOException {
        fresh().create("x", new ConnectionProfile("lmstudio", null, "m", "", null, null, null, 0, null, 0, null));
        assertTrue(Files.readString(dir.resolve("providers.json"))
                .contains("\"" + com.dwinovo.numen.api.config.ConfigMigrations.PROVIDER_IDS_MARKER + "\": true"));
    }
}
