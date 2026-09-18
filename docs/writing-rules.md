# 📜 Writing rules

> [!NOTE]
> Describes 2.0.0, which isn't released yet. For 1.8.0, see
> [this page at v1.8.0](https://github.com/brantunger/unruly-engine/blob/v1.8.0/docs/writing-rules.md).

Every rule has two expressions: a **condition** that decides whether the rule matches, and an **action** that runs
when it fires. They are written in the expression language the rule names or, for a rule that names none, in the
engine's default language, which is [MVEL](languages/mvel.md) when MVEL is the only language found.
This guide covers what holds whatever the language; the [MVEL guide](languages/mvel.md) covers MVEL's syntax.

[← Documentation index](README.md)

- [Anatomy of a rule](#-anatomy-of-a-rule)
- [Choosing a language](#-choosing-a-language)
- [What rules can change](#-what-rules-can-change)
- [Loading rules from data](#-loading-rules-from-data)
- [A bigger example](#-a-bigger-example)
- [Testing rules](#-testing-rules)

---

## 🧬 Anatomy of a rule

```java
Rule.builder()
        .ruleName("prime-rate")                          // required and unique: used in errors and listeners
        .description("Best rate for excellent credit")   // free text, ignored by the engine
        .priority(10)                                    // higher fires first
        .language("mvel")                                // optional: null means the engine's default language
        .condition("applicant.creditScore >= 750")       // must evaluate to a boolean
        .action("output.approved = true; output.interestRate = 4.5")
        .build();
```

| Topic | Condition | Action |
| --- | --- | --- |
| **Facts, by name** | ✅ Read | ✅ Read |
| **`output`** | ❌ Not available: a condition that uses it fails when the rule runs | ✅ Change it |
| **Assignments** | ❌ Rejected: an assignment to a fact by `load()`, and any write to the facts when the rule runs | ✅ To the action's own variables, visible only inside it |
| **What it produces** | A `boolean` (not `null`, not a string) | Changes to `output`, made in place or returned as properties for the engine to set. In MVEL, the value the expression evaluates to is ignored |

- **Names are unique by exact match.** `Rate`, `rate` and `rate ` (with a trailing space) are three different names.
  Two rules with the same name fail `load()` at once with `Duplicate rule name 'rate'`, before any rule is compiled,
  so it's the only failure reported. A blank name fails `build()`.
- **Blank expressions fail at `load()`.** `build()` accepts an empty or whitespace-only condition or action, and
  `load()` rejects it (`Rule 'prime-rate' has a blank condition expression`). It's reported in one
  `RuleCompilationException` together with every other rule that fails to compile, one entry for each in
  `failures()`.
- **Order and output** belong to the engine: see [Rule order](engines-and-runs.md#-rule-order) and
  [The output object](engines-and-runs.md#-the-output-object).

> [!TIP]
> Rules are usually Java string literals, so in MVEL use **single quotes** for strings inside them:
> `"applicant.name == 'Ada'"` reads far better than `"applicant.name == \"Ada\""`.

## 🌐 Choosing a language

A rule is written in the language its `language` names, or in the engine's default language when that is `null`, and
one rule list can mix languages. An engine's languages and imports are set on its builder, so every `load()` compiles
the rules with the same ones. To give an engine several languages and choose one for each rule, see
[Choosing a language per rule](languages/README.md#-choosing-a-language-per-rule); the rest of
[Expression languages](languages/README.md) says how the engine picks the default and what to depend on.

## 🔏 What rules can change

### Conditions can't assign

A condition can't change the facts. Every language that passes the
[contract test kit](languages/custom.md#-testing-with-the-contract-kit) rejects a condition that assigns to a fact
when `load()` compiles it, with a `RuleCompilationException`. The engine also rejects any write to the facts while a
condition runs, failing the rule with `Cannot assign or declare 'x' in a condition`.

That isn't a sandbox: a condition can still call methods, loop and run several statements, so it can change state
(`System.setProperty('k', 'v') == null`) or never finish (`while (true) {}; true`). Keep conditions to expressions
without side effects. In MVEL, these are rejected by `load()`:

| Rejected condition | Why it's usually a mistake |
| --- | --- |
| `applicant.approved = true` | A typo for `==` |
| `applicant.creditScore += 5`, `x++` | Changes a fact |
| `x = 5; x > 1` | Declares a local variable |
| `with (applicant) { ... }`, `def f() { ... }` | A `with` block or function |

> [!WARNING]
> Neither check sees a method call that changes a fact object, such as `applicant.setApproved(true)`. Every condition
> and action after it in the same run sees the change, even on a first-match engine, and so does your own code. Keep
> method calls in conditions free of side effects.

### Actions change the output

- **Local variables stay local.** A variable an action declares is visible only inside that action. Other rules still
  see the original facts.
- **Change `output`; don't replace it.** An action changes the output object in place, or returns properties for the
  engine to set on it. It can't put another object in its place. In MVEL, see
  [Mostly in actions](languages/mvel.md#mostly-in-actions).
- **The facts stay as they were.** A language that writes to the facts it's given (`ActionContext.facts()`) fails the
  rule with `The facts passed to an action are read-only`. In MVEL, assigning to a fact's name creates a variable local
  to the action instead; see [Mostly in actions](languages/mvel.md#mostly-in-actions).
- **Pass results between rules through `output`.** In an all-matches engine, all matched actions share the same output
  object, in priority order.
- **Facts aren't copied.** An action that calls a method that changes a fact, such as
  `applicant.setCreditScore(0)`, affects the rules that fire after it in the same run. Conditions have already been
  evaluated by then, so it never changes which rules match.

## 💾 Loading rules from data

Conditions and actions are strings, so rules can live in a database, a YAML file or a configuration service. To read
rules from JSON with Jackson, register one mix-in for `Rule` and one for its builder:

```java
@JsonDeserialize(builder = Rule.RuleBuilder.class)
abstract class RuleMixIn {
}

@JsonPOJOBuilder(withPrefix = "")
abstract class RuleBuilderMixIn {
}

ObjectMapper mapper = JsonMapper.builder()
        .addMixIn(Rule.class, RuleMixIn.class)
        .addMixIn(Rule.RuleBuilder.class, RuleBuilderMixIn.class)
        .build();
List<Rule> rules = mapper.readValue(json, new TypeReference<List<Rule>>() { });
```

The code is the same for Jackson 2 and Jackson 3, which Spring Boot 4 uses; only the imports differ
(`com.fasterxml.jackson` or `tools.jackson`). A rule without a name, condition or action fails while it's read.

Rules are code, so load them only from sources you trust as much as your application code; see
[Security](../README.md#-security).

## 📐 A bigger example

An all-matches engine suits validation: every rule that matches adds its finding, so the output collects them all.

```java
// Applicant is the record from the README's quick start; Loan is another of your types:
// public record Loan(double amount, int termMonths) {}

RulesEngine<List<String>> engine = RulesEngineBuilder.<List<String>>allMatches(ArrayList::new).build();
engine.load(List.of(
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

`load()` catches many mistakes, but not all of them: a misspelled property, a missing import or a
non-boolean condition only fails when the rule runs (see
[Caught when loading or only when running?](error-handling.md#-caught-when-loading-or-only-when-running)). An MVEL engine
that [declares its facts](facts.md#-declaring-facts) and turns on
[strong typing](languages/mvel.md#-strong-typing) catches a misspelled property and an unknown fact at `load()`
too. Either way, give each rule a test with sample facts that make it match and not match:

```java
// primeRateRule is the prime-rate Rule from the README's quick start.
@Test
void primeRateAppliesFrom750() {
    RulesEngine<LoanDecision> engine = RulesEngineBuilder.firstMatch(LoanDecision::new).build();
    engine.load(List.of(primeRateRule));

    FactStore<Object> facts = new FactMap<>();
    facts.setValue("applicant", new Applicant("Ada", 750));
    assertEquals(4.5, engine.run(facts).getInterestRate());

    facts.setValue("applicant", new Applicant("Ada", 749));
    assertNull(engine.run(facts));
}
```

To check the order the engine evaluates the rules in, assert on `rules().rules()`, which lists them in
[evaluation order](engines-and-runs.md#-rule-order):

```java
// rules is the list your application loads.
@Test
void rulesAreEvaluatedInPriorityOrder() {
    RulesEngine<LoanDecision> engine = RulesEngineBuilder.firstMatch(LoanDecision::new).build();
    engine.load(rules);

    assertEquals(List.of("prime-rate", "standard-rate"),
            engine.rules().rules().stream().map(Rule::getRuleName).toList());
}
```

If your rules live outside the code, for example in a database, a test that loads them all and runs each one
against representative facts catches errors before they reach production. Rules are code, so protect that database
like your source code; see [Security](../README.md#-security).
