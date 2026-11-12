package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Fact;
import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.FactType;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.StubExpressionLanguage;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A fact can be declared with its type arguments, so a language is told a {@code List<String>} fact's element type
 * (#1390): {@code CompileContext.declaredFacts()} gives each type as a {@link Class} or a {@link ParameterizedType},
 * the engine checks a run's value against the type's class, and a type that has no class of its own is rejected when
 * it's declared.
 */
@DisplayName("a fact is declared with its type arguments, and a language is told them")
class DeclaredFactTypesTest {

    /** An applicant as a record, the shape the docs use. */
    public record Applicant(String name) {
    }

    private static final Rule RULE = Rule.builder().ruleName("r").condition("true").action("put ok true").build();

    private static final String NOT_A_FACT_TYPE = "A fact's type must be a class or a parameterized type, such as"
            + " List<String>, not ";

    /** A parameterized type as another library's type token makes one. */
    private record Parameterized(Type rawType, Type argument) implements ParameterizedType {

        @Override
        public Type[] getActualTypeArguments() {
            return new Type[] {argument};
        }

        @Override
        public Type getRawType() {
            return rawType;
        }

        @Override
        public Type getOwnerType() {
            return null;
        }
    }

    /** A method whose parameters have the kinds of type no fact can be declared with. */
    @SuppressWarnings("unused")
    private static <T> void kinds(T variable, List<? extends Number> wildcard, List<String>[] genericArray) {
    }

    private static Type[] kinds() throws NoSuchMethodException {
        return DeclaredFactTypesTest.class.getDeclaredMethod("kinds", Object.class, List.class, List[].class)
                .getGenericParameterTypes();
    }

    /** The declared facts a language is told when an engine built by {@code builder} loads a rule. */
    private static Map<String, Type> toldToTheLanguage(RulesEngineBuilder<Map<String, Object>> builder) {
        List<Map<String, Type>> told = new ArrayList<>();
        try (RulesEngine<Map<String, Object>> engine = builder
                .language(new StubExpressionLanguage().onNewCompiler(context -> told.add(context.declaredFacts())))
                .build()) {
            engine.load(List.of(RULE));
        }
        assertEquals(1, told.size());
        return told.get(0);
    }

    private static RulesEngineBuilder<Map<String, Object>> builder() {
        return RulesEngineBuilder.allMatches(HashMap::new);
    }

    @Test
    @DisplayName("a fact declared with a FactType reaches the language as the parameterized type, not the token")
    void languageIsToldTheTypeArguments() {
        Map<String, Type> told = toldToTheLanguage(builder()
                .fact("tags", new FactType<List<String>>() {
                })
                .fact("byId", new FactType<Map<String, Applicant>>() {
                })
                .fact("applicant", Applicant.class));

        ParameterizedType tags = assertInstanceOf(ParameterizedType.class, told.get("tags"));
        assertEquals(List.class, tags.getRawType());
        assertArrayEquals(new Type[] {String.class}, tags.getActualTypeArguments());
        assertEquals("java.util.Map<java.lang.String, " + Applicant.class.getName() + ">",
                told.get("byId").getTypeName());
        assertSame(Applicant.class, told.get("applicant"));
        assertEquals(List.class, CompileContext.rawClass(told.get("tags")));
    }

    @Test
    @DisplayName("a fact declared with a class literal reaches the language as that class, a primitive as its wrapper")
    void classesAreToldAsBefore() {
        Map<String, Type> told = toldToTheLanguage(builder().fact("tags", List.class).fact("age", int.class));

        assertEquals(Map.of("tags", List.class, "age", Integer.class), told);
    }

    @Test
    @DisplayName("facts(map) takes classes, tokens and another library's parameterized types together")
    void mapOfMixedTypes() {
        Type custom = new Parameterized(Set.class, Applicant.class);
        Map<String, Type> types = new LinkedHashMap<>();
        types.put("name", String.class);
        types.put("tags", new FactType<List<String>>() {
        });
        types.put("people", custom);

        Map<String, Type> told = toldToTheLanguage(builder().facts(types));

        assertSame(String.class, told.get("name"));
        assertEquals("java.util.List<java.lang.String>", told.get("tags").getTypeName());
        assertFalse(told.get("tags") instanceof FactType);
        assertSame(custom, told.get("people"));
    }

    @Test
    @DisplayName("facts(map) still takes a map of classes")
    void mapOfClasses() {
        Map<String, Class<?>> types = Map.of("name", String.class);

        assertEquals(Map.of("name", String.class), toldToTheLanguage(builder().facts(types)));
    }

    @Test
    @DisplayName("a type's own arguments reach the language as written, a wildcard included")
    void nestedArgumentsAreNotChecked() {
        Map<String, Type> told = toldToTheLanguage(builder().fact("numbers",
                new FactType<List<? extends Number>>() {
                }));

        assertEquals("java.util.List<? extends java.lang.Number>", told.get("numbers").getTypeName());
    }

    @Test
    @DisplayName("a type variable, a wildcard and a generic array type are rejected when declared, naming each")
    void otherKindsRejected() throws NoSuchMethodException {
        RulesEngineBuilder<Map<String, Object>> builder = builder();
        Type variable = kinds()[0];
        Type wildcard = ((ParameterizedType) kinds()[1]).getActualTypeArguments()[0];
        Type genericArray = kinds()[2];

        assertEquals(NOT_A_FACT_TYPE + "the type variable T",
                assertThrows(IllegalArgumentException.class, () -> builder.fact("a", variable)).getMessage());
        assertEquals(NOT_A_FACT_TYPE + "the wildcard ? extends java.lang.Number",
                assertThrows(IllegalArgumentException.class, () -> builder.fact("a", wildcard)).getMessage());
        assertEquals(NOT_A_FACT_TYPE + "the generic array type java.util.List<java.lang.String>[]",
                assertThrows(IllegalArgumentException.class, () -> builder.fact("a", genericArray)).getMessage());
        assertEquals(Map.of(), toldToTheLanguage(builder));
    }

    @Test
    @DisplayName("a parameterized type whose raw type isn't a class is rejected when declared")
    void rawTypeMustBeAClass() throws NoSuchMethodException {
        Type noRawClass = new Parameterized(kinds()[0], String.class);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> builder().fact("a", noRawClass));

        assertTrue(thrown.getMessage().startsWith(NOT_A_FACT_TYPE + "a parameterized type whose raw type isn't a"
                + " class or an interface, "), thrown.getMessage());
    }

    @Test
    @DisplayName("a parameterized type whose raw type is a primitive class is rejected when declared")
    void rawTypeMustNotBePrimitive() {
        Type primitiveRaw = new Parameterized(int.class, String.class);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> builder().fact("a", primitiveRaw));

        assertTrue(thrown.getMessage().startsWith(NOT_A_FACT_TYPE + "a parameterized type whose raw type isn't a"
                + " class or an interface, "), thrown.getMessage());
    }

    /** A parameterized type an application wrote, which counts how often its raw type is asked for, and can fail. */
    private static final class Watched implements ParameterizedType {

        private final AtomicInteger reads = new AtomicInteger();
        private volatile Type rawType = List.class;
        private volatile boolean failing;

        @Override
        public Type[] getActualTypeArguments() {
            return new Type[] {String.class};
        }

        @Override
        public Type getRawType() {
            reads.incrementAndGet();
            if (failing) {
                throw new IllegalStateException("getRawType() was called after the engine was built");
            }
            return rawType;
        }

        @Override
        public Type getOwnerType() {
            return null;
        }
    }

    @Test
    @DisplayName("a parameterized type is asked for its raw type when it's declared and when the engine is built, and"
            + " never by a load(), a validate() or a run(), so one that changes or throws later changes nothing")
    void rawTypeReadOnceAtBuild() {
        Watched watched = new Watched();
        List<Type> told = new ArrayList<>();
        RulesEngineBuilder<Map<String, Object>> builder = builder()
                .language(new StubExpressionLanguage().onNewCompiler(context -> told.add(
                        context.declaredFacts().get("tags"))))
                .fact("tags", watched);
        assertEquals(1, watched.reads.get(), "fact() reads the raw type once, to check it");

        try (RulesEngine<Map<String, Object>> engine = builder.build()) {
            assertEquals(2, watched.reads.get(), "build() reads the raw type once, and keeps it");
            // From here on the type answers Map, and then throws: the engine goes by what it read.
            watched.rawType = Map.class;
            List<Rule> rules = List.of(RULE);

            assertEquals(List.of(), engine.validate(rules));
            engine.load(rules);
            watched.failing = true;
            assertEquals(List.of(), engine.validate(rules));
            engine.load(rules);
            engine.run(new FactMap<>(new Fact<>("tags", List.of("a"))));
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> engine.run(new FactMap<>(new Fact<>("tags", Map.of()))));

            assertEquals("Fact 'tags' was declared as java.util.List, but the run supplied a "
                    + Map.of().getClass().getName(), thrown.getMessage());
            assertEquals(2, watched.reads.get(), "load(), validate() and run() read the raw type again");
            assertEquals(4, told.size());
            told.forEach(type -> assertSame(watched, type));
        }
    }

    @Test
    @DisplayName("the declarations an engine tells a language are a map that looks names up and can't be changed")
    void engineDeclarationsAreAMap() {
        Map<String, Type> told = toldToTheLanguage(builder().fact("tags", List.class).fact("age", int.class));

        assertEquals(2, told.size());
        assertTrue(told.containsKey("age"));
        assertFalse(told.containsKey("name"));
        assertSame(Integer.class, told.get("age"));
        assertNull(told.get("name"));
        assertEquals(Set.of("tags", "age"), told.keySet());
        assertThrows(UnsupportedOperationException.class, () -> told.put("name", String.class));
        assertThrows(UnsupportedOperationException.class, told::clear);
    }

    @Test
    @DisplayName("the name is checked before the type, and a null type is named as before")
    void nameCheckedFirst() throws NoSuchMethodException {
        RulesEngineBuilder<Map<String, Object>> builder = builder();
        Type variable = kinds()[0];

        assertEquals("name must not be null",
                assertThrows(NullPointerException.class, () -> builder.fact(null, variable)).getMessage());
        assertEquals("type must not be null",
                assertThrows(NullPointerException.class, () -> builder.fact("a", (Type) null)).getMessage());
        assertEquals("fact name must not be blank",
                assertThrows(IllegalArgumentException.class, () -> builder.fact(" ", variable)).getMessage());
    }

    @Test
    @DisplayName("a map with a type no fact can be declared with declares none of its entries")
    void rejectedTypeDeclaresNothing() throws NoSuchMethodException {
        RulesEngineBuilder<Map<String, Object>> builder = builder();
        Map<String, Type> types = new LinkedHashMap<>();
        types.put("a", String.class);
        types.put("b", kinds()[0]);

        assertThrows(IllegalArgumentException.class, () -> builder.facts(types));

        assertEquals(Map.of(), toldToTheLanguage(builder));
    }

    @Test
    @DisplayName("a run's value is checked against the type's class: the failure names it, and elements aren't checked")
    void runChecksTheRawClass() {
        try (RulesEngine<Map<String, Object>> engine = builder().language(new ToyExpressionLanguage())
                .fact("tags", new FactType<List<String>>() {
                })
                .requireDeclaredFacts().build()) {
            engine.load(List.of(RULE));

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> engine.run(new FactMap<>(new Fact<>("tags", "not a list"))));

            assertEquals("Fact 'tags' was declared as java.util.List, but the run supplied a java.lang.String",
                    thrown.getMessage());
            assertEquals(Map.of("ok", true), engine.run(new FactMap<>(new Fact<>("tags", List.of("a")))));
            // The elements' type is erased, so a list of anything passes.
            assertEquals(Map.of("ok", true), engine.run(new FactMap<>(new Fact<>("tags", List.of(1)))));
        }
    }

    @Test
    @DisplayName("a compile context made outside an engine, as the test kit does, declares the same types")
    void compileContextDeclarationsMatchAnEngine() throws NoSuchMethodException {
        Map<String, Type> declared = new LinkedHashMap<>();
        declared.put("age", int.class);
        declared.put("tags", new FactType<List<String>>() {
        });
        EngineCompileContext context = new EngineCompileContext(Set.of(), Set.of(),
                ClassLoader.getSystemClassLoader(), Object.class, Map.of(), declared, true);

        assertSame(Integer.class, context.declaredFacts().get("age"));
        assertEquals("java.util.List<java.lang.String>", context.declaredFacts().get("tags").getTypeName());
        assertFalse(context.declaredFacts().get("tags") instanceof FactType);

        Map<String, Type> rejected = Map.of("a", kinds()[0]);
        assertEquals(NOT_A_FACT_TYPE + "the type variable T", assertThrows(IllegalArgumentException.class,
                () -> new EngineCompileContext(Set.of(), Set.of(), ClassLoader.getSystemClassLoader(), Object.class,
                        Map.of(), rejected, true)).getMessage());
        assertEquals(NOT_A_FACT_TYPE + "the type variable T", assertThrows(IllegalArgumentException.class,
                () -> EngineCompileContext.forLanguage(new ToyExpressionLanguage(), Set.of(), Set.of(),
                        ClassLoader.getSystemClassLoader(), Object.class, Map.of(), rejected, true, List.of()))
                .getMessage());
    }
}
