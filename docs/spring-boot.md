# 🌱 Spring Boot

unruly-engine has no Spring dependency, but it fits naturally into a Spring application: build each engine as a
singleton bean, load its rules at startup, and call `run()` from your request handlers.

[← Back to README](../README.md)

- [Configure the engine](#-configure-the-engine)
- [Rules from configuration](#-rules-from-configuration)
- [Use it in a controller](#-use-it-in-a-controller)
- [Reload rules without restarting](#-reload-rules-without-restarting)
- [Several engines](#-several-engines)
- [Logging](#-logging)

---

## ⚙ Configure the engine

```java
@Configuration
public class RulesEngineConfiguration {

    @Bean
    public RulesEngine<LoanDecision> loanRulesEngine(RuleRepository ruleRepository) {
        RulesEngine<LoanDecision> engine = RulesEngineBuilder.stateless(LoanDecision::new);
        engine.addImport("java.time");                        // imports first...
        engine.registerListener(new LoggingRuleListener());   // ...listeners at any time...
        engine.setRuleList(ruleRepository.findLoanRules());   // ...then compile the rules once
        return engine;
    }
}
```

`LoanDecision` is your output type and `RuleRepository` is your own component. Rules can come from anywhere that
can produce a `List<Rule>`: a database table, a YAML file, a config service, or plain code.

> [!WARNING]
> Rules are code: a rule can do anything your application code can. Whoever can change the table, file or service
> the rules come from can run code in your application, so protect it like your source code. See
> [Security](../README.md#-security).

```java
@Component
public class RuleRepository {

    public List<Rule> findLoanRules() {
        return List.of(
                Rule.builder()
                        .ruleName("prime-rate")
                        .priority(10)
                        .condition("applicant.creditScore >= 750")
                        .action("output.approved = true; output.interestRate = 4.5")
                        .build());
    }
}
```

> [!TIP]
> If a rule doesn't compile, `setRuleList()` throws a `RuleCompilationException`, so the bean fails to be created
> and the application refuses to start. That's usually what you want: broken rules never reach production traffic.

## 🗂️ Rules from configuration

`Rule` is immutable and has no setters, so `@ConfigurationProperties` can't bind it. Bind your own record, and build
the rules from it:

```java
@ConfigurationProperties("loan")
public record LoanRulesProperties(List<RuleProperties> rules) {

    public record RuleProperties(String name, Integer priority, String condition, String action) {

        public Rule toRule() {
            return Rule.builder()
                    .ruleName(name)
                    .priority(priority)
                    .condition(condition)
                    .action(action)
                    .build();
        }
    }
}
```

```yaml
loan:
  rules:
    - name: prime-rate
      priority: 10
      condition: applicant.creditScore >= 750
      action: output.approved = true; output.interestRate = 4.5
```

Enable the record with `@EnableConfigurationProperties(LoanRulesProperties.class)`, then pass
`properties.rules().stream().map(RuleProperties::toRule).toList()` to `setRuleList()`. A rule without a name, condition
or action makes `toRule()` throw, so the application refuses to start. To read rules from JSON instead, register the
Jackson mix-ins shown under [Rules](../README.md#rules).

## 🌐 Use it in a controller

```java
@RestController
@RequestMapping("/api/loans")
public class LoanController {

    private final RulesEngine<LoanDecision> loanRulesEngine;

    public LoanController(RulesEngine<LoanDecision> loanRulesEngine) {
        this.loanRulesEngine = loanRulesEngine;
    }

    @PostMapping("/decision")
    public ResponseEntity<LoanDecision> decide(@RequestBody Applicant applicant) {
        FactStore<Object> facts = new FactMap<>();   // a fresh store for every request
        facts.setValue("applicant", applicant);

        LoanDecision decision = loanRulesEngine.run(facts);
        // run() returns null when no rule matched
        return decision != null ? ResponseEntity.ok(decision) : ResponseEntity.noContent().build();
    }
}
```

The engine bean is shared by every request thread, which is safe once `setRuleList()` has completed. See
[Thread safety](thread-safety.md).

## 🔄 Reload rules without restarting

`setRuleList()` may be called while other threads are running rules. The new list is compiled first and then
swapped in atomically, so a run already in progress finishes with the old rules.

```java
@Component
public class LoanRulesReloader {

    private final RulesEngine<LoanDecision> loanRulesEngine;
    private final RuleRepository ruleRepository;

    public LoanRulesReloader(RulesEngine<LoanDecision> loanRulesEngine, RuleRepository ruleRepository) {
        this.loanRulesEngine = loanRulesEngine;
        this.ruleRepository = ruleRepository;
    }

    @Scheduled(fixedDelay = 60_000)   // requires @EnableScheduling
    public void reload() {
        try {
            loanRulesEngine.setRuleList(ruleRepository.findLoanRules());
        } catch (RuleCompilationException e) {
            // The previous rules stay in place; alert someone instead of serving broken rules.
        }
    }
}
```

Imports are applied when the rules are compiled. Register them before the first `setRuleList()`, and don't call
`addImport()` while a reload may be running, because it isn't thread-safe.

## 🔀 Several engines

When you have more than one engine with the same output type, name the one you want with `@Qualifier`:

```java
@Bean
public RulesEngine<LoanDecision> pricingEngine() { /* stateless */ }

@Bean
public RulesEngine<LoanDecision> complianceEngine() { /* stateful */ }
```

```java
public LoanController(@Qualifier("pricingEngine") RulesEngine<LoanDecision> pricingEngine,
                      @Qualifier("complianceEngine") RulesEngine<LoanDecision> complianceEngine) {
    // ...
}
```

## 📝 Logging

Spring Boot includes Logback, so the engine's SLF4J messages appear without extra setup. To see every rule
evaluation from `LoggingRuleListener`, add this to `application.properties`:

```properties
logging.level.io.github.brantunger.unruly.api.LoggingRuleListener=DEBUG
```

See [Listeners & logging](listeners-and-logging.md) for what each logger reports.
