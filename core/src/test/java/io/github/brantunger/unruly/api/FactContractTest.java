package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Fact and FactMap contracts the Javadoc promises")
class FactContractTest {

    @Test
    @DisplayName("FactMap.put(FactReference) returns the fact previously stored under the name")
    void putFactReferenceReturnsPrevious() {
        FactMap<Object> facts = new FactMap<>();
        Fact<Object> first = new Fact<>("x", 1);

        assertNull(facts.put(first));
        assertSame(first, facts.put(new Fact<>("x", 2)));
        assertEquals(2, facts.getValue("x"));
    }

    @Test
    @DisplayName("Fact.hashCode depends on the value as well as the name")
    void hashCodeUsesValue() {
        assertNotEquals(new Fact<>("k", "a").hashCode(), new Fact<>("k", "b").hashCode());
    }

    @Test
    @DisplayName("removing a name through keySet() removes the fact")
    void keySetRemoveRemovesFact() {
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("x", 1);
        facts.setValue("y", 2);

        assertTrue(facts.keySet().remove("x"));

        assertFalse(facts.containsKey("x"));
        assertEquals(1, facts.size());
    }

    @Test
    @DisplayName("FactMap.replaceAll(null) names the argument")
    void replaceAllNullMessage() {
        FactMap<Object> facts = new FactMap<>();

        NullPointerException ex = assertThrows(NullPointerException.class, () -> facts.replaceAll(null));

        assertEquals("function must not be null", ex.getMessage());
    }
}
