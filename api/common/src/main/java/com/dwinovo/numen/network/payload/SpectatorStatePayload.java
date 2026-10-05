package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** 与实体追踪分开的观看数据。inventory 使用原版 Inventory 的 0..40 槽顺序。 */
public record SpectatorStatePayload(long sessionId, UUID target, int entityId,
                                    ResourceLocation dimension, boolean active,
                                    List<ItemStack> inventory, int selected,
                                    float health, float maxHealth, int food, float saturation,
                                    float experienceProgress, int experienceLevel,
                                    int totalExperience, float attackStrength)
        implements CustomPacketPayload {
    public static final Type<SpectatorStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "spectator_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SpectatorStatePayload> STREAM_CODEC =
            StreamCodec.of(SpectatorStatePayload::write, SpectatorStatePayload::read);

    private static void write(RegistryFriendlyByteBuf buf, SpectatorStatePayload p) {
        buf.writeVarLong(p.sessionId());
        UUIDUtil.STREAM_CODEC.encode(buf, p.target());
        buf.writeVarInt(p.entityId());
        buf.writeResourceLocation(p.dimension());
        buf.writeBoolean(p.active());
        ItemStack.OPTIONAL_LIST_STREAM_CODEC.encode(buf, p.inventory());
        buf.writeVarInt(p.selected());
        buf.writeFloat(p.health());
        buf.writeFloat(p.maxHealth());
        buf.writeVarInt(p.food());
        buf.writeFloat(p.saturation());
        buf.writeFloat(p.experienceProgress());
        buf.writeVarInt(p.experienceLevel());
        buf.writeVarInt(p.totalExperience());
        buf.writeFloat(p.attackStrength());
    }

    private static SpectatorStatePayload read(RegistryFriendlyByteBuf buf) {
        return new SpectatorStatePayload(buf.readVarLong(), UUIDUtil.STREAM_CODEC.decode(buf),
                buf.readVarInt(), buf.readResourceLocation(), buf.readBoolean(),
                ItemStack.OPTIONAL_LIST_STREAM_CODEC.decode(buf), buf.readVarInt(),
                buf.readFloat(), buf.readFloat(), buf.readVarInt(), buf.readFloat(),
                buf.readFloat(), buf.readVarInt(), buf.readVarInt(), buf.readFloat());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SpectatorStatePayload p) {
        com.dwinovo.numen.network.ClientPayloadSink.receiveSpectatorState.accept(p);
    }
}
