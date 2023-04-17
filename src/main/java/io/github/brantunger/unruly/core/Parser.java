package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactStore;

public interface Parser<O> {

    boolean parseCondition(String expression, FactStore<Object> facts);

    O parseAction(String expression, O outputResult);
}
