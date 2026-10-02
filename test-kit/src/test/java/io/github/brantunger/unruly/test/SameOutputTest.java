package io.github.brantunger.unruly.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SameOutput compares outputs with numbers by value, and says where two that print the same differ")
class SameOutputTest {

    @Test
    @DisplayName("numbers are the same when their values are, whatever their types")
    void numbersByValue() {
        assertTrue(SameOutput.sameValue(1, 1L));
        assertTrue(SameOutput.sameValue(1, 1.0));
        assertTrue(SameOutput.sameValue(BigDecimal.ONE, (short) 1));
        assertFalse(SameOutput.sameValue(1, 2L));
        assertFalse(SameOutput.sameValue(1, "1"));
    }

    @Test
    @DisplayName("NaN and the infinities, which have no BigDecimal form, are compared by their text")
    void numbersWithoutBigDecimalForm() {
        assertTrue(SameOutput.sameValue(Double.NaN, Double.NaN));
        assertTrue(SameOutput.sameValue(Double.POSITIVE_INFINITY, Float.POSITIVE_INFINITY));
        assertFalse(SameOutput.sameValue(Double.NaN, 1.0));
        assertFalse(SameOutput.sameValue(1, Double.NEGATIVE_INFINITY));
    }

    @Test
    @DisplayName("maps are the same when they have the same keys and the same value for each")
    void maps() {
        assertTrue(SameOutput.sameValue(Map.of("a", 1, "b", 2), Map.of("a", 1L, "b", 2.0)));
        assertFalse(SameOutput.sameValue(Map.of("a", 1), Map.of("b", 1)));
        assertFalse(SameOutput.sameValue(Map.of("a", 1), Map.of("a", 2)));
        assertFalse(SameOutput.sameValue(Map.of("a", 1), "{a=1}"));
    }

    @Test
    @DisplayName("lists are the same when they have the same size and the same value at each index")
    void lists() {
        assertTrue(SameOutput.sameValue(List.of(1, 2), List.of(1L, 2.0)));
        assertTrue(SameOutput.sameValue(List.of(), List.of()));
        assertFalse(SameOutput.sameValue(List.of(1), List.of(1, 2)));
        assertFalse(SameOutput.sameValue(List.of(1, 2), List.of(1, 3)));
        assertFalse(SameOutput.sameValue(List.of(1), "[1]"));
    }

    @Test
    @DisplayName("anything else is compared with equals")
    void others() {
        assertTrue(SameOutput.sameValue(null, null));
        assertTrue(SameOutput.sameValue("a", "a"));
        assertFalse(SameOutput.sameValue("a", null));
        assertFalse(SameOutput.sameValue(true, "true"));
    }

    @Test
    @DisplayName("outputs whose texts differ are described by their texts alone")
    void mismatchWithDifferentTexts() {
        assertEquals("expected: <{a=1}> but was: <{b=1}>", SameOutput.mismatch(Map.of("a", 1), Map.of("b", 1)));
        assertEquals("expected: <[1]> but was: <[1, 2]>", SameOutput.mismatch(List.of(1), List.of(1, 2)));
        assertEquals("expected: <{a=1}> but was: <{a=2}>", SameOutput.mismatch(Map.of("a", 1), Map.of("a", 2)));
        assertEquals("expected: <1> but was: <2>", SameOutput.mismatch(1, 2));
    }

    @Test
    @DisplayName("outputs that print the same are described with the classes of the value that differs")
    void mismatchWithTheSameText() {
        assertEquals("expected: <1> but was: <1>, and expected 1 (java.lang.Integer) but was 1 (java.lang.String)",
                SameOutput.mismatch(1, "1"));
        assertEquals("expected: <null> but was: <null>, and expected null (null) but was null (java.lang.String)",
                SameOutput.mismatch(null, "null"));
        assertEquals("expected: <{a=1}> but was: <{a=1}>, and expected {a=1} (java.util.HashMap) but was {a=1}"
                + " (java.lang.String)", SameOutput.mismatch(new HashMap<>(Map.of("a", 1)), "{a=1}"));
        assertEquals("expected: <[1]> but was: <[1]>, and expected [1] (java.util.ArrayList) but was [1]"
                + " (java.lang.String)", SameOutput.mismatch(new ArrayList<>(List.of(1)), "[1]"));
    }

    @Test
    @DisplayName("the value that differs is found through maps and lists, past the values that are the same")
    void mismatchFindsTheValue() {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("a", 1);
        expected.put("b", 2);
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("a", 1L);
        actual.put("b", "2");
        assertEquals("expected: <{a=1, b=2}> but was: <{a=1, b=2}>, and at b, expected 2 (java.lang.Integer) but was 2"
                + " (java.lang.String)", SameOutput.mismatch(expected, actual));

        assertEquals("expected: <[1, 2]> but was: <[1, 2]>, and at [1], expected 2 (java.lang.Integer) but was 2"
                + " (java.lang.String)", SameOutput.mismatch(List.of(1, 2), List.of(1, "2")));

        assertEquals("expected: <{a={b=[1]}}> but was: <{a={b=[1]}}>, and at a.b[0], expected 1 (java.lang.Integer)"
                        + " but was 1 (java.lang.String)",
                SameOutput.mismatch(Map.of("a", Map.of("b", List.of(1))), Map.of("a", Map.of("b", List.of("1")))));
    }

    @Test
    @DisplayName("maps or lists that are the same are described as a whole, as they print the same")
    void mismatchOfTheSameOutputs() {
        // mismatch() is only called for outputs that aren't the same, but it doesn't rely on that.
        assertEquals("expected: <{a=1}> but was: <{a=1}>, and expected {a=1} (java.util.HashMap) but was {a=1}"
                        + " (java.util.HashMap)",
                SameOutput.mismatch(new HashMap<>(Map.of("a", 1)), new HashMap<>(Map.of("a", 1L))));
        assertEquals("expected: <[1]> but was: <[1]>, and expected [1] (java.util.ArrayList) but was [1]"
                        + " (java.util.ArrayList)",
                SameOutput.mismatch(new ArrayList<>(List.of(1)), new ArrayList<>(List.of(1L))));
    }

    @Test
    @DisplayName("maps whose keys print the same but aren't the same are described with the classes of the keys")
    void mismatchOfKeysThatPrintTheSame() {
        assertEquals("expected: <{1=a}> but was: <{1=a}>, and expected key 1 (java.lang.Integer) but was key 1"
                        + " (java.lang.String)",
                SameOutput.mismatch(new HashMap<>(Map.of(1, "a")), new HashMap<>(Map.of("1", "a"))));
        // Keys are compared with equals, not by value as numbers are.
        assertEquals("expected: <{1=a}> but was: <{1=a}>, and expected key 1 (java.lang.Integer) but was key 1"
                        + " (java.lang.Long)",
                SameOutput.mismatch(new HashMap<>(Map.of(1, "a")), new HashMap<>(Map.of(1L, "a"))));
    }

    @Test
    @DisplayName("keys that print the same but aren't the same are found in a map in a map, which is named")
    void mismatchOfNestedKeysThatPrintTheSame() {
        assertEquals("expected: <{m={1=a}}> but was: <{m={1=a}}>, and at m, expected key 1 (java.lang.Integer) but"
                        + " was key 1 (java.lang.String)",
                SameOutput.mismatch(Map.of("m", Map.of(1, "a")), Map.of("m", Map.of("1", "a"))));
    }

    @Test
    @DisplayName("keys that print the same but aren't the same are found when the maps print their entries in"
            + " different orders")
    void mismatchOfKeysThatPrintTheSameInAnotherOrder() {
        // A HashMap orders its entries by their keys' hash codes, which differ between 14 and "14".
        Map<Object, Object> expected = new HashMap<>();
        expected.put(10, "a");
        expected.put(14, "b");
        Map<Object, Object> actual = new HashMap<>();
        actual.put("10", "a");
        actual.put("14", "b");

        assertEquals("expected: <{10=a, 14=b}> but was: <{14=b, 10=a}>, and expected key 10 (java.lang.Integer) but"
                + " was key 10 (java.lang.String)", SameOutput.mismatch(expected, actual));
    }

    @Test
    @DisplayName("keys are compared with a map that refuses to look for a key of another class, or for null")
    void mismatchOfKeysInMapsThatRefuseThem() {
        // A TreeMap of Integer keys throws ClassCastException when asked for a String key.
        assertEquals("expected: <{1=a}> but was: <{1=a}>, and expected key 1 (java.lang.Integer) but was key 1"
                + " (java.lang.String)", SameOutput.mismatch(new TreeMap<>(Map.of(1, "a")), Map.of("1", "a")));

        // Map.of throws NullPointerException when asked for null.
        Map<Object, Object> withNull = new HashMap<>();
        withNull.put(null, "a");
        assertEquals("expected: <{1=a}> but was: <{null=a}>", SameOutput.mismatch(Map.of(1, "a"), withNull));
    }

    @Test
    @DisplayName("keys are compared with a map that throws anything else when asked for a key, unless it's fatal")
    void mismatchOfKeysInAMapThatThrows() {
        assertEquals("expected: <{7=1}> but was: <{x=1, y=2}>", SameOutput.mismatch(Map.of(7, 1), namesOnly(() -> {
            throw new IllegalArgumentException("not a name");
        })));
        assertEquals("expected: <{7=1}> but was: <{x=1, y=2}>", SameOutput.mismatch(Map.of(7, 1), namesOnly(() -> {
            throw new AssertionError("not a name");
        })));
        assertEquals("expected: <{7=1}> but was: <{x=1, y=2}>", SameOutput.mismatch(Map.of(7, 1), namesOnly(() -> {
            throw new StackOverflowError();
        })));

        Map<Object, Object> fatal = namesOnly(() -> {
            throw new OutOfMemoryError("refused");
        });
        assertEquals("refused",
                assertThrows(OutOfMemoryError.class, () -> SameOutput.mismatch(Map.of(7, 1), fatal)).getMessage());
    }

    @Test
    @DisplayName("a map that throws InterruptedException when asked for a key leaves the thread interrupted")
    void mismatchOfKeysInAMapThatIsInterrupted() {
        try {
            assertEquals("expected: <{7=1}> but was: <{x=1, y=2}>",
                    SameOutput.mismatch(Map.of(7, 1), namesOnly(() -> sneakyThrow(new InterruptedException()))));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            // So that no later test runs on an interrupted thread.
            Thread.interrupted();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable thrown) throws T {
        throw (T) thrown;
    }

    /**
     * A map of {@code x=1} and {@code y=2} whose {@code containsKey} runs a refusal for a key that isn't a
     * {@code String}.
     */
    private static Map<Object, Object> namesOnly(Runnable refusal) {
        Map<Object, Object> map = new HashMap<>() {
            @Override
            public boolean containsKey(Object key) {
                if (!(key instanceof String)) {
                    refusal.run();
                }
                return super.containsKey(key);
            }
        };
        map.put("x", 1);
        map.put("y", 2);
        return map;
    }

    @Test
    @DisplayName("an expected key is paired with the output's key of another class that prints the same, past one"
            + " of its own class")
    void mismatchOfKeysPassesOverAKeyOfTheSameClass() {
        StringBuilder key = new StringBuilder("1");
        StringBuilder other = new StringBuilder("1");
        assertNotEquals(key, other);
        Map<Object, Object> actual = new LinkedHashMap<>();
        actual.put(other, "a");
        actual.put("1", "b");

        assertEquals("expected: <{1=a}> but was: <{1=a, 1=b}>, and expected key 1 (java.lang.StringBuilder) but was"
                + " key 1 (java.lang.String)", SameOutput.mismatch(Map.of(key, "a"), actual));
    }

    @Test
    @DisplayName("an output key that the expected map has isn't paired with an expected key the output lacks")
    void mismatchOfKeysPairsOnlyKeysTheExpectedMapLacks() {
        Map<Object, Object> expected = new LinkedHashMap<>();
        expected.put(1, "a");
        expected.put("1", "b");

        assertEquals("expected: <{1=a, 1=b}> but was: <{1=b}>", SameOutput.mismatch(expected, Map.of("1", "b")));
    }

    @Test
    @DisplayName("keys that print the same but aren't the same are described when the maps differ in other ways too")
    void mismatchOfKeysThatPrintTheSameInMapsThatPrintDifferently() {
        Map<Object, Object> expected = new LinkedHashMap<>();
        expected.put(1, "a");
        expected.put("b", 2);
        Map<Object, Object> actual = new LinkedHashMap<>();
        actual.put("1", "a");
        actual.put("b", 3);

        assertEquals("expected: <{1=a, b=2}> but was: <{1=a, b=3}>, and expected key 1 (java.lang.Integer) but was"
                + " key 1 (java.lang.String)", SameOutput.mismatch(expected, actual));
    }

    @Test
    @DisplayName("keys of the same class that print the same but aren't equal aren't described by their classes")
    void mismatchOfKeysOfTheSameClassThatPrintTheSame() {
        // A StringBuilder is equal only to itself.
        Map<Object, Object> expected = new HashMap<>();
        expected.put(new StringBuilder("k"), "a");
        Map<Object, Object> actual = new HashMap<>();
        actual.put(new StringBuilder("k"), "a");
        assertEquals("expected: <{k=a}> but was: <{k=a}>, and expected {k=a} (java.util.HashMap) but was {k=a}"
                + " (java.util.HashMap)", SameOutput.mismatch(expected, actual));

        // They are passed over for keys whose classes tell them apart.
        Map<Object, Object> expectedTwo = new LinkedHashMap<>();
        expectedTwo.put(new StringBuilder("k"), "a");
        expectedTwo.put(1, "b");
        Map<Object, Object> actualTwo = new LinkedHashMap<>();
        actualTwo.put(new StringBuilder("k"), "a");
        actualTwo.put("1", "b");
        assertEquals("expected: <{k=a, 1=b}> but was: <{k=a, 1=b}>, and expected key 1 (java.lang.Integer) but was"
                + " key 1 (java.lang.String)", SameOutput.mismatch(expectedTwo, actualTwo));
    }

    @Test
    @DisplayName("maps whose different keys print differently are described by their texts alone")
    void mismatchOfKeysThatPrintDifferently() {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("a", 1);
        expected.put("b", 2);
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("a", 1);
        actual.put("c", 2);

        assertEquals("expected: <{a=1, b=2}> but was: <{a=1, c=2}>", SameOutput.mismatch(expected, actual));
    }

    @Test
    @DisplayName("an output that isn't the same fails with both values, and the run it came from")
    void assertSameOutput() {
        assertDoesNotThrow(() -> SameOutput.assertSameOutput(Map.of("a", 1), Map.of("a", 1L)));

        AssertionFailedError plain = assertThrows(AssertionFailedError.class,
                () -> SameOutput.assertSameOutput(Map.of("a", 1), Map.of("a", 2)));
        assertEquals("expected: <{a=1}> but was: <{a=2}>", plain.getMessage());
        assertEquals(Map.of("a", 1), plain.getExpected().getValue());
        assertEquals(Map.of("a", 2), plain.getActual().getValue());

        AssertionFailedError named = assertThrows(AssertionFailedError.class,
                () -> SameOutput.assertSameOutput(1, "1", "the second run"));
        assertEquals("the second run ==> expected: <1> but was: <1>, and expected 1 (java.lang.Integer) but was 1"
                + " (java.lang.String)", named.getMessage());
    }

    @Test
    @DisplayName("an expected list longer than the output fails as an assertion that shows both")
    void expectedListLonger() {
        assertFalse(SameOutput.sameValue(List.of(1, 2), List.of(1)));

        AssertionFailedError thrown = assertThrows(AssertionFailedError.class,
                () -> SameOutput.assertSameOutput(List.of(1, 2), List.of(1)));
        assertEquals("expected: <[1, 2]> but was: <[1]>", thrown.getMessage());
    }

    @Test
    @DisplayName("an output key whose equals throws makes the output not the same, and the check fails with its own"
            + " message")
    void outputKeyWhoseEqualsThrows() {
        for (Runnable refusal : refusals()) {
            Map<Object, Object> actual = new HashMap<>();
            actual.put(new RefusingKey(refusal), 1);

            assertFalse(SameOutput.sameValue(Map.of("a", 1), actual));
            AssertionFailedError thrown = assertThrows(AssertionFailedError.class,
                    () -> SameOutput.assertSameOutput(Map.of("a", 1), actual));
            assertEquals("expected: <{a=1}> but was: <{a=1}>, and expected key a (java.lang.String) but was key a"
                    + " (" + RefusingKey.class.getName() + ")", thrown.getMessage());
            assertSame(actual, thrown.getActual().getEphemeralValue());
        }

        Map<Object, Object> fatal = new HashMap<>();
        fatal.put(new RefusingKey(() -> {
            throw new OutOfMemoryError("refused");
        }), 1);
        assertEquals("refused", assertThrows(OutOfMemoryError.class,
                () -> SameOutput.assertSameOutput(Map.of("a", 1), fatal)).getMessage());
    }

    @Test
    @DisplayName("keys of the same class whose equals throws end the search for where two outputs differ")
    void mismatchOfKeysWhoseEqualsThrows() {
        for (Runnable refusal : refusals()) {
            Map<Object, Object> actual = new HashMap<>();
            actual.put(new RefusingKey(refusal), 1);

            assertEquals("expected: <{a=1}> but was: <{a=1}>",
                    SameOutput.mismatch(Map.of(new RefusingKey(refusal), 1), actual));
        }
    }

    @Test
    @DisplayName("a number whose toString throws isn't the same as any other, and the check fails with its own"
            + " message")
    void numberWhoseToStringThrows() {
        for (Runnable refusal : refusals()) {
            Number number = refusingNumber(refusal);
            String unavailable = " (message unavailable: " + thrownBy(refusal).getName() + ")";

            assertFalse(SameOutput.sameValue(2, number));
            assertFalse(SameOutput.sameValue(number, 2));
            assertFalse(SameOutput.sameValue(List.of(2), List.of(number)));

            Map<String, Object> actual = new HashMap<>();
            actual.put("a", number);
            AssertionFailedError thrown = assertThrows(AssertionFailedError.class,
                    () -> SameOutput.assertSameOutput(Map.of("a", 2), actual));
            assertEquals("expected: <{a=2}> but was: <java.util.HashMap" + unavailable + ">", thrown.getMessage());
            assertEquals("expected: <[2]> but was: <java.util.ArrayList" + unavailable + ">",
                    SameOutput.mismatch(List.of(2), new ArrayList<>(List.of(number))));
        }

        Number fatal = refusingNumber(() -> {
            throw new OutOfMemoryError("refused");
        });
        assertEquals("refused",
                assertThrows(OutOfMemoryError.class, () -> SameOutput.sameValue(2, fatal)).getMessage());
    }

    @Test
    @DisplayName("an output whose toString throws an error fails the check with both values attached")
    void outputWhoseToStringThrowsAnError() {
        Map<String, Object> actual = new HashMap<>();
        actual.put("a", refusingNumber(() -> {
            throw new AssertionError("refused");
        }));

        AssertionFailedError thrown = assertThrows(AssertionFailedError.class,
                () -> SameOutput.assertSameOutput(Map.of("a", "x"), actual));
        assertEquals("expected: <{a=x}> but was: <java.util.HashMap (message unavailable: java.lang.AssertionError)>",
                thrown.getMessage());
        assertEquals(Map.of("a", "x"), thrown.getExpected().getValue());
        assertSame(actual, thrown.getActual().getEphemeralValue());
        assertEquals("java.util.HashMap (message unavailable: java.lang.AssertionError)",
                thrown.getActual().getStringRepresentation());
    }

    @Test
    @DisplayName("an output list whose get or size throws isn't the same, and the check fails with its own message")
    void listThatThrows() {
        for (Runnable refusal : refusals()) {
            List<Object> refusingGet = new ArrayList<>(List.of(1, 2)) {
                @Override
                public Object get(int index) {
                    refusal.run();
                    return super.get(index);
                }
            };
            List<Object> refusingSize = new ArrayList<>(List.of(1, 2)) {
                @Override
                public int size() {
                    refusal.run();
                    return super.size();
                }
            };

            for (List<Object> actual : List.of(refusingGet, refusingSize)) {
                assertFalse(SameOutput.sameValue(List.of(1, 2), actual));
                assertEquals("expected: <[1, 2]> but was: <[1, 2]>", SameOutput.mismatch(List.of(1, 2), actual));
                AssertionFailedError thrown = assertThrows(AssertionFailedError.class,
                        () -> SameOutput.assertSameOutput(List.of(1, 2), actual));
                assertEquals("expected: <[1, 2]> but was: <[1, 2]>", thrown.getMessage());
                assertSame(actual, thrown.getActual().getEphemeralValue());
            }
        }
    }

    @Test
    @DisplayName("an output list element whose equals throws isn't asked, as the expected element is")
    void listElementWhoseEqualsThrows() {
        Object element = new RefusingKey(() -> {
            throw new IllegalArgumentException("refused");
        });

        assertFalse(SameOutput.sameValue(List.of("a"), List.of(element)));
        assertEquals("expected: <[a]> but was: <[a]>, and at [0], expected a (java.lang.String) but was a ("
                + RefusingKey.class.getName() + ")", SameOutput.mismatch(List.of("a"), List.of(element)));
    }

    @Test
    @DisplayName("an output map whose get throws at the same keys isn't the same, and the check fails with its own"
            + " message")
    void mapWhoseGetThrows() {
        for (Runnable refusal : refusals()) {
            Map<Object, Object> actual = refusingGet(refusal);

            assertFalse(SameOutput.sameValue(Map.of("a", 1), actual));
            assertEquals("expected: <{a=1}> but was: <{a=1}>", SameOutput.mismatch(Map.of("a", 1), actual));
            AssertionFailedError thrown = assertThrows(AssertionFailedError.class,
                    () -> SameOutput.assertSameOutput(Map.of("a", 1), actual));
            assertEquals("expected: <{a=1}> but was: <{a=1}>", thrown.getMessage());
            assertEquals(Map.of("a", 1), thrown.getExpected().getValue());
            assertSame(actual, thrown.getActual().getEphemeralValue());
        }

        Map<Object, Object> fatal = refusingGet(() -> {
            throw new OutOfMemoryError("refused");
        });
        assertEquals("refused", assertThrows(OutOfMemoryError.class,
                () -> SameOutput.assertSameOutput(Map.of("a", 1), fatal)).getMessage());
        assertEquals("refused",
                assertThrows(OutOfMemoryError.class, () -> SameOutput.mismatch(Map.of("a", 1), fatal)).getMessage());
    }

    @Test
    @DisplayName("an output map whose keySet throws isn't the same, and the check fails with its own message")
    void mapWhoseKeySetThrows() {
        for (Runnable refusal : refusals()) {
            Map<Object, Object> actual = refusingKeySet(refusal);

            assertFalse(SameOutput.sameValue(Map.of(7, 1), actual));
            assertEquals("expected: <{7=1}> but was: <{x=1}>", SameOutput.mismatch(Map.of(7, 1), actual));
            AssertionFailedError thrown = assertThrows(AssertionFailedError.class,
                    () -> SameOutput.assertSameOutput(Map.of(7, 1), actual));
            assertEquals("expected: <{7=1}> but was: <{x=1}>", thrown.getMessage());
            assertSame(actual, thrown.getActual().getEphemeralValue());
        }

        Map<Object, Object> fatal = refusingKeySet(() -> {
            throw new OutOfMemoryError("refused");
        });
        assertEquals("refused",
                assertThrows(OutOfMemoryError.class, () -> SameOutput.mismatch(Map.of(7, 1), fatal)).getMessage());
    }

    @Test
    @DisplayName("an output map whose get throws InterruptedException leaves the thread interrupted")
    void mapWhoseGetIsInterrupted() {
        try {
            AssertionFailedError thrown = assertThrows(AssertionFailedError.class, () -> SameOutput.assertSameOutput(
                    Map.of("a", 1), refusingGet(() -> sneakyThrow(new InterruptedException()))));
            assertEquals("expected: <{a=1}> but was: <{a=1}>", thrown.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            // So that no later test runs on an interrupted thread.
            Thread.interrupted();
        }
    }

    /**
     * Refusals that throw an {@link IllegalArgumentException}, an {@link AssertionError} and a
     * {@link StackOverflowError}, none of which the engine rethrows.
     */
    private static List<Runnable> refusals() {
        return List.of(() -> {
            throw new IllegalArgumentException("refused");
        }, () -> {
            throw new AssertionError("refused");
        }, () -> {
            throw new StackOverflowError();
        });
    }

    private static Class<? extends Throwable> thrownBy(Runnable refusal) {
        return assertThrows(Throwable.class, refusal::run).getClass();
    }

    /**
     * The number 2, whose {@code toString} runs a refusal.
     */
    private static Number refusingNumber(Runnable refusal) {
        return new Number() {
            @Override
            public int intValue() {
                return 2;
            }

            @Override
            public long longValue() {
                return 2;
            }

            @Override
            public float floatValue() {
                return 2;
            }

            @Override
            public double doubleValue() {
                return 2;
            }

            @Override
            public String toString() {
                refusal.run();
                return "2";
            }
        };
    }

    /**
     * A map of {@code a=1} whose {@code get} runs a refusal.
     */
    private static Map<Object, Object> refusingGet(Runnable refusal) {
        Map<Object, Object> map = new HashMap<>() {
            @Override
            public Object get(Object key) {
                refusal.run();
                return super.get(key);
            }
        };
        map.put("a", 1);
        return map;
    }

    /**
     * A map of {@code x=1} whose {@code keySet} runs a refusal.
     */
    private static Map<Object, Object> refusingKeySet(Runnable refusal) {
        Map<Object, Object> map = new HashMap<>() {
            @Override
            public Set<Object> keySet() {
                refusal.run();
                return super.keySet();
            }
        };
        map.put("x", 1);
        return map;
    }

    /**
     * A key that prints as {@code a} and has its hash code, whose {@code equals} runs a refusal.
     */
    private static final class RefusingKey {

        private final Runnable refusal;

        RefusingKey(Runnable refusal) {
            this.refusal = refusal;
        }

        @Override
        public boolean equals(Object other) {
            refusal.run();
            return this == other;
        }

        @Override
        public int hashCode() {
            return "a".hashCode();
        }

        @Override
        public String toString() {
            return "a";
        }
    }
}
