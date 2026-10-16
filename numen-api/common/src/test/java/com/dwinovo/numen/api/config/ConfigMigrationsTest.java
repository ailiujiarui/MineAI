package com.dwinovo.numen.api.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站点目录退回只读内置之后,落盘的那份成了死数据,挂成 {@code .bak} 移开。
 *
 * <p>关键在于<b>认形状不认文件名</b>:{@code providers.json} 这个名字被两代东西用过——
 * 开发期是站点目录({@code providers} 数组),现在是面板的密钥库({@code entries})。
 * 认名字就会把玩家的 API Key 搬走。
 */
class ConfigMigrationsTest {

    private static final String CATALOG = "{\"providers\":[{\"id\":\"openai\"}]}";
    private static final String LIBRARY = "{\"entries\":[{\"id\":\"prov_1\",\"api_key\":\"sk-real\"}]}";

    @TempDir
    Path dir;

    @Test
    void legacyCatalogIsParked() throws IOException {
        Files.writeString(dir.resolve("models.json"), CATALOG);
        ConfigMigrations.run(dir);
        assertFalse(Files.exists(dir.resolve("models.json")));
        assertEquals(CATALOG, Files.readString(dir.resolve("models.json.bak")));
    }

    @Test
    void catalogSeededUnderTheNewNameIsAlsoParked() throws IOException {
        // 0.1.2 开发期播下的种子:文件名已经是 providers.json,但形状还是站点目录
        Files.writeString(dir.resolve("providers.json"), CATALOG);
        ConfigMigrations.run(dir);
        assertFalse(Files.exists(dir.resolve("providers.json")));
        assertEquals(CATALOG, Files.readString(dir.resolve("providers.json.bak")));
    }

    @Test
    void playerKeyLibraryIsNeverTouched() throws IOException {
        // 同一个文件名,装的是玩家的 API Key —— 动它就是丢数据
        Files.writeString(dir.resolve("providers.json"), LIBRARY);
        ConfigMigrations.run(dir);
        assertEquals(LIBRARY, Files.readString(dir.resolve("providers.json")));
        assertFalse(Files.exists(dir.resolve("providers.json.bak")));
    }

    @Test
    void unreadableFileIsLeftAlone() throws IOException {
        // 读不动/不是 JSON 一律不搬:宁可留着让人自己看,也不擅自移走
        Files.writeString(dir.resolve("providers.json"), "{ 半截");
        ConfigMigrations.run(dir);
        assertTrue(Files.exists(dir.resolve("providers.json")));
    }

    @Test
    void emptyDirIsANoOp() {
        ConfigMigrations.run(dir);
        assertFalse(Files.exists(dir.resolve("providers.json.bak")));
        assertFalse(Files.exists(dir.resolve("models.json.bak")));
    }

    @Test
    void runningTwiceKeepsTheFirstBackup() throws IOException {
        Files.writeString(dir.resolve("models.json"), CATALOG);
        ConfigMigrations.run(dir);
        // 第二次启动:上一版又播了一份种子下来,已有备份不被覆盖
        Files.writeString(dir.resolve("models.json"), "{\"providers\":[]}");
        ConfigMigrations.run(dir);
        assertFalse(Files.exists(dir.resolve("models.json")));
        assertEquals(CATALOG, Files.readString(dir.resolve("models.json.bak")));
    }

    // ---- 厂家 id 迁到 models.dev ----

    private static com.google.gson.JsonObject entryOf(Path file, int index) throws IOException {
        return com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject()
                .getAsJsonArray("entries").get(index).getAsJsonObject();
    }

    @Test
    void legacyProviderIdsBecomeModelsDevIds() throws IOException {
        Files.writeString(dir.resolve("providers.json"), """
                {"entries":[
                  {"id":"a","provider":"moonshot","api_key":"k1","model":"kimi-k2"},
                  {"id":"b","provider":"Zhipu"},{"id":"c","provider":"dashscope"},{"id":"d","provider":"siliconflow"},
                  {"id":"e","provider":"gemini"},{"id":"f","provider":"grok"},{"id":"g","provider":"qwen"},
                  {"id":"h","provider":"deepseek"}]}""");
        ConfigMigrations.run(dir);
        Path file = dir.resolve("providers.json");
        String[] expected = {"moonshotai", "zhipuai", "alibaba-cn", "siliconflow-cn", "google", "xai",
                "alibaba-cn", "deepseek"};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], entryOf(file, i).get("provider").getAsString(), "entry " + i);
        }
        assertEquals("k1", entryOf(file, 0).get("api_key").getAsString(), "别的字段原样保留");
        assertTrue(Files.readString(file).contains(ConfigMigrations.PROVIDER_IDS_MARKER));
    }

    @Test
    void localDeploymentsBecomeCustomProvidersKeepingTheirAddress() throws IOException {
        Files.writeString(dir.resolve("providers.json"), """
                {"entries":[
                  {"id":"a","provider":"ollama","model":"qwen3"},
                  {"id":"b","provider":"lmstudio","base_url":"http://192.168.1.5:1234/v1"},
                  {"id":"c","provider":"vllm"}]}""");
        ConfigMigrations.run(dir);
        Path file = dir.resolve("providers.json");
        for (int i = 0; i < 3; i++) {
            assertEquals("custom", entryOf(file, i).get("provider").getAsString());
            assertEquals("chat", entryOf(file, i).get("protocol").getAsString());
        }
        assertEquals("http://localhost:11434/v1", entryOf(file, 0).get("base_url").getAsString());
        assertEquals("http://192.168.1.5:1234/v1", entryOf(file, 1).get("base_url").getAsString(), "玩家填过的地址不动");
        assertEquals("http://localhost:8000/v1", entryOf(file, 2).get("base_url").getAsString());
        assertEquals("qwen3", entryOf(file, 0).get("model").getAsString());
    }

    @Test
    void aMigratedLibraryIsNotMigratedAgain() throws IOException {
        // models.dev 自己也有 lmstudio:迁过之后玩家选了它,不能再被当成老 id 改成自定义
        String migrated = "{\"entries\":[{\"id\":\"a\",\"provider\":\"lmstudio\"}],\"models_dev_ids\":true}";
        Files.writeString(dir.resolve("providers.json"), migrated);
        ConfigMigrations.run(dir);
        assertEquals(migrated, Files.readString(dir.resolve("providers.json")));
    }

    @Test
    void runningTheIdMigrationTwiceChangesNothingTheSecondTime() throws IOException {
        Files.writeString(dir.resolve("providers.json"),
                "{\"entries\":[{\"id\":\"a\",\"provider\":\"ollama\"},{\"id\":\"b\",\"provider\":\"kimi\"}]}");
        ConfigMigrations.run(dir);
        String once = Files.readString(dir.resolve("providers.json"));
        ConfigMigrations.run(dir);
        assertEquals(once, Files.readString(dir.resolve("providers.json")));
    }

    @Test
    void missingDirDoesNotThrow() {
        ConfigMigrations.run(dir.resolve("no-such-subdir"));
        assertTrue(true);
    }
}
