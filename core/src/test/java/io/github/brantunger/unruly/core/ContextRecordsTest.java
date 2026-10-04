package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException.Issue.Severity;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.Expression;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
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
    @DisplayName("#1019: a compile context keeps an unmodifiable copy of the reserved fact names")
    void compileContextCopiesReservedFactNames() {
        Set<String> reserved = new HashSet<>(Set.of("ctx"));

        EngineCompileContext context = new EngineCompileContext(Set.of(), Set.of(), loader, Object.class, Map.of(),
                Map.of(), false, true, List.of(), reserved);
        reserved.add("late");

        assertEquals(Set.of("ctx"), context.reservedFactNames());
        assertThrows(UnsupportedOperationException.class, () -> context.reservedFactNames().add("late"));
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
        EngineEvaluationContext context = new EngineEvaluationContext(facts, Deadline.NONE);

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> context.facts().put("y", 2));

        assertTrue(ex.getMessage().startsWith("Cannot assign or declare 'y' in a condition"), ex.getMessage());
        assertEquals(Map.of("x", 1), context.facts());
    }

    @Test
    @DisplayName("an action context's facts reject writes as an action's do")
    void actionFactsReadOnly() {
        Map<String, Object> facts = new HashMap<>(Map.of("x", 1));
        EngineActionContext context = new EngineActionContext(facts, new HashMap<>(), Deadline.NONE);

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> context.facts().put("y", 2));

        assertTrue(ex.getMessage().startsWith("The facts passed to an action are read-only; 'y'"), ex.getMessage());
    }

    @Test
    @DisplayName("an action context needs an output object")
    void actionContextNeedsOutput() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new EngineActionContext(Map.of(), null, Deadline.NONE));

        assertEquals("output must not be null", ex.getMessage());
    }

    @Test
    @DisplayName("an evaluation or action context needs facts")
    void contextsNeedFacts() {
        NullPointerException evaluation = assertThrows(NullPointerException.class,
                () -> new EngineEvaluationContext(null, Deadline.NONE));
        NullPointerException action = assertThrows(NullPointerException.class,
                () -> new EngineActionContext(null, new HashMap<>(), Deadline.NONE));

        assertEquals("facts must not be null", evaluation.getMessage());
        assertEquals("facts must not be null", action.getMessage());
    }

    @Test
    @DisplayName("a context the test kit creates rejects a null or blank fact name with a run's message (#1018)")
    void testKitContextsCheckFactNames() {
        Map<String, String> messages = new HashMap<>();
        messages.put(null, "fact name must not be null");
        messages.put("", "fact name must not be blank");
        messages.put(" ", "fact name must not be blank");
        for (Map.Entry<String, String> name : messages.entrySet()) {
            // A good name beside the bad one, so each is checked whatever the order of the map.
            Map<String, Object> facts = new HashMap<>(Map.of("x", 1));
            facts.put(name.getKey(), 1);

            IllegalArgumentException evaluation = assertThrows(IllegalArgumentException.class,
                    () -> new EngineEvaluationContext(facts, (Instant) null));
            IllegalArgumentException action = assertThrows(IllegalArgumentException.class,
                    () -> new EngineActionContext(facts, new HashMap<>(), (Instant) null));

            assertEquals(name.getValue(), evaluation.getMessage());
            assertEquals(name.getValue(), action.getMessage());
        }
        // The names are checked once the facts are known not to be null, with the message they had before.
        assertEquals("facts must not be null", assertThrows(NullPointerException.class,
                () -> new EngineEvaluationContext(null, (Instant) null)).getMessage());
        assertEquals("facts must not be null", assertThrows(NullPointerException.class,
                () -> new EngineActionContext(null, new HashMap<>(), (Instant) null)).getMessage());
        // A name with no-break or zero-width spaces only isn't blank, as in a run.
        assertEquals(Set.of("\u00A0", "\u200B"), new EngineEvaluationContext(Map.of("\u00A0", 1, "\u200B", 2),
                (Instant) null).facts().keySet());
    }

    @Test
    @DisplayName("an evaluation or action context needs a deadline, which is Deadline.NONE for a run without one")
    void contextsNeedADeadline() {
        NullPointerException evaluation = assertThrows(NullPointerException.class,
                () -> new EngineEvaluationContext(Map.of(), (Deadline) null));
        NullPointerException action = assertThrows(NullPointerException.class,
                () -> new EngineActionContext(Map.of(), new HashMap<>(), (Deadline) null));

        assertEquals("runDeadline must not be null", evaluation.getMessage());
        assertEquals("runDeadline must not be null", action.getMessage());
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
        EngineEvaluationContext context = new EngineEvaluationContext(Map.of(), (Instant) null);

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

    @Test
    @DisplayName("a context's time left is at most its timeout, and zero once its deadline has passed")
    void timeLeftBeforeAndAfterTheDeadline() {
        Duration timeout = Duration.ofSeconds(60);
        Deadline ahead = Deadline.from(timeout);
        Deadline passed = Deadline.from(Duration.ZERO);

        for (EvaluationContext context : List.of(new EngineEvaluationContext(Map.of(), ahead),
                new EngineActionContext(Map.of(), new HashMap<>(), ahead))) {
            Duration left = context.timeLeft();
            assertTrue(left.isPositive() && left.compareTo(timeout) <= 0, left.toString());
        }
        assertEquals(Duration.ZERO, new EngineEvaluationContext(Map.of(), passed).timeLeft());
        assertEquals(Duration.ZERO, new EngineActionContext(Map.of(), new HashMap<>(), passed).timeLeft());
    }

    @Test
    @DisplayName("a context without a deadline has the most time left that converts to nanoseconds")
    void timeLeftWithoutADeadline() {
        for (EvaluationContext context : List.of(new EngineEvaluationContext(Map.of(), Deadline.NONE),
                new EngineActionContext(Map.of(), new HashMap<>(), (Instant) null))) {
            assertEquals(Duration.ofNanos(Long.MAX_VALUE), context.timeLeft());
            assertNull(context.deadline());
        }
    }

    @Test
    @DisplayName("a context equals only itself, even another created from the same facts and instant")
    void aContextEqualsOnlyItself() {
        Instant instant = Instant.parse("2030-01-01T00:00:00Z");
        Map<String, Object> output = new HashMap<>();
        EngineEvaluationContext evaluation = new EngineEvaluationContext(Map.of(), instant);
        EngineActionContext action = new EngineActionContext(Map.of(), output, instant);

        assertNotEquals(evaluation, new EngineEvaluationContext(Map.of(), instant));
        assertNotEquals(action, new EngineActionContext(Map.of(), output, instant));
        assertNotEquals(new EngineEvaluationContext(Map.of(), (Instant) null),
                new EngineEvaluationContext(Map.of(), (Instant) null));
        assertNotEquals(new EngineActionContext(Map.of(), output, (Instant) null),
                new EngineActionContext(Map.of(), output, (Instant) null));
        assertEquals(evaluation, evaluation);
        assertEquals(action, action);
        assertEquals(System.identityHashCode(evaluation), evaluation.hashCode());
        assertEquals(System.identityHashCode(action), action.hashCode());
    }

    @Test
    @DisplayName("a context's hash reads no fact and no output object, so one whose hashCode throws doesn't matter")
    void hashReadsNoFact() {
        Object boom = new Object() {
            @Override
            public int hashCode() {
                throw new IllegalStateException("a fact's hashCode");
            }
        };
        EngineEvaluationContext evaluation = new EngineEvaluationContext(Map.of("boom", boom), Deadline.NONE);
        EngineActionContext action = new EngineActionContext(Map.of("boom", boom), boom, Deadline.NONE);

        assertEquals(System.identityHashCode(evaluation), assertDoesNotThrow(evaluation::hashCode));
        assertEquals(System.identityHashCode(action), assertDoesNotThrow(action::hashCode));
    }

    @Test
    @DisplayName("a context created from an instant shows that instant, and is cancelled once it has passed")
    void contextFromAnInstant() {
        Instant ahead = Instant.now().plusSeconds(60);
        EngineEvaluationContext context = new EngineEvaluationContext(Map.of(), ahead);

        assertEquals(ahead, context.deadline());
        assertEquals("EvaluationContext(deadline=" + ahead + ")", context.toString());
        Duration left = context.timeLeft();
        assertTrue(left.compareTo(Duration.ofSeconds(59)) > 0 && left.compareTo(Duration.ofSeconds(61)) < 0,
                left.toString());
        assertEquals(Instant.MAX, new EngineActionContext(Map.of(), new HashMap<>(), Instant.MAX).deadline());
        assertFalse(new EngineActionContext(Map.of(), new HashMap<>(), Instant.MAX).isCancelled());
    }
}
