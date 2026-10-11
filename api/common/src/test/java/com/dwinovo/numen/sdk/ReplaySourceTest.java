package com.dwinovo.numen.sdk;

import com.dwinovo.numen.task.TaskPersistence;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

class ReplaySourceTest {
    @Test
    void capabilityIsBoundToCompanionAndExactProgram() throws ReflectiveOperationException {
        var constructor = TaskPersistence.ReplaySource.class.getDeclaredConstructor(java.util.UUID.class, String.class);
        constructor.setAccessible(true);
        var companion = java.util.UUID.randomUUID();
        var source = constructor.newInstance(companion, "restored-numen.move.go");
        assertTrue(source.matches(companion, source.programId() + "#2"));
        assertFalse(source.matches(java.util.UUID.randomUUID(), source.programId() + "#2"));
        assertFalse(source.matches(companion, "restored-numen.move.go#2"));
        assertFalse(source.matches(companion, source.programId() + "-impostor#2"));
        assertFalse(source.matches(companion, source.programId()));
    }

    @Test
    void restoredNameCannotCreateReplayAuthority() {
        assertFalse(new ServerCall(null, null, null, "restored-numen.move.go-pretend#1").isReplay());
        assertFalse(new ServerCall(null, null, null, "restored#1", null).isReplay());
        for (var constructor : TaskPersistence.ReplaySource.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()), "only TaskPersistence creates the capability");
        }
    }
}
