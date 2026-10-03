package com.dwinovo.numen.client.eval;

import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.eval.WorldEvalObservers;
import com.dwinovo.numen.permission.Mode;
import com.dwinovo.numen.permission.Permission;
import com.dwinovo.numen.permission.PermissionStore;
import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.Verdict;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.task.TaskRecord;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.PlayerRespawnLogic;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stats;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Fresh, isolated evaluation worlds; setup and observations never pass through the model's tools. */
public final class LiveEvalWorld {
    private static volatile Path createdWorld;
    private static UUID subject;
    private static ServerStatsCounter stats;

    private LiveEvalWorld() {}

    /** Client-thread entry. A fresh directory is reserved before Minecraft opens it. */
    public static void create(Minecraft mc, String worldName, long seed, String preset) {
        if (createdWorld != null || mc.level != null || mc.getSingleplayerServer() != null) {
            throw new IllegalStateException("Evaluation must create its own fresh world from the title screen");
        }
        if (!worldName.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Evaluation world name must be a single portable directory name");
        }
        var worldPreset = switch (preset) {
            case "flat" -> WorldPresets.FLAT;
            case "normal" -> WorldPresets.NORMAL;
            default -> throw new IllegalArgumentException("Unknown evaluation world preset: " + preset);
        };
        Path saves = mc.getLevelSource().getBaseDir().toAbsolutePath().normalize();
        Path target = saves.resolve(worldName).normalize();
        if (!target.getParent().equals(saves)) {
            throw new IllegalArgumentException("Evaluation world must be directly inside the saves directory");
        }
        try {
            Files.createDirectories(saves);
            Files.createDirectory(target);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot reserve fresh evaluation world; existing saves are never reused", e);
        }
        createdWorld = target;
        LevelSettings settings = new LevelSettings(worldName, GameType.SURVIVAL, false,
                Difficulty.NORMAL, false, new GameRules(), WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(worldName, settings, new WorldOptions(seed, true, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(worldPreset)
                        .value().createWorldDimensions(), mc.screen);
    }

    /** Server-thread entry, exactly once and before the objective is delivered. */
    public static UUID setup(MinecraftServer server, UUID ownerId, JsonObject spec) {
        requireWorld(server);
        if (subject != null) throw new IllegalStateException("Evaluation world has already been prepared");
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
        if (owner == null || owner instanceof NumenPlayer) {
            throw new IllegalStateException("Evaluation owner is not connected");
        }
        ServerLevel level = server.overworld();
        JsonObject setup = spec.getAsJsonObject("setup");
        if (setup == null) throw new IllegalArgumentException("Scenario must contain setup");
        server.setDifficulty(Difficulty.NORMAL, true);
        if (setup.has("fixed_time")) {
            level.setDayTime(setup.get("fixed_time").getAsLong());
            server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        }
        if (setup.has("floor")) {
            JsonObject floor = setup.getAsJsonObject("floor");
            BlockPos from = blockPos(floor.getAsJsonArray("from"));
            BlockPos to = blockPos(floor.getAsJsonArray("to"));
            long volume = Math.multiplyExact(Math.multiplyExact(Math.abs((long) to.getX() - from.getX()) + 1,
                    Math.abs((long) to.getY() - from.getY()) + 1), Math.abs((long) to.getZ() - from.getZ()) + 1);
            if (volume > 32768) throw new IllegalArgumentException("Evaluation floor exceeds 32768 blocks");
            Block block = block(floor.get("block").getAsString());
            for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
                place(level, pos, block);
            }
        }
        if (setup.has("blocks")) {
            for (JsonElement element : setup.getAsJsonArray("blocks")) {
                JsonObject entry = element.getAsJsonObject();
                place(level, blockPos(entry.getAsJsonArray("pos")), block(entry.get("block").getAsString()));
            }
        }
        Vec3 spawn = setup.has("spawn") ? vector(setup.getAsJsonArray("spawn")) : surfaceSpawn(level);
        if (level.isOutsideBuildHeight(BlockPos.containing(spawn))) {
            throw new IllegalArgumentException("Evaluation spawn is outside world build height");
        }
        owner.setGameMode(GameType.SPECTATOR);
        owner.teleportTo(spawn.x, spawn.y + 4, spawn.z);
        NumenPlayer body = Companions.summon(server, ownerId, "EvalBot", level, spawn);
        subject = body.getUUID();
        body.teleportTo(spawn.x, spawn.y, spawn.z);
        body.setGameMode(GameType.SURVIVAL);
        body.getInventory().clearContent();
        if (setup.has("inventory")) {
            for (var entry : setup.getAsJsonObject("inventory").entrySet()) {
                ResourceLocation id = ResourceLocation.parse(entry.getKey());
                if (!BuiltInRegistries.ITEM.containsKey(id)) {
                    throw new IllegalArgumentException("Unknown setup item: " + id);
                }
                Item item = BuiltInRegistries.ITEM.get(id);
                if (new ItemStack(item).isEmpty()) throw new IllegalArgumentException("Setup item cannot be empty: " + id);
                int count = entry.getValue().getAsInt();
                if (count < 0 || count > 2304) throw new IllegalArgumentException("Invalid setup item count: " + count);
                while (count > 0) {
                    ItemStack stack = new ItemStack(item, Math.min(count, new ItemStack(item).getMaxStackSize()));
                    count -= stack.getCount();
                    body.getInventory().add(stack);
                    if (!stack.isEmpty()) throw new IllegalArgumentException("Setup inventory does not fit");
                }
            }
        }
        body.setHealth(body.getMaxHealth());
        body.getFoodData().setFoodLevel(20);
        body.getFoodData().setSaturation(5);
        Permission.setMode(body, Mode.ASK);
        PermissionStore rules = PermissionStore.of(server, ownerId);
        rules.add(Verdict.Kind.DENY, Rule.parse("command(!numen)"));
        // The isolated world belongs to the subject. Its own workstations and discarded items need no human consent.
        for (String rule : new String[]{"break(*)", "place(*)", "drop(*)"}) {
            rules.add(Verdict.Kind.ALLOW, Rule.parse(rule));
        }
        stats = body.getStats();
        if (stats.getValue(Stats.CUSTOM.get(Stats.DEATHS)) != 0
                || stats.getValue(Stats.ENTITY_KILLED.get(EntityType.ENDER_DRAGON)) != 0) {
            throw new IllegalStateException("Fresh evaluation companion already has death or dragon-kill statistics");
        }
        if (spec.has("world_observer")) WorldEvalObservers.start(spec.get("world_observer").getAsString(), body);
        Companions.syncRosterToOwner(server, owner);
        return subject;
    }

    /** Server-thread entry. Vanilla statistics retain attribution across death and body replacement. */
    public static JsonObject snapshot(MinecraftServer server, UUID companion) {
        requireSubject(server, companion);
        NumenPlayer body = NumenPlayer.findByUuid(server, companion);
        if (body != null) stats = body.getStats();
        JsonObject result = new JsonObject();
        result.addProperty("alive", body != null && body.isAlive());
        result.addProperty("game_tick", server.overworld().getGameTime());
        result.addProperty("dragon_kills", stats.getValue(Stats.ENTITY_KILLED.get(EntityType.ENDER_DRAGON)));
        result.addProperty("deaths", stats.getValue(Stats.CUSTOM.get(Stats.DEATHS)));
        JsonObject inventory = new JsonObject();
        result.add("inventory", inventory);
        if (body == null) {
            for (String field : new String[]{"health", "food", "position", "dimension", "current_task"}) {
                result.add(field, JsonNull.INSTANCE);
            }
            return result;
        }
        result.addProperty("health", body.getHealth());
        result.addProperty("food", body.getFoodData().getFoodLevel());
        result.addProperty("dimension", body.level().dimension().location().toString());
        JsonObject world = WorldEvalObservers.observe(body);
        if (world != null) result.add("world", world);
        JsonArray position = new JsonArray();
        position.add(body.getX());
        position.add(body.getY());
        position.add(body.getZ());
        result.add("position", position);
        Map<String, Integer> counts = new TreeMap<>();
        for (int i = 0; i < body.getInventory().getContainerSize(); i++) {
            ItemStack stack = body.getInventory().getItem(i);
            if (!stack.isEmpty()) counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        counts.forEach(inventory::addProperty);
        TaskRecord task = CompanionTickDispatcher.currentTaskFor(companion);
        if (task == null) {
            result.add("current_task", JsonNull.INSTANCE);
        } else {
            JsonObject current = new JsonObject();
            current.addProperty("id", task.publicId());
            current.addProperty("tool", task.getToolName());
            current.addProperty("state", task.getState().name());
            result.add("current_task", current);
        }
        return result;
    }

    /** Cancel the evaluated body's work without deleting the world or its evidence. */
    public static void stop(MinecraftServer server, UUID companion) {
        requireSubject(server, companion);
        NumenPlayer body = NumenPlayer.findByUuid(server, companion);
        if (body != null) CompanionTickDispatcher.cancelFor(body);
        WorldEvalObservers.stop(companion);
    }

    private static void requireWorld(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Evaluation world access must run on the server thread");
        if (createdWorld == null || !createdWorld.equals(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize())) {
            throw new IllegalStateException("Refusing to access a world not freshly created by this evaluation");
        }
    }

    private static void requireSubject(MinecraftServer server, UUID companion) {
        requireWorld(server);
        if (subject == null || !subject.equals(companion)) throw new IllegalArgumentException("Unknown evaluation companion");
    }

    private static Vec3 vector(JsonArray coordinates) {
        if (coordinates == null || coordinates.size() != 3) throw new IllegalArgumentException("Expected three coordinates");
        double x = coordinates.get(0).getAsDouble();
        double y = coordinates.get(1).getAsDouble();
        double z = coordinates.get(2).getAsDouble();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 29_999_984 || Math.abs(y) > 2048 || Math.abs(z) > 29_999_984) {
            throw new IllegalArgumentException("Invalid evaluation coordinates");
        }
        return new Vec3(x, y, z);
    }

    /** Reuse vanilla's heightmap-based dry-land spawn search around the world's shared spawn chunk. */
    private static Vec3 surfaceSpawn(ServerLevel level) {
        ChunkPos origin = new ChunkPos(level.getSharedSpawnPos());
        for (int radius = 0; radius <= 2; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    BlockPos pos = PlayerRespawnLogic.getSpawnPosInChunk(level, new ChunkPos(origin.x + dx, origin.z + dz));
                    if (pos == null || !level.getWorldBorder().isWithinBounds(pos)) continue;
                    var footing = level.getBlockState(pos.below());
                    if (footing.is(Blocks.MAGMA_BLOCK) || footing.is(Blocks.CACTUS)
                            || level.getBlockState(pos).is(BlockTags.FIRE) || level.getBlockState(pos.above()).is(BlockTags.FIRE)
                            || level.getBlockState(pos).is(Blocks.POWDER_SNOW)
                            || !level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.above()).isEmpty()) continue;
                    Vec3 spawn = Vec3.atBottomCenterOf(pos);
                    if (level.noCollision(new AABB(spawn.x - .3, spawn.y, spawn.z - .3,
                            spawn.x + .3, spawn.y + 1.8, spawn.z + .3))) return spawn;
                }
            }
        }
        throw new IllegalStateException("No dry, walkable vanilla spawn found near the world's shared spawn");
    }

    private static BlockPos blockPos(JsonArray coordinates) {
        Vec3 position = vector(coordinates);
        if (position.x != Math.floor(position.x) || position.y != Math.floor(position.y) || position.z != Math.floor(position.z)) {
            throw new IllegalArgumentException("Block coordinates must be integers");
        }
        return BlockPos.containing(position);
    }

    private static Block block(String name) {
        ResourceLocation id = ResourceLocation.parse(name);
        if (!BuiltInRegistries.BLOCK.containsKey(id)) throw new IllegalArgumentException("Unknown setup block: " + id);
        return BuiltInRegistries.BLOCK.get(id);
    }

    private static void place(ServerLevel level, BlockPos pos, Block block) {
        if (level.isOutsideBuildHeight(pos)) throw new IllegalArgumentException("Setup block is outside world build height: " + pos);
        level.setBlockAndUpdate(pos, block.defaultBlockState());
        if (!level.getBlockState(pos).is(block)) throw new IllegalStateException("Setup block was not placed: " + pos);
    }
}
