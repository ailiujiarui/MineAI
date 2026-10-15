package com.dwinovo.numen.pathing.gametest;

import com.dwinovo.numen.api.gametest.TerrainWorld;
import com.dwinovo.numen.pathing.api.NavRequest;
import com.dwinovo.numen.pathing.api.NavStatus;
import com.dwinovo.numen.pathing.api.Navigation;
import com.dwinovo.numen.pathing.api.Navigator;
import com.dwinovo.numen.pathing.api.Ports;
import com.dwinovo.numen.pathing.api.Report;
import com.dwinovo.numen.pathing.drive.EditLedger;
import com.dwinovo.numen.pathing.drive.Journal;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

/**
 * 在固定存档上走一条固定路线({@link TerrainRoutes.Route}),跑完记一行 JSON 到日志({@code [terrain-route] {...}}),
 * 这份记录是寻路优化的基线,数据全取自寻路交出的行程报告({@link Report})。成败只看到没到、超没超时;其余字段只记录、不断言。
 */
final class TerrainRun {

    private static final Logger LOG = LogUtils.getLogger();
    /** 走路的规格:可挖可搭、只看价钱,与 numen 里模型走路用的出厂规格同一口径(身上没有垫路的料)。 */
    private static final RouteSpec WALKING = RouteSpec.defaults().edit().changes(true).build();
    /** 汇总表要等所有路线都跑完;每条跑完时登记一行。 */
    private static final List<JsonObject> DONE = new ArrayList<>();

    private enum Phase { LOADING, SPAWNED, WALKING, FINISHED }

    private final GameTestHelper helper;
    private final TerrainRoutes.Route route;
    private final TerrainBlock block;
    private final ServerLevel level;
    private Phase phase = Phase.LOADING;
    private TestBody body;
    private Navigation navigation;
    private int waited;
    private float lowestHealth = Float.MAX_VALUE;
    /** 开跑前挖空的格子原来的样子。 */
    private final Map<BlockPos, BlockState> carved = new LinkedHashMap<>();

    private TerrainRun(GameTestHelper helper, TerrainBlock block, TerrainRoutes.Route route) {
        this.block = block;
        this.helper = helper;
        this.route = route;
        this.level = helper.getLevel().getServer().getLevel(TerrainWorld.DIMENSION);
    }

    /** 开跑:核对路线与存档,压一张票据把存档里路线可能走到的整片区块加载好(路线绕路也不会走进没加载的区块),之后每刻推一步。 */
    static void start(GameTestHelper helper, TerrainRoutes.Route route) {
        TerrainBlock block = TerrainBlock.load();
        block.requireInside(route);
        TerrainRun run = new TerrainRun(helper, block, route);
        block.requireFromSave(run.level);
        Worlds.settle(run.level);
        ChunkPos center = block.center();
        run.level.getChunkSource().addRegionTicket(TicketType.FORCED, center, TerrainBlock.LOAD_DISTANCE, center);
        run.level.getChunkSource().setViewDistance(TerrainBlock.BODY_VIEW);
        // 整片区域一次加载完(阻塞到全部 FULL):加载花多少墙钟与刻数都不进路线的账,GameTest 的时限也不被它吃掉
        int r = TerrainBlock.LOAD_RADIUS;
        for (int cx = center.x - r; cx <= center.x + r; cx++) {
            for (int cz = center.z - r; cz <= center.z + r; cz++) {
                run.level.getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
            }
        }
        helper.onEachTick(run::tick);
    }

    private boolean loaded() {
        int r = TerrainBlock.LOAD_RADIUS;
        ChunkPos center = block.center();
        for (int cx = center.x - r; cx <= center.x + r; cx++) {
            for (int cz = center.z - r; cz <= center.z + r; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private void tick() {
        switch (phase) {
            case LOADING -> {
                if (loaded()) {
                    BlockPos from = route.from();
                    for (TerrainRoutes.Box box : route.carve()) {
                        for (BlockPos cell : BlockPos.betweenClosed(box.min(), box.max())) {
                            carved.put(cell.immutable(), level.getBlockState(cell));
                            level.setBlock(cell, Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                    if (route.buried() && !sealedInRock(route.to())) {
                        helper.fail("路线常量过期:终点 " + route.to().toShortString() + " 不是封闭在实心方块里的一格");
                        restore();
                        phase = Phase.FINISHED;
                        return;
                    }
                    if (level.getBlockState(from.below()).isAir()) {
                        helper.fail("路线常量过期:起点 " + from.toShortString() + " 脚下是空气");
                        restore();
                        phase = Phase.FINISHED;
                        return;
                    }
                    body = TestBody.spawn(level, route.name(), from.getX() + 0.5, from.getY(), from.getZ() + 0.5);
                    body.getFoodData().setFoodLevel(20);
                    phase = Phase.SPAWNED;
                }
            }
            case SPAWNED -> {
                // 出生无敌期里摔落不掉血,等过了再走,记的最低血量才有意义
                if (++waited > Trial.SPAWN_INVULNERABILITY) {
                    Navigator navigator = Navigator.of(body, new Ports(Trial.ALLOW_ALL, Trial.NO_MATERIALS, Threats.NONE));
                    navigation = navigator.drive(NavRequest.to(route.goal(), WALKING));
                    phase = Phase.WALKING;
                }
            }
            case WALKING -> walk();
            case FINISHED -> {
            }
        }
    }

    /**
     * 把这条路线动过的格子还原(开跑前挖空的,和导航挖过、放过、开关过的)。各路线共用这一个地形维度,不还原的话,后跑的路线读到的是
     * 被前面的路线挖过的世界,结果就随批次的先后变。
     */
    private void restore() {
        if (navigation != null) {
            List<EditLedger.Entry> entries = navigation.report().ledger().entries();
            for (int i = entries.size() - 1; i >= 0; i--) {
                BlockState before = switch (entries.get(i)) {
                    case EditLedger.Dug d -> d.before();
                    case EditLedger.Placed p -> p.before();
                    case EditLedger.Toggled t -> t.before();
                };
                level.setBlock(entries.get(i).pos(), before, 2);
            }
        }
        carved.forEach((pos, state) -> level.setBlock(pos, state, 2));
    }

    /** 这一格和它上下四面六邻都是实心方块。 */
    private boolean sealedInRock(BlockPos cell) {
        if (!level.getBlockState(cell).isSolid()) {
            return false;
        }
        for (Direction side : Direction.values()) {
            if (!level.getBlockState(cell.relative(side)).isSolid()) {
                return false;
            }
        }
        return true;
    }

    private void walk() {
        NavStatus status = navigation.tick();
        while (navigation.waiting()) {
            nap();
            status = navigation.tick();
        }
        lowestHealth = Math.min(lowestHealth, body.getHealth());
        boolean overdue = status.running() && navigation.report().ticks() >= route.limit();
        if (status.running() && !overdue) {
            return;
        }
        phase = Phase.FINISHED;
        if (overdue) {
            navigation.stop();
        }
        JsonObject record = record(status, overdue);
        LOG.info("[terrain-route] {}", record);
        body.leave();
        restore();
        summarize(record);
        if (status.state() == NavStatus.State.ARRIVED) {
            helper.succeed();
        } else {
            helper.fail((overdue ? "超时 " + route.limit() + " 刻没走到:" : "没走到:") + status.state() + " " + status.outcome()
                    + ",身体在 " + body.blockPosition().toShortString());
        }
    }

    private JsonObject record(NavStatus status, boolean overdue) {
        Report report = navigation.report();
        Journal journal = report.journal();
        int dug = 0;
        int placed = 0;
        int toggled = 0;
        for (EditLedger.Entry e : report.ledger().entries()) {
            switch (e) {
                case EditLedger.Dug d -> dug++;
                case EditLedger.Placed p -> placed++;
                case EditLedger.Toggled t -> toggled++;
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("route", route.name());
        o.addProperty("kind", route.kind());
        o.addProperty("from", route.from().toShortString());
        o.addProperty("to", route.to().toShortString());
        o.addProperty("distance", Math.round(route.distance() * 10) / 10.0);
        o.addProperty("rise", route.rise());
        o.addProperty("arrived", status.state() == NavStatus.State.ARRIVED);
        o.addProperty("state", overdue ? "TIMEOUT" : status.state().name());
        o.addProperty("outcome", status.outcome() == null ? "" : status.outcome().toString());
        o.addProperty("ticks", report.ticks());
        o.addProperty("limit", route.limit());
        o.addProperty("searches", journal.searches());
        o.addProperty("budgetStops", journal.budgetStops());
        JsonArray expansions = new JsonArray();
        journal.expansions().forEach(expansions::add);
        o.add("expansions", expansions);
        o.addProperty("expanded", journal.expansions().stream().mapToInt(Integer::intValue).sum());
        o.addProperty("replans", journal.replans());
        o.addProperty("blocked", journal.blockages());
        o.addProperty("stuck", journal.stuck());
        o.addProperty("rechecks", journal.rechecks());
        o.addProperty("steps", report.ledger().steps());
        o.addProperty("walked", report.ledger().walked().toString());
        JsonObject timings = new JsonObject();
        journal.timings().forEach((kind, t) -> {
            JsonObject one = new JsonObject();
            one.addProperty("steps", t.steps());
            one.addProperty("ticks", t.ticks());
            one.addProperty("estimated", Math.round(t.estimated() * 10) / 10.0);
            timings.add(kind.name(), one);
        });
        o.add("timings", timings);
        o.addProperty("dug", dug);
        o.addProperty("placed", placed);
        o.addProperty("toggled", toggled);
        o.addProperty("lowestHealth", lowestHealth);
        o.addProperty("endedAt", body.blockPosition().toShortString());
        return o;
    }

    /** 每条跑完登记一行,全部路线都登记了就打一张汇总表。 */
    private static void summarize(JsonObject record) {
        synchronized (DONE) {
            DONE.add(record);
            if (DONE.size() < TerrainRoutes.ALL.size()) {
                return;
            }
            LOG.info("[terrain-table] {}", String.format("%-14s %-8s %6s %5s %5s %6s %6s %6s %7s %7s %7s %6s %5s %5s", "route", "arrived",
                    "dist", "rise", "limit", "ticks", "search", "budget", "expand", "replan", "blocked", "stuck", "steps", "hp"));
            for (JsonObject o : DONE) {
                LOG.info("[terrain-table] {}", String.format("%-14s %-8s %6.1f %5d %5d %6d %6d %6d %7d %7d %7d %6d %5d %5.1f",
                        o.get("route").getAsString(), o.get("state").getAsString(), o.get("distance").getAsDouble(),
                        o.get("rise").getAsInt(), o.get("limit").getAsInt(), o.get("ticks").getAsInt(),
                        o.get("searches").getAsInt(), o.get("budgetStops").getAsInt(), o.get("expanded").getAsInt(), o.get("replans").getAsInt(), o.get("blocked").getAsInt(),
                        o.get("stuck").getAsInt(), o.get("steps").getAsInt(), o.get("lowestHealth").getAsFloat()));
            }
            DONE.clear();
        }
    }

    private static void nap() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
