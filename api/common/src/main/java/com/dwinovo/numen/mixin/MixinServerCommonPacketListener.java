package com.dwinovo.numen.mixin;

import com.dwinovo.numen.entity.FakeConnection;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drop every outbound packet aimed at a companion {@link FakeConnection}, at the
 * <em>packet-listener</em> level — one layer above {@link FakeConnection#send} — after
 * offering it to the body's {@link com.dwinovo.numen.entity.FakeClient}, which answers the
 * handshakes the server would otherwise wait on forever.
 *
 * <h2>Why this is needed on top of {@code FakeConnection.send} being a no-op</h2>
 * NeoForge inserts its custom-payload channel validation
 * ({@code NetworkRegistry.checkPacket}) <em>inside</em>
 * {@link ServerCommonPacketListenerImpl#send}, which runs <strong>before</strong> the
 * call reaches {@link Connection#send}. So a companion's no-op {@code Connection.send}
 * never gets the chance to discard a modded clientbound payload — the validation throws
 * first:
 * <pre>UnsupportedOperationException: Payload &lt;mod:foo&gt; may not be sent to the client!</pre>
 * A fake player never negotiated any custom channels (it skips the configuration phase),
 * so the channel set is empty and <em>any</em> mod custom payload trips the check and
 * crashes the server tick. This happens for any mod that pushes a clientbound payload at
 * the body — an item's {@code use()} opening a screen (chiikawa's music box), a menu's
 * per-tick {@code broadcastChanges} (AE2 terminals), etc.
 *
 * <p>Cancelling at {@code HEAD} of the 1-arg {@code send(Packet)} short-circuits before
 * the (2-arg) overload that carries {@code checkPacket} is ever reached. It is a strict
 * superset of what {@link FakeConnection#send} already did (drop on the floor — there is
 * no client), so there is no behavioural regression: vanilla position/chunk/keep-alive
 * packets were already discarded.
 *
 * <p>Common (all environments): the integrated server hits this path in singleplayer too,
 * so it must NOT be a {@code server}-only (dedicated) mixin.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class MixinServerCommonPacketListener {

    @Shadow
    @Final
    protected Connection connection;

    /**
     * 这具身体的对外连接是不是"没有真客户端"的。真玩家的连接是原版直接 {@code new Connection(flow)}
     * (恰好就是 {@code Connection} 本身);我们自己的 {@link FakeConnection} 与上游寻路测试夹具的
     * {@code TestBody.Wire} 都是它的子类——两者的 {@code send} 都只丢弃,不真的发给谁。
     */
    private static boolean numen$isFakeConnection(Connection connection) {
        return connection instanceof FakeConnection || connection.getClass() != Connection.class;
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void numen$dropOutboundForFakeConnection(Packet<?> packet, CallbackInfo ci) {
        if (!numen$isFakeConnection(this.connection)) {
            return;
        }
        // 丢之前先给"客户端"看一眼:原版有几处是发完就等对面回话的(传送编号),
        // 回执不来那边就永远悬着。这里是玩家连接唯一的下行出口,所以也是唯一该问这句话的地方。
        if ((Object) this instanceof ServerGamePacketListenerImpl game
                && game.getPlayer() instanceof NumenPlayer companion) {
            companion.fakeClient().onOutbound(packet);
        }
        ci.cancel();
    }

    /**
     * 同样的丢弃,但拦 <b>2 参</b> {@code send(Packet, PacketSendListener)}:NeoForge 的自定义载荷校验
     * ({@code NetworkRegistry.checkPacket})是在这个重载<b>内部</b>调用的;而
     * {@code send(CustomPacketPayload)} 接口默认方法直接走这一个重载,绕过 1 参 {@code send(Packet)},
     * 所以只拦 1 参挡不住。合并上游内核后同伴带上了 Curios 等数据,Curios 加入世界时推
     * {@code sync_curios} 正好走到这里。
     */
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"), cancellable = true)
    private void numen$dropOutboundForFakeConnection2(Packet<?> packet,
                                                      net.minecraft.network.PacketSendListener listener,
                                                      CallbackInfo ci) {
        if (!numen$isFakeConnection(this.connection)) {
            return;
        }
        if ((Object) this instanceof ServerGamePacketListenerImpl game
                && game.getPlayer() instanceof NumenPlayer companion) {
            companion.fakeClient().onOutbound(packet);
        }
        ci.cancel();
    }
}
