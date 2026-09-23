package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue.Severity;
import io.github.brantunger.unruly.api.language.Expression;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

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
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new EngineCompileContext(Set.of(), Set.of(), null));

        assertEquals("classLoader must not be null", ex.getMessage());
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
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new EngineActionContext(Map.of(), null, null));

        assertEquals("output must not be null", ex.getMessage());
    }

    @Test
    @DisplayName("an evaluation or action context needs facts")
    void contextsNeedFacts() {
        NullPointerException evaluation = assertThrows(NullPointerException.class,
                () -> new EngineEvaluationContext(null, null));
        NullPointerException action = assertThrows(NullPointerException.class,
                () -> new EngineActionContext(null, new HashMap<>(), null));

        assertEquals("facts must not be null", evaluation.getMessage());
        assertEquals("facts must not be null", action.getMessage());
    }

    private static void assertNullMessage(String expected, Executable creation) {
        assertEquals(expected, assertThrows(NullPointerException.class, creation).getMessage());
    }

    @Test
    @DisplayName("a run context names the argument that is null")
    void runContextNeedsItsArguments() {
        Instant now = Instant.now();

        assertAll(
                () -> assertNullMessage("matchPolicy must not be null",
                        () -> new EngineRunContext(1, null, null, "sum", Map.of(), Set.of(), now)),
                () -> assertNullMessage("ruleSetChecksum must not be null",
                        () -> new EngineRunContext(1, null, "firstMatch", null, Map.of(), Set.of(), now)),
                () -> assertNullMessage("facts must not be null",
                        () -> new EngineRunContext(1, null, "firstMatch", "sum", null, Set.of(), now)),
                () -> assertNullMessage("tags must not be null",
                        () -> new EngineRunContext(1, null, "firstMatch", "sum", Map.of(), null, now)),
                () -> assertNullMessage("startedAt must not be null",
                        () -> new EngineRunContext(1, null, "firstMatch", "sum", Map.of(), Set.of(), null)));
    }

    @Test
    @DisplayName("a compile context's warn names the argument that is null")
    void warnNeedsItsArguments() {
        EngineCompileContext context = new EngineCompileContext(Set.of(), Set.of(), loader);
        Expression source = new Expression("r", ExpressionKind.CONDITION, "x");
        Issue issue = new Issue(Severity.WARNING, 1, 1, "unused");

        assertAll(
                () -> assertNullMessage("source must not be null", () -> context.warn(null, issue)),
                () -> assertNullMessage("issue must not be null", () -> context.warn(source, null)));
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
