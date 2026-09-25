package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mvel2.MVEL;
import org.mvel2.asm.ClassWriter;
import org.mvel2.asm.Opcodes;
import org.mvel2.compiler.Accessor;
import org.mvel2.optimizers.OptimizerFactory;
import org.mvel2.optimizers.impl.asm.ASMAccessorOptimizer;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Once MVEL's JIT compiles an accessor, it defines the accessor's class in a class loader whose parent is the
 * {@link ExactNameClassLoader} the rule list was compiled with, and the JVM links it against MVEL's classes through
 * that loader's two-argument {@code loadClass}. A child class loader of an {@code ExactNameClassLoader} makes the same
 * lookups here, so the unit tests need no JIT. The context class loader of the thread that calls {@code load()} may
 * not see MVEL, as with a plugin's {@code URLClassLoader} whose parent is the platform class loader, or may see a copy
 * of its own (#612).
 */
@DisplayName("code MVEL's JIT generates links against the engine's MVEL, whatever class loader loaded the rules")
class JitClassLinkageTest {

    private static final String ACCESSOR = Accessor.class.getName();

    /** A class in MVEL's package that only {@link ApplicationWithMvelPackageClass} has. */
    private static final String ONLY_IN_APPLICATION = MVEL.class.getPackageName() + ".OnlyInApplication";

    /** The most runs {@link #runsPastJit} makes while it waits for MVEL's JIT to compile an accessor. */
    private static final int MAX_RUNS = 10_000;

    /**
     * MVEL keeps a JVM-wide class loader made from the context class loader of the thread that first sets up its
     * optimizer. Set up here, it's the class path's, as for every other test, and not one of the loaders below.
     */
    @BeforeAll
    static void initializeMvel() {
        OptimizerFactory.getDefaultAccessorCompiler();
    }

    /** Looks {@code name} up as the JVM does when it links a class defined in a child of {@code exactName}. */
    private static Class<?> linked(ExactNameClassLoader exactName, String name) throws ClassNotFoundException {
        return Class.forName(name, false, new ClassLoader(exactName) {
        });
    }

    /** A class loader that can't see the class path, so it sees neither MVEL nor this library. */
    private static ClassLoader classPathHidden() {
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
        };
    }

    /** The jar, or class directory, MVEL is loaded from. */
    private static URL mvelJar() {
        return MVEL.class.getProtectionDomain().getCodeSource().getLocation();
    }

    /** A class loader that sees nothing of the class path but a copy of MVEL's jar. */
    private static URLClassLoader mvelCopy() {
        return new URLClassLoader(new URL[] {mvelJar()}, ClassLoader.getPlatformClassLoader());
    }

    /** A class loader that can't see the class path, but defines a class in MVEL's package that MVEL hasn't got. */
    private static final class ApplicationWithMvelPackageClass extends ClassLoader {

        ApplicationWithMvelPackageClass() {
            super(ClassLoader.getPlatformClassLoader());
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.equals(ONLY_IN_APPLICATION)) {
                throw new ClassNotFoundException(name);
            }
            ClassWriter writer = new ClassWriter(0);
            writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null);
            writer.visitEnd();
            byte[] bytes = writer.toByteArray();
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /**
     * A context class loader for {@code load()} that notes whether the JVM asked it for a class while MVEL's JIT was
     * defining an accessor's class, which shows that the JIT compiled one with the rule list's class loader.
     */
    private static final class JitWatchingClassLoader extends URLClassLoader {

        volatile boolean jitLinked;

        JitWatchingClassLoader(URL... urls) {
            super(urls, ClassLoader.getPlatformClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!jitLinked && StackWalker.getInstance().walk(frames -> frames
                    .anyMatch(frame -> frame.getClassName().startsWith(ASMAccessorOptimizer.class.getName())))) {
                jitLinked = true;
            }
            return super.loadClass(name, resolve);
        }
    }

    @Test
    @DisplayName("an MVEL class is taken from MVEL's class loader without asking the application's")
    void mvelClassFromMvel() throws ClassNotFoundException {
        RecordingClassLoader application = new RecordingClassLoader();

        assertSame(Accessor.class, linked(new ExactNameClassLoader(application), ACCESSOR));
        assertFalse(application.loadedClasses.contains(ACCESSOR), application.loadedClasses::toString);
    }

    @Test
    @DisplayName("an MVEL class is found when the application's class loader can't see MVEL")
    void mvelClassWithoutMvel() throws ClassNotFoundException {
        assertSame(Accessor.class, linked(new ExactNameClassLoader(classPathHidden()), ACCESSOR));
    }

    @Test
    @DisplayName("an MVEL class is the engine's own when the application's class loader has a copy of MVEL")
    void mvelClassNotFromCopy() throws ClassNotFoundException, IOException {
        try (URLClassLoader copy = mvelCopy()) {
            assertNotSame(Accessor.class, copy.loadClass(ACCESSOR), "the copy must be a different class");

            assertSame(Accessor.class, linked(new ExactNameClassLoader(copy), ACCESSOR));
        }
    }

    @Test
    @DisplayName("a class in MVEL's package that MVEL hasn't got is the application's, if it has one")
    void mvelPackageClassFromApplication() throws ClassNotFoundException {
        ClassLoader application = new ApplicationWithMvelPackageClass();
        ExactNameClassLoader exactName = new ExactNameClassLoader(application);

        assertSame(application, linked(exactName, ONLY_IN_APPLICATION).getClassLoader());
        assertThrows(ClassNotFoundException.class,
                () -> linked(exactName, MVEL.class.getPackageName() + ".NowhereToBeFound"));
    }

    @Test
    @DisplayName("any other class, even one in a package whose name only starts like MVEL's, is the application's")
    void otherClassesFromApplication() throws ClassNotFoundException {
        RecordingClassLoader application = new RecordingClassLoader();
        ExactNameClassLoader exactName = new ExactNameClassLoader(application);

        assertSame(JitClassLinkageTest.class, linked(exactName, JitClassLinkageTest.class.getName()));
        assertThrows(ClassNotFoundException.class, () -> linked(exactName, "org.mvel2x.Missing"));
        assertTrue(application.loadedClasses.containsAll(List.of(JitClassLinkageTest.class.getName(),
                "org.mvel2x.Missing")), application.loadedClasses::toString);
    }

    /**
     * Loads a rule with one condition accessor and three action accessors with {@code contextClassLoader} as the
     * context class loader, then runs it with a fact of a JDK class, which every class loader here sees, until MVEL's
     * JIT has compiled an accessor, after 50 runs in 100 ms, and 100 runs more. Each accessor failed one run when it
     * did.
     */
    private static void runsPastJit(JitWatchingClassLoader contextClassLoader) {
        try (RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .build()) {
            Thread thread = Thread.currentThread();
            ClassLoader original = thread.getContextClassLoader();
            thread.setContextClassLoader(contextClassLoader);
            try {
                engine.load(List.of(Rule.builder().ruleName("r").condition("date.year > 1")
                        .action("output.put('year', date.year); output.put('month', date.monthValue);"
                                + " output.put('day', date.dayOfMonth)")
                        .build()));
            } finally {
                thread.setContextClassLoader(original);
            }

            FactMap<Object> facts = new FactMap<>(new Fact<>("date", LocalDate.of(2026, 9, 25)));
            List<String> failures = new ArrayList<>();
            int runs = 0;
            int jitRun = 0;
            while (runs < MAX_RUNS && (jitRun == 0 || runs < jitRun + 100)) {
                runs++;
                try {
                    assertEquals(Map.of("year", 2026, "month", 9, "day", 25), engine.run(facts));
                } catch (RuntimeException e) {
                    Throwable root = e;
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    failures.add("run " + runs + ": " + root);
                }
                if (jitRun == 0 && contextClassLoader.jitLinked) {
                    jitRun = runs;
                }
            }
            assertNotEquals(0, jitRun, "MVEL's JIT compiled no accessor in " + runs + " runs");
            assertEquals(List.of(), failures);
        }
    }

    @Test
    @DisplayName("rules loaded with a context class loader that can't see MVEL run past the JIT without failing")
    void runsPastJitWithoutMvel() throws IOException {
        try (JitWatchingClassLoader withoutMvel = new JitWatchingClassLoader()) {
            runsPastJit(withoutMvel);
        }
    }

    @Test
    @DisplayName("rules loaded with a context class loader that has a copy of MVEL run past the JIT without failing")
    void runsPastJitWithMvelCopy() throws IOException {
        try (JitWatchingClassLoader copy = new JitWatchingClassLoader(mvelJar())) {
            runsPastJit(copy);
        }
    }
}
