package io.github.brantunger.unruly.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ReadOnlyFacts")
class ReadOnlyFactsTest {

    private final Map<String, Object> backing = new HashMap<>(Map.of("status", "DENIED"));
    private final Map<String, Object> facts = ReadOnlyFacts.forConditions(backing);

    @Test
    @DisplayName("reads through to the backing map")
    void readsThrough() {
        assertEquals("DENIED", facts.get("status"));
        assertTrue(facts.containsKey("status"));
        assertFalse(facts.containsKey("missing"));
        assertEquals(Map.of("status", "DENIED"), facts);
        assertEquals(1, facts.size());
    }

    @Test
    @DisplayName("put names the variable and leaves the backing map unchanged")
    void putThrows() {
        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> facts.put("status", "APPROVED"));
        assertEquals("Cannot assign or declare 'status' in a condition: conditions can't change facts or create "
                + "variables. Move assignments and declarations into the action.", ex.getMessage());
        assertEquals("DENIED", backing.get("status"));
    }

    @Test
    @DisplayName("put escapes and shortens the name it rejects, as the engine's messages show names")
    void putEscapesTheName() {
        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> facts.put("a\n" + "x".repeat(300), 1));

        assertEquals("Cannot assign or declare 'a\\n" + "x".repeat(198) + "... (102 more characters)' in a condition: "
                + "conditions can't change facts or create variables. Move assignments and declarations into the "
                + "action.", ex.getMessage());
        assertEquals("The facts passed to a RuleListener are read-only; 'null' can't be changed.",
                assertThrows(UnsupportedOperationException.class,
                        () -> ReadOnlyFacts.forListeners(backing).put(null, 1)).getMessage());
    }

    @Test
    @DisplayName("the view for listeners reads the same facts and rejects a write with a message about listeners")
    void listenerView() {
        Map<String, Object> listenerFacts = ReadOnlyFacts.forListeners(backing);

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> listenerFacts.put("status", "APPROVED"));

        assertEquals("The facts passed to a RuleListener are read-only; 'status' can't be changed.", ex.getMessage());
        assertEquals(Map.of("status", "DENIED"), listenerFacts);
        assertEquals("DENIED", backing.get("status"));
    }

    @Test
    @DisplayName("the view for actions reads the same facts and rejects a write, pointing to the output object")
    void actionView() {
        Map<String, Object> actionFacts = ReadOnlyFacts.forActions(backing);

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> actionFacts.put("status", "APPROVED"));

        assertEquals("The facts passed to an action are read-only; 'status' can't be changed. Put the result in the "
                + "output object instead.", ex.getMessage());
        assertEquals(Map.of("status", "DENIED"), actionFacts);
        assertEquals("DENIED", backing.get("status"));
    }

    @Test
    @DisplayName("remove and clear are rejected")
    void removeAndClearThrow() {
        assertThrows(UnsupportedOperationException.class, () -> facts.remove("status"));
        assertThrows(UnsupportedOperationException.class, facts::clear);
        assertEquals(1, backing.size());
    }

    /** What a write names, which decides its message. */
    private enum Names {
        /** A write to the fact, such as put. */
        WRITTEN,
        /** A removal of the fact. */
        REMOVED,
        /** No single fact, such as clear. */
        NOTHING
    }

    /** One write to the facts, the fact it names, if any, and whether it runs against no facts at all. */
    private record Write(String description, Consumer<Map<String, Object>> performed, Names names, String fact,
                         boolean empty) {
        @Override
        public String toString() {
            return (empty ? "no facts, " : "") + description;
        }
    }

    private static Write write(String description, Consumer<Map<String, Object>> write, Names names, String fact) {
        return new Write(description, write, names, fact, false);
    }

    private static Write nothing(String description, Consumer<Map<String, Object>> write) {
        return new Write(description, write, Names.NOTHING, null, false);
    }

    private static Write onNoFacts(String description, Consumer<Map<String, Object>> write) {
        return new Write(description, write, Names.NOTHING, null, true);
    }

    /** Every kind of write, whether or not it would change the facts: none of them may go through. */
    private static List<Write> writes() {
        return List.of(
                write("put(score, 1)", m -> m.put("score", 1), Names.WRITTEN, "score"),
                write("put(new, 1)", m -> m.put("new", 1), Names.WRITTEN, "new"),
                nothing("putAll({score: 1})", m -> m.putAll(Map.of("score", 1))),
                nothing("putAll({})", m -> m.putAll(Map.of())),
                write("putIfAbsent(score, 1), present", m -> m.putIfAbsent("score", 1), Names.WRITTEN, "score"),
                write("putIfAbsent(new, 1)", m -> m.putIfAbsent("new", 1), Names.WRITTEN, "new"),
                write("putIfAbsent(nothing, 1), null value", m -> m.putIfAbsent("nothing", 1), Names.WRITTEN,
                        "nothing"),
                write("remove(score)", m -> m.remove("score"), Names.REMOVED, "score"),
                write("remove(absent)", m -> m.remove("absent"), Names.REMOVED, "absent"),
                write("remove(score, 700)", m -> m.remove("score", 700), Names.REMOVED, "score"),
                write("remove(score, 1), no match", m -> m.remove("score", 1), Names.REMOVED, "score"),
                write("replace(score, 1)", m -> m.replace("score", 1), Names.WRITTEN, "score"),
                write("replace(absent, 1)", m -> m.replace("absent", 1), Names.WRITTEN, "absent"),
                write("replace(score, 700, 1)", m -> m.replace("score", 700, 1), Names.WRITTEN, "score"),
                write("replace(score, 1, 2), no match", m -> m.replace("score", 1, 2), Names.WRITTEN, "score"),
                write("compute(score, -> 1)", m -> m.compute("score", (k, v) -> 1), Names.WRITTEN, "score"),
                write("compute(score, -> null)", m -> m.compute("score", (k, v) -> null), Names.WRITTEN, "score"),
                write("compute(absent, -> null)", m -> m.compute("absent", (k, v) -> null), Names.WRITTEN,
                        "absent"),
                write("computeIfAbsent(new, -> 1)", m -> m.computeIfAbsent("new", k -> 1), Names.WRITTEN, "new"),
                write("computeIfAbsent(score, -> 1), present", m -> m.computeIfAbsent("score", k -> 1),
                        Names.WRITTEN, "score"),
                write("computeIfPresent(score, -> 1)", m -> m.computeIfPresent("score", (k, v) -> 1), Names.WRITTEN,
                        "score"),
                write("computeIfPresent(score, -> null)", m -> m.computeIfPresent("score", (k, v) -> null),
                        Names.WRITTEN, "score"),
                write("computeIfPresent(absent, -> 1)", m -> m.computeIfPresent("absent", (k, v) -> 1),
                        Names.WRITTEN, "absent"),
                write("merge(score, 1, -> 2)", m -> m.merge("score", 1, (x, y) -> 2), Names.WRITTEN, "score"),
                write("merge(score, 1, -> null)", m -> m.merge("score", 1, (x, y) -> null), Names.WRITTEN, "score"),
                write("merge(new, 1, -> 2)", m -> m.merge("new", 1, (x, y) -> 2), Names.WRITTEN, "new"),
                nothing("clear()", Map::clear),
                nothing("replaceAll(-> 0)", m -> m.replaceAll((k, v) -> 0)),
                write("entrySet() entry.setValue(0)", m -> m.entrySet().iterator().next().setValue(0), Names.WRITTEN,
                        "score"),
                write("entrySet() iterator remove()", m -> removeFirst(m.entrySet()), Names.REMOVED, "score"),
                nothing("entrySet() iterator remove() before next()", m -> m.entrySet().iterator().remove()),
                nothing("entrySet().add(new = 1)", m -> m.entrySet().add(new AbstractMap.SimpleEntry<>("new", 1))),
                nothing("entrySet().addAll([])", m -> m.entrySet().addAll(List.of())),
                write("entrySet().remove(score = 700)", m -> m.entrySet().remove(Map.entry("score", 700)),
                        Names.REMOVED, "score"),
                write("entrySet().remove(absent = 1)", m -> m.entrySet().remove(Map.entry("absent", 1)),
                        Names.REMOVED, "absent"),
                nothing("entrySet().remove(score), not an entry", m -> m.entrySet().remove("score")),
                nothing("entrySet().removeAll([])", m -> m.entrySet().removeAll(List.of())),
                nothing("entrySet().retainAll(itself)", m -> m.entrySet().retainAll(new ArrayList<>(m.entrySet()))),
                nothing("entrySet().removeIf(-> false)", m -> m.entrySet().removeIf(e -> false)),
                nothing("entrySet().clear()", m -> m.entrySet().clear()),
                write("keySet().remove(score)", m -> m.keySet().remove("score"), Names.REMOVED, "score"),
                write("keySet().remove(absent)", m -> m.keySet().remove("absent"), Names.REMOVED, "absent"),
                write("keySet() iterator remove()", m -> removeFirst(m.keySet()), Names.REMOVED, "score"),
                nothing("keySet() iterator remove() before next()", m -> m.keySet().iterator().remove()),
                nothing("keySet().add(new)", m -> m.keySet().add("new")),
                nothing("keySet().addAll([])", m -> m.keySet().addAll(List.of())),
                nothing("keySet().removeAll([score])", m -> m.keySet().removeAll(List.of("score"))),
                nothing("keySet().removeAll([])", m -> m.keySet().removeAll(List.of())),
                nothing("keySet().retainAll([])", m -> m.keySet().retainAll(List.of())),
                nothing("keySet().retainAll(itself)", m -> m.keySet().retainAll(Set.copyOf(m.keySet()))),
                nothing("keySet().removeIf(-> true)", m -> m.keySet().removeIf(k -> true)),
                nothing("keySet().removeIf(-> false)", m -> m.keySet().removeIf(k -> false)),
                nothing("keySet().clear()", m -> m.keySet().clear()),
                write("values() iterator remove()", m -> removeFirst(m.values()), Names.REMOVED, "score"),
                nothing("values() iterator remove() before next()", m -> m.values().iterator().remove()),
                nothing("values().add(1)", m -> m.values().add(1)),
                nothing("values().addAll([])", m -> m.values().addAll(List.of())),
                nothing("values().remove(700)", m -> m.values().remove(700)),
                nothing("values().remove(absent)", m -> m.values().remove("absent")),
                nothing("values().removeAll([])", m -> m.values().removeAll(List.of())),
                nothing("values().retainAll(itself)", m -> m.values().retainAll(new ArrayList<>(m.values()))),
                nothing("values().removeIf(-> true)", m -> m.values().removeIf(v -> true)),
                nothing("values().clear()", m -> m.values().clear()),
                onNoFacts("clear()", Map::clear),
                onNoFacts("replaceAll(-> 0)", m -> m.replaceAll((k, v) -> 0)),
                onNoFacts("entrySet().clear()", m -> m.entrySet().clear()),
                onNoFacts("keySet().clear()", m -> m.keySet().clear()),
                onNoFacts("values().clear()", m -> m.values().clear()));
    }

    private static void removeFirst(Collection<?> view) {
        Iterator<?> iterator = view.iterator();
        iterator.next();
        iterator.remove();
    }

    static Stream<Arguments> everyWriteToEveryView() {
        String advice = "conditions can't change facts or create variables. Move assignments and declarations into "
                + "the action.";
        String listener = "The facts passed to a RuleListener are read-only; '%s' can't be changed.";
        String action = "The facts passed to an action are read-only; '%s' can't be changed. Put the result in the "
                + "output object instead.";
        Stream.Builder<Arguments> rows = Stream.builder();
        for (Write write : writes()) {
            rows.add(Arguments.of("condition", write, message(write, "Cannot assign or declare '%s' in a condition: "
                    + advice, "Cannot remove '%s' in a condition: " + advice, "Conditions can't change facts or "
                    + "create variables. Move assignments and declarations into the action.")));
            rows.add(Arguments.of("listener", write, message(write, listener, listener,
                    "The facts passed to a RuleListener are read-only.")));
            rows.add(Arguments.of("action", write, message(write, action, action,
                    "The facts passed to an action are read-only. Put the result in the output object instead.")));
        }
        return rows.build();
    }

    private static String message(Write write, String written, String removed, String nothing) {
        return switch (write.names()) {
            case WRITTEN -> written.formatted(write.fact());
            case REMOVED -> removed.formatted(write.fact());
            case NOTHING -> nothing;
        };
    }

    private static Function<Map<String, Object>, Map<String, Object>> view(String name) {
        return switch (name) {
            case "condition" -> ReadOnlyFacts::forConditions;
            case "listener" -> ReadOnlyFacts::forListeners;
            default -> ReadOnlyFacts::forActions;
        };
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("everyWriteToEveryView")
    @DisplayName("every write through the map or its views is rejected with the view's message, even one that would"
            + " change nothing, and the facts are unchanged")
    void everyWriteRejected(String viewName, Write write, String message) {
        Map<String, Object> source = new LinkedHashMap<>();
        if (!write.empty()) {
            source.put("score", 700);
            source.put("nothing", null);
        }
        Map<String, Object> before = new LinkedHashMap<>(source);
        Map<String, Object> view = view(viewName).apply(source);

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> write.performed().accept(view));

        assertEquals(message, ex.getMessage());
        assertEquals(before, source);
    }

    @Test
    @DisplayName("a removal names the fact escaped and shortened, as a write does")
    void removalEscapesTheName() {
        String name = "a\n" + "x".repeat(300);

        assertEquals("Cannot remove 'a\\n" + "x".repeat(198) + "... (102 more characters)' in a condition: "
                + "conditions can't change facts or create variables. Move assignments and declarations into the "
                + "action.", assertThrows(UnsupportedOperationException.class, () -> facts.remove(name)).getMessage());
    }

    @Test
    @DisplayName("the key, value and entry views read the facts, and compare as the backing map's views do")
    void viewsReadThrough() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("score", 700);
        source.put("nothing", null);
        Map<String, Object> view = ReadOnlyFacts.forActions(source);

        assertEquals(source.keySet(), view.keySet());
        assertEquals(view.keySet(), source.keySet());
        assertEquals(source.keySet().hashCode(), view.keySet().hashCode());
        assertEquals(source.entrySet(), view.entrySet());
        assertEquals(view.entrySet(), source.entrySet());
        assertEquals(source.entrySet().hashCode(), view.entrySet().hashCode());
        assertEquals(new ArrayList<>(source.values()), new ArrayList<>(view.values()));
        assertEquals(2, view.values().size());
        assertTrue(view.values().contains(null));
        assertFalse(view.values().contains(1));
        assertTrue(view.keySet().contains("nothing"));
        assertTrue(view.entrySet().contains(new AbstractMap.SimpleEntry<>("score", 700)));
        assertFalse(view.entrySet().contains(Map.entry("score", 1)));
        assertEquals(source, view);
        assertEquals(view, source);
        assertEquals(source.hashCode(), view.hashCode());
        assertEquals("{score=700, nothing=null}", view.toString());
        Map.Entry<String, Object> first = view.entrySet().iterator().next();
        assertEquals("score", first.getKey());
        assertEquals(700, first.getValue());
        assertEquals(Map.entry("score", 700), first);
        source.put("score", 710);
        assertEquals(710, view.get("score"), "the view reads the facts as they are now");
        assertEquals(710, view.entrySet().iterator().next().getValue());
    }

    @Test
    @DisplayName("an entry read before a fact changes sees the change, as the backing map's entry does")
    void entriesReadThrough() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("score", 700);
        Map.Entry<String, Object> entry = ReadOnlyFacts.forListeners(source).entrySet().iterator().next();

        source.put("score", 1);

        assertEquals(1, entry.getValue());
        assertEquals(Map.entry("score", 1), entry);
    }

    @Test
    @DisplayName("an entry compares, hashes and prints as a map entry does")
    void entryObjectMethods() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("score", 700);
        source.put("nothing", null);
        Iterator<Map.Entry<String, Object>> entries = ReadOnlyFacts.forConditions(source).entrySet().iterator();
        Map.Entry<String, Object> score = entries.next();
        Map.Entry<String, Object> nothing = entries.next();

        assertEquals(score, Map.entry("score", 700));
        assertNotEquals(score, Map.entry("other", 700));
        assertNotEquals(score, Map.entry("score", 701));
        assertNotEquals(score, "score=700");
        assertEquals(Map.entry("score", 700).hashCode(), score.hashCode());
        assertEquals(new AbstractMap.SimpleEntry<>("nothing", null), nothing);
        assertEquals(new AbstractMap.SimpleEntry<>("nothing", null).hashCode(), nothing.hashCode());
        assertEquals("score=700", score.toString());
        assertEquals("nothing=null", nothing.toString());
    }

    @Test
    @DisplayName("the views are created once, and forEach and getOrDefault read the facts")
    void viewsCachedAndReadsDelegated() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("score", 700);
        source.put("nothing", null);
        Map<String, Object> view = ReadOnlyFacts.forActions(source);
        Map<String, Object> seen = new LinkedHashMap<>();

        view.forEach(seen::put);

        assertEquals(source, seen);
        assertSame(view.keySet(), view.keySet());
        assertSame(view.values(), view.values());
        assertEquals(view.values(), view.values());
        assertSame(view.entrySet(), view.entrySet());
        assertEquals(700, view.getOrDefault("score", 1));
        assertEquals(1, view.getOrDefault("absent", 1));
        assertNull(view.getOrDefault("nothing", 1), "a fact whose value is null is still there");
    }
}
