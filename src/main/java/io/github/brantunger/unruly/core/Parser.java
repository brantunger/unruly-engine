package io.github.brantunger.unruly.core;

import io.github.brantunger.unruly.api.FactStore;

import java.io.Serializable;

/**
 * The Parser is an interface that parses conditional expressions and action expressions. When the condition expression
 * evaluates to <strong>true</strong> the action expression can be executed by the {@link RulesEngine}
 *
 * @param <O> The output object to instantiate when the action is fired
 */
public interface Parser<O> {

    /**
     * Parse the condition expression and evaluate it to either a <strong>true</strong> or a <strong>false</strong>
     * value.
     *
     * @param compiledExpression The serialized expression to evaluate
     * @param factStore          A key/value store of all the facts to be evaluated by the parser
     * @return A <strong>true</strong> or a <strong>false</strong> value after the condition is evaluated
     */
    boolean parseCondition(Serializable compiledExpression, FactStore<Object> factStore);

    /**
     * Parse the action expression that is fired by the {@link RulesEngine} and instantiate the output object type.
     *
     * @param compiledExpression The serialized expression to evaluate
     * @param outputResult       The output object
     * @return The output object that is instantiated when the action is fired
     */
    O parseAction(Serializable compiledExpression, O outputResult);
}
