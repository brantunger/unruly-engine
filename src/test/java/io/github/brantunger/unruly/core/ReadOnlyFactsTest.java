package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ReadOnlyFacts")
class ReadOnlyFactsTest {

    private final Map<String, Object> backing = new HashMap<>(Map.of("status", "DENIED"));
    private final Map<String, Object> facts = new ReadOnlyFacts(backing);

    @Test
    @DisplayName("reads through to the backing map")
    void readsThrough() {
        assertEquals("DENIED", facts.get("status"));
        assertTrue(facts.containsKey("status"));
        assertFalse(facts.containsKey("missing"));
        assertEquals(Map.of("status", "DENIED"), facts);
        assertEquals(1, facts.size());
    }

    @Test
    @DisplayName("put names the variable and leaves the backing map unchanged")
    void putThrows() {
        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> facts.put("status", "APPROVED"));
        assertTrue(ex.getMessage().contains("'status'"));
        assertEquals("DENIED", backing.get("status"));
    }

    @Test
    @DisplayName("remove and clear are rejected")
    void removeAndClearThrow() {
        assertThrows(UnsupportedOperationException.class, () -> facts.remove("status"));
        assertThrows(UnsupportedOperationException.class, facts::clear);
        assertEquals(1, backing.size());
    }
}
