package com.github.squi2rel.vp.video;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoScreenInputLimitsTest {
    @Test
    void nameValidationUsesCodePointsAndUtf8Bytes() {
        String supplementaryCjk = "\uD842\uDFB7";

        assertTrue(VideoScreen.validName(supplementaryCjk.repeat(VideoScreen.MAX_NAME_LENGTH)));
        assertFalse(VideoScreen.validName(supplementaryCjk.repeat(VideoScreen.MAX_NAME_LENGTH + 1)));
        assertTrue(VideoScreen.validNameInput(""));
        assertFalse(VideoScreen.validName(""));
    }

    @Test
    void playbackUrlValidationUsesUtf8BytesInsteadOfJavaLength() {
        String cjk = "\u754C";
        String accepted = cjk.repeat(341) + "a";
        String rejected = accepted + cjk;

        assertTrue(accepted.length() < VideoScreen.MAX_PLAY_URL_BYTES);
        assertTrue(VideoScreen.validPlayUrl(accepted));
        assertFalse(VideoScreen.validPlayUrl(rejected));
        assertTrue(VideoScreen.validPlayUrlInput(""));
        assertFalse(VideoScreen.validPlayUrl(""));
    }

    @Test
    void idlePlayValidationAppliesItemAndTotalBudgets() {
        String item = "界".repeat(300);
        assertTrue(VideoScreen.validIdlePlayConfig(List.of(item)));
        assertFalse(VideoScreen.validIdlePlayConfig(List.of("界".repeat(342))));
        assertFalse(VideoScreen.validIdlePlayConfig(java.util.Collections.nCopies(VideoScreen.MAX_IDLE_PLAY_ITEMS + 1, "x")));
        assertFalse(VideoScreen.validIdlePlayConfig(java.util.Collections.nCopies(25, "界".repeat(333))));
    }

    @Test
    void displayConfigRejectsNonFiniteAndOutOfRangeValues() {
        VideoScreen screen = new VideoScreen(null, "screen", List.of(), "");

        screen.scaleX = Float.NaN;
        assertFalse(screen.hasValidDisplayConfig());

        screen.scaleX = 1f;
        screen.scaleY = 16.01f;
        assertFalse(screen.hasValidDisplayConfig());

        screen.scaleY = 1f;
        screen.u1 = Float.POSITIVE_INFINITY;
        assertFalse(screen.hasValidDisplayConfig());

        screen.u1 = 0f;
        assertTrue(screen.hasValidDisplayConfig());
    }
}
