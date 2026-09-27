package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A deadline decides on {@link System#nanoTime()} alone, and shows the run's start on the system clock plus its
 * timeout.
 */
@DisplayName("a run's deadline passes after its timeout, and shows when that is on the system clock")
class DeadlineTest {

    /**
     * The outer run's timeout in {@link #aNestedRunWithAFarDeadlineStopsAtAPassedOuterOne}: long enough that a slow
     * runner still reaches the outer rule's action before it passes.
     */
    private static final Duration OUTER_TIMEOUT = Duration.ofSeconds(1);

    @Test
    @DisplayName("no deadline never passes, shows nothing, and leaves all the time there is")
    void none() {
        assertFalse(Deadline.NONE.isSet());
        assertFalse(Deadline.NONE.hasPassed());
        assertNull(Deadline.NONE.instant());
        assertEquals(Long.MAX_VALUE, Deadline.NONE.nanosLeft());
        assertEquals(Duration.ofNanos(Long.MAX_VALUE), Deadline.NONE.timeLeft());
        assertSame(Deadline.NONE, Deadline.at(null));
    }

    @Test
    @DisplayName("a deadline is ahead until its timeout has gone by, and then has passed, with no time left")
    void passesAfterItsTimeout() {
        Duration timeout = Duration.ofMillis(20);
        long start = System.nanoTime();
        Deadline deadline = Deadline.from(timeout);

        assertTrue(deadline.isSet());
        assertTrue(deadline.nanosLeft() <= timeout.toNanos());
        while (!deadline.hasPassed()) {
            Thread.onSpinWait();
        }

        assertTrue(System.nanoTime() - start >= timeout.toNanos(), "it passed before its timeout had gone by");
        assertEquals(Duration.ZERO, deadline.timeLeft());
        assertTrue(deadline.nanosLeft() <= 0);
    }

    @Test
    @DisplayName("a deadline shows the system clock's time when it was set, plus its timeout, the same each time")
    void showsStartPlusTimeout() {
        Duration timeout = Duration.ofSeconds(30);
        Instant before = Instant.now();
        Deadline deadline = Deadline.from(timeout);
        Instant after = Instant.now();

        Instant shown = deadline.instant();
        assertFalse(shown.isBefore(before.plus(timeout)), shown + " is before " + before.plus(timeout));
        assertFalse(shown.isAfter(after.plus(timeout)), shown + " is after " + after.plus(timeout));
        assertSame(shown, deadline.instant(), "worked out once");
    }

    @Test
    @DisplayName("a deadline at an instant shows it, and one already past has passed")
    void atAnInstant() {
        Instant ahead = Instant.now().plusSeconds(60);
        Instant past = Instant.now().minusSeconds(60);

        assertSame(ahead, Deadline.at(ahead).instant());
        assertFalse(Deadline.at(ahead).hasPassed());
        assertTrue(Deadline.at(past).hasPassed());
        assertTrue(Deadline.at(Instant.MIN).hasPassed(), "the earliest instant there is overflows nothing");
        assertFalse(Deadline.at(Instant.MAX).hasPassed(), "nor does the latest");
        assertEquals(Instant.MAX, Deadline.at(Instant.MAX).instant());
    }

    @Test
    @DisplayName("a deadline created at an instant shows that instant however it's worked out, even on a thread that"
            + " sees nothing worked out yet")
    void atAnInstantWorksItsInstantOutAgain() throws ReflectiveOperationException {
        Instant ahead = Instant.now().plusSeconds(60);
        Deadline deadline = Deadline.at(ahead);
        // What a thread that reads the cache before another thread's write reaches it sees.
        Field shown = Deadline.class.getDeclaredField("shown");
        shown.setAccessible(true);
        shown.set(deadline, null);

        assertEquals(ahead, deadline.instant());
    }

    @Test
    @DisplayName("deadlines created at the same instant are equal, and any other deadline equals only itself")
    void equality() {
        Instant instant = Instant.parse("2030-01-01T00:00:00Z");
        Deadline at = Deadline.at(instant);
        Deadline from = Deadline.from(Duration.ofSeconds(60));

        assertEquals(at, Deadline.at(instant));
        assertEquals(at.hashCode(), Deadline.at(instant).hashCode());
        assertNotEquals(at, Deadline.at(instant.plusSeconds(1)));
        assertEquals(from, from);
        assertNotEquals(from, Deadline.from(Duration.ofSeconds(60)));
        assertNotEquals(at, from);
        assertNotEquals(from, at);
        assertNotEquals(at, instant);
        assertEquals(System.identityHashCode(from), from.hashCode());
    }

    @Test
    @DisplayName("an outer deadline already past wins over any deadline of the run's own, however far away")
    void aPassedOuterDeadlineWinsOverAFarOne() throws InterruptedException {
        Deadline outer = Deadline.from(Duration.ofMillis(1));
        while (!outer.hasPassed()) {
            Thread.sleep(1);
        }

        assertSame(outer, Deadline.earliest(Deadline.from(ChronoUnit.FOREVER.getDuration()), outer));
        assertSame(outer, Deadline.earliest(Deadline.from(Duration.ofSeconds(Long.MAX_VALUE)), outer));
        assertSame(outer, Deadline.earliest(Deadline.from(Duration.ofDays(1)), outer));
        Deadline soon = Deadline.from(Duration.ofSeconds(1));
        assertSame(soon, Deadline.earliest(soon, Deadline.from(ChronoUnit.FOREVER.getDuration())));
    }

    @Test
    @DisplayName("a run with a deadline as far away as there are, started after its outer run's has passed, stops at"
            + " its first rule")
    void aNestedRunWithAFarDeadlineStopsAtAPassedOuterOne() {
        List<String> nested = new ArrayList<>();
        RulesEngine<Map<String, Object>> inner = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(calling(() -> nested.add("inner rule ran"))).runTimeout(ChronoUnit.FOREVER.getDuration())
                .build();
        inner.load(List.of(Rule.builder().ruleName("inner").condition("c").action("a").build()));
        RulesEngine<Map<String, Object>> outer = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(calling(() -> {
                    try {
                        // Past the outer run's deadline, as System.nanoTime() measures it from when the action began,
                        // which is after the run did, with half a second to spare.
                        long until = System.nanoTime() + OUTER_TIMEOUT.plusMillis(500).toNanos();
                        for (long left = until - System.nanoTime(); left > 0; left = until - System.nanoTime()) {
                            Thread.sleep(TimeUnit.NANOSECONDS.toMillis(left) + 1);
                        }
                        inner.run(new FactMap<>());
                        nested.add("inner run returned");
                    } catch (RuleExecutionException e) {
                        nested.add(e.getMessage().replaceAll("of \\S+ ", "of <deadline> "));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })).runTimeout(OUTER_TIMEOUT).build();
        outer.load(List.of(Rule.builder().ruleName("outer").condition("c").action("a").build()));

        assertThrows(RuleExecutionException.class, () -> outer.run(new FactMap<>()));

        assertEquals(List.of("run() passed its deadline of <deadline> before rule 'inner'"), nested);
    }

    /** A language whose conditions are true, and whose actions run {@code action}. */
    private static ExpressionLanguage calling(Runnable action) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return "calling";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression source) {
                        return (evaluation, session) -> true;
                    }

                    @Override
                    public CompiledAction compileAction(Expression source) {
                        return (evaluation, session) -> {
                            action.run();
                            return ActionResult.done();
                        };
                    }

                    @Override
                    public Session newSession() {
                        return Session.none();
                    }
                };
            }
        };
    }

    @Test
    @DisplayName("of two deadlines that pass together, the outer run's wins, so a nested run shares it")
    void aTieGoesToTheOuterDeadline() {
        Deadline deadline = Deadline.from(Duration.ofSeconds(60));

        assertSame(deadline, Deadline.earliest(deadline, deadline));
    }

    /** A language whose condition keeps the context it's given and waits until its run must stop. */
    private static ExpressionLanguage waiting(AtomicReference<EvaluationContext> kept, List<String> seen) {
        return new ExpressionLanguage() {
            @Override
            public String name() {
                return "waiting";
            }

            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                return new ExpressionCompiler() {
                    @Override
                    public CompiledCondition compileCondition(Expression source) {
                        return (evaluation, session) -> {
                            kept.set(evaluation);
                            // Each is asked while the other still says the run may go on, so they never disagree.
                            while (!evaluation.isCancelled()) {
                                Thread.onSpinWait();
                            }
                            seen.add("timeLeft=" + evaluation.timeLeft());
                            return true;
                        };
                    }

                    @Override
                    public CompiledAction compileAction(Expression source) {
                        return (action, session) -> ActionResult.done();
                    }

                    @Override
                    public Session newSession() {
                        return Session.none();
                    }
                };
            }
        };
    }

    @Test
    @DisplayName("a language sees the run's start on the system clock plus its timeout, and no time left once the run"
            + " is cancelled")
    void aLanguageSeesTheDeadlineAndTheTimeLeft() {
        Duration timeout = Duration.ofMillis(50);
        AtomicReference<EvaluationContext> kept = new AtomicReference<>();
        List<String> seen = new ArrayList<>();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(waiting(kept, seen)).runTimeout(timeout).build();
        engine.load(List.of(Rule.builder().ruleName("r").condition("c").action("a").build()));

        Instant before = Instant.now();
        assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));
        Instant after = Instant.now();

        Instant shown = kept.get().deadline();
        // Read with the system clock, as the run's start was: a step of that clock while this ran would widen this.
        assertFalse(shown.isBefore(before.plus(timeout)), shown + " is before " + before.plus(timeout));
        assertFalse(shown.isAfter(after.plus(timeout)), shown + " is after " + after.plus(timeout));
        assertEquals(List.of("timeLeft=PT0S"), seen, "cancelled with time left");
        assertTrue(kept.get().isCancelled());
        assertEquals(Duration.ZERO, kept.get().timeLeft());
    }

    @Test
    @DisplayName("a context has time left exactly until it's cancelled")
    void timeLeftAndCancelledAgree() {
        for (int i = 0; i < 100; i++) {
            EvaluationContext cancelled = new EngineEvaluationContext(Map.of(),
                    Deadline.from(Duration.ofNanos(100_000)));
            while (!cancelled.isCancelled()) {
                Thread.onSpinWait();
            }
            assertEquals(Duration.ZERO, cancelled.timeLeft(), "cancelled with time left");

            EvaluationContext runOut = new EngineEvaluationContext(Map.of(), Deadline.from(Duration.ofNanos(100_000)));
            while (runOut.timeLeft().isPositive()) {
                Thread.onSpinWait();
            }
            assertTrue(runOut.isCancelled(), "no time left, but not cancelled");
        }
    }
}
