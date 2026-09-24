package com.example.withtestkit;

import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.mvel.MvelExpressionLanguage;
import io.github.brantunger.unruly.test.ExpressionLanguageContractTest;

import java.util.Collection;
import java.util.List;

class MvelContractTest extends ExpressionLanguageContractTest {

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

    // Main counts a skipped check as one that didn't pass, so the check this hook skips by default runs here.
    @Override
    protected Collection<String> usableFactNames() {
        return List.of("credit_score2");
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
