package io.github.brantunger.unruly.mvel;

import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.test.ExpressionLanguageContractTest;
import org.junit.jupiter.api.DisplayName;

import java.util.Collection;
import java.util.List;

@DisplayName("MVEL keeps the expression-language contract")
class MvelExpressionLanguageContractTest extends ExpressionLanguageContractTest {

    @Override
    protected ExpressionLanguage language() {
        return new MvelExpressionLanguage();
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
        return "output.put('" + key + "', " + fact + ")";
    }

    @Override
    protected String declareVariable(String name, int value) {
        return name + " = " + value;
    }

    @Override
    protected String copyThroughVariable(String key, String fact) {
        return "tmp = " + fact + "; output.put('" + key + "', tmp)";
    }

    @Override
    protected String reassignOutput() {
        return "output = new java.util.HashMap()";
    }

    @Override
    protected String syntaxError() {
        return "x >= ";
    }

    @Override
    protected String unusableFactName() {
        return "empty";
    }

    // Java identifiers MVEL reads as facts: with an underscore and a digit, a leading underscore and a dollar sign.
    @Override
    protected Collection<String> usableFactNames() {
        return List.of("credit_score2", "_score", "$total");
    }

    @Override
    protected String factProperty(String fact, String property, int value) {
        return fact + "." + property + " == " + value;
    }

    @Override
    protected String missingFactProperty(String fact, String property, int value) {
        return factProperty(fact, property, value);
    }

    @Override
    protected String putFactProperty(String key, String fact, String property) {
        return "output.put('" + key + "', " + fact + "." + property + ")";
    }
}
