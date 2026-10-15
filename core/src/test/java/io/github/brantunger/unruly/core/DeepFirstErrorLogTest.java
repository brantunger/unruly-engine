package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.ChildJvm;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.core.DeepFirstErrorLogScenario.Site;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1208: the first message the engine logs initializes classes of the logging provider's, such as SLF4J's
 * {@code Level} and {@code FormattingTuple} with slf4j-simple, which building an engine leaves to it. Their first load
 * deep in a stack could overflow inside the engine's own log call, past the room the engine checks for, and the caller
 * got a bare {@link StackOverflowError}, with no message, in place of the failure the line was about, or of what a
 * call it warned about returns. An overflow in the log call is now caught, as one in keeping a failure on another is
 * (see {@code EngineEvaluationContext}): the line is lost, and the caller gets the failure, or the call goes on. The
 * test runs {@link DeepFirstErrorLogScenario} in a new JVM for each place the engine logs such a failure or warning, at
 * ERROR and at WARN, so the deep failure is the first the JVM logs, with the engine's logging on, and checks that the
 * first call that reaches the log call throws the failure, or nothing, not the overflow.
 */
@DisplayName("a failure the JVM's engines log first, deep in a stack, reaches the caller (#1208)")
class DeepFirstErrorLogTest {

    // The scenario's recursion kept interpreted, so it reaches the same depth each time it looks for the end of the
    // stack.
    private static final String INTERPRETED_SCENARIO =
            "-XX:CompileCommand=exclude," + DeepFirstErrorLogScenario.class.getName() + "::descend";
    // Each method compiled as soon as it is queued, while its caller waits, so the scenario's warm-up compiles the
    // engine's check of its stack's room even on a busy machine, where the JIT's threads could otherwise fall behind.
    private static final String COMPILED_IN_TIME = "-Xbatch";

    // Each site with the engine's log at ERROR, where a warning logs nothing, and at WARN, where its first one loads
    // the logging provider's classes too.
    private static Stream<Arguments> sitesAndLevels() {
        return Stream.of(Site.values()).flatMap(site -> Stream.of(Arguments.of(site, "error"),
                Arguments.of(site, "warn")));
    }

    @ParameterizedTest(name = "{0} at {1}")
    @MethodSource("sitesAndLevels")
    @DisplayName("deep, a first logged failure is thrown, or a warned-of call goes on, whatever happens to its line")
    void failureReachesTheCaller(Site site, String level, @TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = ChildJvm.run(dir, DeepFirstErrorLogScenario.class, "-XX:CompileCommand=quiet",
                INTERPRETED_SCENARIO, COMPILED_IN_TIME, "-Dorg.slf4j.simpleLogger.defaultLogLevel=" + level,
                "-Dorg.slf4j.simpleLogger.logFile=System.out", "-D" + DeepFirstErrorLogScenario.SITE + "=" + site)
                .lines().toList();

        String output = String.join("\n", lines);
        assertEquals("missing", value(lines, DeepFirstErrorLogScenario.OVERFLOWED), output);
        assertTrue(throwsAs(value(lines, DeepFirstErrorLogScenario.DEEP), thrown(site)), output);
    }

    // What the call throws: the failure, or the fatal error the scenario throws, rethrown as it is; null for a call
    // that throws nothing, one a warning is about.
    private static Class<?> thrown(Site site) {
        return switch (site) {
            case COMPILE, LIST -> RuleCompilationException.class;
            case RULE, UNIQUE_MATCH, NULL_OUTPUT, OUTPUT_THROWS, NULL_SESSION, STOPPED_WAITING, CANCELLED ->
                    RuleExecutionException.class;
            case FACT_REJECTED, NAME_REJECTED, NAME_CHECK_FAILS -> IllegalArgumentException.class;
            case LISTENER_THROWS, COMPILE_WARNING, VALUE_CLOSE_FAILS, SESSION_CLOSE_FAILS, EXTRA_COPY -> null;
            default -> OutOfMemoryError.class;
        };
    }

    // Whether what a call threw, as the scenario printed it, is of the class expected, or a class of its, or, when
    // none is expected, whether it threw nothing.
    private static boolean throwsAs(String thrown, Class<?> expected) {
        if (expected == null) {
            return DeepFirstErrorLogScenario.NOTHING.equals(thrown);
        }
        try {
            return expected.isAssignableFrom(Class.forName(thrown.split(":", 2)[0]));
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static String value(List<String> lines, String start) {
        return lines.stream().filter(line -> line.startsWith(start)).map(line -> line.substring(start.length()))
                .findFirst().orElse("missing");
    }
}
