package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.provider.VideoInfo;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class PlaybackControllerTest {
    @Test
    void advancesFromFirstQueueItemToSecond() {
        Fixture fixture = new Fixture();
        VideoInfo first = info("A", "https://example.com/a.mp4", "");
        VideoInfo second = info("B", "https://example.com/b.mp4", "");

        fixture.add(first);
        fixture.add(second);
        assertSame(first, fixture.controller.currentInfo());

        fixture.listener("A").finish();

        assertSame(second, fixture.controller.currentInfo());
        assertEquals(1, fixture.queue.size());
        assertEquals(3, fixture.broadcaster.syncs);
    }

    @Test
    void removesFailedItemAndContinuesToThird() {
        Fixture fixture = new Fixture();
        VideoInfo first = info("A", "https://example.com/a.mp4", "");
        VideoInfo bad = info("bad", "", "bad-source");
        VideoInfo third = info("C", "https://example.com/c.mp4", "");

        fixture.add(first);
        fixture.add(bad);
        fixture.add(third);
        fixture.listener("A").finish();

        assertSame(third, fixture.controller.currentInfo());
        assertSame(third, fixture.queue.peek());
        assertEquals(1, fixture.queue.size());
    }

    @Test
    void stoppedResolutionDoesNotCreateAListener() {
        ArrayList<Runnable> resolutions = new ArrayList<>();
        AtomicInteger listeners = new AtomicInteger();
        VideoArea area = new VideoArea(new Vector3f(), new Vector3f(1), "area", "world");
        area.initServer();
        area.addPlayer(UUID.randomUUID());
        VideoScreen screen = new VideoScreen(area, "screen", new Vector3f(), new Vector3f(1, 0, 0),
                new Vector3f(1, 1, 0), new Vector3f(0, 1, 0), "");
        PlaybackQueue queue = new PlaybackQueue(screen);
        PlaybackController controller = new PlaybackController(
                screen,
                queue,
                new RecordingBroadcaster(screen),
                info -> info,
                url -> null,
                info -> {
                    listeners.incrementAndGet();
                    return new FakeListener();
                },
                resolutions::add,
                Runnable::run,
                (command, delay) -> command.run()
        );

        queue.add(info("A", "https://example.com/a.mp4", ""));
        controller.playNext();
        controller.stopAndClear(false);
        resolutions.getFirst().run();

        assertEquals(0, listeners.get());
    }

    private static VideoInfo info(String name, String path, String rawPath) {
        return new VideoInfo("player", name, path, rawPath, -1, true, new String[0], 1_000);
    }

    private static final class Fixture {
        private final PlaybackQueue queue;
        private final RecordingBroadcaster broadcaster;
        private final Map<String, FakeListener> listeners = new HashMap<>();
        private final PlaybackController controller;

        private Fixture() {
            VideoArea area = new VideoArea(new Vector3f(), new Vector3f(1), "area", "world");
            area.initServer();
            area.addPlayer(UUID.randomUUID());
            VideoScreen screen = new VideoScreen(area, "screen", new Vector3f(), new Vector3f(1, 0, 0),
                    new Vector3f(1, 1, 0), new Vector3f(0, 1, 0), "");
            queue = new PlaybackQueue(screen);
            broadcaster = new RecordingBroadcaster(screen);
            controller = new PlaybackController(
                    screen,
                    queue,
                    broadcaster,
                    info -> info.path().isBlank() ? null : info,
                    url -> null,
                    info -> listeners.computeIfAbsent(info.name(), ignored -> new FakeListener()),
                    Runnable::run,
                    Runnable::run,
                    (command, delay) -> command.run()
            );
        }

        private void add(VideoInfo info) {
            queue.add(info);
            controller.playNext();
        }

        private FakeListener listener(String name) {
            return listeners.get(name);
        }
    }

    private static final class RecordingBroadcaster extends ScreenBroadcaster {
        private int syncs;

        private RecordingBroadcaster(VideoScreen screen) {
            super(screen);
        }

        @Override
        public void send(byte[] data) {
        }

        @Override
        public void syncPlaylist() {
            syncs++;
        }
    }

    private static final class FakeListener implements IVideoListener {
        private boolean playing;
        private Consumer<Boolean> playingListener = ignored -> {};
        private Runnable stoppedListener = () -> {};

        @Override
        public long getProgress() {
            return 0;
        }

        @Override
        public boolean isPlaying() {
            return playing;
        }

        @Override
        public void playing(Consumer<Boolean> playing) {
            playingListener = playing;
        }

        @Override
        public void stopped(Runnable stopped) {
            stoppedListener = stopped;
        }

        @Override
        public void errored(Runnable errored) {
        }

        @Override
        public void timeout(Runnable timeout) {
        }

        @Override
        public void listen() {
            playing = true;
            playingListener.accept(true);
        }

        @Override
        public void cancel() {
            playing = false;
        }

        private void finish() {
            playing = false;
            stoppedListener.run();
        }
    }
}
