package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FactNames looks class names up as MVEL does, within bounds")
class FactNamesTest {

    /** What GraalVM sets to tell code it is running in an image, which the check reads instead of the GraalVM SDK. */
    private static final String IMAGE_CODE = "org.graalvm.nativeimage.imagecode";

    /**
     * {@code FactNames.MAX_CACHED_MISS_CHARS}, written out so these tests also run on a check that has no such limit;
     * {@code FactNamesMissCacheTest} asserts the two agree.
     */
    static final int CACHED_MISS_CHARS = 4096 * 255;

    /** {@code FactNames.MAX_CACHED_MISS_LENGTH}, written out as {@link #CACHED_MISS_CHARS} is. */
    static final int LONGEST_CACHED_MISS = CACHED_MISS_CHARS / 16;

    /** Seeds the names a full cache evicts, so a test counting lookups after evictions sees the same ones each run. */
    private static final long EVICTION_SEED = 763;

    private static FactNames javaUtil(ClassLoader loader) {
        return new FactNames(new Imports(Set.of("java.util"), Set.of(), loader));
    }

    private static FactNames javaUtilSeeded(ClassLoader loader) {
        return new FactNames(new Imports(Set.of("java.util"), Set.of(), loader),
                new SplittableRandom(EVICTION_SEED));
    }

    /**
     * Runs {@code action} with the image-code property set to {@code value}. No JVM sets that property, so the
     * finally clears it rather than putting an earlier value back; leaving it set would follow every later test.
     */
    private static void withImageCode(String value, Runnable action) {
        System.setProperty(IMAGE_CODE, value);
        try {
            action.run();
        } finally {
            System.clearProperty(IMAGE_CODE);
        }
    }

    /**
     * A class loader that loads classes as the JDK's own do but serves no class file as a resource, which is what a
     * native image does with a class its resource configuration doesn't name. It records every name it is asked for.
     */
    private static ClassLoader servesNoClassFile(List<String> lookups) {
        return new ClassLoader(FactNamesTest.class.getClassLoader()) {
            @Override
            public URL getResource(String name) {
                lookups.add(name);
                return null;
            }
        };
    }

    /**
     * What a class directory on a case-insensitive file system does: pkg/date.class finds pkg/Date.class. It records
     * every name it is asked for, so a test can say whether the class file was looked up at all.
     */
    private static ClassLoader caseInsensitiveClassDirectory(URL found, List<String> lookups) {
        return new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
                lookups.add(name);
                return name.equals("pkg/date.class") ? found : null;
            }

            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("pkg.date")) {
                    throw new NoClassDefFoundError("pkg/date (wrong name: pkg/Date)");
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    @Test
    @DisplayName("a class name is looked up once and then answered from the cache")
    void classNameCached() {
        RecordingClassLoader loader = new RecordingClassLoader();
        FactNames names = javaUtil(loader);

        for (int i = 0; i < 3; i++) {
            assertThrows(IllegalArgumentException.class, () -> names.check("Date"));
        }

        assertEquals(1, Collections.frequency(loader.resources, "java/util/Date.class"));
    }

    // #763: a full cache was cleared, so one name more than it holds left 1 cached; now one is evicted, and 4,096 kept.
    @Test
    @DisplayName("names that aren't classes are cached, and a full cache evicts one to make room for another")
    void missesBounded() {
        RecordingClassLoader loader = new RecordingClassLoader();
        FactNames names = javaUtil(loader);

        names.check("name0");
        names.check("name0");
        assertEquals(1, Collections.frequency(loader.resources, "java/util/name0.class"), "a miss is cached");

        for (int i = 1; i <= FactNames.MAX_CACHED_MISSES; i++) {
            names.check("name" + i);
        }

        assertEquals(FactNames.MAX_CACHED_MISSES, names.cachedMisses(),
                "the full cache evicted one name for the last, rather than being cleared");
    }

    // #763: once a full cache was cleared, every name it had held was looked up again the next time it came round.
    @Test
    @DisplayName("a full cache that takes one new name still answers for nearly all of the names it held")
    void fullCacheKeepsItsWorkingSet() {
        RecordingClassLoader loader = new RecordingClassLoader();
        FactNames names = javaUtilSeeded(loader);
        for (int i = 0; i < FactNames.MAX_CACHED_MISSES; i++) {
            names.check("name" + i);
        }
        names.check("newName");
        int before = loader.resources.size();

        for (int i = 0; i < FactNames.MAX_CACHED_MISSES; i++) {
            names.check("name" + i);
        }

        // One name was evicted for newName; looking it up again evicts another, which may come round too, and so on.
        int lookups = loader.resources.size() - before;
        assertTrue(lookups <= 16, lookups + " of " + FactNames.MAX_CACHED_MISSES + " names were looked up again");
    }

    // #763: a cycle of names just larger than the cache cleared it on every pass, so a working set just larger than
    // the cache never found a name in it.
    @Test
    @DisplayName("a cycle of names larger than the cache still finds most of them in it")
    void cycleLargerThanTheCacheMostlyCached() {
        RecordingClassLoader loader = new RecordingClassLoader();
        FactNames names = javaUtilSeeded(loader);
        int cycle = FactNames.MAX_CACHED_MISSES + 50;
        for (int i = 0; i < cycle; i++) {
            names.check("name" + i);
        }
        int before = loader.resources.size();

        for (int i = 0; i < cycle; i++) {
            names.check("name" + i);
        }

        int lookups = loader.resources.size() - before;
        assertTrue(lookups < cycle / 4, lookups + " of " + cycle + " names were looked up again");
    }

    // Review of #763: a name that needs more room than one eviction frees evicts as many names as it takes.
    @Test
    @DisplayName("a long name evicts as many names as it takes to fit within the characters the cache may hold")
    void longNameEvictsUntilItFits() {
        FactNames names = javaUtilSeeded(new RecordingClassLoader());
        // 4,096 names of 255 characters take every character the cache may hold, and every slot.
        for (int i = 0; i < FactNames.MAX_CACHED_MISSES; i++) {
            names.check(String.format("n%04d", i).repeat(51));
        }
        assertEquals(CACHED_MISS_CHARS, names.cachedMissChars());

        names.check("x".repeat(LONGEST_CACHED_MISS));

        assertEquals(CACHED_MISS_CHARS, names.cachedMissChars(), "256 names of 255 characters were evicted for it");
        assertEquals(FactNames.MAX_CACHED_MISSES - 256 + 1, names.cachedMisses());
    }

    @Test
    @DisplayName("a name another check cached while this one looked it up is cached, and counted, once")
    void missCachedMeanwhileCountedOnce() {
        AtomicReference<FactNames> holder = new AtomicReference<>();
        // Checks the name again while the first check is looking it up, as another thread could.
        ClassLoader loader = new ClassLoader(FactNamesTest.class.getClassLoader()) {
            private boolean nested;

            @Override
            public URL getResource(String name) {
                if (!nested) {
                    nested = true;
                    holder.get().check("meanwhile");
                }
                return null;
            }
        };
        FactNames names = javaUtil(loader);
        holder.set(names);

        names.check("meanwhile");

        assertEquals(1, names.cachedMisses());
        assertEquals("meanwhile".length(), names.cachedMissChars());
    }

    // #700: a name of more than 255 characters wasn't cached, so its class file was looked up again on every run.
    @Test
    @DisplayName("a name of more than 255 characters is cached, so it is looked up once")
    void longMissCached() {
        RecordingClassLoader loader = new RecordingClassLoader();
        FactNames names = javaUtil(loader);
        String name = "x".repeat(256);

        names.check(name);
        names.check(name);

        assertEquals(1, Collections.frequency(loader.resources, "java/util/" + name + ".class"));
    }

    @Test
    @DisplayName("a name longer than the longest cached one isn't kept")
    void missLongerThanTheLongestNotCached() {
        FactNames names = javaUtil(new RecordingClassLoader());

        names.check("x".repeat(LONGEST_CACHED_MISS + 1));

        assertEquals(0, names.cachedMisses());
        assertEquals(0, names.cachedMissChars());
    }

    @Test
    @DisplayName("a name as long as the longest cached one is still cached")
    void missAsLongAsTheLongestCached() {
        FactNames names = javaUtil(new RecordingClassLoader());

        names.check("x".repeat(LONGEST_CACHED_MISS));

        assertEquals(1, names.cachedMisses());
        assertEquals(LONGEST_CACHED_MISS, names.cachedMissChars());
    }

    // #774: a name the rule list's class loader refuses, as over 2,000 characters, was still looked up on the class
    // path, once for every imported package, and on every run when it was too long to cache.
    @Test
    @DisplayName("a name the class loader refuses as too long isn't looked up on the class path, and is still cached")
    void refusedNameNotLookedUp() {
        RecordingClassLoader loader = new RecordingClassLoader();
        FactNames names = new FactNames(new Imports(Set.of("java.util", "java.time"), Set.of(), loader));
        String refused = "x".repeat(ExactNameClassLoader.MAX_NAME_LENGTH - "java.util.".length() + 1);
        String tooLongToCache = "x".repeat(LONGEST_CACHED_MISS + 1);

        names.check(refused);
        names.check(tooLongToCache);
        names.check(tooLongToCache);

        assertEquals(List.of(), loader.resources);
        assertEquals(1, names.cachedMisses(), "the refused name is cached, as a name looked up and not found is");
        assertEquals(refused.length(), names.cachedMissChars());
    }

    @Test
    @DisplayName("a name the class loader doesn't refuse, however near the limit, is still looked up")
    void longestNameNotRefusedLookedUp() {
        RecordingClassLoader loader = new RecordingClassLoader();
        FactNames names = javaUtil(loader);
        String name = "x".repeat(ExactNameClassLoader.MAX_NAME_LENGTH - "java.util.".length());

        names.check(name);

        assertEquals(List.of("java/util/" + name + ".class"), loader.resources);
    }

    @Test
    @DisplayName("a name that would take the cache's characters past what it may hold evicts one to make room")
    void missesBoundedByCharacters() {
        FactNames names = javaUtil(new RecordingClassLoader());
        // Sixteen names of the longest cached length fill the cache, so a seventeenth doesn't fit with them.
        List<String> longest = new ArrayList<>();
        for (char c = 'a'; c <= 'q'; c++) {
            longest.add(String.valueOf(c).repeat(LONGEST_CACHED_MISS));
        }

        longest.subList(0, 16).forEach(names::check);
        assertEquals(16, names.cachedMisses(), "the first sixteen fit exactly, so none was evicted");
        assertEquals(CACHED_MISS_CHARS, names.cachedMissChars());

        names.check(longest.get(16));
        assertEquals(16, names.cachedMisses(),
                "the seventeenth didn't fit with them, so one was evicted for it rather than the cache being cleared");
        assertEquals(CACHED_MISS_CHARS, names.cachedMissChars(), "the evicted name's characters were freed");
    }

    // Review of #700: two names of more than half of what the cache may hold, checked in turn, cleared it each time.
    @Test
    @DisplayName("very long names checked in turn don't clear the cache of the short ones")
    void veryLongMissesKeepShortOnes() {
        RecordingClassLoader loader = new RecordingClassLoader();
        FactNames names = javaUtil(loader);
        String half = "y".repeat(CACHED_MISS_CHARS / 2 + 1);

        names.check("shortName");
        for (int i = 0; i < 2; i++) {
            names.check(half);
            names.check(half.replace('y', 'z'));
        }
        names.check("shortName");

        assertEquals(1, Collections.frequency(loader.resources, "java/util/shortName.class"));
    }

    @Test
    @DisplayName("a class file that can't be loaded isn't a class, as MVEL's own lookup in an imported package treats"
            + " it")
    void unloadableClassFile(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("broken"));
        Files.write(dir.resolve("broken").resolve("Thing.class"), new byte[] {1, 2, 3});

        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()}, null)) {
            FactNames names = new FactNames(new Imports(Set.of("broken"), Set.of(), loader));

            assertNotNull(loader.getResource("broken/Thing.class"));
            assertDoesNotThrow(() -> names.check("Thing"));
        }
    }

    @Test
    @DisplayName("a class file the loader lists but then can't find isn't a class")
    void listedButNotLoadable(@TempDir Path dir) throws IOException {
        URL found = dir.toUri().toURL();
        ClassLoader resourcesOnly = new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
                return name.equals("pkg/Ghost.class") ? found : null;
            }
        };
        FactNames names = new FactNames(new Imports(Set.of("pkg"), Set.of(), resourcesOnly));

        assertDoesNotThrow(() -> names.check("Ghost"));
    }

    @Test
    @DisplayName("a class file found for a name that differs in case isn't a class")
    void classFileWithWrongName(@TempDir Path dir) throws IOException {
        List<String> lookups = new ArrayList<>();
        FactNames names = new FactNames(
                new Imports(Set.of("pkg"), Set.of(), caseInsensitiveClassDirectory(dir.toUri().toURL(), lookups)));

        assertDoesNotThrow(() -> names.check("date"));

        assertEquals(List.of("pkg/date.class"), lookups, "the class file is what the lookup found, wrong name and all");
    }

    @Test
    @DisplayName("in a native image a name is loaded without the class file being looked up, as MVEL loads it")
    void imageLoadsWithoutLookingTheClassFileUp() {
        List<String> lookups = new ArrayList<>();
        FactNames names = javaUtil(servesNoClassFile(lookups));

        withImageCode("runtime", () -> assertThrows(IllegalArgumentException.class, () -> names.check("Date"),
                "an image loads java.util.Date as MVEL does, so a fact named Date is rejected there too"));

        assertEquals(List.of(), lookups, "an image serves no class file, so a lookup would answer for every name");
    }

    @Test
    @DisplayName("while an image is being built the class file is looked up first, as on any other JVM")
    void imageBuildTimeLooksTheClassFileUpFirst() {
        List<String> lookups = new ArrayList<>();
        FactNames names = javaUtil(servesNoClassFile(lookups));

        // "buildtime" is an ordinary JVM loading the classes an image is built from, where the lock object the
        // lookup exists to avoid is as real as it is anywhere else.
        withImageCode("buildtime", () -> assertDoesNotThrow(() -> names.check("Date")));

        assertEquals(List.of("java/util/Date.class"), lookups, "only a run in an image skips the lookup");
    }

    @Test
    @DisplayName("in a native image a name that differs in case is ruled out by the load, with no class file looked up")
    void classFileWithWrongNameInImage(@TempDir Path dir) throws IOException {
        // The false match is ruled out by the load, not by the lookup, which is what lets an image skip the lookup
        // and stay protected from it. The same loader as the test above, so the two answers are comparable.
        List<String> lookups = new ArrayList<>();
        FactNames names = new FactNames(
                new Imports(Set.of("pkg"), Set.of(), caseInsensitiveClassDirectory(dir.toUri().toURL(), lookups)));

        withImageCode("runtime", () -> assertDoesNotThrow(() -> names.check("date")));

        assertEquals(List.of(), lookups, "an image asks for no class file, so its false match can't come from one");
    }

    @Test
    @DisplayName("a name the loader refuses with an error that isn't a linkage error isn't a class")
    void unregisteredClassIsntAClass(@TempDir Path dir) throws IOException {
        URL found = dir.toUri().toURL();
        ClassLoader unregistered = new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
                return name.equals("pkg/Thing.class") ? found : null;
            }

            @Override
            protected Class<?> loadClass(String name, boolean resolve) {
                throw new NotRegisteredError(name + " isn't registered for reflection");
            }
        };
        FactNames names = new FactNames(new Imports(Set.of("pkg"), Set.of(), unregistered));

        assertDoesNotThrow(() -> names.check("Thing"),
                "the name reads as the fact, as MVEL's own lookup reads it, instead of failing the run");
    }

    @Test
    @DisplayName("the check doesn't read an error that means the JVM itself is failing as a name that isn't a class")
    void virtualMachineErrorIsRethrown(@TempDir Path dir) throws IOException {
        // What the check itself does, not what a caller of the engine sees: AbstractRulesEngine catches what escapes
        // a fact-name check, and only Failures.fatalError — which passes a StackOverflowError over — is rethrown, so
        // a caller reads an IllegalArgumentException naming the fact instead.
        URL found = dir.toUri().toURL();
        ClassLoader failing = new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
                return name.equals("pkg/Thing.class") ? found : null;
            }

            @Override
            protected Class<?> loadClass(String name, boolean resolve) {
                throw new StackOverflowError("simulated");
            }
        };
        FactNames names = new FactNames(new Imports(Set.of("pkg"), Set.of(), failing));

        assertThrows(StackOverflowError.class, () -> names.check("Thing"));
    }

    /**
     * A class loader that serves {@code classFile} as a resource and records every class it is asked to load, then
     * finds none: what the rule list's class loader must not ask for a name too long to look up.
     */
    private static ClassLoader recordingLoads(URL found, String classFile, List<String> loads) {
        return new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
                return name.equals(classFile) ? found : null;
            }

            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                loads.add(name);
                throw new ClassNotFoundException(name);
            }
        };
    }

    @Test
    @DisplayName("a class file whose name has too many parts to look up isn't a class, and isn't loaded (#687)")
    void nameWithTooManyPartsIsntAClass(@TempDir Path dir) throws IOException {
        // A package of 81 parts, as a test can make one: the engine imports at most 64. With the fact's name, the
        // class has 82.
        String pkg = "p.".repeat(80) + "p";
        List<String> loads = new ArrayList<>();
        ClassLoader loader = recordingLoads(dir.toUri().toURL(), pkg.replace('.', '/') + "/Thing.class", loads);
        FactNames names = new FactNames(new Imports(Set.of(pkg), Set.of(), loader));

        assertDoesNotThrow(() -> names.check("Thing"), "the name reads as the fact, as MVEL's own lookup reads it");

        assertEquals(List.of(), loads, "the class loader was asked for a name of 82 parts");
    }

    @Test
    @DisplayName("in a native image a name too long to look up isn't a class, and isn't loaded (#687)")
    void nameTooLongIsntAClassInImage() {
        // A fact's name may have a '$': a name too long to look up is refused as a missing class, '$' or not.
        List<String> loads = new ArrayList<>();
        FactNames names = javaUtil(recordingLoads(null, "", loads));
        String name = "x$".repeat(1_000);

        withImageCode("runtime", () -> assertDoesNotThrow(() -> names.check(name),
                "the name reads as the fact, as MVEL's own lookup reads it"));

        assertEquals(List.of(), loads, "the class loader was asked for a name of 2,010 characters");
    }

    /**
     * Stands in for a native image's {@code MissingReflectionRegistrationError}, which is an {@link Error} but not a
     * {@link LinkageError}, so the catch that reads a name it can't load as the fact has to name {@code Error} to
     * hold it.
     */
    private static final class NotRegisteredError extends Error {
        private static final long serialVersionUID = 1L;

        NotRegisteredError(String message) {
            super(message);
        }
    }
}
