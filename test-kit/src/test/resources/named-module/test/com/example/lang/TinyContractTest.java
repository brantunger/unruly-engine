package com.example.lang;

import io.github.brantunger.unruly.api.language.ExpressionLanguage;
import io.github.brantunger.unruly.test.ExpressionLanguageContractTest;

public class TinyContractTest extends ExpressionLanguageContractTest {

    @Override
    protected ExpressionLanguage language() {
        return new TinyLanguage();
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
        return "let " + name + " " + value;
    }

    @Override
    protected String reassignOutput() {
        return "output = new";
    }

    @Override
    protected String syntaxError() {
        return "((";
    }

    @Override
    protected String unusableFactName() {
        return "not a name";
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
