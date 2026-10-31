package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.PropertyInputs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.beans.Introspector;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.github.brantunger.unruly.JavaSources.assertCompiles;
import static io.github.brantunger.unruly.JavaSources.source;
import static org.junit.jupiter.api.Assertions.*;

/**
 * #1332: the property a getter is read as, and which of two getters read as one property wins, as
 * {@link FactProperties#read}'s Javadoc states them, checked on random beans (see {@link PropertyInputs}). Each bean
 * has a few getters whose names start, after {@code get} or {@code is}, with letters whose case rules differ: the
 * dotted and dotless I, the Kelvin and Ohm signs, title-case letters, final sigma, Deseret capitals outside the Basic
 * Multilingual Plane. Their first letters often lower-case alike, so two getters often read one property, and they are
 * declared in a random order, which the winner mustn't depend on. The beans are compiled once, when the tests start,
 * as {@link FactPropertiesFirstLetterTest} compiles its own; each getter records its name when it is called, so a read
 * shows which getter it called.
 */
@DisplayName("FactProperties: a getter's property is named, and two getters' tie broken, as documented (#1332)")
class FactPropertiesNamePropertyTest {

    private static final int CASES = 200;

    private static final List<Long> SEEDS = PropertyInputs.seeds(CASES).boxed().toList();

    // Letters that lower-case alike, by family, so that getters of one bean collide: the I's and the dotted capital,
    // the K's and the Kelvin sign, the sigmas, the omegas and the Ohm sign, the A's with a ring and the angstrom sign,
    // the DZ digraphs in their three cases, and Deseret's long I in both cases, each a surrogate pair.
    private static final String[][] FAMILIES = {
        {"I", "i", "İ", "ı"},
        {"K", "k", "K"},
        {"Σ", "σ", "ς"},
        {"Ω", "ω", "Ω"},
        {"Å", "å", "Å"},
        {"Ǆ", "ǅ", "ǆ"},
        {"𐐀", "𐐨"},
        {"µ", "Μ", "μ", "M"},
        {"ß", "ẞ", "S", "_", "1", "$"},
    };

    // What can follow the first letter: nothing, letters of either case, a title-case letter, letters above U+00FF of
    // either case, a Deseret capital, a digit and an underscore.
    private static final String[] SECONDS = {"", "", "s", "S", "ǅ", "Π", "π", "İ", "𐐀",
        "1", "_"};

    private static final String[] RESTS = {"", "im", "x"};

    private static ClassLoader beans;

    /** A getter, or a method that only looks like one: its name, and what it returns. */
    private record Getter(String name, String returns) {
    }

    private static Stream<Arguments> cases() {
        return IntStream.range(0, CASES).mapToObj(index -> Arguments.of(index, SEEDS.get(index)));
    }

    @BeforeAll
    static void compileBeans(@TempDir Path classes) throws Exception {
        StringBuilder source = new StringBuilder("package com.example.generated;\n\npublic final class Generated {\n\n"
                + "    public static String called;\n");
        for (int index = 0; index < CASES; index++) {
            source.append("\n    public static class Bean").append(index).append(" {\n");
            for (Getter getter : getters(SEEDS.get(index))) {
                String value = "String".equals(getter.returns()) ? "\"" + getter.name() + "\"" : "true";
                source.append("        public ").append(getter.returns()).append(' ').append(getter.name())
                        .append("() {\n            called = \"").append(getter.name())
                        .append("\";\n            return ").append(value).append(";\n        }\n");
            }
            source.append("    }\n");
        }
        assertCompiles(List.of("-proc:none", "-d", classes.toString()),
                List.of(source("com/example/generated/Generated", source.append("}\n").toString())));
        beans = new URLClassLoader(new URL[]{classes.toUri().toURL()},
                FactPropertiesNamePropertyTest.class.getClassLoader());
    }

    /** A bean's getters, in the order it declares them, from its seed. */
    private static List<Getter> getters(long seed) {
        PropertyInputs inputs = new PropertyInputs(seed);
        String[] family = FAMILIES[inputs.below(FAMILIES.length)];
        Map<String, Getter> getters = new LinkedHashMap<>();
        int count = 1 + inputs.below(6);
        for (int i = 0; i < count; i++) {
            String[] letters = inputs.below(5) == 0 ? FAMILIES[inputs.below(FAMILIES.length)] : family;
            String suffix = inputs.below(25) == 0 ? ""
                    : letters[inputs.below(letters.length)] + SECONDS[inputs.below(SECONDS.length)]
                    + RESTS[inputs.below(RESTS.length)];
            Getter getter = switch (inputs.below(5)) {
                case 0, 1 -> new Getter("get" + suffix, "String");
                case 2 -> new Getter("is" + suffix, "boolean");
                case 3 -> new Getter("is" + suffix, "Boolean");
                // Not a getter: an isX must return a boolean or a Boolean.
                default -> new Getter("is" + suffix, "String");
            };
            getters.putIfAbsent(getter.name(), getter);
        }
        List<Getter> declared = new ArrayList<>(getters.values());
        Collections.shuffle(declared, new Random(seed));
        return declared;
    }

    @ParameterizedTest(name = "seed {1}")
    @MethodSource("cases")
    @DisplayName("a bean's properties are its getters' names with the first letter lower-cased unless the second is "
            + "upper case, and a getX beats an isX, then the greater name wins")
    void namedAndTiesBrokenAsDocumented(int index, long seed) throws ReflectiveOperationException {
        List<Getter> getters = getters(seed);
        Map<String, Getter> winners = new LinkedHashMap<>();
        for (Getter getter : getters) {
            String property = property(getter);
            if (property != null) {
                winners.merge(property, getter, FactPropertiesNamePropertyTest::preferred);
            }
        }
        Class<?> type = beans.loadClass("com.example.generated.Generated$Bean" + index);
        Object bean = type.getConstructor().newInstance();
        String about = "a bean with " + getters + ": ";

        assertEquals(winners.keySet(), FactProperties.propertyNames(bean), about + "its properties");
        Field called = type.getDeclaringClass().getField("called");
        for (Map.Entry<String, Getter> winner : winners.entrySet()) {
            called.set(null, null);
            Object value = FactProperties.read(bean, winner.getKey());

            assertEquals(winner.getValue().name(), called.get(null),
                    about + "the getter '" + winner.getKey() + "' is read with");
            assertEquals("String".equals(winner.getValue().returns()) ? winner.getValue().name() : true, value,
                    about + "the value of '" + winner.getKey() + "'");
        }
    }

    /**
     * The property a getter is read as, by {@link FactProperties#read}'s Javadoc: {@code getX}, or {@code isX}
     * returning a {@code boolean} or a {@link Boolean}, is read as {@code X} with its first character lower-cased by
     * {@link Character#toLowerCase(char)}, unless the second is upper case. That is {@code java.beans}' rule wherever
     * the first is upper case too, which is checked here as well.
     */
    private static String property(Getter getter) {
        String name = getter.name();
        String x;
        if (name.startsWith("get") && name.length() > 3) {
            x = name.substring(3);
        } else if (name.startsWith("is") && name.length() > 2 && !"String".equals(getter.returns())) {
            x = name.substring(2);
        } else {
            return null;
        }
        boolean secondUpper = x.length() > 1 && Character.isUpperCase(x.charAt(1));
        String property = secondUpper ? x : Character.toLowerCase(x.charAt(0)) + x.substring(1);
        if (!secondUpper || Character.isUpperCase(x.charAt(0))) {
            assertEquals(Introspector.decapitalize(x), property, "java.beans names " + name + "() otherwise");
        }
        return property;
    }

    // A getX beats an isX, and between two of the same kind the greater name wins.
    private static Getter preferred(Getter one, Getter other) {
        boolean oneIsGet = one.name().startsWith("get");
        if (oneIsGet != other.name().startsWith("get")) {
            return oneIsGet ? one : other;
        }
        return one.name().compareTo(other.name()) > 0 ? one : other;
    }
}
