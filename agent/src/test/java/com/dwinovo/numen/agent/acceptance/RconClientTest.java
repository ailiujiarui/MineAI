package com.dwinovo.numen.agent.acceptance;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RconClientTest {

    /** 一个只会答一次的假靶场:先收认证、再收一条命令,各回一包。 */
    private static void serveOnce(ServerSocket server) {
        try (Socket socket = server.accept();
             InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {
            RconPacket.Packet auth = RconPacket.read(in);
            out.write(RconPacket.encode(auth.id(), RconPacket.TYPE_RESPONSE, ""));
            out.flush();

            RconPacket.Packet command = RconPacket.read(in);
            out.write(RconPacket.encode(command.id(), RconPacket.TYPE_RESPONSE,
                    "There are 1 of a max of 20 players"));
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void authenticatesAndRunsCommands() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread serverThread = new Thread(() -> serveOnce(server), "fake-rcon");
            serverThread.setDaemon(true);
            serverThread.start();

            RconConfig config = new RconConfig("127.0.0.1", server.getLocalPort(), "secret", 5000);
            try (RconClient client = new RconClient(config)) {
                client.connect();
                assertTrue(client.connected());
                assertEquals("There are 1 of a max of 20 players", client.command("list"));
            }

            serverThread.join(5000);
        }
    }
}
