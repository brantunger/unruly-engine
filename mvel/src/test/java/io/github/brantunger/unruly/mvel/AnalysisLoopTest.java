package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mvel2.CompileException;
import org.mvel2.MVEL;
import org.mvel2.ParserConfiguration;
import org.mvel2.ParserContext;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #840: MVEL's analysis pass is stopped once it has asked the parser configuration for its class loader more times
 * than the expression allows, as it does on every round of a loop that never ends.
 */
@DisplayName("MVEL's analysis is stopped when it goes round in a loop")
class AnalysisLoopTest {

    private static final Imports IMPORTS = new Imports(Set.of(), Set.of(), AnalysisLoopTest.class.getClassLoader());

    /** What the counting configuration throws, which MVEL can't catch where it asks, as it catches Exceptions. */
    private static final class Stop extends Error {
        private static final long serialVersionUID = 1L;
    }

    /** A plain configuration of MVEL's own that counts the calls, and stops the analysis after a number of them. */
    private static final class Counting extends ParserConfiguration {
        private static final long serialVersionUID = 1L;
        private final long stopAfter;
        private long calls;

        Counting(long stopAfter) {
            this.stopAfter = stopAfter;
        }

        @Override
        public ClassLoader getClassLoader() {
            if (++calls > stopAfter) {
                throw new Stop();
            }
            return super.getClassLoader();
        }
    }

    /** Runs MVEL's analysis of a text with a counting configuration on a daemon thread, and returns the calls. */
    private static long callsAnalysing(String text, long stopAfter) throws InterruptedException {
        Counting configuration = new Counting(stopAfter);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread analysis = new Thread(() -> {
            try {
                MVEL.analysisCompile(text, new ParserContext(configuration));
            } catch (Throwable e) {
                thrown.set(e);
            }
        }, "analysis");
        analysis.setDaemon(true);
        analysis.start();
        try {
            analysis.join(TimeUnit.SECONDS.toMillis(30));
            assertFalse(analysis.isAlive(), "MVEL's analysis went on without asking for the class loader");
        } finally {
            analysis.interrupt();
        }
        return configuration.calls;
    }

    @Test
    @DisplayName("MVEL asks for the class loader on every round of the loop: an upgrade that changes this fails here")
    void mvelAsksOnEveryRound() throws InterruptedException {
        // The detector relies on this. If a new MVEL returns from the analysis instead, its loop may be fixed; if it
        // stops asking, the detector no longer stops the loop.
        assertTrue(callsAnalysing("java.lang.Math.abs(1)x", 100_000) > 100_000);
        assertTrue(callsAnalysing("if (true) { java.util.List.of().size()x }", 100_000) > 100_000);
        assertTrue(callsAnalysing("java.lang.Math.abs(1) + java.util.List.of().size()", 100_000) < 10);
    }

    @Test
    @DisplayName("a configuration answers as many calls as the limit allows, and then throws on every call")
    void limitAllowsExactlyTheCalls() {
        ParserConfiguration configuration = IMPORTS.newConfiguration();
        Imports.limitClassLoaderCalls(configuration, 3);

        for (int call = 0; call < 3; call++) {
            assertSame(IMPORTS.classLoader(), configuration.getClassLoader());
        }
        assertNull(Imports.analysisLoop(configuration));
        Imports.AnalysisLoop loop = assertThrows(Imports.AnalysisLoop.class, configuration::getClassLoader);
        assertSame(loop, assertThrows(Imports.AnalysisLoop.class, configuration::getClassLoader));
        assertSame(loop, Imports.analysisLoop(configuration));
    }

    @Test
    @DisplayName("a configuration without a limit answers every call")
    void noLimitByDefault() {
        ParserConfiguration configuration = IMPORTS.newConfiguration();

        for (int call = 0; call < 100_000; call++) {
            assertSame(IMPORTS.classLoader(), configuration.getClassLoader());
        }
        assertNull(Imports.analysisLoop(configuration));
    }

    /**
     * Imports of java.util and 200 other packages, with a class loader that isn't one of the JDK's own, so MVEL looks
     * every name up in every package with the class loader.
     */
    private static Imports manyPackages() {
        Set<String> packages = new HashSet<>(Set.of("java.util"));
        IntStream.range(0, 200).forEach(index -> packages.add("pkg" + index));
        return new Imports(packages, Set.of(), new ClassLoader(AnalysisLoopTest.class.getClassLoader()) {
        });
    }

    /** 300 names, each looked up in 201 packages: far more calls for the class loader than the limit allows. */
    private static String manyNames() {
        return IntStream.range(0, 300).mapToObj(index -> "x" + index).collect(Collectors.joining(" + "));
    }

    @Test
    @DisplayName("looking names up in many imported packages doesn't count against the limit")
    void importLookupsDontCount() {
        Imports imports = manyPackages();

        assertDoesNotThrow(() -> new MvelAnalysis(manyNames(), imports).compile());
    }

    @Test
    @DisplayName("looking names up in many imported packages doesn't make a class a name that isn't one")
    void importLookupsKeepClasses() {
        Imports imports = manyPackages();
        MvelAnalysis analysis = new MvelAnalysis(manyNames() + "; new ArrayList()", imports);

        assertDoesNotThrow(analysis::compile);
        assertFalse(imports.notClasses().contains("ArrayList"));
        assertDoesNotThrow(() -> new MvelAnalysis("new ArrayList()", imports).compile());
    }

    @ParameterizedTest(name = "{0} characters -> {1}")
    @CsvSource({"0, 10000", "1, 10020", "3, 10060", "140000, 2810000"})
    @DisplayName("the limit is a fixed allowance and 20 more for each character")
    void limit(int length, long limit) {
        assertEquals(limit, MvelAnalysis.classLoaderCallLimit(length));
    }

    /** A chain of n names that aren't classes, wrapped in n levels of parentheses MVEL analyses it again for. */
    private static String wrappedChain(int levels) {
        return "(".repeat(levels) + "m" + ".a".repeat(levels) + ")".repeat(levels);
    }

    @Test
    @DisplayName("a long chain in 92 levels of brackets still loads, and one in 93 is rejected: MVEL asks about as "
            + "many times as the levels and the chain's parts multiplied")
    void wrappedChainAtTheLimit() {
        // A documented consequence of a limit that grows with the length alone (see classLoaderCallLimit).
        assertDoesNotThrow(() -> new MvelAnalysis(wrappedChain(92), IMPORTS).compile());
        CompileException loop = assertThrows(CompileException.class,
                () -> new MvelAnalysis(wrappedChain(93), IMPORTS).compile());
        assertInstanceOf(Imports.AnalysisLoop.class, loop.getCause());
    }
}
