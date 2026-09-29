package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.ChildJvm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mvel2.CompileException;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * While MVEL compiles an expression, the thread's context class loader is the rule list's, so MVEL asks no other class
 * loader for a name the rule list's refuses, such as {@code java.lang.Object$p1} for {@code f.p1}, which one of the
 * JDK's class loaders kept a lock object for (#807). The thread's own is restored after.
 */
@DisplayName("MVEL compiles with the rule list's class loader as the thread's context class loader (#807)")
class CompileContextClassLoaderTest {

    private static final String OBJECT_NESTED = Object.class.getName() + "$";

    private static Imports imports() {
        return new Imports(Set.of(), Set.of(), ClassLoader.getSystemClassLoader());
    }

    private static <T> T withContextLoader(ClassLoader loader, Supplier<T> call) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return call.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static List<String> nestedInObject(RecordingClassLoader loader) {
        return loader.loadedClasses.stream().filter(name -> name.startsWith(OBJECT_NESTED)).toList();
    }

    @Test
    @DisplayName("a property read through a value typed as Object isn't asked of the thread's context class loader")
    void compileAsksContextLoaderNothing() {
        RecordingClassLoader context = new RecordingClassLoader();

        withContextLoader(context, () -> MvelExpression.compile("f.p1 == 1", imports()));

        assertEquals(List.of(), nestedInObject(context));
    }

    @Test
    @DisplayName("nor when a session compiles the expression again")
    void newCompiledAsksContextLoaderNothing() {
        MvelExpression expression = MvelExpression.compile("f.p1 == 1", imports());
        expression.newCompiled();
        RecordingClassLoader context = new RecordingClassLoader();

        assertNotNull(withContextLoader(context, expression::newCompiled));
        assertEquals(List.of(), nestedInObject(context));
    }

    // A named guard: the thread's own context class loader is back once MVEL returns or throws.
    @Test
    @DisplayName("the thread's context class loader is restored after a compilation, whether it fails or not")
    void contextLoaderRestored() {
        RecordingClassLoader context = new RecordingClassLoader();
        MvelExpression expression = withContextLoader(context, () -> {
            MvelExpression compiled = MvelExpression.compile("f.p1 == 1", imports());
            assertSame(context, Thread.currentThread().getContextClassLoader());
            assertThrows(CompileException.class, () -> MvelExpression.compile("x == == 1", imports()));
            assertSame(context, Thread.currentThread().getContextClassLoader());
            return compiled;
        });
        expression.newCompiled();

        withContextLoader(context, () -> {
            assertNotNull(expression.newCompiled());
            assertSame(context, Thread.currentThread().getContextClassLoader());
            return null;
        });
    }

    // MVEL's optimizer is set up before the first compilation, as the rule's inline list would set it up inside it,
    // and with MVEL's own class loader as the context class loader, not the plug-in's the rules are loaded with.
    @Test
    @DisplayName("MVEL's JVM-wide class loader is made from MVEL's own, not a rule list's or the loading thread's")
    void optimizerLoaderIsMvelLoader(@TempDir Path dir) throws Exception {
        String output = ChildJvm.run(dir, CompileContextClassLoaderScenario.class);

        assertTrue(output.contains(CompileContextClassLoaderScenario.PARENT
                + CompileContextClassLoaderScenario.MVEL_OWN), "scenario output:\n" + output);
    }

    // A named guard: MVEL calls no method of the rule's while it compiles, so none sees the rule list's class loader
    // as the thread's context class loader. A call with literal arguments isn't folded into a constant.
    @Test
    @DisplayName("a static method a rule calls isn't called while the rule compiles, with literal arguments or not")
    void staticMethodNotCalledWhileCompiling() {
        Imports imports = new Imports(Set.of(), Set.of(Probe.class), ClassLoader.getSystemClassLoader());
        Probe.CALLS.clear();

        for (String source : List.of("Probe.loader() == 1", "Probe.loader(1) == 1", "1 + Probe.loader(1)",
                "x = Probe.loader(1); x", "new java.util.ArrayList(Probe.loader(1))")) {
            MvelExpression.compile(source, imports).newCompiled();
        }

        assertEquals(List.of(), Probe.CALLS);
    }

    // MVEL initialises a class a rule names in full, while it compiles, so the class's static initialiser sees the
    // rule list's class loader as the thread's context class loader, not the thread's own.
    @Test
    @DisplayName("a class a rule names in full is initialised with the rule list's class loader as the context one")
    void classInitialisedWithRuleListLoader() {
        Imports imports = imports();

        withContextLoader(new RecordingClassLoader(), () -> MvelExpression.compile(
                "io.github.brantunger.unruly.mvel.ClassInitProbe.VALUE == 1", imports));

        assertSame(imports.classLoader(), ClassInitProbe.SEEN);
    }

    /** A class whose static method records the context class loader of each call. */
    public static final class Probe {

        static final List<ClassLoader> CALLS = new CopyOnWriteArrayList<>();

        private Probe() {
        }

        public static int loader() {
            return loader(1);
        }

        public static int loader(int value) {
            CALLS.add(Thread.currentThread().getContextClassLoader());
            return value;
        }
    }
}
