package com.dwinovo.numen.plugins.ae2;

import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingLinkNexus;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.Permission;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** AE2 的异步合成计算与独立 CPU 作业;产物回 ME 网络,不直接交给同伴。 */
public final class Ae2CraftTool implements NumenTool {
    private static final Gson GSON = new Gson();
    private static final int MAX_REQUESTS = 64;
    private final Map<UUID, Request> requests = new LinkedHashMap<>();
    private final NumenApi numen;

    private record Args(Integer x, Integer y, Integer z, String item, Long count, String request_id) {}

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
        long bytes;

        Request(NumenPlayer body, BlockPos pos, IGridNode node, AEItemKey key, long count,
                Future<ICraftingPlan> calculation) {
            this.body = body;
            this.level = (ServerLevel) body.level();
            this.pos = pos;
            this.node = node;
            this.key = key;
            this.count = count;
            this.calculation = calculation;
            this.started = body.getServer().getTickCount();
        }

        boolean active() { return state.equals("calculating") || state.equals("submitted"); }

        Map<String, Object> data() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("request_id", id.toString());
            data.put("state", state);
            data.put("item", key.getId().toString());
            data.put("count", count);
            data.put("bytes", bytes);
            if (link != null) data.put("job_id", link.getCraftingID().toString());
            return data;
        }

        String result() {
            return (state.equals("failed") || state.equals("canceled")
                    ? TaskResult.fail(detail, data()) : TaskResult.ok(detail, data())).toJson();
        }
    }

    public Ae2CraftTool(NumenApi numen) { this.numen = numen; }

    @Override public String name() { return "request_craft"; }

    @Override public String description() {
        return "Request AE2 autocrafting server-side. Give x/y/z of a nearby loaded AE2 network device, "
                + "item (registry id), and count (default 1, EXTRA items to craft, not a stock target). "
                + "Requires a powered network, an installed pattern, ingredients, and a suitable idle crafting CPU. "
                + "Returns request_id and calculating; this is NOT completion. Query this SAME tool with ONLY "
                + "request_id to read calculating/submitted/completed/failed/canceled. Never repeat a new request "
                + "to poll: it would craft another batch. ae2_craft events report transitions. Outputs go to ME "
                + "storage, not my inventory. Tracking lasts until world shutdown; requests remain AE2 jobs.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalInteger("x", "AE2 device block X; required for a new request.")
                .optionalInteger("y", "AE2 device block Y.")
                .optionalInteger("z", "AE2 device block Z.")
                .optionalString("item", "Item registry id; required for a new request.")
                .optionalInteger("count", "Additional items to craft; positive integer, default 1.")
                .optionalString("request_id", "Query an existing request; omit all new-order arguments.")
                .build();
    }

    @Override public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        try {
            Args a = GSON.fromJson(args, Args.class);
            if (a == null) throw new IllegalArgumentException("arguments are required");
            if (a.request_id != null) {
                if (a.x != null || a.y != null || a.z != null || a.item != null || a.count != null)
                    throw new IllegalArgumentException("query with request_id only; no new-order arguments");
                Request r = requests.get(UUID.fromString(a.request_id));
                if (r == null || !r.body.getUUID().equals(self.getUUID()))
                    throw new IllegalArgumentException("unknown request_id for this companion (tracking resets on world shutdown)");
                reply.accept(r.result());
                return;
            }
            if (a.x == null || a.y == null || a.z == null || a.item == null)
                throw new IllegalArgumentException("new requests require x, y, z and item");
            long count = a.count == null ? 1 : a.count;
            if (count <= 0) throw new IllegalArgumentException("count must be positive");
            var item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(a.item))
                    .orElseThrow(() -> new IllegalArgumentException("unknown item: " + a.item));
            if (item == Items.AIR) throw new IllegalArgumentException("cannot craft air");
            ServerLevel level = (ServerLevel) self.level();
            BlockPos pos = new BlockPos(a.x, a.y, a.z);
            if (!level.hasChunkAt(pos)) throw new IllegalArgumentException("network device chunk is not loaded");
            if (self.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > 64)
                throw new IllegalArgumentException("move within 8 blocks of the network device first");
            requirePermission(self, pos, item);
            IGridNode node = Ae2NetworkOps.resolveNode(level, pos);
            if (node == null || !node.isActive() || node.getGrid() == null)
                throw new IllegalArgumentException("device is not on an active powered AE2 network");
            AEItemKey key = AEItemKey.of(item);
            var crafting = node.getGrid().getCraftingService();
            if (!crafting.isCraftable(key)) throw new IllegalArgumentException("no installed pattern for " + a.item);
            if (requests.size() >= MAX_REQUESTS) {
                var finished = requests.values().iterator();
                while (finished.hasNext() && requests.size() >= MAX_REQUESTS) {
                    if (!finished.next().active()) finished.remove();
                }
            }
            if (requests.size() >= MAX_REQUESTS) throw new IllegalArgumentException("too many active requests; query existing requests first");
            IActionSource source = IActionSource.ofPlayer(self);
            ICraftingSimulationRequester requester = new ICraftingSimulationRequester() {
                @Override public IActionSource getActionSource() { return source; }
                @Override public IGridNode getGridNode() { return node; }
            };
            Future<ICraftingPlan> calculation = crafting.beginCraftingCalculation(level, requester, key, count,
                    CalculationStrategy.REPORT_MISSING_ITEMS);
            Request r = new Request(self, pos, node, key, count, calculation);
            requests.put(r.id, r);
            reply.accept(r.result());
        } catch (IllegalArgumentException | IllegalStateException e) {
            reply.accept(TaskResult.fail("request_craft: " + e.getMessage()).toJson());
        }
    }

    private static void requirePermission(NumenPlayer body, BlockPos pos, net.minecraft.world.item.Item item) {
        var state = body.level().getBlockState(pos);
        var use = Permission.judge(body, Action.useBlock(pos, state));
        if (!use.allowed()) throw new IllegalArgumentException(use.reason());
        var take = Permission.judge(body, Action.take(pos, state, item));
        if (!take.allowed()) throw new IllegalArgumentException(take.reason());
    }

    /** Future 只在完成后取值;提交与读 link 都在服务器线程,不阻塞 tick。 */
    public void tick(MinecraftServer server) {
        for (Request r : requests.values()) {
            if (!r.active()) continue;
            String previous = r.state;
            try {
                if (r.state.equals("calculating")) {
                    if (r.body.isRemoved() || r.body.level() != r.level) {
                        r.calculation.cancel(true);
                        throw new IllegalStateException("companion left the request's level before submission");
                    }
                    if (!r.calculation.isDone()) {
                        if (server.getTickCount() - r.started > 1200) {
                            r.calculation.cancel(true);
                            throw new IllegalStateException("crafting calculation timed out; no job submitted");
                        }
                        continue;
                    }
                    ICraftingPlan plan = r.calculation.get();
                    r.bytes = plan.bytes();
                    if (plan.simulation()) {
                        StringBuilder missing = new StringBuilder("Missing ingredients; no job submitted: ");
                        for (var entry : plan.missingItems())
                            missing.append(entry.getKey()).append(" x").append(entry.getLongValue()).append("; ");
                        throw new IllegalStateException(missing.toString());
                    }
                    requirePermission(r.body, r.pos, r.key.getItem());
                    if (!r.level.hasChunkAt(r.pos) || Ae2NetworkOps.resolveNode(r.level, r.pos) != r.node
                            || !r.node.isActive() || r.node.getGrid() == null)
                        throw new IllegalStateException("network device changed or went offline before submission");
                    // 独立作业的 submit result 不返回 requester link;必须从实际选中的 CPU 取作业 link。
                    var source = IActionSource.ofPlayer(r.body);
                    var crafting = r.node.getGrid().getCraftingService();
                    CraftingCPUCluster cpu = crafting.getCpus().stream()
                            .map(c -> (CraftingCPUCluster) c)
                            .filter(c -> c.isActive() && !c.isBusy() && c.getAvailableStorage() >= plan.bytes()
                                    && c.canBeAutoSelectedFor(source))
                            .sorted(java.util.Comparator.comparing((CraftingCPUCluster c) -> !c.isPreferredFor(source))
                                    .thenComparingInt(CraftingCPUCluster::getCoProcessors)
                                    .thenComparingLong(CraftingCPUCluster::getAvailableStorage))
                            .findFirst().orElse(null);
                    if (cpu == null) throw new IllegalStateException(crafting.getCpus().isEmpty()
                            ? "NO_CPU_FOUND" : "NO_SUITABLE_CPU_FOUND (busy, storage too small, or auto-selection disabled)");
                    var result = crafting.submitJob(plan, null, cpu, false, source);
                    if (!result.successful()) throw new IllegalStateException("AE2 refused submission: "
                            + result.errorCode() + " " + result.errorDetail());
                    r.link = cpu.craftingLogic.getLastLink();
                    if (r.link == null) throw new IllegalStateException("CPU accepted job but exposes no job link; inspect CPU before retrying");
                    // AE2 独立 link 默认没有 nexus,markDone 不记完成标志。只挂 CPU 端的 nexus
                    // 保留终端作业的回库语义(insert 没有 requester 仍返回 0),同时接收完成/取消标志。
                    ((CraftingLink) r.link).setNexus(new CraftingLinkNexus(r.link.getCraftingID()));
                    r.state = "submitted";
                    r.detail = "CPU accepted the job; not completed. Query request_id; outputs will return to ME storage.";
                } else if (r.link.isCanceled()) {
                    r.state = "canceled";
                    r.detail = "AE2 job was canceled; do not treat it as completed.";
                } else if (r.link.isDone()) {
                    r.state = "completed";
                    r.detail = "AE2 reports this job completed; outputs returned to ME storage, not my inventory.";
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                r.state = "failed";
                r.detail = "Crafting calculation interrupted; no job submitted.";
            } catch (ExecutionException | RuntimeException e) {
                r.state = "failed";
                r.detail = "request_craft failed: " + (e.getCause() == null ? e : e.getCause());
            }
            if (!previous.equals(r.state) && !r.body.isRemoved())
                numen.emit(r.body, "ae2_craft", Map.of("request_id", r.id.toString(), "state", r.state),
                        r.result(), true);
        }
    }

    public void clear() {
        for (Request r : requests.values()) if (r.state.equals("calculating")) r.calculation.cancel(true);
        requests.clear();
    }
}
