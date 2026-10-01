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
    @DisplayName("maps whose keys print the same but aren't the same get a hidden-difference note")
    void mismatchOfKeysThatPrintTheSame() {
        String mismatch = SameOutput.mismatch(new HashMap<>(Map.of(1, "a")), new HashMap<>(Map.of("1", "a")));

        assertTrue(mismatch.startsWith("expected: <{1=a}> but was: <{1=a}>, and expected {1=a} ("), mismatch);
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
}
