package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.ToyExpressionLanguage;
import io.github.brantunger.unruly.mvel.MvelExpressionLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("each expression language is given its own options when rules are loaded")
class LanguageOptionsTest {

    /** A language that records the context it was given, and compiles like the toy language. */
    private record Capturing(ExpressionLanguage language, AtomicReference<CompileContext> context)
            implements ExpressionLanguage {

        @Override
        public String name() {
            return language.name();
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext given) {
            context.set(given);
            return language.newCompiler(given);
        }
    }

    private static Rule rule(String language) {
        return Rule.builder().ruleName(language).language(language).condition("true").action("put k 1").build();
    }

    @Test
    @DisplayName("a language sees its own options, and another language's options are its own")
    void optionsPerLanguage() {
        AtomicReference<CompileContext> toy = new AtomicReference<>();
        AtomicReference<CompileContext> other = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new Capturing(new ToyExpressionLanguage(), toy))
                .language(new Capturing(new ToyExpressionLanguage("other"), other))
                .defaultLanguage("toy")
                .option("toy", "strict", "true")
                .option("toy", "depth", "2")
                .option("other", "strict", "false")
                .build();

        engine.load(List.of(rule("toy"), rule("other")));

        assertEquals(Map.of("strict", "true", "depth", "2"), toy.get().options());
        assertEquals(Map.of("strict", "false"), other.get().options());
    }

    @Test
    @DisplayName("a language given no options sees an empty, unmodifiable map")
    void noOptions() {
        AtomicReference<CompileContext> captured = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new Capturing(new ToyExpressionLanguage(), captured)).build();

        engine.load(List.of(rule("toy")));

        Map<String, String> options = captured.get().options();
        assertEquals(Map.of(), options);
        assertThrows(UnsupportedOperationException.class, () -> options.put("strict", "true"));
    }

    @Test
    @DisplayName("setting an option again replaces its value")
    void lastValueWins() {
        AtomicReference<CompileContext> captured = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new Capturing(new ToyExpressionLanguage(), captured))
                .option("toy", "strict", "true")
                .option("toy", "strict", "false")
                .build();

        engine.load(List.of(rule("toy")));

        assertEquals(Map.of("strict", "false"), captured.get().options());
    }

    @Test
    @DisplayName("options for a language the engine doesn't have fail build(), so a typo isn't ignored")
    void unknownLanguageFailsBuild() {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder
                .<Map<String, Object>>allMatches(HashMap::new)
                .language(new MvelExpressionLanguage()).language(new ToyExpressionLanguage())
                .defaultLanguage("mvel")
                .option("toys", "strict", "true");

        IllegalStateException ex = assertThrows(IllegalStateException.class, builder::build);

        assertEquals("Options are given for the expression language 'toys', which isn't one of the engine's "
                + "expression languages: [mvel, toy]", ex.getMessage());
    }

    @Test
    @DisplayName("option() rejects a null language, key or value")
    void nullRejected() {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.allMatches(HashMap::new);

        assertEquals("language must not be null", assertThrows(NullPointerException.class,
                () -> builder.option(null, "strict", "true")).getMessage());
        assertEquals("key must not be null", assertThrows(NullPointerException.class,
                () -> builder.option("toy", null, "true")).getMessage());
        assertEquals("value must not be null", assertThrows(NullPointerException.class,
                () -> builder.option("toy", "strict", null)).getMessage());
    }
}
