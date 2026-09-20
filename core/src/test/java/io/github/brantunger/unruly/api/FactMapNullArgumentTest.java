package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FactMap rejects null arguments with a clear message")
class FactMapNullArgumentTest {

    @Test
    @DisplayName("FactMap(Map) with a null map")
    void nullMap() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new FactMap<>((Map<String, FactReference<Object>>) null));
        assertEquals("facts must not be null", ex.getMessage());
    }

    @Test
    @DisplayName("FactMap(FactReference...) with a null array")
    void nullArray() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new FactMap<>((FactReference<Object>[]) null));
        assertEquals("facts must not be null", ex.getMessage());
    }

    @Test
    @DisplayName("FactMap(FactReference...) with a null element")
    void nullElement() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new FactMap<>(new Fact<Object>("a", 1), null));
        assertEquals("facts must not contain null", ex.getMessage());
    }

    @Test
    @DisplayName("put(FactReference) with a null fact")
    void nullFact() {
        FactMap<Object> facts = new FactMap<>();

        NullPointerException ex = assertThrows(NullPointerException.class, () -> facts.put((FactReference<Object>) null));
        assertEquals("fact must not be null", ex.getMessage());
    }

    @Test
    @DisplayName("putAll with a null map")
    void nullPutAll() {
        FactMap<Object> facts = new FactMap<>();

        NullPointerException ex = assertThrows(NullPointerException.class, () -> facts.putAll(null));
        assertEquals("map must not be null", ex.getMessage());
    }
}
