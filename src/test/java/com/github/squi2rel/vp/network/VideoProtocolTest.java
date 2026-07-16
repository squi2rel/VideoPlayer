package com.github.squi2rel.vp.network;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoProtocolTest {
    @Test
    void createsAndMatchesTheExactWireToken() {
        assertEquals("2.0.1|vp2", VideoProtocol.token("2.0.1"));
        assertTrue(VideoProtocol.compatible("2.0.1", "2.0.1|vp2"));
        assertFalse(VideoProtocol.compatible("2.0.1", " 2.0.1|vp2"));
        assertFalse(VideoProtocol.compatible("2.0.1", "2.0.1|vp2 "));
    }

    @Test
    void rejectsThePlainReleaseVersion() {
        assertFalse(VideoProtocol.compatible("2.0.1", "2.0.1"));
    }

    @Test
    void enforcesTheVersionAndWireRevisionMatrix() {
        List<CompatibilityCase> cases = List.of(
                new CompatibilityCase("2.0.1", "2.0.1|vp2", true),
                new CompatibilityCase("2.0.1", "2.0.0|vp2", false),
                new CompatibilityCase("2.0.1", "2.0.10|vp2", false),
                new CompatibilityCase("2.0.1", "2.0.1|vp1", false),
                new CompatibilityCase("2.0.1", "2.0.1|vp2-extra", false),
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
    void allowsOnlyTheExplicitRejectPacketAfterClientRejection() {
        for (VideoPacketType type : VideoPacketType.values()) {
            assertEquals(
                    type == VideoPacketType.PROTOCOL_REJECT,
                    VideoProtocol.allowedForRejectedClient(type),
                    type.name()
            );
        }
    }

    private record CompatibilityCase(String localVersion, String remoteToken, boolean expected) {
    }
}
