package com.unruly.configuration;

import com.unruly.model.LoanDetails;
import com.unruly.service.RuleParser;
import com.unruly.service.RulesEngine;
import com.unruly.service.StatefulRulesEngine;
import com.unruly.service.StatelessRulesEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RulesEngineConfiguration {

    @Bean
    public RulesEngine<LoanDetails> statelessRulesEngine(RuleParser<LoanDetails> ruleParser) {
        return new StatelessRulesEngine<>(ruleParser, LoanDetails::new);
    }

    @Bean
    public RulesEngine<LoanDetails> statefulRulesEngine(RuleParser<LoanDetails> ruleParser) {
        return new StatefulRulesEngine<>(ruleParser, LoanDetails::new);
    }
}
