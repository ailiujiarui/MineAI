package com.dwinovo.numen.adapter;

import com.dwinovo.numen.agent.adapter.AdapterRegistry;
import com.dwinovo.numen.agent.adapter.HandlerKind;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.adapter.AdapterHandlers;
import com.dwinovo.numen.api.gear.GearSlot;
import com.dwinovo.numen.api.gear.GearSource;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.LuaCodecs;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AdapterWiringTest {
    @TempDir Path dir;

    @AfterEach
    void cleanup() {
        AdapterHandlers.clear();
        AdapterManager.registry().clear();
    }

    @Test
    void pluginRegistrationLoadConsumptionAndReceiptUseTheSameValues() throws Exception {
        GearSlot ring = new GearSlot() {
            public String name() { return "test:ring"; }
            public ItemStack worn() { return null; }
            public Optional<String> refuseWear(ItemStack one) { return Optional.empty(); }
            public Optional<String> refuseRemove() { return Optional.empty(); }
            public ItemStack swap(ItemStack in) { return in; }
        };
        NumenPlugins.register("adapter_test", api -> {
            api.api("adapter", "Adapter diagnostics.", AdapterApi.class);
            api.registerAdapterGui("reader", (body, menu, source) -> List.of("progress: 7"));
            api.registerAdapterContainer("reader", (body, pos, access) -> List.of("energy: 42"));
            api.registerAdapterGear("gear", new GearSource() {
                public List<GearSlot> slots(NumenPlayer body) { return List.of(ring); }
                public Set<String> kindsOf(NumenPlayer body, ItemStack stack) { return Set.of("ring"); }
            });
        });
        Files.writeString(dir.resolve("host.json"), """
                {"id":"host","requires":["reader","gear"],
                 "equipRoutes":[{"item":"test:ring","container":"gear"}],
                 "guis":[{"menu":"test:menu","source":"reader"}],
                 "containers":[{"block":"test:block","access":"reader"}]}
                """);
        var registry = AdapterManager.registry();
        var report = registry.reload(List.of(dir), mod -> true, AdapterHandlers::has, AdapterHandlers::has);
        assertEquals(1, report.loaded());
        assertEquals(List.of("progress: 7"), AdapterHandlers.gui(registry.gui("test:menu").orElseThrow().source())
                .read(null, null, "reader"));
        assertEquals(List.of("energy: 42"), AdapterHandlers.container(registry.container("test:block").orElseThrow().access())
                .read(null, null, "reader"));
        assertEquals(Set.of("ring"), AdapterHandlers.gear(registry.equip("test:ring").orElseThrow().container())
                .kindsOf(null, null));
        assertEquals(List.of(ring), new AdapterGearSource().slots(null));
        Map<?, ?> receipt = (Map<?, ?>) LuaCodecs.encode(report);
        assertEquals(List.of("host"), receipt.get("added"));
        Map<?, ?> state = (Map<?, ?>) LuaCodecs.encode(AdapterApi.list(null));
        assertEquals(List.of("host"), state.get("active"));
    }

    @Test
    void aSameNamedHandlerOfAnotherTypeCannotSatisfyRequires() throws Exception {
        Files.writeString(dir.resolve("typed.json"), """
                {"id":"typed","requires":["reader"],
                 "guis":[{"menu":"test:menu","source":"reader"}]}
                """);
        AdapterHandlers.registerContainer("reader", (body, pos, access) -> List.of());
        AdapterRegistry registry = new AdapterRegistry();
        var blocked = registry.reload(List.of(dir), mod -> true, AdapterHandlers::has, AdapterHandlers::has);
        assertEquals(0, blocked.loaded());
        assertTrue(blocked.skipped().getFirst().reason().contains("missing gui handler 'reader'"));
        assertFalse(AdapterHandlers.has(HandlerKind.GUI, "reader"));
        AdapterHandlers.registerGui("reader", (body, menu, source) -> List.of());
        assertEquals(1, registry.reload(List.of(dir), mod -> true, AdapterHandlers::has, AdapterHandlers::has).loaded());
    }
}
