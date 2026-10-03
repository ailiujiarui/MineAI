package com.dwinovo.numen.core.eval;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.eval.WorldEvalObservers;
import com.dwinovo.numen.permission.PlacedBlocks;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 服务端事实观察:不派任务、不改世界,也不把评分进度喂给模型。 */
public final class SurvivalWorldObserver implements WorldEvalObservers.Observer {
    private static final int RADIUS = 24;
    private static final int HEIGHT = 8;
    private static final int MAX_HARVESTS = 256;
    private static final int ROOM_RADIUS = 8;
    private static final int MAX_ROOM_CELLS = 64;
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
    private record Cell(ResourceKey<Level> dimension, BlockPos pos) {}
    private final Map<Cell, Long> harvested = new LinkedHashMap<>();
    private final Set<Cell> replanted = new HashSet<>();

    @Override
    public void broken(NumenPlayer body, BlockPos pos, BlockState previous) {
        if (!previous.is(Blocks.WHEAT) || !((CropBlock) Blocks.WHEAT).isMaxAge(previous)) return;
        ServerLevel level = body.serverLevel();
        // 查询真实空格会清掉种植者的旧记号;之后必须有一次新的种植记录。
        PlacedBlocks.of(level).placerAt(pos, level.getBlockState(pos));
        Cell cell = new Cell(level.dimension(), pos.immutable());
        harvested.put(cell, level.getGameTime());
        while (harvested.size() > MAX_HARVESTS) {
            Cell oldest = harvested.keySet().iterator().next();
            harvested.remove(oldest);
            replanted.remove(oldest);
        }
    }

    @Override
    public JsonObject observe(NumenPlayer body) {
        ServerLevel level = body.serverLevel();
        long tick = level.getGameTime();
        JsonObject facts = scanNearby(body);
        JsonObject evidence = facts.getAsJsonObject("evidence");
        JsonObject shelterEvidence = shelter(level, body.blockPosition());
        evidence.addProperty("scanned_game_tick", tick);
        evidence.addProperty("dimension", level.dimension().location().toString());
        JsonArray food = new JsonArray();
        JsonArray tools = new JsonArray();
        int nutrition = 0;
        boolean pickaxe = false;
        boolean hoe = false;
        for (int slot = 0; slot < body.getInventory().getContainerSize(); slot++) {
            ItemStack stack = body.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            boolean usable = !stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage();
            boolean pick = usable && stack.getItem() instanceof PickaxeItem
                    && stack.isCorrectToolForDrops(Blocks.IRON_ORE.defaultBlockState());
            boolean farm = usable && stack.getItem() instanceof HoeItem;
            pickaxe |= pick;
            hoe |= farm;
            if (pick || farm) tools.add(item(stack));
            int value = safeNutrition(stack);
            if (value > 0) {
                nutrition += value;
                JsonObject entry = item(stack);
                entry.addProperty("nutrition", value);
                food.add(entry);
            }
        }
        facts.addProperty("usable_pickaxe", pickaxe);
        facts.addProperty("usable_hoe", hoe);
        facts.addProperty("food_nutrition", nutrition);
        facts.addProperty("shelter", shelterEvidence.get("valid").getAsBoolean());
        evidence.add("food", food);
        evidence.add("tools", tools);
        evidence.add("shelter", shelterEvidence.deepCopy());
        JsonArray planted = new JsonArray();
        for (var entry : harvested.entrySet()) {
            Cell cell = entry.getKey();
            ServerLevel farmLevel = level.getServer().getLevel(cell.dimension());
            if (farmLevel == null || !farmLevel.hasChunkAt(cell.pos())) continue;
            BlockState state = farmLevel.getBlockState(cell.pos());
            var placer = PlacedBlocks.of(farmLevel).placerAt(cell.pos(), state);
            boolean hers = placer != null && (placer.id().equals(body.getUUID())
                    || placer.id().equals(body.getOwnerUuid()));
            if (hers && state.is(Blocks.WHEAT) && !((CropBlock) Blocks.WHEAT).isMaxAge(state)) replanted.add(cell);
            if (replanted.contains(cell)) {
                JsonObject record = coordinates(cell.pos());
                record.addProperty("dimension", cell.dimension().location().toString());
                record.addProperty("harvested_game_tick", entry.getValue());
                planted.add(record);
            }
        }
        facts.addProperty("replanted_wheat", replanted.size());
        evidence.add("replanted_wheat", planted);
        facts.add("evidence", evidence);
        return facts;
    }

    private JsonObject scanNearby(NumenPlayer body) {
        ServerLevel level = body.serverLevel();
        BlockPos center = body.blockPosition();
        JsonArray crops = new JsonArray();
        JsonArray tables = new JsonArray();
        JsonArray furnaces = new JsonArray();
        int cropCount = 0;
        int tableCount = 0;
        int furnaceCount = 0;
        for (BlockPos cursor : BlockPos.betweenClosed(center.offset(-RADIUS, -HEIGHT, -RADIUS),
                center.offset(RADIUS, HEIGHT, RADIUS))) {
            if (!level.hasChunkAt(cursor)) continue;
            BlockState state = level.getBlockState(cursor);
            if (state.is(Blocks.CRAFTING_TABLE)) {
                tableCount++;
                if (tables.size() < 64) tables.add(coordinates(cursor));
            }
            if (state.is(Blocks.FURNACE)) {
                furnaceCount++;
                if (furnaces.size() < 64) furnaces.add(coordinates(cursor));
            }
            if (!state.is(Blocks.WHEAT) || !growing(level, cursor)) continue;
            cropCount++;
            if (crops.size() >= 64) continue;
            JsonObject record = coordinates(cursor);
            record.addProperty("age", ((CropBlock) Blocks.WHEAT).getAge(state));
            record.addProperty("moisture", level.getBlockState(cursor.below()).getValue(FarmBlock.MOISTURE));
            record.addProperty("brightness", level.getRawBrightness(cursor, 0));
            crops.add(record);
        }
        JsonObject nearby = new JsonObject();
        nearby.addProperty("growing_wheat", cropCount);
        nearby.addProperty("crafting_tables", tableCount);
        nearby.addProperty("furnaces", furnaceCount);
        JsonObject nearbyEvidence = new JsonObject();
        nearbyEvidence.add("scan_origin", coordinates(center));
        nearbyEvidence.add("wheat", crops);
        nearbyEvidence.add("crafting_tables", tables);
        nearbyEvidence.add("furnaces", furnaces);
        nearbyEvidence.addProperty("coordinates_truncated", cropCount > 64 || tableCount > 64 || furnaceCount > 64);
        nearby.add("evidence", nearbyEvidence);
        return nearby;
    }

    private static boolean growing(ServerLevel level, BlockPos pos) {
        BlockState soil = level.getBlockState(pos.below());
        if (!soil.is(Blocks.FARMLAND) || soil.getValue(FarmBlock.MOISTURE) <= 0
                || level.getRawBrightness(pos, 0) < 9) return false;
        for (BlockPos water : BlockPos.betweenClosed(pos.offset(-4, -1, -4), pos.offset(4, 0, 4))) {
            if (level.hasChunkAt(water) && level.getFluidState(water).is(FluidTags.WATER)) return true;
        }
        return false;
    }

    private static int safeNutrition(ItemStack stack) {
        var food = stack.get(DataComponents.FOOD);
        if (food == null || food.nutrition() <= 0 || food.effects().stream().anyMatch(effect ->
                effect.probability() > 0 && effect.effect().getEffect().value().getCategory() == MobEffectCategory.HARMFUL)) return 0;
        var stew = stack.get(DataComponents.SUSPICIOUS_STEW_EFFECTS);
        if (stew != null && stew.effects().stream().anyMatch(effect ->
                effect.effect().value().getCategory() == MobEffectCategory.HARMFUL)) return 0;
        return food.nutrition() * stack.getCount();
    }

    /** 保守识别单层矩形屋/整修洞穴:净空至多64格,半径8,屋顶高2–5格。 */
    private static JsonObject shelter(ServerLevel level, BlockPos origin) {
        JsonObject out = new JsonObject();
        out.addProperty("valid", false);
        out.add("position", coordinates(origin));
        if (!clear(level, origin) || !clear(level, origin.above())) return fail(out, "body_space_obstructed");
        Set<BlockPos> cells = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        cells.add(origin);
        queue.add(origin);
        while (!queue.isEmpty()) {
            BlockPos at = queue.remove();
            if (Math.abs(at.getX() - origin.getX()) > ROOM_RADIUS || Math.abs(at.getZ() - origin.getZ()) > ROOM_RADIUS
                    || cells.size() > MAX_ROOM_CELLS) return fail(out, "open_or_oversize_room");
            for (Direction direction : HORIZONTAL) {
                BlockPos next = at.relative(direction);
                if (clear(level, next) && clear(level, next.above()) && cells.add(next)) queue.add(next);
            }
        }
        boolean square = cells.stream().anyMatch(at -> cells.contains(at.east()) && cells.contains(at.south())
                && cells.contains(at.east().south()));
        if (!square) return fail(out, "no_two_by_two_floor");
        int minX = cells.stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int maxX = cells.stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int minZ = cells.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        int maxZ = cells.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
        int y = origin.getY();
        int roof = y + 2;
        while (roof <= y + 5 && !solid(level, origin.atY(roof))) roof++;
        if (roof > y + 5) return fail(out, "no_solid_roof");
        int minimumLight = 15;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos floor = new BlockPos(x, y - 1, z);
                if (!solid(level, floor) || hazard(level.getBlockState(floor))) return fail(out, "unsafe_floor");
                if (!solid(level, new BlockPos(x, roof, z))) return fail(out, "roof_gap");
                for (int height = y; height < roof; height++) {
                    if (hazard(level.getBlockState(new BlockPos(x, height, z)))) return fail(out, "interior_hazard");
                }
                BlockPos stance = floor.above();
                if (cells.contains(stance)) minimumLight = Math.min(minimumLight, level.getBrightness(LightLayer.BLOCK, stance));
            }
        }
        if (minimumLight <= 0) return fail(out, "no_artificial_light_at_every_stance");
        Set<BlockPos> doors = new HashSet<>();
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) continue;
                BlockPos wall = new BlockPos(x, y, z);
                Direction outside = x < minX ? Direction.WEST : x > maxX ? Direction.EAST
                        : z < minZ ? Direction.NORTH : Direction.SOUTH;
                boolean door = cells.contains(wall.relative(outside.getOpposite()))
                        && usableDoor(level, wall, outside);
                for (int height = y; height < roof; height++) {
                    if (door && height <= y + 1) continue;
                    if (!solid(level, wall.atY(height))) return fail(out, "wall_gap_or_open_door");
                }
                if (door) doors.add(wall);
            }
        }
        if (doors.isEmpty()) return fail(out, "no_closed_door_to_outdoors");
        AABB room = new AABB(minX, y, minZ, maxX + 1, roof, maxZ + 1);
        if (!level.getEntitiesOfClass(Mob.class, room, mob -> mob.isAlive()
                && mob.getType().getCategory() == MobCategory.MONSTER).isEmpty()) return fail(out, "hostile_inside");
        out.addProperty("valid", true);
        out.addProperty("reason", "enclosed_lit_room_with_exit");
        out.addProperty("walkable_cells", cells.size());
        out.addProperty("roof_y", roof);
        out.addProperty("minimum_block_light", minimumLight);
        out.add("min", coordinates(new BlockPos(minX, y, minZ)));
        out.add("max", coordinates(new BlockPos(maxX, roof - 1, maxZ)));
        JsonArray exits = new JsonArray();
        doors.stream().sorted(java.util.Comparator.comparingLong(BlockPos::asLong)).forEach(door -> exits.add(coordinates(door)));
        out.add("doors", exits);
        return out;
    }

    private static boolean usableDoor(ServerLevel level, BlockPos at, Direction outside) {
        BlockState lower = level.getBlockState(at);
        BlockState upper = level.getBlockState(at.above());
        if (!(lower.getBlock() instanceof DoorBlock door) || !door.type().canOpenByHand()
                || lower.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER || lower.getValue(DoorBlock.OPEN)
                || lower.getValue(DoorBlock.FACING).getAxis() != outside.getAxis()
                || !upper.is(lower.getBlock()) || upper.getValue(DoorBlock.HALF) != DoubleBlockHalf.UPPER
                || upper.getValue(DoorBlock.OPEN)) return false;
        BlockPos exit = at.relative(outside);
        return clear(level, exit) && clear(level, exit.above()) && solid(level, exit.below())
                && !hazard(level.getBlockState(exit.below())) && level.canSeeSky(exit);
    }

    private static boolean solid(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return false;
        BlockState state = level.getBlockState(pos);
        return state.getFluidState().isEmpty() && state.isCollisionShapeFullBlock(level, pos);
    }

    private static boolean clear(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return false;
        BlockState state = level.getBlockState(pos);
        return !hazard(state) && state.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean hazard(BlockState state) {
        return !state.getFluidState().isEmpty() || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.POINTED_DRIPSTONE);
    }

    private static JsonObject fail(JsonObject evidence, String reason) {
        evidence.addProperty("reason", reason);
        return evidence;
    }

    private static JsonObject item(ItemStack stack) {
        JsonObject item = new JsonObject();
        item.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        item.addProperty("count", stack.getCount());
        if (stack.isDamageableItem()) item.addProperty("remaining_durability", stack.getMaxDamage() - stack.getDamageValue());
        return item;
    }

    private static JsonObject coordinates(BlockPos pos) {
        JsonObject out = new JsonObject();
        JsonArray xyz = new JsonArray();
        xyz.add(pos.getX());
        xyz.add(pos.getY());
        xyz.add(pos.getZ());
        out.add("position", xyz);
        return out;
    }
}
