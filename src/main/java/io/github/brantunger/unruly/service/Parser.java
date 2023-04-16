package io.github.brantunger.unruly.service;

import io.github.brantunger.unruly.model.FactStore;

public interface Parser<O> {

    boolean parseCondition(String expression, FactStore<Object> facts);

    O parseAction(String expression, O outputResult);
}
