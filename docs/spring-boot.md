# 🌱 Spring Boot

> [!NOTE]
> Describes 2.0.0, which isn't released yet. For 1.8.0, see
> [this page at v1.8.0](https://github.com/brantunger/unruly-engine/blob/v1.8.0/docs/spring-boot.md).

unruly-engine has no Spring dependency, but it fits naturally into a Spring application: build each engine as a
singleton bean, load its rules at startup, and call `run()` from your request handlers.

[← Documentation index](README.md)

- [Configure the engine](#-configure-the-engine)
- [Rules from configuration](#-rules-from-configuration)
- [Use it in a controller](#-use-it-in-a-controller)
- [Reload rules without restarting](#-reload-rules-without-restarting)
- [Several engines](#-several-engines)
- [Logging](#-logging)

---

## 🔧 Configure the engine

```java
@Configuration
public class RulesEngineConfiguration {

    @Bean
    public RulesEngine<LoanDecision> loanRulesEngine(RuleRepository ruleRepository) {
        RulesEngine<LoanDecision> engine = RulesEngineBuilder.firstMatch(LoanDecision::new)
                .imports("java.time")                       // imports and listeners are set on the builder...
                .listener(new LoggingRuleListener())
                .build();
        engine.load(ruleRepository.findLoanRules());        // ...then compile the rules once
        return engine;
    }
}
```

`LoanDecision` is your output type and `RuleRepository` is your own component. Rules can come from anywhere that
can produce a `List<Rule>`: a database table, a YAML file, a config service, or plain code.

> [!CAUTION]
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
> If a rule doesn't compile, `load()` throws a `RuleCompilationException`, so the bean fails to be created
> and the application refuses to start. That's usually what you want: broken rules never reach production traffic.

## 📜 Rules from configuration

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
`properties.rules().stream().map(RuleProperties::toRule).toList()` to `load()`. A rule without a name, condition
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

To record **why** a decision was made, use `runWithResult()` instead. It returns the output, the rules that fired, and
a checksum of the rules the run used, so an audit row ties the decision to a version of the rules even if they're
reloaded a moment later:

```java
RunResult<LoanDecision> result = loanRulesEngine.runWithResult(facts);

auditLog.record(applicant.id(),
        result.firedRules().stream().map(Rule::getRuleName).toList(),
        result.ruleSetChecksum());          // engine.rules().checksum() is the engine's current rule set
```

The engine bean is shared by every request thread, which is safe once `load()` has completed. See
[Thread safety](thread-safety.md).

## 🔄 Reload rules without restarting

`load()` may be called while other threads are running rules. The new list is compiled first and then
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
            loanRulesEngine.load(ruleRepository.findLoanRules());
        } catch (RuleCompilationException e) {
            // The previous rules stay in place; alert someone instead of serving broken rules.
        }
    }
}
```

A reload replaces only the rules. The engine's imports and listeners are set when it's built; to change them, build a
new engine.

## 🔀 Several engines

When you have more than one engine with the same output type, name the one you want with `@Qualifier`:

```java
@Bean
public RulesEngine<LoanDecision> pricingEngine() { /* firstMatch */ }

@Bean
public RulesEngine<LoanDecision> complianceEngine() { /* allMatches */ }
```

```java
public LoanController(@Qualifier("pricingEngine") RulesEngine<LoanDecision> pricingEngine,
                      @Qualifier("complianceEngine") RulesEngine<LoanDecision> complianceEngine) {
    // ...
}
```

## 🪵 Logging

Spring Boot includes Logback, so the engine's SLF4J messages appear without extra setup. To see every rule
evaluation from `LoggingRuleListener`, add this to `application.properties`:

```properties
logging.level.io.github.brantunger.unruly.api.LoggingRuleListener=DEBUG
```

See [Listeners & logging](listeners-and-logging.md) for what each logger reports.
