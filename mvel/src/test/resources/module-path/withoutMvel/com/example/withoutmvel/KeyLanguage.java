package com.example.withoutmvel;

import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.FactProperties;
import io.github.brantunger.unruly.api.language.Session;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A minimal language. A condition is the name of a fact, or a fact's property written {@code fact.property}, and is
 * true when that value is {@code true}; the property is read with {@link FactProperties}, so a record fact costs the
 * language nothing. An action is {@code property=value}, or several separated by {@code ;}, which it returns for the
 * engine to set on the output object: {@code true} and {@code false} are booleans, a value with a decimal point is a
 * {@code double}, and anything else is text.
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
                int dot = source.indexOf('.');
                if (dot < 0) {
                    return (evaluation, session) -> Boolean.TRUE.equals(evaluation.facts().get(source));
                }
                String fact = source.substring(0, dot);
                String property = source.substring(dot + 1);
                return (evaluation, session) ->
                        Boolean.TRUE.equals(FactProperties.read(evaluation.facts().get(fact), property));
            }

            @Override
            public CompiledAction compileAction(Expression expression) {
                Map<String, Object> properties = propertiesOf(expression.text());
                return (action, session) -> ActionResult.set(properties);
            }

            @Override
            public Session newSession() {
                return Session.none();
            }
        };
    }

    private static Map<String, Object> propertiesOf(String source) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String assignment : source.split(";")) {
            String[] nameAndValue = assignment.split("=", 2);
            properties.put(nameAndValue[0], valueOf(nameAndValue[1]));
        }
        return properties;
    }

    /** The value as the type its text reads as, because a setter takes the type it was declared with. */
    private static Object valueOf(String text) {
        if ("true".equals(text) || "false".equals(text)) {
            return Boolean.valueOf(text);
        }
        if (text.indexOf('.') >= 0) {
            return Double.valueOf(text);
        }
        return text;
    }
}
