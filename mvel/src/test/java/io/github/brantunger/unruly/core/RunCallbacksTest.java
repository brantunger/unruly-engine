package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("listeners see a run start and end, including failures that belong to no rule")
class RunCallbacksTest {

    /** Records the callbacks it receives, and the contexts of the runs it saw. */
    private static final class Recorder implements RuleListener {

        private final List<String> calls = new CopyOnWriteArrayList<>();
        private final List<RunContext> runs = new CopyOnWriteArrayList<>();
        private final List<RunResult<?>> results = new CopyOnWriteArrayList<>();

        @Override
        public void beforeRun(RunContext run) {
            calls.add("beforeRun");
            runs.add(run);
        }

        @Override
        public void afterRun(RunContext run, RunResult<?> result) {
            calls.add("afterRun");
            runs.add(run);
            results.add(result);
        }

        @Override
        public void onRunError(RunContext run, RuntimeException error) {
            calls.add("onRunError: " + error.getMessage());
            runs.add(run);
        }

        @Override
        public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
            calls.add("beforeEvaluate " + rule.getRuleName());
        }

        @Override
        public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matchResult) {
            calls.add("afterEvaluate " + rule.getRuleName());
        }

        @Override
        public void beforeExecute(Rule rule, Object output) {
            calls.add("beforeExecute " + rule.getRuleName());
        }

        @Override
        public void afterExecute(Rule rule, Object output) {
            calls.add("afterExecute " + rule.getRuleName());
        }

        @Override
        public void onError(Rule rule, RuleExecutionException error) {
            calls.add("onError " + rule.getRuleName());
        }
    }

    private static Rule rule(String name, String condition, String action) {
        return Rule.builder().ruleName(name).condition(condition).action(action).build();
    }

    private static final Rule MATCHES = rule("r", "true", "output.put('k', 1)");

    @Test
    @DisplayName("a successful run is beforeRun, the rule callbacks, then afterRun with the result")
    void successfulRun() {
        Recorder recorder = new Recorder();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .listener(recorder).build();
        engine.load(List.of(MATCHES));

        engine.run(new FactMap<>());

        assertEquals(List.of("beforeRun", "beforeEvaluate r", "afterEvaluate r", "beforeExecute r", "afterExecute r",
                "afterRun"), recorder.calls);
        assertSame(recorder.runs.get(0), recorder.runs.get(1), "beforeRun and afterRun get the same run");
        RunResult<?> result = recorder.results.get(0);
        assertEquals(Map.of("k", 1), result.output());
        assertEquals(List.of(MATCHES), result.firedRules());
        assertEquals(engine.rules().checksum(), result.ruleSetChecksum());
    }

    @Test
    @DisplayName("a run carries its number, the match policy and the checksum of the rules it uses")
    void runContext() {
        Recorder first = new Recorder();
        Recorder all = new Recorder();
        RulesEngine<Map<String, Object>> firstMatch = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .listener(first).build();
        RulesEngine<Map<String, Object>> allMatches = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .listener(all).build();
        firstMatch.load(List.of(MATCHES));
        allMatches.load(List.of(MATCHES));

        firstMatch.run(new FactMap<>());
        firstMatch.run(new FactMap<>());
        allMatches.run(new FactMap<>());

        assertEquals("firstMatch", first.runs.get(0).matchPolicy());
        assertEquals("allMatches", all.runs.get(0).matchPolicy());
        assertEquals(1, first.runs.get(0).runId());
        assertEquals(2, first.runs.get(2).runId(), "the engine numbers its runs");
        assertEquals(1, all.runs.get(0).runId(), "each engine numbers its own runs");
        assertEquals(firstMatch.rules().checksum(), first.runs.get(0).ruleSetChecksum());
        assertNull(first.runs.get(0).parent());
    }

    @Test
    @DisplayName("a failing rule reaches onError and then onRunError, and not afterRun")
    void ruleFailure() {
        Recorder recorder = new Recorder();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .listener(recorder).build();
        engine.load(List.of(rule("r", "true", "missing.value")));

        assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        assertEquals(List.of("beforeRun", "beforeEvaluate r", "afterEvaluate r", "beforeExecute r", "onError r"),
                recorder.calls.subList(0, 5));
        assertTrue(recorder.calls.get(5).startsWith("onRunError: Failed to execute action for rule 'r'"),
                recorder.calls.toString());
        assertEquals(6, recorder.calls.size(), recorder.calls.toString());
    }

    @Test
    @DisplayName("an output supplier that throws reaches onRunError, which no callback reported before")
    void outputSupplierFailure() {
        Recorder recorder = new Recorder();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(() -> {
            throw new IllegalStateException("pool exhausted");
        }).listener(recorder).build();
        engine.load(List.of(MATCHES));

        assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        assertEquals(List.of("beforeRun", "beforeEvaluate r", "afterEvaluate r",
                "onRunError: Output factory threw java.lang.IllegalStateException: pool exhausted"), recorder.calls);
    }

    @Test
    @DisplayName("a rejected fact name reaches onRunError, and beforeRun already saw the fact")
    void factNameFailure() {
        Recorder recorder = new Recorder();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .listener(recorder).build();
        engine.load(List.of(MATCHES));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("output", 1);

        assertThrows(IllegalArgumentException.class, () -> engine.run(facts));

        assertEquals(List.of("beforeRun", "onRunError: 'output' is reserved for the output object and cannot be used "
                + "as a fact name"), recorder.calls);
        assertEquals(Map.of("output", 1), recorder.runs.get(0).facts(),
                "the names are checked after beforeRun, so the run's facts are the ones it was given");
    }

    @Test
    @DisplayName("a rule that throws a fatal Error reaches onRunError, and run() still rethrows the error")
    void fatalErrorFromARule() {
        Recorder recorder = new Recorder();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .listener(recorder).build();
        engine.load(List.of(rule("r", "true", "bomb.explode()")));
        FactStore<Object> facts = new FactMap<>();
        // Java code a rule calls, as in WrappedFatalErrorTest: MVEL wraps what it throws.
        facts.setValue("bomb", new WrappedFatalErrorTest.Bomb(new OutOfMemoryError("no room")));

        assertThrows(OutOfMemoryError.class, () -> engine.run(facts));

        assertTrue(recorder.calls.contains("onError r"), recorder.calls.toString());
        assertTrue(recorder.calls.stream().anyMatch(call -> call.startsWith("onRunError: The run failed with ")),
                recorder.calls.toString());
        assertFalse(recorder.calls.contains("afterRun"), recorder.calls.toString());
    }

    @Test
    @DisplayName("a fatal Error from a run callback is rethrown once every listener has had the callback")
    void fatalErrorFromAListener() {
        Recorder recorder = new Recorder();
        RuleListener fatal = new RuleListener() {
            @Override
            public void beforeRun(RunContext run) {
                throw new OutOfMemoryError("listener");
            }
        };
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .listener(fatal).listener(recorder).build();
        engine.load(List.of(MATCHES));

        OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, () -> engine.run(new FactMap<>()));

        assertEquals("listener", thrown.getMessage());
        assertEquals(List.of("beforeRun"), recorder.calls, "the other listener still got the callback");
    }

    @Test
    @DisplayName("a run interrupted while it waits for a compiled copy opens and closes a scope of its own")
    void interruptedWhileWaitingForACopy() throws InterruptedException {
        Recorder recorder = new Recorder();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .listener(recorder).maxCopies(1).build();
        engine.load(List.of(rule("hold", "true", "gate.hold()")));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("gate", new Gate(holding, release));

        Thread holder = new Thread(() -> engine.run(facts), "holds-the-copy");
        holder.start();
        assertTrue(holding.await(30, TimeUnit.SECONDS), "the first run never started");

        List<Throwable> thrown = new CopyOnWriteArrayList<>();
        List<Boolean> interrupted = new CopyOnWriteArrayList<>();
        Thread waiter = new Thread(() -> {
            try {
                engine.run(new FactMap<>());
            } catch (RuntimeException e) {
                thrown.add(e);
                interrupted.add(Thread.currentThread().isInterrupted());
            }
        }, "waits-for-a-copy");
        recorder.calls.clear();
        waiter.start();
        // The waiter is parked on the permit, or about to be; interrupting either way ends its wait.
        Thread.sleep(200);
        waiter.interrupt();
        waiter.join(30_000);
        release.countDown();
        holder.join(30_000);

        assertEquals(1, thrown.size(), "the waiting run should have failed");
        assertInstanceOf(RuleExecutionException.class, thrown.get(0));
        assertEquals(List.of(true), interrupted, "the interrupt status stays set");
        assertEquals(List.of("beforeRun", "onRunError: run() was interrupted while waiting for a compiled copy "
                + "of the rules: "
                + "all 1 were in use"), recorder.calls.stream().filter(call -> call.startsWith("beforeRun")
                || call.startsWith("onRunError")).toList(), recorder.calls.toString());
    }

    /** Java code a rule calls, which holds the run's copy of the rules until it's released. */
    public static class Gate {

        private final CountDownLatch holding;
        private final CountDownLatch release;

        Gate(CountDownLatch holding, CountDownLatch release) {
            this.holding = holding;
            this.release = release;
        }

        public boolean hold() throws InterruptedException {
            holding.countDown();
            return release.await(30, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("a run started from an action is a child of the run around it, and the parent is restored afterwards")
    void nestedRun() {
        Recorder recorder = new Recorder();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .listener(recorder).build();
        engine.load(List.of(rule("outer", "isdef inner", "output.put('nested', eng.run(inner))")));
        FactStore<Object> facts = new FactMap<>();
        FactMap<Object> inner = new FactMap<>();
        facts.setValue("eng", engine);
        facts.setValue("inner", inner);

        engine.run(facts);

        List<RunContext> starts = new ArrayList<>(recorder.runs);
        RunContext outer = starts.get(0);
        RunContext nested = starts.stream().filter(run -> run.parent() != null).findFirst().orElseThrow();
        assertSame(outer, nested.parent());
        assertEquals(2, nested.runId());

        recorder.runs.clear();
        engine.run(new FactMap<>());
        assertNull(recorder.runs.get(0).parent(), "the thread is no longer inside the first run");
    }
}
