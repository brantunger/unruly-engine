package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.api.exception.InvalidExpressionException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A tiny expression language for tests. It keeps its own variables and never writes to the map it reads facts from,
 * so tests that use it show the engine's guarantees don't depend on how MVEL works.
 *
 * <ul>
 *     <li>A condition is {@code OPERAND} or {@code OPERAND OPERATOR OPERAND}, where the operator is {@code ==},
 *     {@code >}, {@code >=}, {@code <} or {@code <=}. {@code ==} compares any two values; the others compare two
 *     numbers, and fail the rule when either side isn't one.</li>
 *     <li>An action is statements separated by {@code ;}: {@code let NAME = OPERAND} declares a variable, and
 *     {@code put KEY OPERAND} puts a value into the output map, or, for a language that returns properties, into the
 *     action's {@link ActionResult}.</li>
 *     <li>An operand is an integer, a string in single quotes and without spaces, {@code true}, {@code false},
 *     {@code null}, the name of a variable or fact, or {@code fact.property}, which {@link FactProperties#read}
 *     reads from a record, a bean or a map.</li>
 * </ul>
 *
 * <p>
 * Every token is separated by whitespace, so a string operand can't contain a space.
 * </p>
 *
 * <p>
 * Its compiled expressions keep no state, so its session is {@link Session#none()}. It relies on the default
 * {@code checkFactName()}.
 * </p>
 */
public final class ToyExpressionLanguage implements ExpressionLanguage {

    /** The name the language has unless another is given. */
    public static final String LANGUAGE_NAME = "toy";

    /** The operators a condition can compare two operands with. */
    private static final Set<String> OPERATORS = Set.of("==", ">", ">=", "<", "<=");

    private final String languageName;
    private final boolean returnsProperties;

    public ToyExpressionLanguage() {
        this(LANGUAGE_NAME);
    }

    public ToyExpressionLanguage(String languageName) {
        this(languageName, false);
    }

    /**
     * Creates the language.
     *
     * @param languageName      Its name
     * @param returnsProperties Whether an action returns what it puts as properties, instead of changing the output
     */
    public ToyExpressionLanguage(String languageName, boolean returnsProperties) {
        this.languageName = languageName;
        this.returnsProperties = returnsProperties;
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

    /** One statement of an action, given the action's context, its variables and the map that {@code put} writes to. */
    @FunctionalInterface
    private interface Statement {
        void run(ActionContext context, Map<String, Object> locals, Map<String, Object> target);
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
        if (tokens.size() == 3 && OPERATORS.contains(tokens.get(1))) {
            String left = tokens.get(0);
            String operator = tokens.get(1);
            String right = tokens.get(2);
            return (context, session) -> compare(operator, value(left, context.facts(), Map.of()),
                    value(right, context.facts(), Map.of()));
        }
        throw new IllegalArgumentException("syntax error in condition '" + source + "'");
    }

    /**
     * Compares two operands. {@code ==} compares with {@link Objects#equals}, so numbers of different types aren't
     * equal; the ordering operators compare numerically, whatever kind of {@link Number} each side is.
     */
    private static boolean compare(String operator, Object left, Object right) {
        if ("==".equals(operator)) {
            return Objects.equals(left, right);
        }
        if (!(left instanceof Number leftNumber) || !(right instanceof Number rightNumber)) {
            throw new IllegalStateException("'" + operator + "' compares numbers, not '" + left + "' and '" + right
                    + "'");
        }
        int order = Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue());
        return switch (operator) {
            case ">" -> order > 0;
            case ">=" -> order >= 0;
            case "<" -> order < 0;
            case "<=" -> order <= 0;
            default -> throw new IllegalStateException("unknown operator '" + operator + "'");
        };
    }

    private CompiledAction action(String source) {
        List<Statement> statements = new ArrayList<>();
        for (String statement : source.split(";")) {
            List<String> tokens = tokens(statement);
            if (tokens.size() >= 2 && ActionContext.OUTPUT_NAME.equals(tokens.get(0)) && "=".equals(tokens.get(1))) {
                throw new InvalidExpressionException("assigns output, which an action can't replace");
            }
            if (tokens.size() == 4 && "let".equals(tokens.get(0)) && "=".equals(tokens.get(2))) {
                String variable = tokens.get(1);
                String operand = tokens.get(3);
                statements.add((context, locals, target) ->
                        locals.put(variable, value(operand, context.facts(), locals)));
            } else if (tokens.size() == 3 && "put".equals(tokens.get(0))) {
                String key = tokens.get(1);
                String operand = tokens.get(2);
                statements.add((context, locals, target) -> target.put(key, value(operand, context.facts(), locals)));
            } else if (!tokens.isEmpty()) {
                throw new IllegalArgumentException("syntax error in action statement '" + statement.trim() + "'");
            }
        }
        return (context, session) -> {
            Map<String, Object> locals = new HashMap<>();
            Map<String, Object> target = returnsProperties ? new LinkedHashMap<>() : output(context);
            statements.forEach(statement -> statement.run(context, locals, target));
            return returnsProperties ? ActionResult.set(target) : ActionResult.done();
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
        if (token.length() >= 2 && token.startsWith("'") && token.endsWith("'")) {
            return token.substring(1, token.length() - 1);
        }
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
        int dot = token.indexOf('.');
        if (dot > 0) {
            // A record component, a getter or a map key, without this language knowing which: that's the point of
            // the helper. A property that isn't there throws, so a misspelling can't read as false.
            Object fact = value(token.substring(0, dot), facts, locals);
            return FactProperties.read(Objects.requireNonNull(fact, "fact '" + token + "' is null"),
                    token.substring(dot + 1));
        }
        throw new IllegalStateException("unknown name '" + token + "'");
    }
}
