package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RuleListener;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.exception.ExpressionKind;
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
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("the engine sets the properties an action returns on the output object")
class ActionResultsTest {

    /**
     * A language whose conditions are always true and whose actions return properties instead of changing the output.
     * An action is {@code name=value} pairs separated by {@code ;}. A value is {@code true}, {@code false}, {@code null},
     * an integer, a decimal number or text. The action {@code nothing} returns {@code null}.
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
                    if ("nothing".equals(source.text())) {
                        return (action, session) -> null;
                    }
                    Map<String, Object> properties = new LinkedHashMap<>();
                    for (String pair : source.text().split(";")) {
                        String[] nameAndValue = pair.split("=", 2);
                        properties.put(nameAndValue[0], value(nameAndValue[1]));
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

    private static Object value(String text) {
        return switch (text) {
            case "true", "false" -> Boolean.valueOf(text);
            case "null" -> null;
            default -> text.matches("-?\\d+") ? (Object) Integer.valueOf(text)
                    : text.matches("-?\\d+\\.\\d+") ? (Object) Double.valueOf(text) : text;
        };
    }

    /** An output class with setters, as rules for a patch language set them. */
    public static final class Decision {
        private boolean approved;
        private double rate;
        private Object value;
        private String valueSetter;

        public void setApproved(boolean approved) {
            this.approved = approved;
        }

        public void setRate(double rate) {
            this.rate = rate;
        }

        public void setValue(String value) {
            this.value = value;
            this.valueSetter = "String";
        }

        public void setValue(Integer value) {
            this.value = value;
            this.valueSetter = "Integer";
        }

        public void setRefused(String reason) {
            throw new IllegalStateException("refused: " + reason);
        }

        public void setFatal(String ignored) {
            throw new OutOfMemoryError("simulated");
        }

        public static void setStaticValue(String ignored) {
            throw new AssertionError("a static method isn't a setter");
        }
    }

    /** An output without setters. */
    public record Summary(String text) {
    }

    private static Rule rule(String name, int priority, String action) {
        return Rule.builder().ruleName(name).priority(priority).language("patch").condition("c").action(action).build();
    }

    private static <O> RulesEngine<O> stateful(Supplier<O> output, Rule... rules) {
        RulesEngine<O> engine = RulesEngineBuilder.<O>allMatches(output).language(PATCH).build();
        engine.load(List.of(rules));
        return engine;
    }

    @Test
    @DisplayName("on a Map output, each property is put")
    void mapOutput() {
        RulesEngine<Map<String, Object>> engine = stateful(HashMap::new, rule("r", 1, "approved=true;note=null"));

        Map<String, Object> expected = new HashMap<>();
        expected.put("approved", true);
        expected.put("note", null);
        assertEquals(expected, engine.run(new FactMap<>()));
    }

    @Test
    @DisplayName("on a bean, each property is set with its setter, boxed values reaching primitive parameters")
    void beanOutput() {
        RulesEngine<Decision> engine = stateful(Decision::new, rule("r", 1, "approved=true;rate=4.5"));

        Decision decision = engine.run(new FactMap<>());

        assertTrue(decision.approved);
        assertEquals(4.5, decision.rate);
    }

    @Test
    @DisplayName("of overloaded setters, the one whose parameter accepts the value is used")
    void overloadedSetter() {
        Decision number = stateful(Decision::new, rule("r", 1, "value=7")).run(new FactMap<>());
        Decision text = stateful(Decision::new, rule("r", 1, "value=seven")).run(new FactMap<>());
        Decision none = stateful(Decision::new, rule("r", 1, "value=null")).run(new FactMap<>());

        assertEquals("Integer", number.valueSetter);
        assertEquals(7, number.value);
        assertEquals("String", text.valueSetter);
        assertNull(none.value, "null goes to the first overload whose parameter isn't primitive");
        assertEquals("Integer", none.valueSetter);
    }

    @Test
    @DisplayName("in a run that fires every match, rules' properties are set in firing order, so a later rule overwrites an earlier one")
    void firingOrder() {
        RulesEngine<Map<String, Object>> engine = stateful(LinkedHashMap::new,
                rule("first", 2, "rate=1;first=true"),
                rule("second", 1, "rate=2"));

        assertEquals(Map.of("rate", 2, "first", true), engine.run(new FactMap<>()));
    }

    @Test
    @DisplayName("afterExecute sees the output with the properties the action returned already on it")
    void afterExecuteSeesTheProperties() {
        List<Map<?, ?>> audited = new ArrayList<>();
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(PATCH).listener(new RuleListener() {
                    @Override
                    public void afterExecute(Rule rule, Object output) {
                        // A listener that audits what a rule did reads the output as the rule left it.
                        audited.add(new HashMap<>((Map<?, ?>) output));
                    }
                }).build();
        engine.load(List.of(rule("r", 1, "approved=true")));

        engine.run(new FactMap<>());

        assertEquals(List.of(Map.of("approved", true)), audited);
    }

    @Test
    @DisplayName("a property without a setter that accepts the value fails the rule as an action, and listeners hear of it")
    void noSetter() {
        List<RuleExecutionException> errors = new ArrayList<>();
        RulesEngine<Decision> engine = RulesEngineBuilder.allMatches(Decision::new).language(PATCH)
                .listener(new RuleListener() {
                    @Override
                    public void onError(Rule rule, RuleExecutionException exception) {
                        errors.add(exception);
                    }
                })
                .build();
        engine.load(List.of(rule("r", 1, "missing=1")));

        RuleExecutionException ex = assertThrows(RuleExecutionException.class, () -> engine.run(new FactMap<>()));

        assertEquals("Failed to set 'missing' on the output for rule 'r': " + Decision.class.getName()
                + " has no public method setMissing that accepts a java.lang.Integer", ex.getMessage());
        assertEquals("r", ex.getRuleName());
        assertEquals(ExpressionKind.ACTION, ex.getExpressionKind());
        assertEquals(List.of(ex), errors);
    }

    @Test
    @DisplayName("null can't be set through a primitive parameter, a static method or a record without setters")
    void unsettable() {
        RuleExecutionException primitive = assertThrows(RuleExecutionException.class,
                () -> stateful(Decision::new, rule("r", 1, "rate=null")).run(new FactMap<>()));
        RuleExecutionException staticMethod = assertThrows(RuleExecutionException.class,
                () -> stateful(Decision::new, rule("r", 1, "staticValue=x")).run(new FactMap<>()));
        RuleExecutionException record = assertThrows(RuleExecutionException.class,
                () -> stateful(() -> new Summary("s"), rule("r", 1, "text=t")).run(new FactMap<>()));

        assertTrue(primitive.getMessage().endsWith("has no public method setRate that accepts null"),
                primitive.getMessage());
        assertTrue(staticMethod.getMessage().endsWith("has no public method setStaticValue that accepts a "
                + "java.lang.String"), staticMethod.getMessage());
        assertTrue(record.getMessage().endsWith(Summary.class.getName() + " has no public method setText that accepts "
                + "a java.lang.String"), record.getMessage());
    }

    @Test
    @DisplayName("a setter that throws fails the rule with the setter's exception as the cause")
    void setterThrows() {
        RuleExecutionException ex = assertThrows(RuleExecutionException.class,
                () -> stateful(Decision::new, rule("r", 1, "refused=no")).run(new FactMap<>()));

        assertEquals("Failed to set 'refused' on the output for rule 'r': refused: no", ex.getMessage());
        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }

    @Test
    @DisplayName("a fatal Error from a setter is rethrown unchanged")
    void setterFatalError() {
        RulesEngine<Decision> engine = stateful(Decision::new, rule("r", 1, "fatal=x"));

        OutOfMemoryError error = assertThrows(OutOfMemoryError.class, () -> engine.run(new FactMap<>()));

        assertEquals("simulated", error.getMessage());
    }

    @Test
    @DisplayName("an unmodifiable Map output fails the rule")
    void unmodifiableMap() {
        RuleExecutionException ex = assertThrows(RuleExecutionException.class,
                () -> stateful(Map::of, rule("r", 1, "a=1")).run(new FactMap<>()));

        assertTrue(ex.getMessage().startsWith("Failed to set 'a' on the output for rule 'r': "), ex.getMessage());
    }

    @Test
    @DisplayName("an action that returns no result fails the rule as an action")
    void nullResult() {
        RuleExecutionException ex = assertThrows(RuleExecutionException.class,
                () -> stateful(HashMap::new, rule("r", 1, "nothing")).run(new FactMap<>()));

        assertEquals("Action for rule 'r' returned no result. An action returns ActionResult.done() or "
                + "ActionResult.set(...).", ex.getMessage());
        assertEquals(ExpressionKind.ACTION, ex.getExpressionKind());
    }
}
