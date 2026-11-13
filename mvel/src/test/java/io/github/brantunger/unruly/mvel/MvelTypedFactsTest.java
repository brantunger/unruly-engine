package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactStore;
import io.github.brantunger.unruly.api.FactType;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleCompilationException;
import io.github.brantunger.unruly.api.RuleExecutionException;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1411: with its {@code strongTyping} option on, MVEL is told the type arguments of a fact declared as a collection
 * or a map with them, so such a fact no longer fails {@code load()} as a dynamic one, and a misspelled member of an
 * element does. A fact whose type arguments MVEL can't use stays refused, named with them: one whose class's type
 * parameters aren't {@code Collection}'s or {@code Map}'s under the same names, and one with an argument MVEL can't
 * check.
 */
// The collection classes below are only ever declared as a fact's type, never serialized.
@SuppressWarnings("serial")
@DisplayName("#1411: with strongTyping on, MVEL checks a rule against a collection or map fact's type arguments")
class MvelTypedFactsTest {

    private static final Type LIST_OF_STRINGS = new FactType<List<String>>() {
    }.type();
    private static final Type MAP_OF_APPLICANTS = new FactType<Map<String, Applicant>>() {
    }.type();

    /** An applicant as a record, whose components MVEL reads as properties. */
    public record Applicant(int creditScore, String name) {
    }

    /** A colour, for a key that is an enum and a value a rule compares with a string. */
    public enum Color { RED, GREEN }

    /** A generic bean, which MVEL checks by its class, whatever its type argument. */
    public static final class Box<T> {

        private final T value;

        public Box(T value) {
            this.value = value;
        }

        public T getValue() {
            return value;
        }
    }

    /** An output object with real properties, so an action can be type-checked. */
    public static final class Decision {

        private int score;
        private String label = "";

        public int getScore() {
            return score;
        }

        public void setScore(int score) {
            this.score = score;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }
    }

    /** A list whose one type parameter is its element, under {@code Collection}'s name for it. */
    public static class Strings<E> extends ArrayList<E> {
    }

    /** A list with no type parameter. */
    public static class Tags extends ArrayList<String> {
    }

    /** A map whose one type parameter is its value. */
    public static class ByName<V> extends HashMap<String, V> {
    }

    /** A map whose type parameters are its value, then its key. */
    public static class Flipped<V, K> extends HashMap<K, V> {
    }

    /** A list whose type parameter isn't its element. */
    public static class Labelled<L> extends ArrayList<String> {
    }

    /** A list whose type parameter is its element, under another name. */
    public static class Things<T> extends ArrayList<T> {
    }

    /** A map whose type parameters are its key and value, under other names. */
    public static class Lookup<A, B> extends HashMap<A, B> {
    }

    /** A map whose type parameters are its key and value, under each other's names. */
    public static class Odd<V, K> extends HashMap<V, K> {
    }

    /** A list whose type parameter has the element's name, and isn't the element. */
    public static class Named<E> extends ArrayList<String> {
    }

    /** A list whose type parameter has the element's name, and whose superclass was written without it. */
    @SuppressWarnings("rawtypes")
    public static class Loose<E> extends ArrayList {
    }

    /** A list whose type parameter has the element's name, and is the type parameter of a list that isn't. */
    public static class Relabelled<E> extends Labelled<E> {
    }

    /** A list of its second type parameter, whose first, under the element's name, is what its methods return. */
    public static class Mid<E, X> extends ArrayList<X> {

        private E label;

        public E label() {
            return label;
        }

        public void label(E newLabel) {
            this.label = newLabel;
        }
    }

    /** A list whose type parameter is its element, under a name its superclass uses for a string. */
    public static class Top<E> extends Mid<String, E> {
    }

    /** A list with a method that returns a type variable of the method's own, under the element's name. */
    public static class Pile<E> extends ArrayList<E> {

        @SuppressWarnings("unchecked")
        public <E> E other() {
            return (E) Integer.valueOf(7);
        }
    }

    /** A map with a method that returns a type variable of the method's own, under the value's name. */
    public static class Bag<K, V> extends HashMap<K, V> {

        @SuppressWarnings("unchecked")
        public <V> V typed(K key) {
            return (V) Integer.valueOf(key.hashCode());
        }
    }

    /**
     * A list with methods that return the element's name inside another type, or a type variable of the method's own
     * under another name, neither of which MVEL checks as the element.
     */
    public static class Sorted<E> extends ArrayList<E> {

        @SuppressWarnings("unchecked")
        public <E> List<E> others() {
            return (List<E>) List.of(7);
        }

        @SuppressWarnings("unchecked")
        public <T> T as() {
            return (T) Integer.valueOf(7);
        }
    }

    /** Something held, under the name a collection uses for its element. */
    public interface Holder<E> {

        default E held() {
            return null;
        }
    }

    /** A list whose type parameter is its element, under a name an interface it implements uses for a number. */
    public static class Held<E> extends ArrayList<E> implements Holder<Integer> {
    }

    private static Rule rule(String condition, String action) {
        return Rule.builder().ruleName("r").condition(condition).action(action).build();
    }

    /** A builder with everything strong typing needs but the facts, and the applicant's class imported. */
    private static RulesEngineBuilder<Decision> strongTyping() {
        return RulesEngineBuilder.allMatches(Decision::new)
                .outputType(Decision.class)
                .imports(Applicant.class.getName())
                .requireDeclaredFacts()
                .option("mvel", "strongTyping", "true");
    }

    /** An engine with the two facts docs/fact-types.md declares, and the two more its examples read them with. */
    private static RulesEngine<Decision> documentedEngine() {
        return strongTyping().fact("tags", LIST_OF_STRINGS).fact("applicantsById", MAP_OF_APPLICANTS)
                .fact("i", Integer.class).fact("id", String.class).build();
    }

    private static FactStore<Object> documentedFacts() {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("tags", List.of("a", "bb"));
        facts.setValue("applicantsById", Map.of("a1", new Applicant(760, "Alex"), "b2", new Applicant(640, "Bo")));
        facts.setValue("i", 1);
        facts.setValue("id", "b2");
        return facts;
    }

    private static FactStore<Object> fact(String name, Object value) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue(name, value);
        return facts;
    }

    private static <T> Type listOfTypeVariable() {
        return new FactType<List<T>>() {
        }.type();
    }

    /** Loads one rule that doesn't compile, and returns the message load() fails with. */
    private static String loadFailure(RulesEngine<Decision> engine, String condition, String action) {
        return assertThrows(RuleCompilationException.class, () -> engine.load(List.of(rule(condition, action))))
                .getMessage();
    }

    private static void assertRefused(Type declared, String named) {
        RulesEngine<Decision> engine = strongTyping().fact("x", declared).build();

        assertEquals("The 'mvel' expression language failed to create a compiler: MVEL's strongTyping option is on, "
                + "but strong typing can't apply, because fact 'x' is declared as " + named + ", whose members MVEL "
                + "can't check", loadFailure(engine, "true", "output.score = 1"));
    }

    private static String name(Class<?> type) {
        return type.getName();
    }

    /** A parameterized type as another library's type token gives one, with any number of arguments of any kind. */
    private static ParameterizedType parameterized(Class<?> raw, Type... arguments) {
        return new ParameterizedType() {
            @Override
            public Type[] getActualTypeArguments() {
                return arguments.clone();
            }

            @Override
            public Type getRawType() {
                return raw;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }

            @Override
            public String toString() {
                return raw.getName() + Arrays.stream(arguments).map(Type::getTypeName)
                        .collect(Collectors.joining(", ", "<", ">"));
            }
        };
    }

    /** A wildcard as another library's type token gives one, with no upper bound, which the JDK's own always has. */
    private static WildcardType unboundedWildcard() {
        return new WildcardType() {
            @Override
            public Type[] getUpperBounds() {
                return new Type[0];
            }

            @Override
            public Type[] getLowerBounds() {
                return new Type[0];
            }

            @Override
            public String toString() {
                return "?";
            }
        };
    }

    @Test
    @DisplayName("a List<String> fact loads and runs a rule that reads its elements, in every form the docs list")
    void aListOfStringsLoadsAndRuns() {
        try (RulesEngine<Decision> engine = documentedEngine()) {
            engine.load(List.of(rule("tags[0].length() > 0 && tags.get(0).length() == 1 && tags.size() == 2",
                    "output.label = tags[i].toUpperCase()")));

            assertEquals("BB", engine.run(documentedFacts()).getLabel());
        }
    }

    @Test
    @DisplayName("a misspelled method of a List<String> fact's element fails load(), with its line and column")
    void aMisspelledElementMethodFailsLoading() {
        try (RulesEngine<Decision> engine = documentedEngine()) {
            assertEquals("Condition for rule 'r' failed to compile at line 1, column 9: unable to resolve method "
                            + "using strict-mode: java.lang.String.lenght()",
                    loadFailure(engine, "tags[0].lenght() > 0", "output.score = 1"));
            assertEquals("Action for rule 'r' failed to compile at line 1, column 24: unable to resolve method using "
                            + "strict-mode: java.lang.String.toUpperCas()",
                    loadFailure(engine, "true", "output.label = tags[0].toUpperCas()"));
        }
    }

    @Test
    @DisplayName("a misspelled method of an element on the third line of an action fails load() at line 3")
    void aMisspelledElementMethodOnLineThreeFailsLoading() {
        try (RulesEngine<Decision> engine = documentedEngine()) {
            assertEquals("Action for rule 'r' failed to compile at line 3, column 6: unable to resolve method using "
                            + "strict-mode: java.lang.String.lenght()",
                    loadFailure(engine, "true", "output.label = 'a';\noutput.score = tags[1]\n    .lenght()"));
        }
    }

    @Test
    @DisplayName("a Map<String, Applicant> fact loads and runs a rule that reads its values, in every form the docs "
            + "list, and a misspelled property of a value fails load()")
    void aMapOfBeansIsChecked() {
        try (RulesEngine<Decision> engine = documentedEngine()) {
            engine.load(List.of(rule("applicantsById['a1'].creditScore > 700 && applicantsById.a1.name == 'Alex'",
                    "output.score = applicantsById.get('a1').creditScore + applicantsById[id].creditScore")));

            assertEquals(1400, engine.run(documentedFacts()).getScore());
            assertEquals("Condition for rule 'r' failed to compile at line 1, column 19: unqualified type in strict "
                            + "mode for: creditScor",
                    loadFailure(engine, "applicantsById.a1.creditScor > 700", "output.score = 1"));
            assertEquals("Condition for rule 'r' failed to compile at line 1, column 20: unqualified type in strict "
                            + "mode for: creditScor",
                    loadFailure(engine, "applicantsById[id].creditScor > 700", "output.score = 1"));
        }
    }

    @Test
    @DisplayName("a Set<String> fact loads and runs a rule with a typed foreach over it, and a misspelled method of "
            + "the loop's variable fails load()")
    void aSetOfStringsIsChecked() {
        try (RulesEngine<Decision> engine = strongTyping().fact("tags", new FactType<Set<String>>() {
        }).build()) {
            engine.load(List.of(rule("tags.contains('a')",
                    "n = 0; foreach (String t : tags) { n += t.length() }; output.score = n")));

            assertEquals(3, engine.run(fact("tags", new LinkedHashSet<>(List.of("a", "bb")))).getScore());
            assertEquals("Action for rule 'r' failed to compile at line 1, column 43: unable to resolve method using "
                            + "strict-mode: java.lang.String.lenght()",
                    loadFailure(engine, "true", "n = 0; foreach (String t : tags) { n += t.lenght() }"));
        }
    }

    @Test
    @DisplayName("an ArrayList<String> fact is checked as a List<String> is")
    void anArrayListOfStringsIsChecked() {
        try (RulesEngine<Decision> engine = strongTyping().fact("tags", new FactType<ArrayList<String>>() {
        }).build()) {
            engine.load(List.of(rule("tags[0].length() > 0", "output.label = tags[0]")));

            assertEquals("a", engine.run(fact("tags", new ArrayList<>(List.of("a")))).getLabel());
            assertEquals("Condition for rule 'r' failed to compile at line 1, column 9: unable to resolve method "
                            + "using strict-mode: java.lang.String.lenght()",
                    loadFailure(engine, "tags[0].lenght() > 0", "output.score = 1"));
        }
    }

    @Test
    @DisplayName("a Map<Integer, String> fact is checked: a key follows the same rule as a value")
    void aMapWithIntegerKeysIsChecked() {
        try (RulesEngine<Decision> engine = strongTyping().fact("names", new FactType<Map<Integer, String>>() {
        }).build()) {
            engine.load(List.of(rule("names[1].length() == 1", "output.label = names.get(1)")));

            assertEquals("b", engine.run(fact("names", Map.of(1, "b"))).getLabel());
            assertEquals("Condition for rule 'r' failed to compile at line 1, column 10: unable to resolve method "
                            + "using strict-mode: java.lang.String.lenght()",
                    loadFailure(engine, "names[1].lenght() == 1", "output.score = 1"));
        }
    }

    @Test
    @DisplayName("a List<String[]> fact is checked: an array of a class MVEL can check is one too")
    void aListOfStringArraysIsChecked() {
        try (RulesEngine<Decision> engine = strongTyping().fact("rows", new FactType<List<String[]>>() {
        }).build()) {
            engine.load(List.of(rule("rows[0][0].length() == 3", "output.score = rows[0].length")));
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[] {"abc", "d"});

            assertEquals(2, engine.run(fact("rows", rows)).getScore());
            assertEquals("Condition for rule 'r' failed to compile at line 1, column 12: unable to resolve method "
                            + "using strict-mode: java.lang.String.lenght()",
                    loadFailure(engine, "rows[0][0].lenght() == 3", "output.score = 1"));
        }
    }

    @Test
    @DisplayName("a List<? extends String> and a Map<String, ? extends Applicant> fact, as Kotlin writes them, are "
            + "checked by the wildcard's upper bound")
    void kotlinsWildcardsAreCheckedByTheirUpperBound() {
        try (RulesEngine<Decision> engine = strongTyping().fact("tags", new FactType<List<? extends String>>() {
        }).fact("applicantsById", new FactType<Map<String, ? extends Applicant>>() {
        }).build()) {
            engine.load(List.of(rule("tags[0].length() > 0 && applicantsById.a1.creditScore > 700",
                    "output.label = tags[0]; output.score = applicantsById['a1'].creditScore")));
            FactStore<Object> facts = fact("tags", List.of("a"));
            facts.setValue("applicantsById", Map.of("a1", new Applicant(760, "Alex")));
            Decision decision = engine.run(facts);

            assertEquals("a", decision.getLabel());
            assertEquals(760, decision.getScore());
            assertEquals("Condition for rule 'r' failed to compile at line 1, column 9: unable to resolve method "
                            + "using strict-mode: java.lang.String.lenght()",
                    loadFailure(engine, "tags[0].lenght() > 0", "output.score = 1"));
            assertEquals("Condition for rule 'r' failed to compile at line 1, column 19: unqualified type in strict "
                            + "mode for: creditScor",
                    loadFailure(engine, "applicantsById.a1.creditScor > 700", "output.score = 1"));
        }
    }

    @Test
    @DisplayName("every collection and map type whose own type parameters are Collection's or Map's is accepted")
    void collectionTypesWhoseTypeParametersLineUpAreAccepted() {
        Map<Type, Object> values = new LinkedHashMap<>();
        values.put(LIST_OF_STRINGS, List.of("a"));
        values.put(new FactType<Set<String>>() {
        }.type(), Set.of("a"));
        values.put(new FactType<ArrayList<String>>() {
        }.type(), new ArrayList<>(List.of("a")));
        values.put(MAP_OF_APPLICANTS, Map.of());
        values.put(new FactType<Collection<String>>() {
        }.type(), List.of("a"));
        values.put(new FactType<Deque<String>>() {
        }.type(), new ArrayDeque<>(List.of("a")));
        values.put(new FactType<Strings<String>>() {
        }.type(), new Strings<>());
        values.put(new FactType<HashMap<String, Applicant>>() {
        }.type(), new HashMap<>());
        values.put(new FactType<LinkedHashMap<String, Applicant>>() {
        }.type(), new LinkedHashMap<>());
        values.put(new FactType<SortedMap<String, Applicant>>() {
        }.type(), new TreeMap<>());
        values.put(new FactType<EnumMap<Color, Applicant>>() {
        }.type(), new EnumMap<>(Color.class));

        values.forEach((declared, value) -> {
            try (RulesEngine<Decision> engine = strongTyping().fact("x", declared).build()) {
                engine.load(List.of(rule("x.size() < 2", "output.score = x.size() + 5")));

                assertEquals(5 + (value instanceof Map ? 0 : ((Collection<?>) value).size()),
                        engine.run(fact("x", value)).getScore(), declared.getTypeName());
            }
        });
    }

    @Test
    @DisplayName("an element of a list that extends ArrayList<E> under the name E, and a value of an EnumMap, are "
            + "checked")
    void elementsOfASubclassAndAnEnumMapAreChecked() {
        try (RulesEngine<Decision> engine = strongTyping().fact("tags", new FactType<Strings<String>>() {
        }).fact("byColor", new FactType<EnumMap<Color, Applicant>>() {
        }).fact("color", Color.class).build()) {
            engine.load(List.of(rule("tags[0].length() > 0 && tags.get(0).length() == 1",
                    "output.score = byColor[color].creditScore")));
            Strings<String> tags = new Strings<>();
            tags.add("a");
            FactStore<Object> facts = fact("tags", tags);
            facts.setValue("byColor", new EnumMap<>(Map.of(Color.RED, new Applicant(760, "Alex"))));
            facts.setValue("color", Color.RED);

            assertEquals(760, engine.run(facts).getScore());
            assertEquals("Condition for rule 'r' failed to compile at line 1, column 9: unable to resolve method "
                            + "using strict-mode: java.lang.String.lenght()",
                    loadFailure(engine, "tags[0].lenght() > 0", "output.score = 1"));
            assertEquals("Condition for rule 'r' failed to compile at line 1, column 16: unqualified type in strict "
                            + "mode for: creditScor",
                    loadFailure(engine, "byColor[color].creditScor > 0", "output.score = 1"));
        }
    }

    @Test
    @DisplayName("every session's copy of a rule over a typed fact is type-checked, so concurrent runs give one "
            + "result")
    void concurrentRunsGiveOneResult() throws Exception {
        try (RulesEngine<Decision> engine = documentedEngine()) {
            engine.load(List.of(rule("tags[0].length() > 0 && applicantsById.a1.creditScore > 700",
                    "output.score = applicantsById[id].creditScore + tags[i].length()")));
            ExecutorService threads = Executors.newFixedThreadPool(4);
            List<Future<Decision>> runs = new ArrayList<>();

            for (int run = 0; run < 16; run++) {
                runs.add(threads.submit(() -> engine.run(documentedFacts())));
            }

            for (Future<Decision> run : runs) {
                assertEquals(642, run.get(30, TimeUnit.SECONDS).getScore());
            }
            threads.shutdownNow();
        }
    }

    @Test
    @DisplayName("a List or Map fact declared without type arguments, or as a class with none to give, is refused "
            + "with the message it always had")
    void aCollectionWithoutTypeArgumentsIsRefusedAsBefore() {
        assertRefused(List.class, "java.util.List");
        assertRefused(Map.class, "java.util.Map");
        assertRefused(Tags.class, name(Tags.class));
        assertRefused(Strings.class, name(Strings.class));
    }

    @Test
    @DisplayName("a List fact whose type argument MVEL can't check is refused, named with its type arguments")
    @SuppressWarnings("rawtypes")
    void aListWithAnArgumentMvelCantCheckIsRefused() {
        assertRefused(new FactType<List<Object>>() {
        }.type(), "java.util.List<java.lang.Object>");
        assertRefused(new FactType<List<?>>() {
        }.type(), "java.util.List<?>");
        assertRefused(new FactType<List<? super String>>() {
        }.type(), "java.util.List<? super java.lang.String>");
        assertRefused(new FactType<List<? extends List<String>>>() {
        }.type(), "java.util.List<? extends java.util.List<java.lang.String>>");
        assertRefused(MvelTypedFactsTest.<String>listOfTypeVariable(), "java.util.List<T>");
        assertRefused(new FactType<List<List<String>>>() {
        }.type(), "java.util.List<java.util.List<java.lang.String>>");
        assertRefused(new FactType<List<Map<String, String>>>() {
        }.type(), "java.util.List<java.util.Map<java.lang.String, java.lang.String>>");
        assertRefused(new FactType<List<Box<String>>>() {
        }.type(), "java.util.List<" + name(Box.class) + "<java.lang.String>>");
        assertRefused(new FactType<List<Box>>() {
        }.type(), "java.util.List<" + name(Box.class) + ">");
        assertRefused(new FactType<List<Box[]>>() {
        }.type(), "java.util.List<" + name(Box.class) + "[]>");
        assertRefused(new FactType<List<Object[]>>() {
        }.type(), "java.util.List<java.lang.Object[]>");
        assertRefused(new FactType<List<Tags>>() {
        }.type(), "java.util.List<" + name(Tags.class) + ">");
    }

    @Test
    @DisplayName("a Map fact whose key or value MVEL can't check is refused, named with its type arguments")
    void aMapWithAnArgumentMvelCantCheckIsRefused() {
        assertRefused(new FactType<Map<String, Object>>() {
        }.type(), "java.util.Map<java.lang.String, java.lang.Object>");
        assertRefused(new FactType<Map<Object, String>>() {
        }.type(), "java.util.Map<java.lang.Object, java.lang.String>");
        assertRefused(new FactType<Map<String, List<String>>>() {
        }.type(), "java.util.Map<java.lang.String, java.util.List<java.lang.String>>");
        assertRefused(new FactType<Map<List<String>, String>>() {
        }.type(), "java.util.Map<java.util.List<java.lang.String>, java.lang.String>");
        assertRefused(new FactType<HashMap<String, ?>>() {
        }.type(), "java.util.HashMap<java.lang.String, ?>");
    }

    @Test
    @DisplayName("a fact declared as a collection or map class whose type parameters aren't Collection's or Map's, in "
            + "order and under their names, is refused, named with its type arguments")
    void aSubclassWhoseTypeParametersDontLineUpIsRefused() {
        String applicant = name(Applicant.class);
        assertRefused(new FactType<ByName<Applicant>>() {
        }.type(), name(ByName.class) + "<" + applicant + ">");
        assertRefused(new FactType<Flipped<Applicant, String>>() {
        }.type(), name(Flipped.class) + "<" + applicant + ", java.lang.String>");
        assertRefused(new FactType<Labelled<Integer>>() {
        }.type(), name(Labelled.class) + "<java.lang.Integer>");
        assertRefused(new FactType<Things<String>>() {
        }.type(), name(Things.class) + "<java.lang.String>");
        assertRefused(new FactType<Lookup<String, Applicant>>() {
        }.type(), name(Lookup.class) + "<java.lang.String, " + applicant + ">");
        assertRefused(new FactType<Odd<String, Applicant>>() {
        }.type(), name(Odd.class) + "<java.lang.String, " + applicant + ">");
        assertRefused(new FactType<Named<Integer>>() {
        }.type(), name(Named.class) + "<java.lang.Integer>");
        assertRefused(new FactType<Loose<String>>() {
        }.type(), name(Loose.class) + "<java.lang.String>");
        assertRefused(new FactType<Relabelled<String>>() {
        }.type(), name(Relabelled.class) + "<java.lang.String>");
    }

    @Test
    @DisplayName("a list whose superclass uses its type parameter's name for another type is refused: MVEL would "
            + "check what an inherited method returns as the element")
    void aSubclassWhoseSuperclassReusesItsTypeParametersNameIsRefused() {
        assertRefused(new FactType<Top<Applicant>>() {
        }.type(), name(Top.class) + "<" + name(Applicant.class) + ">");
    }

    @Test
    @DisplayName("a list that implements an interface using its type parameter's name for another type is refused")
    void aSubclassWhoseInterfaceReusesItsTypeParametersNameIsRefused() {
        assertRefused(new FactType<Held<String>>() {
        }.type(), name(Held.class) + "<java.lang.String>");
    }

    @Test
    @DisplayName("a list with a method that returns a type variable of its own under the element's name is refused: "
            + "MVEL would check what it returns as the element")
    void aListWhoseMethodReturnsItsOwnTypeVariableUnderTheElementsNameIsRefused() {
        assertRefused(new FactType<Pile<String>>() {
        }.type(), name(Pile.class) + "<java.lang.String>");
    }

    @Test
    @DisplayName("a map with a method that returns a type variable of its own under the value's name is refused")
    void aMapWhoseMethodReturnsItsOwnTypeVariableUnderTheValuesNameIsRefused() {
        assertRefused(new FactType<Bag<String, String>>() {
        }.type(), name(Bag.class) + "<java.lang.String, java.lang.String>");
    }

    @Test
    @DisplayName("a list with a method that returns the element's name inside another type, or its own type variable "
            + "under another name, is accepted: MVEL checks neither as the element")
    void aListWhoseMethodsReturnNothingMvelChecksAsTheElementIsAccepted() {
        try (RulesEngine<Decision> engine = strongTyping().fact("x", new FactType<Sorted<String>>() {
        }).build()) {
            Sorted<String> sorted = new Sorted<>();
            sorted.add("abc");

            engine.load(List.of(rule("x[0].length() == 3 && x.others().size() == 1 && x.as() == 7",
                    "output.score = 1")));
            assertEquals(1, engine.run(fact("x", sorted)).getScore());
            assertEquals("Condition for rule 'r' failed to compile at line 1, column 15: unable to resolve method "
                            + "using strict-mode: java.util.List.length()",
                    loadFailure(engine, "x.others()[0].length() == 1", "output.score = 1"));
            assertEquals("Condition for rule 'r' failed to compile at line 1, column 8: unable to resolve method "
                            + "using strict-mode: java.lang.Object.length()",
                    loadFailure(engine, "x.as().length() == 1", "output.score = 1"));
        }
    }

    @Test
    @DisplayName("a List declared by another library's type with no type argument is refused, not an error")
    void aListWithNoTypeArgumentIsRefused() {
        assertRefused(parameterized(List.class), "java.util.List<>");
    }

    @Test
    @DisplayName("a List declared by another library's type with two type arguments is refused, not an error")
    void aListWithTwoTypeArgumentsIsRefused() {
        assertRefused(parameterized(List.class, String.class, String.class),
                "java.util.List<java.lang.String, java.lang.String>");
    }

    @Test
    @DisplayName("a Map declared by another library's type with one type argument is refused, not an error")
    void aMapWithOneTypeArgumentIsRefused() {
        assertRefused(parameterized(Map.class, String.class), "java.util.Map<java.lang.String>");
    }

    @Test
    @DisplayName("a Map declared by another library's type with three type arguments is refused, not an error")
    void aMapWithThreeTypeArgumentsIsRefused() {
        assertRefused(parameterized(Map.class, String.class, String.class, String.class),
                "java.util.Map<java.lang.String, java.lang.String, java.lang.String>");
    }

    @Test
    @DisplayName("a List of another library's wildcard with no upper bound is refused, not an error")
    void aWildcardWithNoUpperBoundIsRefused() {
        assertRefused(parameterized(List.class, unboundedWildcard()), "java.util.List<?>");
    }

    @Test
    @DisplayName("a List declared by another library's type with a primitive type argument is refused")
    void aPrimitiveTypeArgumentIsRefused() {
        assertRefused(parameterized(List.class, int.class), "java.util.List<int>");
    }

    @Test
    @DisplayName("a List declared by another library's type with void for its type argument is refused")
    void aVoidTypeArgumentIsRefused() {
        assertRefused(parameterized(List.class, void.class), "java.util.List<void>");
    }

    @Test
    @DisplayName("known limitation: a misspelled property of a typed List or Set itself, not of an element, loads, and "
            + "fails every run")
    void knownLimitationAMisspelledPropertyOfTheCollectionItselfFailsOnlyTheRun() {
        try (RulesEngine<Decision> engine = documentedEngine()) {
            engine.load(List.of(rule("tags.frist == 'a'", "output.score = 1")));
            RuleExecutionException thrown = assertThrows(RuleExecutionException.class,
                    () -> engine.run(documentedFacts()));
            assertTrue(thrown.getMessage().contains("could not access: frist; in class: "), thrown.getMessage());
        }
        try (RulesEngine<Decision> engine = strongTyping().fact("tags", new FactType<Set<String>>() {
        }).build()) {
            engine.load(List.of(rule("tags.bogus.length() > 0", "output.score = 1")));
            RuleExecutionException thrown = assertThrows(RuleExecutionException.class,
                    () -> engine.run(fact("tags", Set.of("a"))));
            assertTrue(thrown.getMessage().contains("could not access: bogus; in class: "), thrown.getMessage());
        }
    }

    @Test
    @DisplayName("a member of a map's value read in a foreach body fails load(), and a typed local or a loop over the "
            + "values loads, as docs/fact-types.md says")
    void aValuesMemberInAForeachBodyFailsLoading() {
        try (RulesEngine<Decision> engine = documentedEngine()) {
            assertEquals("Action for rule 'r' failed to compile at line 1, column 78: unqualified type in strict mode "
                            + "for: creditScore",
                    loadFailure(engine, "true", "n = 0; foreach (String k : applicantsById.keySet()) "
                            + "{ n += applicantsById[k].creditScore }; output.score = n"));

            engine.load(List.of(rule("true", "n = 0; foreach (String k : applicantsById.keySet()) "
                    + "{ Applicant a = applicantsById[k]; n += a.creditScore }; output.score = n")));
            assertEquals(1400, engine.run(documentedFacts()).getScore());
            engine.load(List.of(rule("true", "n = 0; foreach (Applicant a : applicantsById.values()) "
                    + "{ n += a.creditScore }; output.score = n")));
            assertEquals(1400, engine.run(documentedFacts()).getScore());
        }
    }

    @Test
    @DisplayName("an element's method called in a for body fails load(), a foreach with a typed variable loads, and a "
            + "typed local in the for body loads and then fails the run, as docs/fact-types.md says")
    void anElementsMethodInAForBodyFailsLoading() {
        try (RulesEngine<Decision> engine = documentedEngine()) {
            assertEquals("Action for rule 'r' failed to compile at line 1, column 61: unable to resolve method using "
                            + "strict-mode: java.lang.Object.length()",
                    loadFailure(engine, "true", "n = 0; for (int j = 0; j < tags.size(); j++) "
                            + "{ n += tags[j].length() }; output.score = n"));

            engine.load(List.of(rule("true", "n = 0; foreach (String t : tags) { n += t.length() }; "
                    + "output.score = n")));
            assertEquals(3, engine.run(documentedFacts()).getScore());
            engine.load(List.of(rule("true", "n = 0; for (int j = 0; j < tags.size(); j++) "
                    + "{ String s = tags[j]; n += s.length() }; output.score = n")));
            RuleExecutionException thrown = assertThrows(RuleExecutionException.class,
                    () -> engine.run(documentedFacts()));
            assertTrue(thrown.getMessage().contains("variable already defined within scope"), thrown.getMessage());
        }
    }

    @Test
    @DisplayName("an element read with get and an argument that isn't a literal fails load(), and the same read with "
            + "[] loads, as docs/fact-types.md says")
    void getWithAVariableFailsLoading() {
        try (RulesEngine<Decision> engine = documentedEngine()) {
            assertEquals("Condition for rule 'r' failed to compile at line 1, column 24: unqualified type in strict "
                            + "mode for: creditScore",
                    loadFailure(engine, "applicantsById.get(id).creditScore > 600", "output.score = 1"));
            assertEquals("Condition for rule 'r' failed to compile at line 1, column 13: unable to resolve method "
                            + "using strict-mode: java.lang.Object.length()",
                    loadFailure(engine, "tags.get(i).length() > 1", "output.score = 1"));

            engine.load(List.of(rule("applicantsById[id].creditScore > 600 && tags[i].length() > 1",
                    "output.score = 1")));
            assertEquals(1, engine.run(documentedFacts()).getScore());
        }
    }

    @Test
    @DisplayName("a projection over a typed list, and an element reached through another call, fail load(), as "
            + "docs/fact-types.md says")
    void aProjectionAndAnElementThroughAnotherCallFailLoading() {
        try (RulesEngine<Decision> engine = documentedEngine()) {
            assertEquals("Condition for rule 'r' failed to compile at line 1, column 2: unable to resolve method "
                            + "using strict-mode: java.lang.Object.length()",
                    loadFailure(engine, "(length() in tags).size() == 2", "output.score = 1"));
            assertEquals("Condition for rule 'r' failed to compile at line 1, column 24: unable to resolve method "
                            + "using strict-mode: java.lang.Object.length()",
                    loadFailure(engine, "tags.iterator().next().length() > 0", "output.score = 1"));
            assertEquals("Condition for rule 'r' failed to compile at line 1, column 23: unable to resolve method "
                            + "using strict-mode: java.lang.Object.length()",
                    loadFailure(engine, "tags.subList(0, 1)[0].length() > 0", "output.score = 1"));
        }
    }

    @Test
    @DisplayName("a generic bean declared with its type argument is still checked by its class alone, as "
            + "docs/fact-types.md says: MVEL isn't told the argument, which would make it reject a comparison that "
            + "loads")
    void aGenericBeanIsCheckedByItsClassAlone() {
        try (RulesEngine<Decision> engine = strongTyping().fact("box", new FactType<Box<String>>() {
        }).fact("colors", new FactType<Box<Color>>() {
        }).build()) {
            FactStore<Object> facts = fact("box", new Box<>("a"));
            facts.setValue("colors", new Box<>(Color.RED));

            engine.load(List.of(rule("box.getValue() == 'a'", "output.score = 1")));
            assertEquals(1, engine.run(facts).getScore());
            // Told that the value is a Color, MVEL's strict mode rejects comparing it with a string.
            engine.load(List.of(rule("box.getValue() == 'a' || colors.getValue() == 'RED'", "output.score = 2")));
            assertEquals(2, engine.run(facts).getScore());
            assertEquals("Condition for rule 'r' failed to compile at line 1, column 16: unable to resolve method "
                            + "using strict-mode: java.lang.Object.length()",
                    loadFailure(engine, "box.getValue().length() > 0", "output.score = 1"));
        }
    }
}
