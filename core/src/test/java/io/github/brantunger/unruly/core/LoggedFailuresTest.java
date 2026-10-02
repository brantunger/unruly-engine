package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import io.github.brantunger.unruly.core.EngineLogs.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.brantunger.unruly.core.EngineLogs.capture;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The thread's record of the failures nested runs and loads logged: made only for a nested one, forgotten when the
 * outermost run ends, and bounded, so a failure logged before the last {@value LoggedFailures#MAX_LOGGED} is logged
 * again if it's thrown on, and none is ever left out. The fatal {@link Error}s runs logged, and the exceptions the
 * engine built around a failure, are bounded the same way.
 */
@DisplayName("the thread's record of what nested runs logged is made only when nested, and bounded")
class LoggedFailuresTest {

    private static final String OUTPUT_REJECTED = "'output' is reserved for the output object and cannot be used as "
            + "a fact name";

    /**
     * Reads the ring the thread records logged failures in, as the outermost run on the thread leaves it.
     *
     * @return The ring, or {@code null} if none was created, or no run is in progress on this thread
     */
    private static Object ring() throws ReflectiveOperationException {
        Field runsField = LoggedFailures.class.getDeclaredField("RUNS");
        runsField.setAccessible(true);
        Object runs = ((ThreadLocal<?>) runsField.get(null)).get();
        if (runs == null) {
            return null;
        }
        Field logged = runs.getClass().getDeclaredField("logged");
        logged.setAccessible(true);
        return logged.get(runs);
    }

    private static RulesEngine<Map<String, Object>> plain(String ruleName, RuleListener... listeners) {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.<Map<String, Object>>firstMatch(
                HashMap::new).language(new StubExpressionLanguage());
        for (RuleListener listener : listeners) {
            builder.listener(listener);
        }
        RulesEngine<Map<String, Object>> engine = builder.build();
        engine.load(List.of(Rule.builder().ruleName(ruleName).condition("c").action("a").build()));
        return engine;
    }

    private static void runWithOutputFact(RulesEngine<Map<String, Object>> engine) {
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("output", 1);
        engine.run(facts);
    }

    @Test
    @DisplayName("an outermost run records nothing it logs, and creates nothing to record it in")
    void outermostRecordsNothing() throws ReflectiveOperationException {
        IllegalArgumentException failure = new IllegalArgumentException("top level");
        LoggedFailures.enter();
        try {
            assertSame(failure, LoggedFailures.loggedByRun(failure));
            assertSame(failure, LoggedFailures.loggedByLoad(failure));
            assertNull(LoggedFailures.find(failure));
            assertNull(Failures.nestedRunFailure(failure));
            assertNull(ring());
        } finally {
            LoggedFailures.leave();
        }
    }

    @Test
    @DisplayName("a top-level run that rejects its facts leaves the thread with no ring while its listeners hear of it")
    void topLevelRejectionCreatesNoRing() {
        AtomicReference<Object> ring = new AtomicReference<>("not read");
        RulesEngine<Map<String, Object>> engine = plain("r", new RuleListener() {
            @Override
            public void onRunError(RunContext run, RuntimeException error) {
                try {
                    ring.set(ring());
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        });

        Outcome<IllegalArgumentException> outcome = capture(IllegalArgumentException.class,
                () -> runWithOutputFact(engine));

        assertEquals(List.of(OUTPUT_REJECTED), outcome.lines("ERROR"), outcome.logs());
        assertNull(ring.get());
    }

    @Test
    @DisplayName("a nested run's failure is recorded, with whether a load() logged it, and forgotten with the "
            + "outermost run")
    void nestedRecordedThenForgotten() {
        IllegalArgumentException byRun = new IllegalArgumentException("by run");
        IllegalStateException byLoad = new IllegalStateException("by load");
        LoggedFailures.enter();
        try {
            LoggedFailures.enter();
            try {
                LoggedFailures.loggedByRun(byRun);
                LoggedFailures.loggedByLoad(byLoad);
            } finally {
                LoggedFailures.leave();
            }
            assertFalse(LoggedFailures.find(byRun).byLoad());
            assertTrue(LoggedFailures.find(byLoad).byLoad());
            assertNull(LoggedFailures.find(new IllegalArgumentException("by run")));
            assertEquals("wrapped (after a nested load() failed: by load)",
                    Failures.describe(new IllegalStateException("wrapped", byLoad)));
        } finally {
            LoggedFailures.leave();
        }
        assertNull(LoggedFailures.find(byRun));
        assertEquals("wrapped", Failures.describe(new IllegalStateException("wrapped", byLoad)));
    }

    @Test
    @DisplayName("the ring of fatal Errors exists as soon as a run starts, so recording an OutOfMemoryError allocates"
            + " nothing")
    void fatalRingMadeBeforeAnyFailure() throws ReflectiveOperationException {
        Field runsField = LoggedFailures.class.getDeclaredField("RUNS");
        runsField.setAccessible(true);
        LoggedFailures.enter();
        try {
            Object runs = ((ThreadLocal<?>) runsField.get(null)).get();
            Field loggedFatal = runs.getClass().getDeclaredField("loggedFatal");
            loggedFatal.setAccessible(true);
            Object ring = loggedFatal.get(runs);
            assertEquals(LoggedFailures.MAX_LOGGED, ((Error[]) ring).length);
            assertTrue(LoggedFailures.unloggedFatal(new OutOfMemoryError("heap")));
            assertSame(ring, loggedFatal.get(runs), "a ring made while recording");
        } finally {
            LoggedFailures.leave();
        }
    }

    @Test
    @DisplayName("a fatal Error logged once stays logged while fewer than the bound of others are logged after it")
    void fatalStaysLoggedPastOthers() {
        InternalError first = new InternalError("first");
        LoggedFailures.enter();
        try {
            assertTrue(LoggedFailures.unloggedFatal(first));
            for (int i = 1; i < LoggedFailures.MAX_LOGGED; i++) {
                LoggedFailures.enter();
                try {
                    assertTrue(LoggedFailures.unloggedFatal(new InternalError("nested " + i)));
                } finally {
                    LoggedFailures.leave();
                }
            }
            assertTrue(LoggedFailures.logged(first));
            assertFalse(LoggedFailures.unloggedFatal(first));
        } finally {
            LoggedFailures.leave();
        }
    }

    @Test
    @DisplayName("past the bound, the oldest fatal Error logged is logged again, and the newest isn't")
    void boundedFatalRecord() {
        InternalError first = new InternalError("first");
        InternalError last = null;
        LoggedFailures.enter();
        try {
            assertTrue(LoggedFailures.unloggedFatal(first));
            for (int i = 1; i <= LoggedFailures.MAX_LOGGED; i++) {
                last = new InternalError("later " + i);
                assertTrue(LoggedFailures.unloggedFatal(last));
            }
            assertFalse(LoggedFailures.unloggedFatal(last));
            assertFalse(LoggedFailures.logged(first));
            assertTrue(LoggedFailures.unloggedFatal(first), "logged again");
            assertFalse(LoggedFailures.unloggedFatal(first), "and recorded again");
        } finally {
            LoggedFailures.leave();
        }
        LoggedFailures.enter();
        try {
            assertTrue(LoggedFailures.unloggedFatal(first), "forgotten with the outermost run");
        } finally {
            LoggedFailures.leave();
        }
    }

    @Test
    @DisplayName("a fatal Error is nested only above the depth that logged it, named for what that level started")
    void fatalRecordedWithItsDepth() {
        InternalError byLoad = new InternalError("by a load");
        InternalError byRun = new InternalError("by a run");
        int loads = 5;
        LoggedFailures.enter();
        try {
            for (int i = 0; i < loads; i++) {
                LoggedFailures.enterLoad();
            }
            assertTrue(LoggedFailures.unloggedFatal(byLoad));
            assertEquals(LoggedFailures.LoggedAt.NOT_BELOW, LoggedFailures.loggedAt(byLoad), "its own level's");
            LoggedFailures.enter();
            assertTrue(LoggedFailures.unloggedFatal(byRun));
            LoggedFailures.leave();
            assertEquals(LoggedFailures.LoggedAt.NESTED_RUN, LoggedFailures.loggedAt(byRun), "a run the load started");
            for (int i = 0; i < loads; i++) {
                LoggedFailures.leave();
            }
            // The outermost run started a load(), whatever that load started in turn.
            assertEquals(LoggedFailures.LoggedAt.NESTED_LOAD, LoggedFailures.loggedAt(byLoad));
            assertEquals(LoggedFailures.LoggedAt.NESTED_LOAD, LoggedFailures.loggedAt(byRun));
        } finally {
            LoggedFailures.leave();
        }
        assertNull(LoggedFailures.loggedAt(byLoad), "forgotten with the outermost run");
    }

    @Test
    @DisplayName("a load() nested deeper than a long has bits for counts as a run, and leaves the rest as they were")
    void loadsPastTheBitsCountAsRuns() {
        InternalError fatal = new InternalError("deep");
        int depth = Long.SIZE + 2;
        for (int i = 0; i < depth; i++) {
            LoggedFailures.enterLoad();
        }
        try {
            assertTrue(LoggedFailures.unloggedFatal(fatal));
            LoggedFailures.leave();
            LoggedFailures.leave();
            assertEquals(LoggedFailures.LoggedAt.NESTED_RUN, LoggedFailures.loggedAt(fatal));
            for (int i = 2; i < Long.SIZE; i++) {
                LoggedFailures.leave();
            }
            assertEquals(LoggedFailures.LoggedAt.NESTED_LOAD, LoggedFailures.loggedAt(fatal));
        } finally {
            LoggedFailures.leave();
            LoggedFailures.leave();
        }
        assertNull(LoggedFailures.loggedAt(fatal));
    }

    @Test
    @DisplayName("a fatal Error a run that has ended logged deeper isn't nested in a run started after it")
    void fatalLoggedDeeperByARunThatHasEnded() {
        InternalError deeper = new InternalError("by a run that has ended");
        InternalError own = new InternalError("by the run started after it");
        LoggedFailures.enter();
        try {
            LoggedFailures.enter();
            try {
                LoggedFailures.enter();
                try {
                    assertTrue(LoggedFailures.unloggedFatal(deeper));
                } finally {
                    LoggedFailures.leave();
                }
            } finally {
                LoggedFailures.leave();
            }
            assertEquals(LoggedFailures.LoggedAt.NESTED_RUN, LoggedFailures.loggedAt(deeper), "on its way out");
            LoggedFailures.enter();
            try {
                assertEquals(LoggedFailures.LoggedAt.NOT_BELOW, LoggedFailures.loggedAt(deeper), "a later sibling");
                LoggedFailures.enter();
                try {
                    assertEquals(LoggedFailures.LoggedAt.NOT_BELOW, LoggedFailures.loggedAt(deeper),
                            "a run the sibling started");
                } finally {
                    LoggedFailures.leave();
                }
                assertTrue(LoggedFailures.unloggedFatal(own));
                LoggedFailures.enter();
                LoggedFailures.leave();
                assertEquals(LoggedFailures.LoggedAt.NOT_BELOW, LoggedFailures.loggedAt(own), "its own level's");
            } finally {
                LoggedFailures.leave();
            }
            assertEquals(LoggedFailures.LoggedAt.NESTED_RUN, LoggedFailures.loggedAt(deeper));
            assertEquals(LoggedFailures.LoggedAt.NESTED_RUN, LoggedFailures.loggedAt(own));
        } finally {
            LoggedFailures.leave();
        }
    }

    @Test
    @DisplayName("every fatal Error in a ring that has wrapped, logged deeper by runs that have ended, isn't nested"
            + " in a run started after them")
    void wrappedFatalRecordLoggedDeeperByRunsThatHaveEnded() {
        List<InternalError> fatals = new ArrayList<>();
        for (int i = 0; i <= LoggedFailures.MAX_LOGGED; i++) {
            fatals.add(new InternalError("by a run that has ended " + i));
        }
        LoggedFailures.enter();
        try {
            LoggedFailures.enter();
            try {
                LoggedFailures.enter();
                try {
                    for (InternalError fatal : fatals) {
                        assertTrue(LoggedFailures.unloggedFatal(fatal));
                    }
                } finally {
                    LoggedFailures.leave();
                }
            } finally {
                LoggedFailures.leave();
            }
            assertNull(LoggedFailures.loggedAt(fatals.get(0)), "pushed out of the ring");
            List<InternalError> held = fatals.subList(1, fatals.size());
            for (InternalError fatal : held) {
                assertEquals(LoggedFailures.LoggedAt.NESTED_RUN, LoggedFailures.loggedAt(fatal), fatal.getMessage());
            }
            LoggedFailures.enter();
            try {
                // The newest is in the ring's first slot and the one before it in its last, past where the next goes.
                for (InternalError fatal : held) {
                    assertEquals(LoggedFailures.LoggedAt.NOT_BELOW, LoggedFailures.loggedAt(fatal),
                            fatal.getMessage());
                }
            } finally {
                LoggedFailures.leave();
            }
        } finally {
            LoggedFailures.leave();
        }
    }

    @Test
    @DisplayName("a fatal Error logged deeper isn't nested in a later sibling when a shallower one was logged after it")
    void fatalLoggedDeeperThenShallowerByRunsThatHaveEnded() {
        InternalError deeper = new InternalError("by the deeper run");
        InternalError shallower = new InternalError("by the run around it, after it ended");
        LoggedFailures.enter();
        try {
            LoggedFailures.enter();
            try {
                LoggedFailures.enter();
                try {
                    assertTrue(LoggedFailures.unloggedFatal(deeper));
                } finally {
                    LoggedFailures.leave();
                }
                assertTrue(LoggedFailures.unloggedFatal(shallower));
            } finally {
                LoggedFailures.leave();
            }
            LoggedFailures.enter();
            try {
                assertEquals(LoggedFailures.LoggedAt.NOT_BELOW, LoggedFailures.loggedAt(deeper), "the deeper");
                assertEquals(LoggedFailures.LoggedAt.NOT_BELOW, LoggedFailures.loggedAt(shallower), "the shallower");
            } finally {
                LoggedFailures.leave();
            }
        } finally {
            LoggedFailures.leave();
        }
    }

    @Test
    @DisplayName("a fatal Error a load() logged through a run it started is still the load()'s once a sibling ran")
    void fatalLoggedBelowALoadAfterASiblingRun() {
        InternalError fatal = new InternalError("below a load");
        LoggedFailures.enter();
        try {
            LoggedFailures.enterLoad();
            try {
                LoggedFailures.enter();
                try {
                    assertTrue(LoggedFailures.unloggedFatal(fatal));
                } finally {
                    LoggedFailures.leave();
                }
            } finally {
                LoggedFailures.leave();
            }
            LoggedFailures.enter();
            LoggedFailures.leave();
            assertEquals(LoggedFailures.LoggedAt.NESTED_LOAD, LoggedFailures.loggedAt(fatal));
        } finally {
            LoggedFailures.leave();
        }
    }

    @Test
    @DisplayName("a run nested at the depth of a load() that has ended is a run, not a load()")
    void runAfterALoadAtTheSameDepth() {
        InternalError fatal = new InternalError("by a run after a load");
        LoggedFailures.enter();
        try {
            LoggedFailures.enterLoad();
            LoggedFailures.leave();
            LoggedFailures.enter();
            try {
                assertTrue(LoggedFailures.unloggedFatal(fatal));
            } finally {
                LoggedFailures.leave();
            }

            assertEquals(LoggedFailures.LoggedAt.NESTED_RUN, LoggedFailures.loggedAt(fatal));
        } finally {
            LoggedFailures.leave();
        }
    }

    @Test
    @DisplayName("#904: a failure a run reported is nested only above the depth that built it, and once that run has"
            + " ended, not in a sibling, nor once the outermost run has ended")
    void reportedRecordedWithItsDepth() {
        ReportedFailure nested;
        LoggedFailures.enter();
        try {
            LoggedFailures.enter();
            try {
                LoggedFailures.enter();
                try {
                    nested = new ReportedFailure("by a nested run", null);
                } finally {
                    LoggedFailures.leave();
                }
                assertEquals("wrapped (after a nested run() failed: by a nested run)",
                        Failures.describe(new IllegalStateException("wrapped", nested)), "the run that started it");
            } finally {
                LoggedFailures.leave();
            }
            assertEquals("wrapped (after a nested run() failed: by a nested run)",
                    Failures.describe(new IllegalStateException("wrapped", nested)), "a run around that one");
            LoggedFailures.enter();
            try {
                assertEquals("wrapped (caused by by a nested run, already logged)",
                        Failures.describe(new IllegalStateException("wrapped", nested)), "a later sibling");
            } finally {
                LoggedFailures.leave();
            }
        } finally {
            LoggedFailures.leave();
        }
        assertEquals("wrapped (caused by by a nested run, already logged)",
                Failures.describe(new IllegalStateException("wrapped", nested)), "no run in progress");
        LoggedFailures.enter();
        try {
            assertEquals("wrapped (caused by by a nested run, already logged)",
                    Failures.describe(new IllegalStateException("wrapped", nested)), "a later outermost run");
        } finally {
            LoggedFailures.leave();
        }
    }

    @Test
    @DisplayName("#904: past the bound, a failure a run reported is taken for a nested run's, as before the engine"
            + " recorded any, and the newest isn't")
    void boundedReportedRecord() {
        List<ReportedFailure> reported = new ArrayList<>();
        LoggedFailures.enter();
        try {
            LoggedFailures.enter();
            try {
                for (int i = 0; i <= LoggedFailures.MAX_LOGGED; i++) {
                    reported.add(new ReportedFailure("reported " + i, null));
                }
            } finally {
                LoggedFailures.leave();
            }
            LoggedFailures.enter();
            try {
                assertEquals("wrapped (after a nested run() failed: reported 0)",
                        Failures.describe(new IllegalStateException("wrapped", reported.get(0))), "pushed out");
                assertEquals("wrapped (caused by reported 32, already logged)",
                        Failures.describe(new IllegalStateException("wrapped", reported.get(32))), "the newest");
            } finally {
                LoggedFailures.leave();
            }
        } finally {
            LoggedFailures.leave();
        }
    }

    @Test
    @DisplayName("#904: a failure a run on another thread reported is taken for a nested run's")
    void reportedOnAnotherThreadIsNested() throws InterruptedException {
        AtomicReference<ReportedFailure> reported = new AtomicReference<>();
        Thread other = new Thread(() -> {
            LoggedFailures.enter();
            try {
                reported.set(new ReportedFailure("on another thread", null));
            } finally {
                LoggedFailures.leave();
            }
        });
        other.start();
        other.join();
        LoggedFailures.enter();
        try {
            assertEquals("wrapped (after a nested run() failed: on another thread)",
                    Failures.describe(new IllegalStateException("wrapped", reported.get())));
        } finally {
            LoggedFailures.leave();
        }
    }

    @Test
    @DisplayName("#904: a pooled thread keeps no record of a run's reported failures once its outermost run has ended")
    void pooledThreadKeepsNoReportedRecord() throws Exception {
        RulesEngine<Map<String, Object>> failing = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new StubExpressionLanguage().action((action, session) -> {
                    throw new IllegalStateException("inner rule failed");
                })).build();
        failing.load(List.of(Rule.builder().ruleName("inner-rule").condition("c").action("a").build()));
        AtomicReference<RuntimeException> kept = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new StubExpressionLanguage().action((action, session) -> {
                    try {
                        failing.run(new FactMap<>());
                    } catch (RuntimeException e) {
                        kept.set(e);
                    }
                    return ActionResult.done();
                })).build();
        engine.load(List.of(Rule.builder().ruleName("outer-rule").condition("c").action("a").build()));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Outcome<Throwable> run = capture(() -> executor.submit(() -> engine.run(new FactMap<>())).get());
            assertNull(run.thrown(), run.logs());
            assertEquals(List.of("Failed to execute action for rule 'inner-rule': inner rule failed"),
                    run.lines("ERROR"), run.logs());
            Field runsField = LoggedFailures.class.getDeclaredField("RUNS");
            runsField.setAccessible(true);
            assertNull(executor.submit(() -> ((ThreadLocal<?>) runsField.get(null)).get()).get());
            assertEquals("wrapped (caused by Failed to execute action for rule 'inner-rule': inner rule failed,"
                    + " already logged)", executor.submit(() -> {
                        LoggedFailures.enter();
                        try {
                            return Failures.describe(new IllegalStateException("wrapped", kept.get()));
                        } finally {
                            LoggedFailures.leave();
                        }
                    }).get());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Asserts how a failure a run reported reads thrown on with no words of its own, as is and in
     * {@code new RuntimeException(e)}, whose message is its {@code toString()}.
     */
    private static void assertThrownOnReads(String expected, ReportedFailure reported, String where) {
        assertEquals(expected, Failures.describe(reported), where + ", as is");
        assertEquals(expected, Failures.describe(new RuntimeException(reported)), where + ", wrapped");
    }

    @Test
    @DisplayName("#956: a failure a run reported, thrown on with no words of its own, is nested only above the depth"
            + " that built it, and once that run has ended reads as logged already")
    void reportedThrownOnRecordedWithItsDepth() {
        ReportedFailure nested;
        LoggedFailures.enter();
        try {
            LoggedFailures.enter();
            try {
                LoggedFailures.enter();
                try {
                    nested = new ReportedFailure("by a nested run", null);
                } finally {
                    LoggedFailures.leave();
                }
                assertThrownOnReads("a nested run() failed: by a nested run", nested, "the run that started it");
            } finally {
                LoggedFailures.leave();
            }
            assertThrownOnReads("a nested run() failed: by a nested run", nested, "a run around that one");
            LoggedFailures.enter();
            try {
                assertThrownOnReads("by a nested run (already logged)", nested, "a later sibling");
            } finally {
                LoggedFailures.leave();
            }
        } finally {
            LoggedFailures.leave();
        }
        assertThrownOnReads("by a nested run (already logged)", nested, "no run in progress");
        LoggedFailures.enter();
        try {
            assertThrownOnReads("by a nested run (already logged)", nested, "a later outermost run");
        } finally {
            LoggedFailures.leave();
        }
    }

    @Test
    @DisplayName("#956: past the bound, a failure a run reported, thrown on with no words of its own, is taken for a"
            + " nested run's, and the newest reads as logged already")
    void boundedReportedRecordThrownOn() {
        List<ReportedFailure> reported = new ArrayList<>();
        LoggedFailures.enter();
        try {
            LoggedFailures.enter();
            try {
                for (int i = 0; i <= LoggedFailures.MAX_LOGGED; i++) {
                    reported.add(new ReportedFailure("reported " + i, null));
                }
            } finally {
                LoggedFailures.leave();
            }
            LoggedFailures.enter();
            try {
                assertThrownOnReads("a nested run() failed: reported 0", reported.get(0), "pushed out");
                assertThrownOnReads("reported 32 (already logged)", reported.get(32), "the newest");
            } finally {
                LoggedFailures.leave();
            }
        } finally {
            LoggedFailures.leave();
        }
    }

    @Test
    @DisplayName("#956 guard: a failure a run on another thread reported, thrown on with no words of its own, is taken"
            + " for a nested run's")
    void reportedOnAnotherThreadThrownOnIsNested() throws InterruptedException {
        AtomicReference<ReportedFailure> reported = new AtomicReference<>();
        Thread other = new Thread(() -> {
            LoggedFailures.enter();
            try {
                reported.set(new ReportedFailure("on another thread", null));
            } finally {
                LoggedFailures.leave();
            }
        });
        other.start();
        other.join();
        LoggedFailures.enter();
        try {
            assertThrownOnReads("a nested run() failed: on another thread", reported.get(), "another thread");
        } finally {
            LoggedFailures.leave();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"the oldest", "the newest"})
    @DisplayName("past the bound, the oldest failure recorded is logged again if it's thrown on, the newest isn't")
    void boundedRecord(String which) {
        RulesEngine<Map<String, Object>> nested = plain("inner-rule");
        List<IllegalArgumentException> rejections = new ArrayList<>();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .language(new StubExpressionLanguage().action((action, session) -> {
                    for (int i = 0; i <= LoggedFailures.MAX_LOGGED; i++) {
                        try {
                            runWithOutputFact(nested);
                        } catch (IllegalArgumentException e) {
                            rejections.add(e);
                        }
                    }
                    throw "the oldest".equals(which) ? rejections.get(0) : rejections.get(rejections.size() - 1);
                })).build();
        engine.load(List.of(Rule.builder().ruleName("outer-rule").condition("c").action("a").build()));

        Outcome<RuntimeException> outcome = capture(RuntimeException.class, () -> engine.run(new FactMap<>()));

        List<String> errors = outcome.lines("ERROR");
        assertEquals(LoggedFailures.MAX_LOGGED + 1, errors.stream().filter(OUTPUT_REJECTED::equals).count(),
                outcome.logs());
        String outer = "Failed to execute action for rule 'outer-rule': ";
        if ("the oldest".equals(which)) {
            // Logged twice, by the nested run and by the rule, but not left out.
            assertEquals(outer + OUTPUT_REJECTED, outcome.thrown().getMessage());
            assertEquals(outer + OUTPUT_REJECTED, errors.get(errors.size() - 1), outcome.logs());
            assertEquals(LoggedFailures.MAX_LOGGED + 2, errors.size(), outcome.logs());
        } else {
            assertEquals(outer + "a nested run() failed: " + OUTPUT_REJECTED, outcome.thrown().getMessage());
            assertEquals(LoggedFailures.MAX_LOGGED + 1, errors.size(), outcome.logs());
        }
        // Nothing is left behind on the thread.
        assertNull(LoggedFailures.find(rejections.get(rejections.size() - 1)));
    }

    @Test
    @DisplayName("an exception the engine built around a nested failure adds nothing to it whatever it says, while an "
            + "equal one of an application's is news, and the record is forgotten with the outermost run and bounded")
    void engineWrapperKnownByInstance() {
        ReportedFailure inner = new ReportedFailure("inner failed", null);
        IllegalArgumentException engines = new IllegalArgumentException("check failed: inner failed", inner);
        IllegalArgumentException applications = new IllegalArgumentException("check failed: inner failed", inner);
        String named = "a nested run() failed: inner failed";

        assertFalse(LoggedFailures.isEngineWrapper(engines), "no run in progress");
        LoggedFailures.enter();
        try {
            assertFalse(LoggedFailures.isEngineWrapper(engines), "nothing built yet");
            assertSame(engines, LoggedFailures.builtByEngine(engines));
            assertTrue(LoggedFailures.isEngineWrapper(engines));
            assertEquals(named, Failures.describe(engines));
            assertSame(inner, Failures.nestedRunFailure(engines));
            assertFalse(LoggedFailures.isEngineWrapper(applications));
            assertEquals("check failed: inner failed (after " + named + ")", Failures.describe(applications));
            for (int i = 1; i < LoggedFailures.MAX_LOGGED; i++) {
                LoggedFailures.builtByEngine(new IllegalStateException("built " + i));
            }
            assertTrue(LoggedFailures.isEngineWrapper(engines), "kept while fewer than the bound are built after it");
            LoggedFailures.builtByEngine(new IllegalStateException("one more"));
            assertFalse(LoggedFailures.isEngineWrapper(engines), "the oldest is forgotten past the bound");
            LoggedFailures.builtByEngine(engines);
        } finally {
            LoggedFailures.leave();
        }
        assertFalse(LoggedFailures.isEngineWrapper(engines), "forgotten when the outermost run ends");
    }
}
