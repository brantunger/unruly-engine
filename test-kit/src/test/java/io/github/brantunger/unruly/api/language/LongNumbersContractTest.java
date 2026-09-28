package io.github.brantunger.unruly.api.language;

import org.junit.jupiter.api.DisplayName;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The contract test for a language whose whole numbers are {@code Long}s, as CEL's are: its actions return the facts
 * they put as {@code Long}s. A faithful adapter for such a language passes without converting numbers for the kit
 * (#347).
 */
@DisplayName("a language whose whole numbers are Longs keeps the expression-language contract")
class LongNumbersContractTest extends ToyPropertiesContractTest {

    @Override
    protected ExpressionLanguage language() {
        return longNumbers(new ToyExpressionLanguage("toy-longs", true));
    }

    /**
     * Wraps a language that returns its results as properties, so every {@link Integer} it returns is a {@link Long}.
     *
     * @param language The language to wrap
     * @return The wrapped language
     */
    static ExpressionLanguage longNumbers(ExpressionLanguage language) {
        return new ForwardingExpressionLanguage(language) {
            @Override
            public ExpressionCompiler newCompiler(CompileContext context) {
                ExpressionCompiler compiler = language.newCompiler(context);
                return new ForwardingExpressionCompiler(compiler) {
                    @Override
                    public CompiledAction compileAction(Expression expression) {
                        CompiledAction action = compiler.compileAction(expression);
                        return (actionContext, session) -> {
                            Map<String, Object> properties = new LinkedHashMap<>();
                            action.execute(actionContext, session).properties().forEach((key, value) ->
                                    properties.put(key, value instanceof Integer number ? number.longValue() : value));
                            return ActionResult.set(properties);
                        };
                    }
                };
            }
        };
    }
}
