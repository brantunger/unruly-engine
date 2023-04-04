package com.unruly.service;

import com.unruly.model.Rule;

import java.util.List;

public interface RulesEngine<I, O> {

    /**
     * Fire rules engine against the rules supplied by the rules list.
     * @param ruleList This is a list of {@link Rule} objects to run through the rules engine
     * @param inputData The set of input data to fire the rules engine against
     * @return The output of firing the actions of each {@link Rule} object
     */
    O run(List<Rule> ruleList, I inputData);
}
