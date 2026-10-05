package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.spectator.SpectatorMenuFrame;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Full menu frames use a viewing session and increasing menu/revision numbers, never vanilla IDs alone. */
public record SpectatorMenuPayload(long sessionId, UUID target, long menuId, long revision,
        int event, ResourceLocation menuType, Component title, boolean inventory,
        SpectatorMenuFrame frame) implements CustomPacketPayload {
    public static final int OPEN = 0, UPDATE = 1, CLOSE = 2, DISMISS = 3;
    public static final Type<SpectatorMenuPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "spectator_menu"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SpectatorMenuPayload> STREAM_CODEC =
            StreamCodec.of(SpectatorMenuPayload::write, SpectatorMenuPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, SpectatorMenuPayload payload) {
        buf.writeVarLong(payload.sessionId);
        buf.writeUUID(payload.target);
        buf.writeVarLong(payload.menuId);
        buf.writeVarLong(payload.revision);
        buf.writeVarInt(payload.event);
        buf.writeResourceLocation(payload.menuType);
        ComponentSerialization.STREAM_CODEC.encode(buf, payload.title);
        buf.writeBoolean(payload.inventory);
        buf.writeVarInt(payload.frame.slots().size());
        for (ItemStack stack : payload.frame.slots()) ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, payload.frame.carried());
        buf.writeVarInt(payload.frame.data().size());
        for (int value : payload.frame.data()) buf.writeVarInt(value);
    }

    private static SpectatorMenuPayload read(RegistryFriendlyByteBuf buf) {
        long sessionId = buf.readVarLong();
        UUID target = buf.readUUID();
        long menuId = buf.readVarLong();
        long revision = buf.readVarLong();
        int event = buf.readVarInt();
        ResourceLocation type = buf.readResourceLocation();
        Component title = ComponentSerialization.STREAM_CODEC.decode(buf);
        boolean inventory = buf.readBoolean();
        int count = boundedCount(buf.readVarInt(), 128, "slots");
        List<ItemStack> slots = new ArrayList<>(count);
        for (int i = 0; i < count; i++) slots.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
        ItemStack carried = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        int dataCount = boundedCount(buf.readVarInt(), 64, "data");
        List<Integer> data = new ArrayList<>(dataCount);
        for (int i = 0; i < dataCount; i++) data.add(buf.readVarInt());
        return new SpectatorMenuPayload(sessionId, target, menuId, revision, event, type,
                title, inventory, new SpectatorMenuFrame(slots, carried, data));
    }

    private static int boundedCount(int count, int max, String field) {
        if (count < 0 || count > max) throw new IllegalArgumentException("Invalid spectator menu " + field + " length: " + count);
        return count;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SpectatorMenuPayload payload) {
        com.dwinovo.numen.network.ClientPayloadSink.receiveSpectatorMenu.accept(payload);
    }
}
