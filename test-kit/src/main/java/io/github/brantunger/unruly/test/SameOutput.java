package io.github.brantunger.unruly.test;

import org.jspecify.annotations.Nullable;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.ValueWrapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static io.github.brantunger.unruly.test.KitFailures.describe;

/**
 * How the contract kit's checks compare a run's output with what they expected: numbers by value, so that {@code 1},
 * {@code 1L} and {@code 1.0} are the same output, and everything else with {@code equals}. Maps, lists and numbers
 * whose comparison throws, such as at a key whose {@code equals} throws, a map whose {@code get} throws or a number
 * whose {@code toString} throws, aren't the same, so that the check fails with its own message. A value compared with
 * {@code equals}, such as a {@code Set}, is left to what the expected value's {@code equals} does.
 */
final class SameOutput {

    /**
     * What {@link #read} returns for a value whose reading threw, which no output can hold.
     */
    private static final Object UNREADABLE = new Object();

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
            // With both values, so that an IDE can show the difference, and with their texts from describe(), as
            // opentest4j's own text of a value lets an Error its toString() throws out.
            throw new AssertionFailedError((run.isEmpty() ? "" : run + " ==> ") + mismatch(expected, actual),
                    ValueWrapper.create(expected, describe(expected)), ValueWrapper.create(actual, describe(actual)));
        }
    }

    /**
     * Describes two outputs that aren't the same, as {@code expected: <...> but was: <...>}. When the first value in
     * them that differs prints the same on both sides, such as the {@code Integer} 1 and the {@code String} "1", it
     * says where that value is and what each side's class is, since the two texts alone would look equal. A map key
     * that the other side lacks, but where the other side has a key whose class has another name and that prints the
     * same, such as the same two, is described the same way, whether or not the two maps print the same.
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
     * lists of the same size down as {@link #sameValue} does, and describes it if both sides print it the same. Two
     * maps are first searched for a key that one lacks but where the other has a key whose class has another name and
     * that prints the same, before their texts are compared, since a map can print its entries in an order that
     * depends on its keys' classes, and since the two maps can differ in other ways too. An output map or list whose
     * keys, size or values can't be compared or read, as {@link #read} takes what they throw, ends the search with no
     * description, unless a pair of keys that print the same was found first.
     *
     * @param path     Where the two values are in the outputs: keys joined with {@code .}, and list indexes in
     *                 {@code []}; empty for the outputs themselves
     * @param expected The expected value
     * @param actual   The value the engine returned
     * @return For two map keys, {@code at <path>, expected key <text> (<class>) but was key <text> (<class>)}, even if
     *         the two maps print differently; otherwise {@code at <path>, expected <text> (<class>) but was <text>
     *         (<class>)}, or {@code null} if the two texts of the value that differs are different already, or if
     *         comparing the keys or sizes, or reading a value, threw
     */
    private static @Nullable String hiddenDifference(String path, @Nullable Object expected, @Nullable Object actual) {
        if (expected instanceof Map<?, ?> left && actual instanceof Map<?, ?> right) {
            String keys = hiddenKeyDifference(left, right);
            if (keys != null) {
                return at(path) + keys;
            }
            Object sameKeys = read(() -> left.keySet().equals(right.keySet()));
            if (sameKeys == UNREADABLE) {
                return null;
            }
            if (Boolean.TRUE.equals(sameKeys)) {
                for (Map.Entry<?, ?> entry : left.entrySet()) {
                    Object other = read(() -> right.get(entry.getKey()));
                    if (other == UNREADABLE) {
                        return null;
                    }
                    if (!sameValue(entry.getValue(), other)) {
                        String key = describe(entry.getKey());
                        return hiddenDifference(path.isEmpty() ? key : path + "." + key, entry.getValue(), other);
                    }
                }
            }
        }
        if (expected instanceof List<?> left && actual instanceof List<?> right) {
            Object sameSize = read(() -> left.size() == right.size());
            if (sameSize == UNREADABLE) {
                return null;
            }
            if (Boolean.TRUE.equals(sameSize)) {
                for (int i = 0; i < left.size(); i++) {
                    int index = i;
                    Object other = read(() -> right.get(index));
                    if (other == UNREADABLE) {
                        return null;
                    }
                    if (!sameValue(left.get(i), other)) {
                        return hiddenDifference(path + "[" + i + "]", left.get(i), other);
                    }
                }
            }
        }
        String expectedText = describe(expected);
        String actualText = describe(actual);
        if (!expectedText.equals(actualText)) {
            return null;
        }
        return at(path) + "expected " + expectedText + " (" + className(expected) + ") but was " + actualText + " ("
                + className(actual) + ")";
    }

    private static String at(String path) {
        return path.isEmpty() ? "" : "at " + path + ", ";
    }

    /**
     * Finds a key of the expected map that the output's map doesn't have, but that prints the same as a key whose
     * class has another name that the output's map has and the expected map doesn't, such as the {@code Integer} 1
     * and the {@code String} "1". Two keys whose classes have the same name and that print the same aren't paired, as
     * their classes wouldn't tell them apart.
     *
     * @param expected The expected map
     * @param actual   The map the engine returned
     * @return {@code expected key <text> (<class>) but was key <text> (<class>)} for the first such pair of keys, or
     *         {@code null} if there is none, or if the keys of the engine's map can't be read
     */
    private static @Nullable String hiddenKeyDifference(Map<?, ?> expected, Map<?, ?> actual) {
        // Collected only once a key is missing, which none is in maps with the same keys.
        Map<String, List<Object>> unmatched = null;
        for (Object key : expected.keySet()) {
            if (!containsKey(actual, key)) {
                if (unmatched == null) {
                    unmatched = unmatchedKeys(actual, expected);
                }
                String text = describe(key);
                for (Object other : unmatched.getOrDefault(text, List.of())) {
                    if (!className(other).equals(className(key))) {
                        return "expected key " + text + " (" + className(key) + ") but was key " + text + " ("
                                + className(other) + ")";
                    }
                }
            }
        }
        return null;
    }

    /**
     * Collects the keys of one map that another doesn't have, by their texts.
     *
     * @param map   The map whose keys are collected
     * @param other The map they are looked for in
     * @return The keys of {@code map} that {@code other} doesn't have, by their texts, with each text's keys in
     *         {@code map}'s order, or none if {@code map}'s keys can't be read, as {@link #read} takes what reading
     *         them throws
     */
    private static Map<String, List<Object>> unmatchedKeys(Map<?, ?> map, Map<?, ?> other) {
        Map<String, List<Object>> unmatched = new HashMap<>();
        boolean readable = holds(() -> {
            for (Object key : map.keySet()) {
                if (!containsKey(other, key)) {
                    unmatched.computeIfAbsent(describe(key), text -> new ArrayList<>()).add(key);
                }
            }
            return true;
        });
        return readable ? unmatched : Map.of();
    }

    /**
     * Whether a map has a key, taking a map that refuses to look for it, such as {@code Map.of}'s for {@code null} or
     * a {@code TreeMap}'s for a key of another class, to not have it, so that the check fails with its own message.
     * Whatever {@code containsKey} throws, a {@link StackOverflowError} or an {@link AssertionError} too, only means
     * the key isn't there; an error the engine rethrows (see {@link KitFailures#isFatal}) is rethrown, and after an
     * {@link InterruptedException} the thread stays interrupted.
     *
     * @param map The map
     * @param key The key
     * @return Whether the map has the key
     */
    private static boolean containsKey(Map<?, ?> map, @Nullable Object key) {
        return holds(() -> map.containsKey(key));
    }

    /**
     * Whether a comparison of two outputs, or of parts of them, holds, taking one that throws to not hold, so that the
     * check fails with its own message: a key whose {@code equals} throws, a number whose {@code toString} throws, or
     * a map or list whose {@code get} or {@code size} throws, makes the two outputs not the same. What is thrown is
     * taken as {@link #read} takes it.
     *
     * @param comparison The comparison
     * @return Whether it returned {@code true}
     */
    private static boolean holds(BooleanSupplier comparison) {
        return Boolean.TRUE.equals(read(comparison::getAsBoolean));
    }

    /**
     * Reads a value of an output, or something about it, returning {@link #UNREADABLE} when that throws. Whatever is
     * thrown, a {@link StackOverflowError} or an {@link AssertionError} too, only means the value can't be read; an
     * error the engine rethrows (see {@link KitFailures#isFatal}) is rethrown, and after an
     * {@link InterruptedException} the thread stays interrupted.
     *
     * @param reading The reading
     * @return What it returned, or {@link #UNREADABLE} if it threw
     */
    private static @Nullable Object read(Supplier<?> reading) {
        try {
            return reading.get();
        } catch (Throwable thrown) {
            KitFailures.rethrowIfFatal(thrown);
            keepInterrupted(thrown);
            return UNREADABLE;
        }
    }

    /**
     * Puts back the thread's interrupt status when what an output threw is an {@link InterruptedException}, which
     * methods such as {@code containsKey} or {@code get} don't declare, so a {@code catch} clause of its own can't take
     * it: the check's own failure stays the one reported, and the thread stays interrupted.
     *
     * @param thrown What the output threw
     */
    private static void keepInterrupted(Throwable thrown) {
        if (thrown instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private static String className(@Nullable Object object) {
        return object == null ? "null" : object.getClass().getName();
    }

    static boolean sameValue(@Nullable Object expected, @Nullable Object actual) {
        if (expected instanceof Number left && actual instanceof Number right) {
            return sameNumber(left, right);
        }
        if (expected instanceof Map<?, ?> left && actual instanceof Map<?, ?> right) {
            return holds(() -> left.keySet().equals(right.keySet())
                    && left.keySet().stream().allMatch(key -> sameValue(left.get(key), right.get(key))));
        }
        if (expected instanceof List<?> left && actual instanceof List<?> right) {
            return holds(() -> sameElements(left, right));
        }
        return Objects.equals(expected, actual);
    }

    private static boolean sameElements(List<?> left, List<?> right) {
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

    private static boolean sameNumber(Number left, Number right) {
        return holds(() -> sameNumberText(left.toString(), right.toString()));
    }

    private static boolean sameNumberText(String left, String right) {
        try {
            return new BigDecimal(left).compareTo(new BigDecimal(right)) == 0;
        } catch (NumberFormatException e) {
            // NaN or an infinity, which have no BigDecimal form.
            return left.equals(right);
        }
    }
}
