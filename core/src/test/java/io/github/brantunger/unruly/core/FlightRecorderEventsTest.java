package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.RunContext;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.brantunger.unruly.TestLogs.logsOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * #427: where Flight Recorder isn't there, such as a GraalVM native image built without JFR support, the engine
 * records no events instead of failing its first run. The native image itself is checked by CI's native-image job.
 * Where it is there, a run's event says how the run ended, whatever language its rules are written in.
 */
@DisplayName("the engine uses its Flight Recorder events only where they can be loaded")
class FlightRecorderEventsTest {

    private static final String RUN_EVENT = "io.github.brantunger.unruly.Run";

    @Test
    @DisplayName("on this JVM the events load, so they're used")
    void usableHere() {
        assertTrue(FlightRecorderEvents.USABLE);
    }

    @Test
    @DisplayName("events that load are usable")
    void loads() {
        AtomicBoolean loaded = new AtomicBoolean();

        assertTrue(FlightRecorderEvents.loads(() -> loaded.set(true)));
        assertTrue(loaded.get());
    }

    @Test
    @DisplayName("events whose registration fails, as in a native image without JFR support, aren't used, and it's"
            + " logged at DEBUG")
    void registrationFails() {
        boolean[] usable = new boolean[1];

        String logs = logsOf(() -> usable[0] = FlightRecorderEvents.loads(() -> {
            throw new UnsatisfiedLinkError("jdk.jfr.internal.JVM.isExcluded(Ljava/lang/Class;)Z");
        }));

        assertFalse(usable[0]);
        assertTrue(logs.contains("The engine records no Flight Recorder events here, because they can't be loaded: "
                + "java.lang.UnsatisfiedLinkError: jdk.jfr.internal.JVM.isExcluded"), logs);
    }

    @Test
    @DisplayName("events whose module isn't there, as in a class-path runtime without jdk.jfr, aren't used")
    void moduleMissing() {
        assertFalse(FlightRecorderEvents.loads(() -> {
            throw new NoClassDefFoundError("jdk/jfr/Event");
        }));
    }

    @Test
    @DisplayName("where the events can't be used, no event is started")
    void notStartedWhereUnusable() {
        assertNull(FlightRecorderEvents.startRun(false));
        assertNull(FlightRecorderEvents.startRule(false));
    }

    @Test
    @DisplayName("where they can, an event starts only when a recording wants it; none is running here")
    void startedOnlyForARecording() {
        assertNull(FlightRecorderEvents.startRun(true));
        assertNull(FlightRecorderEvents.startRule(true));
    }

    @Test
    @DisplayName("an engine runs on the class path in a JVM without the jdk.jfr module, recording no events")
    void runsWithoutTheJfrModule(@TempDir Path dir) throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        // Output to a file, so waiting is bounded by waitFor and not by the child closing a pipe.
        Path log = dir.resolve("scenario.log");
        Process process = new ProcessBuilder(java.toString(), "--limit-modules", "java.se",
                "-Dorg.slf4j.simpleLogger.defaultLogLevel=off",
                "-cp", System.getProperty("java.class.path"), NoJfrScenario.class.getName())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        boolean finished;
        try {
            finished = process.waitFor(60, TimeUnit.SECONDS);
        } finally {
            process.destroyForcibly();
        }
        String output = Files.readString(log, StandardCharsets.UTF_8);

        assertTrue(finished, "the scenario didn't finish:\n" + output);
        assertEquals(0, process.exitValue(), "scenario output:\n" + output);
        assertTrue(output.contains(NoJfrScenario.DONE), output);
    }

    @Test
    @DisplayName("a run whose wait for a copy is interrupted, and whose listener's beforeRun then throws a fatal"
            + " error, is recorded as STOPPED")
    void stoppedWaitWithAFatalBeforeRun(@TempDir Path dir) throws IOException {
        Thread waiter = Thread.currentThread();
        OutOfMemoryError fatal = new OutOfMemoryError("from beforeRun, on purpose");
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        // A session of its own for each copy, so the engine's one copy is one run's at a time.
        StubExpressionLanguage language = new StubExpressionLanguage().newSession(() -> new Session() {
        }).action((action, session) -> {
            holding.countDown();
            await(finish);
            return ActionResult.done();
        });
        RuleListener fatalBeforeRun = new RuleListener() {
            @Override
            public void beforeRun(RunContext run) {
                if (Thread.currentThread() == waiter) {
                    throw fatal;
                }
            }
        };
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(language).defaultLanguage(StubExpressionLanguage.LANGUAGE_NAME).maxCopies(1)
                .listener(fatalBeforeRun).build();
        engine.load(List.of(Rule.builder().ruleName("jfr-stopped-wait").condition("c").action("a").build()));
        String checksum = engine.rules().checksum();
        Thread holder = new Thread(() -> engine.run(new FactMap<>()));
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        List<RecordedEvent> all;

        // Closed however the test ends, so no recording is left running for the tests after it.
        try (Recording recording = new Recording()) {
            recording.enable(RUN_EVENT).withThreshold(Duration.ZERO);
            recording.start();
            logsOf(() -> {
                holder.start();
                await(holding);
                try {
                    // Interrupted before it asks for the copy the holder has, so its wait ends at once, whatever the
                    // timing: a thread whose interrupt status is set still waits for a copy in use.
                    Thread.currentThread().interrupt();
                    engine.run(new FactMap<>());
                } catch (OutOfMemoryError e) {
                    thrown.set(e);
                } finally {
                    Thread.interrupted();
                    finish.countDown();
                    join(holder);
                }
            });
            recording.stop();
            Path file = dir.resolve("stopped-wait.jfr");
            recording.dump(file);
            all = RecordingFile.readAllEvents(file);
        }

        assertSame(fatal, thrown.get());
        List<RecordedEvent> waited = all.stream()
                .filter(event -> event.getEventType().getName().equals(RUN_EVENT))
                .filter(event -> checksum.equals(event.getString("ruleSetChecksum")))
                .filter(event -> event.getInt("rulesEvaluated") == 0)
                .toList();
        assertEquals(1, waited.size(), "one run event with nothing evaluated: " + waited);
        assertEquals("STOPPED", waited.get(0).getString("outcome"));
    }

    @Test
    @DisplayName("anything but a LinkageError isn't taken for a missing Flight Recorder")
    void otherFailuresPropagate() {
        IllegalStateException bug = new IllegalStateException("not about Flight Recorder");

        assertSame(bug, assertThrows(IllegalStateException.class, () -> FlightRecorderEvents.loads(() -> {
            throw bug;
        })));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        assertFalse(thread.isAlive(), "timed out");
    }
}
