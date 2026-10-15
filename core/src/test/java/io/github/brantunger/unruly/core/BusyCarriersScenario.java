package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CancelRegistration;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Run by {@link CancelCallbackTest} in a JVM whose virtual threads have one carrier: a run on a virtual thread, with a
 * timeout of 200 ms, whose action spins without blocking until the action it registered with onCancel stops it, as a
 * runtime that can only be stopped from outside does, so it holds the only carrier. It prints {@link #STOPPED} and how
 * long the spin took if the deadline's action stopped it, or {@link #NEVER_RAN} if the spin gave up after
 * {@link #CAP_MILLIS} ms, the deadline's action never having run.
 *
 * <p>
 * A run that passes its deadline before it reaches the action, or before the action registers, as one can on a machine
 * too busy to give this JVM the CPU for 200 ms, stops with its timeout without spinning, or has its action run at once
 * on its own thread, and shows nothing of the timer: it is run again, on a new virtual thread, up to {@link #ATTEMPTS}
 * times in all, and only the run that reached the action is judged. If none did, it prints {@link #NEVER_REACHED}. The
 * cap is long for the same reason: the spin reads the time, which goes on while the machine gives the JVM no CPU, and
 * only a timer that never runs the action makes it wait that long.
 * </p>
 */
final class BusyCarriersScenario {

    static final String STOPPED = "SCENARIO stopped after ";
    static final String NEVER_RAN = "SCENARIO never ran";
    static final String NEVER_REACHED = "SCENARIO's run passed its deadline before its action registered, in every"
            + " attempt: ";
    static final long CAP_MILLIS = 15_000;
    static final int ATTEMPTS = 20;

    private BusyCarriersScenario() {
    }

    // The registration is closed in a finally, as try-with-resources would warn that the body never reads it.
    @SuppressWarnings({"PMD.SystemPrintln", "PMD.UseTryWithResources"})
    public static void main(String[] args) throws InterruptedException {
        AtomicReference<String> outcome = new AtomicReference<>(NEVER_RAN);
        AtomicBoolean reached = new AtomicBoolean();
        CompiledAction spin = (context, session) -> {
            Thread runner = Thread.currentThread();
            AtomicBoolean stop = new AtomicBoolean();
            AtomicBoolean atOnce = new AtomicBoolean();
            long start = System.nanoTime();
            CancelRegistration deadline = context.onCancel(() -> {
                atOnce.set(Thread.currentThread() == runner);
                stop.set(true);
            });
            try {
                // Run on this thread before onCancel returned, the deadline having passed: the timer had no part.
                if (atOnce.get()) {
                    return ActionResult.done();
                }
                reached.set(true);
                while (!stop.get() && System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(CAP_MILLIS)) {
                    Thread.onSpinWait();
                }
                // Read before closing, which could let the carrier go to the action's thread.
                if (stop.get()) {
                    outcome.set(STOPPED + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + " ms");
                }
            } finally {
                deadline.close();
            }
            return ActionResult.done();
        };
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(
                HashMap::new).language(new StubExpressionLanguage().action(spin)).runTimeout(Duration.ofMillis(200))
                .build()) {
            engine.load(List.of(Rule.builder().ruleName("r").condition("c").action("a").build()));
            int attempts = 0;
            while (!reached.get() && attempts < ATTEMPTS) {
                attempts++;
                Thread run = Thread.ofVirtual().start(() -> {
                    try {
                        engine.run(new FactMap<>());
                    } catch (RuntimeException timedOut) {
                        // The run fails with its timeout either way: only how long the spin took tells.
                    }
                });
                run.join();
            }
            if (!reached.get()) {
                outcome.set(NEVER_REACHED + attempts);
            }
        }
        System.out.println(outcome.get());
    }
}
