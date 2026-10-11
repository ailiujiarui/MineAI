package com.dwinovo.numen.core.tools.verify;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.core.PlayerInv;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.Positional;
import com.dwinovo.numen.sdk.ServerCall;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

/**
 * {@code numen.verify}:拿一句宣称去量权威状态,给一句确定的判词。
 *
 * <p>"做完了没有"在这里可以机检的那部分上有个便宜、确定的对照:背包里数、某一格是什么方块、附近有没有
 * 某种方块。它读的是服务端的权威状态,不是记忆。三个函数都只读,不动世界,当场返回。
 */
public final class VerifyApi {

    private static final int DEFAULT_NEAR_RADIUS = 16;
    private static final int MAX_NEAR_RADIUS = 32;

    private VerifyApi() {}

    public static void install(NumenApi numen) {
        numen.api("verify", "Check one concrete claim against the authoritative server state: what you actually "
                + "hold, what block is at a spot, whether one is nearby. The ground truth, not your memory.",
                VerifyApi.class);
    }

    /** 一次量出来的判词:{@code holds} 说宣称成不成立,{@code expected}/{@code actual} 说宣称与实际各是什么。 */
    public record Claim(@Doc("Whether the claim holds.") boolean holds,
                        @Doc("What the claim said.") String expected,
                        @Doc("What is really there.") String actual) {}

    public record HaveArgs(@Doc("Item id, e.g. minecraft:iron_ingot or iron_ingot.") String item,
                           @Doc("How many the claim says you should hold.")
                           @Omitted("1") @Positional Optional<Integer> count) {}

    @Fn("How many of an item you actually hold, and whether that is at least the claimed count.")
    @Example("numen.verify.have(\"minecraft:iron_ingot\", 64)")
    public static Claim have(ServerCall call, HaveArgs args) {
        int wanted = args.count().orElse(1);
        Item item = item(args.item());
        int have = PlayerInv.count(call.her().getInventory(), item);
        return new Claim(have >= wanted,
                "have " + wanted + " x " + id(item),
                "you hold " + have + " x " + id(item));
    }

    public record BlockArgs(@Doc("Block id, e.g. minecraft:torch or torch.") String block,
                            @Doc("Block X.") int x,
                            @Doc("Block Y.") int y,
                            @Doc("Block Z.") int z) {}

    @Fn("What block is at the given coordinates, and whether it is the claimed one.")
    @Example("numen.verify.block(\"minecraft:torch\", 120, 64, -3)")
    public static Claim block(ServerCall call, BlockArgs args) {
        Block block = block(args.block());
        BlockPos pos = new BlockPos(args.x(), args.y(), args.z());
        BlockState state = call.her().serverLevel().getBlockState(pos);
        String coord = args.x() + " " + args.y() + " " + args.z();
        return new Claim(state.is(block),
                "block " + id(block) + " at " + coord,
                "at " + coord + " is " + blockId(state));
    }

    public record NearArgs(@Doc("Block id.") String block,
                           @Doc("Search radius in blocks (1-" + MAX_NEAR_RADIUS + ").")
                           @Omitted("" + DEFAULT_NEAR_RADIUS) Optional<Integer> radius) {}

    @Fn("Whether any such block is within the given radius of where you stand.")
    @Example("numen.verify.near(\"minecraft:chest\", {radius = 16})")
    public static Claim near(ServerCall call, NearArgs args) {
        Block block = block(args.block());
        int radius = Math.max(1, Math.min(MAX_NEAR_RADIUS, args.radius().orElse(DEFAULT_NEAR_RADIUS)));
        BlockPos center = call.her().blockPosition();
        var level = call.her().serverLevel();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos at = center.offset(dx, dy, dz);
                    if (level.getBlockState(at).is(block)) {
                        return new Claim(true, "a " + id(block) + " within " + radius + " blocks",
                                "found at " + at.getX() + " " + at.getY() + " " + at.getZ());
                    }
                }
            }
        }
        return new Claim(false, "a " + id(block) + " within " + radius + " blocks",
                "none within " + radius + " blocks");
    }

    // ---- 小工具 ----

    /** 认一个物品 id:没写命名空间按 {@code minecraft} 补,查不到是 {@link ErrorKind#BAD_ARGUMENT}。 */
    private static Item item(String raw) {
        ResourceLocation key = parse(raw);
        return BuiltInRegistries.ITEM.getOptional(key).orElseThrow(() ->
                new ApiError(ErrorKind.BAD_ARGUMENT, "no item '" + raw + "'", null));
    }

    /** 认一个方块 id:口径同 {@link #item}。 */
    private static Block block(String raw) {
        ResourceLocation key = parse(raw);
        return BuiltInRegistries.BLOCK.getOptional(key).orElseThrow(() ->
                new ApiError(ErrorKind.BAD_ARGUMENT, "no block '" + raw + "'", null));
    }

    private static ResourceLocation parse(String raw) {
        return raw != null && raw.indexOf(':') >= 0 ? ResourceLocation.parse(raw)
                : ResourceLocation.withDefaultNamespace(raw == null ? "" : raw);
    }

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static String id(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }
}
