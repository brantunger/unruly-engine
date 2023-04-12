package com.unruly.service;

import com.unruly.model.FactStore;

public interface Parser<O> {

    boolean parseCondition(String expression, FactStore<Object> facts);

    O parseAction(String expression, O outputResult);
}
