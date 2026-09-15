package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.OutputWriter;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.RuleExecutionException;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("the engine sets the properties an action returns with the engine's OutputWriter")
class OutputWriterTest {

    /**
     * A language whose conditions are always true. An action is {@code name=value} pairs separated by {@code ;},
     * returned as properties, or {@code done} for an action that changes nothing.
     */
    private static final ExpressionLanguage PATCH = new ExpressionLanguage() {
        @Override
        public String name() {
            return "patch";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression source) {
                    return (evaluation, session) -> true;
                }

                @Override
                public CompiledAction compileAction(Expression source) {
                    if ("done".equals(source.text())) {
                        return (action, session) -> ActionResult.done();
                    }
                    Map<String, Object> properties = new LinkedHashMap<>();
                    for (String pair : source.text().split(";")) {
                        String[] nameAndValue = pair.split("=", 2);
                        properties.put(nameAndValue[0], nameAndValue[1]);
                    }
                    ActionResult result = ActionResult.set(properties);
                    return (action, session) -> result;
                }

                @Override
                public Session newSession() {
                    return Session.none();
                }
            };
        }
    };

    private static Rule rule(String name, int priority, String action) {
        return Rule.builder().ruleName(name).priority(priority).language("patch").condition("true").action(action)
                .build();
    }

    private static RulesEngineBuilder<Map<String, Object>> builder() {
        return RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new).language(PATCH);
    }

    @Test
    @DisplayName("a writer is given the output object and each property, in the order the actions returned them")
    void writerSetsEachProperty() {
        List<String> written = new ArrayList<>();
        Map<String, Object> output = new HashMap<>();
        RulesEngine<Map<String, Object>> engine = builder()
                .outputWriter((out, property, value) -> written.add(property + "=" + value + " on " + out.hashCode()))
                .build();
        engine.load(List.of(rule("high", 2, "rate=4.5;approved=true"), rule("low", 1, "rate=6.9")));

        Map<String, Object> result = engine.run(new FactMap<>());

        assertEquals(List.of("rate=4.5 on " + result.hashCode(), "approved=true on " + result.hashCode(),
                "rate=6.9 on " + result.hashCode()), written);
        assertEquals(Map.of(), output);
        assertEquals(Map.of(), result, "the writer wrote nothing to the output itself");
    }

    @Test
    @DisplayName("an action that changed the output itself doesn't reach the writer")
    void doneSkipsTheWriter() {
        List<String> written = new ArrayList<>();
        RulesEngine<Map<String, Object>> engine = builder()
                .outputWriter((out, property, value) -> written.add(property))
                .build();
        engine.load(List.of(rule("r", 1, "done")));

        engine.run(new FactMap<>());

        assertEquals(List.of(), written);
    }

    @Test
    @DisplayName("a writer that throws fails the rule, naming it and the property, and listeners are told")
    void writerFailureFailsTheRule() {
        List<String> errors = new ArrayList<>();
        RuleListener listener = new RuleListener() {
            @Override
            public void onError(Rule rule, RuleExecutionException error) {
                errors.add(rule.getRuleName() + ": " + error.getMessage());
            }
        };
        RulesEngine<Map<String, Object>> engine = builder().listener(listener)
                .outputWriter((out, property, value) -> {
                    throw new IllegalStateException("no room for " + property);
                })
                .build();
        engine.load(List.of(rule("r", 1, "rate=4.5")));

        RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        assertEquals("Failed to set 'rate' on the output for rule 'r': no room for rate", ex.getMessage());
        assertEquals(List.of("r: " + ex.getMessage()), errors);
    }

    @Test
    @DisplayName("the default writer puts properties into a Map output, like beansAndMaps()")
    void defaultWriterIsBeansAndMaps() {
        RulesEngine<Map<String, Object>> byDefault = builder().build();
        RulesEngine<Map<String, Object>> named = builder().outputWriter(OutputWriter.beansAndMaps()).build();
        byDefault.load(List.of(rule("r", 1, "rate=4.5")));
        named.load(List.of(rule("r", 1, "rate=4.5")));

        assertEquals(Map.of("rate", "4.5"), byDefault.run(new FactMap<>()));
        assertEquals(byDefault.run(new FactMap<>()), named.run(new FactMap<>()));
    }

    @Test
    @DisplayName("outputWriter() rejects null")
    void nullRejected() {
        assertEquals("writer must not be null", assertThrows(NullPointerException.class,
                () -> builder().outputWriter(null)).getMessage());
    }
}
