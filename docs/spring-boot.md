# 🌱 Spring Boot

unruly-engine has no Spring dependency, but it fits naturally into a Spring application: build each engine as a
singleton bean, load its rules at startup, and call `run()` from your request handlers. Checked on Spring Boot 4.1.1
with Tomcat 11.0.24 and JDK 21, where the only difference that turned up was Boot 4's Jackson 3, noted where it
matters.

[← Documentation index](README.md)

- [Configure the engine](#-configure-the-engine)
- [Rules from configuration](#-rules-from-configuration)
- [Use it in a controller](#-use-it-in-a-controller)
- [Reload rules without restarting](#-reload-rules-without-restarting)
- [Virtual threads](#-virtual-threads)
- [Several engines](#-several-engines)
- [Shutting down](#-shutting-down)
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

If a rule doesn't compile, `load()` throws a `RuleCompilationException`, so the bean fails to be created and the
application refuses to start. That's usually what you want: broken rules never reach production traffic.
[Before you go to production](production.md) lists what else to settle before the first request.

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
or action makes `toRule()` throw, so the application refuses to start.

To read rules from JSON instead, register the Jackson mix-ins shown in
[Loading rules from data](writing-rules.md#-loading-rules-from-data) on a mapper the rule-loading component owns — a
`private final JsonMapper` field in your `RuleRepository` — rather than on the mapper Spring Boot auto-configures.
Rule mapping then can't change how the application maps its own JSON. On the shared mapper the mix-ins apply wherever
the application maps a `Rule`, and rule files follow whatever Jackson settings the application set for its own JSON.

Spring Boot 4 brings Jackson 3, which ignores a field it doesn't know instead of failing, so a misspelled `priority`
in a rule file is dropped in silence and the rule keeps the default.
[Loading rules from data](writing-rules.md#-loading-rules-from-data) says how to get that failure back.

## 🌐 Use it in a controller

```java
@RestController
@RequestMapping("/api/loans")
public class LoanController {

    private static final Logger log = LoggerFactory.getLogger(LoanController.class);

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

To record **why** a decision was made, use `runWithResult()` instead. It returns the output, the rules that fired,
each rule's outcome, a checksum of the rules the run used, and the run's tags and start instant, so an audit row ties
the decision to a version of the rules even if they're reloaded a moment later:

```java
RunResult<LoanDecision> result = loanRulesEngine.runWithResult(facts);

auditLog.record(applicant.id(),     // auditLog is your own code
        result.firedRules().stream().map(Rule::getRuleName).toList(),
        result.ruleSetChecksum(),           // engine.rules().checksum() is the engine's current rule set
        result.tags(), result.startedAt()); // what explains a SKIPPED rule
```

> [!WARNING]
> Never return a `RunResult` from a handler. On Spring Boot 4 the response is `200 {}`, with no exception and no log
> line, because Jackson 3 has `SerializationFeature.FAIL_ON_EMPTY_BEANS` off by default. Map it to a record of your
> own instead.

`RunResult` and `RuleEvaluation` are final classes and `RunContext` is an interface; none is a record. Jackson maps a
record from its components and falls back to bean introspection for everything else, and their accessors —
`output()`, `firedRules()`, `outcome()` — aren't getters, so it finds no properties and writes an empty object. A
record of your own maps in full, and `Rule`, which has getters, serializes normally.

Jackson 2 threw `InvalidDefinitionException` here; Jackson 3, which Spring Boot 4 uses, writes `{}`, so a test that
checks only the status passes.

```java
public record LoanAudit(LoanDecision decision, List<String> firedRules, String ruleSetChecksum,
                        Set<String> tags, Instant startedAt) {       // a record of your own

    public static LoanAudit of(RunResult<LoanDecision> result) {
        return new LoanAudit(result.output(),
                result.firedRules().stream().map(Rule::getRuleName).toList(),
                result.ruleSetChecksum(), result.tags(), result.startedAt());
    }
}
```

Return `LoanAudit.of(result)` from the handler. See
[Auditing a decision](engines-and-runs.md#-auditing-a-decision) for what else to record.

Handle what `run()` throws in the controller itself, so that a run the caller stopped and a rule that failed don't get
the same response. [Exceptions by method](error-handling.md#-exceptions-by-method) lists them all.

```java
@ExceptionHandler(RuleExecutionException.class)          // a rule failed, or the run was stopped
public ResponseEntity<String> ruleFailed(RuleExecutionException e) {
    boolean stopped = e.getRuleName() == null                 // null when no one rule failed
            && (e.getCause() instanceof TimeoutException      // java.util.concurrent.TimeoutException
                    || e.getCause() instanceof InterruptedException);
    if (stopped) {
        log.warn("A loan decision was stopped: {}", e.getMessage());   // the caller asked for it: not a failure
    } else if (e.getRuleName() == null) {                         // a failure that belongs to no one rule
        log.error("A loan decision failed, but no one rule did: {}", e.getMessage(), e);
    } else {
        log.error("Rule '{}' failed", e.getRuleName(), e);
    }
    return ResponseEntity.status(stopped ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR)
            .body("No decision could be made");
}

@ExceptionHandler(IllegalArgumentException.class)        // the engine rejected a fact's name, or its type
public ResponseEntity<String> factsRejected(IllegalArgumentException e) {
    log.error("The engine rejected the facts of a request", e);
    return ResponseEntity.internalServerError().body("No decision could be made");
}
```

A stop names no rule, and its cause is the `TimeoutException` or `InterruptedException` itself. A run an action
started that stopped at an earlier deadline of its own is that rule failing, so it takes the other branch. So does a
rule whose Java code throws an `Error` once the run must stop: that names the rule, so the handler logs it at ERROR
and answers 500 rather than 503. See
[telling a stop from a rule bug](stopping-runs.md#how-do-i-tell-a-timeout-an-interrupt-and-a-rule-bug-apart).

A stop isn't the only failure that names no rule, which is why the middle branch is there. `getRuleName()` is also
`null` when the output supplier threw or returned `null`, when an expression language couldn't create a session for
the run, and when more than one rule matched on a unique-match engine. Without that branch each of those logs
`Rule 'null' failed`. [What happens on each failure](error-handling.md#-what-happens-on-each-failure) lists them.

The engine has logged each already — a stop at WARN, every failure at ERROR — so each line above is a second copy
with your own context. [Logging setup](listeners-and-logging.md#-logging-setup) says how to turn the engine's own
logging down if you'd rather log only your own.

An `@ExceptionHandler` method inside the controller applies only to that controller. To share the pair between several
controllers, put them in a `@RestControllerAdvice(assignableTypes = LoanController.class)` — not in a plain advice,
which would turn every `IllegalArgumentException` in the application into this response. See
[Handling failures](error-handling.md#-handling-failures) for what each exception means, and
[Stopping a run](stopping-runs.md) for timeouts.

The engine bean is shared by every request thread. Load its rules **before the application accepts traffic**, as the
bean method above does: a `run()` before the first `load()` throws `IllegalStateException` straight away, rather than
waiting for the load. See [Thread safety](thread-safety.md).

## 🔄 Reload rules without restarting

`load()` may be called while other threads are running rules. The new list is compiled first and then
swapped in atomically, so a run already in progress finishes with the old rules.

```java
@Component
public class LoanRulesReloader {

    private static final Logger log = LoggerFactory.getLogger(LoanRulesReloader.class);

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
            // Nothing was loaded: an open engine keeps serving the previous rules. Don't swallow it: alert someone.
            log.error("The loan rules did not compile, so none of them were loaded", e);
        } catch (IllegalStateException e) {
            // Only during shutdown, if this reload outran the phase timeout; see "Shutting down" below.
            log.info("The loan rules engine is closed, so the reloaded rules were dropped");
        }
    }
}
```

A reload replaces only the rules. The engine's imports and listeners are set when it's built; to change them, build a
new engine.

- A run already in progress finishes with the rules it started with, and a request that arrives while the new list is
  still compiling is served by the old rules, without waiting.
- If two instances of the job overlap, both compile, and the one that finishes last wins: the last to swap in its
  rules, after making any copies at load.
- Compare `RunResult.ruleSetChecksum()` with `engine.rules().checksum()` when an audit row has to name the version of
  the rules a decision used.

See [Reloading rules while running](thread-safety.md#-reloading-rules-while-running).

## 🧵 Virtual threads

Spring Boot can put request handlers and `@Scheduled` tasks on virtual threads:

```properties
spring.threads.virtual.enabled=true
```

That turns on the engine's **default copy limit**, which applies only to runs on virtual threads: one compiled copy of
the rules for every two processors. Rules that compute are fine with it. Rules that wait on a database or another
service hold their copy while they wait, so build those engines with a `maxCopies(...)` sized for the runs you want
waiting at once. `unlimitedCopies()` doesn't bound the copies, which grow with the runs waiting; see
[What changes on virtual threads](virtual-threads.md#-what-changes-on-virtual-threads).
Nothing changes while the property is off, because the default doesn't apply to a Tomcat or Jetty thread pool.

If your rules are written in MVEL and you run on JDK 21 to 23, read
[MVEL on virtual threads](languages/mvel.md#-virtual-threads) before you raise the limit. See
[Limiting the copies](compiled-copies.md#-limiting-the-copies) for the whole picture.

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

A `@Bean` method's name is the bean's name, so `pricingEngine()` above registers a bean called `pricingEngine`.

> [!NOTE]
> Each engine has its own [copy limit](compiled-copies.md#-limiting-the-copies), so two engine beans allow twice as many
> compiled copies as one. That matters if you raise the limit, and on virtual threads.

## 🛑 Shutting down

`RulesEngine` is `AutoCloseable`, and Spring infers `close()` as the destroy method of a bean that implements it. So an
engine bean is closed for you when the application context closes: no `@Bean(destroyMethod = ...)`, no `@PreDestroy`.

`close()` returns at once and neither waits for nor interrupts runs in progress; see
[Closing](thread-safety.md#closing). Spring does the draining, in this order:

1. every `SmartLifecycle` bean is **stopped** — the web server first, then the task scheduler and task executor;
2. only then are the beans **destroyed**, and `close()` is called on the engine.

By the time the engine closes, the connector has stopped and no new request can reach it.

> [!IMPORTANT]
> With `server.shutdown=immediate`, a request that is still running a rule is interrupted and its response is never
> delivered. `graceful` is the default from Spring Boot 3.4.0; on 3.3 and earlier, set it yourself.

```properties
# The default since Spring Boot 3.4.0
server.shutdown=graceful
# The default: how long each phase may take to stop
spring.lifecycle.timeout-per-shutdown-phase=30s
```

With graceful shutdown, a request that was already running a rule finished normally, and the engine bean was closed
just after it returned — never during it. With `immediate`, the same request was cut off part-way through. (Checked on
Spring Boot 4.1.1 and Tomcat 11.0.24, with JDK 21, and earlier on Boot 3.5.16.)

### A scheduled reloader during shutdown

Spring stops the task scheduler before it destroys any bean, and waits up to
`spring.lifecycle.timeout-per-shutdown-phase` for a task that is still running, so a reload that finishes inside that
window always sees an open engine.

A reload that outruns the window is interrupted, and the beans are destroyed while it is still going. If it ignores the
interrupt it can then reach a closed engine, which throws `IllegalStateException("The engine is closed")` at once,
without compiling the list.

A reload already under way when the engine closes isn't stopped. If it fails, it throws what it would on an open
engine, usually `RuleCompilationException`; otherwise its rules are dropped with the same `IllegalStateException`, or
closed with the engine. Catch both, as the [reload example](#-reload-rules-without-restarting) does, so a slow reload
can't turn every shutdown into a stack trace.

## 🪵 Logging

Spring Boot includes Logback, so the engine's SLF4J messages appear without extra setup. To see every rule
evaluation from `LoggingRuleListener`, add this to `application.properties`:

```properties
logging.level.io.github.brantunger.unruly.api.LoggingRuleListener=DEBUG
```

See [Listeners & logging](listeners-and-logging.md) for what each logger reports.
