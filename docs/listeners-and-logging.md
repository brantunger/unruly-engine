# 👂 Listeners & logging

Register a `RuleListener` to trace which rules matched, time each rule, or audit decisions. The engine also logs
its own failures through SLF4J.

[← Back to README](../README.md)

- [Callbacks](#-callbacks)
- [Writing a listener](#-writing-a-listener)
- [Guarantees](#-guarantees)
- [LoggingRuleListener](#-loggingrulelistener)
- [Logging setup](#-logging-setup)

---

## 🔔 Callbacks

Every method has an empty default implementation, so override only the ones you need.

| Callback | Called | Arguments |
| --- | --- | --- |
| `beforeEvaluate(rule, facts)` | Before a condition is evaluated | The rule, and a read-only view of the fact values |
| `afterEvaluate(rule, facts, matched)` | After a condition evaluates to a boolean | ... plus whether it matched |
| `beforeExecute(rule, output)` | Before an action runs | The rule and the output object |
| `afterExecute(rule, output)` | After an action completes | The rule and the output object |
| `onError(rule, error)` | Instead of `afterEvaluate` or `afterExecute`, when the condition or action fails | The rule and the `RuleExecutionException` that `run()` is about to throw |

```mermaid
sequenceDiagram
    autonumber
    participant E as RulesEngine
    participant L as RuleListener
    Note over E,L: Phase 1: evaluate every rule's condition
    E->>L: beforeEvaluate(rule, facts)
    alt condition evaluates to a boolean
        E->>L: afterEvaluate(rule, facts, matched)
    else condition throws or isn't a boolean
        E->>L: onError(rule, error)
        Note over E: run() throws the error
    end
    Note over E,L: Phase 2: for each rule that fires
    E->>L: beforeExecute(rule, output)
    alt action succeeds
        E->>L: afterExecute(rule, output)
    else action throws
        E->>L: onError(rule, error)
        Note over E: run() throws the error
    end
```

Compile errors from `setRuleList()` are never reported to listeners; they're thrown directly.

## ✍ Writing a listener

```java
engine.registerListener(new RuleListener() {
    @Override
    public void afterEvaluate(Rule rule, Map<String, Object> facts, boolean matched) {
        System.out.println(rule.getRuleName() + " matched: " + matched);
    }

    @Override
    public void onError(Rule rule, RuleExecutionException error) {
        System.out.println(rule.getRuleName() + " failed: " + error.getMessage());
    }
});
```

Use `registerListeners(List)` to add several at once. Listeners can be added at any time, but not removed.

### Tracing each rule

Every `before*` callback is followed by exactly one closing callback, so anything you open can always be closed.
`startSpan` and `endSpan` stand in for your own tracing API:

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

## ✅ Guarantees

| Guarantee | Detail |
| --- | --- |
| 🔗 **Paired callbacks** | Every `beforeEvaluate` / `beforeExecute` is followed by exactly one matching `after*` or `onError`. A listener registered partway through a run starts with a `before*` callback, never with a closing one. |
| 🧯 **Listener failures are contained** | An exception thrown by a listener, including a `StackOverflowError` or `AssertionError`, is logged at WARN and the run continues. Any other `Error`, such as `OutOfMemoryError`, propagates out of `run()` once every listener has received the same callback, also when it's the cause of an exception the listener throws. If it came from a `before*` callback, the condition or action doesn't run, and every listener first gets `onError` to close that callback. |
| 💥 **Errors in rules** | A rule that throws a `StackOverflowError` or `AssertionError` is wrapped in the `RuleExecutionException`. Any other `Error`, including one thrown by a method, a getter or a lambda the rule calls, is wrapped for `onError` and then rethrown unchanged from `run()`. |
| 📄 **Private copies** | Each callback receives its own copy of the `Rule`. Changing it affects nothing else. |
| 🔏 **Read-only facts** | The `facts` map holds fact values, not the `FactStore`. Writing to it throws `UnsupportedOperationException`. |
| 🧵 **Concurrency** | An engine shared across threads calls the same listener from every thread, possibly at the same time. **Listeners must be thread-safe.** |

## 🪵 LoggingRuleListener

A ready-made listener that logs every lifecycle event at **DEBUG** level:

```java
engine.registerListener(new LoggingRuleListener());
```

For the README's quick start, a stateless engine and a score of 780, it logs:

```text
Evaluating condition for rule: prime-rate
Evaluated condition for rule: prime-rate | Match: true
Evaluating condition for rule: standard-rate
Evaluated condition for rule: standard-rate | Match: true
Executing action for rule: prime-rate
Executed action for rule: prime-rate
```

Every condition is evaluated before any action runs, and the stateless engine fires only `prime-rate`. When a
condition or action fails, the listener logs a line such as
`Failed rule: prime-rate | Error: Failed to execute action for rule 'prime-rate': ...` in place of the closing line.

Rule names appear as the engine's error messages show them: line breaks and other control characters are escaped
(`\n`), and a name longer than 200 characters is shortened, so a name can't start a log line of its own.

## 🔧 Logging setup

The engine depends only on the **SLF4J 2.x API**. Without a provider on the classpath, SLF4J prints
`No SLF4J providers were found` and discards every message. Spring Boot already includes Logback; other
applications can add Logback, Log4j 2's SLF4J 2 provider, or `slf4j-simple`.

| Logger | Level | Messages |
| --- | --- | --- |
| `io.github.brantunger.unruly.core.AbstractRulesEngine` | `ERROR` | Every rule list `setRuleList()` rejects, fact `run()` rejects, rule failure and output-supplier failure, logged just before the exception is thrown. That includes a fatal `Error` from compiling or running a rule, which is logged and then rethrown. Misuse isn't logged: a `null` argument, `run()` before `setRuleList()`, or an invalid `addImport()` string. |
| `io.github.brantunger.unruly.core.AbstractRulesEngine` | `WARN` | A listener threw an exception |
| `io.github.brantunger.unruly.api.LoggingRuleListener` | `DEBUG` | Lifecycle events, if you registered the listener |

> [!NOTE]
> The engine already logs each failure at ERROR. If you also log the exception you catch, you'll see it twice.

> [!CAUTION]
> Failure messages can contain fact values. The JDK, MVEL and your own code put values into exception messages, such
> as `For input string: "123-45-6789"` or `uncomparable values <<123-45-6789>> and <<5>>`, and the engine copies the
> message into its ERROR log line and into `LoggingRuleListener`'s DEBUG line. If your facts hold sensitive data, turn
> off the `io.github.brantunger.unruly` logger and log a redacted form of the failure yourself.
> Lower the engine logger's level if you prefer to handle logging yourself.

> [!IMPORTANT]
> The engine's logger is named after an internal class, `core.AbstractRulesEngine`, and that name may change in
> 2.0. Configure the parent logger **`io.github.brantunger.unruly`** instead, so your configuration keeps working
> across versions: Logback, Log4j 2 and Spring Boot apply a logger's level to every logger under its name. A more
> specific setting still takes precedence, as the `LoggingRuleListener` line below shows. `LoggingRuleListener` is
> public API, so its logger name won't change.

**Logback** (`logback.xml`):

```xml
<!-- For example, if you log the exceptions you catch: turn off the engine's own ERROR and WARN messages -->
<logger name="io.github.brantunger.unruly" level="OFF"/>
<!-- A more specific logger overrides the parent -->
<logger name="io.github.brantunger.unruly.api.LoggingRuleListener" level="DEBUG"/>
```

**Spring Boot** (`application.properties`):

```properties
logging.level.io.github.brantunger.unruly=OFF
logging.level.io.github.brantunger.unruly.api.LoggingRuleListener=DEBUG
```
