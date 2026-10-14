package io.github.brantunger.unruly.api.language;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static io.github.brantunger.unruly.JavaSources.assertCompiles;
import static io.github.brantunger.unruly.JavaSources.source;
import static org.junit.jupiter.api.Assertions.*;

/**
 * How a getter's name becomes a property's: its first letter lower-cased as {@link Character#toLowerCase(char)} does
 * it, and which getter wins when two are read as one property (#1132). The beans whose getters start with a letter
 * outside ASCII are compiled when the tests start, from the source in {@link #LETTERS}: Checkstyle can't parse a
 * Unicode escape in an identifier, and an escape keeps the source the same whatever encoding the compiler assumes.
 */
@DisplayName("a getter's first letter is lower-cased as java.beans does it, and of two getters of one property a getX"
        + " beats an isX, then the greater name wins")
class FactPropertiesFirstLetterTest {

    private static final String LETTERS = """
            package com.example.letters;

            public final class Letters {

                /** A getter starting with a capital dotted I, which String lower-cases to two characters. */
                public static class DottedI {

                    public String get\u0130sim() {
                        return "dotted";
                    }
                }

                /** Two getters whose first letters both lower-case to i. */
                public static class BothI {

                    public String getIsim() {
                        return "plain";
                    }

                    public String get\u0130sim() {
                        return "dotted";
                    }
                }

                /** Two isX getters whose first letters both lower-case to i. */
                public static class BothIsI {

                    public boolean isIsim() {
                        return false;
                    }

                    public boolean is\u0130sim() {
                        return true;
                    }
                }

                /** A getX with a plain I beside an isX with a dotted one. */
                public static class PlainGetDottedIs {

                    public String getIsim() {
                        return "get";
                    }

                    public Boolean is\u0130sim() {
                        return true;
                    }
                }

                /** A getX with a dotted I beside an isX with a plain one. */
                public static class DottedGetPlainIs {

                    public String get\u0130sim() {
                        return "get";
                    }

                    public boolean isIsim() {
                        return true;
                    }
                }

                /** A getter with a K beside one with a Kelvin sign, which lower-cases to k as well. */
                public static class Kelvins {

                    public String getKelvin() {
                        return "letter";
                    }

                    public String get\u212Aelvin() {
                        return "sign";
                    }
                }

                /** Getters whose first letter is title case, or one half of a surrogate pair. */
                public static class Uncommon {

                    /** Title case first, and upper case second, so the name is kept as it is. */
                    public String get\u01C5Z() {
                        return "kept";
                    }

                    /** Title case first, and lower case second, so the first letter is lower-cased. */
                    public String get\u01C5z() {
                        return "lowered";
                    }

                    /** A Deseret capital, which is two chars, the first of which no char rule lower-cases. */
                    public String get\uD801\uDC00x() {
                        return "deseret";
                    }
                }
            }
            """;

    private static ClassLoader letters;

    /** A getter declared with a less specific type than its class's. */
    public interface Named {

        /**
         * The name.
         *
         * @return The name
         */
        Object getName();
    }

    /** Overrides {@link Named#getName()} with a String, so the compiler adds a bridge returning an Object. */
    public static class Covariant implements Named {

        @Override
        public String getName() {
            return "covariant";
        }
    }

    @BeforeAll
    static void compileLetters(@TempDir Path classes) throws Exception {
        assertCompiles(List.of("-proc:none", "-d", classes.toString()),
                List.of(source("com/example/letters/Letters", LETTERS)));
        letters = new URLClassLoader(new URL[]{classes.toUri().toURL()},
                FactPropertiesFirstLetterTest.class.getClassLoader());
    }

    private static Object bean(String name) throws ReflectiveOperationException {
        return letters.loadClass("com.example.letters.Letters$" + name).getConstructor().newInstance();
    }

    @Test
    @DisplayName("a getter starting with a capital dotted I is read by its name with a plain i")
    void dottedCapitalIIsReadWithAPlainI() throws ReflectiveOperationException {
        Object bean = bean("DottedI");

        assertEquals("dotted", FactProperties.read(bean, "isim"));
        assertEquals(Set.of("isim"), FactProperties.propertyNames(bean));
    }

    @Test
    @DisplayName("of getIsim() and get\u0130sim(), the greater name wins, and the fact has one property isim")
    void greaterSuffixWinsBetweenTwoGetters() throws ReflectiveOperationException {
        Object bean = bean("BothI");

        assertEquals("dotted", FactProperties.read(bean, "isim"));
        assertEquals(Set.of("isim"), FactProperties.propertyNames(bean));
        assertEquals(List.of("isim"), List.copyOf(FactProperties.toData(bean, 1).keySet()));
        assertEquals("dotted", FactProperties.toData(bean, 1).get("isim"));
    }

    @Test
    @DisplayName("of isIsim() and is\u0130sim(), the greater name wins")
    void greaterSuffixWinsBetweenTwoIsGetters() throws ReflectiveOperationException {
        Object bean = bean("BothIsI");

        assertEquals(true, FactProperties.read(bean, "isim"));
        assertEquals(Set.of("isim"), FactProperties.propertyNames(bean));
    }

    @Test
    @DisplayName("a getX beats an isX before names are compared, whichever has the dotted I")
    void getBeatsIsWhateverTheSuffix() throws ReflectiveOperationException {
        Object plainGet = bean("PlainGetDottedIs");
        Object dottedGet = bean("DottedGetPlainIs");

        assertEquals("get", FactProperties.read(plainGet, "isim"));
        assertEquals(Set.of("isim"), FactProperties.propertyNames(plainGet));
        assertEquals("get", FactProperties.read(dottedGet, "isim"));
        assertEquals(Set.of("isim"), FactProperties.propertyNames(dottedGet));
    }

    @Test
    @DisplayName("of getKelvin() and a getter with a Kelvin sign, the sign's, whose name is greater, wins")
    void kelvinSignWins() throws ReflectiveOperationException {
        Object bean = bean("Kelvins");

        assertEquals("sign", FactProperties.read(bean, "kelvin"));
        assertEquals(Set.of("kelvin"), FactProperties.propertyNames(bean));
    }

    @Test
    @DisplayName("a title-case first letter is kept before an upper-case one, and a surrogate is never lower-cased")
    void titleCaseAndSurrogatesAreUnchanged() throws ReflectiveOperationException {
        Object bean = bean("Uncommon");

        assertEquals(Set.of("\u01C5Z", "\u01C6z", "\uD801\uDC00x"), FactProperties.propertyNames(bean));
        assertEquals("kept", FactProperties.read(bean, "\u01C5Z"));
        assertEquals("lowered", FactProperties.read(bean, "\u01C6z"));
        assertEquals("deseret", FactProperties.read(bean, "\uD801\uDC00x"));
    }

    @Test
    @DisplayName("a getter and the bridge the compiler added for it read the same value")
    void bridgeAndItsGetterAgree() {
        assertEquals("covariant", FactProperties.read(new Covariant(), "name"));
        assertEquals(Set.of("name"), FactProperties.propertyNames(new Covariant()));
    }
}
