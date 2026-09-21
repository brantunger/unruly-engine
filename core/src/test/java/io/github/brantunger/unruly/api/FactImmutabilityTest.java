package io.github.brantunger.unruly.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Uses reflection, so the test compiles against any {@code Fact}, {@code FactReference} and {@code FactStore},
 * whatever members they have.
 */
@DisplayName("facts are immutable and named, and a FactStore isn't a Map")
class FactImmutabilityTest {

    /** A FactReference without a name, created by reflection, as code that ignores the nullness annotations can. */
    @SuppressWarnings("unchecked")
    private static FactReference<Object> unnamed(Object value) {
        return (FactReference<Object>) Proxy.newProxyInstance(FactReference.class.getClassLoader(),
                new Class<?>[] {FactReference.class},
                (proxy, method, args) -> "getValue".equals(method.getName()) ? value : null);
    }

    @Test
    @DisplayName("Fact is a final class whose fields are private and final")
    void finalFact() {
        assertTrue(Modifier.isFinal(Fact.class.getModifiers()), "Fact isn't final");
        for (Field field : Fact.class.getDeclaredFields()) {
            assertTrue(Modifier.isPrivate(field.getModifiers()), field + " isn't private");
            assertTrue(Modifier.isFinal(field.getModifiers()), field + " isn't final");
        }
    }

    @Test
    @DisplayName("neither Fact nor FactReference has a setter")
    void noSetters() {
        List<String> setters = Arrays.stream(new Class<?>[] {Fact.class, FactReference.class})
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .map(Method::getName)
                .filter(name -> name.startsWith("set"))
                .toList();
        assertEquals(List.of(), setters);
    }

    @Test
    @DisplayName("Fact has no constructor that names the fact after its value")
    void noValueOnlyConstructor() {
        assertThrows(NoSuchMethodException.class, () -> Fact.class.getConstructor(Object.class));
    }

    @Test
    @DisplayName("a Fact needs a name, also when it copies another fact")
    void nameRequired() {
        NullPointerException ex = assertThrows(NullPointerException.class, () -> new Fact<>(null, 1));
        assertEquals("name must not be null", ex.getMessage());

        FactReference<Object> source = unnamed(1);
        ex = assertThrows(NullPointerException.class, () -> new Fact<Object>(source));
        assertEquals("name must not be null", ex.getMessage());
    }

    @Test
    @DisplayName("a FactStore isn't a Map")
    void factStoreNotMap() {
        assertFalse(Map.class.isAssignableFrom(FactStore.class), "FactStore is a Map");
    }

    @Test
    @DisplayName("FactMap still rejects a FactReference without a name, which a hand-written implementation can return")
    void factMapRejectsUnnamedReference() {
        FactMap<Object> facts = new FactMap<>();
        FactReference<Object> fact = unnamed(1);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> facts.put(fact));
        assertEquals("fact name must not be null", ex.getMessage());
        ex = assertThrows(IllegalArgumentException.class, () -> new FactMap<>(fact));
        assertEquals("fact name must not be null", ex.getMessage());
    }
}
