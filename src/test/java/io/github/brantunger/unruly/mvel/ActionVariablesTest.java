package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ActionVariables reads the facts without copying them")
class ActionVariablesTest {

    /** Facts that fail the test if anything lists or copies them. */
    private static final class UncopiedFacts extends HashMap<String, Object> {
        private static final long serialVersionUID = 1L;

        UncopiedFacts(Map<String, Object> facts) {
            super(facts);
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            throw new AssertionError("the facts were copied");
        }
    }

    private final Map<String, Object> facts = new HashMap<>(Map.of("score", 1, "name", "a"));
    private final Object output = new Object();

    @Test
    @DisplayName("creating the variables and reading, writing and looking up names never copies the facts")
    void factsNotCopied() {
        Map<String, Object> variables = new ActionVariables(new UncopiedFacts(facts), output);

        assertEquals(1, variables.get("score"));
        assertTrue(variables.containsKey("name"));
        assertNull(variables.put("local", 5));
        assertEquals(5, variables.get("local"));
    }

    @Test
    @DisplayName("an assignment is local: it hides the fact from the action and leaves the fact unchanged")
    void assignmentIsLocal() {
        Map<String, Object> variables = new ActionVariables(facts, output);

        assertEquals(1, variables.put("score", 10));
        assertEquals(10, variables.get("score"));
        assertEquals(10, variables.put("score", 11));
        assertEquals(1, facts.get("score"));
        assertFalse(facts.containsKey("output"));
    }

    @Test
    @DisplayName("output is bound to the output object and can't be assigned")
    void outputBoundAndNotAssignable() {
        Map<String, Object> variables = new ActionVariables(facts, output);

        assertSame(output, variables.get("output"));
        assertTrue(variables.containsKey("output"));
        assertThrows(UnsupportedOperationException.class, () -> variables.put("output", new Object()));
        assertFalse(variables.containsKey("missing"));
        assertNull(variables.get("missing"));
    }

    @Test
    @DisplayName("listing the variables shows facts, locals and output, with locals taking precedence, read-only")
    void entrySetMerged() {
        Map<String, Object> variables = new ActionVariables(facts, output);
        variables.put("score", 10);

        assertEquals(Map.of("score", 10, "name", "a", "output", output), Map.copyOf(variables));
        assertEquals(3, variables.size());
        assertThrows(UnsupportedOperationException.class, () -> variables.entrySet().clear());
    }
}
