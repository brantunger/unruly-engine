package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.ChildJvm;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.internal.Causes;
import io.github.brantunger.unruly.internal.ImportSize;
import io.github.brantunger.unruly.internal.Lookups;
import io.github.brantunger.unruly.internal.Text;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1260: a language, a rule or the application may call {@link Throwables}, {@link ImportLimits} and
 * {@link MessageText} before any engine is built, as in a {@code catch} after a failure, deep in a stack. A class the
 * first call initializes there could overflow its static initializer, and then fail for the life of the JVM, every
 * later engine build with it: {@code Failures}' logger started SLF4J, and on JDK 21 a count of an import's parts in
 * a {@code long} initialized {@code java.lang.Long}, as did the {@code String.startsWith} that {@code Lookups} told
 * the JVM's "wrong name" error with, when it didn't match. Overflowing at every depth finds that only on some JDKs and
 * stacks, so this runs {@link FacadeFirstUseScenario} in a new JVM, before any engine is built, and reads the
 * {@code Initializing '...'} lines {@code -Xlog:class+init} prints between its markers: no class with a static
 * initializer may be initialized there, the JVM's hidden classes aside, but what {@link #MEASURED_SAFE} names. A
 * class without one, which the JVM logs with {@code (no method)}, runs no code of its own. Then the scenario builds and
 * runs an engine, which shows the calls left nothing broken. #1286: escaping a character above U+00FF read the JDK's
 * table for its plane, {@code java.lang.CharacterData00} or a sibling, which the JVM builds on its first use, so the
 * scenario escapes such characters too, and the test checks that no such table is initialized by the calls. The test
 * assumes the JVM has read a string with a character above U+00FF already, as a JVM on Windows has at startup: the
 * scenario reads one before its first marker, as the first read of one initializes {@code java.lang.StringUTF16},
 * the JDK's code for such strings, which nothing at startup does on Linux or macOS. A deep first read there breaks
 * StringUTF16 for the life of the JVM, which the library can't avoid for a call with no engine built; a separate
 * issue covers it. #1292: the blank check of a rule's name and tags, a run's tags, a language's name and a fact's
 * name, {@code String.isBlank()}, read the same table for a name that starts with such a character, with no engine
 * built, until the engine's own {@code internal.Whitespace} decided that; so the scenario builds a rule, declares
 * facts, adds a language and makes run options with such names too, between the markers. #1298: a rule's build and
 * run options sorted their tags in a {@code TreeSet}, whose static initializer, and {@code TreeMap}'s, the JVM runs
 * on its first use, with no engine built, until the engine's own {@code internal.Sorting} sorted them; the scenario
 * used to initialize {@code TreeSet} before its first marker, and now the build and the options between the markers
 * must initialize neither. #1313: the engine's own {@code internal.Text}, which the escaping calls reach, held the
 * characters above U+00FF it escapes in two arrays, built by a static initializer, so it was measured safe; it now
 * decides them with comparisons, has no static initializer, and the calls must initialize it. #1271: a language's
 * tests make a compile context with no engine built, which checks each package import's shape, as {@code build()}
 * does, so the scenario makes one with a package import and tries one with an import that isn't a valid package name,
 * between the markers; the context's constructor checked its options and declarations with two lambdas, whose call
 * sites, when linked, initialized the JDK's method handle classes, and {@code java.lang.Long} on JDK 21, and it now
 * checks them with loops.
 */
@DisplayName("the language facades' first calls, with no engine, initialize no class with a static initializer (#1260)")
class FacadeFirstUseInitializationTest {

    private static final String INITIALIZING = "Initializing '";
    private static final String NO_METHOD = "(no method)";

    // The classes a first call may initialize, each measured safe: its first initialization, deep in a stack at
    // every depth to the end, was clean on JDK 21 and 25 (#1260), as its static initializer is too small to overflow.
    // Each may or may not appear, as a JDK may initialize it at startup.
    private static final Set<String> MEASURED_SAFE = Set.of(
            // A cause chain's set of the exceptions read is backed by one.
            "java.util.IdentityHashMap");

    // The facades, and the engine's classes behind them, none with a static initializer, which the calls must
    // initialize: so the test can't pass by reading the wrong lines, nor because something before the calls had
    // initialized them already. Failures, FactNames and LanguageNames aren't public, or aren't reachable from here,
    // and Whitespace and Sorting aren't on a release before their fixes, so they are named by their names (#1292,
    // #1298).
    private static final List<String> FIRST_USED = List.of(Throwables.class.getName(), ImportLimits.class.getName(),
            MessageText.class.getName(), "io.github.brantunger.unruly.core.Failures", Lookups.class.getName(),
            ImportSize.class.getName(), Causes.class.getName(), Text.class.getName(), Rule.class.getName(),
            Rule.RuleBuilder.class.getName(), "io.github.brantunger.unruly.core.EngineCompileContext",
            "io.github.brantunger.unruly.core.FactNames", "io.github.brantunger.unruly.core.LanguageNames",
            "io.github.brantunger.unruly.internal.Whitespace", "io.github.brantunger.unruly.internal.Sorting");
    // JDK 21 doesn't initialize Long at startup, so there the calls are the first that could (#1260).
    private static final String LONG = INITIALIZING + "java/lang/Long'";
    // No JDK initializes TreeSet or TreeMap at startup, unless a multi-release jar is opened before the main class, so
    // the calls are the first that could, and the test checks that nothing before them did (#1298).
    private static final List<String> SORTED = List.of(INITIALIZING + "java/util/TreeSet'",
            INITIALIZING + "java/util/TreeMap'");
    // The JDK's tables of the Unicode characters above U+00FF, one class a plane, such as CharacterData00, which the
    // JVM builds on their first use: escaping such a character read one until the engine's own table decided it, as
    // did the blank check of a name that starts with one (#1292), and the engine's first build initializes all of
    // them, after the calls (#1286).
    private static final String CHARACTER_DATA = "java.lang.CharacterData";

    @Test
    @DisplayName("between the first calls' markers, only classes without a static initializer, or measured safe, are"
            + " initialized, and an engine built after them runs")
    void firstCallsInitializeNothingThatCouldBreak(@TempDir Path dir) throws IOException, InterruptedException {
        String output = ChildJvm.run(dir, FacadeFirstUseScenario.class, "-Xlog:class+init=info:stdout");
        List<String> lines = output.lines().toList();
        int before = lines.indexOf(FacadeFirstUseScenario.BEFORE);
        int after = lines.indexOf(FacadeFirstUseScenario.AFTER);
        assertTrue(before >= 0 && after > before, "markers missing:\n" + output);
        for (String line : lines.subList(0, before)) {
            if (Runtime.version().feature() == 21) {
                assertFalse(line.contains(LONG), "Long initialized before the first calls:\n" + output);
            }
            for (String sorted : SORTED) {
                assertFalse(line.contains(sorted), "TreeSet or TreeMap initialized before the first calls, so they "
                        + "couldn't have been the first to:\n" + output);
            }
        }

        List<String> initialized = new ArrayList<>();
        List<String> reached = new ArrayList<>();
        List<String> hidden = new ArrayList<>();
        for (String line : lines.subList(before + 1, after)) {
            int at = line.indexOf(INITIALIZING);
            if (at < 0) {
                continue;
            }
            int start = at + INITIALIZING.length();
            String name = line.substring(start, line.indexOf('\'', start)).replace('/', '.');
            initialized.add(name);
            // A hidden class's name has a slash, or a plus sign, before its address, as in
            // java/lang/invoke/LambdaForm$MH/0x000001a0010c4400.
            if (name.contains(".0x") || name.contains("+0x")) {
                hidden.add(line);
            } else if (!line.contains(NO_METHOD) && !MEASURED_SAFE.contains(name)) {
                reached.add(line);
            }
        }

        assertEquals(List.of(), reached, "classes with a static initializer the first calls initialized (hidden "
                + "classes left out: " + hidden + "):\n" + output);
        assertEquals(List.of(), initialized.stream().filter(name -> name.startsWith(CHARACTER_DATA)).toList(),
                "the JDK's tables of the characters above U+00FF read by the first calls (#1286):\n" + output);
        for (String type : FIRST_USED) {
            assertTrue(initialized.contains(type), type + " not initialized by the first calls:\n" + output);
        }
        // What the first calls answered, so they are known to have taken each path.
        List<String> answers = answers(lines);
        assertEquals(List.of("false", "true", "true", "false", "false", "false", "true", "false", "false", "true",
                "false", "null", "OutOfMemoryError(probe)", "OutOfMemoryError(probe)", "OutOfMemoryError(probe)",
                "null", "false", "true", "true", "false", "outer",
                "(message unavailable: java.lang.IllegalStateException)",
                "[]", "[RuntimeException(outer), IllegalArgumentException(inner)]", "[CauseThrows(null)]",
                "IllegalArgumentException(inner)", "CauseThrows(null)", "false", "true", "true", "false", "false",
                "false", "false", "true", "false", "null",
                "it has 65 dot-separated parts, and an import may have at most 64",
                "it has 1001 characters, and an import may have at most 1000"), answers.subList(0, 39), output);
        // MessageText's answers, which MessageTextTest pins, are only counted.
        assertEquals(61, answers.size(), output);
        // The names that start with a Greek letter were accepted: the rule and the run options, as each describes
        // itself, and the builder's three calls, each true for having answered the builder (#1292).
        assertEquals(List.of("Rule(ruleName=\u03c0rule, condition=x == 1, action=put hit true, priority=null,"
                + " description=null, language=null, enabled=true, validFrom=null, validTo=null, tags=[\u03c0tag])",
                "true", "true", "true", "RunOptions(timeout=the engine's, tags=[\u03c0run])"),
                answers.subList(54, 59), output);
        // The compile contexts: the package import kept, and the malformed one rejected with build()'s message (#1271).
        assertEquals(List.of("[java.util]",
                "IllegalArgumentException('zq..b' is neither a class nor a valid package name)"),
                answers.subList(59, 61), output);
        assertTrue(lines.contains(FacadeFirstUseScenario.ENGINE + "{hit=true}"), "the engine didn't run:\n" + output);
    }

    private static List<String> answers(List<String> lines) {
        List<String> answers = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith(FacadeFirstUseScenario.ANSWER)) {
                answers.add(line.substring(FacadeFirstUseScenario.ANSWER.length()));
            }
        }
        return answers;
    }
}
