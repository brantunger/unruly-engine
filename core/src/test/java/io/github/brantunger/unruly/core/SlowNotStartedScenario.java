package io.github.brantunger.unruly.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Run by {@link CancelTimerTest} in a JVM whose virtual threads have one carrier, with the engine's logging at WARN: a
 * virtual thread spins without blocking, so it holds the only carrier, while the cancel timer starts an action whose
 * pool can't make it a thread, so it starts it on a virtual thread, which no carrier is free to run. The spin ends
 * once the timer has logged the action as not started, and the scenario prints {@link #NOT_RUN} if the action hadn't
 * run by then, or {@link #RAN} if it had, or {@link #NOT_LOGGED} if nothing was logged in {@link #CAP_MILLIS} ms.
 */
final class SlowNotStartedScenario {

    static final String NOT_RUN = "SCENARIO logged as not started before it ran";
    static final String RAN = "SCENARIO ran before it was logged";
    static final String NOT_LOGGED = "SCENARIO nothing logged";
    static final long CAP_MILLIS = 5000;
    // What the timer logs once the action is slow, without the time, which the test checks.
    private static final String NOT_STARTED = "hasn't started after";

    private SlowNotStartedScenario() {
    }

    @SuppressWarnings("PMD.SystemPrintln")
    public static void main(String[] args) throws InterruptedException {
        // The timer's WARNs go to System.err, read when it logs: copied here, so the spin can tell when one is logged.
        AtomicBoolean logged = new AtomicBoolean();
        PrintStream original = System.err;
        System.setErr(new PrintStream(new Watched(original, logged), true, StandardCharsets.UTF_8));
        CancelTimer.slowAfter(TimeUnit.MILLISECONDS.toNanos(200));
        AtomicBoolean ran = new AtomicBoolean();
        CountDownLatch ranAtLast = new CountDownLatch(1);
        AtomicBoolean spinning = new AtomicBoolean();
        String[] outcome = new String[1];
        Thread spin = Thread.ofVirtual().start(() -> {
            spinning.set(true);
            long start = System.nanoTime();
            while (!logged.get() && System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(CAP_MILLIS)) {
                Thread.onSpinWait();
            }
            outcome[0] = ran.get() ? RAN : logged.get() ? NOT_RUN : NOT_LOGGED;
        });
        while (!spinning.get()) {
            Thread.onSpinWait();
        }
        // Keeps the timer running, so the fault can be set for its thread before it starts the action.
        CancelTimer.Registration keeper = CancelTimer.registration(() -> {
        }, Deadline.from(Duration.ofHours(1)));
        CancelTimer.schedule(keeper);
        Faults.inject(CancelTimer.runningThread(), Faults.Step.CANCEL_ACTION_STARTING, 1,
                new OutOfMemoryError("unable to create native thread"));
        CancelTimer.schedule(CancelTimer.registration(() -> {
            ran.set(true);
            ranAtLast.countDown();
        }, Deadline.from(Duration.ofMillis(50))));
        spin.join();
        // Once the spin lets the carrier go, the action runs after all.
        boolean ranAfter = ranAtLast.await(10, TimeUnit.SECONDS);
        keeper.close();
        System.setErr(original);
        System.out.println(outcome[0]);
        System.out.println("SCENARIO ran once the carrier was free: " + ranAfter);
    }

    /** Copies what is written to the stream it wraps, and records once the timer has logged an action not started. */
    private static final class Watched extends OutputStream {
        private final PrintStream target;
        private final AtomicBoolean logged;
        private final ByteArrayOutputStream written = new ByteArrayOutputStream();

        Watched(PrintStream target, AtomicBoolean logged) {
            this.target = target;
            this.logged = logged;
        }

        @Override
        public synchronized void write(int b) {
            target.write(b);
            written.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            target.write(b, off, len);
            written.write(b, off, len);
            check();
        }

        @Override
        public synchronized void flush() throws IOException {
            target.flush();
            check();
        }

        private void check() {
            if (written.toString(StandardCharsets.UTF_8).contains(NOT_STARTED)) {
                logged.set(true);
            }
        }
    }
}
