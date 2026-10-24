package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.brantunger.unruly.TestSupport.withContextClassLoader;
import static org.junit.jupiter.api.Assertions.*;

/**
 * An error the application's class loader throws for a name is read alike by the fact-name check, the lookup of a
 * class nested in an imported one and the scan for the classes an expression names: any {@link Error} means the
 * name is no class, as MVEL's own lookup of a name in an imported package reads it, bar a
 * {@link VirtualMachineError}, which is thrown (#1255).
 */
@DisplayName("the lookups of a name as a class read an error from the class loader alike (#1255)")
class ClassLookupErrorTest {

    /** An error that is neither a {@link LinkageError} nor a {@link VirtualMachineError}. */
    private static final class OtherError extends Error {
        private static final long serialVersionUID = 1L;

        OtherError(String message) {
            super(message);
        }
    }

    /**
     * A class loader of the application's own that serves every class and class file the test's loader does, but
     * throws an error for one name.
     */
    private static final class ThrowingLoader extends ClassLoader {

        private final String name;
        private final Error error;
        private volatile int thrown;

        ThrowingLoader(String name, Error error) {
            super(ClassLookupErrorTest.class.getClassLoader());
            this.name = name;
            this.error = error;
        }

        @Override
        protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
            if (className.equals(name)) {
                thrown++;
                throw error;
            }
            return super.loadClass(className, resolve);
        }
    }

    // MVEL looks up a def function's arguments only when the action runs (ClassNameRootFactTest), so at load only the
    // scan for the classes an expression names asks for boom.Target.
    private static final String DEF_ARGUMENT = "def f(x) { x }; f(boom.Target.X); output.put('k', 1)";

    // MVEL asks for AbstractMap.SimpleEntry among the imports, so the lookup of a nested class loads it.
    private static final String NEW_NESTED = "output.put('k', new AbstractMap.SimpleEntry('a', 1).getValue())";

    private static RulesEngine<Map<String, Object>> loaded(ClassLoader loader, String action, String... imports) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .imports(imports).build();
        withContextClassLoader(loader, () -> engine.load(List.of(
                Rule.builder().ruleName("r").condition("true").action(action).build())));
        return engine;
    }

    // MVEL's message, without what follows to show where in the text it failed, after a line break the engine's
    // message writes as a backslash and an n.
    private static String firstLine(Throwable thrown) {
        String message = thrown.getMessage();
        int end = message.indexOf("\\n");
        return end < 0 ? message : message.substring(0, end);
    }

    @Test
    @DisplayName("a nested class the loader throws another error for isn't one")
    void nestedClassOtherErrorIsNoClass() {
        ThrowingLoader loader = new ThrowingLoader("java.util.Map$Entry", new OtherError("for Map$Entry"));
        Imports imports = new Imports(Set.of(), Set.of(Map.class), loader);

        assertFalse(imports.newConfiguration().hasImport("Map.Entry"));
        assertEquals(1, loader.thrown, "the class loader was asked for the nested class");
    }

    @Test
    @DisplayName("a nested class the loader throws an error for that means the JVM itself is failing passes it on")
    void nestedClassVirtualMachineErrorPassedOn() {
        StackOverflowError overflow = new StackOverflowError("simulated");
        ThrowingLoader loader = new ThrowingLoader("java.util.Map$Entry", overflow);
        Imports imports = new Imports(Set.of(), Set.of(Map.class), loader);

        assertSame(overflow, assertThrows(StackOverflowError.class,
                () -> imports.newConfiguration().hasImport("Map.Entry")));
    }

    @Test
    @DisplayName("a class a def function's argument names, which the loader throws another error for, loads, and fails"
            + " when it runs")
    void defArgumentOtherErrorFailsAtRun() {
        ThrowingLoader loader = new ThrowingLoader("boom.Target", new OtherError("for boom.Target"));

        RulesEngine<Map<String, Object>> engine = loaded(loader, DEF_ARGUMENT);
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> withContextClassLoader(loader, () -> engine.run(new FactMap<>())));

        assertEquals("Failed to execute action for rule 'r': [Error: for boom.Target]", firstLine(ex));
        assertTrue(ex.getMessage().endsWith("[Line: 1, Column: 19]"), ex.getMessage());
    }

    @Test
    @DisplayName("a class a def function's argument names, which the loader throws an error for that means the JVM"
            + " itself is failing, fails the load")
    void defArgumentVirtualMachineErrorFailsTheLoad() {
        ThrowingLoader loader = new ThrowingLoader("boom.Target", new StackOverflowError("simulated"));

        RuleCompilationException ex = assertThrows(RuleCompilationException.class,
                () -> loaded(loader, DEF_ARGUMENT));

        assertEquals("Action for rule 'r' failed to compile: the expression is too long or too deeply nested to"
                + " compile", ex.getMessage());
    }

    @Test
    @DisplayName("a nested class created with new, which the loader throws another error for, loads, and fails when it"
            + " runs, as a class that isn't there")
    void newNestedOtherErrorFailsAtRun() {
        ThrowingLoader loader = new ThrowingLoader("java.util.AbstractMap$SimpleEntry",
                new OtherError("for SimpleEntry"));

        RulesEngine<Map<String, Object>> engine = loaded(loader, NEW_NESTED, "java.util.AbstractMap");
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> withContextClassLoader(loader, () -> engine.run(new FactMap<>())));

        assertEquals("Failed to execute action for rule 'r': [Error: could not resolve class: AbstractMap.SimpleEntry]",
                firstLine(ex));
        assertTrue(ex.getMessage().endsWith("[Line: 1, Column: 21]"), ex.getMessage());
    }
}
