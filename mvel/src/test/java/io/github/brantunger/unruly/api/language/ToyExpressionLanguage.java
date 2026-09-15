package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.api.exception.InvalidExpressionException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * A tiny expression language for tests. It keeps its own variables and never writes to the map it reads facts from,
 * so tests that use it show the engine's guarantees don't depend on how MVEL works.
 *
 * <ul>
 *     <li>A condition is {@code OPERAND} or {@code OPERAND == OPERAND}.</li>
 *     <li>An action is statements separated by {@code ;}: {@code let NAME = OPERAND} declares a variable, and
 *     {@code put KEY OPERAND} puts a value into the output map.</li>
 *     <li>An operand is an integer, {@code true}, {@code false}, {@code null}, or the name of a variable or fact.</li>
 * </ul>
 *
 * <p>
 * Its compiled expressions keep no state, so its session is {@link Session#none()}. It relies on the default
 * {@code checkFactName()}.
 * </p>
 */
public final class ToyExpressionLanguage implements ExpressionLanguage {

    /** The name the language has unless another is given. */
    public static final String LANGUAGE_NAME = "toy";

    private final String languageName;

    public ToyExpressionLanguage() {
        this(LANGUAGE_NAME);
    }

    public ToyExpressionLanguage(String languageName) {
        this.languageName = languageName;
    }

    @Override
    public String name() {
        return languageName;
    }

    @Override
    public ExpressionCompiler newCompiler(CompileContext context) {
        return new ExpressionCompiler() {
            @Override
            public CompiledCondition compileCondition(Expression expression) {
                String source = expression.text();
                return condition(source);
            }

            @Override
            public CompiledAction compileAction(Expression expression) {
                String source = expression.text();
                return action(source);
            }

            @Override
            public Session newSession() {
                return Session.none();
            }
        };
    }

    private static CompiledCondition condition(String source) {
        List<String> tokens = tokens(source);
        if (tokens.contains("=")) {
            throw new InvalidExpressionException("contains an assignment");
        }
        if (tokens.size() == 1) {
            String operand = tokens.get(0);
            return (context, session) -> value(operand, context.facts(), Map.of());
        }
        if (tokens.size() == 3 && "==".equals(tokens.get(1))) {
            String left = tokens.get(0);
            String right = tokens.get(2);
            return (context, session) -> Objects.equals(value(left, context.facts(), Map.of()),
                    value(right, context.facts(), Map.of()));
        }
        throw new IllegalArgumentException("syntax error in condition '" + source + "'");
    }

    private static CompiledAction action(String source) {
        List<BiConsumer<ActionContext, Map<String, Object>>> statements = new ArrayList<>();
        for (String statement : source.split(";")) {
            List<String> tokens = tokens(statement);
            if (tokens.size() >= 2 && ActionContext.OUTPUT_NAME.equals(tokens.get(0)) && "=".equals(tokens.get(1))) {
                throw new InvalidExpressionException("assigns output, which an action can't replace");
            }
            if (tokens.size() == 4 && "let".equals(tokens.get(0)) && "=".equals(tokens.get(2))) {
                String variable = tokens.get(1);
                String operand = tokens.get(3);
                statements.add((context, locals) -> locals.put(variable, value(operand, context.facts(), locals)));
            } else if (tokens.size() == 3 && "put".equals(tokens.get(0))) {
                String key = tokens.get(1);
                String operand = tokens.get(2);
                statements.add((context, locals) -> output(context).put(key, value(operand, context.facts(), locals)));
            } else if (!tokens.isEmpty()) {
                throw new IllegalArgumentException("syntax error in action statement '" + statement.trim() + "'");
            }
        }
        return (context, session) -> {
            Map<String, Object> locals = new HashMap<>();
            statements.forEach(statement -> statement.accept(context, locals));
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> output(ActionContext context) {
        return (Map<String, Object>) context.output();
    }

    private static List<String> tokens(String source) {
        String trimmed = source.trim();
        return trimmed.isEmpty() ? List.of() : Arrays.asList(trimmed.split("\\s+"));
    }

    private static Object value(String token, Map<String, Object> facts, Map<String, Object> locals) {
        if ("true".equals(token) || "false".equals(token)) {
            return Boolean.valueOf(token);
        }
        if ("null".equals(token)) {
            return null;
        }
        if (token.matches("-?\\d+")) {
            return Integer.valueOf(token);
        }
        if (locals.containsKey(token)) {
            return locals.get(token);
        }
        if (facts.containsKey(token)) {
            return facts.get(token);
        }
        throw new IllegalStateException("unknown name '" + token + "'");
    }
}
