package com.dwinovo.numen.api.adapter;

import com.dwinovo.numen.api.gear.GearSlot;
import com.dwinovo.numen.api.gear.GearSource;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 故障隔离的边界:第三方处理器抛异常时,路由按"没生效"处理(中性值),不打穿调用方,
 * 且原因进 {@link AdapterHandlers#failures()} 可见。fail-soft 不是吞异常。
 */
class AdapterHandlersTest {

    @AfterEach
    void cleanup() {
        AdapterHandlers.clear();
    }

    @Test
    void aThrowingUseHandlerIsIsolatedAndRecorded() {
        AdapterHandlers.registerUse("boom", (body, itemId) -> {
            throw new IllegalStateException("kaboom");
        });
        AdapterHandlers.UseHandler handler = AdapterHandlers.use("boom");
        assertNotNull(handler);
        assertFalse(handler.act(null, "x"), "抛异常按没生效处理,不打穿");
        assertTrue(AdapterHandlers.failures().getOrDefault("boom", "").contains("kaboom"),
                "原因要可见,不然就是吞异常");
    }

    @Test
    void aThrowingGuiHandlerReturnsNull() {
        AdapterHandlers.registerGui("boom", (body, menu, source) -> {
            throw new RuntimeException("nope");
        });
        assertNull(AdapterHandlers.gui("boom").read(null, null, "boom"));
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
        AdapterHandlers.registerContainer("c", (body, pos, access) -> new JsonObject());
        assertTrue(AdapterHandlers.has("c"));
    }

    @Test
    void aRecoveredHandlerClearsItsFailureState() {
        boolean[] boom = {true};
        AdapterHandlers.registerUse("flaky", (body, item) -> {
            if (boom[0]) {
                throw new IllegalStateException("x");
            }
            return true;
        });
        assertFalse(AdapterHandlers.use("flaky").act(null, "i"));
        assertTrue(AdapterHandlers.failures().containsKey("flaky"));

        boom[0] = false;
        assertTrue(AdapterHandlers.use("flaky").act(null, "i"));
        assertFalse(AdapterHandlers.failures().containsKey("flaky"), "恢复后清掉失败态,不刷屏");
    }
}
