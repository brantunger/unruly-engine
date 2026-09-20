package io.github.brantunger.unruly.api.language;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ActionResult: done, or properties to set on the output")
class ActionResultTest {

    @Test
    @DisplayName("done() has no properties, and is always the same result")
    void done() {
        assertEquals(Map.of(), ActionResult.done().properties());
        assertSame(ActionResult.done(), ActionResult.done());
        assertEquals("ActionResult.done()", ActionResult.done().toString());
    }

    @Test
    @DisplayName("set() keeps a copy of the properties, in order, including null values")
    void setCopiesInOrder() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("b", 2);
        properties.put("a", null);

        ActionResult result = ActionResult.set(properties);
        properties.put("c", 3);

        assertEquals(List.of("b", "a"), new ArrayList<>(result.properties().keySet()));
        assertNull(result.properties().get("a"));
        assertTrue(result.properties().containsKey("a"));
        assertThrows(UnsupportedOperationException.class, () -> result.properties().put("d", 4));
        assertEquals("ActionResult.set({b=2, a=null})", result.toString());
    }

    @Test
    @DisplayName("set() rejects a null map, a null name and an empty name")
    void setValidated() {
        Map<String, Object> nullName = new HashMap<>();
        nullName.put(null, 1);

        assertThrows(NullPointerException.class, () -> ActionResult.set(null));
        assertThrows(NullPointerException.class, () -> ActionResult.set(nullName));
        assertThrows(IllegalArgumentException.class, () -> ActionResult.set(Map.of("", 1)));
    }

    @Test
    @DisplayName("results with the same properties are equal, and an empty set() equals done()")
    void equality() {
        assertEquals(ActionResult.set(Map.of("a", 1)), ActionResult.set(Map.of("a", 1)));
        assertEquals(ActionResult.set(Map.of("a", 1)).hashCode(), ActionResult.set(Map.of("a", 1)).hashCode());
        assertNotEquals(ActionResult.set(Map.of("a", 1)), ActionResult.set(Map.of("a", 2)));
        assertEquals(ActionResult.done(), ActionResult.set(Map.of()));
        assertNotEquals(ActionResult.done(), "ActionResult.done()");
    }
}
