package com.dwinovo.numen.core.route;

import com.dwinovo.numen.core.CoreApiFixture;
import com.dwinovo.numen.core.nav.NavText;
import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.sdk.LuaCodecs;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@Tag("mc")
class WalkCommitTest {
    @BeforeAll
    static void boot() {
        CoreApiFixture.install();
    }

    private static WalkCommit commit() {
        Description.Directions spec = (Description.Directions) LuaCodecs.of(Description.Directions.class, "numen")
                .decode(Map.of("to", Map.of("x", 12, "y", 64, "z", 0),
                        "stops", List.of(Map.of("to", Map.of("x", 3, "y", 64, "z", 0), "type", "through")),
                        "costs", Map.of("dig", true, "place", 42, "consent", 10, "max_changes", 8),
                        "avoid", List.of("water"), "avoid_break", List.of("minecraft:chest"),
                        "avoid_place", List.of("minecraft:oak_planks"), "avoid_step", List.of("minecraft:stone"),
                        "materials", List.of("minecraft:cobblestone")));
        BlockPos dig = new BlockPos(8, 64, 0);
        return new WalkCommit(spec, BlockPos.ZERO,
                List.of(new WalkCommit.CommitCell(dig, Blocks.DIRT)),
                List.of(new WalkCommit.CommitCell(dig.below(), Blocks.COBBLESTONE)),
                List.of(new WalkCommit.CommitAsk(dig, "placed by a player")), List.of(),
                ResourceLocation.parse("minecraft:overworld"), "64fa8be8-1000-4000-8000-000000000001", 1);
    }

    @Test
    void codecRetainsFullDescriptionAndCommitWithoutAPathOrGrant() {
        WalkCommit saved = commit();
        saved.lua();
        Plans.Ref ref = new Plans.Ref("p9", Optional.of(LuaCodecs.encode(saved)));
        Plans.Ref decoded = Plans.Ref.CODEC.decode(Plans.Ref.CODEC.encode(ref));
        Plan fresh = new Plan("p9", Description.of(saved.remaining()), BlockPos.ZERO, List.of());
        WalkCommit restored = WalkCommit.restored(decoded, true, fresh);
        assertEquals(LuaCodecs.encode(saved), LuaCodecs.encode(restored));
        assertEquals(saved.worldId(), restored.worldId());
        String lua = restored.lua();
        assertTrue(lua.contains("numen.route.plan") && lua.contains("numen.move.go") && lua.contains("p.commit"), lua);
        assertFalse(lua.contains("path =") || lua.contains("credential") || lua.contains("grant"), lua);
        assertTrue(restored.promise("fresh").legs().stream().allMatch(l -> l.route() == null && l.chart() == null));
    }

    @Test
    void untrustedCommitIsIgnoredEvenWhenMalformedOrWidened() {
        WalkCommit saved = commit();
        Plan fresh = new Plan("p9", Description.of(saved.remaining()), BlockPos.ZERO, List.of());
        assertNull(WalkCommit.restored(new Plans.Ref("p9", Optional.of("not a commit")), false, fresh));
        assertNull(WalkCommit.restored(Plans.Ref.CODEC.decode(Map.of("id", "p9", "commit",
                Map.of("spec", Map.of("to", Map.of("x", 9000, "y", 64, "z", 0)),
                        "digs", List.of(Map.of("pos", Map.of("x", 9, "y", 64, "z", 0), "block", "minecraft:stone"))))),
                false, fresh));
    }

    @Test
    void trustedReplayMustMatchRemainingDescriptionAndRealm() {
        WalkCommit saved = commit();
        saved.lua();
        Plans.Ref ref = Plans.Ref.CODEC.decode(Plans.Ref.CODEC.encode(new Plans.Ref("p9", Optional.of(LuaCodecs.encode(saved)))));
        Plan wrong = new Plan("p9", Description.of(saved.spec()), BlockPos.ZERO, List.of());
        assertThrows(IllegalArgumentException.class, () -> WalkCommit.restored(ref, true, wrong));
        Description.Directions changedGoal = (Description.Directions) LuaCodecs.of(Description.Directions.class, "numen")
                .decode(Map.of("to", Map.of("x", 9000, "y", 64, "z", 0)));
        assertThrows(IllegalArgumentException.class, () -> WalkCommit.restored(ref, true,
                new Plan("p9", Description.of(changedGoal), BlockPos.ZERO, List.of())));
        WalkCommit wrongFirst = saved.at(0);
        Plans.Ref wrongProgress = Plans.Ref.CODEC.decode(Plans.Ref.CODEC.encode(
                new Plans.Ref("p9", Optional.of(LuaCodecs.encode(wrongFirst)))));
        assertThrows(IllegalArgumentException.class, () -> WalkCommit.restored(wrongProgress, true,
                new Plan("p9", Description.of(saved.remaining()), BlockPos.ZERO, List.of())));
        assertThrows(IllegalArgumentException.class, () -> WalkCommit.restored(new Plans.Ref("p9"), true, wrong));
        assertTrue(saved.inRealm(saved.worldId(), saved.dimension()));
        assertFalse(saved.inRealm("64fa8be8-1000-4000-8000-000000000002", saved.dimension()));
        assertFalse(saved.inRealm(saved.worldId(), ResourceLocation.parse("minecraft:the_nether")));
    }

    @Test
    void remainingStopsDoNotReturnToCompletedWaypointsAndKeepAllConstraints() {
        WalkCommit saved = commit();
        Description remaining = Description.of(saved.remaining());
        assertEquals(List.of(Description.of(saved.spec()).stops().get(1)), remaining.stops());
        assertEquals(saved.spec().mode(), saved.remaining().mode());
        assertEquals(saved.spec().allow(), saved.remaining().allow());
        assertEquals(saved.spec().costs(), saved.remaining().costs());
        assertEquals(saved.spec().avoid(), saved.remaining().avoid());
        assertEquals(saved.spec().avoidBreak(), saved.remaining().avoidBreak());
        assertEquals(saved.spec().avoidPlace(), saved.remaining().avoidPlace());
        assertEquals(saved.spec().avoidStep(), saved.remaining().avoidStep());
        assertEquals(saved.spec().materials(), saved.remaining().materials());
        assertThrows(IllegalArgumentException.class, () -> saved.at(2).remaining());
    }

    @Test
    void restoredPromiseRejectsExtraDigsPlacesAndAsksAndDoesNotPublishAPlan() {
        WalkCommit saved = commit();
        Plan promise = saved.promise("fresh");
        BlockPos extra = new BlockPos(9, 64, 0);
        Plan fresh = new Plan("fresh", Description.of(saved.remaining()), extra,
                List.of(new Plan.Leg(Description.of(saved.remaining()).stops().getFirst(), null,
                        RouteSpec.defaults(), Plan.Reach.REACHED, null, null,
                        new NavText.Changes(Map.of(extra, Blocks.STONE), Map.of(extra.below(), Blocks.DIRT),
                                Map.of(extra, "placed by a player")), "")));
        Plan.Difference diff = fresh.beyond(promise);
        assertEquals(1, diff.digs().size());
        assertEquals(1, diff.places().size());
        assertEquals(1, diff.asks().size());
        assertTrue(promise.bind(RouteSpec.defaults()).positions().forbids(Use.DIG, extra.asLong()));
        assertTrue(promise.bind(RouteSpec.defaults()).positions().forbids(Use.PLACE, extra.below().asLong()));
        Plans plans = new Plans();
        plans.nextId("original");
        plans.put("original", promise);
        assertNull(plans.get("another", promise.id()));
    }
}
