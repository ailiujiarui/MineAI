package io.github.gameai.forgeagent.client;

import io.github.gameai.forgeagent.GameAiForgeAgent;
import io.github.gameai.forgeagent.bridge.LocalBridgeClient;

public final class ClientAgentController {
    private static final LocalBridgeClient BRIDGE_CLIENT = new LocalBridgeClient();

    private ClientAgentController() {
    }

    public static void bootstrap() {
        GameAiForgeAgent.LOGGER.info("Game AI Forge Agent client scaffold initialized.");
        BRIDGE_CLIENT.start();
    }
}
