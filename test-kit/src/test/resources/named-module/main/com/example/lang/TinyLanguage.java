package com.example.lang;

import io.github.brantunger.unruly.api.language.ActionResult;
import io.github.brantunger.unruly.api.language.CompileContext;
import io.github.brantunger.unruly.api.language.CompiledAction;
import io.github.brantunger.unruly.api.language.CompiledCondition;
import io.github.brantunger.unruly.api.language.Expression;
import io.github.brantunger.unruly.api.language.ExpressionCompiler;
import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.api.language.FactProperties;
import io.github.brantunger.unruly.api.language.Session;

import java.math.BigDecimal;
import java.util.Map;

/**
 * A language small enough to keep the contract. A condition is {@code true}, {@code NAME}, {@code NAME == INT} or
 * {@code NAME.PROPERTY == INT}, and an action is {@code put KEY NAME}, {@code let NAME INT}, whose variable is local
 * to the action, or {@code output = new}, which fails.
 */
public final class TinyLanguage implements ExpressionLanguage {

    @Override
    public String name() {
        return "tiny";
    }

    @Override
    public ExpressionCompiler newCompiler(CompileContext context) {
        return new ExpressionCompiler() {
            @Override
            public CompiledCondition compileCondition(Expression expression) {
                String text = expression.text().trim();
                if (text.equals("true")) {
                    return (evaluation, session) -> Boolean.TRUE;
                }
                if (text.matches("\\w+")) {
                    return (evaluation, session) -> fact(evaluation.facts(), text);
                }
                if (text.matches("\\w+[.]\\w+ == -?\\d+")) {
                    String[] sides = text.split(" == ");
                    String[] path = sides[0].split("[.]");
                    int value = Integer.parseInt(sides[1]);
                    return (evaluation, session) ->
                            equal(FactProperties.read(fact(evaluation.facts(), path[0]), path[1]), value);
                }
                if (text.matches("\\w+ == -?\\d+")) {
                    String[] sides = text.split(" == ");
                    int value = Integer.parseInt(sides[1]);
                    return (evaluation, session) -> equal(fact(evaluation.facts(), sides[0]), value);
                }
                throw new IllegalArgumentException("not a condition: " + text);
            }

            @Override
            public CompiledAction compileAction(Expression expression) {
                String text = expression.text().trim();
                String[] words = text.split(" ");
                if (words.length == 3 && words[0].equals("put")) {
                    return (action, session) -> ActionResult.set(Map.of(words[1], fact(action.facts(), words[2])));
                }
                if (words.length == 3 && words[0].equals("let")) {
                    return (action, session) -> ActionResult.done();
                }
                if (text.equals("output = new")) {
                    return (action, session) -> {
                        throw new IllegalStateException("an action can't replace the output");
                    };
                }
                throw new IllegalArgumentException("not an action: " + text);
            }

            @Override
            public Session newSession() {
                return Session.none();
            }

            @Override
            public void checkFactName(String name) {
                if (!name.matches("\\w+")) {
                    throw new IllegalArgumentException("not a name: " + name);
                }
            }
        };
    }

    private static Object fact(Map<String, ?> facts, String name) {
        if (!facts.containsKey(name)) {
            throw new IllegalArgumentException("unknown name " + name);
        }
        return facts.get(name);
    }

    private static boolean equal(Object fact, int value) {
        return fact instanceof Number number
                && new BigDecimal(number.toString()).compareTo(BigDecimal.valueOf(value)) == 0;
    }
}
