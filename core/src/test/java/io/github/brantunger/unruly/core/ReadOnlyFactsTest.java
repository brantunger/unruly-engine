package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ReadOnlyFacts")
class ReadOnlyFactsTest {

    private final Map<String, Object> backing = new HashMap<>(Map.of("status", "DENIED"));
    private final Map<String, Object> facts = ReadOnlyFacts.forConditions(backing);

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
        assertEquals("Cannot assign or declare 'status' in a condition: conditions can't change facts or create "
                + "variables. Move assignments and declarations into the action.", ex.getMessage());
        assertEquals("DENIED", backing.get("status"));
    }

    @Test
    @DisplayName("put escapes and shortens the name it rejects, as the engine's messages show names")
    void putEscapesTheName() {
        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> facts.put("a\n" + "x".repeat(300), 1));

        assertEquals("Cannot assign or declare 'a\\n" + "x".repeat(198) + "... (102 more characters)' in a condition: "
                + "conditions can't change facts or create variables. Move assignments and declarations into the "
                + "action.", ex.getMessage());
        assertEquals("The facts passed to a RuleListener are read-only; 'null' can't be changed.",
                assertThrows(UnsupportedOperationException.class,
                        () -> ReadOnlyFacts.forListeners(backing).put(null, 1)).getMessage());
    }

    @Test
    @DisplayName("the view for listeners reads the same facts and rejects a write with a message about listeners")
    void listenerView() {
        Map<String, Object> listenerFacts = ReadOnlyFacts.forListeners(backing);

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> listenerFacts.put("status", "APPROVED"));

        assertEquals("The facts passed to a RuleListener are read-only; 'status' can't be changed.", ex.getMessage());
        assertEquals(Map.of("status", "DENIED"), listenerFacts);
        assertEquals("DENIED", backing.get("status"));
    }

    @Test
    @DisplayName("the view for actions reads the same facts and rejects a write, pointing to the output object")
    void actionView() {
        Map<String, Object> actionFacts = ReadOnlyFacts.forActions(backing);

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> actionFacts.put("status", "APPROVED"));

        assertEquals("The facts passed to an action are read-only; 'status' can't be changed. Put the result in the "
                + "output object instead.", ex.getMessage());
        assertEquals(Map.of("status", "DENIED"), actionFacts);
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
