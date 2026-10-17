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
        EngineEvaluationContext context = EngineEvaluationContext.of(facts, Deadline.NONE);

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> context.facts().put("y", 2));

        assertTrue(ex.getMessage().startsWith("Cannot assign or declare 'y' in a condition"), ex.getMessage());
        assertEquals(Map.of("x", 1), context.facts());
    }

    @Test
    @DisplayName("an action context's facts reject writes as an action's do")
    void actionFactsReadOnly() {
        Map<String, Object> facts = new HashMap<>(Map.of("x", 1));
        EngineActionContext context = EngineActionContext.of(facts, new HashMap<>(), Deadline.NONE);

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> context.facts().put("y", 2));

        assertTrue(ex.getMessage().startsWith("The facts passed to an action are read-only; 'y'"), ex.getMessage());
    }

    @Test
    @DisplayName("an action context needs an output object")
    void actionContextNeedsOutput() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> EngineActionContext.of(Map.of(), null, Deadline.NONE));

        assertEquals("output must not be null", ex.getMessage());
    }

    @Test
    @DisplayName("an evaluation or action context needs facts")
    void contextsNeedFacts() {
        NullPointerException evaluation = assertThrows(NullPointerException.class,
                () -> EngineEvaluationContext.of(null, Deadline.NONE));
        NullPointerException action = assertThrows(NullPointerException.class,
                () -> EngineActionContext.of(null, new HashMap<>(), Deadline.NONE));

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
                () -> EngineEvaluationContext.of(Map.of(), (Deadline) null));
        NullPointerException action = assertThrows(NullPointerException.class,
                () -> EngineActionContext.of(Map.of(), new HashMap<>(), (Deadline) null));

        assertEquals("runDeadline must not be null", evaluation.getMessage());
        assertEquals("runDeadline must not be null", action.getMessage());
    }

    private static void assertNullMessage(String expected, Executable creation) {
        assertEquals(expected, assertThrows(NullPointerException.class, creation).getMessage());
    }

    @Test
    @DisplayName("#1128: an evaluation or action context needs the run's start and tags, and names the one that is"
            + " null")
    void contextsNeedAStartAndTags() {
        Instant now = Instant.now();
        Map<String, Object> output = new HashMap<>();

        assertAll(
                () -> assertNullMessage("startedAt must not be null",
                        () -> new EngineEvaluationContext(Map.of(), (Instant) null, null, Set.of())),
                () -> assertNullMessage("tags must not be null",
                        () -> new EngineEvaluationContext(Map.of(), (Instant) null, now, null)),
                () -> assertNullMessage("startedAt must not be null",
                        () -> new EngineActionContext(Map.of(), output, (Instant) null, null, Set.of())),
                () -> assertNullMessage("tags must not be null",
                        () -> new EngineActionContext(Map.of(), output, (Instant) null, now, null)),
                () -> assertNullMessage("facts must not be null",
                        () -> new EngineEvaluationContext(null, (Instant) null, now, Set.of())),
                () -> assertNullMessage("facts must not be null",
                        () -> new EngineActionContext(null, output, (Instant) null, now, Set.of())));
    }

    @Test
    @DisplayName("#1128: a context created from a start and tags gives them back, the tags as the set it was given")
    void contextsFromAStartAndTags() {
        Instant start = Instant.parse("2027-06-01T09:00:00Z");
        Instant deadline = Instant.now().plusSeconds(60);
        Set<String> tags = Set.of("eu");
        Map<String, Object> output = new HashMap<>();

        EngineEvaluationContext evaluation = new EngineEvaluationContext(Map.of("x", 1), deadline, start, tags);
        EngineActionContext action = new EngineActionContext(Map.of("x", 1), output, deadline, start, tags);

        assertAll(
                () -> assertEquals(start, evaluation.startedAt()),
                () -> assertSame(tags, evaluation.tags()),
                () -> assertEquals(start, action.startedAt()),
                () -> assertSame(tags, action.tags()),
                () -> assertEquals(deadline, evaluation.deadline()),
                () -> assertEquals(deadline, action.deadline()),
                () -> assertEquals(Map.of("x", 1), evaluation.facts()),
                () -> assertEquals(Map.of("x", 1), action.facts()),
                () -> assertSame(output, action.output()),
                () -> assertNotSame(evaluation.run().scope(), action.run().scope(), "each is a run of its own"),
                () -> assertEquals(start, EngineEvaluationContext.startedAt(evaluation)),
                () -> assertSame(tags, EngineEvaluationContext.tags(action)));
        // The fact names are checked as the other test-kit constructors check them.
        Map<String, Object> blank = new HashMap<>(Map.of("x", 1));
        blank.put(" ", 2);
        assertEquals("fact name must not be blank", assertThrows(IllegalArgumentException.class,
                () -> new EngineEvaluationContext(blank, deadline, start, tags)).getMessage());
        assertEquals("fact name must not be blank", assertThrows(IllegalArgumentException.class,
                () -> new EngineActionContext(blank, output, deadline, start, tags)).getMessage());
    }

    @Test
    @DisplayName("#1128: a context created without a start or tags started now, by the system clock, and has none")
    void contextsWithoutAStartOrTags() {
        Instant before = Instant.now();
        Map<String, Object> output = new HashMap<>();

        List<EvaluationContext> contexts = List.of(EngineEvaluationContext.of(Map.of(), Deadline.NONE),
                EngineEvaluationContext.of(Map.of(), Deadline.NONE, new RunScope()),
                new EngineEvaluationContext(Map.of(), (Instant) null),
                EngineActionContext.of(Map.of(), output, Deadline.NONE),
                EngineActionContext.of(Map.of(), output, Deadline.NONE, new RunScope()),
                new EngineActionContext(Map.of(), output, (Instant) null));

        Instant after = Instant.now();
        for (EvaluationContext context : contexts) {
            Instant startedAt = context.startedAt();
            assertFalse(startedAt.isBefore(before) || startedAt.isAfter(after), context + " started at " + startedAt);
            assertEquals(Set.of(), context.tags());
        }
    }

    @Test
    @DisplayName("#1128: an action context in the same run as another has its start and tags, the tags as the same set")
    void sameRunActionContextCopiesTheStartAndTags() {
        Instant start = Instant.parse("2027-06-01T09:00:00Z");
        Set<String> tags = Set.of("eu", "retail");
        Map<String, Object> output = new HashMap<>();
        RunScope scope = new RunScope();
        EngineEvaluationContext evaluation = new EngineEvaluationContext(Map.of("x", 1),
                new RunState(Deadline.NONE, scope, start, tags));

        EngineActionContext action = new EngineActionContext(evaluation, output);
        EngineActionContext next = new EngineActionContext(action, output);

        assertAll(
                () -> assertEquals(start, action.startedAt()),
                () -> assertSame(tags, action.tags()),
                () -> assertEquals(start, next.startedAt()),
                () -> assertSame(tags, next.tags()),
                () -> assertSame(scope, next.run().scope()),
                () -> assertEquals(Map.of("x", 1), next.facts()));
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

        for (EvaluationContext context : List.of(EngineEvaluationContext.of(Map.of(), ahead),
                EngineActionContext.of(Map.of(), new HashMap<>(), ahead))) {
            Duration left = context.timeLeft();
            assertTrue(left.isPositive() && left.compareTo(timeout) <= 0, left.toString());
        }
        assertEquals(Duration.ZERO, EngineEvaluationContext.of(Map.of(), passed).timeLeft());
        assertEquals(Duration.ZERO, EngineActionContext.of(Map.of(), new HashMap<>(), passed).timeLeft());
    }

    @Test
    @DisplayName("a context without a deadline has the most time left that converts to nanoseconds")
    void timeLeftWithoutADeadline() {
        for (EvaluationContext context : List.of(EngineEvaluationContext.of(Map.of(), Deadline.NONE),
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
        EngineEvaluationContext evaluation = EngineEvaluationContext.of(Map.of("boom", boom), Deadline.NONE);
        EngineActionContext action = EngineActionContext.of(Map.of("boom", boom), boom, Deadline.NONE);

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
