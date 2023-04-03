package com.unruly;

import com.unruly.model.LoanDetails;
import com.unruly.model.Rule;
import com.unruly.model.UserDetails;
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

    public UnrulyController(KnowledgeBase knowledgeBase,
                            RuleParser<UserDetails, LoanDetails> ruleParser) {
        this.knowledgeBase = knowledgeBase;
        this.ruleEngine = new RuleEngine<>(ruleParser, LoanDetails::new);
    }

    @GetMapping(value = "/rules")
    public ResponseEntity<?> getAllRules() {
        List<Rule> allRules = knowledgeBase.getAllRules();
        return ResponseEntity.ok(allRules);
    }

    @PostMapping(value = "/loan")
    public ResponseEntity<?> postLoan(@RequestBody UserDetails userDetails) {
        // TODO: Here we use a DB to get rules, it should be a cache
        List<Rule> allRules = knowledgeBase.getAllRules();
        LoanDetails result = ruleEngine.run(allRules, userDetails);
        return ResponseEntity.ok(result);
    }
}
