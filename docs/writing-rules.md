# ✍️ Writing rules

Every rule has two [MVEL](https://github.com/mvel/mvel) expressions: a **condition** that decides whether the rule
matches, and an **action** that runs when it fires. MVEL looks like Java, with some extra operators and looser
typing.

[← Back to README](../README.md)

- [Anatomy of a rule](#-anatomy-of-a-rule)
- [MVEL cheat sheet](#-mvel-cheat-sheet)
- [Classes and imports](#-classes-and-imports)
- [What rules can change](#-what-rules-can-change)
- [Comparison gotchas](#-comparison-gotchas)
- [A bigger example](#-a-bigger-example)
- [Testing rules](#-testing-rules)

---

## 🧬 Anatomy of a rule

```java
Rule.builder()
        .ruleName("prime-rate")                          // unique, used in errors and listeners
        .description("Best rate for excellent credit")   // free text, ignored by the engine
        .priority(10)                                    // higher fires first
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
> Rules are usually Java string literals, so use **single quotes** for strings inside them:
> `"applicant.name == 'Ada'"` reads far better than `"applicant.name == \"Ada\""`.

## ⚡ MVEL cheat sheet

Every example below was checked against the engine. For the full language, see the
[MVEL language guide](http://mvel.documentnode.com/).

### In conditions and actions

| To | Write |
| --- | --- |
| Read a property (getter, record accessor or `Map` key) | `applicant.creditScore` |
| Call a method | `applicant.name.length() > 2` |
| Compare | `applicant.name == 'Ada'`, `applicant.creditScore >= 650` |
| Combine | `applicant.creditScore > 700 && applicant.name != empty` |
| Test a string or collection for an element | `applicant.name contains 'd'`, `[700, 780] contains applicant.creditScore` |
| Match a regular expression | `applicant.name ~= '[A-Z][a-z]+'` |
| Choose a value | `applicant.creditScore >= 750 ? 'prime' : 'standard'` |
| Concatenate | `'Hello ' + applicant.name` |
| Write an inline list or map | `[1, 2, 3]`, `['a': 1, 'b': 2]` |
| Check that a fact was supplied | `isdef coapplicant` |
| Use a class without importing it | `java.time.LocalDate.now().getYear() >= 2026` |

> [!WARNING]
> MVEL has no `in` membership test: `780 in [700, 780]` doesn't compile. To check whether a collection holds a
> value, write `[700, 780] contains 780`.

### In actions only

| To | Write |
| --- | --- |
| Run several statements | `output.approved = true; output.interestRate = 4.5` |
| Branch | `if (applicant.creditScore > 700) { output.tier = 'high' } else { output.tier = 'low' }` |
| Loop | `total = 0; foreach (n : [1, 2, 3]) { total += n }; output.total = total` |
| Define a function | `def bonus(score) { score / 100 }; output.bonus = bonus(applicant.creditScore)` |
| Make several calls on one object | `with (output) { put('a', 1), put('b', 2) }` |

## 📥 Classes and imports

Without an import, MVEL resolves only this fixed set of class names:

| Built-in class names |
| --- |
| `Boolean` `Byte` `Character` `CharSequence` `Class` `ClassLoader` `Double` `Exception` `Float` `Integer` `Long` `Math` `Number` `Object` `Runtime` `Short` `String` `StringBuilder` `System` `Thread` `Void` `Array` (`java.lang.reflect.Array`) |

Everything else needs an import or a fully qualified name. That includes most of `java.lang`: `IllegalStateException`
fails with `could not resolve class`, and `ProcessHandle` with `unresolvable property or identifier`.

```java
engine.addImport("java.util");                        // a whole package
engine.addImport("java.time.LocalDate");              // a single class
engine.addImport("java.util.Map.Entry");              // a nested class, spelled as in a Java import
engine.addImports(Set.of("java.math", "java.time"));  // several at once

engine.setRuleList(rules);                            // imports take effect here
```

> [!IMPORTANT]
> Register imports **before** `setRuleList()`. The rules are compiled with the imports registered at that moment,
> so an import added afterwards has no effect until the next `setRuleList()`. A rule that needs a missing import is
> still accepted, and only fails at `run()` with `unresolvable property or identifier`.

- A string that is neither a loadable class nor a valid package name, such as `"java.util."`, is rejected with an
  `IllegalArgumentException`, and nothing from that call is imported.
- A well-formed package name that doesn't exist, such as `"com.nope"`, can't be detected and is accepted.
- An imported class name can no longer be used as a fact name. After `addImport("java.util")`, a fact named `Date`
  is rejected. See [Facts](facts.md#-naming-rules).
- Classes are looked up with the context class loader of the thread that calls `setRuleList()`. Fact names are
  checked against that class loader too, on whichever thread calls `run()`.

## 🔏 What rules can change

### Conditions are read-only

`setRuleList()` rejects, with a `RuleCompilationException`, any condition that assigns or declares something:

| Rejected condition | Why it's usually a mistake |
| --- | --- |
| `applicant.approved = true` | A typo for `==` |
| `applicant.creditScore += 5`, `x++` | Changes a fact |
| `x = 5; x > 1` | Declares a local variable |
| `with (applicant) { ... }`, `def f() { ... }` | A `with` block or function |

> [!NOTE]
> The check reads the condition's text, so it can't see a method call that changes a fact, such as
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

## 🚧 Comparison gotchas

MVEL compares values more loosely than Java, which can make a condition match, or not match, unexpectedly.

| Gotcha | Example | Do this instead |
| --- | --- | --- |
| 🔤 **Enums vs strings** | `order.status == 'SHIPPED'` is always `false` when `status` is an enum, with no error | `order.status.name() == 'SHIPPED'` |
| 🔢 **Type coercion** | `'1' == 1` is `true`. A `BigDecimal` of `1.00` equals `1`. | Compare values of the same type when the difference matters |
| 🔠 **String ordering** | A String fact `"10"` compared as `s > 9` is `true`, but `'10' > '9'` compares text and is `false` | Convert first: `Integer.parseInt(s) > 9` |
| 🕳 **`empty`** | `s == empty` is `true` for `""`, and `n == empty` is `true` for `0` | Use `== ''` or `== 0` when you mean exactly that |
| ❓ **Missing facts** | A fact that isn't in the store throws `unresolvable property or identifier`, so `x == null` can't test for it | `isdef x && x > 1` |

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
