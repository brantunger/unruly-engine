package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.ChildJvm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1302: the {@code toString()} of a rule with a validity window, of a run's result and of a loaded rule set wrote
 * their instants with {@link java.time.Instant#toString()}, whose formatter's classes, some 25 with a static
 * initializer, the JVM's first such call initialized: deep in a stack, that overflowed inside
 * {@code DateTimeFormatter}'s static initializer, which left every later {@code Instant.toString()} in the JVM
 * failing with {@link NoClassDefFoundError}, the application's own included, and with no engine built,
 * {@link ExceptionInInitializerError}'s class too, so every later build failed. Nothing the engine did initialized the
 * formatter first: the first build read the clock but formatted nothing. And run options with tags showed them
 * through a stream pipeline, the JVM's first with no engine built, which deep in a stack overflowed inside
 * {@code java.util.stream.StreamOpFlag}'s static initializer and left every later stream, and every build, failing.
 * So did a result's and a rule set's, which showed their rules' names through one: a result or a rule set the
 * application makes itself, with {@code RunResult.of} or {@code RuleSetInfo.of} for a test double, is made with no
 * engine built. Now the engine writes its instants itself, shows the tags and the rules' names with loops, and the
 * first build formats one instant, so the application's own formatting is safe after a build too. Each test runs
 * {@link DeepFirstToStringScenario} in a new JVM, so the deep call is the JVM's first, and checks that it returned its
 * text, and that the formatter, streams, {@code ExceptionInInitializerError} and engine builds work after it.
 */
@DisplayName("a first toString() deep in a stack leaves the JDK's formatter, streams and engine builds working (#1302)")
class DeepFirstToStringTest {

    // The scenario's recursion kept interpreted, so it reaches the same depth each time it looks for the end of the
    // stack.
    private static final String INTERPRETED_SCENARIO =
            "-XX:CompileCommand=exclude," + DeepFirstToStringScenario.class.getName() + "::descend";
    private static final String INSTANT = "2023-11-14T22:13:20.123Z";

    @Test
    @DisplayName("deep, with no engine built, a rule's first toString() shows its validity window")
    void ruleToString(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = scenario(dir, "rule");

        assertTrue(value(lines, DeepFirstToStringScenario.TEXT).contains("validFrom=" + INSTANT
                + ", validTo=2023-11-14T22:14:20.123Z"), "the deep call's text:\n" + String.join("\n", lines));
        assertNothingBroken(lines);
    }

    @Test
    @DisplayName("deep, after a shallow build, load and run, a result's first toString() shows when the run started")
    void resultToString(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = scenario(dir, "result");

        assertTrue(value(lines, DeepFirstToStringScenario.TEXT).contains("startedAt=" + INSTANT),
                "the deep call's text:\n" + String.join("\n", lines));
        assertNothingBroken(lines);
    }

    @Test
    @DisplayName("deep, after a shallow build, load and run, a rule set's first toString() shows when it was loaded")
    void rulesToString(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = scenario(dir, "rules");

        // The load's time is the system clock's, not the engine's, which gives a run its start and judges the
        // validity windows.
        assertTrue(value(lines, DeepFirstToStringScenario.TEXT)
                .matches(".*, loadedAt=\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z\\)"),
                "the deep call's text:\n" + String.join("\n", lines));
        assertNothingBroken(lines);
    }

    @Test
    @DisplayName("deep, with no engine built, the first toString() of a result the application made shows its rules")
    void ownResultToString(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = scenario(dir, "ownResult");

        assertEquals("RunResult(output={hit=true}, firedRules=[dated], evaluations=[], ruleSetChecksum="
                + DeepFirstToStringScenario.CHECKSUM + ", tags=[], startedAt=null)",
                value(lines, DeepFirstToStringScenario.TEXT), "the deep call's text:\n" + String.join("\n", lines));
        assertNothingBroken(lines);
    }

    @Test
    @DisplayName("deep, with no engine built, the first toString() of a rule set the application made shows its rules"
            + " and when it says they were loaded")
    void ownRulesToString(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = scenario(dir, "ownRules");

        assertEquals("RuleSetInfo(rules=[dated], checksum=" + DeepFirstToStringScenario.CHECKSUM + ", loadedAt="
                + INSTANT + ")", value(lines, DeepFirstToStringScenario.TEXT),
                "the deep call's text:\n" + String.join("\n", lines));
        assertNothingBroken(lines);
    }

    @Test
    @DisplayName("deep, with no engine built and the tags given at the top, run options' first toString() shows them")
    void optionsToString(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = scenario(dir, "options");

        assertEquals("RunOptions(timeout=the engine's, tags=[eu])", value(lines, DeepFirstToStringScenario.TEXT),
                "the deep call's text:\n" + String.join("\n", lines));
        assertNothingBroken(lines);
    }

    // The walk started at the end of the stack, the first call that didn't overflow threw nothing, and the formatter,
    // streams, ExceptionInInitializerError and engine builds work after it.
    private static void assertNothingBroken(List<String> lines) {
        String output = String.join("\n", lines);
        assertNotEquals("0", value(lines, DeepFirstToStringScenario.OVERFLOWED), "calls that overflowed:\n" + output);
        assertEquals(DeepFirstToStringScenario.NOTHING, value(lines, DeepFirstToStringScenario.DEEP),
                "what the first call that didn't overflow threw:\n" + output);
        assertEquals("2023-11-14T22:13:20.123456789Z", value(lines, DeepFirstToStringScenario.FORMATTER),
                "Instant.toString() after the deep call:\n" + output);
        assertEquals("[1, 2]", value(lines, DeepFirstToStringScenario.STREAM), "a stream after the deep call:\n"
                + output);
        assertEquals("probe", value(lines, DeepFirstToStringScenario.ERROR),
                "a new ExceptionInInitializerError after the deep call:\n" + output);
        assertEquals("{hit=true}", value(lines, DeepFirstToStringScenario.ENGINE),
                "an engine built, loaded and run after the deep call:\n" + output);
    }

    private static List<String> scenario(Path dir, String mode) throws IOException, InterruptedException {
        return ChildJvm.run(dir, DeepFirstToStringScenario.class, "-XX:CompileCommand=quiet", INTERPRETED_SCENARIO,
                "-D" + DeepFirstToStringScenario.MODE + "=" + mode).lines().toList();
    }

    private static String value(List<String> lines, String start) {
        return lines.stream().filter(line -> line.startsWith(start)).map(line -> line.substring(start.length()))
                .findFirst().orElse("missing");
    }
}
