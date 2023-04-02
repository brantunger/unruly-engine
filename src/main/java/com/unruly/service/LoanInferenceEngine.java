package com.unruly.service;

import com.unruly.model.LoanDetails;
import com.unruly.model.RuleNamespace;
import com.unruly.model.UserDetails;
import org.springframework.stereotype.Service;

@Service
public class LoanInferenceEngine extends InferenceEngine<UserDetails, LoanDetails> {

    public LoanInferenceEngine(RuleParser<UserDetails, LoanDetails> ruleParser) {
        super(ruleParser);
    }

    @Override
    public RuleNamespace getRuleNamespace() {
        return RuleNamespace.LOAN;
    }

    @Override
    protected LoanDetails initializeOutputResult() {
        return LoanDetails.builder().build();
    }
}
