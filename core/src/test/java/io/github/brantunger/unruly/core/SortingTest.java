package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.internal.Sorting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1298: the engine's own sort, {@link Sorting}, which a rule's tags, a run's tags and a fact class's properties are
 * sorted with, so that none of them initializes {@code TreeSet}, {@code TreeMap} or the JDK's sorts before any engine
 * is built. Its order must be theirs: {@link Sorting#set} gives what a {@link TreeSet} holds, in its order, and
 * {@link Sorting#sort(String[], Object[])} what a stable sort by the keys gives, so values with equal keys keep their
 * order, as the choice between two getters of one property depends on it.
 */
@DisplayName("Sorting: the engine's own sort orders as TreeSet and a stable sort do (#1298)")
class SortingTest {

    // Keys with ties, prefixes, an empty string, and characters above U+00FF, which String order places after ASCII.
    private static final List<String> ALPHABET = List.of("", "a", "A", "aa", "ab", "b", "B", "getName", "getname",
            "isName", "é", "π", "z", "0", " ");

    @Test
    @DisplayName("set gives the strings in String order, each once, unmodifiable, in its iteration order")
    void set() {
        Set<String> set = Sorting.set(List.of("retail", "eu", "asia", "eu", "", "π", "Eu"));

        assertEquals(List.of("", "Eu", "asia", "eu", "retail", "π"), List.copyOf(set));
        assertEquals(new TreeSet<>(List.of("retail", "eu", "asia", "eu", "", "π", "Eu")), set);
        assertThrows(UnsupportedOperationException.class, () -> set.add("uk"));
        assertEquals(Set.of(), Sorting.set(List.of()));
    }

    @Test
    @DisplayName("set holds what a TreeSet of the same strings holds, in its order, for random strings with repeats")
    void setAsTreeSet() {
        Random random = new Random(1298);
        for (int round = 0; round < 2000; round++) {
            List<String> strings = randomStrings(random, random.nextInt(12));

            assertEquals(List.copyOf(new TreeSet<>(strings)), List.copyOf(Sorting.set(strings)), strings.toString());
        }
    }

    @Test
    @DisplayName("set reads a collection whole, even one that holds more than its size() says")
    void setReadsMoreThanSize() {
        Collection<String> lying = new AbstractCollection<>() {
            @Override
            public Iterator<String> iterator() {
                return List.of("c", "b", "a", "d").iterator();
            }

            @Override
            public int size() {
                return 1;
            }
        };

        assertEquals(List.of("a", "b", "c", "d"), List.copyOf(Sorting.set(lying)));
    }

    @Test
    @DisplayName("set reads a collection whose size() is negative")
    void setReadsNegativeSize() {
        Collection<String> negative = new AbstractCollection<>() {
            @Override
            public Iterator<String> iterator() {
                return List.of("b", "a").iterator();
            }

            @Override
            public int size() {
                return -1;
            }
        };

        assertEquals(List.of("a", "b"), List.copyOf(Sorting.set(negative)));
    }

    @Test
    @DisplayName("set refuses a null string, first or after others")
    void setRefusesNull() {
        NullPointerException first = assertThrows(NullPointerException.class,
                () -> Sorting.set(Collections.singletonList(null)));
        NullPointerException later = assertThrows(NullPointerException.class,
                () -> Sorting.set(Arrays.asList("a", null)));

        assertEquals("strings must not contain null", first.getMessage());
        assertEquals("strings must not contain null", later.getMessage());
    }

    @Test
    @DisplayName("sort with values orders the keys as Arrays.sort does and keeps the order of values with equal keys")
    void sortStable() {
        Random random = new Random(1298);
        for (int round = 0; round < 2000; round++) {
            List<String> keyList = randomStrings(random, random.nextInt(12));
            String[] keys = keyList.toArray(new String[0]);
            Integer[] values = new Integer[keys.length];
            for (int i = 0; i < values.length; i++) {
                values[i] = i;
            }
            // A stable sort of the indexes by their keys: what the values must come out as.
            List<Integer> expected = new ArrayList<>(List.of(values));
            expected.sort(Comparator.comparing(keyList::get));
            String[] sortedKeys = keys.clone();
            Arrays.sort(sortedKeys);

            Sorting.sort(keys, values);

            assertArrayEquals(sortedKeys, keys, keyList.toString());
            assertEquals(expected, List.of(values), keyList.toString());
        }
    }

    @Test
    @DisplayName("sort without values orders the keys as Arrays.sort does")
    void sortKeys() {
        String[] keys = {"b", "", "a", "π", "a", "B"};

        Sorting.sort(keys);

        assertArrayEquals(new String[] {"", "B", "a", "a", "b", "π"}, keys);
        Sorting.sort(new String[0]);
    }

    private static List<String> randomStrings(Random random, int count) {
        List<String> strings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            strings.add(ALPHABET.get(random.nextInt(ALPHABET.size())));
        }
        return strings;
    }
}
