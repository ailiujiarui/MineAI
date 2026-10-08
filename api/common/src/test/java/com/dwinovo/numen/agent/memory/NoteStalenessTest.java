package com.dwinovo.numen.agent.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 札记的印章:写下时盖的是当时那一格;读的时候对得上就照旧信,对不上就当过期,读不出来(区块没载、
 * 她换了维度)时放行——没法核不算核不过。世界是源头,这里只是"别再照旧数走"的一道闸。
 */
class NoteStalenessTest {

    @TempDir
    Path tmp;

    private static final UUID SHE = UUID.randomUUID();

    /** 假的当下世界:绝对格 → 方块 id;不在表里 = 读不出来。 */
    private final Map<String, String> world = new HashMap<>();

    @BeforeEach
    void wire() {
        world.clear();
        NoteBook.init(uuid -> tmp.resolve(uuid.toString()), () -> 3, new NoteBook.BlockLookup() {
            @Override
            public WorldAnchor stamp(UUID owner, int x, int y, int z) {
                String block = world.get(x + "," + y + "," + z);
                return block == null ? null
                        : new WorldAnchor("minecraft:overworld", x, y, z, block);
            }

            @Override
            public String blockAt(UUID owner, WorldAnchor anchor) {
                return world.get(anchor.x() + "," + anchor.y() + "," + anchor.z());
            }
        });
    }

    @Test
    void aNoteIsTrustedWhileItsBlockIsUnchanged() {
        world.put("10,64,10", "minecraft:chest");
        NoteBook book = NoteBook.of(SHE);
        NoteBook.Note note = book.write("base", "主基地", "world", "", book.fingerprint(10, 64, 10));

        assertNotNull(note.anchor(), "说了地方就该盖上一枚印章");
        assertFalse(book.isStale(book.read("base")));
        assertTrue(book.formatXml().contains("主基地"));
        assertFalse(book.formatXml().contains("stale"), "没坏就不该标坏");
        assertEquals(1, book.liveIndex().size());
    }

    @Test
    void aNoteGoesStaleOnceItsBlockChanges() {
        world.put("10,64,10", "minecraft:chest");
        NoteBook book = NoteBook.of(SHE);
        book.write("base", "主基地", "world", "", book.fingerprint(10, 64, 10));

        world.put("10,64,10", "minecraft:air");
        assertTrue(book.isStale(book.read("base")));
        assertTrue(book.formatXml().contains("[stale"), book.formatXml());
        assertTrue(book.liveIndex().isEmpty(), "过期的那条不该再当成可信的");
    }

    @Test
    void anUnreadableCellIsNotStale() {
        world.put("10,64,10", "minecraft:chest");
        NoteBook book = NoteBook.of(SHE);
        book.write("base", "主基地", "world", "", book.fingerprint(10, 64, 10));

        world.clear();   // 区块没加载 / 她换了维度
        assertFalse(book.isStale(book.read("base")), "读不出来是没法核,不是核不过");
        assertFalse(book.formatXml().contains("stale"));
    }

    @Test
    void aNoteAboutNoPlaceNeverGoesStale() {
        NoteBook book = NoteBook.of(SHE);
        book.write("owner", "主人喜欢在夜里干活", "owner", "");
        assertNull(book.read("owner").anchor());
        assertFalse(book.isStale(book.read("owner")));
    }

    @Test
    void theStampSurvivesAReloadAndStillValidates() {
        world.put("10,64,10", "minecraft:chest");
        NoteBook.of(SHE).write("base", "主基地", "world", "", NoteBook.of(SHE).fingerprint(10, 64, 10));

        NoteBook reloaded = NoteBook.of(SHE);   // 同一本;解析走的是落盘后的 frontmatter
        assertNotNull(reloaded.read("base").anchor());
        assertEquals("minecraft:chest", reloaded.read("base").anchor().block());
        world.put("10,64,10", "minecraft:water");
        assertTrue(reloaded.isStale(reloaded.read("base")));
    }
}
