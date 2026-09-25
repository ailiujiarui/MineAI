package com.dwinovo.numen.agent.acceptance;

/**
 * 连哪个靶场。默认端口是原版 {@code server.properties} 里 {@code rcon.port} 的默认值 25575。
 *
 * <p>纯 JVM,不碰 Minecraft。
 *
 * @param host      服务端地址
 * @param port      RCON 端口
 * @param password  {@code rcon.password};靶场是测试服,允许空
 * @param timeoutMs 连接与读超时
 */
public record RconConfig(String host, int port, String password, int timeoutMs) {

    public static final int DEFAULT_PORT = 25575;
    public static final int DEFAULT_TIMEOUT_MS = 5000;

    public RconConfig {
        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
        }
        password = password == null ? "" : password;
        if (timeoutMs <= 0) {
            timeoutMs = DEFAULT_TIMEOUT_MS;
        }
    }

    public static RconConfig of(String host, int port, String password) {
        return new RconConfig(host, port, password, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 从系统属性读:{@code <prefix>.host/.port/.password/.timeout}。靶场起服时把 {@code server.properties}
     * 里那三项照抄过来即可,不用改代码。
     */
    public static RconConfig fromSystemProperties(String prefix) {
        String p = prefix == null || prefix.isBlank() ? "numen.rcon" : prefix;
        String host = System.getProperty(p + ".host", "127.0.0.1");
        int port = readInt(System.getProperty(p + ".port"), DEFAULT_PORT);
        String password = System.getProperty(p + ".password", "");
        int timeout = readInt(System.getProperty(p + ".timeout"), DEFAULT_TIMEOUT_MS);
        return new RconConfig(host, port, password, timeout);
    }

    private static int readInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.strip());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }
}
