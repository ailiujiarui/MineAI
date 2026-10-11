package com.dwinovo.numen.experiment;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import java.net.*;
import java.util.UUID;
import java.util.function.Consumer;

/** 实验主人的内存连接:真实服务端下行状态和事件交给实验大脑。 */
final class ExperimentOwner extends Connection {
    private final Consumer<CustomPacketPayload> sink;
    private ExperimentOwner(Consumer<CustomPacketPayload> sink) {
        super(PacketFlow.SERVERBOUND);
        this.sink = sink;
        new EmbeddedChannel(this);
        NetworkRegistry.configureMockConnection(this);
    }
    static ServerPlayer join(MinecraftServer server, Consumer<CustomPacketPayload> sink) {
        ServerPlayer owner = new ServerPlayer(server, server.overworld(),
                new GameProfile(UUID.randomUUID(), "EvalOwner"), ClientInformation.createDefault());
        server.getPlayerList().placeNewPlayer(new ExperimentOwner(sink), owner,
                CommonListenerCookie.createInitial(owner.getGameProfile(), false));
        owner.teleportTo(0.5, 81, 0.5);
        return owner;
    }
    @Override public SocketAddress getRemoteAddress() { return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0); }
    @Override public void send(Packet<?> p, PacketSendListener listener, boolean flush) {
        if (p instanceof ClientboundCustomPayloadPacket c) sink.accept(c.payload());
    }
    @Override public void runOnceConnected(Consumer<Connection> action) {}
    @Override public boolean isConnected() { return true; }
    @Override public void tick() {}
    @Override public void disconnect(Component message) {}
    @Override public void disconnect(DisconnectionDetails details) {}
    @Override public void handleDisconnection() {}
    @Override public void flushChannel() {}
    @Override public void setReadOnly() {}
}
