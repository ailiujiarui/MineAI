package com.dwinovo.numen.agent.acceptance;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 最小 RCON 客户端:连、认证、发一行命令、拿回输出。同步、非线程安全。
 *
 * <p>它只被验收骨骼用,不在同伴的正常执行路径上——模型够不到它,所以它不可能被拿来作弊。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class RconClient implements AutoCloseable {

    private final RconConfig config;
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private int nextId = 1;

    public RconClient(RconConfig config) {
        this.config = config;
    }

    /** 建连并认证。认证失败(服务端回 id {@code -1})抛 {@link IOException}。 */
    public void connect() throws IOException {
        if (connected()) {
            return;
        }
        Socket s = new Socket();
        s.connect(new InetSocketAddress(config.host(), config.port()), config.timeoutMs());
        s.setSoTimeout(config.timeoutMs());
        socket = s;
        in = new BufferedInputStream(s.getInputStream());
        out = new BufferedOutputStream(s.getOutputStream());
        RconPacket.Packet auth = exchange(RconPacket.TYPE_AUTH, config.password());
        if (auth.id() == -1) {
            close();
            throw new IOException("rcon: 认证失败(检查 rcon.password)");
        }
    }

    public boolean connected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    /** 发一行命令,回它的输出。连不上就抛 {@link IOException}。 */
    public String command(String line) throws IOException {
        if (!connected()) {
            connect();
        }
        RconPacket.Packet response = exchange(RconPacket.TYPE_COMMAND, line == null ? "" : line);
        return response.body();
    }

    private RconPacket.Packet exchange(int type, String body) throws IOException {
        int id = nextId++;
        byte[] frame = RconPacket.encode(id, type, body);
        out.write(frame);
        out.flush();
        return RconPacket.read(in);
    }

    @Override
    public void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关的时候出错没有补救价值
            }
        }
        socket = null;
        in = null;
        out = null;
    }
}
