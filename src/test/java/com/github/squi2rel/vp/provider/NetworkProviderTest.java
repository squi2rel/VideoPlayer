package com.github.squi2rel.vp.provider;

import com.github.squi2rel.vp.video.IVideoListener;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkProviderTest {
    @Test
    void rejectsEmptyAndSingleCharacterInputs() {
        NetworkProvider provider = new NetworkProvider();
        NamedProviderSource source = new NamedProviderSource("test");

        assertNull(provider.from(null, source));
        assertNull(provider.from("", source));
        assertNull(provider.from("a", source));
        assertNull(provider.from("   ", source));
        assertNull(provider.from(" a ", source));
    }

    @Test
    void cancellationStopsListenerAndResolverThread() throws Exception {
        CountDownLatch listening = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicReference<Thread> resolver = new AtomicReference<>();
        NetworkProvider provider = new NetworkProvider(
                info -> new BlockingListener(listening, cancelled, resolver),
                host -> new java.net.InetAddress[]{java.net.InetAddress.getByAddress(host, new byte[]{93, (byte) 184, (byte) 216, 34})}
        );

        CompletableFuture<VideoInfo> future = provider.from("rtsp://example.invalid/live", new NamedProviderSource("test"));

        assertNotNull(future);
        assertTrue(listening.await(2, TimeUnit.SECONDS));
        assertTrue(future.cancel(false));
        assertTrue(cancelled.await(2, TimeUnit.SECONDS));
        Thread worker = resolver.get();
        assertNotNull(worker);
        worker.join(2000);
        assertTrue(future.isCancelled());
        assertTrue(!worker.isAlive());
    }

    @Test
    void rejectsHostsThatCannotBeResolvedByThePolicyResolver() {
        NetworkProvider provider = new NetworkProvider(info -> new BlockingListener(
                new CountDownLatch(1), new CountDownLatch(1), new AtomicReference<>()
        ), host -> { throw new java.net.UnknownHostException(host); });

        CompletableFuture<VideoInfo> future = provider.from("https://unknown.example.invalid/video", new NamedProviderSource("test"));
        assertNotNull(future);
        assertNull(future.join());
    }

    private static final class BlockingListener implements IVideoListener {
        private final CountDownLatch listening;
        private final CountDownLatch cancelled;
        private final AtomicReference<Thread> resolver;
        private volatile Runnable timeout = () -> {};

        private BlockingListener(CountDownLatch listening, CountDownLatch cancelled, AtomicReference<Thread> resolver) {
            this.listening = listening;
            this.cancelled = cancelled;
            this.resolver = resolver;
        }

        @Override
        public long getProgress() {
            return 0;
        }

        @Override
        public boolean isPlaying() {
            return false;
        }

        @Override
        public void playing(Consumer<Boolean> playing) {
        }

        @Override
        public void stopped(Runnable stopped) {
        }

        @Override
        public void errored(Runnable errored) {
        }

        @Override
        public void timeout(Runnable timeout) {
            this.timeout = timeout;
        }

        @Override
        public void listen() {
            resolver.set(Thread.currentThread());
            listening.countDown();
        }

        @Override
        public void cancel() {
            cancelled.countDown();
            timeout.run();
        }
    }
}
