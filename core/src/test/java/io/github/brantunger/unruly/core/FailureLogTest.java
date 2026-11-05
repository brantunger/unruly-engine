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
 * outermost run ends, and bounded, so a failure logged before the last {@value FailureLog#MAX_LOGGED} is logged
 * again if it's thrown on, and none is ever left out. The fatal {@link Error}s runs logged, and the exceptions the
 * engine built around a failure, are bounded the same way.
 */
@DisplayName("the thread's record of what nested runs logged is made only when nested, and bounded")
class FailureLogTest {

    private static final String OUTPUT_REJECTED = "'output' is reserved for the output object and cannot be used as "
            + "a fact name";

    /**
     * Reads the ring the thread records logged failures in, as the outermost run on the thread leaves it.
     *
     * @return The ring, or {@code null} if none was created, or no run is in progress on this thread
     */
    private static Object ring() throws ReflectiveOperationException {
        Field runsField = FailureLog.class.getDeclaredField("RUNS");
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
        FailureLog.enter();
        try {
            assertSame(failure, FailureLog.loggedByRun(failure));
            assertSame(failure, FailureLog.loggedByLoad(failure));
            assertNull(FailureLog.find(failure));
            assertNull(CauseChains.nestedRunFailure(failure));
            assertNull(ring());
        } finally {
            FailureLog.leave();
        }
    }

    @Test
    @DisplayName("#1268: a failure the innermost run itself builds is recorded at that run's depth, so it isn't below"
            + " the run, while one a nested run built is")
    void ownReportedFailureIsNotBelow() {
        FailureLog.enter();
        try {
            ReportedFailure own = CauseChains.report("the run's own", null);
            assertFalse(FailureLog.loggedBelow(own), "built by the innermost run");

            FailureLog.enter();
            ReportedFailure nested;
            try {
                nested = CauseChains.report("a nested run's", null);
            } finally {
                FailureLog.leave();
            }
            assertTrue(FailureLog.loggedBelow(nested), "built by a nested run");
        } finally {
            FailureLog.leave();
        }
    }

    /** An exception equal to every other of its class, as a language that compares its exceptions by text might. */
    private static final class EqualToAll extends RuntimeException {
        private static final long serialVersionUID = 1L;

        EqualToAll(String message) {
            super(message);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualToAll;
        }

        @Override
        public int hashCode() {
            return 0;
        }
    }

    @Test
    @DisplayName("the record holds the very instance a nested run logged and threw as is: an equal one is still news")
    void equalInstanceIsStillNews() {
        EqualToAll logged = new EqualToAll("rejected");
        EqualToAll equal = new EqualToAll("rejected");
        FailureLog.enter();
        try {
            FailureLog.enter();
            try {
                FailureLog.loggedByRun(logged);
            } finally {
                FailureLog.leave();
            }
            assertNotNull(FailureLog.find(logged), "the very instance");
            assertNull(FailureLog.find(equal), "an equal one");
            assertSame(logged, CauseChains.nestedRunFailure(new IllegalStateException(logged)));
            assertNull(CauseChains.nestedRunFailure(new IllegalStateException(equal)));
        } finally {
            FailureLog.leave();
        }
    }

    /**
     * Reads one field of what is in progress on this thread.
     *
     * @param runs What {@link FailureLog#enter()} returned
     * @param name The field's name
     * @return Its value
     */
    private static Object field(FailureLog.Runs runs, String name) throws ReflectiveOperationException {
        Field field = FailureLog.Runs.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(runs);
    }

    @Test
    @DisplayName("#961: an outermost run that starts no nested run and builds no failure creates nothing to tell its"
            + " call-outs apart with, and the first nested run creates it, a fatal Error's stamps included")
    void callOutRecordCreatedWithTheFirstNestedRun() throws ReflectiveOperationException {
        FailureLog.Runs runs = FailureLog.enter();
        try {
            runs.callOut();
            assertTrue(FailureLog.unloggedFatal(new InternalError("by the outermost run")));
            for (String name : List.of("stamps", "outermost")) {
                assertNull(field(runs, name), name);
            }
            FailureLog.enter();
            try {
                assertNotNull(field(runs, "stamps"));
            } finally {
                FailureLog.leave();
            }
            assertNotNull(CauseChains.report("built", null).builtIn());
            assertNotNull(field(runs, "outermost"));
        } finally {
            FailureLog.leave();
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
        FailureLog.enter();
        try {
            FailureLog.enter();
            try {
                FailureLog.loggedByRun(byRun);
                FailureLog.loggedByLoad(byLoad);
            } finally {
                FailureLog.leave();
            }
            assertFalse(FailureLog.find(byRun).byLoad());
            assertTrue(FailureLog.find(byLoad).byLoad());
            assertNull(FailureLog.find(new IllegalArgumentException("by run")));
            assertEquals("wrapped (after a nested load() failed: by load)",
                    CauseChains.describe(new IllegalStateException("wrapped", byLoad)));
        } finally {
            FailureLog.leave();
        }
        assertNull(FailureLog.find(byRun));
        assertEquals("wrapped", CauseChains.describe(new IllegalStateException("wrapped", byLoad)));
    }

    @Test
    @DisplayName("the ring of fatal Errors exists as soon as a run starts, so recording an OutOfMemoryError allocates"
            + " nothing")
    void fatalRingMadeBeforeAnyFailure() throws ReflectiveOperationException {
        Field runsField = FailureLog.class.getDeclaredField("RUNS");
        runsField.setAccessible(true);
        FailureLog.enter();
        try {
            Object runs = ((ThreadLocal<?>) runsField.get(null)).get();
            Field loggedFatal = runs.getClass().getDeclaredField("loggedFatal");
            loggedFatal.setAccessible(true);
            Object ring = loggedFatal.get(runs);
            assertEquals(FailureLog.MAX_LOGGED, ((Error[]) ring).length);
            assertTrue(FailureLog.unloggedFatal(new OutOfMemoryError("heap")));
            assertSame(ring, loggedFatal.get(runs), "a ring made while recording");
        } finally {
            FailureLog.leave();
        }
    }

    @Test
    @DisplayName("a fatal Error logged once stays logged while fewer than the bound of others are logged after it")
    void fatalStaysLoggedPastOthers() {
        InternalError first = new InternalError("first");
        FailureLog.enter();
        try {
            assertTrue(FailureLog.unloggedFatal(first));
            for (int i = 1; i < FailureLog.MAX_LOGGED; i++) {
                FailureLog.enter();
                try {
                    assertTrue(FailureLog.unloggedFatal(new InternalError("nested " + i)));
                } finally {
                    FailureLog.leave();
                }
            }
            assertTrue(CauseChains.logged(first));
            assertFalse(FailureLog.unloggedFatal(first));
        } finally {
            FailureLog.leave();
        }
    }

    @Test
    @DisplayName("past the bound, the oldest fatal Error logged is logged again, and the newest isn't")
    void boundedFatalRecord() {
        InternalError first = new InternalError("first");
        InternalError last = null;
        FailureLog.enter();
        try {
            assertTrue(FailureLog.unloggedFatal(first));
            for (int i = 1; i <= FailureLog.MAX_LOGGED; i++) {
                last = new InternalError("later " + i);
                assertTrue(FailureLog.unloggedFatal(last));
            }
            assertFalse(FailureLog.unloggedFatal(last));
            assertFalse(CauseChains.logged(first));
            assertTrue(FailureLog.unloggedFatal(first), "logged again");
            assertFalse(FailureLog.unloggedFatal(first), "and recorded again");
        } finally {
            FailureLog.leave();
        }
        FailureLog.enter();
        try {
            assertTrue(FailureLog.unloggedFatal(first), "forgotten with the outermost run");
        } finally {
            FailureLog.leave();
        }
    }

    @Test
    @DisplayName("a fatal Error is nested only above the depth that logged it, named for what that level started")
    void fatalRecordedWithItsDepth() {
        InternalError byLoad = new InternalError("by a load");
        InternalError byRun = new InternalError("by a run");
        int loads = 5;
        FailureLog.enter();
        try {
            for (int i = 0; i < loads; i++) {
                FailureLog.enterLoad();
            }
            assertTrue(FailureLog.unloggedFatal(byLoad));
            assertEquals(FailureLog.LoggedAt.NOT_BELOW, FailureLog.loggedAt(byLoad), "its own level's");
            FailureLog.enter();
            assertTrue(FailureLog.unloggedFatal(byRun));
            FailureLog.leave();
            assertEquals(FailureLog.LoggedAt.NESTED_RUN, FailureLog.loggedAt(byRun), "a run the load started");
            for (int i = 0; i < loads; i++) {
                FailureLog.leave();
            }
            // The outermost run started a load(), whatever that load started in turn.
            assertEquals(FailureLog.LoggedAt.NESTED_LOAD, FailureLog.loggedAt(byLoad));
            assertEquals(FailureLog.LoggedAt.NESTED_LOAD, FailureLog.loggedAt(byRun));
        } finally {
            FailureLog.leave();
        }
        assertNull(FailureLog.loggedAt(byLoad), "forgotten with the outermost run");
    }

    @Test
    @DisplayName("a load() nested deeper than a long has bits for counts as a run, and leaves the rest as they were")
    void loadsPastTheBitsCountAsRuns() {
        InternalError fatal = new InternalError("deep");
        int depth = Long.SIZE + 2;
        for (int i = 0; i < depth; i++) {
            FailureLog.enterLoad();
        }
        try {
            assertTrue(FailureLog.unloggedFatal(fatal));
            FailureLog.leave();
            FailureLog.leave();
            assertEquals(FailureLog.LoggedAt.NESTED_RUN, FailureLog.loggedAt(fatal));
            for (int i = 2; i < Long.SIZE; i++) {
                FailureLog.leave();
            }
            assertEquals(FailureLog.LoggedAt.NESTED_LOAD, FailureLog.loggedAt(fatal));
        } finally {
            FailureLog.leave();
            FailureLog.leave();
        }
        assertNull(FailureLog.loggedAt(fatal));
    }

    @Test
    @DisplayName("a fatal Error a run that has ended logged deeper isn't nested in a run started after it")
    void fatalLoggedDeeperByARunThatHasEnded() {
        InternalError deeper = new InternalError("by a run that has ended");
        InternalError own = new InternalError("by the run started after it");
        FailureLog.enter();
        try {
            FailureLog.enter();
            try {
                FailureLog.enter();
                try {
                    assertTrue(FailureLog.unloggedFatal(deeper));
                } finally {
                    FailureLog.leave();
                }
            } finally {
                FailureLog.leave();
            }
            assertEquals(FailureLog.LoggedAt.NESTED_RUN, FailureLog.loggedAt(deeper), "on its way out");
            FailureLog.enter();
            try {
                assertEquals(FailureLog.LoggedAt.NOT_BELOW, FailureLog.loggedAt(deeper), "a later sibling");
                FailureLog.enter();
                try {
                    assertEquals(FailureLog.LoggedAt.NOT_BELOW, FailureLog.loggedAt(deeper),
                            "a run the sibling started");
                } finally {
                    FailureLog.leave();
                }
                assertTrue(FailureLog.unloggedFatal(own));
                FailureLog.enter();
                FailureLog.leave();
                assertEquals(FailureLog.LoggedAt.NOT_BELOW, FailureLog.loggedAt(own), "its own level's");
            } finally {
                FailureLog.leave();
            }
            assertEquals(FailureLog.LoggedAt.NESTED_RUN, FailureLog.loggedAt(deeper));
            assertEquals(FailureLog.LoggedAt.NESTED_RUN, FailureLog.loggedAt(own));
        } finally {
            FailureLog.leave();
        }
    }

    @Test
    @DisplayName("every fatal Error in a ring that has wrapped, logged deeper by runs that have ended, isn't nested"
            + " in a run started after them")
    void wrappedFatalRecordLoggedDeeperByRunsThatHaveEnded() {
        List<InternalError> fatals = new ArrayList<>();
        for (int i = 0; i <= FailureLog.MAX_LOGGED; i++) {
            fatals.add(new InternalError("by a run that has ended " + i));
        }
        FailureLog.enter();
        try {
            FailureLog.enter();
            try {
                FailureLog.enter();
                try {
                    for (InternalError fatal : fatals) {
                        assertTrue(FailureLog.unloggedFatal(fatal));
                    }
                } finally {
                    FailureLog.leave();
                }
            } finally {
                FailureLog.leave();
            }
            assertNull(FailureLog.loggedAt(fatals.get(0)), "pushed out of the ring");
            List<InternalError> held = fatals.subList(1, fatals.size());
            for (InternalError fatal : held) {
                assertEquals(FailureLog.LoggedAt.NESTED_RUN, FailureLog.loggedAt(fatal), fatal.getMessage());
            }
            FailureLog.enter();
            try {
                // The newest is in the ring's first slot and the one before it in its last, past where the next goes.
                for (InternalError fatal : held) {
                    assertEquals(FailureLog.LoggedAt.NOT_BELOW, FailureLog.loggedAt(fatal),
                            fatal.getMessage());
                }
            } finally {
                FailureLog.leave();
            }
        } finally {
            FailureLog.leave();
        }
    }

    @Test
    @DisplayName("a fatal Error logged deeper isn't nested in a later sibling when a shallower one was logged after it")
    void fatalLoggedDeeperThenShallowerByRunsThatHaveEnded() {
        InternalError deeper = new InternalError("by the deeper run");
        InternalError shallower = new InternalError("by the run around it, after it ended");
        FailureLog.enter();
        try {
            FailureLog.enter();
            try {
                FailureLog.enter();
                try {
                    assertTrue(FailureLog.unloggedFatal(deeper));
                } finally {
                    FailureLog.leave();
                }
                assertTrue(FailureLog.unloggedFatal(shallower));
            } finally {
                FailureLog.leave();
            }
            FailureLog.enter();
            try {
                assertEquals(FailureLog.LoggedAt.NOT_BELOW, FailureLog.loggedAt(deeper), "the deeper");
                assertEquals(FailureLog.LoggedAt.NOT_BELOW, FailureLog.loggedAt(shallower), "the shallower");
            } finally {
                FailureLog.leave();
            }
        } finally {
            FailureLog.leave();
        }
    }

    @Test
    @DisplayName("a fatal Error a load() logged through a run it started is still the load()'s once a sibling ran")
    void fatalLoggedBelowALoadAfterASiblingRun() {
        InternalError fatal = new InternalError("below a load");
        FailureLog.enter();
        try {
            FailureLog.enterLoad();
            try {
                FailureLog.enter();
                try {
                    assertTrue(FailureLog.unloggedFatal(fatal));
                } finally {
                    FailureLog.leave();
                }
            } finally {
                FailureLog.leave();
            }
            FailureLog.enter();
            FailureLog.leave();
            assertEquals(FailureLog.LoggedAt.NESTED_LOAD, FailureLog.loggedAt(fatal));
        } finally {
            FailureLog.leave();
        }
    }

    @Test
    @DisplayName("a run nested at the depth of a load() that has ended is a run, not a load()")
    void runAfterALoadAtTheSameDepth() {
        InternalError fatal = new InternalError("by a run after a load");
        FailureLog.enter();
        try {
            FailureLog.enterLoad();
            FailureLog.leave();
            FailureLog.enter();
            try {
                assertTrue(FailureLog.unloggedFatal(fatal));
            } finally {
                FailureLog.leave();
            }

            assertEquals(FailureLog.LoggedAt.NESTED_RUN, FailureLog.loggedAt(fatal));
        } finally {
            FailureLog.leave();
        }
    }

    @Test
    @DisplayName("#904: a failure a run reported is nested only above the depth that built it, and once that run has"
            + " ended, not in a sibling, nor once the outermost run has ended")
    void reportedRecordedWithItsDepth() {
        ReportedFailure nested;
        FailureLog.enter();
        try {
            FailureLog.enter();
            try {
                FailureLog.enter();
                try {
                    nested = CauseChains.report("by a nested run", null);
                } finally {
                    FailureLog.leave();
                }
                assertEquals("wrapped (after a nested run() failed: by a nested run)",
                        CauseChains.describe(new IllegalStateException("wrapped", nested)),
                        "the run that started it");
            } finally {
                FailureLog.leave();
            }
            assertEquals("wrapped (after a nested run() failed: by a nested run)",
                    CauseChains.describe(new IllegalStateException("wrapped", nested)), "a run around that one");
            FailureLog.enter();
            try {
                assertEquals("wrapped (caused by by a nested run, already logged)",
                        CauseChains.describe(new IllegalStateException("wrapped", nested)), "a later sibling");
            } finally {
                FailureLog.leave();
            }
        } finally {
            FailureLog.leave();
        }
        assertEquals("wrapped (caused by by a nested run, already logged)",
                CauseChains.describe(new IllegalStateException("wrapped", nested)), "no run in progress");
        FailureLog.enter();
        try {
            assertEquals("wrapped (caused by by a nested run, already logged)",
                    CauseChains.describe(new IllegalStateException("wrapped", nested)), "a later outermost run");
        } finally {
            FailureLog.leave();
        }
    }

    @Test
    @DisplayName("#904: past the bound, a failure a run reported is taken for a nested run's, as before the engine"
            + " recorded any, and the newest isn't")
    void boundedReportedRecord() {
        List<ReportedFailure> reported = new ArrayList<>();
        FailureLog.enter();
        try {
            FailureLog.enter();
            try {
                for (int i = 0; i <= FailureLog.MAX_LOGGED; i++) {
                    reported.add(CauseChains.report("reported " + i, null));
                }
            } finally {
                FailureLog.leave();
            }
            FailureLog.enter();
            try {
                assertEquals("wrapped (after a nested run() failed: reported 0)",
                        CauseChains.describe(new IllegalStateException("wrapped", reported.get(0))), "pushed out");
                assertEquals("wrapped (caused by reported 32, already logged)",
                        CauseChains.describe(new IllegalStateException("wrapped", reported.get(32))), "the newest");
            } finally {
                FailureLog.leave();
            }
        } finally {
            FailureLog.leave();
        }
    }

    @Test
    @DisplayName("#904: a failure a run on another thread reported is taken for a nested run's")
    void reportedOnAnotherThreadIsNested() throws InterruptedException {
        AtomicReference<ReportedFailure> reported = new AtomicReference<>();
        Thread other = new Thread(() -> {
            FailureLog.enter();
            try {
                reported.set(CauseChains.report("on another thread", null));
            } finally {
                FailureLog.leave();
            }
        });
        other.start();
        other.join();
        FailureLog.enter();
        try {
            assertEquals("wrapped (after a nested run() failed: on another thread)",
                    CauseChains.describe(new IllegalStateException("wrapped", reported.get())));
        } finally {
            FailureLog.leave();
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
            Field runsField = FailureLog.class.getDeclaredField("RUNS");
            runsField.setAccessible(true);
            assertNull(executor.submit(() -> ((ThreadLocal<?>) runsField.get(null)).get()).get());
            assertEquals("wrapped (caused by Failed to execute action for rule 'inner-rule': inner rule failed,"
                    + " already logged)", executor.submit(() -> {
                        FailureLog.enter();
                        try {
                            return CauseChains.describe(new IllegalStateException("wrapped", kept.get()));
                        } finally {
                            FailureLog.leave();
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
        assertEquals(expected, CauseChains.describe(reported), where + ", as is");
        assertEquals(expected, CauseChains.describe(new RuntimeException(reported)), where + ", wrapped");
    }

    @Test
    @DisplayName("#956: a failure a run reported, thrown on with no words of its own, is nested only above the depth"
            + " that built it, and once that run has ended reads as logged already")
    void reportedThrownOnRecordedWithItsDepth() {
        ReportedFailure nested;
        FailureLog.enter();
        try {
            FailureLog.enter();
            try {
                FailureLog.enter();
                try {
                    nested = CauseChains.report("by a nested run", null);
                } finally {
                    FailureLog.leave();
                }
                assertThrownOnReads("a nested run() failed: by a nested run", nested, "the run that started it");
            } finally {
                FailureLog.leave();
            }
            assertThrownOnReads("a nested run() failed: by a nested run", nested, "a run around that one");
            FailureLog.enter();
            try {
                assertThrownOnReads("by a nested run (already logged)", nested, "a later sibling");
            } finally {
                FailureLog.leave();
            }
        } finally {
            FailureLog.leave();
        }
        assertThrownOnReads("by a nested run (already logged)", nested, "no run in progress");
        FailureLog.enter();
        try {
            assertThrownOnReads("by a nested run (already logged)", nested, "a later outermost run");
        } finally {
            FailureLog.leave();
        }
    }

    @Test
    @DisplayName("#956: past the bound, a failure a run reported, thrown on with no words of its own, is taken for a"
            + " nested run's, and the newest reads as logged already")
    void boundedReportedRecordThrownOn() {
        List<ReportedFailure> reported = new ArrayList<>();
        FailureLog.enter();
        try {
            FailureLog.enter();
            try {
                for (int i = 0; i <= FailureLog.MAX_LOGGED; i++) {
                    reported.add(CauseChains.report("reported " + i, null));
                }
            } finally {
                FailureLog.leave();
            }
            FailureLog.enter();
            try {
                assertThrownOnReads("a nested run() failed: reported 0", reported.get(0), "pushed out");
                assertThrownOnReads("reported 32 (already logged)", reported.get(32), "the newest");
            } finally {
                FailureLog.leave();
            }
        } finally {
            FailureLog.leave();
        }
    }

    @Test
    @DisplayName("#956 guard: a failure a run on another thread reported, thrown on with no words of its own, is taken"
            + " for a nested run's")
    void reportedOnAnotherThreadThrownOnIsNested() throws InterruptedException {
        AtomicReference<ReportedFailure> reported = new AtomicReference<>();
        Thread other = new Thread(() -> {
            FailureLog.enter();
            try {
                reported.set(CauseChains.report("on another thread", null));
            } finally {
                FailureLog.leave();
            }
        });
        other.start();
        other.join();
        FailureLog.enter();
        try {
            assertThrownOnReads("a nested run() failed: on another thread", reported.get(), "another thread");
        } finally {
            FailureLog.leave();
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
                    for (int i = 0; i <= FailureLog.MAX_LOGGED; i++) {
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
        assertEquals(FailureLog.MAX_LOGGED + 1, errors.stream().filter(OUTPUT_REJECTED::equals).count(),
                outcome.logs());
        String outer = "Failed to execute action for rule 'outer-rule': ";
        if ("the oldest".equals(which)) {
            // Logged twice, by the nested run and by the rule, but not left out.
            assertEquals(outer + OUTPUT_REJECTED, outcome.thrown().getMessage());
            assertEquals(outer + OUTPUT_REJECTED, errors.get(errors.size() - 1), outcome.logs());
            assertEquals(FailureLog.MAX_LOGGED + 2, errors.size(), outcome.logs());
        } else {
            assertEquals(outer + "a nested run() failed: " + OUTPUT_REJECTED, outcome.thrown().getMessage());
            assertEquals(FailureLog.MAX_LOGGED + 1, errors.size(), outcome.logs());
        }
        // Nothing is left behind on the thread.
        assertNull(FailureLog.find(rejections.get(rejections.size() - 1)));
    }

    @Test
    @DisplayName("an exception the engine built around a nested failure adds nothing to it whatever it says, while an "
            + "equal one of an application's is news, and the record is forgotten with the outermost run and bounded")
    void engineWrapperKnownByInstance() {
        ReportedFailure inner = CauseChains.report("inner failed", null);
        IllegalArgumentException engines = new IllegalArgumentException("check failed: inner failed", inner);
        IllegalArgumentException applications = new IllegalArgumentException("check failed: inner failed", inner);
        String named = "a nested run() failed: inner failed";

        assertFalse(FailureLog.isEngineWrapper(engines), "no run in progress");
        FailureLog.enter();
        try {
            assertFalse(FailureLog.isEngineWrapper(engines), "nothing built yet");
            assertSame(engines, FailureLog.builtByEngine(engines));
            assertTrue(FailureLog.isEngineWrapper(engines));
            assertEquals(named, CauseChains.describe(engines));
            assertSame(inner, CauseChains.nestedRunFailure(engines));
            assertFalse(FailureLog.isEngineWrapper(applications));
            assertEquals("check failed: inner failed (after " + named + ")", CauseChains.describe(applications));
            for (int i = 1; i < FailureLog.MAX_LOGGED; i++) {
                FailureLog.builtByEngine(new IllegalStateException("built " + i));
            }
            assertTrue(FailureLog.isEngineWrapper(engines), "kept while fewer than the bound are built after it");
            FailureLog.builtByEngine(new IllegalStateException("one more"));
            assertFalse(FailureLog.isEngineWrapper(engines), "the oldest is forgotten past the bound");
            FailureLog.builtByEngine(engines);
        } finally {
            FailureLog.leave();
        }
        assertFalse(FailureLog.isEngineWrapper(engines), "forgotten when the outermost run ends");
    }

    /**
     * A run's call-outs are told apart in a run fewer than {@value FailureLog#MARKED_DEPTHS} deep: what a run the
     * call-out in progress started logged is below the run, even when that run is too deep to mark call-outs of its
     * own, and what one an earlier call-out started logged isn't, until that call-out is taken up again. In a run
     * deeper than that, what any run it started logged is below it, as before call-outs were told apart.
     *
     * @param depth How deep the run is
     */
    @ParameterizedTest(name = "a run {0} deep")
    @ValueSource(ints = {FailureLog.MARKED_DEPTHS - 1, FailureLog.MARKED_DEPTHS})
    @DisplayName("#961: a run's call-outs are told apart up to the bound, and past it a failure below the run is a"
            + " nested run's whatever call-out started it")
    void callOutsToldApartUpToTheBound(int depth) {
        InternalError fatal = new InternalError("by a nested run");
        IllegalArgumentException rejection = new IllegalArgumentException("rejected by a nested run");
        ReportedFailure reported;
        FailureLog.Runs runs = FailureLog.enter();
        for (int i = 1; i < depth; i++) {
            runs = FailureLog.enter();
        }
        try {
            long first = runs.callOut();
            FailureLog.enter();
            try {
                assertTrue(FailureLog.unloggedFatal(fatal));
                FailureLog.loggedByRun(rejection);
                reported = CauseChains.report("reported by a nested run", null);
            } finally {
                FailureLog.leave();
            }
            assertBelow(true, fatal, rejection, reported, "the call-out that started the nested run");
            runs.callOut();
            assertBelow(depth >= FailureLog.MARKED_DEPTHS, fatal, rejection, reported, "a later call-out");
            runs.resume(first);
            assertBelow(true, fatal, rejection, reported, "the call-out that started it, taken up again");
        } finally {
            for (int i = 0; i < depth; i++) {
                FailureLog.leave();
            }
        }
    }

    /**
     * A failure a run that had ended logged, however deep, isn't below a run started after it, past the bound of
     * {@value FailureLog#MARKED_DEPTHS} too, where the run's call-outs aren't told apart:
     * {@link FailureLog#enter} lowers it to the depth the later run starts at.
     *
     * @param depth How deep the later run is
     */
    @ParameterizedTest(name = "a run {0} deep")
    @ValueSource(ints = {FailureLog.MARKED_DEPTHS - 1, FailureLog.MARKED_DEPTHS,
        FailureLog.MARKED_DEPTHS + 1})
    @DisplayName("#961: a failure a run that had ended logged deeper isn't below a later sibling, past the bound too")
    void loggedByASiblingThatHadEndedNotBelowPastTheBound(int depth) {
        InternalError fatal = new InternalError("by a nested run of an earlier sibling");
        IllegalArgumentException rejection = new IllegalArgumentException("rejected by a nested run");
        ReportedFailure reported;
        FailureLog.enter();
        for (int i = 2; i < depth; i++) {
            FailureLog.enter();
        }
        try {
            FailureLog.enter();
            try {
                FailureLog.enter();
                try {
                    assertTrue(FailureLog.unloggedFatal(fatal));
                    FailureLog.loggedByRun(rejection);
                    reported = CauseChains.report("reported by a nested run", null);
                } finally {
                    FailureLog.leave();
                }
            } finally {
                FailureLog.leave();
            }
            FailureLog.enter();
            try {
                assertBelow(false, fatal, rejection, reported, "a later sibling");
            } finally {
                FailureLog.leave();
            }
        } finally {
            for (int i = 1; i < depth; i++) {
                FailureLog.leave();
            }
        }
    }

    /**
     * A failure a run that had ended logged deeper isn't below a later sibling past the bound of
     * {@value FailureLog#MARKED_DEPTHS} when the parent logged failures of its own in between, at its shallower
     * depth: the parent's logging keeps the deepest depth a failure was logged at, rather than lowering it to its own,
     * so the later sibling's {@link FailureLog#enter} still finds a failure logged deeper than it starts, and
     * lowers that failure's depth to its own.
     *
     * @param depth How deep the later run is
     */
    @ParameterizedTest(name = "a run {0} deep, the parent logging in between")
    @ValueSource(ints = {FailureLog.MARKED_DEPTHS, FailureLog.MARKED_DEPTHS + 1,
        FailureLog.MARKED_DEPTHS + 2})
    @DisplayName("#1019: a failure a run that had ended logged deeper isn't below a later sibling past the bound, when"
            + " the parent logged failures of its own in between")
    void parentLogsBetweenPastTheBound(int depth) {
        InternalError fatal = new InternalError("by a nested run of an earlier sibling");
        IllegalArgumentException rejection = new IllegalArgumentException("rejected by a nested run");
        ReportedFailure reported;
        FailureLog.enter();
        for (int i = 2; i < depth; i++) {
            FailureLog.enter();
        }
        try {
            FailureLog.enter();
            try {
                FailureLog.enter();
                try {
                    assertTrue(FailureLog.unloggedFatal(fatal));
                    FailureLog.loggedByRun(rejection);
                    reported = CauseChains.report("reported by a nested run", null);
                } finally {
                    FailureLog.leave();
                }
            } finally {
                FailureLog.leave();
            }
            assertTrue(FailureLog.unloggedFatal(new InternalError("the parent's own")));
            FailureLog.loggedByRun(new IllegalStateException("the parent's own"));
            CauseChains.report("the parent's own", null);
            FailureLog.enter();
            try {
                assertBelow(false, fatal, rejection, reported, "a later sibling");
            } finally {
                FailureLog.leave();
            }
        } finally {
            for (int i = 1; i < depth; i++) {
                FailureLog.leave();
            }
        }
    }

    /**
     * A failure a run that had ended only reported, logging nothing, isn't below a later sibling past the bound of
     * {@value FailureLog#MARKED_DEPTHS} either: reporting it records the depth it was built at, as logging does.
     *
     * @param depth How deep the later run is
     */
    @ParameterizedTest(name = "only reported, a run {0} deep")
    @ValueSource(ints = {FailureLog.MARKED_DEPTHS, FailureLog.MARKED_DEPTHS + 1})
    @DisplayName("#1019: a failure a run that had ended only reported isn't below a later sibling past the bound")
    void onlyReportedPastTheBound(int depth) {
        ReportedFailure reported;
        FailureLog.enter();
        for (int i = 2; i < depth; i++) {
            FailureLog.enter();
        }
        try {
            FailureLog.enter();
            try {
                FailureLog.enter();
                try {
                    reported = CauseChains.report("reported by a nested run", null);
                } finally {
                    FailureLog.leave();
                }
            } finally {
                FailureLog.leave();
            }
            FailureLog.enter();
            try {
                assertFalse(FailureLog.loggedBelow(reported), "a later sibling: a run's failure");
            } finally {
                FailureLog.leave();
            }
        } finally {
            for (int i = 1; i < depth; i++) {
                FailureLog.leave();
            }
        }
    }

    @Test
    @DisplayName("#1019: a load() nested exactly as deep as a long has bits for counts as a run")
    void loadAtTheBoundCountsAsRun() {
        InternalError fatal = new InternalError("by a load " + FailureLog.MARKED_DEPTHS + " deep");
        FailureLog.enter();
        for (int i = 2; i < FailureLog.MARKED_DEPTHS; i++) {
            FailureLog.enter();
        }
        try {
            FailureLog.enterLoad();
            try {
                assertTrue(FailureLog.unloggedFatal(fatal));
            } finally {
                FailureLog.leave();
            }
            assertEquals(FailureLog.LoggedAt.NESTED_RUN, FailureLog.loggedAt(fatal));
        } finally {
            for (int i = 1; i < FailureLog.MARKED_DEPTHS; i++) {
                FailureLog.leave();
            }
        }
    }

    private static void assertBelow(boolean below, InternalError fatal, IllegalArgumentException rejection,
                                    ReportedFailure reported, String where) {
        assertEquals(below ? FailureLog.LoggedAt.NESTED_RUN : FailureLog.LoggedAt.NOT_BELOW,
                FailureLog.loggedAt(fatal), where + ": a fatal Error");
        assertEquals(below, FailureLog.loggedBelow(FailureLog.find(rejection)), where + ": rejected facts");
        assertEquals(below, FailureLog.loggedBelow(reported), where + ": a run's failure");
    }
}
