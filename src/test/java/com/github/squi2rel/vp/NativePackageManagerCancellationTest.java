package com.github.squi2rel.vp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativePackageManagerCancellationTest {
    @AfterEach
    void cancelDownloads() {
        NativePackageManager.cancelActiveDownloads();
    }

    @Test
    void inactiveGuardCancelsPendingHttpFuture() throws Exception {
        AtomicBoolean active = new AtomicBoolean(true);
        CountDownLatch waiting = new CountDownLatch(1);
        CompletableFuture<String> response = new CompletableFuture<>();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<String> result = caller.submit(() -> NativePackageManager.awaitDownloadFuture(
                    response,
                    () -> {
                        waiting.countDown();
                        return active.get();
                    }
            ));
            assertTrue(waiting.await(2, TimeUnit.SECONDS));

            active.set(false);

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> result.get(2, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
            assertTrue(response.isCancelled());
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void lifecycleCancellationStopsBlockedReadAndAllowsLaterReads() throws Exception {
        BlockingInputStream input = new BlockingInputStream();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> result = caller.submit(() -> NativePackageManager.readWithIdleTimeout(
                    input,
                    new byte[1],
                    () -> true
            ));
            assertTrue(input.started.await(2, TimeUnit.SECONDS));

            NativePackageManager.cancelActiveDownloads();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> result.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof CancellationException
                    || failure.getCause() instanceof IOException);
            assertTrue(input.closed.get());
            assertEquals(1, NativePackageManager.readWithIdleTimeout(
                    new ByteArrayInputStream(new byte[]{42}),
                    new byte[1],
                    () -> true
            ));
        } finally {
            input.close();
            caller.shutdownNow();
        }
    }

    private static final class BlockingInputStream extends InputStream {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public int read() throws IOException {
            byte[] value = new byte[1];
            return read(value, 0, 1) < 0 ? -1 : value[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            started.countDown();
            try {
                released.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", error);
            }
            if (closed.get()) throw new IOException("closed");
            return -1;
        }

        @Override
        public void close() {
            closed.set(true);
            released.countDown();
        }
    }
}
