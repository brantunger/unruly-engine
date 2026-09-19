# 🟣 Kotlin

> [!NOTE]
> Describes 2.0.0, which isn't released yet. For 1.8.0, see
> [this page at v1.8.0](https://github.com/brantunger/unruly-engine/blob/v1.8.0/docs/kotlin.md).

The engine is plain Java, and Kotlin uses it directly. Since 1.5.0 its public API marks what can be `null` with
[JSpecify](https://jspecify.dev/) annotations, and Kotlin 2.1 and later read them strictly, so Kotlin sees exactly
which types are nullable.

[← Documentation index](README.md)

- [Using the engine from Kotlin](#-using-the-engine-from-kotlin)
- [Upgrading Kotlin code from 1.4 or earlier](#-upgrading-kotlin-code-from-14-or-earlier)

---

## 🚀 Using the engine from Kotlin

`Applicant`, `LoanDecision` and the two rules, here `rules`, are from the [Quick start](../README.md#-quick-start).

```kotlin
import io.github.brantunger.unruly.api.FactMap
import io.github.brantunger.unruly.api.Rule
import io.github.brantunger.unruly.api.RuleListener
import io.github.brantunger.unruly.api.RulesEngine
import io.github.brantunger.unruly.api.RulesEngineBuilder

val engine: RulesEngine<LoanDecision> = RulesEngineBuilder.firstMatch(::LoanDecision)
    .listener(object : RuleListener {
        override fun afterEvaluate(rule: Rule, facts: Map<String, Any?>, matchResult: Boolean) {
            println("${rule.ruleName} matched: $matchResult")
        }
    })
    .build()
engine.load(rules)

val facts = FactMap<Any?>()
facts.setValue("applicant", Applicant("Ada", 780))

val decision: LoanDecision? = engine.run(facts)   // null when no rule matched
```

| API | Kotlin type |
| --- | --- |
| `RulesEngine.run(facts)` | takes `FactStore<*>`, so any `FactMap`; returns `O?` |
| `FactStore.getValue(name)`, `FactMap.getValue(name)` | `T?` |
| `RuleListener.beforeEvaluate` / `afterEvaluate` facts, `RunContext.facts()` | `Map<String, Any?>` |
| `RunResult.output()` | `O?`: `null` when no rule fired |
| `RunContext.parent()` | `RunContext?`: `null` unless another run of the same engine started this one |
| `RunContext.startedAt()` | `Instant` |
| `RunResult.startedAt()` | `Instant?`: `null` on a result built with `RunResult.of(...)` and not given a run with `withRun(...)`; the engine's results always have one |
| `RuleSetInfo.loadedAt()` | `Instant?`: `null` before the first `load()` |
| `RunOptions.timeout()` | `Duration?`: `null` when the run uses the engine's timeout |
| `RunOptions.tags()`, `RunContext.tags()`, `RunResult.tags()` | `Set<String>`: empty when the run uses every rule, and on a result built with `RunResult.of(...)` and not given a run with `withRun(...)` |
| `Rule.priority`, `description`, `language` | `Int?`, `String?`, `String?` |
| `Rule.validFrom`, `Rule.validTo` | `Instant?`: `null` when the rule has no start or no end |
| `Rule.isEnabled`, `Rule.tags` | `Boolean`, `Set<String>` |
| `Rule.ruleName`, `Rule.condition`, `Rule.action`, and the builder's `ruleName()`, `condition()` and `action()` | `String` |
| `FactReference.name` | `String` |
| `RuleCompilationException.ruleName`, `RuleExecutionException.ruleName` | `String?` |
| `RuleCompilationException.expressionKind`, `RuleExecutionException.expressionKind` | `ExpressionKind?` |

## 🔼 Upgrading Kotlin code from 1.4 or earlier

Code that compiled against 1.4 can fail to compile against 1.5.0 or later. Each error has a small fix:

| Kotlin code written for 1.4 | Error from 1.5.0 | Change it to |
| --- | --- | --- |
| `override fun beforeEvaluate(rule: Rule, facts: Map<String, Any>)` | `'beforeEvaluate' overrides nothing` | `facts: Map<String, Any?>`, also in `afterEvaluate` |
| `val out: Out = engine.run(facts)` | `expected 'Out', actual 'Out?'` | handle `null`, which `run()` returns when no rule matched |
| `val value: Any = facts.getValue("a")` | a nullable type where a non-null one is expected | `Any?`, or handle `null` |
| `Rule.builder().condition(c)` with `c: String?` | a nullable argument where a non-null one is expected | pass a `String`: a condition and an action are required |

Two errors that 1.5.0 to 1.8.0 reported are gone in 2.0.0: `engine.run(FactMap<Any>())` compiles, and a fact's
`name` is a `String`.

To upgrade first and fix the errors afterwards, the Kotlin compiler flag `-Xjspecify-annotations=warn` reports them as
warnings. It's a temporary escape hatch; remove it once the code compiles without it:

```kotlin
// build.gradle.kts
kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjspecify-annotations=warn")
    }
}
```
