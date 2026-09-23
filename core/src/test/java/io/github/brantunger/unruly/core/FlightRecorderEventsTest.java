package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.brantunger.unruly.TestLogs.logsOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * #427: where Flight Recorder isn't there, such as a GraalVM native image built without JFR support, the engine
 * records no events instead of failing its first run. The native image itself is checked by CI's native-image job.
 */
@DisplayName("the engine uses its Flight Recorder events only where they can be loaded")
class FlightRecorderEventsTest {

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
    @DisplayName("anything but a LinkageError isn't taken for a missing Flight Recorder")
    void otherFailuresPropagate() {
        IllegalStateException bug = new IllegalStateException("not about Flight Recorder");

        assertSame(bug, assertThrows(IllegalStateException.class, () -> FlightRecorderEvents.loads(() -> {
            throw bug;
        })));
    }
}
