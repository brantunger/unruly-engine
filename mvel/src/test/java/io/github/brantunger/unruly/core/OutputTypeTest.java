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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("an expression language is told the type of the output object")
class OutputTypeTest {

    /** A language that records the context it was given, and compiles like the toy language. */
    private record Capturing(AtomicReference<CompileContext> context) implements ExpressionLanguage {

        @Override
        public String name() {
            return ToyExpressionLanguage.LANGUAGE_NAME;
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext given) {
            context.set(given);
            return new ToyExpressionLanguage().newCompiler(given);
        }
    }

    private static final Rule RULE = Rule.builder().ruleName("r").condition("true").action("put k 1").build();

    private static CompileContext contextOf(RulesEngine<Map<String, Object>> engine,
                                            AtomicReference<CompileContext> captured) {
        engine.load(List.of(RULE));
        return captured.get();
    }

    @Test
    @DisplayName("a language is told the output type the engine was built with")
    void outputTypeGiven() {
        AtomicReference<CompileContext> captured = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new Capturing(captured)).outputType(Map.class).build();

        assertEquals(Map.class, contextOf(engine, captured).outputType());
    }

    @Test
    @DisplayName("without outputType(), a language is told Object")
    void objectByDefault() {
        AtomicReference<CompileContext> captured = new AtomicReference<>();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(new Capturing(captured)).build();

        assertEquals(Object.class, contextOf(engine, captured).outputType());
    }

    @Test
    @DisplayName("a supertype of the output is accepted, so a Map output can be declared as Map")
    void supertypeAccepted() {
        AtomicReference<CompileContext> captured = new AtomicReference<>();
        RulesEngine<StringBuilder> engine = RulesEngineBuilder.allMatches(StringBuilder::new)
                .language(new Capturing(captured)).outputType(CharSequence.class).build();
        engine.load(List.of(RULE));

        assertEquals(CharSequence.class, captured.get().outputType());
    }

    @Test
    @DisplayName("outputType() rejects null")
    void nullRejected() {
        RulesEngineBuilder<Map<String, Object>> builder = RulesEngineBuilder.allMatches(HashMap::new);

        assertEquals("type must not be null",
                assertThrows(NullPointerException.class, () -> builder.outputType(null)).getMessage());
    }
}
