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

    // The toy's == is Objects.equals and its literals are Integers, so 1L and 1 are different values to it.
    @Override
    protected boolean comparesWholeNumbersByValue() {
        return false;
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

    @Override
    protected String factProperty(String fact, String property, int value) {
        return fact + "." + property + " == " + value;
    }

    @Override
    protected String missingFactProperty(String fact, String property, int value) {
        return factProperty(fact, property, value);
    }
}
