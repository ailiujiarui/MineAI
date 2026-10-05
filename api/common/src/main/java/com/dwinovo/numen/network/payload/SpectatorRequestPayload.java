package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.spectator.ServerSpectatorSessions;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** 主人请求进入、退出或查看当前观看目标的背包;身体动作不经过这条通道。 */
public record SpectatorRequestPayload(UUID target, int action, long sessionId, long menuId) implements CustomPacketPayload {
    public static final int ENTER = 0;
    public static final int EXIT = 1;
    public static final int INVENTORY = 2;
    public static final int CLOSE_INVENTORY = 3;

    public static final Type<SpectatorRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "spectator_request"));
    public static final StreamCodec<ByteBuf, SpectatorRequestPayload> STREAM_CODEC =
            StreamCodec.composite(UUIDUtil.STREAM_CODEC, SpectatorRequestPayload::target,
                    ByteBufCodecs.VAR_INT, SpectatorRequestPayload::action,
                    ByteBufCodecs.VAR_LONG, SpectatorRequestPayload::sessionId,
                    ByteBufCodecs.VAR_LONG, SpectatorRequestPayload::menuId, SpectatorRequestPayload::new);

    public SpectatorRequestPayload(UUID target, int action) {
        this(target, action, 0, 0);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SpectatorRequestPayload p, ServerPlayer sender) {
        ServerSpectatorSessions.request(sender, p);
    }
}
