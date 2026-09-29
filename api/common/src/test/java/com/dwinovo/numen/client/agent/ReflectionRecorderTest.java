package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.memory.NoteBook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失败正文 → 教训的映射:纯函数,不碰 Minecraft,headless 单测钉住。
 *
 * <p>这里守四件事:只有失败才学、教训带原因也带下一步、正文不原样倒进去、
 * 同名同文不重写。
 */
class ReflectionRecorderTest {

    @TempDir
    Path tmp;

    private static final UUID SHE = UUID.randomUUID();

    /** 一条和 {@code NumenEvents.taskFinished} 拼出来的形状一致的事件。 */
    private static EventQueue.Entry finished(String task, String status, String body) {
        return new EventQueue.Entry(EventTypes.TASK_FINISHED,
                "<event kind=\"task_finished\" day=\"1\" t=\"06:00\" id=\"t1\" task=\"" + task
                        + "\" status=\"" + status + "\">" + body + "</event>", 0L, true);
    }

    @Test
    void successAndOwnerStopAreNotFailures() {
        assertNull(ReflectionRecorder.lessonFor("work_mine", "done", "mined 3 iron_ore"));
        assertNull(ReflectionRecorder.lessonFor("work_mine", "stopped", "the owner cancelled it"));
    }

    /** 失败话里认得出的原因要接上一句能带走的下一次怎么做,不是把正文抄一遍。 */
    @Test
    void anUnreachableTargetBecomesAMoveFirstLesson() {
        var lesson = ReflectionRecorder.lessonFor("work_mine", "failed",
                "found 6 iron_ore but could not reach any of them from mine area");

        assertNotNull(lesson);
        assertEquals("lesson-task-work-mine", lesson.name());
        assertEquals("work_mine: target unreachable — move_goto closer first"
                + " (it stops beside a solid block), then retry", lesson.description());
    }

    @Test
    void aTimeoutIsLearnedAsRunningOutOfTime() {
        var lesson = ReflectionRecorder.lessonFor("work_fish", "timeout", "timed out");

        assertNotNull(lesson);
        assertTrue(lesson.description().startsWith("work_fish: ran out of time — "));
    }

    @Test
    void aMissingTargetIsLearnedAsScanOrMoveCloser() {
        var lesson = ReflectionRecorder.lessonFor("work_mine", "failed",
                "no iron_ore found in the loaded area around me");

        assertNotNull(lesson);
        assertTrue(lesson.description().contains("target not found nearby"));
        assertTrue(lesson.description().contains("scan or move closer"));
    }

    /** 认不出的原因退回一句通用的,仍不把原文倒进索引行。 */
    @Test
    void anUnrecognizedFailureStillGetsAGeneralLesson() {
        var lesson = ReflectionRecorder.lessonFor("work_build", "failed",
                "something odd happened with the blueprint");

        assertNotNull(lesson);
        assertTrue(lesson.description().startsWith("work_build: it did not get done — "));
        assertTrue(lesson.description().length() <= ReflectionRecorder.MAX_LESSON);
    }

    @Test
    void theEventIsReadBackWithItsAttributesAndUnescapedBody() {
        var lesson = ReflectionRecorder.fromEvent(finished("work_mine", "failed",
                "no iron_ore found &amp; &lt;path blocked&gt;"));

        assertNotNull(lesson);
        assertEquals("lesson-task-work-mine", lesson.name());
        assertTrue(lesson.content().startsWith("failed: "));
        assertTrue(lesson.content().contains("no iron_ore found & <path blocked>"),
                "正文要还原成她写下的样子,不是带实体转义的原样倒出");
    }

    @Test
    void otherEventsAndMissingTaskWriteNothing() {
        assertNull(ReflectionRecorder.fromEvent(new EventQueue.Entry(EventTypes.REFLEX,
                "<event kind=\"reflex\" reflex=\"mlg\">broke a fall</event>", 0L, false)));
        assertNull(ReflectionRecorder.fromEvent(new EventQueue.Entry(EventTypes.TASK_FINISHED,
                "<event kind=\"task_finished\" status=\"failed\">no task name</event>", 0L, true)));
    }

    /** 同名覆盖、同文不重写:同一件活反复失败只留一条,索引不白重贴。 */
    @Test
    void aRepeatedIdenticalLessonIsNotRewrittenAndADifferentOneUpdatesIt() {
        NoteBook.init(uuid -> tmp.resolve(uuid.toString()), () -> 1);
        NoteBook book = NoteBook.of(SHE);

        ReflectionRecorder.record(book, finished("work_mine", "failed", "could not reach the iron_ore"));
        int afterFirst = book.revision();

        ReflectionRecorder.record(book, finished("work_mine", "failed", "could not reach the iron_ore"));
        assertEquals(afterFirst, book.revision(), "一字不变就不该再写一次");

        ReflectionRecorder.record(book, finished("work_mine", "timeout", "timed out"));
        assertEquals(afterFirst + 1, book.revision(), "换了原因要更新同一条");
        assertEquals(1, book.index().size(), "同一件活只有一个落点,不堆条数");
    }
}
