package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleEvaluation;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunOptions;
import io.github.brantunger.unruly.api.RunResult;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import jdk.jfr.Configuration;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.SettingDescriptor;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Flight Recorder events a run emits, read back from a recording. The events are named, never referenced as
 * classes, so this test compiles on an engine without them. A recording is JVM-wide and other tests run in the same
 * JVM, so every assertion filters the events by this test's rule names or checksum, and never counts totals.
 */
@DisplayName("a run records Flight Recorder events")
class JfrEventsTest {

    private static final String RUN_EVENT = "io.github.brantunger.unruly.Run";
    private static final String RULE_EVENT = "io.github.brantunger.unruly.Rule";

    @TempDir
    Path dir;

    /** The test's recording, closed after each test so a failed assertion can't leave it running for the next. */
    private Recording recording;

    @AfterEach
    void closeRecording() {
        if (recording != null) {
            recording.close();
        }
        // A test that interrupted its own thread and then failed would leave the status set for the next test.
        Thread.interrupted();
    }

    /** Something a condition can call to stop the run from inside a rule: it interrupts the run's own thread. */
    public static final class Interrupter {
        public boolean interrupt() {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    /** Something a condition can call to sleep, so a run passes its deadline inside a rule. */
    public static class Sleeper {
        public boolean sleep(long millis) throws InterruptedException {
            Thread.sleep(millis);
            return true;
        }
    }

    /** Something a condition can call to fail. */
    public static final class Thrower {
        public boolean fail() {
            throw new IllegalStateException("the condition failed on purpose");
        }
    }

    private static Rule rule(String name, int priority, String condition) {
        return Rule.builder().ruleName(name).priority(priority).condition(condition)
                .action("output.put('" + name + "', true)").build();
    }

    /** Starts a recording with both events enabled and the run event's threshold removed. */
    private Recording recordEverything() {
        recording = new Recording();
        recording.enable(RUN_EVENT).withThreshold(Duration.ZERO);
        recording.enable(RULE_EVENT);
        recording.start();
        return recording;
    }

    /**
     * Starts a recording with the JDK's default settings, which say nothing about the engine's events, so each event's
     * own defaults apply.
     */
    private Recording recordDefaults() throws IOException {
        try {
            recording = new Recording(Configuration.getConfiguration("default"));
        } catch (java.text.ParseException e) {
            throw new IllegalStateException(e);
        }
        recording.start();
        return recording;
    }

    private List<RecordedEvent> stop(Recording recording, String name) throws IOException {
        recording.stop();
        Path file = dir.resolve(name + ".jfr");
        recording.dump(file);
        recording.close();
        return RecordingFile.readAllEvents(file);
    }

    private static Map<String, String> defaultSettings(String eventName) {
        EventType type = FlightRecorder.getFlightRecorder().getEventTypes().stream()
                .filter(candidate -> candidate.getName().equals(eventName)).findFirst()
                .orElseThrow(() -> new AssertionError("no event type named " + eventName));
        Map<String, String> settings = new HashMap<>();
        for (SettingDescriptor setting : type.getSettingDescriptors()) {
            settings.put(setting.getName(), setting.getDefaultValue());
        }
        return settings;
    }

    private static List<RecordedEvent> events(List<RecordedEvent> all, String type, Predicate<RecordedEvent> which) {
        return all.stream().filter(event -> event.getEventType().getName().equals(type)).filter(which).toList();
    }

    private static RecordedEvent runEvent(List<RecordedEvent> all, String checksum) {
        List<RecordedEvent> runs = events(all, RUN_EVENT, event -> checksum.equals(event.getString("ruleSetChecksum")));
        assertEquals(1, runs.size(), "one run event for the checksum " + checksum);
        return runs.get(0);
    }

    private static List<RecordedEvent> ruleEvents(List<RecordedEvent> all, RecordedEvent run) {
        long engine = run.getLong("engineId");
        long runId = run.getLong("runId");
        return events(all, RULE_EVENT,
                event -> event.getLong("engineId") == engine && event.getLong("runId") == runId);
    }

    private static String describe(RecordedEvent event) {
        return event.getString("ruleName") + " " + event.getString("phase") + " " + event.getString("result");
    }

    @Test
    @DisplayName("a completed all-matches run reports its counts, and every condition and action has a rule event")
    void completedRun() throws IOException {
        RulesEngine<Map<String, Object>> engine =
                RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build();
        engine.load(List.of(rule("jfr-all-first", 3, "score > 700"), rule("jfr-all-second", 2, "score > 900"),
                rule("jfr-all-third", 1, "score > 500")));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("score", 750);

        Recording recording = recordEverything();
        RunResult<Map<String, Object>> result = engine.runWithResult(facts);
        List<RecordedEvent> all = stop(recording, "completed");

        RecordedEvent run = runEvent(all, result.ruleSetChecksum());
        assertEquals("COMPLETED", run.getString("outcome"));
        assertEquals("allMatches", run.getString("matchPolicy"));
        assertEquals(0L, run.getLong("parentRunId"));
        assertTrue(run.getLong("engineId") > 0 && run.getLong("runId") > 0, run.toString());
        assertEquals(3, run.getInt("rulesEvaluated"));
        assertEquals(result.firedRules().size(), run.getInt("rulesFired"));
        assertEquals(2, run.getInt("rulesFired"));

        List<String> rules = ruleEvents(all, run).stream().map(JfrEventsTest::describe).toList();
        assertEquals(List.of("jfr-all-first CONDITION MATCHED", "jfr-all-second CONDITION NOT_MATCHED",
                "jfr-all-third CONDITION MATCHED", "jfr-all-first ACTION FIRED", "jfr-all-third ACTION FIRED"), rules);
        assertEquals("mvel", ruleEvents(all, run).get(0).getString("language"));
    }

    @Test
    @DisplayName("a first-match run counts only the conditions it evaluated, and a rule below the match has no event")
    void firstMatchRun() throws IOException {
        RulesEngine<Map<String, Object>> engine =
                RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new).build();
        engine.load(List.of(rule("jfr-first-miss", 3, "score > 900"), rule("jfr-first-hit", 2, "score > 700"),
                rule("jfr-first-below", 1, "score > 500")));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("score", 750);

        Recording recording = recordEverything();
        RunResult<Map<String, Object>> result = engine.runWithResult(facts);
        List<RecordedEvent> all = stop(recording, "first-match");

        RecordedEvent run = runEvent(all, result.ruleSetChecksum());
        long evaluated = result.evaluations().stream()
                .filter(evaluation -> evaluation.outcome() != RuleEvaluation.Outcome.NOT_EVALUATED).count();
        assertEquals(2, evaluated);
        assertEquals(evaluated, run.getInt("rulesEvaluated"));
        assertEquals(1, run.getInt("rulesFired"));
        assertEquals("firstMatch", run.getString("matchPolicy"));
        assertEquals(List.of("jfr-first-miss CONDITION NOT_MATCHED", "jfr-first-hit CONDITION MATCHED",
                "jfr-first-hit ACTION FIRED"), ruleEvents(all, run).stream().map(JfrEventsTest::describe).toList());
    }

    @Test
    @DisplayName("a rule the run skips has no event and isn't counted as evaluated")
    void skippedRulesNotRecorded() throws IOException {
        RulesEngine<Map<String, Object>> engine =
                RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build();
        engine.load(List.of(rule("jfr-skip-disabled", 3, "score > 500").toBuilder().enabled(false).build(),
                rule("jfr-skip-untagged", 2, "score > 500"),
                rule("jfr-skip-tagged", 1, "score > 500").toBuilder().tags(Set.of("eu")).build()));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("score", 750);

        Recording recording = recordEverything();
        RunResult<Map<String, Object>> result = engine.runWithResult(facts,
                RunOptions.defaults().withTags(Set.of("eu")));
        List<RecordedEvent> all = stop(recording, "skipped");

        RecordedEvent run = runEvent(all, result.ruleSetChecksum());
        assertEquals(1, run.getInt("rulesEvaluated"));
        assertEquals(1, run.getInt("rulesFired"));
        assertEquals(List.of("jfr-skip-tagged CONDITION MATCHED", "jfr-skip-tagged ACTION FIRED"),
                ruleEvents(all, run).stream().map(JfrEventsTest::describe).toList());
    }

    @Test
    @DisplayName("a condition that throws records FAILED on the rule and on the run, with the count so far")
    void failedCondition() throws IOException {
        RulesEngine<Map<String, Object>> engine =
                RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build();
        engine.load(List.of(rule("jfr-fail-first", 2, "score > 700"), rule("jfr-fail-throws", 1, "thrower.fail()")));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("score", 750);
        facts.setValue("thrower", new Thrower());
        String checksum = engine.rules().checksum();

        Recording recording = recordEverything();
        RuleExecutionException thrown = assertThrows(RuleExecutionException.class, () -> engine.run(facts));
        List<RecordedEvent> all = stop(recording, "failed");

        assertEquals("jfr-fail-throws", thrown.getRuleName());
        RecordedEvent run = runEvent(all, checksum);
        assertEquals("FAILED", run.getString("outcome"));
        assertEquals(2, run.getInt("rulesEvaluated"));
        assertEquals(0, run.getInt("rulesFired"), "the actions never ran");
        assertEquals(List.of("jfr-fail-first CONDITION MATCHED", "jfr-fail-throws CONDITION FAILED"),
                ruleEvents(all, run).stream().map(JfrEventsTest::describe).toList());
    }

    @Test
    @DisplayName("a run interrupted inside a condition records STOPPED on the rule and on the run")
    void stoppedDuringRule() throws IOException {
        RulesEngine<Map<String, Object>> engine =
                RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build();
        engine.load(List.of(rule("jfr-stop-inside", 1, "interrupter.interrupt()")));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("interrupter", new Interrupter());
        String checksum = engine.rules().checksum();

        Recording recording = recordEverything();
        RuleExecutionException thrown = assertThrows(RuleExecutionException.class, () -> engine.run(facts));
        assertTrue(Thread.interrupted(), "the run keeps the interrupt status set");
        List<RecordedEvent> all = stop(recording, "stopped");

        assertInstanceOf(InterruptedException.class, thrown.getCause());
        RecordedEvent run = runEvent(all, checksum);
        assertEquals("STOPPED", run.getString("outcome"));
        assertEquals(1, run.getInt("rulesEvaluated"));
        assertEquals(List.of("jfr-stop-inside CONDITION STOPPED"),
                ruleEvents(all, run).stream().map(JfrEventsTest::describe).toList());
    }

    @Test
    @DisplayName("a run that passes its deadline while waiting for a copy records STOPPED with nothing evaluated")
    void stoppedWhileWaiting() throws Exception {
        RulesEngine<Map<String, Object>> engine =
                RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).maxCopies(1)
                        .runTimeout(Duration.ofMillis(100)).build();
        engine.load(List.of(rule("jfr-wait-holder", 1, "sleeper.sleep(600)")));
        String checksum = engine.rules().checksum();
        CountDownLatch holding = new CountDownLatch(1);
        FactStore<Object> holderFacts = new FactMap<>();
        holderFacts.setValue("sleeper", new Sleeper() {
            @Override
            public boolean sleep(long millis) throws InterruptedException {
                holding.countDown();
                return super.sleep(millis);
            }
        });
        FactStore<Object> waiterFacts = new FactMap<>();
        waiterFacts.setValue("sleeper", new Sleeper());

        Recording recording = recordEverything();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // The holder's own run passes the deadline too, inside its condition: that is the STOPPED-during-rule case.
            Future<?> holder = executor.submit(() -> assertThrows(RuleExecutionException.class,
                    () -> engine.run(holderFacts)));
            assertTrue(holding.await(5, TimeUnit.SECONDS), "the holder never started its condition");
            RuleExecutionException thrown = assertThrows(RuleExecutionException.class, () -> engine.run(waiterFacts));
            assertInstanceOf(TimeoutException.class, thrown.getCause());
            assertTrue(thrown.getMessage().contains("while waiting for a compiled copy"), thrown.getMessage());
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        List<RecordedEvent> all = stop(recording, "waiting");

        List<RecordedEvent> runs = events(all, RUN_EVENT, event -> checksum.equals(event.getString("ruleSetChecksum")));
        assertEquals(2, runs.size(), "the holder's run and the waiting run");
        RecordedEvent waited = runs.stream().filter(event -> event.getInt("rulesEvaluated") == 0).findFirst()
                .orElseThrow(() -> new AssertionError("no run event with nothing evaluated: " + runs));
        assertEquals("STOPPED", waited.getString("outcome"));
        assertEquals(0, waited.getInt("rulesFired"));
        assertEquals(List.of(), ruleEvents(all, waited), "a run that never got a copy evaluated no rule");
    }

    @Test
    @DisplayName("a stop that a listener's fatal error closes is still STOPPED on the rule and on the run")
    void stopClosedByFatalError() throws IOException {
        RuleListener fatalOnError = new RuleListener() {
            @Override
            public void onError(Rule rule, RuleExecutionException error) {
                throw new OutOfMemoryError("from onError, on purpose");
            }
        };
        RulesEngine<Map<String, Object>> engine =
                RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).listener(fatalOnError).build();
        engine.load(List.of(rule("jfr-stop-fatal", 1, "interrupter.interrupt()")));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("interrupter", new Interrupter());
        String checksum = engine.rules().checksum();

        Recording recording = recordEverything();
        assertThrows(OutOfMemoryError.class, () -> engine.run(facts));
        assertTrue(Thread.interrupted(), "the run keeps the interrupt status set");
        List<RecordedEvent> all = stop(recording, "stop-fatal");

        RecordedEvent run = runEvent(all, checksum);
        assertEquals("STOPPED", run.getString("outcome"));
        assertEquals(List.of("jfr-stop-fatal CONDITION STOPPED"),
                ruleEvents(all, run).stream().map(JfrEventsTest::describe).toList());
    }

    @Test
    @DisplayName("with the defaults, a slow run is kept and its rules aren't")
    void defaultsKeepSlowRuns() throws IOException {
        RulesEngine<Map<String, Object>> engine =
                RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build();
        engine.load(List.of(rule("jfr-default-slow", 1, "sleeper.sleep(40)")));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("sleeper", new Sleeper());
        String checksum = engine.rules().checksum();

        Recording recording = recordDefaults();
        engine.run(facts);
        List<RecordedEvent> all = stop(recording, "defaults-slow");

        RecordedEvent run = runEvent(all, checksum);
        assertEquals("COMPLETED", run.getString("outcome"));
        assertTrue(run.getDuration().toMillis() >= 40, "the run event spans the run: " + run.getDuration());
        assertEquals(List.of(), ruleEvents(all, run), "the rule event is disabled by default");
    }

    @Test
    @DisplayName("by default the run event is on with a 10 ms threshold, and the rule event is off without a stack trace")
    void defaults() throws IOException {
        RulesEngine<Map<String, Object>> engine =
                RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).build();
        engine.load(List.of(rule("jfr-default-rule", 1, "score > 700")));
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("score", 750);
        // A run registers the events, so their types can be looked up.
        engine.run(facts);

        Map<String, String> run = defaultSettings(RUN_EVENT);
        assertEquals("true", run.get("enabled"));
        assertEquals("10 ms", run.get("threshold"));
        assertEquals("true", run.get("stackTrace"));

        Map<String, String> rule = defaultSettings(RULE_EVENT);
        assertEquals("false", rule.get("enabled"));
        assertEquals("false", rule.get("stackTrace"));
    }
}
