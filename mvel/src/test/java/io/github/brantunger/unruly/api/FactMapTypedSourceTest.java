package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Before #77 this class didn't compile: FactMap(Map) only accepted a {@code Map<String, FactReference<T>>}.
 */
@DisplayName("FactMap(Map) accepts maps of a FactReference subtype")
class FactMapTypedSourceTest {

    @Test
    @DisplayName("a Map<String, Fact<Object>> can be copied into a FactMap<Object>")
    void acceptsMapOfFacts() {
        Map<String, Fact<Object>> source = new LinkedHashMap<>();
        source.put("a", new Fact<>("a", 1));
        source.put("b", new Fact<>("b", "two"));

        FactMap<Object> facts = new FactMap<>(source);

        assertEquals(1, facts.getValue("a"));
        assertEquals("two", facts.getValue("b"));
        assertSame(source.get("a"), facts.get("a"));
    }

    @Test
    @DisplayName("the key/name check still applies to a typed source map")
    void stillChecksNames() {
        Map<String, Fact<Object>> source = Map.of("a", new Fact<>("other", 1));

        assertThrows(IllegalArgumentException.class, () -> new FactMap<>(source));
    }
}
