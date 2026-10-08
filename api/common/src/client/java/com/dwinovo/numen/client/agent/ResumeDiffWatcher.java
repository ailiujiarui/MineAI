package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.resume.ResumeDiff;
import com.dwinovo.numen.agent.resume.WorldSample;
import com.dwinovo.numen.client.data.ClientNumenState;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 主人断线复连时,替她算一份"你不在的这段时间世界变了什么"的回执(见 {@link ResumeDiff})。
 *
 * <h2>锚在离开那一刻</h2>
 * 离开({@code quiesce})时在她脚下采一圈<b>有界的</b>方块指纹(半径 {@link #RADIUS}、步长 {@link #STEP},
 * 只看脚那一层)与背包总数,只存这一小份,不存地形。回来后的第一个 tick(那时连接已经恢复、
 * 身体也解析得到)拿它对一眼当下,变了什么写成一条事件交给模型,随即丢掉指纹——不是第二份世界模型,
 * 只是一次"前后各看一眼"。
 *
 * <h2>帧偏了就不硬算</h2>
 * 她离开时还在服务器里跑,回来可能已经挪窝甚至换了维度。指纹里带着离开时的维度与坐标:
 * 维度不同、或那一格现在没加载,就跳过该格(读不出来不算"变了");她也可能整个走远,
 * 那回执里至少有一句"你现在离离开的地方多少格"。区块重新加载后同一坐标不同内容也会被如实算成变化——
 * 这正是"世界变了"要说的事。
 */
public final class ResumeDiffWatcher {

    /** 采样半径:够近,一眼看得完,也够小,重进时算得起。 */
    static final int RADIUS = 8;
    /** 采样步长:稀疏取格,不读整片。 */
    static final int STEP = 2;

    /** 离开时的指纹;没有 = 没在"离开"状态,不必算。 */
    private WorldSample leave;

    /** 记下离开那一刻的指纹。身体解析不到(不在视野里)就什么也不记。 */
    public void capture(UUID uuid, AbstractClientPlayer body) {
        if (body == null) {
            return;
        }
        Level level = body.level();
        BlockPos center = body.blockPosition();
        Map<String, String> blocks = new LinkedHashMap<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx += STEP) {
            for (int dz = -RADIUS; dz <= RADIUS; dz += STEP) {
                BlockPos p = center.offset(dx, 0, dz);
                if (!level.isLoaded(p)) {
                    continue;   // 没加载的格不采,免得把"空气"当成"当时就是这样"
                }
                blocks.put(p.getX() + "," + p.getY() + "," + p.getZ(), id(level.getBlockState(p)));
            }
        }
        leave = new WorldSample(level.dimension().location().toString(), center.getX(), center.getY(),
                center.getZ(), blocks, totals(uuid));
    }

    /**
     * 回来后的每个 tick 调一次。有一次待算的指纹、身体也解析得到时,算出回执并交回(指纹随即清掉);
     * 否则返回 {@code null}。没差异也返回 {@code null}(空回执不值得进队列)。
     */
    public String resumeReceipt(UUID uuid, AbstractClientPlayer body) {
        WorldSample before = leave;
        if (before == null || body == null) {
            return null;
        }
        Level level = body.level();
        if (!level.dimension().location().toString().equals(before.dimension())) {
            // 换了维度:离开那圈的帧没了,算不了也不该硬套。
            leave = null;
            return null;
        }
        ResumeDiff.Reader reader = (dimension, x, y, z) -> {
            BlockPos p = new BlockPos(x, y, z);
            return level.isLoaded(p) ? id(level.getBlockState(p)) : null;
        };
        BlockPos center = body.blockPosition();
        ResumeDiff.Result result = ResumeDiff.compute(before, totals(uuid),
                center.getX(), center.getY(), center.getZ(), reader);
        leave = null;
        return ResumeDiff.format(result);
    }

    /** 背包里每样东西的总数(合并同类),和 {@code <inventory>} 的口径一致:按 id,不看槽位。 */
    private static Map<String, Integer> totals(UUID uuid) {
        Map<String, Integer> out = new TreeMap<>();
        ClientNumenState.get(uuid).ifPresent(snapshot -> {
            if (!snapshot.loaded()) {
                return;
            }
            for (ItemStack stack : snapshot.items()) {
                if (!stack.isEmpty()) {
                    out.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(),
                            Integer::sum);
                }
            }
        });
        return out;
    }

    private static String id(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }
}
