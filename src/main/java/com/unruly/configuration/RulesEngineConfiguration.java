package com.unruly.configuration;

import com.unruly.model.LoanDetails;
import com.unruly.model.UserDetails;
import com.unruly.service.RuleParser;
import com.unruly.service.RulesEngine;
import com.unruly.service.StatefulRulesEngine;
import com.unruly.service.StatelessRulesEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RulesEngineConfiguration {

    @Bean
    public RulesEngine<UserDetails, LoanDetails> statelessRulesEngine(RuleParser<UserDetails, LoanDetails> ruleParser) {
        return new StatelessRulesEngine<>(ruleParser, LoanDetails::new);
    }

    @Bean
    public RulesEngine<UserDetails, LoanDetails> statefulRulesEngine(RuleParser<UserDetails, LoanDetails> ruleParser) {
        return new StatefulRulesEngine<>(ruleParser, LoanDetails::new);
    }
}
