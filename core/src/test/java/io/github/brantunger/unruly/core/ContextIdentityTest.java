package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactMap;
import io.github.brantunger.unruly.api.Rule;
import io.github.brantunger.unruly.api.RulesEngine;
import io.github.brantunger.unruly.api.RulesEngineBuilder;
import io.github.brantunger.unruly.api.language.ActionContext;
import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.EvaluationContext;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The contexts the engine passes to a language compare by identity, as {@code RunContext} does: a context equals only
 * itself, a run passes one evaluation context to every condition, and each action gets a new action context. Uses
 * only the public API, so it compiles against the engine before the fix.
 */
@DisplayName("the contexts an engine passes to a language equal only themselves")
class ContextIdentityTest {

    /** A language whose conditions match and whose actions do nothing, and which keeps every context it's given. */
    private static final class RecordingLanguage implements ExpressionLanguage {

        private final List<EvaluationContext> conditions = new CopyOnWriteArrayList<>();
        private final List<ActionContext> actions = new CopyOnWriteArrayList<>();

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public ExpressionCompiler newCompiler(CompileContext context) {
            return new ExpressionCompiler() {
                @Override
                public CompiledCondition compileCondition(Expression expression) {
                    return (evaluation, session) -> {
                        conditions.add(evaluation);
                        return true;
                    };
                }

                @Override
                public CompiledAction compileAction(Expression expression) {
                    return (action, session) -> {
                        actions.add(action);
                        return ActionResult.done();
                    };
                }

                @Override
                public Session newSession() {
                    return Session.none();
                }
            };
        }
    }

    private static RulesEngine<Map<String, Object>> engine(RecordingLanguage language) {
        RulesEngine<Map<String, Object>> engine = RulesEngineBuilder.<Map<String, Object>>allMatches(HashMap::new)
                .language(language).build();
        engine.load(List.of(Rule.builder().ruleName("first").condition("any").action("any").build(),
                Rule.builder().ruleName("second").condition("any").action("any").build()));
        return engine;
    }

    @Test
    @DisplayName("a run's conditions share one context, and each action gets a new one")
    void oneEvaluationContextPerRunAndOneActionContextPerAction() {
        RecordingLanguage language = new RecordingLanguage();

        engine(language).run(new FactMap<>());

        assertEquals(2, language.conditions.size());
        assertEquals(2, language.actions.size());
        assertSame(language.conditions.get(0), language.conditions.get(1));
        assertNotSame(language.actions.get(0), language.actions.get(1));
        assertNotEquals(language.actions.get(0), language.actions.get(1));
        assertNotEquals(language.conditions.get(0), language.actions.get(0));
    }

    @Test
    @DisplayName("two runs with equal facts and equal outputs get contexts that aren't equal")
    void twoRunsWithEqualFactsGetUnequalContexts() {
        RecordingLanguage language = new RecordingLanguage();
        RulesEngine<Map<String, Object>> engine = engine(language);
        FactMap<Object> facts = new FactMap<>();
        facts.setValue("x", 1);

        engine.run(facts);
        engine.run(facts);

        assertEquals(4, language.conditions.size());
        assertEquals(4, language.actions.size());
        assertNotEquals(language.conditions.get(0), language.conditions.get(2));
        assertNotEquals(language.actions.get(0), language.actions.get(2));
    }
}
