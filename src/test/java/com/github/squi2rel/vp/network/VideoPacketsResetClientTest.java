package com.github.squi2rel.vp.network;

import com.github.squi2rel.vp.ServerConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class VideoPacketsResetClientTest {
    @Test
    void resetClientCarriesCurrentServerConfig() {
        ServerConfig config = new ServerConfig();
        config.remoteControlName = "minecraft:stick";
        config.remoteControlId = 17.5f;
        config.remoteControlRange = 96.0f;
        config.noControlRange = 24.0f;

        ByteBuf buf = Unpooled.wrappedBuffer(VideoPackets.resetClient("2.0.1", config));
        try {
            assertEquals(VideoPacketType.RESET_CLIENT, VideoPackets.readType(buf));
            assertEquals("2.0.1", ByteBufUtils.readString(buf, 16));
            assertEquals("minecraft:stick", ByteBufUtils.readString(buf, 256));
            assertEquals(17.5f, buf.readFloat());
            assertEquals(96.0f, buf.readFloat());
            assertEquals(24.0f, buf.readFloat());
            assertFalse(buf.isReadable());
        } finally {
            buf.release();
        }
    }
}
