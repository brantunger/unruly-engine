package com.unruly.configuration;

import com.unruly.model.LoanDetails;
import com.unruly.service.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RulesEngineConfiguration {

    @Bean
    public Parser<LoanDetails> ruleParser() {
        return new RuleParser<>();
    }

    @Bean
    public RulesEngine<LoanDetails> statelessRulesEngine(Parser<LoanDetails> ruleParser) {
        return new StatelessRulesEngine<>(ruleParser, LoanDetails::new);
    }

    @Bean
    public RulesEngine<LoanDetails> statefulRulesEngine(Parser<LoanDetails> ruleParser) {
        return new StatefulRulesEngine<>(ruleParser, LoanDetails::new);
    }
}
