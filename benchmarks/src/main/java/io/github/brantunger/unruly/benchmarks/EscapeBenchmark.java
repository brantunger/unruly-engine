package io.github.brantunger.unruly.benchmarks;

import io.github.brantunger.unruly.core.Failures;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * What escaping costs: {@code Failures.escape}, which every failure's message and every name in one goes through,
 * over 999 characters of text. Plain text escapes nothing; the other inputs escape every character, or one in twenty
 * as ordinary right-to-left or emoji text does, so the cost of writing an escape shows.
 *
 * <p>
 * Run it with {@code ./gradlew :benchmarks:jmh -PjmhArgs="EscapeBenchmark -prof gc"}. See
 * {@code benchmarks/README.md}.
 * </p>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class EscapeBenchmark {

    /** How long each input is: one character short of what a message includes of an exception's text. */
    private static final int LENGTH = 999;

    /** How often {@code mixed} has a character that is escaped: one in this many. */
    private static final int ESCAPED_EVERY = 20;

    private static final char ZERO_WIDTH_SPACE = (char) 0x200b;
    private static final char START_OF_HEADING = (char) 0x0001;
    private static final char HEBREW_ALEF = (char) 0x05d0;
    private static final char RIGHT_TO_LEFT_MARK = (char) 0x200f;

    /**
     * The text to escape: {@code plain} (ASCII letters, nothing to escape), {@code zwsp} (every character a
     * zero-width space, a format character), {@code control} (every character U+0001) or {@code mixed} (Hebrew with a
     * right-to-left mark every twentieth character).
     */
    @Param({"plain", "zwsp", "control", "mixed"})
    String input;

    private String text;

    /** Creates the benchmark. JMH creates one for each input. */
    public EscapeBenchmark() {
        // Nothing to set up: makeText() builds the input once the parameter is set.
    }

    /** Builds the text for the chosen input. */
    @Setup
    public void makeText() {
        StringBuilder built = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            built.append(switch (input) {
                case "plain" -> 'a';
                case "zwsp" -> ZERO_WIDTH_SPACE;
                case "control" -> START_OF_HEADING;
                case "mixed" -> i % ESCAPED_EVERY == ESCAPED_EVERY - 1 ? RIGHT_TO_LEFT_MARK : HEBREW_ALEF;
                default -> throw new IllegalArgumentException("no input named " + input);
            });
        }
        text = built.toString();
    }

    /**
     * Escapes the text.
     *
     * @return The escaped text, so JMH keeps the work
     */
    @Benchmark
    public String escape() {
        return Failures.escape(text);
    }
}
