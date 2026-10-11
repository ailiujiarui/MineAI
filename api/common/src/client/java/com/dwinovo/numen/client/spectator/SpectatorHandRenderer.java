package com.dwinovo.numen.client.spectator;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;

public interface SpectatorHandRenderer {
    void numen$renderHands(float partialTick, PoseStack pose, MultiBufferSource.BufferSource buffers,
                           AbstractClientPlayer target, int light);
    void numen$resetHands();
}
