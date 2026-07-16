package com.github.squi2rel.vp.video;

import com.github.squi2rel.vp.provider.VideoInfo;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderedPlayAdmissionsTest {
    @Test
    void commitsInReservationOrderWhenSecondCompletesFirst() {
        Fixture fixture = new Fixture();
        CompletableFuture<VideoInfo> firstFuture = new CompletableFuture<>();
        CompletableFuture<VideoInfo> secondFuture = new CompletableFuture<>();
        List<OrderedPlayAdmissions.Result> firstResults = new ArrayList<>();
        List<OrderedPlayAdmissions.Result> secondResults = new ArrayList<>();
        VideoInfo first = info("A");
        VideoInfo second = info("B");

        OrderedPlayAdmissions.Reservation firstReservation = fixture.admissions.reserve(firstResults::add);
        OrderedPlayAdmissions.Reservation secondReservation = fixture.admissions.reserve(secondResults::add);
        fixture.admissions.attach(firstReservation, firstFuture);
        fixture.admissions.attach(secondReservation, secondFuture);

        secondFuture.complete(second);

        assertTrue(fixture.screen.accepted.isEmpty());
        assertTrue(firstResults.isEmpty());
        assertTrue(secondResults.isEmpty());

        firstFuture.complete(first);

        assertEquals(List.of(first, second), fixture.screen.accepted);
        assertEquals(1, fixture.screen.batches);
        assertEquals(1, firstResults.size());
        assertEquals(1, secondResults.size());
        assertTrue(firstResults.getFirst().success());
        assertTrue(secondResults.getFirst().success());
        assertEquals(0, fixture.admissions.pendingCount());
    }

    @Test
    void skipsFailedItemAndCommitsFirstAndThirdTogether() {
        Fixture fixture = new Fixture();
        CompletableFuture<VideoInfo> firstFuture = new CompletableFuture<>();
        CompletableFuture<VideoInfo> badFuture = new CompletableFuture<>();
        CompletableFuture<VideoInfo> thirdFuture = new CompletableFuture<>();
        List<OrderedPlayAdmissions.Result> firstResults = new ArrayList<>();
        List<OrderedPlayAdmissions.Result> badResults = new ArrayList<>();
        List<OrderedPlayAdmissions.Result> thirdResults = new ArrayList<>();
        VideoInfo first = info("A");
        VideoInfo third = info("C");

        OrderedPlayAdmissions.Reservation firstReservation = fixture.admissions.reserve(firstResults::add);
        OrderedPlayAdmissions.Reservation badReservation = fixture.admissions.reserve(badResults::add);
        OrderedPlayAdmissions.Reservation thirdReservation = fixture.admissions.reserve(thirdResults::add);
        fixture.admissions.attach(firstReservation, firstFuture);
        fixture.admissions.attach(badReservation, badFuture);
        fixture.admissions.attach(thirdReservation, thirdFuture);

        thirdFuture.complete(third);
        badFuture.completeExceptionally(new IllegalStateException("bad"));
        firstFuture.complete(first);

        assertEquals(List.of(first, third), fixture.screen.accepted);
        assertEquals(1, fixture.screen.batches);
        assertTrue(firstResults.getFirst().success());
        assertFalse(badResults.getFirst().success());
        assertTrue(thirdResults.getFirst().success());
        assertEquals(0, fixture.admissions.pendingCount());
    }

    @Test
    void rejectsThirtyThirdCombinedQueueAndPendingItem() {
        Fixture fixture = new Fixture();
        fixture.screen.queueSize = 30;

        assertNotNull(fixture.admissions.reserve(result -> {}));
        assertNotNull(fixture.admissions.reserve(result -> {}));
        assertNull(fixture.admissions.reserve(result -> {}));
        assertEquals(2, fixture.admissions.pendingCount());
    }

    @Test
    void staleCompletionDoesNotLeaveLaterSequenceBlocked() {
        Fixture fixture = new Fixture();
        CompletableFuture<VideoInfo> firstFuture = new CompletableFuture<>();
        CompletableFuture<VideoInfo> staleFuture = new CompletableFuture<>();
        CompletableFuture<VideoInfo> thirdFuture = new CompletableFuture<>();
        List<OrderedPlayAdmissions.Result> firstResults = new ArrayList<>();
        List<OrderedPlayAdmissions.Result> staleResults = new ArrayList<>();
        List<OrderedPlayAdmissions.Result> thirdResults = new ArrayList<>();
        VideoInfo first = info("A");
        VideoInfo third = info("C");

        OrderedPlayAdmissions.Reservation firstReservation = fixture.admissions.reserve(firstResults::add);
        OrderedPlayAdmissions.Reservation staleReservation = fixture.admissions.reserve(staleResults::add);
        OrderedPlayAdmissions.Reservation thirdReservation = fixture.admissions.reserve(thirdResults::add);
        fixture.admissions.attach(firstReservation, firstFuture);
        fixture.admissions.attach(staleReservation, staleFuture);
        fixture.admissions.attach(thirdReservation, thirdFuture);

        fixture.screen.lifecycleCurrent = false;
        staleFuture.complete(info("stale"));
        fixture.screen.lifecycleCurrent = true;
        thirdFuture.complete(third);
        firstFuture.complete(first);

        assertEquals(List.of(first, third), fixture.screen.accepted);
        assertEquals(1, fixture.screen.batches);
        assertTrue(firstResults.getFirst().success());
        assertFalse(staleResults.getFirst().success());
        assertInstanceOf(java.util.concurrent.CancellationException.class, staleResults.getFirst().error());
        assertTrue(thirdResults.getFirst().success());
        assertEquals(0, fixture.admissions.pendingCount());
    }

    @Test
    void controlledTimeoutFailsWithoutWaitingForProductionTimeout() {
        ControlledTimeout timeout = new ControlledTimeout();
        Fixture fixture = new Fixture(timeout);
        CompletableFuture<VideoInfo> future = new CompletableFuture<>();
        List<OrderedPlayAdmissions.Result> results = new ArrayList<>();
        OrderedPlayAdmissions.Reservation reservation = fixture.admissions.reserve(results::add);
        fixture.admissions.attach(reservation, future);

        timeout.trigger();
        future.complete(info("late"));

        assertEquals(1, results.size());
        assertFalse(results.getFirst().success());
        assertInstanceOf(TimeoutException.class, results.getFirst().error());
        assertTrue(fixture.screen.accepted.isEmpty());
        assertEquals(0, fixture.admissions.pendingCount());
    }

    @Test
    void callbackIsNotRepeatedAfterCompletionFailureAndClose() {
        Fixture fixture = new Fixture();
        CompletableFuture<VideoInfo> future = new CompletableFuture<>();
        List<OrderedPlayAdmissions.Result> results = new ArrayList<>();
        OrderedPlayAdmissions.Reservation reservation = fixture.admissions.reserve(results::add);
        fixture.admissions.attach(reservation, future);

        future.complete(info("A"));
        fixture.admissions.fail(reservation, new IllegalStateException("late failure"));
        fixture.admissions.close();

        assertEquals(1, results.size());
        assertTrue(results.getFirst().success());
    }

    @Test
    void closeThenFutureCompletionDoesNotNotifyTwice() {
        Fixture fixture = new Fixture();
        CompletableFuture<VideoInfo> future = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return false;
            }
        };
        List<OrderedPlayAdmissions.Result> results = new ArrayList<>();
        OrderedPlayAdmissions.Reservation reservation = fixture.admissions.reserve(results::add);
        fixture.admissions.attach(reservation, future);

        fixture.admissions.close();
        future.complete(info("late"));

        assertEquals(1, results.size());
        assertFalse(results.getFirst().success());
        assertInstanceOf(java.util.concurrent.CancellationException.class, results.getFirst().error());
        assertTrue(fixture.screen.accepted.isEmpty());
        assertEquals(0, fixture.admissions.pendingCount());
    }

    private static VideoInfo info(String name) {
        return new VideoInfo("player", name, "https://example.com/" + name + ".mp4", "", -1, true, new String[0], 1_000);
    }

    private static final class Fixture {
        private final FakeScreen screen = new FakeScreen();
        private final OrderedPlayAdmissions admissions;

        private Fixture() {
            this(UnaryOperator.identity());
        }

        private Fixture(UnaryOperator<CompletableFuture<VideoInfo>> timeout) {
            admissions = new OrderedPlayAdmissions(
                    screen,
                    timeout,
                    (epoch, command) -> command.run(),
                    screen::isLifecycleCurrent
            );
        }
    }

    private static final class FakeScreen extends VideoScreen {
        private final ScreenLifecycleToken token = new ScreenLifecycleToken(1, 1, new ScreenKey("world", "area", "screen"));
        private final ArrayList<VideoInfo> accepted = new ArrayList<>();
        private int queueSize;
        private int batches;
        private boolean lifecycleCurrent = true;

        private FakeScreen() {
            super(
                    new VideoArea(new Vector3f(), new Vector3f(1), "area", "world"),
                    "screen",
                    new Vector3f(),
                    new Vector3f(1, 0, 0),
                    new Vector3f(1, 1, 0),
                    new Vector3f(0, 1, 0),
                    ""
            );
        }

        @Override
        public boolean serverActive() {
            return true;
        }

        @Override
        public int queueSize() {
            return queueSize;
        }

        @Override
        public ScreenLifecycleToken captureLifecycleToken() {
            return token;
        }

        @Override
        public boolean isLifecycleCurrent(ScreenLifecycleToken candidate) {
            return lifecycleCurrent && token.equals(candidate);
        }

        @Override
        public void addResolvedInfos(List<VideoInfo> resolved) {
            accepted.addAll(resolved);
            queueSize += resolved.size();
            batches++;
        }
    }

    private static final class ControlledTimeout implements UnaryOperator<CompletableFuture<VideoInfo>> {
        private CompletableFuture<VideoInfo> result;

        @Override
        public CompletableFuture<VideoInfo> apply(CompletableFuture<VideoInfo> source) {
            CompletableFuture<VideoInfo> timed = new CompletableFuture<>();
            result = timed;
            source.whenComplete((info, error) -> {
                if (error == null) {
                    timed.complete(info);
                } else {
                    timed.completeExceptionally(error);
                }
            });
            return timed;
        }

        private void trigger() {
            result.completeExceptionally(new TimeoutException("controlled timeout"));
        }
    }
}
