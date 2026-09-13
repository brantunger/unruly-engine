package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FactMap equals, hashCode and toString follow the Map contract")
class FactMapEqualityTest {

    @Test
    @DisplayName("two FactMaps with the same facts are equal and have the same hash code")
    void sameFactsEqual() {
        Fact<Object> fact = new Fact<>("x", 5);
        FactMap<Object> first = new FactMap<>(fact);
        FactMap<Object> second = new FactMap<>(new Fact<>("x", 5));

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    @DisplayName("two empty FactMaps are equal")
    void emptyMapsEqual() {
        assertEquals(new FactMap<>(), new FactMap<>());
    }

    @Test
    @DisplayName("equality with another Map implementation is symmetric")
    void symmetricWithHashMap() {
        Fact<Object> fact = new Fact<>("x", 5);
        FactMap<Object> factMap = new FactMap<>(fact);
        Map<String, FactReference<Object>> hashMap = new HashMap<>();
        hashMap.put("x", fact);

        assertEquals(factMap, hashMap);
        assertEquals(hashMap, factMap);
        assertEquals(hashMap.hashCode(), factMap.hashCode());
    }

    @Test
    @DisplayName("different facts, non-maps and null are not equal")
    void notEqual() {
        FactMap<Object> factMap = new FactMap<>(new Fact<>("x", 5));

        assertNotEquals(new FactMap<>(new Fact<>("x", 6)), factMap);
        assertNotEquals(new FactMap<>(new Fact<>("y", 5)), factMap);
        // Called directly: assertNotEquals would use String.equals, or skip equals entirely for null.
        assertFalse(factMap.equals("{x=5}"), "a non-map is never equal");
        assertFalse(factMap.equals(null), "null is never equal");
    }

    @Test
    @DisplayName("toString lists the facts instead of an identity hash")
    void readableToString() {
        assertEquals("{x=Fact{name='x', value=5}}", new FactMap<>(new Fact<>("x", 5)).toString());
    }
}
