package com.example.withoutmvel;

import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.Session;

import java.util.Map;

/**
 * A minimal language. A condition is the name of a fact, and is true when the fact is {@code true}. An action is
 * {@code KEY=VALUE}, and puts VALUE into the output map.
 */
public final class KeyLanguage implements ExpressionLanguage {

    @Override
    public String name() {
        return "key";
    }

    @Override
    public ExpressionCompiler newCompiler(CompileContext context) {
        return new ExpressionCompiler() {
            @Override
            public CompiledCondition compileCondition(Expression expression) {
                String source = expression.text();
                return (evaluation, session) -> Boolean.TRUE.equals(evaluation.facts().get(source));
            }

            @Override
            public CompiledAction compileAction(Expression expression) {
                String source = expression.text();
                String[] keyAndValue = source.split("=", 2);
                return (action, session) -> {
                    output(action.output()).put(keyAndValue[0], keyAndValue[1]);
                    return ActionResult.done();
                };
            }

            @Override
            public Session newSession() {
                return Session.none();
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> output(Object output) {
        return (Map<String, Object>) output;
    }
}
