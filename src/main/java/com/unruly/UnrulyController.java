package com.unruly;

import com.unruly.model.LoanDetails;
import com.unruly.model.Rule;
import com.unruly.model.UserDetails;
import com.unruly.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(value = "/api")
public class UnrulyController {

    private final KnowledgeBase knowledgeBase;
    private final RulesEngine<UserDetails, LoanDetails> statelessRuleEngine;
    private final RulesEngine<UserDetails, LoanDetails> statefulRulesEngine;

    public UnrulyController(KnowledgeBase knowledgeBase,
                            RuleParser<UserDetails, LoanDetails> ruleParser) {
        this.knowledgeBase = knowledgeBase;
        this.statelessRuleEngine = new StatelessRuleEngine<>(ruleParser, LoanDetails::new);
        this.statefulRulesEngine = new StatefulRulesEngine<>(ruleParser, LoanDetails::new);
    }

    @GetMapping(value = "/rules")
    public ResponseEntity<?> getAllRules() {
        List<Rule> allRules = knowledgeBase.getAllRules();
        return ResponseEntity.ok(allRules);
    }

    @PostMapping(value = "/loan")
    public ResponseEntity<?> postLoan(@RequestBody UserDetails userDetails) {
        // TODO: Here we use a DB to get rules, it might need to be a cache
        List<Rule> allRules = knowledgeBase.getAllRules();
        LoanDetails result = statefulRulesEngine.run(allRules, userDetails);
        return ResponseEntity.ok(result);
    }
}
