package com.dwinovo.numen.agent.acceptance;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RconPacketTest {

    @Test
    void encodeDecodeRoundTrips() {
        byte[] frame = RconPacket.encode(7, RconPacket.TYPE_COMMAND, "/list");
        RconPacket.Packet packet = RconPacket.decode(frame);
        assertEquals(7, packet.id());
        assertEquals(RconPacket.TYPE_COMMAND, packet.type());
        assertEquals("/list", packet.body());
    }

    @Test
    void lengthPrefixIsLittleEndian() {
        byte[] frame = RconPacket.encode(1, RconPacket.TYPE_AUTH, "secret");
        int declared = (frame[0] & 0xff) | ((frame[1] & 0xff) << 8)
                | ((frame[2] & 0xff) << 16) | ((frame[3] & 0xff) << 24);
        assertEquals(frame.length - 4, declared);
    }

    @Test
    void readConsumesOneFrameFromAStream() throws IOException {
        byte[] frame = RconPacket.encode(3, RconPacket.TYPE_RESPONSE, "There are 1 of a max of 20 players");
        RconPacket.Packet packet = RconPacket.read(new ByteArrayInputStream(frame));
        assertEquals(3, packet.id());
        assertEquals("There are 1 of a max of 20 players", packet.body());
    }

    @Test
    void aTruncatedFrameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RconPacket.decode(new byte[]{0, 0, 0, 0}));
    }
}
