package com.github.squi2rel.vp.network;

import com.github.squi2rel.vp.provider.VideoInfo;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerPacketHandlerFutureTest {
    @Test
    void cancellingGuardedFutureCancelsUpstream() {
        CompletableFuture<VideoInfo> upstream = new CompletableFuture<>();
        CompletableFuture<VideoInfo> guarded = ServerPacketHandler.guardNativeRuntime(upstream);

        guarded.cancel(true);

        assertTrue(upstream.isCancelled());
    }

    @Test
    void timingOutGuardedFutureCancelsUpstream() {
        CompletableFuture<VideoInfo> upstream = new CompletableFuture<>();
        CompletableFuture<VideoInfo> guarded = ServerPacketHandler.guardNativeRuntime(upstream);
        guarded.orTimeout(10, TimeUnit.MILLISECONDS);

        assertThrows(CompletionException.class, guarded::join);
        assertTrue(upstream.isCancelled());
    }

    @Test
    void successfulGuardDoesNotCancelUpstream() {
        CompletableFuture<VideoInfo> upstream = new CompletableFuture<>();
        CompletableFuture<VideoInfo> guarded = ServerPacketHandler.guardNativeRuntime(upstream);

        upstream.complete(null);

        assertNull(guarded.join());
        assertFalse(upstream.isCancelled());
    }
}
