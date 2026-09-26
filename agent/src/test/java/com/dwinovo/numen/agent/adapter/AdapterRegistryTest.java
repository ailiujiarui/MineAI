package com.dwinovo.numen.agent.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdapterRegistryTest {

    @TempDir
    Path dir;

    private static final String CURIOS = """
            {"id":"curios","targetMod":"curios","side":"server",
             "equipRoutes":[{"item":"curios:ring*","container":"curios","slot":"ring"}],
             "slotMaps":[{"name":"ring","container":"curios","item":"curios:ring","index":46}]}
            """;

    private static final String TACZ = """
            {"id":"tacz","targetMod":"tacz","side":"server",
             "containers":[{"block":"tacz:ammo_crate","access":"bd-storage"}],
             "useRoutes":[{"item":"tacz:*","intent":"tacz_fire"}]}
            """;

    private static final String BEYOND_DIMENSIONS = """
            {"id":"beyonddimensions","targetMod":"beyonddimensions","side":"client",
             "guis":[{"menu":"beyonddimensions:storage","read":"client","serverIndex":-1,"source":"bd_storage"}]}
            """;

    private void write(String name, String json) throws IOException {
        Files.writeString(dir.resolve(name), json, StandardCharsets.UTF_8);
    }

    private static boolean skippedFor(ReloadReport report, String id, String reasonPart) {
        return report.skipped().stream()
                .anyMatch(skip -> skip.id().equals(id) && skip.reason().contains(reasonPart));
    }

    @Test
    void loadsAdaptersAndAnswersLookups() throws IOException {
        write("curios.json", CURIOS);
        write("tacz.json", TACZ);

        AdapterRegistry registry = new AdapterRegistry();
        ReloadReport report = registry.reload(dir);

        assertEquals(2, report.loaded());
        assertTrue(report.errors().isEmpty());
        assertEquals("curios", registry.equip("curios:ring").orElseThrow().container());
        assertEquals(46, registry.slots("curios").get(0).index());
        assertEquals("tacz_fire", registry.use("tacz:ak47").orElseThrow().intent());
        assertEquals("bd-storage", registry.container("tacz:ammo_crate").orElseThrow().access());
    }

    @Test
    void aMalformedFileDoesNotBreakTheRest() throws IOException {
        write("good.json", CURIOS);
        write("bad.json", "{ this is not json");

        AdapterRegistry registry = new AdapterRegistry();
        ReloadReport report = registry.reload(dir);

        assertEquals(1, report.loaded());
        assertEquals(1, report.failed());
        assertTrue(registry.equip("curios:ring").isPresent(), "坏文件不影响好文件");
    }

    @Test
    void clientAdaptersAreSkippedInV1() throws IOException {
        write("bd.json", BEYOND_DIMENSIONS);
        AdapterRegistry registry = new AdapterRegistry();
        ReloadReport report = registry.reload(dir);
        assertEquals(0, report.loaded());
        assertTrue(skippedFor(report, "beyonddimensions", "server only"));
    }

    @Test
    void disabledAdaptersAreSkipped() throws IOException {
        write("off.json", "{\"id\":\"off\",\"side\":\"server\",\"enabled\":false}");
        AdapterRegistry registry = new AdapterRegistry();
        assertTrue(skippedFor(registry.reload(dir), "off", "disabled"));
    }

    @Test
    void aNewerSchemaIsSkippedInsteadOfSilentlyMisparsed() throws IOException {
        write("future.json", "{\"id\":\"future\",\"side\":\"server\",\"schema\":99}");
        AdapterRegistry registry = new AdapterRegistry();
        assertTrue(skippedFor(registry.reload(dir), "future", "schema 99"));
    }

    @Test
    void aMissingRequiredHandlerIsSkippedWithReason() throws IOException {
        write("needs.json", "{\"id\":\"needs\",\"side\":\"server\",\"requires\":[\"curios\"],"
                + "\"useRoutes\":[{\"item\":\"y\",\"intent\":\"z\"}]}");
        AdapterRegistry registry = new AdapterRegistry();

        ReloadReport withoutHandler = registry.reload(dir, mod -> true, handler -> false);
        assertEquals(0, withoutHandler.loaded());
        assertTrue(skippedFor(withoutHandler, "needs", "missing handler 'curios'"));

        ReloadReport withHandler = registry.reload(dir, mod -> true, handler -> true);
        assertEquals(1, withHandler.loaded());
    }

    @Test
    void priorityDecidesAmongOverlappingRoutes() throws IOException {
        write("low.json", "{\"id\":\"low\",\"side\":\"server\",\"priority\":0,"
                + "\"useRoutes\":[{\"item\":\"x\",\"intent\":\"low\"}]}");
        write("high.json", "{\"id\":\"high\",\"side\":\"server\",\"priority\":10,"
                + "\"useRoutes\":[{\"item\":\"x\",\"intent\":\"high\"}]}");
        AdapterRegistry registry = new AdapterRegistry();
        registry.reload(dir);
        assertEquals("high", registry.use("x").orElseThrow().intent(), "分高者胜,不看文件顺序");
    }

    @Test
    void reloadReportsAddedUpdatedRemoved() throws IOException {
        write("curios.json", CURIOS);
        AdapterRegistry registry = new AdapterRegistry();
        assertEquals(List.of("curios"), registry.reload(dir).added());

        write("tacz.json", TACZ);
        ReloadReport second = registry.reload(dir);
        assertEquals(List.of("tacz"), second.added());
        assertTrue(second.removed().isEmpty());

        write("curios.json", CURIOS.replace("\"index\":46", "\"index\":12"));
        assertEquals(List.of("curios"), registry.reload(dir).updated());

        Files.delete(dir.resolve("tacz.json"));
        assertEquals(List.of("tacz"), registry.reload(dir).removed());
    }

    @Test
    void adaptersWhoseModIsAbsentAreSkipped() throws IOException {
        write("curios.json", CURIOS);
        AdapterRegistry registry = new AdapterRegistry();
        ReloadReport report = registry.reload(dir, mod -> !mod.equals("curios"));
        assertEquals(0, report.loaded());
        assertTrue(skippedFor(report, "curios", "not loaded"));
        assertTrue(registry.active().isEmpty());
    }

    @Test
    void theEarlierDirectoryWinsSoUsersOverrideBundled() throws IOException {
        Path user = Files.createDirectory(dir.resolve("user"));
        Path bundled = Files.createDirectory(dir.resolve("bundled"));
        Files.writeString(bundled.resolve("curios.json"), CURIOS, StandardCharsets.UTF_8);
        Files.writeString(user.resolve("curios.json"),
                CURIOS.replace("\"index\":46", "\"index\":7"), StandardCharsets.UTF_8);

        AdapterRegistry registry = new AdapterRegistry();
        registry.reload(List.of(user, bundled), mod -> true, handler -> true);

        assertEquals(7, registry.slots("curios").get(0).index(), "用户目录在前,覆盖 bundled 的同名 id");
    }
}
