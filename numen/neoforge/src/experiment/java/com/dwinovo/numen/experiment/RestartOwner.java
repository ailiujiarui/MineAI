package com.dwinovo.numen.experiment;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.function.Consumer;

/** 重启夹具的固定身份主人:登录事件走原版路径,不替同伴调用恢复入口。 */
final class RestartOwner extends Connection {
    private final Consumer<CustomPacketPayload> sink;

    private RestartOwner(Consumer<CustomPacketPayload> sink) {
        super(PacketFlow.SERVERBOUND);
        this.sink = sink;
        new EmbeddedChannel(this);
        NetworkRegistry.configureMockConnection(this);
    }

    static ServerPlayer join(MinecraftServer server, UUID uuid, Consumer<CustomPacketPayload> sink) {
        ServerPlayer owner = new ServerPlayer(server, server.overworld(),
                new GameProfile(uuid, "RestartOwner"), ClientInformation.createDefault());
        server.getPlayerList().placeNewPlayer(new RestartOwner(sink), owner,
                CommonListenerCookie.createInitial(owner.getGameProfile(), false));
        return owner;
    }

    @Override public SocketAddress getRemoteAddress() { return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0); }
    @Override public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
        if (packet instanceof ClientboundCustomPayloadPacket custom) sink.accept(custom.payload());
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
