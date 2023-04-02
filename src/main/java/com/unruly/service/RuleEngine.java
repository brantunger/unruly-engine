package com.unruly.service;

import com.unruly.model.Rule;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RuleEngine<I, O> {

    private final KnowledgeBase knowledgeBase;

    public RuleEngine(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    public O run(InferenceEngine<I, O> inferenceEngine, I inputData) {
        String ruleNamespace = inferenceEngine.getRuleNamespace().toString();
        // TODO: Here for each call, we are fetching all rules from db. It should be cache.
        List<Rule> allRulesByNamespace = knowledgeBase.getAllRuleByNamespace(ruleNamespace);
        return inferenceEngine.run(allRulesByNamespace, inputData);
    }

}
