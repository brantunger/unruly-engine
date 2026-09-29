package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
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
import java.util.concurrent.atomic.AtomicReference;

import static io.github.brantunger.unruly.core.EngineLogs.capture;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The thread's record of the failures nested runs and loads logged: made only for a nested one, forgotten when the
 * outermost run ends, and bounded, so a failure logged before the last {@value LoggedFailures#MAX_LOGGED} is logged
 * again if it's thrown on, and none is ever left out. The fatal {@link Error}s runs logged are bounded the same way.
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
}
