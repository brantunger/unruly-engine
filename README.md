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

### Create MVEL Rules
The rules parsing language is written using MVEL. MVEL has largely been inspired by Java syntax, but has some fundamental differences aimed at making it more efficient as an expression language, such as operators that directly support collection, array and string matching, as well as regular expressions. MVEL is used to evaluate expressions written using Java syntax. 

You can view how MVEL works here: http://mvel.documentnode.com/

This is an example usage of MVEL defining a rule, where **"claim"** is the input FactStore object.

```java
Objects.nonNull(claim.getMDDB_MULTSRC_CD())
&& Objects.nonNull(claim.getBRND_NM_TYP_CD())
&& ((claim.getMDDB_MULTSRC_CD().equalsIgnoreCase("M")
   && claim.getBRND_NM_TYP_CD().toUpperCase().matches("TRDMK"))
   || (claim.getMDDB_MULTSRC_CD().equalsIgnoreCase("N")
      && claim.getBRND_NM_TYP_CD().toUpperCase().matches("BRNDGNRC|TRDMK"))
   || (claim.getMDDB_MULTSRC_CD().equalsIgnoreCase("O")
      && claim.getBRND_NM_TYP_CD().toUpperCase().matches("BRNDGNRC|TRDMK")
      && CrsObjects.matches(claim.getPROD_SLCTN_CD(), "0|1|2|7|8|9")))
```

You can call methods upon the **"claim"** object to create boolean logic. This logic is stored in the **"condition"** field of the Rule. If the condition evaluates to _TRUE_ then the action of the rule will be executed.

### Facts
Facts are the input objects for the rules engine. If you need to process a **"claim"**, then the model must be defined and input into the Rules Engine. You do that by creating a **FactStore**. One implementation of the FactStore is a **FactMap**. Here's an example:

```java
FactStore<Object> facts = new FactMap<>();
facts.setValue("claim", userDetails);
```

Now "claim" can be used in the MVEL rule, you can access methods of that object, and send data through the Rules Engine.

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
        facts.setValue("claim", userDetails);

        statefulRulesEngine.setRuleList(allRules);
        LoanDetails result = statefulRulesEngine.run(facts);
        return ResponseEntity.ok(result);
    }
}
```
