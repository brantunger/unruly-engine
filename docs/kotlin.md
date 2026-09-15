# 🟣 Kotlin

The engine is plain Java, and Kotlin uses it directly. Since 1.5.0 its public API marks what can be `null` with
[JSpecify](https://jspecify.dev/) annotations, and Kotlin 2.1 and later read them strictly, so Kotlin sees exactly
which types are nullable.

[← Back to README](../README.md)

- [Using the engine from Kotlin](#-using-the-engine-from-kotlin)
- [Upgrading Kotlin code from 1.4 or earlier](#-upgrading-kotlin-code-from-14-or-earlier)

---

## 🚀 Using the engine from Kotlin

```kotlin
val engine: RulesEngine<LoanDecision> = RulesEngineBuilder.stateless(::LoanDecision)
engine.setRuleList(rules)

val facts = FactMap<Any?>()
facts.setValue("applicant", Applicant("Ada", 780))

val decision: LoanDecision? = engine.run(facts)   // null when no rule matched

engine.registerListener(object : RuleListener {
    override fun afterEvaluate(rule: Rule, facts: Map<String, Any?>, matchResult: Boolean) {
        println("${rule.ruleName} matched: $matchResult")
    }
})
```

| API | Kotlin type |
| --- | --- |
| `RulesEngine.run(facts)` | takes `FactStore<*>`, so any `FactMap`; returns `O?` |
| `FactStore.getValue(name)`, `FactMap.getValue(name)` | `T?` |
| `RuleListener.beforeEvaluate` / `afterEvaluate` facts | `Map<String, Any?>` |
| `Rule.priority`, `description`, `language` | `Int?`, `String?`, `String?` |
| `Rule.ruleName`, `Rule.condition`, `Rule.action`, and the builder's `ruleName()`, `condition()` and `action()` | `String` |
| `FactReference.name` | `String` |
| `RuleCompilationException.ruleName`, `RuleExecutionException.ruleName` | `String?` |

## ⬆ Upgrading Kotlin code from 1.4 or earlier

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
