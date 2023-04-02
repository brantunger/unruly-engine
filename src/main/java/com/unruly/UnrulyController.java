package com.unruly;

import com.unruly.model.LoanDetails;
import com.unruly.model.Rule;
import com.unruly.model.UserDetails;
import com.unruly.service.KnowledgeBase;
import com.unruly.service.LoanInferenceEngine;
import com.unruly.service.RuleEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(value = "/api")
public class UnrulyController {

    private final KnowledgeBase knowledgeBase;
    private final RuleEngine<UserDetails, LoanDetails> ruleEngine;
    private final LoanInferenceEngine loanInferenceEngine;

    public UnrulyController(KnowledgeBase knowledgeBase,
                            RuleEngine<UserDetails, LoanDetails> ruleEngine,
                            LoanInferenceEngine loanInferenceEngine) {
        this.knowledgeBase = knowledgeBase;
        this.ruleEngine = ruleEngine;
        this.loanInferenceEngine = loanInferenceEngine;
    }

    @GetMapping(value = "/rules")
    public ResponseEntity<?> getAllRules() {
        List<Rule> allRules = knowledgeBase.getAllRules();
        return ResponseEntity.ok(allRules);
    }

    @PostMapping(value = "/loan")
    public ResponseEntity<?> postLoan(@RequestBody UserDetails userDetails) {
        LoanDetails result = ruleEngine.run(loanInferenceEngine, userDetails);
        return ResponseEntity.ok(result);
    }
}
