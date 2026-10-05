package com.dwinovo.numen.plugins.ae2;

import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingLinkNexus;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.Permission;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.Positional;
import com.dwinovo.numen.sdk.ServerCall;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * {@code ae2.craft}:向 AE2 合成 CPU 下单一笔自动合成,并查询它的处境。
 *
 * <p>计算是异步的:先算料(缺料/无 CPU 会如实失败),算完在服务器 tick 里提交给 CPU。产物回 ME 网络,
 * 不进她的背包。缺料、无合适 CPU 都在提交前失败,不消耗原料。查询账本只在这次开服内有效,AE2 的作业本身不受影响。
 *
 * <p>只提交、不搬货;权限按 {@code use_block}/{@code take} 对网络设备那一格判。AE2 不在场时这个类不被加载。
 */
public final class Ae2CraftApi {

    private static final int MAX_REQUESTS = 64;
    private static final Map<UUID, Request> REQUESTS = new LinkedHashMap<>();
    private static NumenApi numen;

    private Ae2CraftApi() {}

    public static void install(NumenApi api) {
        numen = api;
        api.api("craft", "Request AE2 autocrafting server-side and query the job: give a network device, an item "
                + "and a count. Outputs return to ME storage, not your inventory.", Ae2CraftApi.class);
    }

    public record RequestArgs(@Doc("AE2 device block X (a cable-connected block).") int x,
                              @Doc("Block Y.") int y,
                              @Doc("Block Z.") int z,
                              @Doc("Item id to craft, e.g. minecraft:oak_planks.") String item,
                              @Doc("How many EXTRA items to craft (not a stock target).")
                              @Omitted("1") @Positional Optional<Long> count) {}

    public record Submitted(@Doc("Keep it to query this request.") String requestId,
                            @Doc("calculating — the CPU job is not submitted yet.") String state,
                            @Doc("What is happening.") String detail) {}

    public record StatusArgs(@Doc("The request_id from a previous request().") String requestId) {}

    public record Status(@Doc("The request id.") String requestId,
                         @Doc("calculating / submitted / completed / failed / canceled.") String state,
                         @Doc("What is happening; on failure, why.") String detail,
                         @Doc("Item id.") String item,
                         @Doc("Requested count.") long count,
                         @Doc("Plan size in bytes.") long bytes,
                         @Doc("AE2 job id, once submitted.") String jobId,
                         @Doc("Items done so far (submitted).") long progress,
                         @Doc("Total items (submitted).") long total) {}

    @Fn("Request AE2 autocrafting: hand a network device, an item and a count to the crafting CPUs. Returns a "
            + "request_id that is NOT completion — query it with ae2.craft.status.")
    @Example("local r = ae2.craft.request(120, 64, -3, \"minecraft:oak_planks\", 4)\nreturn ae2.craft.status(r.requestId)")
    public static Submitted request(ServerCall call, RequestArgs args) {
        if (args.item() == null || args.item().isBlank()) {
            throw bad("give the item to craft.");
        }
        long count = args.count().orElse(1L);
        if (count <= 0) {
            throw bad("count must be positive.");
        }
        NumenPlayer self = call.her();
        Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(args.item())).orElseThrow(() ->
                bad("unknown item: " + args.item()));
        if (item == Items.AIR) {
            throw bad("cannot craft air.");
        }
        ServerLevel level = self.serverLevel();
        BlockPos pos = new BlockPos(args.x(), args.y(), args.z());
        if (!level.hasChunkAt(pos)) {
            throw bad("the network device's chunk is not loaded.");
        }
        if (self.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0) {
            throw new ApiError(ErrorKind.OUT_OF_REACH, "move within 8 blocks of the network device first.", null);
        }
        requirePermission(self, pos, item);
        IGridNode node = Ae2NetworkApi.resolveNode(level, pos);
        if (node == null || !node.isActive() || node.getGrid() == null) {
            throw bad("that block is not on an active powered AE2 network.");
        }
        AEItemKey key = AEItemKey.of(item);
        var crafting = node.getGrid().getCraftingService();
        if (crafting == null || !crafting.isCraftable(key)) {
            throw bad("no installed pattern for " + args.item() + " on that network.");
        }
        pruneFinished();
        if (REQUESTS.size() >= MAX_REQUESTS) {
            throw bad("too many active requests; query the existing ones first.");
        }
        IActionSource source = IActionSource.ofPlayer(self);
        ICraftingSimulationRequester requester = new ICraftingSimulationRequester() {
            @Override public IActionSource getActionSource() { return source; }
            @Override public IGridNode getGridNode() { return node; }
        };
        Future<ICraftingPlan> calculation = crafting.beginCraftingCalculation(level, requester, key, count,
                CalculationStrategy.REPORT_MISSING_ITEMS);
        Request request = new Request(self, pos, node, key, count, calculation);
        REQUESTS.put(request.id, request);
        return new Submitted(request.id.toString(), request.state, request.detail);
    }

    @Fn("Read the state of a request you started: calculating / submitted / completed / failed / canceled.")
    @Example("local s = ae2.craft.status(\"<request_id>\")\nprint(s.state, s.progress, s.total)")
    public static Status status(ServerCall call, StatusArgs args) {
        Request request = find(call.her(), args.requestId());
        return request.status();
    }

    /** 服务器 tick:算完的提交给 CPU、已提交的看收尾;状态变化发 {@code ae2_craft} 事件。 */
    public static void tick(MinecraftServer server) {
        for (Request request : REQUESTS.values()) {
            if (!request.active()) {
                continue;
            }
            String previous = request.state;
            try {
                request.step(server);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                request.fail("Crafting calculation interrupted; no job submitted.");
            } catch (ExecutionException | RuntimeException failure) {
                request.fail("request_craft failed: " + (failure.getCause() == null ? failure : failure.getCause()));
            }
            if (!previous.equals(request.state) && !request.body.isRemoved() && numen != null) {
                numen.emit(request.body, "ae2_craft",
                        Map.of("request_id", request.id.toString(), "state", request.state),
                        request.status().detail(), true);
            }
        }
    }

    public static void clear() {
        for (Request request : REQUESTS.values()) {
            if (request.state.equals("calculating")) {
                request.calculation.cancel(true);
            }
        }
        REQUESTS.clear();
    }

    // ---- ledger ----

    private static void pruneFinished() {
        Iterator<Request> it = REQUESTS.values().iterator();
        while (it.hasNext() && REQUESTS.size() >= MAX_REQUESTS) {
            if (!it.next().active()) {
                it.remove();
            }
        }
    }

    private static Request find(NumenPlayer self, String rawId) {
        UUID id;
        try {
            id = UUID.fromString(rawId);
        } catch (RuntimeException bad) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "not a request_id: " + rawId, null);
        }
        Request request = REQUESTS.get(id);
        if (request == null || !request.body.getUUID().equals(self.getUUID())) {
            throw new ApiError(ErrorKind.NOT_FOUND,
                    "unknown request_id for this companion (tracking resets on world shutdown)", null);
        }
        return request;
    }

    private static void requirePermission(NumenPlayer body, BlockPos pos, Item item) {
        var state = body.level().getBlockState(pos);
        var use = Permission.judge(body, Action.useBlock(pos, state));
        if (!use.allowed()) {
            throw new ApiError(ErrorKind.DENIED, use.reason(), null);
        }
        var take = Permission.judge(body, Action.take(pos, state, item));
        if (!take.allowed()) {
            throw new ApiError(ErrorKind.DENIED, take.reason(), null);
        }
    }

    private static ApiError bad(String message) {
        return new ApiError(ErrorKind.BAD_ARGUMENT, message, null);
    }

    private static final class Request {
        final UUID id = UUID.randomUUID();
        final NumenPlayer body;
        final ServerLevel level;
        final BlockPos pos;
        final IGridNode node;
        final AEItemKey key;
        final long count;
        final Future<ICraftingPlan> calculation;
        final int started;
        String state = "calculating";
        String detail = "Calculating only; no CPU job has been submitted yet.";
        ICraftingLink link;
        CraftingCPUCluster cpu;
        long bytes;

        Request(NumenPlayer body, BlockPos pos, IGridNode node, AEItemKey key, long count,
                Future<ICraftingPlan> calculation) {
            this.body = body;
            this.level = body.serverLevel();
            this.pos = pos;
            this.node = node;
            this.key = key;
            this.count = count;
            this.calculation = calculation;
            this.started = body.getServer().getTickCount();
        }

        boolean active() {
            return state.equals("calculating") || state.equals("submitted");
        }

        void fail(String why) {
            state = "failed";
            detail = why;
        }

        void step(MinecraftServer server) throws ExecutionException, InterruptedException {
            if (state.equals("calculating")) {
                if (body.isRemoved() || body.level() != level) {
                    calculation.cancel(true);
                    throw new IllegalStateException("companion left the request's level before submission");
                }
                if (!calculation.isDone()) {
                    if (server.getTickCount() - started > 1200) {
                        calculation.cancel(true);
                        throw new IllegalStateException("crafting calculation timed out; no job submitted");
                    }
                    return;
                }
                ICraftingPlan plan = calculation.get();
                bytes = plan.bytes();
                if (plan.simulation()) {
                    StringBuilder missing = new StringBuilder("Missing ingredients; no job submitted: ");
                    for (var entry : plan.missingItems()) {
                        missing.append(entry.getKey()).append(" x").append(entry.getLongValue()).append("; ");
                    }
                    throw new IllegalStateException(missing.toString());
                }
                requirePermission(body, pos, key.getItem());
                if (!level.hasChunkAt(pos) || Ae2NetworkApi.resolveNode(level, pos) != node
                        || !node.isActive() || node.getGrid() == null) {
                    throw new IllegalStateException("network device changed or went offline before submission");
                }
                var source = IActionSource.ofPlayer(body);
                var crafting = node.getGrid().getCraftingService();
                CraftingCPUCluster chosen = crafting.getCpus().stream()
                        .map(c -> (CraftingCPUCluster) c)
                        .filter(c -> c.isActive() && !c.isBusy() && c.getAvailableStorage() >= plan.bytes()
                                && c.canBeAutoSelectedFor(source))
                        .sorted(java.util.Comparator.comparing((CraftingCPUCluster c) -> !c.isPreferredFor(source))
                                .thenComparingInt(CraftingCPUCluster::getCoProcessors)
                                .thenComparingLong(CraftingCPUCluster::getAvailableStorage))
                        .findFirst().orElse(null);
                if (chosen == null) {
                    throw new IllegalStateException(crafting.getCpus().isEmpty()
                            ? "NO_CPU_FOUND" : "NO_SUITABLE_CPU_FOUND (busy, storage too small, or auto-selection disabled)");
                }
                var result = crafting.submitJob(plan, null, chosen, false, source);
                if (!result.successful()) {
                    throw new IllegalStateException("AE2 refused submission: " + result.errorCode() + " "
                            + result.errorDetail());
                }
                link = chosen.craftingLogic.getLastLink();
                if (link == null) {
                    throw new IllegalStateException("CPU accepted the job but exposes no job link; inspect the CPU");
                }
                // 独立 link 默认没有 nexus;挂 CPU 端的 nexus 才能收到完成/取消标志。
                ((CraftingLink) link).setNexus(new CraftingLinkNexus(link.getCraftingID()));
                cpu = chosen;
                state = "submitted";
                detail = "CPU accepted the job; not completed. Query it; outputs will return to ME storage.";
            } else if (link.isCanceled()) {
                state = "canceled";
                detail = "AE2 job was canceled; do not treat it as completed.";
            } else if (link.isDone()) {
                state = "completed";
                detail = "AE2 reports this job completed; outputs returned to ME storage, not my inventory.";
            }
        }

        Status status() {
            String jobId = "";
            long progress = 0;
            long total = 0;
            if (link != null) {
                jobId = link.getCraftingID().toString();
            }
            if (cpu != null) {
                var job = cpu.getJobStatus();
                if (job != null) {
                    progress = job.progress();
                    total = job.totalItems();
                }
            }
            return new Status(id.toString(), state, detail, key.getId().toString(), count, bytes, jobId, progress, total);
        }
    }
}
