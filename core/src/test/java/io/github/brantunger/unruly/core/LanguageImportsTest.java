package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("a language is given imports of its own, as written, which the engine's other languages don't see")
class LanguageImportsTest {

    /** A language that records the context it was given, and compiles like the toy language. */
    private record Capturing(String name, Map<String, CompileContext> contexts) implements ExpressionLanguage {

        @Override
        public ExpressionCompiler newCompiler(CompileContext given) {
            contexts.put(name, given);
            return new ToyExpressionLanguage(name).newCompiler(given);
        }
    }

    private static final String JS = "js";
    private static final String TOY = ToyExpressionLanguage.LANGUAGE_NAME;

    private final Map<String, CompileContext> contexts = new ConcurrentHashMap<>();

    /** A builder for an engine with two languages, js and toy, that record their contexts. */
    private RulesEngineBuilder<Map<String, Object>> builder() {
        return RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new Capturing(JS, contexts)).language(new Capturing(TOY, contexts)).defaultLanguage(TOY);
    }

    /** Loads a rule in each language, so each creates a compiler, and returns the context each was given. */
    private Map<String, CompileContext> load(RulesEngine<Map<String, Object>> engine) {
        engine.load(List.of(
                Rule.builder().ruleName("js-rule").language(JS).condition("true").action("put k 1").build(),
                Rule.builder().ruleName("toy-rule").language(TOY).condition("true").action("put k 1").build()));
        return contexts;
    }

    @Test
    @DisplayName("a language sees its imports as written, in order, duplicates kept; another language sees none")
    void givenAsWrittenToTheirLanguageOnly() {
        RulesEngine<Map<String, Object>> engine = builder()
                .languageImports(JS, "lodash/fp", "./rules/util.js")
                .languageImports(JS, List.of("@acme/pricing", "lodash/fp"))
                .build();

        Map<String, CompileContext> given = load(engine);

        assertEquals(List.of("lodash/fp", "./rules/util.js", "@acme/pricing", "lodash/fp"),
                given.get(JS).languageImports());
        assertEquals(List.of(), given.get(TOY).languageImports());
    }

    @Test
    @DisplayName("a language's imports are unmodifiable, and later changes to the builder don't reach the engine")
    void unmodifiableAndDetached() {
        RulesEngineBuilder<Map<String, Object>> builder = builder().languageImports(JS, "lodash");
        RulesEngine<Map<String, Object>> engine = builder.build();
        builder.languageImports(JS, "os.path");

        List<String> imports = load(engine).get(JS).languageImports();

        assertEquals(List.of("lodash"), imports);
        assertThrows(UnsupportedOperationException.class, () -> imports.add("os.path"));
    }

    @Test
    @DisplayName("languageImports() adds nothing to the engine's Java imports, for either language")
    void javaImportsUnchanged() {
        RulesEngine<Map<String, Object>> engine = builder()
                .imports("java.util", "java.time.LocalDate")
                .languageImports(JS, "lodash", "os.path")
                .build();

        Map<String, CompileContext> given = load(engine);

        for (CompileContext context : given.values()) {
            assertEquals(Set.of("java.util"), context.packageImports());
            assertEquals(Set.of(LocalDate.class), context.classImports());
        }
        assertEquals(List.of("lodash", "os.path"), given.get(JS).languageImports());
    }

    @Test
    @DisplayName("build() fails naming a language the engine doesn't have, even with no imports for it")
    void unknownLanguageFailsBuild() {
        String languages = "[js, toy]";

        assertEquals("Imports are given for the expression language 'nope', which isn't one of the engine's "
                + "expression languages: " + languages, assertThrows(IllegalStateException.class,
                () -> builder().languageImports("nope", "lodash").build()).getMessage());
        assertEquals("Imports are given for the expression language 'nope', which isn't one of the engine's "
                + "expression languages: " + languages, assertThrows(IllegalStateException.class,
                () -> builder().languageImports("nope", List.of()).build()).getMessage());
    }

    @Test
    @DisplayName("build() rejects a language import of more than 1,000 characters, and accepts one of 1,000")
    void longestImport() {
        String longest = "m".repeat(ImportResolver.MAX_IMPORT_LENGTH);
        String tooLong = longest + "/";

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> builder().languageImports(JS, "lodash", tooLong).build());
        assertEquals("Can't import '" + "m".repeat(200) + "... (801 more characters)': it has 1001 characters, and "
                + "an import may have at most 1000", ex.getMessage());
        assertEquals(List.of(longest), load(builder().languageImports(JS, longest).build()).get(JS).languageImports());
    }

    @Test
    @DisplayName("a language import isn't limited in dot-separated parts, as it isn't looked up as a class")
    void partsNotLimited() {
        String dotted = "a.".repeat(100) + "js";

        assertEquals(List.of(dotted), load(builder().languageImports(JS, dotted).build()).get(JS).languageImports());
    }

    @Test
    @DisplayName("languageImports() rejects a null language, names or name at the call, adding nothing")
    void nullsRejected() {
        RulesEngineBuilder<Map<String, Object>> builder = builder();
        Collection<String> nullCollection = null;
        String[] nullArray = null;

        assertAll(
                () -> assertEquals("language must not be null", assertThrows(NullPointerException.class,
                        () -> builder.languageImports(null, "lodash")).getMessage()),
                () -> assertEquals("language must not be null", assertThrows(NullPointerException.class,
                        () -> builder.languageImports(null, List.of("lodash"))).getMessage()),
                () -> assertEquals("language must not be null", assertThrows(NullPointerException.class,
                        () -> builder.languageImports(null, nullArray)).getMessage()),
                () -> assertEquals("names must not be null", assertThrows(NullPointerException.class,
                        () -> builder.languageImports(JS, nullArray)).getMessage()),
                () -> assertEquals("names must not be null", assertThrows(NullPointerException.class,
                        () -> builder.languageImports(JS, nullCollection)).getMessage()),
                () -> assertEquals("names must not contain null", assertThrows(NullPointerException.class,
                        () -> builder.languageImports(JS, "lodash", null)).getMessage()),
                () -> assertEquals("names must not contain null", assertThrows(NullPointerException.class,
                        () -> builder.languageImports(JS, Arrays.asList("lodash", null))).getMessage()),
                () -> assertEquals("names must not contain null", assertThrows(NullPointerException.class,
                        () -> builder.languageImports("nope", Arrays.asList("a", null))).getMessage()));
        // Nothing was added, not even the unknown language, so build() doesn't fail naming it.
        assertEquals(List.of(), load(builder.build()).get(JS).languageImports());
    }

    @Test
    @DisplayName("reading a language's imports from a null context names it")
    void nullContextRejected() {
        assertEquals("context must not be null", assertThrows(NullPointerException.class,
                () -> EngineCompileContext.languageImports(null)).getMessage());
    }

    @Test
    @DisplayName("imports() still refuses a module name at build(): it takes only Java classes and packages")
    void importsStillJavaOnly() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> builder().imports("lodash/fp").build());

        assertEquals("'lodash/fp' is neither a class nor a valid package name", ex.getMessage());
    }
}
