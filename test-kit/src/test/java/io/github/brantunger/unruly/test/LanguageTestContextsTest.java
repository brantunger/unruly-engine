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
                () -> assertNullMessage("output must not be null", () -> LanguageTestContexts.action(Map.of(), null)),
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
