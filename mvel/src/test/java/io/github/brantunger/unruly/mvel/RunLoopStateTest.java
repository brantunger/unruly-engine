package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.test.LanguageTestContexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mvel2.MVEL;
import org.mvel2.ParserConfiguration;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #857: how a run's parser configuration limits each run, and how many times legitimate runs
 * ask it for the class loader. The runs of {@link RunTimeLoopTest} go round in the loop itself.
 */
@DisplayName("a run's parser configuration limits each run on its own")
class RunLoopStateTest {

    private static final Imports IMPORTS = new Imports(Set.of(), Set.of(), RunLoopStateTest.class.getClassLoader());

    @Test
    @DisplayName("a run configuration answers as many calls as each run allows, then throws, until the next run")
    void runLimitThrowsUntilTheNextRun() {
        ParserConfiguration configuration = IMPORTS.newRunConfiguration(3, 3);
        // While the expression compiles, before its first run, MVEL may ask any number of times.
        for (int call = 0; call < 100; call++) {
            assertSame(IMPORTS.classLoader(), configuration.getClassLoader());
        }

        Imports.startRun(configuration);
        for (int call = 0; call < 3; call++) {
            assertSame(IMPORTS.classLoader(), configuration.getClassLoader());
        }
        assertNull(Imports.runLoop(configuration));
        Imports.RunLoop loop = assertThrows(Imports.RunLoop.class, configuration::getClassLoader);

        assertSame(loop, assertThrows(Imports.RunLoop.class, configuration::getClassLoader));
        assertSame(loop, Imports.runLoop(configuration));
        assertNull(Imports.analysisLoop(configuration));

        // The next run starts afresh: it may not reach the text that loops.
        Imports.startRun(configuration);
        assertNull(Imports.runLoop(configuration));
        for (int call = 0; call < 3; call++) {
            assertSame(IMPORTS.classLoader(), configuration.getClassLoader());
        }
        assertNotSame(loop, assertThrows(Imports.RunLoop.class, configuration::getClassLoader));
    }

    @Test
    @DisplayName("each run has the whole limit")
    void eachRunHasTheLimit() {
        ParserConfiguration configuration = IMPORTS.newRunConfiguration(3, 3);

        for (int run = 0; run < 10; run++) {
            Imports.startRun(configuration);
            for (int call = 0; call < 3; call++) {
                assertSame(IMPORTS.classLoader(), configuration.getClassLoader());
            }
        }
        assertNull(Imports.runLoop(configuration));
    }

    @Test
    @DisplayName("each run of a compiled copy may ask as many times in all as MVEL's analysis of the expression may, "
            + "and as many from one place")
    void limitGrowsWithTheLength() {
        // The first run of a copy compiles each argument that is a chain of properties, asking about twice for each
        // part, from two places, so a long expression may ask more than a fixed limit would allow.
        String text = "/*" + " ".repeat(50_000) + "*/ x == 1";
        long perSite = MvelAnalysis.classLoaderCallsPerSite(text.length());
        ParserConfiguration configuration = MvelExpression.compile(text, IMPORTS).newCompiled().configuration();

        assertEquals(MvelAnalysis.classLoaderCallLimit(text.length()),
                MvelExpression.classLoaderCallsPerRun(text.length()));
        Imports.startRun(configuration);
        CallSitesTest.askedUntilStopped(configuration);
        CallSitesTest.assertStoppedAtTheLimitForOnePlace(Imports.classLoaderCalls(configuration), perSite);
    }

    @Test
    @DisplayName("a configuration for compiling only throws what MVEL's analysis went round in, whatever is called")
    void analysisConfigurationThrowsAnalysisLoop() {
        ParserConfiguration configuration = IMPORTS.newConfiguration();
        Imports.limitClassLoaderCalls(configuration, 0, 0);

        Imports.AnalysisLoop loop = assertThrows(Imports.AnalysisLoop.class, configuration::getClassLoader);
        assertSame(loop, Imports.analysisLoop(configuration));
        assertNull(Imports.runLoop(configuration));
    }

    /** Imports of their own, so no other test's names found not to be classes change which calls MVEL makes. */
    private static Imports ownImports() {
        return new Imports(Set.of(), Set.of(), RunLoopStateTest.class.getClassLoader());
    }

    @Test
    @DisplayName("a loop MVEL wraps as it compiles part of a condition while it runs still fails as a loop")
    void wrappedLoopInCondition() {
        // No call is left when MVEL compiles the argument as the expression first runs, and it wraps what is thrown
        // in a CompileException of its own.
        String text = "s.substring(java.lang.Math.abs(-1))";
        MvelExpression.Copy copy = MvelExpression.compile(text, ownImports(), 0).newCompiled();
        Imports.startRun(copy.configuration());
        RuntimeException wrapped = assertThrows(RuntimeException.class,
                () -> MVEL.executeExpression(copy.expression(), (Object) null, new HashMap<>(Map.of("s", "abc"))));
        // A named guard: MVEL wraps it here.
        assertInstanceOf(Imports.RunLoop.class, wrapped.getCause());

        MvelExpression expression = MvelExpression.compile(text, ownImports(), 0);
        Imports.RunLoop loop = assertThrows(Imports.RunLoop.class, () -> expression.evaluate(
                LanguageTestContexts.evaluation(Map.of("s", "abc")), new MvelSession()));
        // What MVEL threw in its place wraps the loop, so it isn't kept, which would make a chain that loops back.
        assertEquals(0, loop.getSuppressed().length);
    }

    @Test
    @DisplayName("a loop MVEL wraps as it compiles part of an action while it runs still fails as a loop")
    void wrappedLoopInAction() {
        MvelExpression expression = MvelExpression.compile("x = s.substring(java.lang.Math.abs(-1))", ownImports(), 0);

        assertThrows(Imports.RunLoop.class, () -> expression.execute(
                LanguageTestContexts.action(Map.of("s", "abc"), new HashMap<String, Object>()), new MvelSession()));
    }

    /** Imports of java.util and 200 other packages, with a class loader that isn't one of the JDK's own. */
    private static Imports manyPackages() {
        Set<String> packages = new HashSet<>(Set.of("java.util"));
        IntStream.range(0, 200).forEach(index -> packages.add("pkg" + index));
        return new Imports(packages, Set.of(), new ClassLoader(RunLoopStateTest.class.getClassLoader()) {
        });
    }

    /** Runs a copy that may ask a number of times each run, 60 times, and tells whether none went round in a loop. */
    private static void runsWithin(Imports imports, String text, long calls, Map<String, Object> variables) {
        MvelExpression.Copy copy = MvelExpression.newCopy(text, imports, calls);
        for (int run = 0; run < 60; run++) {
            Imports.startRun(copy.configuration());
            MVEL.executeExpression(copy.expression(), new HashMap<>(variables));
            assertNull(Imports.runLoop(copy.configuration()), text);
        }
    }

    @Test
    @DisplayName("ordinary runs through the engine's own configuration ask for the class loader a few times")
    void engineConfigurationAsksAFewTimes() {
        // A named guard for how few times ordinary runs ask: at most 6 in a run of these, and a function that calls
        // itself about once more for each call still under way. The long expressions RunTimeLoopTest runs ask more.
        Imports imports = manyPackages();
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            values.add(i % 3 == 0 ? (Object) i : i % 3 == 1 ? (Object) ("s" + i) : (Object) List.of(i));
        }
        for (String text : List.of(
                "foreach (e : l) { java.lang.String.valueOf(e.hashCode()) }",
                "foreach (e : l) { e.toString().length() }",
                "foreach (e : l) { java.util.Objects.toString(e) }",
                "foreach (e : l) { new java.lang.StringBuilder(e.toString()).length() }",
                "foreach (e : l) { new ArrayList().size() }",
                "c = 0; foreach (e : l) { if (e instanceof java.lang.String) { c += e.length() } else { c += 1 } }; c",
                "t = 0; for (int i = 0; i < 10000; i++) { t += java.lang.Math.abs(i) }; t",
                "Collections.emptyList().size() + java.lang.Math.abs(1)")) {
            runsWithin(imports, text, 20, Map.of("l", values));
        }
        runsWithin(imports, "def f(v) { v == 0 ? 0 : java.lang.Math.abs(v) + f(v - 1) }; f(50)", 100, Map.of());
    }

    /** A map whose get asks a configuration for the class loader until it runs out, then throws something else. */
    private static Map<String, Object> exhausting(ParserConfiguration configuration, Throwable thrown) {
        return new AbstractMap<>() {
            @Override
            public Set<Entry<String, Object>> entrySet() {
                return Set.of();
            }

            // MVEL reads a key of a map that has it.
            @Override
            public boolean containsKey(Object key) {
                return true;
            }

            @Override
            public Object get(Object key) {
                while (Imports.runLoop(configuration) == null) {
                    try {
                        configuration.getClassLoader();
                    } catch (Imports.RunLoop loop) {
                        // Kept by the configuration.
                    }
                }
                if (thrown instanceof Error error) {
                    throw error;
                }
                throw (RuntimeException) thrown;
            }
        };
    }

    @Test
    @DisplayName("a run that went round in a loop and then failed otherwise fails as a loop, keeping the other failure")
    void otherFailureAfterLoopIsKept() {
        MvelExpression expression = MvelExpression.compile("m.k", ownImports());
        MvelSession session = new MvelSession();
        IllegalStateException other = new IllegalStateException("other");
        Map<String, Object> m = exhausting(session.compiled(expression).configuration(), other);

        Imports.RunLoop loop = assertThrows(Imports.RunLoop.class,
                () -> expression.evaluate(LanguageTestContexts.evaluation(Map.of("m", m)), session));
        // MVEL wraps what the map threw in an exception of its own, which doesn't hold the loop, so it is kept.
        assertEquals(1, loop.getSuppressed().length);
        assertTrue(ExceptionReads.causeChain(loop.getSuppressed()[0]).contains(other));
    }

    @Test
    @DisplayName("a fatal error after a loop is thrown as it is")
    void fatalErrorAfterLoopIsThrown() {
        MvelExpression expression = MvelExpression.compile("m.k", ownImports());
        MvelSession session = new MvelSession();
        ParserConfiguration configuration = session.compiled(expression).configuration();
        OutOfMemoryError fatal = new OutOfMemoryError("fatal");
        Map<String, Object> m = exhausting(configuration, fatal);

        assertSame(fatal, assertThrows(OutOfMemoryError.class,
                () -> expression.evaluate(LanguageTestContexts.evaluation(Map.of("m", m)), session)));
        assertEquals(0, Imports.runLoop(configuration).getSuppressed().length);
    }

    @Test
    @DisplayName("the long expressions RunTimeLoopTest runs ask more than 10,000 times in a copy's first run")
    void longExpressionsAskMoreThanTenThousand() {
        // So a limit of 10,000, whatever the expression's length, would fail them.
        Map<String, Object> m = new HashMap<>();
        m.put("a", m);
        Map<String, Object> facts = Map.of("s", "abc", "m", m);
        StringJoiner chains = new StringJoiner(" || ");
        for (int i = 0; i < 60; i++) {
            chains.add("s.equals(m" + ".a".repeat(90) + ")");
        }
        StringJoiner calls = new StringJoiner(" || ");
        for (int i = 0; i < 1_000; i++) {
            calls.add("s.equals(m.a.a.a.a.a)");
        }
        Imports notJdk = new Imports(Set.of("java.util"), Set.of(), new ClassLoader(RunLoopStateTest.class
                .getClassLoader()) {
        });
        for (MvelExpression expression : List.of(
                MvelExpression.compile("s.equals(m" + ".a".repeat(5_100) + ")", ownImports(), 10_000),
                MvelExpression.compile(chains.toString(), ownImports(), 10_000),
                MvelExpression.compile(calls.toString(), notJdk, 10_000))) {
            assertThrows(Imports.RunLoop.class,
                    () -> expression.evaluate(LanguageTestContexts.evaluation(facts), new MvelSession()));
        }
    }
}
