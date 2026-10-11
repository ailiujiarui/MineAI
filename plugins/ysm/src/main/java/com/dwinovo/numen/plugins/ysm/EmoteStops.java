package com.dwinovo.numen.plugins.ysm;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 给动作收尾:她做过的动作过几秒自己停掉。
 *
 * <h2>为什么非停不可</h2>
 * YSM 里"做动作"就是把玩家的外观切成那套动画。客户端记住"正在做动作"以后,只有<b>本地玩家自己的客户端</b>会把它清掉
 * (主人移动时,或第一人称回到待机时);同伴是服务端假玩家,没有这条客户端路径,服务端自己也不超时。所以不主动停,那身
 * 动作在主人屏幕上就一直卡着。显式的 {@code ysm play <她> stop} 是唯一对所有玩家都生效的收尾,见 {@link Ysm}。
 *
 * <p>计时按服务端刻走(宿主每个 tick 回调一次)。一个人只留最近一次动作的期限:又做了别的动作就按新的算。
 */
final class EmoteStops {

    private final Ysm ysm;

    /** 谁的动作还剩几刻收尾;一个人至多一条,新的覆盖旧的。 */
    private final Map<UUID, Integer> deadlines = new HashMap<>();

    EmoteStops(Ysm ysm) {
        this.ysm = ysm;
    }

    /** 记下 {@code her} 的动作在 {@code ticks} 刻后收尾,覆盖她上一次的期限。 */
    void schedule(ServerPlayer her, int ticks) {
        deadlines.put(her.getUUID(), ticks);
    }

    /** 她主动停了动作(或动作已由别的路径收尾),撤掉待收尾的期限。 */
    void cancel(ServerPlayer her) {
        deadlines.remove(her.getUUID());
    }

    void tick(MinecraftServer server) {
        if (deadlines.isEmpty()) return;
        Iterator<Map.Entry<UUID, Integer>> it = deadlines.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            int left = entry.getValue() - 1;
            if (left > 0) {
                entry.setValue(left);
                continue;
            }
            it.remove();
            // 到点才按 UUID 找在线的她:中途下线或换过身体就什么都不做
            ServerPlayer her = server.getPlayerList().getPlayer(entry.getKey());
            if (her != null) ysm.stopAnimation(server, her.getName().getString());
        }
    }
}
