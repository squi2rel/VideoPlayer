package com.github.squi2rel.vp.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class VideoPacketsProtocolRejectTest {
    @Test
    void carriesTheServerWireTokenInADedicatedControlPacket() {
        assertEquals(24, VideoPacketType.PROTOCOL_REJECT.id);
        ByteBuf buf = Unpooled.wrappedBuffer(VideoPackets.protocolReject("2.0.1"));
        try {
            assertEquals(VideoPacketType.PROTOCOL_REJECT, VideoPackets.readType(buf));
            assertEquals("2.0.1|vp2", ByteBufUtils.readString(buf, 16));
            assertFalse(buf.isReadable());
        } finally {
            buf.release();
        }
    }
}
