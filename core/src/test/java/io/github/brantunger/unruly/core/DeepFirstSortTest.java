package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.ChildJvm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #1298: a rule's build and run options' {@code withTags} sorted their tags in a {@link java.util.TreeSet}, and the
 * first read of a fact class's properties sorted its getters with {@code List.sort} into a {@link java.util.TreeMap}.
 * Each of those classes has a static initializer the JVM doesn't run at startup, and all three paths run with no
 * engine built, before any build has made room on the stack and initialized them. When {@code TreeSet} was loaded but
 * not initialized, as a class literal or an {@code instanceof} anywhere in an application leaves it, and the path's
 * other classes were resolved, the JVM's first {@code Rule.build()} or {@code withTags} deep in a stack overflowed
 * inside {@code TreeSet}'s static initializer, which left the class unusable for the life of the JVM: every later
 * build, with tags or without, every {@code withTags}, every engine build and the application's own {@code TreeSet}s
 * failed with {@link NoClassDefFoundError}. Now the engine sorts them itself, into a {@code LinkedHashSet} or
 * {@code LinkedHashMap}, whose classes have none. Each test runs {@link DeepFirstSortScenario} in a new JVM, which
 * puts it in that state and makes the deep call the JVM's first, with {@code -Xlog:class+init}, and checks that the
 * call returned its sorted text, that the JVM hadn't initialized {@code TreeSet} or {@code TreeMap} before the walk,
 * so the test can't pass because something at startup had initialized them, that the deep thread initialized neither,
 * nor the JDK's sorts, nor any class with a static initializer at all (a fact's first read merged each getter into
 * its map with a method reference, whose link initializes the JDK's method-handle classes, until it chose with a
 * plain call), but did initialize the engine's own sort, which no call before the walk reaches, so the lines the
 * JVM attributes to the deep thread are known to be read; and that {@code TreeSet}, {@code TreeMap}, rules, options,
 * facts and engine builds work after it. On a JDK whose log doesn't name the thread that initialized a class, as
 * Oracle's 21.0.7 doesn't, the checks of what the deep thread initialized are skipped, after the others have run.
 */
@DisplayName("a first sort of tags or of a fact's properties deep in a stack, with no engine built, leaves TreeSet, "
        + "TreeMap and engine builds working (#1298)")
class DeepFirstSortTest {

    // The scenario's recursion kept interpreted, so it reaches the same depth each time it looks for the end of the
    // stack.
    private static final String INTERPRETED_SCENARIO =
            "-XX:CompileCommand=exclude," + DeepFirstSortScenario.class.getName() + "::descend";
    private static final String INITIALIZING = "Initializing '";
    private static final String NO_METHOD = "(no method)";
    private static final String BY_THREAD = " by thread \"";
    private static final String BY_DEEP_THREAD = "by thread \"" + DeepFirstSortScenario.DEEP_THREAD + "\"";
    // The classes the paths used to initialize, as -Xlog:class+init names them: the two sorted collections, and
    // the JDK's sorts, which List.sort, Collections.sort and Arrays.sort initialize on their first use.
    private static final List<String> SORTS = List.of("java/util/TreeSet", "java/util/TreeMap", "java/util/TimSort",
            "java/util/ComparableTimSort", "java/util/Arrays$LegacyMergeSort");
    // The engine's own sort, which only the deep call reaches, named by its name, as it isn't on a release before the
    // fix.
    private static final String SORTING = "io/github/brantunger/unruly/internal/Sorting";

    @Test
    @DisplayName("deep, with no engine built, a rule's first build with tags sorts them, initializing no class with "
            + "a static initializer")
    void ruleBuild(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = scenario(dir, "rule");

        assertNothingBroken(lines);
        assertTrue(value(lines, DeepFirstSortScenario.TEXT).endsWith("tags=[eu, retail])"),
                "the deep call's text:\n" + String.join("\n", lines));
        assertDeepThreadInitializedNothing(lines);
    }

    @Test
    @DisplayName("deep, with no engine built, the first withTags sorts the tags, initializing no class with a static "
            + "initializer")
    void withTags(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = scenario(dir, "options");

        assertNothingBroken(lines);
        assertEquals("RunOptions(timeout=the engine's, tags=[eu, retail])", value(lines, DeepFirstSortScenario.TEXT),
                "the deep call's text:\n" + String.join("\n", lines));
        assertDeepThreadInitializedNothing(lines);
    }

    @Test
    @DisplayName("deep, with no engine built, the first read of a fact class's properties sorts them, initializing no "
            + "class with a static initializer")
    void factProperties(@TempDir Path dir) throws IOException, InterruptedException {
        List<String> lines = scenario(dir, "facts");

        // The sorts first, where the log names the deep thread: TreeMap's one-statement static initializer used to
        // survive the walk, and the next thing on the path, linking the method reference the getters were merged
        // with, broke a class of the JDK's method handles instead.
        if (namesThreads(lines)) {
            assertEquals(List.of(), initializedByDeepThread(lines, true),
                    "sorted collections or sorts of the JDK's the deep call initialized:\n" + String.join("\n", lines));
        }
        assertNothingBroken(lines);
        assertEquals("[age, name]", value(lines, DeepFirstSortScenario.TEXT),
                "the deep call's text:\n" + String.join("\n", lines));
        assertDeepThreadInitializedNothing(lines);
    }

    // The JVM hadn't initialized TreeSet or TreeMap before the walk, the walk started at the end of the stack, the
    // first call that didn't overflow threw nothing, and the checks after the walk, which initialize both, found
    // nothing broken. None of it reads which thread initialized a class.
    private static void assertNothingBroken(List<String> lines) {
        String output = String.join("\n", lines);
        int walking = lines.indexOf(DeepFirstSortScenario.WALKING);
        assertTrue(walking >= 0, "the walk's marker is missing:\n" + output);
        for (String line : lines.subList(0, walking)) {
            for (String sort : SORTS) {
                assertFalse(line.contains(INITIALIZING + sort + "'"), sort + " initialized before the walk, so the "
                        + "deep call couldn't have been its first use:\n" + output);
            }
        }
        assertNotEquals("0", value(lines, DeepFirstSortScenario.OVERFLOWED), "calls that overflowed:\n" + output);
        assertEquals(DeepFirstSortScenario.NOTHING, value(lines, DeepFirstSortScenario.DEEP),
                "what the first call that didn't overflow threw:\n" + output);
        assertEquals("[a, b]", value(lines, DeepFirstSortScenario.TREE_SET), "a new TreeSet after the deep call:\n"
                + output);
        assertEquals("{a=2, b=1}", value(lines, DeepFirstSortScenario.TREE_MAP), "a new TreeMap after the deep call:\n"
                + output);
        assertEquals("[a, b]", value(lines, DeepFirstSortScenario.RULE), "a rule with tags after the deep call:\n"
                + output);
        assertEquals("[]", value(lines, DeepFirstSortScenario.TAGLESS), "a rule without tags after the deep call:\n"
                + output);
        assertEquals("[a, b]", value(lines, DeepFirstSortScenario.OPTIONS), "run options with tags after the deep "
                + "call:\n" + output);
        assertEquals("[age, name]", value(lines, DeepFirstSortScenario.FACTS), "a fact's properties after the deep "
                + "call:\n" + output);
        assertEquals("{hit=true}", value(lines, DeepFirstSortScenario.ENGINE),
                "an engine built, loaded and run with tags after the deep call:\n" + output);
        // The checks after the walk were the JVM's first use of TreeSet: so the log shows what is initialized, and
        // the walk left it to them.
        assertTrue(lines.subList(walking, lines.size()).stream().anyMatch(line -> line.contains(INITIALIZING
                + SORTS.get(0) + "'")), "TreeSet not initialized by the checks after the walk:\n" + output);
    }

    // The deep thread initialized neither TreeSet nor TreeMap, nor a sort of the JDK's, nor any class with a static
    // initializer, but did initialize the engine's own sort. Skipped on a JDK whose -Xlog:class+init doesn't name the
    // thread that initialized a class, as Oracle's 21.0.7 doesn't, where Temurin's 21.0.10, 25 and 26 do: there no
    // line can be told to be the deep thread's.
    private static void assertDeepThreadInitializedNothing(List<String> lines) {
        String output = String.join("\n", lines);
        assumeTrue(namesThreads(lines), "this JDK's -Xlog:class+init doesn't name the initializing thread");
        assertEquals(List.of(), initializedByDeepThread(lines, true),
                "sorted collections or sorts of the JDK's the deep call initialized:\n" + output);
        // The deep call was the first to reach the engine's own sort, as no call before the walk sorts: so the lines
        // the JVM attributes to the deep thread are read as such, and the checks here couldn't pass by reading none.
        assertTrue(lines.stream().anyMatch(line -> line.contains(INITIALIZING + SORTING + "'")
                && line.endsWith(BY_DEEP_THREAD)), "the engine's sort not initialized by the deep thread:\n" + output);
        assertEquals(List.of(), initializedByDeepThread(lines, false),
                "classes with a static initializer the deep call initialized:\n" + output);
    }

    // Whether the JVM's log names the thread that initialized each class.
    private static boolean namesThreads(List<String> lines) {
        return lines.stream().anyMatch(line -> line.contains(INITIALIZING) && line.contains(BY_THREAD));
    }

    // The classes the deep thread initialized, the JVM's hidden classes left out: those with a static initializer, or
    // only the sorted collections and the JDK's sorts.
    private static List<String> initializedByDeepThread(List<String> lines, boolean sortsOnly) {
        List<String> initialized = new ArrayList<>();
        for (String line : lines) {
            int at = line.indexOf(INITIALIZING);
            if (at < 0 || !line.endsWith(BY_DEEP_THREAD)) {
                continue;
            }
            int start = at + INITIALIZING.length();
            String name = line.substring(start, line.indexOf('\'', start));
            // A hidden class's name has a slash, or a plus sign, before its address, as in
            // java/lang/invoke/LambdaForm$MH/0x000001a0010c4400.
            boolean hidden = name.contains("/0x") || name.contains("+0x");
            if (sortsOnly ? SORTS.contains(name) : !hidden && !line.contains(NO_METHOD)) {
                initialized.add(line);
            }
        }
        return initialized;
    }

    private static List<String> scenario(Path dir, String mode) throws IOException, InterruptedException {
        return ChildJvm.run(dir, DeepFirstSortScenario.class, "-XX:CompileCommand=quiet", INTERPRETED_SCENARIO,
                "-Xlog:class+init=info:stdout", "-D" + DeepFirstSortScenario.MODE + "=" + mode).lines().toList();
    }

    private static String value(List<String> lines, String start) {
        return lines.stream().filter(line -> line.startsWith(start)).map(line -> line.substring(start.length()))
                .findFirst().orElse("missing");
    }
}
