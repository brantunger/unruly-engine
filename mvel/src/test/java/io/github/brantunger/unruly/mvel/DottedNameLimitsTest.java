package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.InvalidExpressionException;
import io.github.brantunger.unruly.api.exception.RuleCompilationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mvel2.util.ParseTools;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.brantunger.unruly.JavaSources.assertCompiles;
import static io.github.brantunger.unruly.JavaSources.source;
import static io.github.brantunger.unruly.TestSupport.withContextClassLoader;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MVEL reads a dotted chain in a rule, such as {@code a.a.a == 1}, by looking it up as a class, as a nested class once
 * for each dot, and again for each shorter chain, so a chain of n parts cost lookups that grew with n squared: 39,798
 * for 100 parts. The rule list's class loader refuses a name of more than 81 dot-separated parts or 2,000 characters
 * before the application's class loader is asked, which MVEL reads as "not a class", so a chain costs lookups that
 * grow with n. A name with a {@code $} counts one more part. When MVEL's lookup of a nested class asks for one with
 * too many, it is refused with an exception that stops that lookup; anywhere else it is a missing class, so
 * {@code new}, a cast and a parenthesised path behave as they did before the bound. A property with many {@code $},
 * which MVEL looks up as a class nested in the fact's type, still loads and runs.
 * Without the bound the counting tests fail their bound on lookups, and the 2,000-part ones run for minutes.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("a long dotted name in a rule is refused before it is looked up as a class (#687)")
class DottedNameLimitsTest {

    /** The most parts a looked-up name may have, written out so these tests also run on a loader without the bound. */
    private static final int MAX_PARTS = 81;

    /** The most characters a looked-up name may have, written out as {@link #MAX_PARTS} is. */
    private static final int MAX_LENGTH = 2_000;

    /** How many lookups, per part of the chain, a chain may cost: a bound that grows with n, not n squared. */
    private static final int LOOKUPS_PER_PART = 10;

    /**
     * An application class loader that counts the names it is asked to load, and keeps the most parts and characters
     * any of them had. It is parallel-capable, as the JDK's application class loader is: such a loader keeps a lock
     * object for every name it is asked for, for as long as it lives. It keeps no name, so a test on a loader without
     * the bound, which asks it for millions, counts them without holding them.
     */
    private static final class CountingLoader extends ClassLoader {
        static {
            registerAsParallelCapable();
        }

        private final AtomicLong lookups = new AtomicLong();
        private final AtomicInteger mostParts = new AtomicInteger();
        private final AtomicInteger longest = new AtomicInteger();

        CountingLoader() {
            super(DottedNameLimitsTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            lookups.incrementAndGet();
            mostParts.accumulateAndGet(parts(name), Math::max);
            longest.accumulateAndGet(name.length(), Math::max);
            return super.loadClass(name, resolve);
        }
    }

    /** Counts the parts of a name as the bound does: one more than its dots, and one more again if it has a '$'. */
    private static int parts(String name) {
        return 1 + (int) name.chars().filter(c -> c == '.').count() + (name.indexOf('$') < 0 ? 0 : 1);
    }

    private static String chain(int parts) {
        return "a.".repeat(parts - 1) + "a";
    }

    /** A name of a class nested in one of {@code parts - 1} parts, {@code a.a...a$B}, as a rule may write one. */
    private static String nestedName(int parts) {
        return chain(parts - 1) + "$B";
    }

    /** A fact of maps nested {@code depth} deep, each with the next one as {@code a}, and 1 in the innermost. */
    private static Object nestedMaps(int depth) {
        Object value = 1;
        for (int i = 0; i < depth; i++) {
            Map<String, Object> map = new HashMap<>();
            map.put("a", value);
            value = map;
        }
        return value;
    }

    private static Rule rule(String condition, String action) {
        return Rule.builder().ruleName("r").condition(condition).action(action).build();
    }

    private static RulesEngine<Map<String, Object>> loaded(CountingLoader loader, Rule rule,
                                                           RuleListener... listeners) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>firstMatch(HashMap::new)
                .listeners(List.of(listeners)).build();
        // The engine takes its class loader for the rules' classes from the thread that loads them.
        withContextClassLoader(loader, () -> {
            engine.load(List.of(rule));
            return null;
        });
        return engine;
    }

    private static void assertBounded(CountingLoader loader, int parts) {
        assertAll(
                () -> assertTrue(loader.mostParts.get() <= MAX_PARTS, "a lookup of " + loader.mostParts.get()
                        + " parts reached the application's class loader"),
                () -> assertTrue(loader.longest.get() <= MAX_LENGTH, "a lookup of " + loader.longest.get()
                        + " characters reached the application's class loader"),
                () -> assertTrue(loader.lookups.get() <= (long) LOOKUPS_PER_PART * parts, loader.lookups.get()
                        + " lookups for a chain of " + parts + " parts"));
    }

    private static RuleCompilationException assertRejected(CountingLoader loader, String action) {
        return assertThrows(RuleCompilationException.class, () -> loaded(loader, rule("true", action)));
    }

    private static void assertIssue(RuleCompilationException ex, int line, int column, String description) {
        String where = line == 0 ? "" : " at line " + line + ", column " + column;
        assertAll(
                () -> assertEquals(List.of(new InvalidExpressionException.Issue(
                        InvalidExpressionException.Issue.Severity.ERROR, line, column, description)), ex.issues()),
                () -> assertTrue(ex.getMessage().endsWith(" failed to compile" + where + ": " + description),
                        ex.getMessage()));
    }

    private static String tooManyParts(String name, int parts) {
        return "Can't look up '" + name + "': it has " + parts + " dot-separated parts, and a class name may have at"
                + " most " + MAX_PARTS;
    }

    private static String tooManyNestedParts(String name, int parts) {
        return "Can't look up '" + name + "': it has " + (parts - 1) + " dot-separated parts and a '$', and a class"
                + " name with a '$' may have at most " + (MAX_PARTS - 1);
    }

    @ParameterizedTest(name = "{0} parts")
    @ValueSource(ints = {82, 100, 2_000})
    @DisplayName("a long chain in a condition still loads, with lookups that grow with its length")
    void conditionChainBounded(int parts) {
        CountingLoader loader = new CountingLoader();

        assertDoesNotThrow(() -> loaded(loader, rule(chain(parts) + " == 1", "true")));

        assertBounded(loader, parts);
    }

    @Test
    @DisplayName("a long chain in an action still loads, with lookups that grow with its length")
    void actionChainBounded() {
        CountingLoader loader = new CountingLoader();

        assertDoesNotThrow(() -> loaded(loader, rule("true", "x = " + chain(100) + ";")));

        assertBounded(loader, 100);
    }

    @ParameterizedTest(name = "{0} parts")
    @ValueSource(ints = {400, 2_000})
    @DisplayName("an import of a long package in a with block fails with MVEL's own error, with lookups that grow"
            + " with its length")
    void importInWithBlockBounded(int parts) {
        // MVEL compiles a with block's body as property chains, import line and all, so the check an import in the
        // rule's own text gets doesn't see it. Without the bound, 400 parts took 322,405 lookups.
        CountingLoader loader = new CountingLoader();
        String action = "m = new java.util.HashMap(); with (m) { import " + chain(parts) + ".*; x = 1 }";

        RuleCompilationException ex = assertRejected(loader, action);

        assertAll(
                () -> assertIssue(ex, 1, action.length() + 1, "unexpected end of statement"),
                () -> assertBounded(loader, parts));
    }

    @Test
    @DisplayName("a path of 100 parts through nested maps still loads and runs, as it did before the bound")
    void longValidPathRuns() {
        CountingLoader loader = new CountingLoader();
        RulesEngine<Map<String, Object>> engine = loaded(loader,
                rule("m." + chain(99) + " == 1", "output.put('fired', true)"));
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("m", nestedMaps(99));

        assertEquals(Map.of("fired", true), engine.run(facts));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"(m.%s)", "(m.%s) == 1", "(m.%s) + 1", "((m.%s))"})
    @DisplayName("a path of 91 parts through nested maps in parentheses, which MVEL first tries as a cast, still"
            + " loads and runs")
    void longPathInParenthesesRuns(String expression) {
        CountingLoader loader = new CountingLoader();
        String path = expression.formatted(chain(90));
        RulesEngine<Map<String, Object>> engine = loaded(loader, rule("true", "output.put('v', " + path + ")"));
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("m", nestedMaps(90));

        Object expected = expression.endsWith("== 1") ? true : expression.endsWith("+ 1") ? 2 : 1;
        assertEquals(Map.of("v", expected), engine.run(facts));
    }

    @Test
    @DisplayName("a path of 81 parts through nested maps runs, and its whole name is still looked up as a class")
    void pathAtLimitLookedUp() {
        CountingLoader loader = new CountingLoader();
        RulesEngine<Map<String, Object>> engine = loaded(loader,
                rule("m." + chain(80) + " == 1", "output.put('fired', true)"));
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("m", nestedMaps(80));

        assertEquals(Map.of("fired", true), engine.run(facts));
        assertEquals(MAX_PARTS, loader.mostParts.get(),
                "the longest name the application's class loader was asked for");
    }

    @Test
    @DisplayName("a property of 1,990 characters still loads and runs, as it did before the bound")
    void longPropertyRuns() {
        // MVEL looks the property up as a class nested in the type it is read from, and lets any failure but a
        // missing class through, so a name only too long must be refused as a missing class.
        String name = "a".repeat(1_990);
        CountingLoader loader = new CountingLoader();
        RulesEngine<Map<String, Object>> engine = loaded(loader, rule("true", "output.put('v', m." + name + ")"));
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("m", Map.of(name, 1));

        assertEquals(Map.of("v", 1), engine.run(facts));
    }

    @Test
    @DisplayName("a static field of a real class of 80 parts still reads, through its dotted or its binary name")
    void fieldOfClassAtLimitRuns(@TempDir Path classes) throws IOException {
        // A package of 78 parts, a class and a class nested in it: 80 parts, and the field makes 81. MVEL looks the
        // whole chain up as a class first, then its shorter chains.
        String pkg = "e.".repeat(77) + "e";
        compile(classes, pkg.replace('.', '/') + "/E", "package " + pkg + ";\n"
                + "public class E { public static class F { public static int V = 9; } }\n");
        try (URLClassLoader application = new URLClassLoader(new URL[]{classes.toUri().toURL()},
                DottedNameLimitsTest.class.getClassLoader())) {
            for (String field : List.of(pkg + ".E.F.V", pkg + ".E$F.V")) {
                RulesEngine<Map<String, Object>> engine = RulesEngineBuilder
                        .<Map<String, Object>>firstMatch(HashMap::new).build();
                withContextClassLoader(application, () -> {
                    engine.load(List.of(rule("true", "output.put('v', " + field + ")")));
                    return null;
                });

                assertEquals(Map.of("v", 9), withContextClassLoader(application, () -> engine.run(new FactMap<>())),
                        field.substring(pkg.length()));
            }
        }
    }

    @Test
    @DisplayName("a static field of a real class of 81 parts, over the bound, reads as properties, and fails at run")
    void fieldOfClassOverLimitFailsAtRun(@TempDir Path classes) throws IOException {
        // A package of 79 parts, a class and a class nested in it: 81 parts, more than an import's 64 and the 16 nested
        // classes the bound allows. MVEL gives up on the chain when it is refused before it looks the class up, so
        // this rule, which ran before the bound, reads pkg as an unknown fact.
        String pkg = "e.".repeat(78) + "e";
        compile(classes, pkg.replace('.', '/') + "/E", "package " + pkg + ";\n"
                + "public class E { public static class F { public static int V = 9; } }\n");
        try (URLClassLoader application = new URLClassLoader(new URL[]{classes.toUri().toURL()},
                DottedNameLimitsTest.class.getClassLoader())) {
            RulesEngine<Map<String, Object>> engine = RulesEngineBuilder
                    .<Map<String, Object>>firstMatch(HashMap::new).build();
            withContextClassLoader(application, () -> {
                engine.load(List.of(rule("true", "output.put('v', " + pkg + ".E.F.V)")));
                return null;
            });

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> withContextClassLoader(application, () -> engine.run(new FactMap<>())));

            assertTrue(ex.getMessage().contains("unresolvable property or identifier: e"), ex.getMessage());
        }
    }

    private static void compile(Path directory, String path, String text) {
        assertCompiles(List.of("-proc:none", "-d", directory.toString()), List.of(source(path, text)));
    }

    @Test
    @DisplayName("concurrent runs of a path of 100 parts, each compiling its own copy, look up names that grow with"
            + " its length")
    void concurrentRunsBounded() throws Exception {
        int runs = 8;
        CountDownLatch allStarted = new CountDownLatch(runs);
        // Each run holds its own copy of the rules until every run has one, so the copies are compiled at run().
        RuleListener waitForAll = new RuleListener() {
            @Override
            public void beforeEvaluate(Rule rule, Map<String, Object> facts) {
                allStarted.countDown();
                try {
                    assertTrue(allStarted.await(30, TimeUnit.SECONDS), "not every run started");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        };
        CountingLoader loader = new CountingLoader();
        RulesEngine<Map<String, Object>> engine = loaded(loader,
                rule("m." + chain(99) + " == 1", "output.put('fired', true)"), waitForAll);
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("m", nestedMaps(99));
        long atLoad = loader.lookups.get();

        ExecutorService pool = Executors.newFixedThreadPool(runs);
        List<Object> outputs = new ArrayList<>();
        try {
            List<Future<Map<String, Object>>> results = new ArrayList<>();
            for (int i = 0; i < runs; i++) {
                results.add(pool.submit(() -> engine.run(facts)));
            }
            for (Future<Map<String, Object>> result : results) {
                outputs.add(result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        long atRun = loader.lookups.get() - atLoad;
        assertAll(
                () -> assertEquals(List.of(Map.of("fired", true)), List.copyOf(Set.copyOf(outputs))),
                () -> assertEquals(runs, outputs.size()),
                () -> assertTrue(atRun <= (long) LOOKUPS_PER_PART * 100 * runs, atRun + " lookups in " + runs
                        + " concurrent runs"),
                () -> assertTrue(loader.mostParts.get() <= MAX_PARTS, "a lookup of " + loader.mostParts.get()
                        + " parts reached the application's class loader"));
    }

    @Test
    @DisplayName("new of a dotted class name of 82 parts still loads, and fails at run as it did before the bound")
    void newOfDottedNameLoads() {
        CountingLoader loader = new CountingLoader();
        RulesEngine<Map<String, Object>> engine = loaded(loader, rule("true", "x = new " + chain(82) + "();"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> engine.run(new FactMap<>()));

        assertTrue(ex.getMessage().contains("could not resolve class: " + chain(82)), ex.getMessage());
    }

    @Test
    @DisplayName("a cast to a dotted class name of 82 parts still loads, and fails at run as it did before the bound")
    void castToDottedNameLoads() {
        CountingLoader loader = new CountingLoader();
        RulesEngine<Map<String, Object>> engine = loaded(loader, rule("true", "y = 1; x = (" + chain(82) + ") y;"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> engine.run(new FactMap<>()));

        assertTrue(ex.getMessage().contains("unresolvable property or identifier: a"), ex.getMessage());
    }

    @Test
    @DisplayName("new of a nested class name of 2,001 characters still loads, and fails at run as it did before the"
            + " bound")
    void newOfLongNestedNameLoads() {
        CountingLoader loader = new CountingLoader();
        String name = "a".repeat(1000) + "$" + "a".repeat(1000);
        RulesEngine<Map<String, Object>> engine = loaded(loader, rule("true", "x = new " + name + "();"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> engine.run(new FactMap<>()));

        assertTrue(ex.getMessage().contains("could not resolve class: " + "a".repeat(100)), ex.getMessage());
    }

    @Test
    @DisplayName("new of a nested class name of 82 parts still loads, and fails at run as it did before the bound")
    void newOfNestedNameLoads() {
        CountingLoader loader = new CountingLoader();
        String name = nestedName(82);
        RulesEngine<Map<String, Object>> engine = loaded(loader, rule("true", "x = new " + name + "();"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> engine.run(new FactMap<>()));

        assertTrue(ex.getMessage().contains("could not resolve class: " + name), ex.getMessage());
    }

    @Test
    @DisplayName("a cast to a nested class name of 82 parts still loads, and fails at run as it did before the bound")
    void castToNestedNameLoads() {
        CountingLoader loader = new CountingLoader();
        RulesEngine<Map<String, Object>> engine = loaded(loader,
                rule("true", "y = 1;\nx = (" + nestedName(82) + ") y;"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> engine.run(new FactMap<>()));

        assertTrue(ex.getMessage().contains("unresolvable property or identifier: a"), ex.getMessage());
    }

    @Test
    @DisplayName("a foreach over a nested class name of 82 parts fails to compile with MVEL's own error, as it did"
            + " before the bound")
    void foreachTypeFailsAsBefore() {
        CountingLoader loader = new CountingLoader();
        String name = nestedName(82);

        RuleCompilationException ex = assertRejected(loader, "foreach (" + name + " x : [1]) { }");

        assertIssue(ex, 1, 10, "cannot resolve identifier: " + name);
    }

    @Test
    @DisplayName("new of a nested class name of 82 parts in a function that is never called still loads and runs")
    void newInUncalledFunctionRuns() {
        CountingLoader loader = new CountingLoader();
        RulesEngine<Map<String, Object>> engine = loaded(loader,
                rule("true", "def f() { new " + nestedName(82) + "() }; output.put('v', 1)"));

        assertEquals(Map.of("v", 1), engine.run(new FactMap<>()));
    }

    @Test
    @DisplayName("an import of a class of 82 parts fails to compile with MVEL's own error, as it did before the bound")
    void longClassImportFailsAsBefore() {
        CountingLoader loader = new CountingLoader();

        RuleCompilationException ex = assertRejected(loader, "import " + chain(82) + "; x = 1;");

        assertIssue(ex, 1, 8, "class not found: import " + chain(82) + "; x = 1;");
    }

    /** A path through nested maps of {@code parts} parts, {@code m.a.a...x$y}, whose last key has a '$'. */
    private static String dollarPath(int parts) {
        return "m." + "a.".repeat(parts - 2) + "x$y";
    }

    /** The fact {@link #dollarPath} reads: maps nested {@code parts - 1} deep, and {@code leaf} in the innermost. */
    private static Object dollarMaps(int parts, Object leaf) {
        Map<String, Object> innermost = new HashMap<>();
        innermost.put("x$y", leaf);
        Object value = innermost;
        for (int i = 0; i < parts - 2; i++) {
            Map<String, Object> map = new HashMap<>();
            map.put("a", value);
            value = map;
        }
        return value;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"output.put('v', (%s))", "output.put('v', (%s) == 1 ? 'y' : 'n')",
        "t = 0; foreach (e : (%s)) { t += e }; output.put('v', t)", "with ((%s)) { }; output.put('v', 1)"})
    @DisplayName("a path of 82 parts through nested maps with a '$' in parentheses, which MVEL first tries as a cast,"
            + " still loads and runs, as it did before the bound")
    void dollarPathInParenthesesRuns(String action) {
        CountingLoader loader = new CountingLoader();
        boolean list = action.contains("foreach");
        RulesEngine<Map<String, Object>> engine = loaded(loader, rule("true", action.formatted(dollarPath(82))));
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("m", dollarMaps(82, list ? List.of(1, 2, 3) : 1));

        Object expected = list ? 6 : action.contains("?") ? "y" : 1;
        assertEquals(Map.of("v", expected), engine.run(facts));
    }

    @ParameterizedTest(name = "{0} parts")
    @ValueSource(ints = {82, 86, 400})
    @DisplayName("a path through nested maps with a '$' in parentheses in a condition still loads and runs, with"
            + " lookups that grow with its length")
    void dollarPathInParenthesesBounded(int parts) {
        CountingLoader loader = new CountingLoader();
        RulesEngine<Map<String, Object>> engine = loaded(loader,
                rule("(" + dollarPath(parts) + ") == 1", "output.put('v', 1)"));
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("m", dollarMaps(parts, 1));

        assertEquals(Map.of("v", 1), engine.run(facts));
        assertTrue(loader.lookups.get() <= (long) LOOKUPS_PER_PART * parts, loader.lookups.get()
                + " lookups for a path of " + parts + " parts");
    }

    @ParameterizedTest(name = "{0} '$'")
    @ValueSource(ints = {77, 78, 200, 1_000})
    @DisplayName("a property with many '$', which MVEL looks up as a class nested in the fact's type, still loads and"
            + " runs, in a condition and in an action, as it did before the bound")
    void propertyWithManyDollarsRuns(int dollars) {
        // MVEL looks m.x$x$...x up as java.lang.Object$x$x...x, a name of 4 parts as the bound counts them. A bound
        // that counted each '$' as a part refused it, with an exception MVEL doesn't read as a missing class.
        String property = "x$".repeat(dollars) + "x";
        RulesEngine<Map<String, Object>> engine = loaded(new CountingLoader(),
                rule("m." + property + " == 1", "output.put('v', m." + property + ")"));
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("m", Map.of(property, 1));

        assertEquals(Map.of("v", 1), engine.run(facts));
    }

    @Test
    @DisplayName("a name of 80 or 81 parts, counting a '$' as one, of 2,000 characters, or of many '$', is still asked"
            + " of the application's class loader")
    void namesAtLimitLookedUp() {
        CountingLoader loader = new CountingLoader();
        Imports imports = new Imports(Set.of(), Set.of(), loader);

        assertAll(
                () -> assertThrows(ClassNotFoundException.class,
                        () -> Class.forName(chain(MAX_PARTS - 1), false, imports.classLoader())),
                () -> assertThrows(ClassNotFoundException.class,
                        () -> Class.forName(nestedName(MAX_PARTS - 1), false, imports.classLoader())),
                () -> assertThrows(ClassNotFoundException.class,
                        () -> Class.forName(chain(MAX_PARTS), false, imports.classLoader())),
                () -> assertThrows(ClassNotFoundException.class,
                        () -> Class.forName(nestedName(MAX_PARTS), false, imports.classLoader())),
                () -> assertThrows(ClassNotFoundException.class,
                        () -> Class.forName("a".repeat(MAX_LENGTH), false, imports.classLoader())),
                () -> assertThrows(ClassNotFoundException.class,
                        () -> Class.forName("a$".repeat(999) + "a", false, imports.classLoader())));
        assertEquals(6, loader.lookups.get());
    }

    @Test
    @DisplayName("a name of 82 parts, counting a '$' as one, or of 2,001 characters, is refused without a lookup, as a"
            + " class that isn't there")
    void namesOverLimitRefused() {
        CountingLoader loader = new CountingLoader();
        Imports imports = new Imports(Set.of(), Set.of(), loader);
        String nested = nestedName(82);
        String longNested = "a$" + "a".repeat(MAX_LENGTH - 1);

        assertAll(
                () -> assertEquals(tooManyParts(chain(82), 82), assertThrows(ClassNotFoundException.class,
                        () -> Class.forName(chain(82), false, imports.classLoader())).getMessage()),
                () -> assertThrows(ClassNotFoundException.class,
                        () -> Class.forName("a".repeat(MAX_LENGTH + 1), false, imports.classLoader())),
                () -> assertEquals("Can't look up '" + "a$" + "a".repeat(198) + "... (1801 more characters)': it has"
                        + " 2001 characters, and a class name may have at most " + MAX_LENGTH,
                        assertThrows(ClassNotFoundException.class,
                                () -> Class.forName(longNested, false, imports.classLoader())).getMessage()),
                () -> assertEquals(tooManyNestedParts(nested, 82), assertThrows(ClassNotFoundException.class,
                        () -> Class.forName(nested, false, imports.classLoader())).getMessage()));
        assertEquals(0, loader.lookups.get(), "lookups that reached the application's class loader");
    }

    @Test
    @DisplayName("MVEL's lookup of a nested class stops at the first name with a '$' and too many parts, with no lookup"
            + " of it")
    void nestedLookupStopped() {
        // A missing class would send it on to the next name; any other exception stops it, and its callers catch it.
        CountingLoader loader = new CountingLoader();
        Imports imports = new Imports(Set.of(), Set.of(), loader);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> ParseTools.forNameWithInner(chain(82), imports.classLoader()));

        assertAll(
                () -> assertEquals(tooManyNestedParts(chain(81) + "$a", 82), ex.getMessage()),
                () -> assertEquals(0, loader.lookups.get(), "lookups that reached the application's class loader"));
    }

    @Test
    @DisplayName("a lookup MVEL's JIT passes to its parent isn't bounded, as only MVEL's own names are")
    void jitParentLookupNotBounded() {
        CountingLoader loader = new CountingLoader();
        ExactNameClassLoader exactName = new ExactNameClassLoader(loader);

        assertThrows(ClassNotFoundException.class, () -> exactName.loadClass(chain(82), false));
        assertEquals(1, loader.lookups.get());
    }
}
