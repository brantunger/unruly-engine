package io.github.brantunger.unruly.test;

import io.github.brantunger.unruly.api.exception.ExpressionKind;
import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.Session;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LanguageTestContexts creates the engine's contexts for a language's unit tests")
class LanguageTestContextsTest {

    @Test
    @DisplayName("a compile context without imports uses the thread's context class loader")
    void compileWithoutImports() {
        CompileContext context = LanguageTestContexts.compile();

        assertEquals(Set.of(), context.packageImports());
        assertEquals(Set.of(), context.classImports());
        assertSame(Thread.currentThread().getContextClassLoader(), context.classLoader());
    }

    @Test
    @DisplayName("without a context class loader, a compile context uses the kit's class loader")
    void compileWithoutContextClassLoader() {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(null);
        try {
            assertSame(LanguageTestContexts.class.getClassLoader(), LanguageTestContexts.compile().classLoader());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    @DisplayName("a compile context keeps copies of the imports it's given")
    void compileWithImports() {
        Set<String> packages = new HashSet<>(Set.of("java.util"));
        Set<Class<?>> classes = new HashSet<>(Set.of(List.class));
        ClassLoader loader = getClass().getClassLoader();

        CompileContext context = LanguageTestContexts.compile(packages, classes, loader);
        packages.clear();
        classes.clear();

        assertEquals(Set.of("java.util"), context.packageImports());
        assertEquals(Set.of(List.class), context.classImports());
        assertSame(loader, context.classLoader());
    }

    @Test
    @DisplayName("an evaluation context copies the facts, allows null values, and rejects writes as the engine does")
    void evaluation() {
        Map<String, Object> facts = new HashMap<>();
        facts.put("x", 1);
        facts.put("missing", null);

        EvaluationContext context = LanguageTestContexts.evaluation(facts);
        facts.put("x", 2);

        assertEquals(1, context.facts().get("x"));
        assertTrue(context.facts().containsKey("missing"));
        assertNull(context.facts().get("missing"));
        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> context.facts().put("y", 3));
        assertTrue(ex.getMessage().startsWith("Cannot assign or declare 'y' in a condition"), ex.getMessage());
    }

    @Test
    @DisplayName("an action context copies the facts, keeps the output, and rejects writes to the facts as the engine"
            + " does")
    void action() {
        Map<String, Object> output = new HashMap<>();

        ActionContext context = LanguageTestContexts.action(Map.of("x", 1), output);

        assertSame(output, context.output());
        assertEquals(Map.of("x", 1), context.facts());
        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> context.facts().put("y", 2));
        assertTrue(ex.getMessage().startsWith("The facts passed to an action are read-only; 'y'"), ex.getMessage());
    }

    @Test
    @DisplayName("the contexts it creates give the time left before their deadline, as the engine's do")
    void timeLeft() {
        Instant ahead = Instant.now().plusSeconds(60);
        Instant passed = Instant.now().minusSeconds(1);
        Map<String, Object> output = new HashMap<>();

        for (EvaluationContext context : List.of(LanguageTestContexts.evaluation(Map.of(), ahead),
                LanguageTestContexts.action(Map.of(), output, ahead))) {
            Duration left = context.timeLeft();
            // Converted once, when the context was created, with the system clock read again: allow a moment for that.
            assertTrue(left.compareTo(Duration.ofSeconds(59)) > 0 && left.compareTo(Duration.ofSeconds(61)) < 0,
                    left.toString());
        }
        assertEquals(Duration.ZERO, LanguageTestContexts.evaluation(Map.of(), passed).timeLeft());
        assertEquals(Duration.ZERO, LanguageTestContexts.action(Map.of(), output, passed).timeLeft());
        assertEquals(Duration.ofNanos(Long.MAX_VALUE), LanguageTestContexts.evaluation(Map.of()).timeLeft());
        assertEquals(Duration.ofNanos(Long.MAX_VALUE), LanguageTestContexts.action(Map.of(), output).timeLeft());
    }

    @Test
    @DisplayName("a context it creates equals only itself, as in a run, not another created from the same facts")
    void contextsEqualOnlyThemselves() {
        Map<String, Object> facts = Map.of("x", 1);
        Map<String, Object> output = new HashMap<>();
        EvaluationContext evaluation = LanguageTestContexts.evaluation(facts);
        ActionContext action = LanguageTestContexts.action(facts, output);

        assertNotEquals(evaluation, LanguageTestContexts.evaluation(facts));
        assertNotEquals(action, LanguageTestContexts.action(facts, output));
        assertEquals(evaluation, evaluation);
        assertEquals(action, action);
    }

    @Test
    @DisplayName("a context it creates keeps run-scoped values, for a run of its own that no other context shares")
    void runScopedValuesPerContext() {
        EvaluationContext evaluation = LanguageTestContexts.evaluation(Map.of());
        ActionContext action = LanguageTestContexts.action(Map.of(), new HashMap<>());

        Object value = evaluation.runScoped("key", Object::new);

        assertSame(value, evaluation.runScoped("key", Object::new));
        assertNotSame(value, action.runScoped("key", Object::new));
        assertSame(action.runScoped("key", Object::new), action.runScoped("key", Object::new));
        assertNotSame(value, LanguageTestContexts.evaluation(Map.of()).runScoped("key", Object::new));
    }

    @Test
    @DisplayName("an action context in the same run as another shares its run-scoped values, facts and deadline")
    void actionInTheSameRun() {
        Instant deadline = Instant.now().plusSeconds(60);
        EvaluationContext evaluation = LanguageTestContexts.evaluation(Map.of("x", 1), deadline);
        Object value = evaluation.runScoped("key", Object::new);
        Map<String, Object> output = new HashMap<>();

        ActionContext action = LanguageTestContexts.actionInRun(evaluation, output);
        ActionContext next = LanguageTestContexts.actionInRun(action, output);
        ActionContext otherRun = LanguageTestContexts.actionInRun(LanguageTestContexts.evaluation(Map.of("x", 1)),
                output);

        assertSame(value, action.runScoped("key", Object::new));
        assertSame(value, next.runScoped("key", Object::new));
        assertNotSame(value, otherRun.runScoped("key", Object::new));
        assertEquals("kept by the action", action.runScoped("action key", () -> "kept by the action"));
        assertEquals("kept by the action", evaluation.runScoped("action key", () -> "not made"));
        assertEquals(Map.of("x", 1), action.facts());
        assertSame(output, action.output());
        assertEquals(deadline, action.deadline());
        assertNull(otherRun.deadline());
        assertNotEquals(action, next);
        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> action.facts().put("y", 2));
        assertTrue(ex.getMessage().startsWith("The facts passed to an action are read-only; 'y'"), ex.getMessage());
    }

    /** A value a test keeps for a run, which records its close() and then throws what it was given, if anything. */
    private record Closeable(String name, List<String> closed, Throwable failure) implements AutoCloseable {

        Closeable(String name, List<String> closed) {
            this(name, closed, null);
        }

        @Override
        public void close() {
            closed.add(name);
            if (failure != null) {
                LanguageTestContextsTest.<RuntimeException>sneakyThrow(failure);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable thrown) throws T {
        throw (T) thrown;
    }

    @Test
    @DisplayName("ending a run closes its closing values in reverse order, once, and then refuses new ones")
    void endRunClosesTheRunsValues() throws Exception {
        List<String> closed = new ArrayList<>();
        EvaluationContext evaluation = LanguageTestContexts.evaluation(Map.of());
        ActionContext action = LanguageTestContexts.actionInRun(evaluation, new HashMap<>());
        Object plain = evaluation.runScoped("plain", () -> new Closeable("plain", closed));
        Closeable first = evaluation.runScopedClosing("first", () -> new Closeable("first", closed));
        action.runScopedClosing("second", () -> new Closeable("second", closed));

        LanguageTestContexts.endRun(action);
        LanguageTestContexts.endRun(evaluation);

        assertEquals(List.of("second", "first"), closed);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> evaluation.runScopedClosing("first", () -> first));
        assertEquals("runScopedClosing was called for a key (java.lang.String) after the run ended, when its value"
                + " would never be closed", ex.getMessage());
        assertSame(plain, action.runScoped("plain", Object::new));
        LanguageTestContexts.endRun(LanguageTestContexts.evaluation(Map.of()));
    }

    @Test
    @DisplayName("ending a run closes every value, and throws what the first close() threw, with the others suppressed")
    void endRunThrowsTheFirstFailure() {
        List<String> closed = new ArrayList<>();
        EvaluationContext evaluation = LanguageTestContexts.evaluation(Map.of());
        IOException made = new IOException("made first, closed last");
        IllegalStateException last = new IllegalStateException("made last, closed first");
        evaluation.runScopedClosing("a", () -> new Closeable("a", closed, made));
        evaluation.runScopedClosing("b", () -> new Closeable("b", closed));
        evaluation.runScopedClosing("c", () -> new Closeable("c", closed, last));

        Exception thrown = assertThrows(Exception.class, () -> LanguageTestContexts.endRun(evaluation));

        assertSame(last, thrown);
        assertArrayEquals(new Throwable[] {made}, thrown.getSuppressed());
        assertEquals(List.of("c", "b", "a"), closed);
    }

    @Test
    @DisplayName("ending a run throws what close() threw as it is, an Error or a plain Throwable, and only once")
    void endRunThrowsAnyThrowableAsItIs() {
        List<String> closed = new ArrayList<>();
        EvaluationContext evaluation = LanguageTestContexts.evaluation(Map.of());
        OutOfMemoryError error = new OutOfMemoryError("thrown by both");
        evaluation.runScopedClosing("a", () -> new Closeable("a", closed, error));
        evaluation.runScopedClosing("b", () -> new Closeable("b", closed, error));
        Throwable plain = new Throwable("neither an Exception nor an Error");
        EvaluationContext other = LanguageTestContexts.evaluation(Map.of());
        other.runScopedClosing("a", () -> new Closeable("plain", closed, plain));

        Throwable thrownError = assertThrows(Throwable.class, () -> LanguageTestContexts.endRun(evaluation));
        Throwable thrownPlain = assertThrows(Throwable.class, () -> LanguageTestContexts.endRun(other));

        assertSame(error, thrownError);
        assertEquals(0, error.getSuppressed().length);
        assertSame(plain, thrownPlain);
        assertEquals(List.of("b", "a", "plain"), closed);
    }

    @Test
    @DisplayName("ending a run keeps each failure on the first once, and none that would make a loop of causes")
    void endRunKeepsEachFailureOnce() {
        List<String> closed = new ArrayList<>();
        EvaluationContext evaluation = LanguageTestContexts.evaluation(Map.of());
        IllegalStateException first = new IllegalStateException("closed first");
        IllegalStateException wrapping = new IllegalStateException("wraps the first", first);
        IllegalStateException other = new IllegalStateException("another");
        evaluation.runScopedClosing("a", () -> new Closeable("a", closed, other));
        evaluation.runScopedClosing("b", () -> new Closeable("b", closed, other));
        evaluation.runScopedClosing("c", () -> new Closeable("c", closed, wrapping));
        evaluation.runScopedClosing("d", () -> new Closeable("d", closed, first));

        Exception thrown = assertThrows(Exception.class, () -> LanguageTestContexts.endRun(evaluation));

        assertSame(first, thrown);
        assertArrayEquals(new Throwable[] {other}, first.getSuppressed());
        assertEquals(List.of("d", "c", "b", "a"), closed);
    }

    private static void assertNullMessage(String expected, Executable creation) {
        assertEquals(expected, assertThrows(NullPointerException.class, creation).getMessage());
    }

    @Test
    @DisplayName("a null argument is rejected with a message that names it")
    void nullArguments() {
        ClassLoader loader = getClass().getClassLoader();

        assertAll(
                () -> assertNullMessage("facts must not be null", () -> LanguageTestContexts.evaluation(null)),
                () -> assertNullMessage("facts must not be null",
                        () -> LanguageTestContexts.action(null, new HashMap<>())),
                () -> assertNullMessage("sameRun must not be null",
                        () -> LanguageTestContexts.actionInRun(null, new HashMap<>())),
                () -> assertNullMessage("output must not be null",
                        () -> LanguageTestContexts.actionInRun(LanguageTestContexts.evaluation(Map.of()), null)),
                () -> assertNullMessage("output must not be null", () -> LanguageTestContexts.action(Map.of(), null)),
                () -> assertNullMessage("context must not be null", () -> LanguageTestContexts.endRun(null)),
                () -> assertNullMessage("packageImports must not be null",
                        () -> LanguageTestContexts.compile(null, Set.of(), loader)),
                () -> assertNullMessage("classImports must not be null",
                        () -> LanguageTestContexts.compile(Set.of(), null, loader)),
                () -> assertNullMessage("classLoader must not be null",
                        () -> LanguageTestContexts.compile(Set.of(), Set.of(), null)),
                () -> assertNullMessage("outputType must not be null",
                        () -> LanguageTestContexts.compile(Set.of(), Set.of(), loader, null, Map.of())),
                () -> assertNullMessage("options must not be null",
                        () -> LanguageTestContexts.compile(Set.of(), Set.of(), loader, Object.class, null)),
                () -> assertNullMessage("declaredFacts must not be null", () -> LanguageTestContexts.compile(
                        Set.of(), Set.of(), loader, Object.class, Map.of(), null, false)));
    }

    @Test
    @DisplayName("a null import or option is rejected with a message that names where it was")
    void nullElements() {
        ClassLoader loader = getClass().getClassLoader();
        Map<String, String> nullOptionName = new HashMap<>();
        nullOptionName.put(null, "on");
        Map<String, String> nullOptionValue = new HashMap<>();
        nullOptionValue.put("strict", null);

        assertAll(
                () -> assertNullMessage("packageImports must not contain null", () -> LanguageTestContexts.compile(
                        new HashSet<>(Arrays.asList("java.util", null)), Set.of(), loader)),
                () -> assertNullMessage("classImports must not contain null", () -> LanguageTestContexts.compile(
                        Set.of(), new HashSet<>(Arrays.asList(List.class, null)), loader)),
                () -> assertNullMessage("options must not contain null",
                        () -> LanguageTestContexts.compile(Set.of(), Set.of(), loader, Object.class, nullOptionName)),
                () -> assertNullMessage("options must not contain null",
                        () -> LanguageTestContexts.compile(Set.of(), Set.of(), loader, Object.class, nullOptionValue)));
    }

    @Test
    @DisplayName("a null declared fact name or type keeps its existing message, the one an engine's builder gives")
    void declaredFactsNameAndType() {
        ClassLoader loader = getClass().getClassLoader();
        Map<String, Class<?>> nullFactName = new HashMap<>();
        nullFactName.put(null, Integer.class);
        Map<String, Class<?>> nullFactType = new HashMap<>();
        nullFactType.put("x", null);

        assertAll(
                () -> assertNullMessage("name must not be null", () -> LanguageTestContexts.compile(
                        Set.of(), Set.of(), loader, Object.class, Map.of(), nullFactName, false)),
                () -> assertNullMessage("type must not be null", () -> LanguageTestContexts.compile(
                        Set.of(), Set.of(), loader, Object.class, Map.of(), nullFactType, false)));
    }

    @Test
    @DisplayName("a compile context keeps a copy of the language's own imports, as written, in order, duplicates kept")
    void compileWithLanguageImports() {
        ClassLoader loader = getClass().getClassLoader();
        List<String> names = new ArrayList<>(List.of("lodash/fp", "./rules/util.js", "@acme/pricing", "lodash/fp"));

        CompileContext context = LanguageTestContexts.compile(Set.of("java.util"), Set.of(), loader, Object.class,
                Map.of(), Map.of(), false, names);
        names.clear();

        assertEquals(List.of("lodash/fp", "./rules/util.js", "@acme/pricing", "lodash/fp"), context.languageImports());
        assertEquals(Set.of("java.util"), context.packageImports());
        assertThrows(UnsupportedOperationException.class, () -> context.languageImports().add("os"));
        assertEquals(List.of(), LanguageTestContexts.compile().languageImports());
        assertEquals(List.of(), LanguageTestContexts.compile(Set.of(), Set.of(), loader, Object.class, Map.of(),
                Map.of(), false).languageImports());
    }

    @Test
    @DisplayName("a null language import, or one of more than 1,000 characters, is rejected as an engine rejects it")
    void languageImportsChecked() {
        ClassLoader loader = getClass().getClassLoader();
        String longest = "m".repeat(1000);

        assertAll(
                () -> assertNullMessage("languageImports must not be null", () -> LanguageTestContexts.compile(
                        Set.of(), Set.of(), loader, Object.class, Map.of(), Map.of(), false, null)),
                () -> assertNullMessage("languageImports must not contain null", () -> LanguageTestContexts.compile(
                        Set.of(), Set.of(), loader, Object.class, Map.of(), Map.of(), false,
                        Arrays.asList("lodash", null))),
                () -> assertEquals("Can't import '" + "m".repeat(200) + "... (801 more characters)': it has 1001 "
                        + "characters, and an import may have at most 1000",
                        assertThrows(IllegalArgumentException.class, () -> LanguageTestContexts.compile(Set.of(),
                                Set.of(), loader, Object.class, Map.of(), Map.of(), false, List.of(longest + "/")))
                                .getMessage()),
                () -> assertEquals(List.of(longest), LanguageTestContexts.compile(Set.of(), Set.of(), loader,
                        Object.class, Map.of(), Map.of(), false, List.of(longest)).languageImports()));
    }

    @Test
    @DisplayName("a blank declared fact name is rejected, as an engine's builder rejects it")
    void blankDeclaredFactName() {
        ClassLoader loader = getClass().getClassLoader();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> LanguageTestContexts.compile(
                Set.of(), Set.of(), loader, Object.class, Map.of(), Map.of(" ", Integer.class), false));

        assertEquals("fact name must not be blank", ex.getMessage());
    }

    @Test
    @DisplayName("a language's compiled condition and action can be tested without an engine")
    void unitTestLanguage() throws Exception {
        ExpressionCompiler compiler = new ToyExpressionLanguage().newCompiler(LanguageTestContexts.compile());
        CompiledCondition condition =
                compiler.compileCondition(new Expression("r", ExpressionKind.CONDITION, "x == 1"));
        CompiledAction action = compiler.compileAction(new Expression("r", ExpressionKind.ACTION, "put seen x"));
        Session session = compiler.newSession();
        Map<String, Object> output = new HashMap<>();

        assertEquals(true, condition.evaluate(LanguageTestContexts.evaluation(Map.of("x", 1)), session));
        assertEquals(false, condition.evaluate(LanguageTestContexts.evaluation(Map.of("x", 2)), session));
        action.execute(LanguageTestContexts.action(Map.of("x", 1), output), session);
        assertEquals(Map.of("seen", 1), output);
    }
}
