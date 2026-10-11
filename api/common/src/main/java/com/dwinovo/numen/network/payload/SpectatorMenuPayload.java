package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.network.Wire;
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
        SpectatorMenuFrame frame, List<SpectatorMenuFrame> actionFrames,
        boolean closeAfterAction) implements CustomPacketPayload, Wire.Fragmentable {
    public static final int OPEN = 0, UPDATE = 1, CLOSE = 2, DISMISS = 3, ACTION = 4;
    public static final int MAX_ACTION_FRAMES = 32;
    private static final int MAX_VALUES = 4096;
    public static final Type<SpectatorMenuPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "spectator_menu"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SpectatorMenuPayload> STREAM_CODEC =
            StreamCodec.of(SpectatorMenuPayload::write, SpectatorMenuPayload::read);

    public SpectatorMenuPayload {
        boundedCount(actionFrames.size(), MAX_ACTION_FRAMES, "action frames");
        actionFrames = List.copyOf(actionFrames);
        int values = frame.slots().size() + frame.data().size() + 1;
        for (SpectatorMenuFrame actionFrame : actionFrames) {
            values += actionFrame.slots().size() + actionFrame.data().size() + 1;
        }
        boundedCount(values, MAX_VALUES, "total values");
    }

    public SpectatorMenuPayload(long sessionId, UUID target, long menuId, long revision,
            int event, ResourceLocation menuType, Component title, boolean inventory, SpectatorMenuFrame frame) {
        this(sessionId, target, menuId, revision, event, menuType, title, inventory, frame, List.of(), false);
    }

    private static void write(RegistryFriendlyByteBuf buf, SpectatorMenuPayload payload) {
        buf.writeVarLong(payload.sessionId);
        buf.writeUUID(payload.target);
        buf.writeVarLong(payload.menuId);
        buf.writeVarLong(payload.revision);
        buf.writeVarInt(payload.event);
        buf.writeResourceLocation(payload.menuType);
        ComponentSerialization.STREAM_CODEC.encode(buf, payload.title);
        buf.writeBoolean(payload.inventory);
        writeFrame(buf, payload.frame);
        buf.writeVarInt(payload.actionFrames.size());
        for (SpectatorMenuFrame frame : payload.actionFrames) writeFrame(buf, frame);
        buf.writeBoolean(payload.closeAfterAction);
    }

    private static void writeFrame(RegistryFriendlyByteBuf buf, SpectatorMenuFrame frame) {
        buf.writeVarInt(boundedCount(frame.slots().size(), 128, "slots"));
        for (ItemStack stack : frame.slots()) ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, frame.carried());
        buf.writeVarInt(boundedCount(frame.data().size(), 64, "data"));
        for (int value : frame.data()) buf.writeVarInt(value);
        buf.writeVarInt(frame.selectedHotbar());
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
        int[] remaining = { MAX_VALUES };
        SpectatorMenuFrame frame = readFrame(buf, remaining);
        int framesCount = boundedCount(buf.readVarInt(), MAX_ACTION_FRAMES, "action frames");
        List<SpectatorMenuFrame> frames = new ArrayList<>(framesCount);
        for (int i = 0; i < framesCount; i++) frames.add(readFrame(buf, remaining));
        return new SpectatorMenuPayload(sessionId, target, menuId, revision, event, type,
                title, inventory, frame, frames, buf.readBoolean());
    }

    private static SpectatorMenuFrame readFrame(RegistryFriendlyByteBuf buf, int[] remaining) {
        int count = boundedCount(buf.readVarInt(), 128, "slots");
        remaining[0] = boundedCount(remaining[0] - count - 1, MAX_VALUES, "remaining values");
        List<ItemStack> slots = new ArrayList<>(count);
        for (int i = 0; i < count; i++) slots.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
        ItemStack carried = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        int dataCount = boundedCount(buf.readVarInt(), 64, "data");
        remaining[0] = boundedCount(remaining[0] - dataCount, MAX_VALUES, "remaining values");
        List<Integer> data = new ArrayList<>(dataCount);
        for (int i = 0; i < dataCount; i++) data.add(buf.readVarInt());
        int selected = buf.readVarInt();
        if (selected < -1 || selected > 8) throw new IllegalArgumentException("Invalid spectator selected hotbar: " + selected);
        return new SpectatorMenuFrame(slots, carried, data, selected);
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
