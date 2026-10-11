package com.dwinovo.numen.api.adapter;

import com.dwinovo.numen.api.gear.GearSlot;
import com.dwinovo.numen.api.gear.GearSource;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 故障隔离的边界:装备保留中性值,动作与读取失败交给调用者;原因在诊断里可见。
 */
class AdapterHandlersTest {

    @AfterEach
    void cleanup() {
        AdapterHandlers.clear();
    }

    @Test
    void aThrowingUseHandlerIsIsolatedAndRecorded() {
        AdapterHandlers.registerUse("boom", new AdapterHandlers.UseHandler() {
            public Action action(NumenPlayer body, AdapterHandlers.UseContext context) {
                throw new IllegalStateException("kaboom");
            }
            public List<String> act(NumenPlayer body, AdapterHandlers.UseContext context, Action action) {
                throw new AssertionError("must not execute");
            }
        });
        AdapterHandlers.UseHandler handler = AdapterHandlers.use("boom");
        assertNotNull(handler);
        assertTrue(AdapterHandlers.has(com.dwinovo.numen.agent.adapter.HandlerKind.USE, "boom"));
        assertFalse(AdapterHandlers.has(com.dwinovo.numen.agent.adapter.HandlerKind.GUI, "boom"));
        assertThrows(com.dwinovo.numen.agent.script.ApiError.class, () -> handler.action(null, context()));
        assertTrue(AdapterHandlers.failures().getOrDefault("boom", "").contains("kaboom"),
                "原因要可见,不然就是吞异常");
    }

    @Test
    void aThrowingGuiHandlerFailsTheRead() {
        AdapterHandlers.registerGui("boom", (body, menu, source) -> {
            throw new RuntimeException("nope");
        });
        var failure = assertThrows(com.dwinovo.numen.agent.script.ApiError.class,
                () -> AdapterHandlers.gui("boom").read(null, null, "boom"));
        assertTrue(failure.getMessage().contains("nope"));
        assertTrue(AdapterHandlers.failures().containsKey("boom"));
    }

    @Test
    void aThrowingGearHandlerReturnsEmpty() {
        AdapterHandlers.registerGear("boom", new GearSource() {
            @Override
            public List<GearSlot> slots(NumenPlayer body) {
                throw new IllegalStateException("no slots");
            }

            @Override
            public Set<String> kindsOf(NumenPlayer body, ItemStack stack) {
                throw new IllegalStateException("no kinds");
            }
        });
        assertTrue(AdapterHandlers.gear("boom").slots(null).isEmpty());
        assertTrue(AdapterHandlers.gear("boom").kindsOf(null, null).isEmpty());
        assertTrue(AdapterHandlers.failures().containsKey("boom"));
    }

    @Test
    void hasReportsRegisteredNames() {
        assertFalse(AdapterHandlers.has("c"));
        AdapterHandlers.registerContainer("c", (body, pos, access) -> List.of());
        assertTrue(AdapterHandlers.has("c"));
        assertTrue(AdapterHandlers.has(com.dwinovo.numen.agent.adapter.HandlerKind.CONTAINER, "c"));
        assertFalse(AdapterHandlers.has(com.dwinovo.numen.agent.adapter.HandlerKind.GUI, "c"));
    }

    @Test
    void readsAreValuesAndAContainerFailureIsNotAnEmptySuccess() {
        AdapterHandlers.registerContainer("storage", (body, pos, key) -> List.of("energy: 42"));
        assertEquals(List.of("energy: 42"), AdapterHandlers.container("storage").read(null, null, "storage"));
        AdapterHandlers.registerContainer("storage", (body, pos, key) -> { throw new IllegalStateException("offline"); });
        assertThrows(com.dwinovo.numen.agent.script.ApiError.class,
                () -> AdapterHandlers.container("storage").read(null, null, "storage"));
    }

    @Test
    void aRecoveredHandlerClearsItsFailureState() {
        boolean[] boom = {true};
        AdapterHandlers.registerUse("flaky", new AdapterHandlers.UseHandler() {
            public Action action(NumenPlayer body, AdapterHandlers.UseContext context) { return command(); }
            public List<String> act(NumenPlayer body, AdapterHandlers.UseContext context, Action action) {
                if (boom[0]) throw new IllegalStateException("x");
                return List.of("executed");
            }
        });
        assertThrows(com.dwinovo.numen.agent.script.ApiError.class,
                () -> AdapterHandlers.use("flaky").act(null, context(), command()));
        assertTrue(AdapterHandlers.failures().containsKey("flaky"));

        boom[0] = false;
        assertEquals(List.of("executed"), AdapterHandlers.use("flaky").act(null, context(), command()));
        assertFalse(AdapterHandlers.failures().containsKey("flaky"), "恢复后清掉失败态,不刷屏");
    }

    private static AdapterHandlers.UseContext context() {
        return new AdapterHandlers.UseContext("test:item", null, 0, false);
    }

    private static Action command() {
        return Action.command("say adapter", new com.mojang.brigadier.tree.RootCommandNode<>());
    }

    @Test
    void missingConcreteActionIsRejectedBeforeExecution() {
        for (Action missing : java.util.Arrays.asList(null, Action.attack(null), Action.drop(null),
                Action.useBlock(net.minecraft.core.BlockPos.ZERO, null))) {
            AdapterHandlers.registerUse("missing", new AdapterHandlers.UseHandler() {
                public Action action(NumenPlayer body, AdapterHandlers.UseContext context) { return missing; }
                public List<String> act(NumenPlayer body, AdapterHandlers.UseContext context, Action action) {
                    throw new AssertionError("must not execute");
                }
            });
            var error = assertThrows(com.dwinovo.numen.agent.script.ApiError.class,
                    () -> AdapterHandlers.use("missing").action(null, context()));
            assertTrue(error.getMessage().contains("concrete Action"));
        }
    }
}
