package com.github.squi2rel.vp.network;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoProtocolTest {
    @Test
    void createsAndMatchesTheCurrentWireToken() {
        assertEquals("2.0.3|vp5", VideoProtocol.token("2.0.3"));
        assertTrue(VideoProtocol.compatible("2.0.3", "2.0.3|vp5"));
        assertTrue(VideoProtocol.compatible("2.0.3", " 2.0.3|vp5"));
        assertTrue(VideoProtocol.compatible("2.0.3", "2.0.3|vp5 "));
    }

    @Test
    void advertisesTheReleased202TokenForThe203ClientHandshake() {
        assertEquals("2.0.2|vp5", VideoProtocol.handshakeToken("2.0.3"));
        assertTrue(VideoProtocol.compatible("2.0.2", VideoProtocol.handshakeToken("2.0.3")));
        assertEquals("2.0.4|vp5", VideoProtocol.handshakeToken("2.0.4"));
    }

    @Test
    void acceptsOnlyPublishedWireRevisions() {
        assertTrue(VideoProtocol.compatible("2.0.1", "2.0.1|vp5"));
        assertTrue(VideoProtocol.compatible("2.0.1", "2.0.1|vp2"));
        assertTrue(VideoProtocol.compatible("2.0.1", "2.0.1|vp4"));
        assertFalse(VideoProtocol.compatible("2.0.1", "2.0.1"));
        assertFalse(VideoProtocol.compatible("2.0.1", "2.0.1|vp3"));
        assertFalse(VideoProtocol.compatible("2.0.1", "2.0.1|custom-build"));
    }

    @Test
    void enforcesTheReleaseVersionMatrix() {
        List<CompatibilityCase> cases = List.of(
                new CompatibilityCase("2.0.1", "2.0.1|vp5", true),
                new CompatibilityCase("2.0.1", "2.0.2|vp5", true),
                new CompatibilityCase("2.0.2", "2.0.1|vp2", true),
                new CompatibilityCase("2.0.3", "2.0.1|vp5", true),
                new CompatibilityCase("2.0.1", "2.0.3|vp5", true),
                new CompatibilityCase("2.0.3", "2.0.2|vp5", true),
                new CompatibilityCase("2.0.2", "2.0.3|vp5", true),
                new CompatibilityCase("2.0.3", "2.0.4|vp5", false),
                new CompatibilityCase("2.0.4", "2.0.3|vp5", false),
                new CompatibilityCase("2.0.1", "2.0.10|vp5", false),
                new CompatibilityCase("2.0.1", "2.0.1|vp1", false),
                new CompatibilityCase("2.0.1", "2.0.1|vp5-extra", false),
                new CompatibilityCase("2.0.1", "2.0.1|", false),
                new CompatibilityCase("2.0.1", "2.0.1", false),
                new CompatibilityCase("2.0.1", "", false),
                new CompatibilityCase("2.0.1", null, false)
        );

        for (CompatibilityCase testCase : cases) {
            assertEquals(
                    testCase.expected(),
                    VideoProtocol.compatible(testCase.localVersion(), testCase.remoteToken()),
                    () -> testCase.localVersion() + " against " + testCase.remoteToken()
            );
        }
    }

    @Test
    void respondsWithTheLocalTokenUnlessThePeerTokenIsCompatible() {
        assertEquals("2.0.1|vp5", VideoProtocol.responseToken("2.0.1", "2.0.1|vp5"));
        assertEquals("2.0.1|vp5", VideoProtocol.responseToken("2.0.1", " 2.0.1|vp5 "));
        assertEquals("2.0.1|vp2", VideoProtocol.responseToken("2.0.1", "2.0.1|vp2"));
        assertEquals("2.0.1|vp5", VideoProtocol.responseToken("2.0.1", "2.0.1"));
        assertEquals("2.0.1|vp2", VideoProtocol.responseToken("2.0.2", "2.0.1|vp2"));
        assertEquals("2.0.2|vp5", VideoProtocol.responseToken("2.0.2", "2.0.1|custom-build"));
    }

    @Test
    void exposesWireCapabilitiesForPerPlayerPacketSelection() {
        assertEquals("2.0.1|vp2", VideoProtocol.legacyToken());
        assertEquals(2, VideoProtocol.wireRevision("2.0.1|vp2"));
        assertEquals(4, VideoProtocol.wireRevision("2.0.1|vp4"));
        assertEquals(5, VideoProtocol.wireRevision("2.0.2|vp5"));
        assertEquals(-1, VideoProtocol.wireRevision("2.0.1|custom-build"));
        assertFalse(VideoProtocol.supportsClientPlaybackReporting("2.0.1|vp2"));
        assertTrue(VideoProtocol.supportsClientPlaybackReporting("2.0.1|vp4"));
        assertFalse(VideoProtocol.supportsIdlePlayMutations("2.0.1|vp4"));
        assertTrue(VideoProtocol.supportsIdlePlayMutations("2.0.2|vp5"));
    }

    @Test
    void allowsHandshakePacketsAfterClientRejection() {
        for (VideoPacketType type : VideoPacketType.values()) {
            assertEquals(
                    type == VideoPacketType.PROTOCOL_REJECT
                            || type == VideoPacketType.RESET_CLIENT
                            || type == VideoPacketType.CONFIG,
                    VideoProtocol.allowedForRejectedClient(type),
                    type.name()
            );
        }
    }

    @Test
    void rejectsTokensThatCannotFitTheServerHandshakeField() {
        assertEquals(16, VideoProtocol.MAX_TOKEN_BYTES);
        String hotfixToken = VideoProtocol.token("2.0.3");
        assertEquals("2.0.3|vp5", hotfixToken);
        assertTrue(hotfixToken.getBytes(StandardCharsets.UTF_8).length <= VideoProtocol.MAX_TOKEN_BYTES);
        assertEquals("2.0.2-26.2|vp5", VideoProtocol.token("2.0.2-26.2"));
        assertThrows(IllegalArgumentException.class, () -> VideoProtocol.token("2.0.2-26.2-long"));
        assertThrows(IllegalArgumentException.class, () -> VideoProtocol.token("版本版本版本版本"));
    }

    private record CompatibilityCase(String localVersion, String remoteToken, boolean expected) {
    }
}
