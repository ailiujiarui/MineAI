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

    private static final String BD = """
            {"id":"beyonddimensions","targetMod":"beyonddimensions","side":"client",
             "guis":[{"menu":"beyonddimensions:storage","read":"client","serverIndex":-1,"source":"bd_storage"}]}
            """;

    private static final String TACZ = """
            {"id":"tacz","targetMod":"tacz","side":"both",
             "containers":[{"block":"tacz:ammo_crate","access":"bd-storage"}],
             "useRoutes":[{"item":"tacz:*","intent":"tacz_fire"}]}
            """;

    private void write(String name, String json) throws IOException {
        Files.writeString(dir.resolve(name), json, StandardCharsets.UTF_8);
    }

    @Test
    void loadsAdaptersAndAnswersLookups() throws IOException {
        write("curios.json", CURIOS);
        write("bd.json", BD);
        write("tacz.json", TACZ);

        AdapterRegistry registry = new AdapterRegistry();
        ReloadReport report = registry.reload(dir);

        assertEquals(3, report.loaded());
        assertTrue(report.errors().isEmpty());
        assertEquals("curios", registry.equip("curios:ring").orElseThrow().container());
        assertEquals(46, registry.slots("curios").get(0).index());
        assertEquals("bd_storage", registry.gui("beyonddimensions:storage").orElseThrow().source());
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
    void filtersBySide() throws IOException {
        write("curios.json", CURIOS);
        write("bd.json", BD);

        AdapterRegistry registry = new AdapterRegistry();
        registry.reload(dir);

        assertEquals(List.of("curios"), registry.on(Side.SERVER).stream().map(AdapterSpec::id).toList());
        assertEquals(List.of("beyonddimensions"), registry.on(Side.CLIENT).stream().map(AdapterSpec::id).toList());
    }

    @Test
    void reloadReportsAddedUpdatedRemoved() throws IOException {
        write("curios.json", CURIOS);
        AdapterRegistry registry = new AdapterRegistry();
        assertEquals(List.of("curios"), registry.reload(dir).added());

        write("bd.json", BD);
        ReloadReport second = registry.reload(dir);
        assertEquals(List.of("beyonddimensions"), second.added());
        assertTrue(second.removed().isEmpty());

        write("curios.json", CURIOS.replace("\"index\":46", "\"index\":12"));
        assertEquals(List.of("curios"), registry.reload(dir).updated());

        Files.delete(dir.resolve("bd.json"));
        assertEquals(List.of("beyonddimensions"), registry.reload(dir).removed());
    }

    @Test
    void adaptersWhoseModIsAbsentAreSkipped() throws IOException {
        write("curios.json", CURIOS);
        AdapterRegistry registry = new AdapterRegistry();
        ReloadReport report = registry.reload(dir, mod -> !mod.equals("curios"));

        assertEquals(0, report.loaded());
        assertEquals(List.of("curios"), report.skipped());
        assertTrue(registry.active().isEmpty());
    }
}
