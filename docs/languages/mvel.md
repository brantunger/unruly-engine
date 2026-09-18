# ⚡ MVEL

> [!NOTE]
> Describes 2.0.0, which isn't released yet. For 1.8.0, see
> [this page at v1.8.0](https://github.com/brantunger/unruly-engine/blob/v1.8.0/docs/languages/mvel.md).

MVEL is the engine's default expression language: a rule is written in MVEL when its `language` is unset or
`"mvel"`. MVEL looks like Java, with some extra operators and looser typing.

[← Documentation index](../README.md)

- [MVEL cheat sheet](#-mvel-cheat-sheet)
- [Classes and imports](#-classes-and-imports)
- [Facts in MVEL](#-facts-in-mvel)
- [Comparison gotchas](#-comparison-gotchas)
- [Strong typing](#-strong-typing)
- [Errors when rules load](#-errors-when-rules-load)
- [Compiled copies](#-compiled-copies)
- [Virtual threads](#-virtual-threads)
- [Security](#-security)

---

## ⚡ MVEL cheat sheet

Every example below was checked against the engine. For the full language, see the
[MVEL language guide](http://mvel.documentnode.com/).

### In conditions and actions

| To | Write |
| --- | --- |
| Read a property (getter, record accessor, public field or `Map` key) | `applicant.creditScore` |
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

> [!IMPORTANT]
> MVEL has no `in` membership test: `780 in [700, 780]` doesn't compile. To check whether a collection holds a
> value, write `[700, 780] contains 780`.

### Mostly in actions

| To | Write |
| --- | --- |
| Run several statements | `output.approved = true; output.interestRate = 4.5` |
| Branch | `if (applicant.creditScore > 700) { output.tier = 'high' } else { output.tier = 'low' }` |
| Loop | `total = 0; foreach (n : [1, 2, 3]) { total += n }; output.total = total` (with [strong typing](#-strong-typing), `foreach (int n : ...)`) |
| Define a function | `def bonus(score) { score / 100 }; output.bonus = bonus(applicant.creditScore)` (not with [strong typing](#-strong-typing)) |
| Make several calls on one object | `with (output) { put('a', 1), put('b', 2) }` |

An action changes `output` in place, as in `output.approved = true`, `output.setInterestRate(4.5)` or
`output.put(...)`, and the value the action evaluates to is ignored, so `output.approved = true; 42` is fine. Assigning
to `output` itself, as in `output = [:]`, fails the rule with a `RuleExecutionException` (`Cannot assign 'output'`).
Inside a `def` function, `output = ...` doesn't fail: it creates a variable local to the function, and `output.put(...)`
calls after it in that function change the discarded object.

Assigning to a fact's name, as in `score = 10; output.put('score', score)`, creates a variable local to the action:
the fact keeps its value, and later rules still see it.

A condition may also run several statements, branch or loop, as long as nothing in it assigns; see
[What rules can change](../writing-rules.md#-what-rules-can-change).

## 📥 Classes and imports

Without an import, MVEL resolves these class names on their own, as well as the primitive type names `boolean`
`byte` `char` `double` `float` `int` `long` `short`:

| Built-in class names |
| --- |
| `Boolean` `Byte` `Character` `CharSequence` `Class` `ClassLoader` `Double` `Exception` `Float` `Integer` `Long` `Math` `Number` `Object` `Runtime` `Short` `String` `StringBuilder` `System` `Thread` `Void` `Array` (`java.lang.reflect.Array`) |

Everything else needs an import or a fully qualified name, including most of `java.lang`. The error depends on how
the rule uses the class: `new IllegalStateException()` fails with `could not resolve class`, and a bare
`IllegalStateException` with `unresolvable property or identifier`.

> [!CAUTION]
> Imports are a convenience, not access control. A rule can reach any class by its fully qualified name, such as
> `java.lang.Runtime`, or through reflection on any object. See [Security](#-security).

```java
RulesEngine<LoanDecision> engine = RulesEngineBuilder.firstMatch(LoanDecision::new)
        .imports("java.util")                        // a whole package
        .imports("java.time.LocalDate")              // a single class
        .imports("java.util.Map.Entry")              // a nested class, spelled as in a Java import
        .imports("java.math", "java.time")           // several at once
        .build();                                    // imports are resolved here

engine.load(rules);
```

> [!IMPORTANT]
> An engine's imports are set when it's built, and every `load()` compiles with them. A rule that needs a missing
> import is still accepted, and only fails at `run()`: with `unresolvable property or identifier` for a class it calls,
> such as `Objects.isNull(x)`, or `could not resolve class` for one it creates, such as `new ArrayList()`.

- A string that is neither a loadable class nor a valid package name, such as `"java.util."`, is rejected with an
  `IllegalArgumentException` from `build()`, and no engine is built.
- A class that exists but can't be loaded, for example because a class it extends is missing from the class path,
  is rejected the same way, with the `LinkageError` as the cause. Before 1.6.1 it was imported as a package, and rules
  that used it failed later with `unresolvable property or identifier`.
- A well-formed package name that doesn't exist, such as `"com.nope"`, can't be detected and is accepted.
- An imported class name can no longer be used as a fact name. On an engine built with `imports("java.util")`, a fact
  named `Date` is rejected. See [Fact names MVEL rejects](#fact-names-mvel-rejects).
- A single-class import such as `"java.time.LocalDate"` is resolved by `build()`, with the building thread's
  context class loader. A string that loader can't load as a class, but that is a valid package name, is imported as
  a package.
- Classes in imported packages are looked up with the context class loader of the thread that calls `load()`.
  Fact names are checked against that class loader too, on whichever thread calls `run()`.
- A thread without a context class loader uses this library's own class loader instead.

## 📁 Facts in MVEL

A rule refers to a fact by its name, as a variable. [Facts](../facts.md) covers what holds for every language; this
section covers what MVEL adds.

### Fact names MVEL rejects

`run()` rejects a fact whose name MVEL can't read as that fact, with an `IllegalArgumentException`. A declared fact
with such a name fails `load()` instead, with a `RuleCompilationException`
(`Declared fact 'Math' can't be used: ...`).

A name must be a Java identifier. `my-fact` would read as `my - fact`, so it, `2nd` and `first name` are rejected with:

```text
'my-fact' is not a valid fact name: rules can only refer to a fact named with a Java identifier
```

These identifiers are rejected too, because MVEL reads them as something else before it looks at the facts:

| Kind | Names |
| --- | --- |
| Literals | `true` `false` `null` `nil` `empty` |
| Primitive type names | `boolean` `byte` `char` `double` `float` `int` `long` `short` |
| Built-in class names | The 22 in [Classes and imports](#-classes-and-imports), such as `Math`, `String` and `Thread` |
| Operators and keywords | `and` `assert` `contains` `convertable_to` `def` `do` `else` `for` `foreach` `function` `if` `import` `import_static` `in` `instanceof` `is` `isdef` `new` `or` `return` `soundslike` `stacklang` `strsim` `switch` `until` `var` `while` `with` `this` |
| Imported classes | The simple name of an imported class: `LocalDate` for `imports("java.time.LocalDate")`, `Entry` for `imports("java.util.Map.Entry")`. Any class in an imported package: `Date` for `imports("java.util")` |

```text
'Math' cannot be used as a fact name: MVEL reads it as a keyword or class name, so rules would never see the fact
```

- The check is case-sensitive: `date` is accepted with `imports("java.util")`, and `Date` is accepted when nothing
  imports it.
- Names such as `$x`, `_` and `café` are identifiers, so they're accepted, and rules can refer to them.
- A class in an imported package is looked up with the class loader `load()` captured, as the
  [imports](#-classes-and-imports) are.

### Null and missing facts

MVEL treats a fact whose value is `null` differently from one that isn't in the store:

| Condition | Fact `x` is `null` | No fact `x` |
| --- | --- | --- |
| `x == null` | `true` | Fails the run |
| `isdef x` | `true` | `false` |
| `isdef x && x != null` | `false` | `false` |

A fact with a `null` value and a `null` `FactReference` behave the same. Referring to a missing fact fails the rule with
a `RuleExecutionException` whose message has one of these, the second when the run's
[compiled copy](../glossary.md#compiled-copy) has already run the rule with the fact present:

```text
[Error: unresolvable property or identifier: x]
unable to resolve token: unable to resolve variable 'x'
```

So check with `isdef` before using a fact that may be left out, and for `null` too before reading its property:

```java
.condition("isdef coapplicant && coapplicant != null && coapplicant.creditScore >= 700")
```

A `Map` fact works the same way. A key that isn't in the map is an error, not `null`, and this is intended: a missing
key is usually a misspelled rule.

| Condition, for an `order` map with no `missing` key | Result |
| --- | --- |
| `order.missing == null` | Fails the run: `could not access: missing; in class: java.util.HashMap` |
| `order['missing'] == null` | `true` |
| `order.containsKey('missing')` | `false` |
| `order.note == null`, when `note` is in the map with the value `null` | `true` |

## 🚧 Comparison gotchas

MVEL compares values more loosely than Java, which can make a condition match, or not match, unexpectedly.

| Gotcha | Example | Do this instead |
| --- | --- | --- |
| 🔤 **Enums vs strings** | `order.status == 'SHIPPED'` is always `false` when `status` is an enum, with no error | `order.status.name() == 'SHIPPED'` |
| 🔢 **Type coercion** | `'1' == 1` is `true`. A `BigDecimal` of `1.00` equals `1`. | Compare values of the same type when the difference matters |
| 🔠 **String ordering** | A String fact `"10"` compared as `s > 9` is `true`, but `'10' > '9'` compares text and is `false` | Convert first: `Integer.parseInt(s) > 9` |
| 🕳️ **`empty`** | `s == empty` is `true` for `""`, and `n == empty` is `true` for `0` | Use `== ''` or `== 0` when you mean exactly that |
| ❓ **Missing facts** | A fact that isn't in the store fails the run, so `x == null` can't test for it. See [Null and missing facts](#null-and-missing-facts) | `isdef x && x > 1` |
| 🔑 **A key missing from a `Map` fact** | `order.missing == null` fails the run with `could not access: missing`, rather than being `true` | `order['missing'] == null`, or `order.containsKey('missing')` |
| 🔒 **Facts whose class isn't public** | `applicant.score` on a package-private record fails with `could not access field`, even on the class path | Make the record public, or have it implement a public interface that declares `score()` |

## 🦺 Strong typing

MVEL can compile rules against the facts an engine declares, so a misspelled property or an unknown fact fails
`load()` with its line and column rather than a run. Turn it on with MVEL's one option:

```java
RulesEngineBuilder.firstMatch(LoanDecision::new)
        .outputType(LoanDecision.class)
        .fact("applicant", Applicant.class)
        .requireDeclaredFacts()
        .option("mvel", "strongTyping", "true")   // "true" or "false"; the default is false
        .build();
```

A misspelled name then fails `load()`, such as `.condition("applicant.creditScor >= 750")` with
`RuleCompilationException: unqualified type ... creditScor`. Strong typing only works when MVEL can check everything,
so with the option on, `load()` fails, saying why, unless all of these hold:

| Needed | Why |
| --- | --- |
| `requireDeclaredFacts()`, and at least one fact declared | Otherwise a name nobody declared may still be supplied at run time, so it isn't a mistake |
| No fact declared as `Object`, a `Map`, a `Collection`, or an array of one of them | MVEL's strict mode rejects `order.id` on a `Map`, `items[0].qty` on a `List` and any property of an `Object`, so one such fact would reject working rules |
| `outputType(...)` set to a type that isn't one of those | An action writes to `output`, so its type has to be checkable too |

See [Declaring facts](../facts.md#-declaring-facts) for `fact(...)` and `requireDeclaredFacts()`. Strong typing
doesn't catch a condition that isn't a boolean; the engine checks that itself, whatever the language.

- It rejects some rules that work without it:

  | Without strong typing | With it |
  | --- | --- |
  | `total = 0; foreach (n : [1, 2, 3]) { total += n }` | `total = 0; foreach (int n : [1, 2, 3]) { total += n }`: give the loop variable a type |
  | `m = ['a': 1]; output.score = m.a` | `m = ['a': 1]; output.score = m['a']`: read a map's entries by key |
  | `def bonus(score) { score / 100 }` | Not possible: write the expression inline |

- Any other option for `mvel`, or a value other than `true` or `false`, fails `load()` once MVEL compiles the rule list
  (a rule written in MVEL, including one that names no language when MVEL is the default, or an empty rule list with
  MVEL as the default), so a typo can't leave strong typing silently off.

## 🚨 Errors when rules load

MVEL rejects an expression it can't compile with an `InvalidExpressionException`, which `load()` reports as a
`RuleCompilationException` naming the expression and the rule. How the engine collects and orders those failures is
in [Errors when rules load](custom.md#-errors-when-rules-load); this is what MVEL puts in them. The message has
MVEL's description and, when MVEL gives one, the line and column:

```text
Condition for rule 'prime-rate' failed to compile at line 1, column 26: Malformed expression
Action for rule 'prime-rate' failed to compile at line 1, column 11: unbalanced braces ( ... )
```

for the condition `applicant.creditScore >= ` and the action `output.put('rate', `. Each failure carries one `Issue`
with severity `ERROR`, that line and column counting from 1, and the description. When MVEL gives no position, both
are 0 and the message has no `at line`.

**The line is reliable; the column is where MVEL gave up**, which isn't always the mistake. For `Malformed expression`
it's the token after the one MVEL choked on, or one past the end of the line: `applicant.creditScore == == 750` reports
column 29, the `750`, and `applicant.creditScore >= ` column 26. `unbalanced braces` points at the brace.

**An assignment in a condition** is found by MVEL's own scan of the text, before anything is compiled, and reported at
the operator or keyword, with one issue at that position: `Condition for rule 'prime-rate' contains an assignment ('='
at line 1, column 23). Conditions can't change facts or declare variables; use == to compare.`

**`import_static` in a condition** gets its own message from the same scan, at the keyword: `uses import_static (at line
1, column 1), which declares the method as a variable, and conditions can't declare variables. Call the method through
its class instead, such as Math.max(a, b).` What the scan catches is in
[What rules can change](../writing-rules.md#-what-rules-can-change).

**A condition that doesn't compile hides its action's errors** until the next `load()`, whatever the language; see
[Errors when rules load](custom.md#-errors-when-rules-load).

**What `load()` doesn't catch:** a missing import or an unknown identifier passes `load()` and fails at `run()`, as
[Classes and imports](#-classes-and-imports) explains, unless [strong typing](#-strong-typing) is on.

## 📑 Compiled copies

[Compiled copies](../compiled-copies.md) explains what a compiled copy is for every language. This is why
MVEL needs one, and what it costs.

MVEL caches an accessor in each compiled expression the first time it runs. When a later run binds the same fact name
to a different class — for example when `applicant` is an interface with several implementations, or is a `Map` in one
run and a record in another — MVEL replaces that accessor without synchronization. Two threads running the same
compiled expression could then fail intermittently with a `RuleExecutionException` caused by a `ClassCastException`. So
concurrent runs never share MVEL's compiled form of an expression: each copy holds its own in its session.

A copy is built lazily, one expression at a time:

- A session compiles an expression again the **first time that copy runs it**, so a copy only pays for the rules it
  reaches, not for the whole list.
- The first session to run a given expression takes the form `load()` compiled, so the rule list's own compilation
  isn't wasted.
- As it runs, MVEL generates accessor classes for that session alone.

So an [extra copy](../glossary.md#extra-copy) — one a run makes because no copy is free, rather than one it borrows —
pays that price and then throws it away when the run ends. A rule that keeps making them, by starting a run of the
same engine on another thread under a full [copy limit](../compiled-copies.md#-limiting-the-copies), recompiles and
regenerates accessors over and over, costing CPU and metaspace churn.

Because a session's expressions belong to one run at a time, they're safe with any MVEL optimizer, and the engine
leaves MVEL's global optimizer setting alone. MVEL's default JIT optimizer stays in effect (unless you pass
`-Dmvel2.disable.jit=true`), and other libraries in the same JVM that use MVEL aren't affected.

> [!NOTE]
> Earlier versions switched MVEL to its slower reflective optimizer for the whole JVM when the engine class loaded,
> unless the JVM was started with `-Dunruly.mvel.jit=true`. Later 1.x releases ignored that property, and 2.0 removes
> the `AbstractRulesEngine.JIT_PROPERTY` constant that named it.

## 🧵 Virtual threads

On JDK 21 to 23, MVEL rules run from many virtual threads can **deadlock**: MVEL's property cache is guarded by one
monitor for the whole JVM, and a virtual thread that waits on a monitor keeps the platform thread carrying it. Once
every carrier is held, no run completes again. The engine's default limit on virtual threads (one copy for every two
processors) makes that less likely, but doesn't prevent it:

- each engine has its own limit, so the limits of several engines add up;
- a run that waited five seconds without a copy coming back takes an extra copy on its own thread;
- with one processor, the limit of one copy equals the one carrier;
- `unlimitedCopies()`, or a `maxCopies(...)` at or above the number of carriers, leaves no carrier free.

So on JDK 21 to 23:

- use JDK 24 or later if you can, where a virtual thread waiting on a monitor releases its carrier (JEP 491). A
  virtual thread still keeps its carrier while the JVM looks up a class, which MVEL does whenever it compiles an
  expression, including each time a run makes a new compiled copy;
- otherwise run MVEL rules on platform threads, or keep the copies of all your engines together below
  `jdk.virtualThreadScheduler.parallelism`, which is the number of processors unless you set it.

See [Limiting the copies](../compiled-copies.md#-limiting-the-copies).

## 🔒 Security

An MVEL rule has the same access to the JVM as your own Java code: it can start processes, read files, open sockets
and use reflection. The engine has no sandbox. A [timeout](../stopping-runs.md#-what-a-timeout-doesnt-do) stops a
run only between rules or when an expression returns: MVEL can't be stopped inside an expression, so
`while (true) {}` blocks the thread for ever. See [Security](../../README.md#-security).
