package com.unruly;

import com.unruly.model.*;
import com.unruly.service.KnowledgeBase;
import com.unruly.service.RulesEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(value = "/api")
public class UnrulyController {

    private final KnowledgeBase knowledgeBase;
    private final RulesEngine<LoanDetails> statefulRulesEngine;

    public UnrulyController(KnowledgeBase knowledgeBase,
                            RulesEngine<LoanDetails> statefulRulesEngine) {
        this.knowledgeBase = knowledgeBase;
        this.statefulRulesEngine = statefulRulesEngine;
    }

    @GetMapping(value = "/rules")
    public ResponseEntity<?> getAllRules() {
        List<Rule> allRules = knowledgeBase.getAllRules();
        return ResponseEntity.ok(allRules);
    }

    @PostMapping(value = "/loan")
    public ResponseEntity<?> postLoan(@RequestBody UserDetails userDetails) {
        List<Rule> allRules = knowledgeBase.getAllRules();
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("input", userDetails);

        LoanDetails result = statefulRulesEngine.run(allRules, facts);
        return ResponseEntity.ok(result);
    }
}
