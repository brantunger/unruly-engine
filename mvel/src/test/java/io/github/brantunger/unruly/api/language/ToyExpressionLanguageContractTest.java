package io.github.brantunger.unruly.api.language;

import io.github.brantunger.unruly.test.ExpressionLanguageContractTest;
import org.junit.jupiter.api.DisplayName;

@DisplayName("the test-only language keeps the expression-language contract")
class ToyExpressionLanguageContractTest extends ExpressionLanguageContractTest {

    @Override
    protected ExpressionLanguage language() {
        return new ToyExpressionLanguage();
    }

    @Override
    protected String alwaysTrue() {
        return "true";
    }

    @Override
    protected String factEquals(String fact, int value) {
        return fact + " == " + value;
    }

    @Override
    protected String factValue(String fact) {
        return fact;
    }

    @Override
    protected String assignment(String fact, int value) {
        return fact + " = " + value;
    }

    @Override
    protected String putFact(String key, String fact) {
        return "put " + key + " " + fact;
    }

    @Override
    protected String declareVariable(String name, int value) {
        return "let " + name + " = " + value;
    }

    @Override
    protected String reassignOutput() {
        return "output = 1";
    }

    @Override
    protected String syntaxError() {
        return "x ==";
    }

    @Override
    protected String unusableFactName() {
        return null;
    }
}
