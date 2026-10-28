package io.github.brantunger.unruly.internal;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Strings in {@link String} order, sorted without {@link java.util.TreeSet}, {@link java.util.TreeMap} or the JDK's
 * sorts. Each of those has a static initializer the JVM doesn't run at startup: {@code TreeSet}'s and {@code TreeMap}'s
 * are one statement each, and {@code List.sort}, {@code Collections.sort} and {@code Arrays.sort} initialize
 * {@code Arrays$LegacyMergeSort} and {@code TimSort} or {@code ComparableTimSort} on their first use. A static
 * initializer that overflows leaves its class unusable for the life of the JVM, the application's own code included,
 * and the JVM's first use of one of these classes can be a rule's build, a run's options or a fact's first read deep
 * in a stack, with no engine built, so before the engine's first build has made room on the stack and initialized
 * {@code TreeSet} and {@code TreeMap} (#1298). The tags of a rule and of a run's options and the properties of a fact
 * class are sorted here instead, by insertion into a plain array, and kept in a {@link LinkedHashSet} or a
 * {@code LinkedHashMap} filled in that order, whose classes, and those of their iterators, views and unmodifiable
 * wrappers, have no static initializer, measured on JDK 21, 25 and 26; {@code HashSet} and {@code Collections}, which
 * have one, are initialized by the JVM at startup. <b>Internal:</b> this class may change in any release, and isn't
 * part of the API: its package isn't exported, and is the one the {@code api} and {@code core} packages share. It has
 * no static initializer of its own, so its own first use deep in a stack can't break it.
 */
public final class Sorting {

    private Sorting() {
    }

    /**
     * Returns some strings in {@link String} order, each once, as a set whose iteration order is that order and
     * which can't be modified. A collection that holds more elements than its {@code size()} says is read whole.
     *
     * @param strings The strings, none null
     * @return The strings, sorted, each once, unmodifiable
     * @throws NullPointerException if one of the strings is {@code null}
     */
    public static Set<String> set(Collection<String> strings) {
        // The size is a hint: a collection may answer it wrongly, and grown() makes room for what it holds.
        String[] sorted = new String[Math.max(0, strings.size())];
        int size = 0;
        for (String string : strings) {
            if (string == null) {
                throw new NullPointerException("strings must not contain null");
            }
            if (size == sorted.length) {
                sorted = grown(sorted);
            }
            // Where it goes: after every string before it, and nowhere if it is one of them.
            int at = size;
            while (at > 0 && sorted[at - 1].compareTo(string) >= 0) {
                at--;
            }
            if (at < size && sorted[at].compareTo(string) == 0) {
                continue;
            }
            for (int i = size; i > at; i--) {
                sorted[i] = sorted[i - 1];
            }
            sorted[at] = string;
            size++;
        }
        Set<String> set = new LinkedHashSet<>();
        for (int i = 0; i < size; i++) {
            set.add(sorted[i]);
        }
        return Collections.unmodifiableSet(set);
    }

    /**
     * Sorts some keys into {@link String} order, moving the value at each key's index along with it, so that the
     * values end up in the order of their keys. The sort is stable: two values whose keys are equal keep their order.
     *
     * @param keys   The keys, none null; sorted in place
     * @param values The values, as many as the keys; reordered in place
     */
    // Two arrays of the caller's, sorted in place: a varargs values parameter would have javac warn of an inexact
    // argument type at a call that passes an array of a subtype, such as a Method[].
    @SuppressWarnings("PMD.UseVarargs")
    public static void sort(String[] keys, Object[] values) {
        for (int i = 1; i < keys.length; i++) {
            String key = keys[i];
            Object value = values[i];
            int at = i;
            // Strictly greater, so an equal key stays after the one before it.
            while (at > 0 && keys[at - 1].compareTo(key) > 0) {
                keys[at] = keys[at - 1];
                values[at] = values[at - 1];
                at--;
            }
            keys[at] = key;
            values[at] = value;
        }
    }

    /**
     * Sorts some keys into {@link String} order, in place.
     *
     * @param keys The keys, none null, given as an array
     */
    public static void sort(String... keys) {
        sort(keys, new Object[keys.length]);
    }

    // A copy with room for more, by a loop: not Arrays.copyOf, which goes through Arrays, nor System.arraycopy.
    private static String[] grown(String... sorted) {
        String[] grown = new String[sorted.length * 2 + 1];
        for (int i = 0; i < sorted.length; i++) {
            grown[i] = sorted[i];
        }
        return grown;
    }
}
