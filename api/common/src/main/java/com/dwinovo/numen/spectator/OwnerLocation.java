package com.dwinovo.numen.spectator;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** 同伴眼中的主人站位;摄像机、区块订阅和生命/背包继续使用真实玩家。 */
public record OwnerLocation(ServerLevel level, Vec3 position, Vec3 eyePosition, boolean onGround) {
    public static OwnerLocation of(ServerPlayer owner) {
        OwnerLocation saved = ServerSpectatorSessions.savedLocation(owner);
        return saved != null ? saved : capture(owner);
    }

    static OwnerLocation capture(ServerPlayer owner) {
        return new OwnerLocation(owner.serverLevel(), owner.position(), owner.getEyePosition(), owner.onGround());
    }

    public BlockPos blockPosition() {
        return BlockPos.containing(position);
    }
}
