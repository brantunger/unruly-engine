package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mvel2.MVEL;
import org.mvel2.ParserConfiguration;
import org.mvel2.ParserContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #861: MVEL's calls for the class loader are counted by where they come from, the whole stack in MVEL's analysis, and
 * the frames at its top and its depth in a run, and a loop is stopped once one place has asked more times than the
 * expression's length allows.
 */
@DisplayName("MVEL's calls for the class loader are limited for each place they come from")
class CallSitesTest {

    private static final Imports IMPORTS = new Imports(Set.of(), Set.of(), CallSitesTest.class.getClassLoader());

    /** Runs a body on a daemon thread joined with a bound, so a loop that isn't stopped fails the test. */
    private static void onDaemon(String what, Executable body) throws InterruptedException {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                body.execute();
            } catch (Throwable e) {
                failed.set(e);
            }
        }, "call-sites");
        thread.setDaemon(true);
        thread.start();
        try {
            // The longest loop here is stopped in about 8 seconds with JaCoCo.
            thread.join(TimeUnit.SECONDS.toMillis(80));
            assertFalse(thread.isAlive(), () -> what + " wasn't stopped");
        } finally {
            thread.interrupt();
        }
        if (failed.get() != null) {
            fail(what + " failed", failed.get());
        }
    }

    /** A configuration for MVEL's analysis of a text, with the limits the engine sets for it. */
    private static ParserConfiguration analysisConfiguration(String text) {
        ParserConfiguration configuration = IMPORTS.newConfiguration();
        Imports.limitClassLoaderCalls(configuration, MvelAnalysis.classLoaderCallLimit(text.length()),
                MvelAnalysis.classLoaderCallsPerSite(text.length()));
        return configuration;
    }

    /** The longest gap between two walks of the stack. */
    private static final long LONGEST_GAP = 2L * CallSites.SAMPLED_EVERY - 1;

    /**
     * Checks a loop that asks from one place was stopped as the count allows: once a count drawn from the walks has
     * passed the limit for one place, after the first {@value CallSites#UNCOUNTED_CALLS} calls and at most one gap
     * more, and then the limit and one more calls are counted one by one.
     */
    static void assertStoppedAtTheLimitForOnePlace(long calls, long perSite) {
        long fewest = CallSites.UNCOUNTED_CALLS + 2 * perSite + 2;
        assertTrue(calls >= fewest && calls <= fewest + LONGEST_GAP - 1,
                () -> calls + " calls, for a limit of " + perSite + " for one place");
    }

    /** A call glued to a word, with an argument of 4,000 characters: about a millisecond a round. */
    private static final String LONG_ARGUMENT = "java.lang.Math.abs(" + String.join("+", Collections.nCopies(2_000,
            "1")) + ")x";

    @Test
    @DisplayName("MVEL's analysis going round in a loop with a long argument is stopped once one place has asked more "
            + "than the limit for it")
    void longArgumentLoopStoppedAtTheLimitForOnePlace() throws InterruptedException {
        analysisLoopStoppedAtTheLimitForOnePlace(LONG_ARGUMENT);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"java.lang.Math.abs(1)x", "java.util.List.of().size()x",
        "if (true) { java.util.List.of().size()x }", "java.lang.Math.abs(1) > 1"})
    @DisplayName("MVEL's analysis going round in a loop is stopped once one place has asked more than the limit for "
            + "it: an upgrade that makes the rounds ask from different places fails here")
    void analysisLoopStoppedAtTheLimitForOnePlace(String text) throws InterruptedException {
        ParserConfiguration configuration = analysisConfiguration(text);
        onDaemon(text, () -> assertThrows(Throwable.class,
                () -> MVEL.analysisCompile(text, new ParserContext(configuration))));

        assertNotNull(Imports.analysisLoop(configuration));
        assertStoppedAtTheLimitForOnePlace(Imports.classLoaderCalls(configuration),
                MvelAnalysis.classLoaderCallsPerSite(text.length()));
    }

    @Test
    @DisplayName("MVEL going round in a loop as it runs is stopped once one place has asked more than the limit for "
            + "it: an upgrade that makes the rounds ask from different places fails here")
    void runLoopStoppedAtTheLimitForOnePlace() throws InterruptedException {
        String text = "java.lang.String.class (2)";
        MvelExpression.Copy copy = MvelExpression.newCopy(text, IMPORTS,
                MvelExpression.classLoaderCallsPerRun(text.length()));
        Imports.startRun(copy.configuration());
        onDaemon(text, () -> assertThrows(Throwable.class,
                () -> MVEL.executeExpression(copy.expression(), new HashMap<String, Object>())));

        assertNotNull(Imports.runLoop(copy.configuration()));
        assertStoppedAtTheLimitForOnePlace(Imports.classLoaderCalls(copy.configuration()),
                MvelAnalysis.classLoaderCallsPerSite(text.length()));
    }

    @Test
    @DisplayName("the calls MVEL makes as it compiles a copy, before its first run, aren't counted")
    void compilingACopyIsNotCounted() {
        MvelExpression.Copy copy = MvelExpression.compile("java.lang.Math.abs(-1) + java.util.List.of().size()",
                IMPORTS).newCompiled();
        assertEquals(0, Imports.classLoaderCalls(copy.configuration()));

        ParserConfiguration configuration = IMPORTS.newRunConfiguration(10_000, 10);
        // Every call from this line is from one place, and none counts before a run starts.
        for (int call = 0; call < 1_000; call++) {
            configuration.getClassLoader();
        }
        assertEquals(0, Imports.classLoaderCalls(configuration));
        assertNull(Imports.runLoop(configuration));
    }

    /** Asks for the class loader from one place until the configuration throws, and returns what it threw. */
    static Error askedUntilStopped(ParserConfiguration configuration) {
        while (true) {
            try {
                configuration.getClassLoader();
            } catch (Imports.AnalysisLoop | Imports.RunLoop e) {
                return e;
            }
        }
    }

    @Test
    @DisplayName("each run counts the calls from each place afresh")
    void eachRunCountsAfresh() {
        ParserConfiguration configuration = IMPORTS.newRunConfiguration(10_000, 10);

        for (int run = 0; run < 3; run++) {
            Imports.startRun(configuration);
            assertEquals(0, Imports.classLoaderCalls(configuration));
            assertNull(Imports.runLoop(configuration));
            Error loop = askedUntilStopped(configuration);

            assertStoppedAtTheLimitForOnePlace(Imports.classLoaderCalls(configuration), 10);
            assertSame(loop, Imports.runLoop(configuration));
            // Every call after the one that passed the limit throws it again, from any place, until the run ends.
            assertSame(loop, assertThrows(Imports.RunLoop.class, configuration::getClassLoader));
        }
    }

    @Test
    @DisplayName("calls from two places in turn are counted for each, not together, whichever calls are walked")
    void eachPlaceHasItsOwnCount() {
        ParserConfiguration analysis = IMPORTS.newConfiguration();
        Imports.limitClassLoaderCalls(analysis, 100_000, 6_000);
        ParserConfiguration run = IMPORTS.newRunConfiguration(100_000, 6_000);
        Imports.startRun(run);

        // After the first 100, each line makes 5,000 calls, and the two 10,000. A walk every other call, or every
        // fourth, would count them all at one of the two.
        for (int call = 0; call < 5_050; call++) {
            analysis.getClassLoader();
            analysis.getClassLoader();
            run.getClassLoader();
            run.getClassLoader();
        }
        assertNull(Imports.analysisLoop(analysis));
        assertNull(Imports.runLoop(run));
    }

    /** Asks for the class loader from {@code depth} frames further down the stack. */
    private static void askFrom(ParserConfiguration configuration, int depth) {
        if (depth > 0) {
            askFrom(configuration, depth - 1);
        } else {
            configuration.getClassLoader();
        }
    }

    @Test
    @DisplayName("calls from places that change, as many as the stack is deep, are stopped by the limit in all")
    void placesThatChangeAreStoppedByTheLimitInAll() {
        // A walk counts the calls since the one before at the walked site, so the limit for one place exceeds a gap.
        ParserConfiguration analysis = IMPORTS.newConfiguration();
        Imports.limitClassLoaderCalls(analysis, 500, 2 * LONGEST_GAP);
        ParserConfiguration run = IMPORTS.newRunConfiguration(500, 2 * LONGEST_GAP);
        Imports.startRun(run);

        // Each call is from a stack one frame deeper than the one before, so from a place of its own.
        for (int depth = 0; depth < 500; depth++) {
            askFrom(analysis, depth);
            askFrom(run, depth);
        }
        assertThrows(Imports.AnalysisLoop.class, () -> askFrom(analysis, 500));
        assertThrows(Imports.RunLoop.class, () -> askFrom(run, 500));
        assertEquals(500, Imports.classLoaderCalls(analysis));
        assertEquals(500, Imports.classLoaderCalls(run));
    }

    @Test
    @DisplayName("in a run, calls whose stacks differ only below the frames read at its top, at the same depth, are "
            + "from one place; in MVEL's analysis, the whole stack tells them apart")
    void runReadsTheTopOfTheStackAnalysisAllOfIt() {
        ParserConfiguration analysis = IMPORTS.newConfiguration();
        Imports.limitClassLoaderCalls(analysis, 100_000, 3_000);
        ParserConfiguration run = IMPORTS.newRunConfiguration(100_000, 3_000);
        Imports.startRun(run);
        int deeperThanTheTop = CallSites.RUN_FRAMES;

        // The two lines differ, below the frames askFrom adds, at the same depth: 2,000 calls from each.
        for (int call = 0; call < 2_050; call++) {
            askFrom(analysis, deeperThanTheTop);
            askFrom(analysis, deeperThanTheTop);
        }
        assertNull(Imports.analysisLoop(analysis));
        // In the run they are one place's: after a count drawn from the walks passes the limit, the limit again.
        assertThrows(Imports.RunLoop.class, () -> {
            for (int call = 0; call < 5_000; call++) {
                askFrom(run, deeperThanTheTop);
                askFrom(run, deeperThanTheTop);
            }
        });
    }

    @Test
    @DisplayName("a call close to one MVEL's analysis goes round in a loop for asks too few times to read its place")
    void nearMissesDontReadThePlace() {
        // A named guard for what reading the place costs: ordinary expressions never walk the stack.
        Stream<String> texts = Stream.concat(GluedCallRejectionTest.nearMissesRun(),
                GluedCallRejectionTest.gluedMembersRun()).map(arguments -> (String) arguments.get()[0]);
        texts.forEach(text -> {
            ParserConfiguration configuration = analysisConfiguration(text);
            MVEL.analysisCompile(text, new ParserContext(configuration));
            long calls = Imports.classLoaderCalls(configuration);
            assertTrue(calls <= CallSites.UNCOUNTED_CALLS, () -> text + ": " + calls + " calls");
        });
    }

    /** Counts a call from a stack {@code depth} frames deeper, and tells whether the count said to stop. */
    private static boolean countedFrom(CallSites sites, int depth) {
        return depth > 0 ? countedFrom(sites, depth - 1) : sites.counted();
    }

    @Test
    @DisplayName("more places than the count may keep are a loop: a loop whose place keeps changing is stopped, with "
            + "what the count keeps bounded")
    void tooManyPlacesAreALoop() {
        CallSites sites = new CallSites(false, Long.MAX_VALUE, 100);
        assertEquals(0, sites.sites());

        // Each call is from a stack one frame deeper than the one before, so from a place of its own.
        int depth = 0;
        while (!countedFrom(sites, depth)) {
            depth++;
            assertTrue(depth < 10_000, "never stopped");
        }
        assertEquals(101, sites.sites());
        int stoppedAt = depth;
        assertTrue(stoppedAt > CallSites.UNCOUNTED_CALLS + 100, () -> "stopped after " + stoppedAt);
    }

    @Test
    @DisplayName("a configuration read back, whose count of places isn't kept, counts afresh, and one without a limit "
            + "for one place, as one written before there was one reads, is stopped by the limit in all")
    void configurationReadBack() throws Exception {
        ParserConfiguration written = IMPORTS.newRunConfiguration(10_000, 10);
        Imports.startRun(written);
        askedUntilStopped(written);
        ParserConfiguration read = readBack(written);

        Imports.startRun(read);
        assertEquals(0, Imports.classLoaderCalls(read));
        askedUntilStopped(read);
        assertStoppedAtTheLimitForOnePlace(Imports.classLoaderCalls(read), 10);

        ParserConfiguration analysis = IMPORTS.newConfiguration();
        Imports.limitClassLoaderCalls(analysis, 10_000, 10);
        ParserConfiguration analysisRead = readBack(analysis);
        askedUntilStopped(analysisRead);
        assertStoppedAtTheLimitForOnePlace(Imports.classLoaderCalls(analysisRead), 10);
        assertNotNull(Imports.analysisLoop(analysisRead));

        ParserConfiguration noLimitPerSite = IMPORTS.newRunConfiguration(500, 0);
        Imports.startRun(noLimitPerSite);
        askedUntilStopped(noLimitPerSite);
        assertEquals(500, Imports.classLoaderCalls(noLimitPerSite));
    }

    /** Writes a configuration out and reads it back, as Java's serialization does. */
    private static ParserConfiguration readBack(ParserConfiguration configuration) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(configuration);
        }
        try (ObjectInputStream in = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()))) {
            return (ParserConfiguration) in.readObject();
        }
    }

    /** Counts calls from sites that come round in turn, each making as many, and tells whether the count stopped. */
    private static boolean stoppedInTurn(int sites, long callsEach, long perSite) {
        CallSites count = new CallSites(false, perSite);
        for (long call = 0; call < sites * callsEach; call++) {
            long site = call % sites;
            if (count.countedAt(() -> site)) {
                return true;
            }
        }
        return false;
    }

    @ParameterizedTest(name = "{0} places, {1} calls from each, {2} allowed")
    @CsvSource({"2, 77000, 78005", "3, 46000, 47005", "2, 5100, 6105"})
    @DisplayName("places that ask in turn, each nearly as often as the limit for one place allows, aren't a loop, "
            + "however far a count drawn from the walks strays: the calls are then counted one by one")
    void longValidExpressionsAreNotLoops(int sites, long callsEach, long perSite) {
        // The first is the first run of s.equals(m.a.a...) with 77,000 parts, 154,011 characters: counts drawn from
        // the walks alone passed its limit at call 153,977. The last is the same with 5,100 parts, 10,211 characters.
        assertFalse(stoppedInTurn(sites, callsEach, perSite));
    }

    @Test
    @DisplayName("a place that asks for ever is stopped after the limit for it, a gap, and the limit again")
    void onePlaceIsStoppedAfterTheLimitTwice() {
        CallSites count = new CallSites(false, 78_005);
        long calls = 0;
        while (!count.countedAt(() -> 7)) {
            calls++;
        }
        assertStoppedAtTheLimitForOnePlace(calls + 1, 78_005);
        assertEquals(calls + 1, count.calls());
    }

    // The gaps are drawn from the same start for every count, so the same ones are drawn each time.
    @Test
    @DisplayName("#1019: the gaps between walks of the stack are from 1 call to the longest, some longer than "
            + CallSites.SAMPLED_EVERY)
    void gapsBetweenWalks() {
        CallSites count = new CallSites(false, Long.MAX_VALUE);
        List<Long> walked = new ArrayList<>();
        for (int call = 0; call < 10_000; call++) {
            assertFalse(count.countedAt(() -> {
                walked.add(count.calls());
                return 7;
            }));
        }

        long longest = 0;
        long last = CallSites.UNCOUNTED_CALLS;
        for (long walk : walked) {
            long gap = walk - last;
            assertTrue(gap >= 1 && gap <= LONGEST_GAP, () -> "a gap of " + gap);
            longest = Math.max(longest, gap);
            last = walk;
        }
        assertTrue(longest > CallSites.SAMPLED_EVERY, "the longest gap is " + longest);
    }
}
