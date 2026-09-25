package com.dwinovo.numen.agent.acceptance;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Source RCON 的报文编解码。
 *
 * <p>线格式:小端。{@code int32 长度}(长度之后还有多少字节)、{@code int32 请求 id}、
 * {@code int32 类型}、正文、两个 {@code 0x00}。类型:认证 {@value #TYPE_AUTH}、
 * 执行 {@value #TYPE_COMMAND}、回包 {@value #TYPE_RESPONSE}。
 *
 * <p>自己手写是因为 {@code agent} 是纯 JVM、只依赖 Gson 与 SLF4J,不引第三方 RCON 库;
 * 而 RCON 简单到不值得引一个。{@code DataInputStream} 是按大端读 int 的,所以这里不用它,
 * 一律按小端自己拼。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class RconPacket {

    public static final int TYPE_RESPONSE = 0;
    public static final int TYPE_COMMAND = 2;
    public static final int TYPE_AUTH = 3;
    public static final int TYPE_AUTH_RESPONSE = 4;

    private RconPacket() {}

    public record Packet(int id, int type, String body) {}

    /** 编成完整的一帧(含开头的小端长度)。 */
    public static byte[] encode(int id, int type, String body) {
        byte[] bodyBytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        int length = 4 + 4 + bodyBytes.length + 2;
        ByteBuffer buf = ByteBuffer.allocate(4 + length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(length);
        buf.putInt(id);
        buf.putInt(type);
        buf.put(bodyBytes);
        buf.put((byte) 0).put((byte) 0);
        return buf.array();
    }

    /** 解一帧(含开头的长度)。 */
    public static Packet decode(byte[] frame) {
        if (frame == null || frame.length < 14) {
            throw new IllegalArgumentException("rcon frame too short");
        }
        ByteBuffer buf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        int length = buf.getInt();
        if (length != frame.length - 4 || length < 10) {
            throw new IllegalArgumentException("rcon frame length mismatch: " + length);
        }
        int id = buf.getInt();
        int type = buf.getInt();
        byte[] body = new byte[length - 4 - 4 - 2];
        buf.get(body);
        return new Packet(id, type, new String(body, StandardCharsets.UTF_8));
    }

    /** 从流里读一帧:先读 4 字节小端长度,再读那么多字节。 */
    public static Packet read(InputStream in) throws IOException {
        byte[] lengthBytes = readFully(in, 4);
        int length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (length < 10 || length > 1_000_000) {
            throw new IOException("rcon: bad frame length " + length);
        }
        byte[] payload = readFully(in, length);
        byte[] frame = new byte[4 + length];
        System.arraycopy(lengthBytes, 0, frame, 0, 4);
        System.arraycopy(payload, 0, frame, 4, length);
        return decode(frame);
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] out = new byte[n];
        int read = 0;
        while (read < n) {
            int got = in.read(out, read, n - read);
            if (got < 0) {
                throw new SocketTimeoutException("rcon: connection closed mid-frame");
            }
            read += got;
        }
        return out;
    }
}
