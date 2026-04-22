package io.github.gameai.forgeagent.client;

import net.minecraft.client.Minecraft;

public final class ClientInputPulseExecutor {
    private ClientInputPulseExecutor() {
    }

    public static void pulseAttack(Minecraft minecraft) {
        minecraft.options.keyAttack.setDown(true);
        minecraft.execute(() -> minecraft.options.keyAttack.setDown(false));
    }

    public static void pulseUse(Minecraft minecraft) {
        minecraft.options.keyUse.setDown(true);
        minecraft.execute(() -> minecraft.options.keyUse.setDown(false));
    }
}
