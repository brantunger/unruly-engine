package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.OutputWriter;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A run reads the engine's rules again when the set it read was closed before it could borrow a copy of it. A
 * reload does that to the set it replaces, and {@code close()} to the set it detaches: each retires it, and the last
 * run to give a copy back closes it. The engine's own invariant makes one reading enough, so the bound is far above
 * it; what the bound rules out is a run that finds a closed set every time and spins for ever, leaving nobody a
 * failed run or a stack trace to work from.
 */
@DisplayName("a run that finds the rules it read closed reads them again, but not for ever")
class ClosedRulesRetryTest {

    /**
     * An engine whose {@code currentRules()} hands back a closed rule set the first {@code closedReads} times, and
     * the rules it loaded after that.
     *
     * @param closedReads How many readings find a closed rule set
     * @param reads       Counts every reading, closed or not
     * @param runTimeout  How long a run may take, or {@code null} for no deadline
     * @return The engine, with an empty rule list loaded
     */
    private static AbstractRulesEngine<String> engineFindingClosedRules(int closedReads, AtomicInteger reads,
                                                                       Duration runTimeout) {
        // A rule set no run holds a copy of, retired: retiring closes it there and then, which is the state a run
        // can find when a reload, or a close of the engine, retired the rules it had just read.
        RuleSet closedRules = new RuleSet(List.of(), Map.of(), CopyLimit.none(), new CopyPermits(RuleSet.UNLIMITED));
        closedRules.retire();
        EngineConfiguration<String> configuration = new EngineConfiguration<>(List.of(new ToyExpressionLanguage()),
                null, List.of(), List.of(), CopyLimit.none(), 0, runTimeout, Clock.systemUTC(), Object.class,
                OutputWriter.beansAndMaps(), Map.of(), Map.of(), false);
        AbstractRulesEngine<String> engine = new AbstractRulesEngine<>(configuration) {
            @Override
            RuleSet currentRules() {
                return reads.getAndIncrement() < closedReads ? closedRules : super.currentRules();
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

        assertTrue(thrown.getMessage().contains("closed 64 times in a row"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("that invariant has broken"), thrown.getMessage());
        assertEquals(64, reads.get(), "the run read the rules again after it had given up");
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
