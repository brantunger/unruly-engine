# ✍️ Writing rules

Every rule has two expressions: a **condition** that decides whether the rule matches, and an **action** that runs
when it fires. They are written in an expression language: [MVEL](languages/mvel.md), unless the rule names another.
This guide covers what holds whatever the language; the [MVEL guide](languages/mvel.md) covers MVEL's syntax.

[← Back to README](../README.md)

- [Anatomy of a rule](#-anatomy-of-a-rule)
- [Choosing a language](#-choosing-a-language)
- [What rules can change](#-what-rules-can-change)
- [A bigger example](#-a-bigger-example)
- [Testing rules](#-testing-rules)

---

## 🧬 Anatomy of a rule

```java
Rule.builder()
        .ruleName("prime-rate")                          // unique, used in errors and listeners
        .description("Best rate for excellent credit")   // free text, ignored by the engine
        .priority(10)                                    // higher fires first
        .language("mvel")                                // optional: null means MVEL
        .condition("applicant.creditScore >= 750")       // must evaluate to a boolean
        .action("output.approved = true; output.interestRate = 4.5")
        .build();
```

|  | Condition | Action |
| --- | --- | --- |
| **Facts, by name** | ✅ Read | ✅ Read |
| **`output`** | ❌ Not available | ✅ Change it in place |
| **Assignments and local variables** | ❌ Rejected by `setRuleList()` | ✅ Visible only inside this action |
| **Must evaluate to** | A `boolean` (not `null`, not a string) | Anything; the result is ignored |

> [!TIP]
> Rules are usually Java string literals, so in MVEL use **single quotes** for strings inside them:
> `"applicant.name == 'Ada'"` reads far better than `"applicant.name == \"Ada\""`.

## 🌐 Choosing a language

| Language | How to use it | Guide |
| --- | --- | --- |
| ⚡ MVEL | The default: leave `language` unset, or set it to `"mvel"` | [MVEL](languages/mvel.md): syntax cheat sheet, classes and imports, comparison gotchas |
| 🧩 Any other | `engine.registerLanguage(new MyLanguage())`, then `.language("my")` on each rule | [Other expression languages](languages/custom.md): choosing, writing and testing a language |

One rule list can mix languages. Register languages and imports **before** `setRuleList()`, which compiles every
rule with the languages and imports registered at that moment.

## 🔏 What rules can change

### Conditions are read-only

`setRuleList()` rejects, with a `RuleCompilationException`, a condition that assigns or declares something, when
its language can detect it. In MVEL, these are rejected:

| Rejected condition | Why it's usually a mistake |
| --- | --- |
| `applicant.approved = true` | A typo for `==` |
| `applicant.creditScore += 5`, `x++` | Changes a fact |
| `x = 5; x > 1` | Declares a local variable |
| `with (applicant) { ... }`, `def f() { ... }` | A `with` block or function |

> [!NOTE]
> MVEL's check reads the condition's text, so it can't see a method call that changes a fact, such as
> `applicant.setApproved(true)`. Keep method calls in conditions free of side effects.

### Actions change the output

- **Local variables stay local.** `score = 10; output.put('score', score)` works, but `score` is visible only
  inside that action. Other rules still see the original facts.
- **Change `output` in place.** Use `output.approved = true`, `output.setRate(4.5)` or `output.put(...)`. Assigning
  to `output` itself, as in `output = [:]`, fails with a `RuleExecutionException`. Inside a `def` function,
  `output = ...` doesn't fail: it creates a variable local to the function, and `output.put(...)` calls after it in
  that function change the discarded object.
- **Pass results between rules through `output`.** In a stateful engine, all matched actions share the same output
  object, in priority order.
- **Facts aren't copied.** An action that calls a method that changes a fact, such as
  `applicant.setCreditScore(0)`, affects the rules that fire after it in the same run. Conditions have already been
  evaluated by then, so it never changes which rules match.

## 📐 A bigger example

A stateful engine suits validation: every rule that matches adds its finding, so the output collects them all.

```java
public record Loan(double amount, int termMonths) {}

RulesEngine<List<String>> engine = RulesEngineBuilder.stateful(ArrayList::new);
engine.setRuleList(List.of(
        Rule.builder()
                .ruleName("low-credit")
                .priority(30)
                .condition("applicant.creditScore < 600")
                .action("output.add('Credit score is below 600')")
                .build(),
        Rule.builder()
                .ruleName("large-amount")
                .priority(20)
                .condition("loan.amount > 500000")
                .action("output.add('Amount needs manual approval')")
                .build(),
        Rule.builder()
                .ruleName("long-term")
                .priority(10)
                .condition("loan.termMonths > 360")
                .action("output.add('Term is longer than 30 years')")
                .build()));

FactStore<Object> facts = new FactMap<>();
facts.setValue("applicant", new Applicant("Bob", 580));
facts.setValue("loan", new Loan(750_000, 360));

List<String> findings = engine.run(facts);
// [Credit score is below 600, Amount needs manual approval]
// null when the application passes every check
```

## 🧪 Testing rules

`setRuleList()` catches many mistakes, but not all of them: a misspelled property, a missing import or a
non-boolean condition only fails when the rule runs (see
[Caught when loading or only when running?](error-handling.md#-caught-when-loading-or-only-when-running)). Give each
rule a test with sample facts that make it match and not match:

```java
@Test
void primeRateAppliesFrom750() {
    RulesEngine<LoanDecision> engine = RulesEngineBuilder.stateless(LoanDecision::new);
    engine.setRuleList(List.of(primeRateRule));

    FactStore<Object> facts = new FactMap<>();
    facts.setValue("applicant", new Applicant("Ada", 750));
    assertEquals(4.5, engine.run(facts).getInterestRate());

    facts.setValue("applicant", new Applicant("Ada", 749));
    assertNull(engine.run(facts));
}
```

If your rules live outside the code, for example in a database, a test that loads them all and runs each one
against representative facts catches errors before they reach production.
