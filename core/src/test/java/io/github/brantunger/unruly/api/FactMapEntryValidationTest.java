package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FactMap entry writes are checked like put()")
class FactMapEntryValidationTest {

    @Test
    @DisplayName("entrySet() setValue rejects a fact whose name differs from the key")
    void entrySetValueRejectsMismatch() {
        Fact<Object> claim = new Fact<>("claim", 1);
        FactMap<Object> facts = new FactMap<>(claim);
        Map.Entry<String, FactReference<Object>> entry = facts.entrySet().iterator().next();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> entry.setValue(new Fact<>("other", 2)));

        assertTrue(ex.getMessage().contains("key 'claim' does not match the fact's name 'other'"));
        assertSame(claim, facts.get("claim"));
    }

    @Test
    @DisplayName("entrySet() setValue accepts a fact with the matching name")
    void entrySetValueAcceptsMatch() {
        Fact<Object> claim = new Fact<>("claim", 1);
        FactMap<Object> facts = new FactMap<>(claim);
        Fact<Object> replacement = new Fact<>("claim", 2);

        assertSame(claim, facts.entrySet().iterator().next().setValue(replacement));
        assertSame(replacement, facts.get("claim"));
    }

    @Test
    @DisplayName("replaceAll rejects a result whose name differs from its key and leaves the map unchanged")
    void replaceAllRejectsMismatch() {
        Fact<Object> a = new Fact<>("a", 1);
        Fact<Object> b = new Fact<>("b", 2);
        FactMap<Object> facts = new FactMap<>(a, b);

        assertThrows(IllegalArgumentException.class,
                () -> facts.replaceAll((key, fact) -> new Fact<>("b".equals(key) ? "renamed" : key, 9)));

        assertSame(a, facts.get("a"));
        assertSame(b, facts.get("b"));
    }

    @Test
    @DisplayName("replaceAll stores results with matching names")
    void replaceAllAcceptsMatch() {
        FactMap<Object> facts = new FactMap<>(new Fact<>("a", 1), new Fact<>("b", 2));

        facts.replaceAll((key, fact) -> new Fact<>(key, (Integer) fact.getValue() * 10));

        assertEquals(10, facts.getValue("a"));
        assertEquals(20, facts.getValue("b"));
    }

    @Test
    @DisplayName("the entry view still supports removal, size, equality and toString")
    void entryViewBehavesLikeAMapView() {
        Fact<Object> x = new Fact<>("x", 5);
        FactMap<Object> facts = new FactMap<>(x, new Fact<>("y", 6));
        Map.Entry<String, FactReference<Object>> expected = Map.entry("x", x);

        assertEquals(2, facts.entrySet().size());
        Map.Entry<String, FactReference<Object>> entry = facts.entrySet().stream()
                .filter(e -> "x".equals(e.getKey())).findFirst().orElseThrow();
        assertTrue(entry.equals(expected));
        assertEquals(expected.hashCode(), entry.hashCode());
        assertEquals("x=Fact{name='x', value=5}", entry.toString());
        assertSame(x, entry.getValue());

        Iterator<Map.Entry<String, FactReference<Object>>> it = facts.entrySet().iterator();
        while (it.hasNext()) {
            if ("y".equals(it.next().getKey())) {
                it.remove();
            }
        }
        assertEquals(Map.of("x", x), facts);
    }
}
