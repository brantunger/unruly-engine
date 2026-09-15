package com.example.withoutmvel;

import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;

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
            public CompiledCondition compileCondition(String source) {
                return evaluation -> Boolean.TRUE.equals(evaluation.facts().get(source));
            }

            @Override
            public CompiledAction compileAction(String source) {
                String[] keyAndValue = source.split("=", 2);
                return action -> output(action.output()).put(keyAndValue[0], keyAndValue[1]);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> output(Object output) {
        return (Map<String, Object>) output;
    }
}
