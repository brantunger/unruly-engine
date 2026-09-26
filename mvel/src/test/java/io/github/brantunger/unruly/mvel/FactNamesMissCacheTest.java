package io.github.brantunger.unruly.mvel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FactNames keeps only the names that aren't classes it can save a lookup for")
class FactNamesMissCacheTest {

    private static FactNames imports(Set<String> packages, ClassLoader loader) {
        return new FactNames(new Imports(packages, Set.of(), loader));
    }

    @Test
    @DisplayName("the longest cached name is the one FactNamesTest writes out")
    void longestCachedMissAgrees() {
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

        names.check("x".repeat(FactNames.MAX_CACHED_MISS_LENGTH));
        assertEquals(1, names.cachedMisses(), "a name of the longest cached length is");
    }
}
