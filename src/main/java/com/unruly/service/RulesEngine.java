package com.unruly.service;

import com.unruly.model.Rule;

import java.util.List;

public interface RulesEngine<I, O> {

    O run(List<Rule> ruleList, I inputData);
}
