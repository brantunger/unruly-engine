package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FactNames keeps only the names that aren't classes it can save a lookup for")
class FactNamesMissCacheTest {

    private static FactNames imports(Set<String> packages, ClassLoader loader) {
        return new FactNames(new Imports(packages, Set.of(), loader));
    }

    @Test
    @DisplayName("the characters the cache may hold, and the longest name it keeps, are those FactNamesTest writes out")
    void cachedMissCharsAgree() {
        assertEquals(FactNamesTest.CACHED_MISS_CHARS, FactNames.MAX_CACHED_MISS_CHARS);
        assertEquals(FactNamesTest.LONGEST_CACHED_MISS, FactNames.MAX_CACHED_MISS_LENGTH);
    }

    @Test
    @DisplayName("with no package imported, a name is neither looked up nor cached")
    void noImportsCachesNothing() {
        RecordingClassLoader loader = new RecordingClassLoader();
        FactNames names = imports(Set.of(), loader);

        names.check("abc");

        assertEquals(0, names.cachedMisses(), "there is no lookup to save, so nothing is cached");
        assertEquals(List.of(), loader.resources);
    }

    @Test
    @DisplayName("with no package imported, a reserved word is still rejected")
    void noImportsStillRejectsReservedWords() {
        FactNames names = imports(Set.of(), new RecordingClassLoader());

        assertThrows(IllegalArgumentException.class, () -> names.check("null"));
    }

    @Test
    @DisplayName("a name that isn't a class in an imported package is cached")
    void shortMissCached() {
        FactNames names = imports(Set.of("java.util"), new RecordingClassLoader());

        names.check("abc");

        assertEquals(1, names.cachedMisses());
    }

    @Test
    @DisplayName("only a name no longer than the longest cached one is cached")
    void longMissNotCached() {
        FactNames names = imports(Set.of("java.util"), new RecordingClassLoader());

        names.check("x".repeat(FactNames.MAX_CACHED_MISS_LENGTH + 1));
        assertEquals(0, names.cachedMisses(), "a name one character too long isn't cached");

        names.check("x".repeat(256));
        assertEquals(1, names.cachedMisses(), "a name of more than 255 characters is (#700)");

        names.check("x".repeat(FactNames.MAX_CACHED_MISS_LENGTH));
        assertEquals(2, names.cachedMisses(), "a name of the longest cached length is");
    }

    // #849: the counts the other tests read stayed right whichever name an eviction took, and whether or not it left
    // the set of misses.
    @Test
    @DisplayName("a full cache evicts the name its random numbers pick, and an evicted name is looked up again")
    void evictsThePickedName() {
        long seed = 849;
        RecordingClassLoader loader = new RecordingClassLoader();
        FactNames names = new FactNames(new Imports(Set.of("java.util"), Set.of(), loader), new SplittableRandom(seed));
        // The slots as the cache keeps them, picked from with the same random numbers, so the test knows which names
        // each eviction takes: the last name moves into the slot of the name evicted.
        List<String> slots = new ArrayList<>();
        Set<String> cached = new HashSet<>();
        SplittableRandom picks = new SplittableRandom(seed);
        int count = FactNames.MAX_CACHED_MISSES + 256;
        List<String> expected = new ArrayList<>();
        List<String> lookedUp = new ArrayList<>();
        // Every name once, to fill the cache and evict 256 of them, then every name again.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < count; i++) {
                String name = "name" + i;
                if (!cached.contains(name)) {
                    expected.add(name);
                    if (slots.size() == FactNames.MAX_CACHED_MISSES) {
                        int victim = picks.nextInt(slots.size());
                        cached.remove(slots.get(victim));
                        slots.set(victim, slots.get(slots.size() - 1));
                        slots.remove(slots.size() - 1);
                    }
                    slots.add(name);
                    cached.add(name);
                }
                int before = loader.resources.size();
                names.check(name);
                if (loader.resources.size() > before) {
                    lookedUp.add(name);
                }
            }
        }

        assertEquals(expected, lookedUp, "the names looked up: each the first time, then each evicted since");
        assertEquals(slots.size(), names.cachedMisses());
        assertEquals(slots.stream().mapToInt(String::length).sum(), names.cachedMissChars());
    }
}
