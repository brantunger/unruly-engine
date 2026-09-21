package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("the context records protect what they're given, whoever creates them")
class ContextRecordsTest {

    private final ClassLoader loader = getClass().getClassLoader();

    @Test
    @DisplayName("a compile context keeps unmodifiable copies of the imports")
    void compileContextCopiesImports() {
        Set<String> packages = new HashSet<>(Set.of("java.util"));
        Set<Class<?>> classes = new HashSet<>(Set.of(List.class));

        EngineCompileContext context = new EngineCompileContext(packages, classes, loader);
        packages.add("java.io");
        classes.add(Map.class);

        assertEquals(Set.of("java.util"), context.packageImports());
        assertEquals(Set.of(List.class), context.classImports());
        assertThrows(UnsupportedOperationException.class, () -> context.packageImports().add("java.io"));
        assertThrows(UnsupportedOperationException.class, () -> context.classImports().add(Map.class));
    }

    @Test
    @DisplayName("a compile context needs a class loader")
    void compileContextNeedsClassLoader() {
        assertThrows(NullPointerException.class, () -> new EngineCompileContext(Set.of(), Set.of(), null));
    }

    @Test
    @DisplayName("an evaluation context's facts reject writes as a condition's do")
    void evaluationFactsReadOnly() {
        Map<String, Object> facts = new HashMap<>(Map.of("x", 1));
        EngineEvaluationContext context = new EngineEvaluationContext(facts, null);

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> context.facts().put("y", 2));

        assertTrue(ex.getMessage().startsWith("Cannot assign or declare 'y' in a condition"), ex.getMessage());
        assertEquals(Map.of("x", 1), context.facts());
    }

    @Test
    @DisplayName("an action context's facts reject writes as an action's do")
    void actionFactsReadOnly() {
        Map<String, Object> facts = new HashMap<>(Map.of("x", 1));
        EngineActionContext context = new EngineActionContext(facts, new HashMap<>(), null);

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> context.facts().put("y", 2));

        assertTrue(ex.getMessage().startsWith("The facts passed to an action are read-only; 'y'"), ex.getMessage());
    }

    @Test
    @DisplayName("an action context needs an output object")
    void actionContextNeedsOutput() {
        assertThrows(NullPointerException.class, () -> new EngineActionContext(Map.of(), null, null));
    }

    @Test
    @DisplayName("a context without a deadline is cancelled only while the thread's interrupt status is set")
    void cancelledWithoutADeadline() {
        EngineEvaluationContext context = new EngineEvaluationContext(Map.of(), null);

        assertNull(context.deadline());
        assertFalse(context.isCancelled());
        try {
            Thread.currentThread().interrupt();
            assertTrue(context.isCancelled(), "an interrupted run must stop");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("a context is cancelled once its deadline has passed")
    void cancelledByADeadline() {
        Instant passed = Instant.now().minusSeconds(1);
        Instant ahead = Instant.now().plusSeconds(60);

        assertTrue(new EngineEvaluationContext(Map.of(), passed).isCancelled());
        assertFalse(new EngineEvaluationContext(Map.of(), ahead).isCancelled());
        assertTrue(new EngineActionContext(Map.of(), new HashMap<>(), passed).isCancelled());
        assertFalse(new EngineActionContext(Map.of(), new HashMap<>(), ahead).isCancelled());
        assertEquals(ahead, new EngineActionContext(Map.of(), new HashMap<>(), ahead).deadline());
    }
}
