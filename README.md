# unruly-engine
[![Java CI with Gradle](https://github.com/brantunger/unruly-engine/actions/workflows/build.yml/badge.svg)](https://github.com/brantunger/unruly-engine/actions/workflows/build.yml)

Unruly is a pure Java rule engine that parses and evaluates using MVEL based rules

## Introduction
The Unruly Engine has two rules engine implementations.
- StatelessRulesEngine
- StatefulRulesEngine

### Stateful Rules Engine
In the **stateful** implementation, the rules engine fires all the actions of the rules when the condition field of the Rule returns true. In the stateful rules engine the rules are sorted by priority. The highest priority wins. The output object saves state in between each rule, so rules with lower priority may override the fields in the output object.

### Stateless Rules Engine
In the **stateless** implementation, the rules engine fires the action of a single rule. All condition fields within the rule list are evaluated in the stateless rule engine. However, only a single action is fired. During conflict resolution the rule with the highest priority value is found first. The action field of the rule found first will be the only action triggered. The output object is therefore generated based on only one rule. The rule with the highest priority value.

## Using Unruly Engine
The recommended way to use the Unruly Engine is through some mechanism of dependency injection framework like Spring.

### Create the Configuration Beans
Creating the main beans to use is simple. Let's say you want to create two rules engines, one stateful and one stateless both outputting to your `LoanDetails` object.

```java
@Configuration
public class RulesEngineConfiguration {
    
    @Bean
    public RulesEngine<LoanDetails> statelessRulesEngine() {
        return new StatelessRulesEngine<>(LoanDetails::new);
    }

    @Bean
    public RulesEngine<LoanDetails> statefulRulesEngine() {
        return new StatefulRulesEngine<>(LoanDetails::new);
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

        statefulRulesEngine.setRuleList(allRules);
        LoanDetails result = statefulRulesEngine.run(facts);
        return ResponseEntity.ok(result);
    }
}
```
