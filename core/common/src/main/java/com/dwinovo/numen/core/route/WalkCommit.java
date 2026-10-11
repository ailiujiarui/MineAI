package com.dwinovo.numen.core.route;

import com.dwinovo.numen.core.nav.NavText;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.Call;
import com.dwinovo.numen.sdk.LuaCodecs;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 移动重放的描述与承诺,不是搜索路径、许可凭据或跨程序的计划。first 只记已走完的站。 */
public record WalkCommit(Description.Directions spec, BlockPos from, List<CommitCell> digs,
                         List<CommitCell> places, List<CommitAsk> asks, List<CommitAsk> pending,
                         ResourceLocation dimension, String worldId, int first) {

    public record CommitCell(BlockPos pos, Block block) {}
    public record CommitAsk(BlockPos pos, String cause) {}

    public static WalkCommit of(NumenPlayer her, Plan plan) {
        List<CommitCell> digs = new ArrayList<>();
        List<CommitCell> places = new ArrayList<>();
        List<CommitAsk> asks = new ArrayList<>();
        List<CommitAsk> pending = new ArrayList<>();
        plan.legs().forEach(l -> {
            l.changes().digs().forEach((p, b) -> digs.add(new CommitCell(p, b)));
            l.changes().places().forEach((p, b) -> places.add(new CommitCell(p, b)));
            l.changes().asks().forEach((p, why) -> asks.add(new CommitAsk(p, why)));
            l.changes().pending().forEach((p, why) -> pending.add(new CommitAsk(p, why)));
        });
        return new WalkCommit(plan.description().written(), plan.from(), digs, places, asks, pending,
                her.serverLevel().dimension().location(),
                com.dwinovo.numen.entity.CompanionRegistry.get(her.getServer()).worldId(), 0);
    }

    public WalkCommit at(int first) {
        return new WalkCommit(spec, from, digs, places, asks, pending, dimension, worldId, first);
    }

    public boolean inRealm(NumenPlayer her) {
        return inRealm(com.dwinovo.numen.entity.CompanionRegistry.get(her.getServer()).worldId(),
                her.serverLevel().dimension().location());
    }

    public boolean inRealm(String world, ResourceLocation dimension) {
        return worldId.equals(world) && this.dimension.equals(dimension);
    }

    /** 普通 Lua 的附加字段不参与描述或承诺;只有带服务端来源凭据的调用才解码。 */
    public static WalkCommit restored(Plans.Ref ref, boolean replay, Plan plan) {
        if (!replay) {
            return null;
        }
        WalkCommit commit = ref.commit().map(v -> (WalkCommit) LuaCodecs.of(WalkCommit.class, "numen").decode(v))
                .orElseThrow(() -> new IllegalArgumentException("the saved walk has no original commit"));
        if (!LuaCodecs.encode(commit.remaining()).equals(LuaCodecs.encode(plan.description().written()))) {
            throw new IllegalArgumentException("the replayed plan description does not match the saved remaining stops");
        }
        return commit;
    }

    /** 全部描述保存在 spec;重放只规划当前段及其后的站,不回头访问已完成的站。 */
    public Description.Directions remaining() {
        Description d = Description.of(spec);
        if (first < 0 || first >= d.stops().size()) {
            throw new IllegalArgumentException("the saved walk's current leg is outside its stops");
        }
        return new Description.Directions(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(d.stops().subList(first, d.stops().size())), spec.mode(), spec.costs(), spec.avoid(),
                spec.allow(), spec.avoidBreak(), spec.avoidPlace(), spec.avoidStep(), spec.materials());
    }

    /** 只还原用于比较和绑定的承诺,没有可直接执行的路线。 */
    public Plan promise(String id) {
        Description d = Description.of(spec);
        Map<BlockPos, Block> breaks = new LinkedHashMap<>();
        Map<BlockPos, Block> puts = new LinkedHashMap<>();
        Map<BlockPos, String> questions = new LinkedHashMap<>();
        Map<BlockPos, String> withheld = new LinkedHashMap<>();
        digs.forEach(c -> breaks.put(c.pos(), c.block()));
        places.forEach(c -> puts.put(c.pos(), c.block()));
        asks.forEach(c -> questions.put(c.pos(), c.cause()));
        pending.forEach(c -> withheld.put(c.pos(), c.cause()));
        NavText.Changes changes = new NavText.Changes(breaks, puts, questions, withheld);
        return new Plan(id, d, from, d.stops().stream().map(s -> new Plan.Leg(s, null, null,
                Plan.Reach.UNPLANNED, null, null, changes, "")).toList());
    }

    public String lua() {
        LuaCodecs.of(WalkCommit.class, "numen");
        return "local p = " + Call.of("numen.route.plan", remaining()) + "\np.commit = "
                + LuaCodecs.literal(this) + "\nnumen.move.go(p)";
    }
}
