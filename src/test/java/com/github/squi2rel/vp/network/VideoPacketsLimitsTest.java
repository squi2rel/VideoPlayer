package com.github.squi2rel.vp.network;

import com.github.squi2rel.vp.provider.VideoInfo;
import com.github.squi2rel.vp.video.PlaybackQueue;
import com.github.squi2rel.vp.video.VideoArea;
import com.github.squi2rel.vp.video.VideoScreen;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoPacketsLimitsTest {
    @Test
    void acceptsPayloadAtThirtyThousandBytesAndRejectsTheNextByte() {
        ByteBuf accepted = Unpooled.buffer(VideoPackets.MAX_PAYLOAD_BYTES);
        accepted.writeZero(VideoPackets.MAX_PAYLOAD_BYTES);
        assertEquals(VideoPackets.MAX_PAYLOAD_BYTES, VideoPackets.toByteArray(accepted).length);
        assertEquals(0, accepted.refCnt());

        ByteBuf rejected = Unpooled.buffer(VideoPackets.MAX_PAYLOAD_BYTES + 1);
        rejected.writeZero(VideoPackets.MAX_PAYLOAD_BYTES + 1);
        assertThrows(IllegalStateException.class, () -> VideoPackets.toByteArray(rejected));
        assertEquals(0, rejected.refCnt());
    }

    @Test
    void acceptsThirtyTwoQueueItemsAndRejectsTheThirtyThird() {
        PlaybackQueue queue = new PlaybackQueue(screen());
        for (int i = 0; i < PlaybackQueue.MAX_ITEMS; i++) {
            assertTrue(queue.add(info("video-" + i)));
        }

        assertEquals(PlaybackQueue.MAX_ITEMS, queue.size());
        assertFalse(queue.add(info("video-32")));
        assertEquals(PlaybackQueue.MAX_ITEMS, queue.size());
    }

    @Test
    void rejectsAPlaylistSnapshotThatContainsThirtyThreeItems() {
        VideoScreen screen = screen();
        for (int i = 0; i < PlaybackQueue.MAX_ITEMS; i++) {
            screen.infos.add(info("video-" + i));
        }

        byte[] accepted = VideoPackets.updatePlaylist(List.of(screen));
        assertTrue(accepted.length <= VideoPackets.MAX_PAYLOAD_BYTES);

        screen.infos.add(info("video-32"));
        assertThrows(IllegalStateException.class, () -> VideoPackets.updatePlaylist(List.of(screen)));
    }

    @Test
    void appliesParameterCountAndUtf8ByteLimits() {
        String utf8Parameter = "界".repeat(85) + "x";
        assertEquals(256, utf8Parameter.getBytes(StandardCharsets.UTF_8).length);
        String[] accepted = new String[VideoInfo.MAX_PARAMS];
        Arrays.fill(accepted, utf8Parameter);

        VideoInfo info = new VideoInfo("", "", "", "", -1, true, accepted, 0);
        assertEquals(VideoInfo.MAX_TOTAL_PARAM_BYTES, Arrays.stream(info.params())
                .mapToInt(value -> value.getBytes(StandardCharsets.UTF_8).length)
                .sum());

        String[] tooMany = new String[VideoInfo.MAX_PARAMS + 1];
        assertThrows(IllegalArgumentException.class,
                () -> new VideoInfo("", "", "", "", -1, true, tooMany, 0));

        String[] tooLarge = accepted.clone();
        tooLarge[0] += "x";
        assertThrows(IllegalArgumentException.class,
                () -> new VideoInfo("", "", "", "", -1, true, tooLarge, 0));
    }

    @Test
    void truncatesProviderNamesAtUtf8CodePointBoundaries() {
        VideoInfo info = new VideoInfo("player", "界".repeat(100), "", "", -1, true, new String[0], 0);

        assertTrue(info.name().getBytes(StandardCharsets.UTF_8).length <= 256);
        assertFalse(info.name().endsWith("�"));
    }

    @Test
    void requestCarriesPlaybackGenerationAndAuthoritativeProgress() {
        VideoScreen screen = screen();
        VideoInfo info = info("video");
        ByteBuf buf = Unpooled.wrappedBuffer(VideoPackets.request(screen, info, false, 17L, 2_500L));
        try {
            assertEquals(VideoPacketType.REQUEST, VideoPackets.readType(buf));
            assertEquals("area", VideoPackets.readName(buf));
            assertEquals("screen", VideoPackets.readName(buf));
            assertEquals(17L, buf.readLong());
            assertEquals(2_500L, buf.readLong());
            assertTrue(buf.readLong() > 0L);
            VideoInfo decoded = VideoInfo.read(buf);
            assertEquals(info.playerName(), decoded.playerName());
            assertEquals(info.name(), decoded.name());
            assertEquals(info.path(), decoded.path());
            assertArrayEquals(info.params(), decoded.params());
            assertFalse(buf.readBoolean());
            assertFalse(buf.isReadable());
        } finally {
            buf.release();
        }
    }

    @Test
    void acceptsVideoInfoAtEncodedLimitAndRejectsTheNextByte() {
        VideoInfo accepted = sizedInfo(7_076);
        ByteBuf buf = Unpooled.buffer(VideoInfo.MAX_ENCODED_BYTES);
        try {
            VideoInfo.write(buf, accepted);
            assertEquals(VideoInfo.MAX_ENCODED_BYTES, buf.readableBytes());
            VideoInfo decoded = VideoInfo.read(buf);
            assertEquals(accepted.playerName(), decoded.playerName());
            assertEquals(accepted.name(), decoded.name());
            assertEquals(accepted.path(), decoded.path());
            assertEquals(accepted.rawPath(), decoded.rawPath());
            assertArrayEquals(accepted.params(), decoded.params());
            assertFalse(buf.isReadable());
        } finally {
            buf.release();
        }

        assertThrows(IllegalArgumentException.class, () -> sizedInfo(7_077));
    }

    @Test
    void appliesIdlePlayUtf8TotalByteLimitWhenWritingAndReading() {
        List<String> acceptedUrls = idleUrls(VideoScreen.MAX_IDLE_PLAY_TOTAL_BYTES);
        assertEquals(VideoScreen.MAX_IDLE_PLAY_TOTAL_BYTES, utf8Bytes(acceptedUrls));

        VideoScreen source = screen();
        source.setIdlePlayConfig(acceptedUrls, true);
        ByteBuf accepted = Unpooled.buffer();
        try {
            VideoPackets.writeIdlePlayConfig(accepted, source);
            VideoScreen decoded = screen();
            VideoPackets.readIdlePlayConfig(accepted, decoded);
            assertEquals(acceptedUrls, decoded.idlePlayUrls);
            assertTrue(decoded.idlePlayRandom);
            assertFalse(accepted.isReadable());
        } finally {
            accepted.release();
        }

        List<String> rejectedUrls = idleUrls(VideoScreen.MAX_IDLE_PLAY_TOTAL_BYTES + 1);
        VideoScreen rejectedSource = screen();
        assertThrows(IllegalArgumentException.class, () -> rejectedSource.setIdlePlayConfig(rejectedUrls, false));
        rejectedSource.idlePlayUrls = new ArrayList<>(rejectedUrls);
        ByteBuf rejectedWrite = Unpooled.buffer();
        try {
            assertThrows(IllegalStateException.class,
                    () -> VideoPackets.writeIdlePlayConfig(rejectedWrite, rejectedSource));
        } finally {
            rejectedWrite.release();
        }

        ByteBuf rejectedRead = Unpooled.buffer();
        try {
            rejectedRead.writeBoolean(false);
            rejectedRead.writeByte(rejectedUrls.size());
            for (String url : rejectedUrls) {
                ByteBufUtils.writeString(rejectedRead, url);
            }
            assertThrows(IllegalStateException.class,
                    () -> VideoPackets.readIdlePlayConfig(rejectedRead, screen()));
        } finally {
            rejectedRead.release();
        }
    }

    @Test
    void appliesIdlePlayPerUrlUtf8ByteLimitForNonAsciiValues() {
        String cjk = "\u754C";
        String acceptedUrl = cjk.repeat(341) + "a";
        String rejectedUrl = acceptedUrl + cjk;
        assertEquals(VideoScreen.MAX_IDLE_PLAY_URL_BYTES, ByteBufUtils.utf8Length(acceptedUrl));
        assertEquals(VideoScreen.MAX_IDLE_PLAY_URL_BYTES + 3, ByteBufUtils.utf8Length(rejectedUrl));

        VideoScreen acceptedSource = screen();
        acceptedSource.setIdlePlayConfig(List.of(acceptedUrl), false);
        ByteBuf accepted = Unpooled.buffer();
        try {
            VideoPackets.writeIdlePlayConfig(accepted, acceptedSource);
            VideoScreen decoded = screen();
            VideoPackets.readIdlePlayConfig(accepted, decoded);
            assertEquals(List.of(acceptedUrl), decoded.idlePlayUrls);
        } finally {
            accepted.release();
        }

        VideoScreen rejectedSource = screen();
        assertThrows(IllegalArgumentException.class, () -> rejectedSource.setIdlePlayConfig(List.of(rejectedUrl), false));
        rejectedSource.idlePlayUrls = new ArrayList<>(List.of(rejectedUrl));
        ByteBuf rejectedWrite = Unpooled.buffer();
        try {
            assertThrows(IllegalStateException.class, () -> VideoPackets.writeIdlePlayConfig(rejectedWrite, rejectedSource));
        } finally {
            rejectedWrite.release();
        }

        ByteBuf rejectedRead = Unpooled.buffer();
        try {
            rejectedRead.writeBoolean(false);
            rejectedRead.writeByte(1);
            ByteBufUtils.writeString(rejectedRead, rejectedUrl);
            assertThrows(IllegalStateException.class, () -> VideoPackets.readIdlePlayConfig(rejectedRead, screen()));
        } finally {
            rejectedRead.release();
        }
    }

    @Test
    void idlePlayRequestSerializationDoesNotMutateCurrentScreenState() {
        VideoScreen current = screen();
        current.setIdlePlayConfig(List.of("server-state"), false);
        ByteBuf first = Unpooled.buffer();
        ByteBuf second = Unpooled.buffer();
        try {
            VideoPackets.writeIdlePlayConfig(first, List.of("request-one"), true);
            VideoPackets.writeIdlePlayConfig(second, List.of("request-two"), false);

            assertEquals(List.of("server-state"), current.idlePlayUrls);
            assertFalse(current.idlePlayRandom);

            VideoScreen decodedFirst = screen();
            VideoPackets.readIdlePlayConfig(first, decodedFirst);
            assertEquals(List.of("request-one"), decodedFirst.idlePlayUrls);
            assertTrue(decodedFirst.idlePlayRandom);

            VideoScreen decodedSecond = screen();
            VideoPackets.readIdlePlayConfig(second, decodedSecond);
            assertEquals(List.of("request-two"), decodedSecond.idlePlayUrls);
            assertFalse(decodedSecond.idlePlayRandom);

            assertEquals(List.of("server-state"), current.idlePlayUrls);
            assertFalse(current.idlePlayRandom);
        } finally {
            first.release();
            second.release();
        }
    }

    private static VideoInfo sizedInfo(int parameterBytes) {
        return new VideoInfo(
                "p".repeat(256),
                "n".repeat(256),
                "u".repeat(8_192),
                "r".repeat(8_192),
                -1,
                true,
                new String[]{"x".repeat(parameterBytes)},
                0
        );
    }

    private static VideoInfo info(String name) {
        return new VideoInfo("player", name, "https://example.com/video", "", -1, true, new String[0], 1_000);
    }

    private static VideoScreen screen() {
        VideoArea area = new VideoArea(new Vector3f(), new Vector3f(1), "area", "world");
        return new VideoScreen(
                area,
                "screen",
                new Vector3f(),
                new Vector3f(1, 0, 0),
                new Vector3f(1, 1, 0),
                new Vector3f(0, 1, 0),
                ""
        );
    }

    private static List<String> idleUrls(int totalBytes) {
        ArrayList<String> urls = new ArrayList<>();
        int remaining = totalBytes;
        while (remaining >= 999) {
            urls.add("界".repeat(333));
            remaining -= 999;
        }
        if (remaining > 0) urls.add("x".repeat(remaining));
        return urls;
    }

    private static int utf8Bytes(List<String> values) {
        return values.stream().mapToInt(value -> value.getBytes(StandardCharsets.UTF_8).length).sum();
    }
}
