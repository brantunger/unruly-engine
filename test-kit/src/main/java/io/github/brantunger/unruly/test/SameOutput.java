package io.github.brantunger.unruly.test;

import org.jspecify.annotations.Nullable;
import org.opentest4j.AssertionFailedError;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.github.brantunger.unruly.test.KitFailures.describe;

/**
 * How the contract kit's checks compare a run's output with what they expected: numbers by value, so that {@code 1},
 * {@code 1L} and {@code 1.0} are the same output, and everything else with {@code equals}.
 */
final class SameOutput {

    private SameOutput() {
    }

    /**
     * Asserts that a run's output, or a list of outputs, is what was expected, comparing numbers by value: {@code 1},
     * {@code 1L} and {@code 1.0} are the same output. Everything else is compared with {@code equals}.
     *
     * @param expected The expected output
     * @param actual   The output the engine returned
     */
    static void assertSameOutput(@Nullable Object expected, @Nullable Object actual) {
        assertSameOutput(expected, actual, "");
    }

    /**
     * Asserts that a run's output is what was expected, as {@link #assertSameOutput(Object, Object)} does, for a check
     * with more than one run, whose failure says which run it was.
     *
     * @param expected The expected output
     * @param actual   The output the engine returned
     * @param run      The run that returned it, which the failure's message starts with; empty for none
     */
    static void assertSameOutput(@Nullable Object expected, @Nullable Object actual, String run) {
        if (!sameValue(expected, actual)) {
            // With both values, so that an IDE can show the difference.
            throw new AssertionFailedError((run.isEmpty() ? "" : run + " ==> ") + mismatch(expected, actual),
                    expected, actual);
        }
    }

    /**
     * Describes two outputs that aren't the same, as {@code expected: <...> but was: <...>}. When the first value in
     * them that differs prints the same on both sides, such as the {@code Integer} 1 and the {@code String} "1", it
     * says where that value is and what each side's class is, since the two texts alone would look equal.
     *
     * @param expected The expected output
     * @param actual   The output the engine returned
     * @return The description
     */
    static String mismatch(@Nullable Object expected, @Nullable Object actual) {
        String text = "expected: <" + describe(expected) + "> but was: <" + describe(actual) + ">";
        String hidden = hiddenDifference("", expected, actual);
        return hidden == null ? text : text + ", and " + hidden;
    }

    /**
     * Finds the first value that differs in two outputs that aren't the same, following maps with the same keys and
     * lists of the same size down as {@link #sameValue} does, and describes it if both sides print it the same.
     *
     * @param path     Where the two values are in the outputs: keys joined with {@code .}, and list indexes in
     *                 {@code []}; empty for the outputs themselves
     * @param expected The expected value
     * @param actual   The value the engine returned
     * @return {@code at <path>, expected <text> (<class>) but was <text> (<class>)}, or {@code null} if the two texts
     *         of the value that differs are different already
     */
    private static @Nullable String hiddenDifference(String path, @Nullable Object expected, @Nullable Object actual) {
        if (expected instanceof Map<?, ?> left && actual instanceof Map<?, ?> right
                && left.keySet().equals(right.keySet())) {
            for (Map.Entry<?, ?> entry : left.entrySet()) {
                Object other = right.get(entry.getKey());
                if (!sameValue(entry.getValue(), other)) {
                    String key = describe(entry.getKey());
                    return hiddenDifference(path.isEmpty() ? key : path + "." + key, entry.getValue(), other);
                }
            }
        }
        if (expected instanceof List<?> left && actual instanceof List<?> right && left.size() == right.size()) {
            for (int i = 0; i < left.size(); i++) {
                if (!sameValue(left.get(i), right.get(i))) {
                    return hiddenDifference(path + "[" + i + "]", left.get(i), right.get(i));
                }
            }
        }
        String expectedText = describe(expected);
        String actualText = describe(actual);
        if (!expectedText.equals(actualText)) {
            return null;
        }
        return (path.isEmpty() ? "" : "at " + path + ", ") + "expected " + expectedText + " (" + className(expected)
                + ") but was " + actualText + " (" + className(actual) + ")";
    }

    private static String className(@Nullable Object object) {
        return object == null ? "null" : object.getClass().getName();
    }

    static boolean sameValue(@Nullable Object expected, @Nullable Object actual) {
        if (expected instanceof Number left && actual instanceof Number right) {
            return sameNumber(left, right);
        }
        if (expected instanceof Map<?, ?> left && actual instanceof Map<?, ?> right) {
            return left.keySet().equals(right.keySet())
                    && left.keySet().stream().allMatch(key -> sameValue(left.get(key), right.get(key)));
        }
        if (expected instanceof List<?> left && actual instanceof List<?> right) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int i = 0; i < left.size(); i++) {
                if (!sameValue(left.get(i), right.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(expected, actual);
    }

    private static boolean sameNumber(Number left, Number right) {
        try {
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
        } catch (NumberFormatException e) {
            // NaN or an infinity, which have no BigDecimal form.
            return left.toString().equals(right.toString());
        }
    }
}
