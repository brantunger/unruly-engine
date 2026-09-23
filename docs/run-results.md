# 🏁 Run results and audits

What a run and an engine report about the rules they used, and how to tie a decision to the rules that made it.

**Who it's for:** application developers who read run results and record decisions.
**You'll be able to:** read a `RunResult`, find out why a rule did or didn't apply, and record what an audit needs.
**Before you start:** [Engines and runs](engines-and-runs.md), for the match policies and which rules a run uses.

[← Documentation index](README.md)

- [What a run reports](#-what-a-run-reports)
- [Gotchas](#-gotchas)
- [Auditing a decision](#-auditing-a-decision)
- [Questions you might not think to ask](#-questions-you-might-not-think-to-ask)

---

## 📊 What a run reports

| Call | Returns | Use it when |
| --- | --- | --- |
| `run(facts)` | The output object, or `null` | You only need the decision |
| `runWithResult(facts)` | A `RunResult` | You also record which rules fired, and which rules the run used |
| `runWithResult(facts, options)` | A `RunResult` | One run needs its own timeout ([Stopping a run](stopping-runs.md)) or only the rules with given [tags](engines-and-runs.md#tags) |

`run(facts)` is `runWithResult(facts).output()`: the same run, with the same checks, listener callbacks and exceptions.
A [run result](glossary.md#run-result) holds the decision:

- `output()`: the output object, or `null` exactly when no rule fired.
- `firedRules()`: the rules whose actions ran, in firing order. It's unmodifiable, and has at most one rule on a
  first-match or unique-match engine.

It describes the loaded rules as this run saw them:

- `evaluations()`: one [rule evaluation](glossary.md#rule-evaluation) for every loaded rule, in evaluation order,
  each with the rule and its outcome: `MATCHED`, `NOT_MATCHED`, `NOT_EVALUATED` for a rule after the match on a
  first-match engine, or `SKIPPED` for a rule the run
  [skipped](engines-and-runs.md#-choosing-which-rules-a-run-uses). It's unmodifiable, and empty when the rule list is.
- `ruleSetChecksum()`: the checksum of the rules this run used.

It also says what chose the rules the run used:

- `tags()`: the tags the run was given, `RunOptions.tags()`, in `String` order. It's unmodifiable, never `null`, and
  empty when the run used every rule.
- `startedAt()`: the instant the run judged every rule's
  [validity window](engines-and-runs.md#the-validity-window-and-the-engines-clock) at, from the engine's clock.

The engine fills in both on every result it returns. A `RulesEngine` of your own, such as a test double, that builds
its result with `RunResult.of(...)` returns empty `tags()` and a `null` `startedAt()`, just as the short `of(...)`
form has empty `evaluations()`. `withRun(run)` returns a copy of a result that carries a `RunContext`'s tags and
instant.

A run that fails throws, so there's never a partial result, and no evaluation is reported for a rule that failed.

The evaluations answer "why didn't rule X apply?" without a listener:

```java
RunResult<LoanDecision> result = engine.runWithResult(facts);
for (RuleEvaluation evaluation : result.evaluations()) {
    System.out.println(evaluation.rule().getRuleName() + ": " + evaluation.outcome());
}
// prime-rate: MATCHED
// standard-rate: NOT_EVALUATED
```

On a first-match engine, `NOT_EVALUATED` says only that the rule came after the match. To know whether it would have
matched, use an all-matches or a unique-match engine, which evaluate every condition. `SKIPPED` says the run didn't
use the rule, but not which of the three reasons applied. The result holds what decided it: compare the rule's
`isEnabled()`, its `getValidFrom()` and `getValidTo()` with `result.startedAt()`, and its `getTags()` with
`result.tags()`.

Since 2.2.0, an evaluation also has `detail()`: what the rule's expression language says about why the condition
came out as it did, or `null`. Its type and content are the language's own. It's `null` when the language gives none,
and for a `NOT_EVALUATED` or `SKIPPED` rule, whose condition never ran. In MVEL, the only language the engine ships,
it's always `null`.

The engine records a detail whenever the language gives one with a `Boolean` value; there's nothing to turn on. It
isn't part of `equals()` or `hashCode()`, which compare the rule and the outcome, and a listener's `afterEvaluate`
doesn't receive it. A `RulesEngine` of your own creates an evaluation with a detail with
`RuleEvaluation.of(rule, outcome, detail)`. A language author returns it as
[Writing an expression language](languages/custom.md#explaining-a-conditions-result) describes.

> [!IMPORTANT]
> `run()` returns `null` when no rule fired: no condition was true, or the rule list is empty. A rule that fired always
> gives a non-`null` output, even when its action changed nothing, so check for `null` before you read the output.

The engine keeps the `Rule` objects you pass to `load()`. `rules().rules()`, `firedRules()`, `evaluations()` and
listener callbacks all give you those same instances, so you can compare them with `==` or use them as keys in a map.

### The loaded rules

`engine.rules()` returns a `RuleSetInfo`, which describes the [loaded rules](glossary.md#loaded-rules):

| Engine state | `rules()` | `checksum()` | `loadedAt()` |
| --- | --- | --- | --- |
| Before the first `load()` | Empty | The checksum of no rules, `e3b0c442…b855` | `null` |
| After `load(List.of())` | Empty | The checksum of no rules, `e3b0c442…b855` | When they loaded |
| After `load(rules)` | The rules, in evaluation order | Their checksum | When they loaded |

Before the first `load()`, `run()` throws `IllegalStateException` (`load() must be called before run()`). After an empty
`load()`, every run returns `null`, and still checks the facts' names. On a closed engine, `rules()` throws
`IllegalStateException`.

## 🚧 Gotchas

| Gotcha | What happens | Do this instead |
| --- | --- | --- |
| **`null` from `run()`** | No rule fired, and code that reads the output throws `NullPointerException` | Check for `null`, or add a lowest-priority rule whose condition is `true` |
| **The same checksum on every match policy** | Engines with the same rules report the same checksum, whatever their policy | Record `RunContext.matchPolicy()` too; see [Auditing a decision](#-auditing-a-decision) |
| **`rules().checksum()` read after a run** | A reload in between gives the new rules' checksum | Record `RunResult.ruleSetChecksum()` |
| **A changed description** | The checksum stays the same, but the two `Rule` objects aren't `equals` | Compare checksums to tell whether the rules changed what they do |

> [!NOTE]
> The rest of this page is for applications that record decisions. You can stop here if you don't need to tie a
> decision to the rules that made it.

## 🔏 Auditing a decision

To tie a decision to the rules that made it, record these with it:

- **`RunResult.ruleSetChecksum()`**: which rules the run used. Don't read `engine.rules().checksum()` afterwards: a
  reload in between changes it, while the run finished with the rules it started with; see
  [Reloading rules](engines-and-runs.md#-reloading-rules).
- **The match policy**: `RunContext.matchPolicy()`, which is `"firstMatch"`, `"allMatches"` or `"uniqueMatch"`.
- **`RunResult.firedRules()`**: which rules fired, in order.

To explain the rules that didn't fire, add:

- **`RunResult.evaluations()`**, when the audit must also say why the other rules didn't apply. It marks the rules
  the run skipped, but not why.
- **`tags()` and `startedAt()`**, on `RunContext` or `RunResult`: what explains a `SKIPPED` rule. Runs with the same
  checksum can use different rules when their tags or start differ.

A listener's `afterRun` receives all of them, after every run that succeeds:

```java
// auditLog is your own code.
RuleListener audit = new RuleListener() {
    @Override
    public void afterRun(RunContext run, RunResult<?> result) {
        auditLog.record(run.matchPolicy(), result.ruleSetChecksum(),
                result.firedRules().stream().map(Rule::getRuleName).toList(),
                run.tags(), run.startedAt());
    }
};
RulesEngine<LoanDecision> engine = RulesEngineBuilder.firstMatch(LoanDecision::new).listener(audit).build();
```

> [!WARNING]
> The checksum identifies the rules, not the engine. Engines with different match policies loaded with the same rules
> report the same checksum, although they can decide differently. Record `RunContext.matchPolicy()` with
> `RunResult.ruleSetChecksum()`.

The checksum is the lowercase hex SHA-256 over each rule, in evaluation order: its name, its priority as decimal text,
its [resolved language](glossary.md#resolved-language), its condition, its action, `true` or `false` for whether it's
enabled, its `validFrom` and `validTo` as ISO-8601 text as `Instant.toString()` writes it (`2027-06-01T00:00:00Z`),
and its tags. Each value is written as its length in UTF-8 bytes, in four bytes with the most significant first, then
those bytes. A `null` priority, `validFrom` or `validTo` is written as the length `-1`.

The tags are written as their count, in four bytes, then each tag as a value, sorted by their UTF-8 bytes compared as
unsigned numbers, which can differ from Java's `String` order. So another system can compute the same checksum from
the same rules.

| Change | Changes the checksum? |
| --- | :---: |
| A rule's name, condition or action | ✅ |
| A rule's priority, including from `null` to `0` | ✅ |
| Enabling or disabling a rule | ✅ |
| A rule's `validFrom` or `validTo`, including from `null` | ✅ |
| Adding or removing a rule's tag | ✅ |
| Adding or removing a rule | ✅ |
| Swapping two rules with equal priorities in the list | ✅ |
| A different default language on the engine, for a rule with no `language` | ✅ |
| A rule's description | ❌ |
| The list order of rules whose priorities differ | ❌ |
| Setting `language("mvel")` on a rule when MVEL is already the default | ❌ |
| The match policy | ❌ |
| The order a rule's tags were given in, or a tag given twice | ❌ |
| Imports, listeners, options, declared facts, output type or writer, copy limit, timeout or clock | ❌ |
| The tags a run is given, or when it runs | ❌ |
| Loading the same rules again (`loadedAt()` changes) | ❌ |

## ❓ Questions you might not think to ask

### How do I find out why a rule didn't apply?

Read `runWithResult(facts).evaluations()`: it has every rule with `MATCHED`, `NOT_MATCHED`, `NOT_EVALUATED` after the
match on a first-match engine, or `SKIPPED` when the run didn't use the rule because it's disabled, outside its
validity window, or without the run's tags. To tell which, compare the rule with the result's `startedAt()` and
`tags()`. A rule whose condition threw has no outcome, because the run threw instead. See
[What a run reports](#-what-a-run-reports).

To learn why a condition came out as it did, read the evaluation's `detail()`, if the rule's language gives one.
MVEL doesn't, so for an MVEL rule it's `null`.

### Does `null` from `run()` always mean no rule fired?

Yes, exactly. A rule that fired gives a non-`null` output even when its action changed nothing. See
[What a run reports](#-what-a-run-reports).

### Does the checksum tell a first-match decision from an all-matches or a unique-match one?

No. It covers only the rules: each one's name, priority, resolved language, condition, action, whether it's enabled,
its validity window and its tags. Record
`RunContext.matchPolicy()` with `RunResult.ruleSetChecksum()`. See [Auditing a decision](#-auditing-a-decision).
