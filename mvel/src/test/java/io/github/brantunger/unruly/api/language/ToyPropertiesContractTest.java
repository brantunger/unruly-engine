package io.github.brantunger.unruly.api.language;

import org.junit.jupiter.api.DisplayName;

/**
 * The contract test for a language whose actions return their results as properties instead of changing the output,
 * as CEL or JsonLogic would. The engine sets the properties, and the checks that need an assignment to the output are
 * skipped. It stands in for JsonLogic's reading of a missing property too, which is {@code null} rather than an
 * error, so the check that a misspelled property fails is skipped as well.
 */
@DisplayName("a language whose actions return properties keeps the expression-language contract")
class ToyPropertiesContractTest extends ToyExpressionLanguageContractTest {

    @Override
    protected ExpressionLanguage language() {
        return new ToyExpressionLanguage("toy-properties", true);
    }

    @Override
    protected String reassignOutput() {
        return null;
    }

    @Override
    protected String missingFactProperty(String fact, String property, int value) {
        return null;
    }
}
