package io.github.gameai.forgeagent.bridge;

public final class BridgeConfig {
    public static final String HOST = "127.0.0.1";
    public static final int PORT = 18765;
    public static final long SNAPSHOT_INTERVAL_MS = 200L;
    public static final long RECONNECT_INTERVAL_MS = 3000L;

    private BridgeConfig() {
    }
}
