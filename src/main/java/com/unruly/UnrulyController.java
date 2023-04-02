package com.unruly;

import com.unruly.model.LoanDetails;
import com.unruly.model.Rule;
import com.unruly.model.UserDetails;
import com.unruly.service.InferenceEngine;
import com.unruly.service.KnowledgeBase;
import com.unruly.service.RuleEngine;
import com.unruly.service.RuleParser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(value = "/api")
public class UnrulyController {

    private final KnowledgeBase knowledgeBase;
    private final RuleEngine<UserDetails, LoanDetails> ruleEngine;
    private final InferenceEngine<UserDetails, LoanDetails> inferenceEngine;

    public UnrulyController(KnowledgeBase knowledgeBase,
                            RuleEngine<UserDetails, LoanDetails> ruleEngine,
                            RuleParser<UserDetails, LoanDetails> ruleParser) {
        this.knowledgeBase = knowledgeBase;
        this.ruleEngine = ruleEngine;
        this.inferenceEngine = new InferenceEngine<>(ruleParser, LoanDetails::new);
    }

    @GetMapping(value = "/rules")
    public ResponseEntity<?> getAllRules() {
        List<Rule> allRules = knowledgeBase.getAllRules();
        return ResponseEntity.ok(allRules);
    }

    @PostMapping(value = "/loan")
    public ResponseEntity<?> postLoan(@RequestBody UserDetails userDetails) {
        LoanDetails result = ruleEngine.run(inferenceEngine, userDetails);
        return ResponseEntity.ok(result);
    }
}
