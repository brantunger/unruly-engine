package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A run reads the engine's rules again when the set it read was closed before it could borrow a copy of it. A
 * reload does that to the set it replaces, and {@code close()} to the set it detaches: each retires it, and the last
 * run to give a copy back closes it. A run that finds a different closed set each time is being overtaken by
 * reloads, and reads again for as long as that goes on. The engine's own invariant makes the same closed set read
 * again impossible, so the bound on those readings is far above one; what it rules out is a run that finds the
 * engine's current set closed every time and spins for ever, leaving nobody a failed run or a stack trace to work
 * from.
 */
@DisplayName("a run that finds the rules it read closed reads them again, but not for ever")
class ClosedRulesRetryTest {

    /**
     * An engine whose {@code currentRules()} hands back the same closed rule set the first {@code closedReads} times,
     * and the rules it loaded after that.
     *
     * @param closedReads How many readings find a closed rule set
     * @param reads       Counts every reading, closed or not
     * @param runTimeout  How long a run may take, or {@code null} for no deadline
     * @return The engine, with an empty rule list loaded
     */
    private static AbstractRulesEngine<String> engineFindingClosedRules(int closedReads, AtomicInteger reads,
                                                                       Duration runTimeout) {
        return engineFindingClosedRules(closedReads, reads, runTimeout, List.of());
    }

    /**
     * An engine like {@link #engineFindingClosedRules(int, AtomicInteger, Duration)}, whose runs go to
     * {@code listeners}.
     */
    private static AbstractRulesEngine<String> engineFindingClosedRules(int closedReads, AtomicInteger reads,
                                                                       Duration runTimeout,
                                                                       List<RuleListener> listeners) {
        RuleSet closedRules = closedRules();
        return engineReading(closedReads, reads, runTimeout, listeners, () -> closedRules);
    }

    /**
     * A rule set no run holds a copy of, retired: retiring closes it there and then, which is the state a run can find
     * when a reload, or a close of the engine, retired the rules it had just read.
     */
    private static RuleSet closedRules() {
        RuleSet closedRules = TestRuleSets.ruleSet(List.of(), Map.of()).build();
        closedRules.retire();
        return closedRules;
    }

    /**
     * An engine like {@link #engineFindingClosedRules(int, AtomicInteger, Duration, List)}, whose readings that
     * find a closed rule set find the one {@code closed} gives each time.
     */
    private static AbstractRulesEngine<String> engineReading(int closedReads, AtomicInteger reads, Duration runTimeout,
                                                            List<RuleListener> listeners, Supplier<RuleSet> closed) {
        EngineConfiguration<String> configuration = TestConfigurations.engineConfiguration(
                        Map.of(ToyExpressionLanguage.LANGUAGE_NAME, new ToyExpressionLanguage()))
                .withListeners(listeners).withRunTimeout(runTimeout).build();
        AbstractRulesEngine<String> engine = new AbstractRulesEngine<>(String::new, configuration) {
            @Override
            RuleSet currentRules() {
                return reads.getAndIncrement() < closedReads ? closed.get() : super.currentRules();
            }

            @Override
            RunResult<String> runRules(FactStore<?> facts, Duration timeout, Set<String> tags) {
                return runInScope(facts, timeout, tags, (rules, copy, runFacts) ->
                        RunResult.of("ran", List.of(), rules.checksum()));
            }

            @Override
            String matchPolicy() {
                return "firstMatch";
            }
        };
        engine.load(List.of());
        return engine;
    }

    @Test
    @DisplayName("a run whose rules are closed a few times over reads them again until it borrows a copy")
    void aRunRecoversFromAFewClosedReadings() {
        AtomicInteger reads = new AtomicInteger();

        assertEquals("ran", engineFindingClosedRules(5, reads, null).run(new FactMap<>()));

        assertEquals(6, reads.get(), "one reading for each closed rule set, and one that found the engine's own");
    }

    @Test
    @DisplayName("a run that finds a closed rule set every time fails, naming the invariant that has broken")
    void aRunThatOnlyEverFindsClosedRulesFails() {
        AtomicInteger reads = new AtomicInteger();
        // More closed readings than the bound, but not endlessly many: a run that kept reading would end here too,
        // and fail this test by not throwing, rather than hold it for ever.
        AbstractRulesEngine<String> engine = engineFindingClosedRules(100, reads, null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> engine.run(new FactMap<>()));

        assertTrue(thrown.getMessage().contains(
                "was found closed 64 times in a row while this run was borrowing a copy of it"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("that invariant has broken"), thrown.getMessage());
        assertEquals(64, reads.get(), "the run read the rules again after it had given up");
    }

    @Test
    @DisplayName("a run overtaken by one reload after another reads the rules again for as long as it takes")
    void aRunOvertakenByReloadsKeepsReading() {
        AtomicInteger reads = new AtomicInteger();
        // A different closed rule set at every reading, far more of them than the bound: what a loader reloading fast
        // leaves a run, when each reload retires the set the run has just read. None of them is the engine's current
        // set, so no invariant has broken, and the run borrows once a reading finds that.
        AbstractRulesEngine<String> engine = engineReading(100, reads, null, List.of(),
                ClosedRulesRetryTest::closedRules);

        assertEquals("ran", engine.run(new FactMap<>()));

        assertEquals(101, reads.get(), "one reading for each closed rule set, and one that found the engine's own");
    }

    @Test
    @DisplayName("a run that finds one closed rule set many times, then another, counts each set's readings apart")
    void aRunCountsEachClosedSetInARowApart() {
        AtomicInteger reads = new AtomicInteger();
        RuleSet first = closedRules();
        RuleSet second = closedRules();
        // Forty readings of each, eighty in all: more than the bound together, but fewer than it in a row, which is
        // what the failure's message says happened.
        AbstractRulesEngine<String> engine = engineReading(80, reads, null, List.of(),
                () -> reads.get() <= 40 ? first : second);

        assertEquals("ran", engine.run(new FactMap<>()));

        assertEquals(81, reads.get(), "one reading for each closed rule set, and one that found the engine's own");
    }

    @Test
    @DisplayName("a run whose rules close() retired reads them again, finds no engine, and says the engine is closed")
    void aRunWhoseRulesCloseRetiredFindsNoEngine() {
        AtomicInteger reads = new AtomicInteger();
        // The shape an ordinary shutdown makes: the run read the rules, close() retired them before it could borrow
        // a copy, and reading again finds no rule set at all rather than the one a reload would have left.
        AbstractRulesEngine<String> engine = engineFindingClosedRules(1, reads, null);
        engine.close();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> engine.run(new FactMap<>()));

        // Exactly the message a new run on a closed engine gets, and nothing like the one the read bound throws: a
        // closed engine is the caller's own doing, and a broken invariant is the engine's, so the two must never
        // read alike.
        assertEquals("The engine is closed", thrown.getMessage());
        assertEquals(2, reads.get(), "the run read the rules again after finding the set it had read closed");
    }

    @Test
    @DisplayName("a run interrupted while its rules are closed stops there rather than reading them again")
    void anInterruptedRunStopsRatherThanReadingAgain() {
        AtomicInteger reads = new AtomicInteger();
        AbstractRulesEngine<String> engine = engineFindingClosedRules(5, reads, null);
        Thread.currentThread().interrupt();
        try {
            RuleExecutionException thrown = assertThrows(RuleExecutionException.class,
                    () -> engine.run(new FactMap<>()));

            assertTrue(thrown.getMessage().startsWith("run() was interrupted while reading the engine's rules again"),
                    thrown.getMessage());
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt status stays set");
            assertEquals(1, reads.get(), "the run read the rules again although it had been interrupted");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("a run interrupted while its rules are closed, whose listener clears the interrupt status, returns"
            + " with the status set")
    void anInterruptedRunKeepsTheStatusAListenerClears() {
        AtomicInteger reads = new AtomicInteger();
        AbstractRulesEngine<String> engine = engineFindingClosedRules(5, reads, null, List.of(new RuleListener() {
            @Override
            public void onRunError(RunContext run, RuntimeException error) {
                // As a listener that swallows an InterruptedException does.
                Thread.interrupted();
            }
        }));
        Thread.currentThread().interrupt();
        boolean interrupted;
        try {
            assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));
        } finally {
            // Cleared for the tests after this one, whatever happened.
            interrupted = Thread.interrupted();
        }

        assertTrue(interrupted, "the interrupt status after run()");
    }

    @Test
    @DisplayName("a run past its deadline while its rules are closed stops there rather than reading them again")
    void aRunPastItsDeadlineStopsRatherThanReadingAgain() {
        AtomicInteger reads = new AtomicInteger();
        // A timeout of zero, so the run's deadline has passed by the time it finds the rule set it read closed.
        AbstractRulesEngine<String> engine = engineFindingClosedRules(5, reads, Duration.ZERO);

        RuleExecutionException thrown = assertThrows(RuleExecutionException.class,
                () -> engine.run(new FactMap<>()));

        assertTrue(thrown.getMessage().contains("while reading the engine's rules again"), thrown.getMessage());
        assertTrue(thrown.getMessage().startsWith("run() passed its deadline of "), thrown.getMessage());
        assertEquals(1, reads.get(), "the run read the rules again although its deadline had passed");
    }
}
