# unruly-engine

[![Build](https://github.com/brantunger/unruly-engine/actions/workflows/build.yml/badge.svg)](https://github.com/brantunger/unruly-engine/actions/workflows/build.yml)

Unruly is a pure Java rule engine that parses and evaluates using MVEL based rules

## Installation

Add the dependency to your project:

### Gradle

```groovy
implementation 'io.github.brantunger:unruly-engine:1.0.15'
```

### Maven

```xml
<dependency>
    <groupId>io.github.brantunger</groupId>
    <artifactId>unruly-engine</artifactId>
    <version>1.0.15</version>
</dependency>
```

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
        return RulesEngineBuilder.stateless(LoanDetails::new);
    }

    @Bean
    public RulesEngine<LoanDetails> statefulRulesEngine() {
        return RulesEngineBuilder.stateful(LoanDetails::new);
    }
}
```

### Create MVEL Rules

The rules parsing language is written using MVEL. MVEL has largely been inspired by Java syntax, but has some fundamental differences aimed at making it more efficient as an expression language, such as operators that directly support collection, array and string matching, as well as regular expressions. MVEL is used to evaluate expressions written using Java syntax.

You can view how MVEL works here: https://mvel.documentnode.com/

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
      && claim.getPROD_SLCTN_CD().matches("0|1|2|7|8|9")))
```

You can call methods upon the **"claim"** object to create boolean logic. This logic is stored in the **"condition"** field of the Rule. If the condition evaluates to _TRUE_ then the action of the rule will be executed.

**Action Fact Access:**
Rule actions also have full access to the input facts, allowing you to compute dynamic outputs based on the input data. For example:
```java
// Sets the discount dynamically based on the claim's amount
Rule rule = Rule.builder()
    .ruleName("compute-discount")
    .condition("claim.amount > 100")
    .action("output.put(\"discount\", claim.amount * 0.10)")
    .build();
```

### Package Imports

If your MVEL rule expressions reference classes from specific packages (e.g. `Objects.nonNull()`), you can register package imports with the engine. Imports must be configured **before** calling `setRuleList()`, since rules are compiled at that point.

```java
// Add a single package import
engine.addImport("java.util");

// Or add multiple packages at once
engine.addImports(Set.of("java.util", "java.time"));

// Then set the rules (imports take effect during compilation)
engine.setRuleList(rules);
```

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

    private final RulesEngine<LoanDetails> statefulRulesEngine;

    public UnrulyController(KnowledgeBase knowledgeBase,
                            RulesEngine<LoanDetails> statefulRulesEngine) {
        this.statefulRulesEngine = statefulRulesEngine;
        // Set the rules once during initialization for thread safety
        this.statefulRulesEngine.setRuleList(knowledgeBase.getAllRules());
    }

    @PostMapping(value = "/loan")
    public ResponseEntity<?> postLoan(@RequestBody UserDetails userDetails) {
        FactStore<Object> facts = new FactMap<>();
        facts.setValue("claim", userDetails);

        LoanDetails result = statefulRulesEngine.run(facts);
        return ResponseEntity.ok(result);
    }
}
```

## Thread Safety

The rules engine is designed to be configured once and then used concurrently:

- **`setRuleList()`** should be called once during initialization (e.g. in a constructor or `@PostConstruct` method). This method compiles the MVEL expressions and stores the compiled rules internally. It is **not thread-safe** to call concurrently with `run()`.
- **`run()`** is safe to call from multiple threads after `setRuleList()` has completed, as it only reads the compiled rules and creates a fresh output object per invocation.
- **`addImport()` / `addImports()`** must be called before `setRuleList()`. They are not thread-safe.

## Exception Handling

The Unruly Engine provides a specific exception hierarchy to help you handle errors gracefully:

- **`UnrulyException`**: The base runtime exception for the engine.
- **`RuleCompilationException`**: Thrown during `setRuleList()` if a rule has a syntax error in its MVEL condition or action expression.
- **`RuleExecutionException`**: Thrown during `run()` if a runtime error occurs while evaluating a rule's condition or action (e.g. attempting to invoke a non-existent method).

All exceptions include the name of the offending rule in the message to aid in debugging.

## Observability & Lifecycle Listeners

If you need to trace which rules evaluate to true/false, or audit the engine execution, you can register a `RuleListener`:

```java
// Register a custom listener to capture evaluation decisions
engine.registerListener(new RuleListener() {
    @Override
    public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matchResult) {
        System.out.println("Rule " + rule.getRuleName() + " evaluated to: " + matchResult);
    }
});

// Or use the out-of-the-box LoggingRuleListener (logs all events at DEBUG level via SLF4J)
engine.registerListener(new LoggingRuleListener());
```
