# ⚡ MVEL

MVEL is the engine's default expression language: a rule is written in MVEL when its `language` is unset or
`"mvel"`. MVEL looks like Java, with some extra operators and looser typing; see
[MVEL gotchas](mvel-gotchas.md).

[← Documentation index](../README.md)

- [MVEL cheat sheet](#-mvel-cheat-sheet)
- [Classes and imports](#-classes-and-imports)
- [Facts in MVEL](#-facts-in-mvel)
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
> `in` isn't a membership test: `780 in [700, 780]` fails `load()`; `score in [700, 780]` is just `score`. Write
> `[700, 780] contains score`.

### Mostly in actions

| To | Write |
| --- | --- |
| Run several statements | `output.approved = true; output.interestRate = 4.5` |
| Branch | `if (applicant.creditScore > 700) { output.tier = 'high' } else { output.tier = 'low' }` |
| Loop | `total = 0; foreach (n : [1, 2, 3]) { total += n }; output.total = total` (with [strong typing](#-strong-typing), `foreach (int n : ...)`) |
| Define a function | `def bonus(score) { score / 100 }; output.bonus = bonus(applicant.creditScore)` (not with [strong typing](#-strong-typing)) |
| Make several calls on one object | `with (output) { put('a', 1), put('b', 2) }` |

An action changes `output` in place, by calling a method, such as `output.setInterestRate(4.5)` or `output.put(...)`,
or by assigning a property. An assignment such as `output.approved = true` isn't always the same as calling the
setter: it writes a public field of that name first, and picks among overloaded setters in an order that can change
each time the JVM starts; see [Assignment gotchas](mvel-gotchas.md#-assignment-gotchas).

The value the action evaluates to is ignored, so `output.approved = true; 42` is fine. Assigning to `output` itself,
as in `output = [:]`, fails the rule with a `RuleExecutionException` (`Cannot assign 'output'`).

So does setting a property the output has no setter or public field for, such as a record's component, on an output
that isn't a `Map`: `output.approved = true` fails with `could not access property (approved) in: java.lang.Boolean`,
which names the value's type, not the output's.

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

Everything else needs an import or a fully qualified name, including most of `java.lang`.

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

An engine's imports are set when it's built; every `load()` uses them. A rule missing an import passes `load()`
and fails at `run()`: `unresolvable property or identifier` for a class it calls, such as `Objects.isNull(x)`, or
`could not resolve class` for one it creates, such as `new ArrayList()`. An action declaring a variable of the class,
as in `BigDecimal total = 0`, fails `load()` instead: `unknown class or illegal statement`, often naming `BigDecimal`.

- A string that is neither a loadable class nor a valid package name, such as `"java.util."`, is rejected with an
  `IllegalArgumentException` from `build()`.
- A string over 1,000 characters or 64 dot-separated parts is rejected too, before any lookup.
- A class that exists but can't be loaded, such as one missing its superclass, is rejected too, with the
  `LinkageError` as the cause.
- A well-formed package name that doesn't exist, such as `"com.nope"`, can't be detected and is accepted.
- An imported class name can't be a fact name: with `imports("java.util")`, a fact named `Date` is rejected;
  see [Fact names MVEL rejects](#fact-names-mvel-rejects).
- `build()` resolves a single-class import such as `"java.time.LocalDate"` with the building thread's context
  class loader; a valid package name it can't load as a class is imported as a package.
- Classes in imported packages are looked up with the context class loader of the thread that calls `load()`.
  Fact names are checked against that class loader too, on whichever thread calls `run()`.
- A thread without a context class loader uses this library's class loader.

The classes of your facts and of the output object must be reachable from that same class loader, whatever the
rules import; see [Class loaders](../thread-safety.md#-class-loaders).

While compiling `applicant.creditScore`, MVEL checks whether `applicant` is a class. In a class directory on a
case-insensitive file system, the lookup for `applicant.class` finds `Applicant.class`, and the JVM reports
`NoClassDefFoundError: applicant (wrong name: Applicant)`. That counts as no class, so `applicant` is read as the fact.
Any other `NoClassDefFoundError` while a rule compiles fails `load()`, naming the rule.

## 📁 Facts in MVEL

A rule refers to a fact by its name, as a variable. [Facts](../facts.md) covers what holds for every language; this
section covers what MVEL adds. `applicant.creditScore` reads a public getter, a record accessor or a public field. A
misspelled property, or a private field with no getter, fails the run with
`could not access: creditScor; in class: com.example.Applicant`.

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
- One that can't be loaded isn't read as a class name, so the fact keeps it, as in MVEL's own lookup; a
  [fatal error](../glossary.md#fatal-error) such as an `OutOfMemoryError` leaves `run()` instead.

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

Strong typing also changes arithmetic: MVEL computes in the declared types rather than in doubles; see the
Division row of [Comparison gotchas](mvel-gotchas.md#-comparison-gotchas).

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
`RuleCompilationException` naming the expression and rule;
[Errors when rules load](custom.md#-errors-when-rules-load) covers collecting and ordering them. The message has
the description and any position:

```text
Condition for rule 'prime-rate' failed to compile at line 1, column 26: Malformed expression
Action for rule 'prime-rate' failed to compile at line 1, column 11: unbalanced braces ( ... )
```

for the condition `applicant.creditScore >= ` and the action `output.put('rate', `. Each failure carries one `ERROR`
`Issue` with that line and column (from 1) and the description, shortened to 1,000 characters. `b.` or `( )` get
the engine's `malformed expression`, and `ArrayList(y)` with `java.util` imported `a class can't be called like a
method: use new`, both at line and column 0; `x == 1 && in` gets `malformed expression` at MVEL's position. MVEL's
errors without a position keep its description, such as `illegal use of reserved word: in`.

**A description of `null` ends with the root cause**, unless MVEL's own parser failed. The first rule to use, by
full name, a class whose static initializer throws gets `null`:

```text
Condition for rule 'r' failed to compile at line 1, column 1: null (caused by java.lang.RuntimeException: plain init failure)
```

**The line is reliable; the column is where MVEL gave up**, which isn't always the mistake. For MVEL's
`Malformed expression` it's the token after the one MVEL choked on, or one past the end of the line:
`applicant.creditScore == == 750` reports column 29, the `750`. `unbalanced braces` points at the brace.

**An assignment in a condition** is caught before compiling, with one issue at the operator or keyword:
`Condition for rule 'prime-rate' contains an assignment ('=' at line 1, column 23). Conditions can't change facts
or declare variables; use == to compare.`

**`import_static` in a condition** gets its own message, at the keyword: `uses import_static (at line
1, column 1), which declares the method as a variable, and conditions can't declare variables. Call the method through
its class instead, such as Math.max(a, b).` See
[What rules can change](../writing-rules.md#-what-rules-can-change).

**A condition that doesn't compile hides its action's errors** until the next `load()`; see
[Errors when rules load](custom.md#-errors-when-rules-load).

**What `load()` doesn't catch:** a missing import or an unknown identifier fails only at `run()`, unless
it's a declared variable's type or [strong typing](#-strong-typing) is on; see
[Classes and imports](#-classes-and-imports). With strong typing, `new Nosuch()` reads
`could not resolve class: Nosuch`; several errors read `(1,5) ...; (1,19) ...` at the first's position.

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

A copy an engine makes when the rules load, with [`copiesAtLoad(n)`](../compiled-copies.md#making-copies-at-load), is
different: `load()` compiles every condition and action into it, reached or not, and the first such copy takes the forms
`load()` compiled. Its accessor classes are still generated as it runs.

So an [extra copy](../glossary.md#extra-copy) — one a run makes because no copy is free, rather than one it borrows —
pays that price and then throws it away when the run ends. A rule that keeps making them, by starting a run of the
same engine on another thread under a full [copy limit](../compiled-copies.md#-limiting-the-copies), recompiles and
regenerates accessors over and over, costing CPU and metaspace churn.

Because a session's expressions belong to one run at a time, they're safe with any MVEL optimizer, and the engine
leaves MVEL's global optimizer setting alone. MVEL's default JIT optimizer stays in effect (unless you pass
`-Dmvel2.disable.jit=true`), and other libraries in the same JVM that use MVEL aren't affected. Earlier versions
switched the whole JVM to MVEL's slower reflective optimizer as the engine class loaded, unless started with
`-Dunruly.mvel.jit=true` (`AbstractRulesEngine.JIT_PROPERTY`, removed in 2.0).

### The dynamic optimizer and class loaders

MVEL's dynamic optimizer registers an accessor object the first time an expression is evaluated, and keeps it in a
list held from a static field. The list holds 1,500 accessors, and the oldest are dropped as newer ones arrive.
Dropping one frees nothing while the engine holding that compiled expression is alive: the expression still refers to
the accessor.

Class loaders stay reachable through the optimizer in two ways. The first class loader to evaluate a rule in the JVM
becomes the parent of the optimizer's own class loader and is held for the life of the JVM. The class loader a rule
list was loaded with is held while its accessors are in the list. Closing the engine releases neither, and neither
does dropping it.

An application that keeps one class loader for its lifetime — most applications — never notices. One that discards
loaders, on a WAR redeploy, a plugin or tenant reload, or in a test harness that isolates each case, keeps one loader
per cycle, with its classes and their metaspace.

`-Dmvel2.disable.jit=true` switches the dynamic optimizer off, and the loaders are then collected. MVEL reads the
property once, as its optimizer factory initializes, so it's a decision for the JVM's command line, and it costs
MVEL's reflective accessors instead of its JIT ones everywhere in that JVM, including other libraries that use MVEL.
A GraalVM native image needs it too, or its first condition fails; see
[MVEL's JIT must be off](../native-image.md#-mvels-jit-must-be-off).

The same optimizer is why a class the `load()` thread's context class loader can't reach fails a rule only after about
50 runs in quick succession: until the optimizer steps in, MVEL reads the fact reflectively and the rule works. A
steady trickle of runs never reaches the burst, so tests pass and production fails. See
[Class loaders](../thread-safety.md#-class-loaders).

When a rule's Java code throws, the failure's `getCause()` is what it threw, except where MVEL's exception
stays above it:

- Your own `Map`'s `get()` for `order.id`: until one evaluation of the expression succeeds, JIT on or off, and
  once more as the optimizer compiles it.
- A `toString()` converting an argument: until the optimizer compiles the expression, and always with
  `-Dmvel2.disable.jit=true`.
- An argument's code, such as `code.value` in `output.put('a', code.value)`, when the optimizer compiled the
  argument but not the call around it, which can happen while it compiles and then last.

An exception whose `getMessage()` throws is lost until one evaluation of the expression succeeds: MVEL reads the
message as it wraps the exception, so what `getMessage()` threw becomes the cause. Later failures read
`(message unavailable: …)`.

## 🧵 Virtual threads

On JDK 21 to 23, MVEL rules run from many virtual threads can **deadlock**: MVEL's property cache is guarded by one
monitor for the whole JVM, and a virtual thread that waits on a monitor keeps the platform thread carrying it. Once
every carrier is held, no run completes again. The engine's default limit on virtual threads (one copy for every two
processors) makes that less likely, but doesn't prevent it:

- each engine has its own limit, so the limits of several engines add up;
- a run that waited five seconds without a copy coming back takes an extra copy on its own thread;
- with one processor, the limit of one copy equals the one carrier;
- `unlimitedCopies()`, or a `maxCopies(...)` at or above the number of carriers, leaves no carrier free;
  [build slots](../virtual-threads.md#-waiting-for-a-build-slot) don't change that.

So on JDK 21 to 23:

- use JDK 24 or later if you can, where a virtual thread waiting on a monitor releases its carrier (JEP 491). A
  virtual thread still keeps its carrier while the JVM looks up a class, which MVEL does whenever it compiles an
  expression, including each time a run makes a new compiled copy (see
  [Making copies at load](../compiled-copies.md#making-copies-at-load));
- otherwise run MVEL rules on platform threads, or keep the copies of all your engines together below
  `jdk.virtualThreadScheduler.parallelism`, which is the number of processors unless you set it.

See [Limiting the copies](../compiled-copies.md#-limiting-the-copies) and
[Virtual threads](../virtual-threads.md).

## 🔒 Security

An MVEL rule has the same access to the JVM as your own Java code: it can start processes, read files, open sockets
and use reflection. The engine has no sandbox. A [timeout](../stopping-runs.md#-what-a-timeout-doesnt-do) stops a
run only between rules or when an expression returns: MVEL can't be stopped inside an expression, so
`while (true) {}` blocks the thread for ever. See [Security](../../README.md#-security).
