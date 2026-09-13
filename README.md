# unruly-engine

[![CI](https://github.com/brantunger/unruly-engine/actions/workflows/ci.yml/badge.svg)](https://github.com/brantunger/unruly-engine/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.brantunger/unruly-engine)](https://central.sonatype.com/artifact/io.github.brantunger/unruly-engine)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

Unruly is a pure Java rule engine that parses and evaluates using MVEL based rules

## Installation

Add the dependency to your project:

### Gradle

<!-- x-release-please-start-version -->
```groovy
implementation 'io.github.brantunger:unruly-engine:1.1.3'
```
<!-- x-release-please-end -->

### Maven

<!-- x-release-please-start-version -->
```xml
<dependency>
    <groupId>io.github.brantunger</groupId>
    <artifactId>unruly-engine</artifactId>
    <version>1.1.3</version>
</dependency>
```
<!-- x-release-please-end -->

## Introduction

The Unruly Engine has two rules engine implementations.

- StatelessRulesEngine
- StatefulRulesEngine

### Stateful Rules Engine

In the **stateful** implementation, the rules engine fires all the actions of the rules when the condition field of the Rule returns true. In the stateful rules engine the rules are sorted by priority. The highest priority wins. The output object saves state in between each rule, so rules with lower priority may override the fields in the output object.

The stateful engine **evaluates every condition first, then fires the matched actions** in priority order. An action never causes another rule's condition to be re-checked. If a high-priority action sets `claim.status = "DENIED"`, a lower-priority rule whose condition `claim.status == "PENDING"` was already true still fires.

A run is **not atomic**. If an action throws, the actions that already ran keep their changes to the output object and to any fact objects they modified. Listeners have already received their callbacks, and `run()` throws a `RuleExecutionException` naming only the rule that failed.

### Stateless Rules Engine

In the **stateless** implementation, the rules engine fires the action of a single rule. All condition fields within the rule list are evaluated in the stateless rule engine. However, only a single action is fired. During conflict resolution the rule with the highest priority value is found first. The action field of the rule found first will be the only action triggered. The output object is therefore generated based on only one rule. The rule with the highest priority value.

**Equal priorities:** rules with the same priority keep the order they had in the list passed to `setRuleList()`. If several matching rules share the highest priority, the stateless engine fires the one listed first, and the stateful engine fires them in list order. Rules with a `null` priority come last.

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

**Facts are read-only to rules:**
- A **condition** cannot assign or declare anything. `setRuleList()` rejects a condition containing an assignment with a `RuleCompilationException`: `claim.approved = true` (a typo for `==`), `claim.amount += 5`, `x++`, a local such as `x = 5; x > 1`, a `with` block or a `def` function. The check reads the condition's text, so it can't see a method call that changes a fact, such as `claim.setApproved(true)`.
- An **action** may assign local variables (`score = 10; output.put("score", score)`), but the assignment is visible only within that action. Other rules still see the original facts. Pass results between rules through `output`.
- `output` is reserved. A fact with that name is rejected with an `IllegalArgumentException` at `run()`.
- An action changes the output object in place (`output.put(...)`, `output.setScore(...)`) but can't replace it. Assigning to `output` itself, as in `output = new HashMap()` or `output = output + 1`, throws a `RuleExecutionException`, so the output type must be mutable.

The engine does not deep-copy fact objects, so a method that mutates one (e.g. `claim.setAmount(0)`) is still seen by later rules.

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

A fact's name is its map key, and that key is what rules use. `FactMap` rejects a `null` name, a key that differs from the `Fact`'s own name (`put("claim", new Fact<>("other", 1))`), and two facts with the same name in its constructor, each with an `IllegalArgumentException`. Renaming a `Fact` after adding it does not change the name rules see.

Rule names must be unique. `setRuleList()` throws a `RuleCompilationException` for a duplicate name, while rules without a name are allowed.

`FactMap.setValue` always stores a new `Fact`, so a `FactMap` copied from another (`new FactMap<>(other)`) can be changed without affecting the original. The copy is shallow: the fact values themselves (e.g. the `userDetails` object) are shared, so build a fresh `FactStore` per request rather than sharing one across threads.

### Use the rules engine bean

`run()` returns the output object when at least one rule matched. It returns **`null`** when no rule matched or the rule list is empty, so check for it. Calling `run()` before `setRuleList()` throws an `IllegalStateException`.

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
        // run() returns null when no rule matched
        return result != null ? ResponseEntity.ok(result) : ResponseEntity.noContent().build();
    }
}
```

## Thread Safety

The rules engine is designed to be configured once and then used concurrently:

- **`setRuleList()`** compiles the MVEL expressions and stores the compiled rules internally. Call it during initialization (e.g. in a constructor or `@PostConstruct` method). It may also be called again later to reload rules while other threads are calling `run()`. The new rules are swapped in all at once: a run already in progress finishes with the rules it started with, and runs that start afterwards use the new rules. Each condition and action is compiled on its own, so variables and inline `import` statements in one rule never affect another rule or a later reload. Calling `setRuleList()` from several threads at the same time is safe, but the last call to finish wins.
- **`run()`** is safe to call from multiple threads after `setRuleList()` has completed. Each invocation creates a fresh output object. The compiled rules are shared, and MVEL updates them internally as they are evaluated, which is why the engine configures MVEL as described below.
- **`addImport()` / `addImports()`** must be called before `setRuleList()`. They are not thread-safe.
- **`registerListener()` / `registerListeners()`** are thread-safe and may be called at any time, even from inside a listener callback. A listener registered during a run may start receiving callbacks partway through that run. A registered listener is called from every thread running the engine, so **listener implementations must be thread-safe**.

### MVEL optimizer (JVM-wide)

MVEL's default JIT optimizer generates an accessor for the class it first sees. When the same fact name is later bound to a different class (for example `claim` typed as an interface with several implementations), MVEL falls back to a slower accessor. That fallback is not thread-safe, so concurrent `run()` calls intermittently failed with a `RuleExecutionException` caused by a `ClassCastException`.

To make concurrent use safe by default, **loading the engine switches MVEL's default optimizer to its reflective optimizer for the whole JVM**. MVEL reads this from one global setting, so the choice can't be limited to this engine. It also applies to any other library in the same JVM that uses MVEL.

The reflective optimizer is somewhat slower. A rough single-threaded measurement with three rules went from about 280 ns to 370 ns per `run()`. To keep MVEL's own setting (JIT on, unless you pass `-Dmvel2.disable.jit=true`), start the JVM with:

```
-Dunruly.mvel.jit=true
```

With the JIT on, facts whose runtime class varies must not be run concurrently.

## Exception Handling

The Unruly Engine provides a specific exception hierarchy to help you handle errors gracefully:

- **`UnrulyException`**: The base runtime exception for the engine.
- **`RuleCompilationException`**: Thrown during `setRuleList()` if a rule has a syntax error in its MVEL condition or action expression, has a null or blank condition or action, has a condition that contains an assignment, or if the rule list contains a `null` rule. MVEL's parser is lenient, so not every mistake is caught at this point. For example, `true)` and `output.put("k" 1)` compile without error and only fail with a `RuleExecutionException` when that rule is evaluated. Test each rule against sample facts rather than relying on `setRuleList()` alone.
- **`RuleExecutionException`**: Thrown during `run()` if a runtime error occurs while evaluating a rule's condition or action (e.g. attempting to invoke a non-existent method), or if a condition evaluates to anything other than a boolean. A condition such as `claim.status` is rejected rather than coerced; write `claim.status == "APPROVED"`.

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

Every `beforeEvaluate` / `beforeExecute` callback is followed by exactly one closing call. That's the matching `afterEvaluate` / `afterExecute` when the rule succeeds, or `onError` when its condition or action fails. `onError` receives the `RuleExecutionException` that `run()` then throws. That includes a rule that throws an `Error`: a `StackOverflowError` (e.g. runaway recursion) or `AssertionError` is wrapped in the `RuleExecutionException`, while any other `Error`, such as `OutOfMemoryError`, is wrapped for `onError` and then rethrown unchanged from `run()`. Anything you open in a `before*` callback, such as a timer or tracing span, can therefore always be closed:

```java
engine.registerListener(new RuleListener() {
    @Override
    public void beforeExecute(Rule rule, Object output) {
        startSpan(rule);
    }

    @Override
    public void afterExecute(Rule rule, Object output) {
        endSpan(rule);
    }

    @Override
    public void onError(Rule rule, RuleExecutionException error) {
        endSpan(rule, error);
    }
});
```
