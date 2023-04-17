# unruly-engine
[![Java CI with Gradle](https://github.com/brantunger/unruly-engine/actions/workflows/build.yml/badge.svg)](https://github.com/brantunger/unruly-engine/actions/workflows/build.yml)

Unruly is a pure Java rule engine that parses and evaluates using MVEL based rules

## Using Unruly Engine
The recommended way to use the Unruly Engine is through some mechanism of dependency injection framework like Spring.

### Create the Configuration Beans
Creating the main beans to use is simple. Let's say you want to create two rules engines, one stateful and one stateless both outputting to your `LoanDetails` object.

```java
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
```

### Use the rules engine bean
You might then use one of the rules engine like this practical example:

```java
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

    @PostMapping(value = "/loan")
    public ResponseEntity<?> postLoan(@RequestBody UserDetails userDetails) {
        List<Rule> allRules = knowledgeBase.getAllRules();
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("input", userDetails);

        LoanDetails result = statefulRulesEngine.run(allRules, facts);
        return ResponseEntity.ok(result);
    }
}
```
