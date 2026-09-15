package io.github.brantunger.unruly.api.language;

import org.junit.jupiter.api.DisplayName;

/**
 * The contract test for a language whose actions return their results as properties instead of changing the output,
 * as CEL or JsonLogic would. The engine sets the properties, and the checks that need an assignment to the output are
 * skipped.
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
}
