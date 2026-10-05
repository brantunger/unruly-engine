package io.github.brantunger.unruly.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code concurrentPrepares} looks at every thread, each for what is left of its bound, and names a thread that hadn't
 * finished, and the step it was at, as it names one that failed (#1045). The bound is 30 seconds in the check, so the
 * part that waits is run here with a short one.
 */
@DisplayName("the concurrent-prepare check names a thread that hasn't finished in time, and still looks at the others"
        + " (#1045)")
class ConcurrentPreparesBoundTest {

    @Test
    @DisplayName("a thread that never finishes is named with its step, beside one that failed, and one that passed")
    void unfinishedThreadNamed() {
        IllegalStateException thrown = new IllegalStateException("the runtime failed to start");
        List<Future<Map<String, Object>>> results = List.of(new CompletableFuture<>(),
                CompletableFuture.failedFuture(thrown), CompletableFuture.completedFuture(Map.of("seen", 1)));
        List<AtomicReference<String>> steps = List.of(new AtomicReference<>("prepare()"),
                new AtomicReference<>("build()"), new AtomicReference<>("run()"));

        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> ExpressionLanguageContractTest.assertNoThreadFailed(results, steps, Duration.ofMillis(100)));

        assertEquals("prepare() must be safe to call on several threads at once, as the engine calls it without a lock"
                + " from concurrent build()s and first loads, but 2 of 3 threads that prepared one instance of the"
                + " language at once failed: thread 0, in prepare(), hadn't finished after 100 ms; thread 1, in"
                + " build(), threw java.lang.IllegalStateException: the runtime failed to start",
                failure.getMessage());
        assertArrayEquals(new Throwable[] {thrown}, failure.getSuppressed());
    }
}
