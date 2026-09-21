package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FactMap name validation")
class FactMapNameValidationTest {

    @Test
    @DisplayName("put(key, fact) rejects a key that differs from the fact's name")
    void putRejectsKeyNameMismatch() {
        FactMap<Object> facts = new FactMap<>();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> facts.put("claim", new Fact<>("other", 1)));
        assertTrue(ex.getMessage().contains("key 'claim' does not match the fact's name 'other'"));
        assertTrue(facts.isEmpty());
    }

    @Test
    @DisplayName("put(key, fact) rejects a null key")
    void putRejectsNullKey() {
        FactMap<Object> facts = new FactMap<>();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> facts.put(null, null));
        assertTrue(ex.getMessage().contains("fact name must not be null"));
    }

    @Test
    @DisplayName("put(key, null) is still allowed for a matching key")
    void putAllowsNullFact() {
        FactMap<Object> facts = new FactMap<>();

        assertDoesNotThrow(() -> facts.put("claim", null));
        assertTrue(facts.containsKey("claim"));
    }

    @Test
    @DisplayName("setValue rejects a null name")
    void setValueRejectsNullName() {
        FactMap<Object> facts = new FactMap<>();

        assertThrows(IllegalArgumentException.class, () -> facts.setValue(null, 1));
    }

    @Test
    @DisplayName("the varargs constructor rejects duplicate fact names")
    void varargsRejectsDuplicates() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new FactMap<>(new Fact<>("x", 1), new Fact<>("x", 2)));
        assertTrue(ex.getMessage().contains("duplicate fact name 'x'"));
    }

    @Test
    @DisplayName("the map constructor rejects a null key and a key/name mismatch")
    void mapConstructorValidates() {
        Map<String, FactReference<Object>> nullKey = new HashMap<>();
        nullKey.put(null, null);
        assertThrows(IllegalArgumentException.class, () -> new FactMap<>(nullKey));

        Map<String, FactReference<Object>> mismatch = new HashMap<>();
        mismatch.put("claim", new Fact<>("other", 1));
        assertThrows(IllegalArgumentException.class, () -> new FactMap<>(mismatch));
    }

    @Test
    @DisplayName("putAll rejects an invalid entry and leaves the map unchanged")
    void putAllValidatesAtomically() {
        FactMap<Object> facts = new FactMap<>();
        Map<String, FactReference<Object>> source = new HashMap<>();
        source.put("good", new Fact<>("good", 1));
        source.put("claim", new Fact<>("other", 2));

        assertThrows(IllegalArgumentException.class, () -> facts.putAll(source));
        assertTrue(facts.isEmpty());
    }
}
