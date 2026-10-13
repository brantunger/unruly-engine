# ⚡ MVEL

A rule is written in MVEL when its `language` is `"mvel"`, or unset and MVEL is the engine's
[default language](../glossary.md#default-language). MVEL looks like Java, with extra operators and looser typing;
see [MVEL gotchas](mvel-gotchas.md).

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
or assigning a property. An assignment isn't always the same as calling the
setter: it writes a public field of that name first, and picks among overloaded setters in an order that can change
each time the JVM starts; see [Assignment gotchas](mvel-gotchas.md#-assignment-gotchas).

The action's value is ignored, so `output.approved = true; 42` is fine. Assigning to `output` itself,
as in `output = [:]`, fails the rule with a `RuleExecutionException` (`Cannot assign 'output'`).

So does setting a property the output has no setter or public field for, such as a record's component, on an output
that isn't a `Map`: `output.approved = true` fails with `could not access property (approved) in: java.lang.Boolean`,
which names the value's type, not the output's.

Inside a `def` function, `output = ...` doesn't fail: it creates a function-local variable, and its
later `output.put(...)` calls change the discarded object.

Assigning to a fact's name, as in `score = 10; output.put('score', score)`, creates a variable local to the action:
the fact keeps its value, and later rules still see it.

A condition may also run several statements, branch or loop, as long as nothing in it assigns; see
[What rules can change](../writing-rules.md#-what-rules-can-change).

## 📥 Classes and imports

Without an import, MVEL resolves these class names, and the primitive type names `boolean`
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

A rule missing an import passes `load()` and fails at `run()`: `unresolvable property or identifier` for a
class it calls, such as `Objects.isNull(x)`, or `could not resolve class` for one it creates, such as
`new ArrayList()`. An action declaring a variable of the class, as in `BigDecimal total = 0`, fails `load()`
instead: `unknown class or illegal statement`, often naming `BigDecimal`.

Write a nested class as `new Outer.Nested()` after importing `Outer` or its package, or as
`new com.example.Outer$Nested()`: `new com.example.Outer.Nested()` fails at `run()` with `could not resolve class`.

- A string that is neither a loadable class nor a valid package name, such as `"java.util."`, is rejected with
  `IllegalArgumentException` from `build()`.
- So is a string over 1,000 characters or 64 parts, before any lookup.
- So is an existing class that can't load, such as one missing its superclass, with the `LinkageError` as cause.
- A well-formed package that doesn't exist, such as `"com.nope"`, is accepted.
- [`languageImports("mvel", ...)`](README.md#-choosing-a-language-per-rule) fails `load()` and `validate()`.
- Imported names, inline ones too, can't be [fact names](mvel-fact-names.md) a rule's text holds.
- `build()` loads a single-class import with its thread's context class loader, and imports a valid name it can't
  load as a package.
- `load()` looks up classes in imported packages with its thread's, and `run()` checks fact names against it, on
  any thread. A thread without one uses this library's loader.

Your facts' and output object's classes must be reachable from that loader too; see
[Class loaders](../thread-safety.md#-class-loaders). Looking up a name's class file leaves a soft cache entry in the
JDK's loaders, freed under memory pressure: not a leak.

An `import pkg.*;` anywhere in a rule's text has the 1,000-character and 64-part limits, checked before any
lookup even if unused. It fails at the name: `failed to compile at line 1, column 8: Can't import '...': it has 65
dot-separated parts, and an import may have at most 64`. One whose `.*` starts past the text's first 32,768
characters fails too: move it earlier, or use `imports(...)`. Inline class imports and `import_static` aren't
size-checked.

In a class directory on a case-insensitive file system, `applicant` in `applicant.creditScore` finds
`Applicant.class`; `NoClassDefFoundError: applicant (wrong name: Applicant)` counts as no class, so `applicant`
stays the fact. Any other `NoClassDefFoundError` while a rule compiles fails `load()`, naming the
rule; so does this one for a name over about 1,000 bytes
([#1001](https://github.com/brantunger/unruly-engine/issues/1001)).

## 📁 Facts in MVEL

A rule refers to a fact by its name, as a variable; see [Facts](../facts.md).
`applicant.creditScore` reads a public getter, a record accessor or a public field. A
misspelled property, or a private field with no getter, fails the run with
`could not access: creditScor; in class: com.example.Applicant`.

Where a rule's text holds a fact's name, MVEL rejects one it can't read as that fact, such as `my-fact`, `in` or
`Math`, and reserves `output` for its own rule lists. See [MVEL fact names](mvel-fact-names.md).

### Null and missing facts

A fact set to `null` and a missing one differ:

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

Check with `isdef` for a fact that may be missing, and for `null` before reading its property:

```java
.condition("isdef coapplicant && coapplicant != null && coapplicant.creditScore >= 700")
```

A `Map` fact works the same way: a missing key is an error, not `null`, as it's usually a misspelled rule.

| Condition, for an `order` map with no `missing` key | Result |
| --- | --- |
| `order.missing == null` | Fails the run: `could not access: missing; in class: java.util.HashMap` |
| `order['missing'] == null` | `true` |
| `order.containsKey('missing')` | `false` |
| `order.note == null`, when the map holds `note` as `null` | `true` |

## 🦺 Strong typing

MVEL can compile rules against an engine's declared facts, so a misspelled property or an unknown fact fails
`load()` with its line and column, not a run. Turn it on with MVEL's one option:

```java
RulesEngineBuilder.firstMatch(LoanDecision::new)
        .outputType(LoanDecision.class)
        .fact("applicant", Applicant.class)
        .requireDeclaredFacts()
        .option("mvel", "strongTyping", "true")   // "true" or "false"; the default is false
        .build();
```

`.condition("applicant.creditScor >= 750")` then fails `load()` with
`RuleCompilationException: unqualified type ... creditScor`. Strong typing needs all of these, so MVEL can check
everything; otherwise `load()` fails, saying why:

| Needed | Why |
| --- | --- |
| `requireDeclaredFacts()`, and at least one fact declared | Otherwise a name nobody declared may still be supplied at run time, so it isn't a mistake |
| No fact declared as `Object`, a `Map`, a `Collection`, or an array of one of them | MVEL's strict mode rejects `order.id` on a `Map`, `items[0].qty` on a `List` and any property of an `Object`, so one such fact would reject working rules |
| `outputType(...)` set to a type that isn't one of those | An action writes to `output`, so its type has to be checkable too |

Strong typing also changes arithmetic: MVEL computes in the declared types, not doubles; see the
Division row of [Comparison gotchas](mvel-gotchas.md#-comparison-gotchas).

See [Declaring facts](../facts.md#-declaring-facts) for `fact(...)` and `requireDeclaredFacts()`. Strong typing
doesn't catch a condition that isn't a boolean; the engine checks that for every language.

- It rejects some rules that work without it:

  | Without strong typing | With it |
  | --- | --- |
  | `total = 0; foreach (n : [1, 2, 3]) { total += n }` | `total = 0; foreach (int n : [1, 2, 3]) { total += n }`: give the loop variable a type |
  | `m = ['a': 1]; output.score = m.a` | `m = ['a': 1]; output.score = m['a']`: read a map's entries by key |
  | `def bonus(score) { score / 100 }` | Not possible: write the expression inline |

- Any other option for `mvel`, or a value other than `true` or `false`, fails `load()` once MVEL compiles the rule list
  (any MVEL rule, named or by default, or an empty list with MVEL as the default), so a typo can't leave strong
  typing silently off.

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
`Issue` with that line and column (from 1) and the description. `b.` or `( )` get the engine's
`malformed expression`, and `ArrayList(y)` with `java.util` imported `a class can't be called like a method: use new`,
both at line and column 0; `x == 1 && in` gets `malformed expression` at MVEL's position. MVEL's errors without a
position keep its description, such as `illegal use of reserved word: in`.

**MVEL shortens the description once**, never inside an escape: the escaped message from `failed to compile` on
fits 1,000 characters, as does each rejection of a fact name, an option's name or value, a declared or output
type, or language imports. `(N more characters)` counts characters before escaping; an escape the text holds counts as
written, as for a [nested failure](../nested-runs.md#-what-is-logged).

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

**`import_static` in a condition** is reported at the keyword: `uses import_static (at line
1, column 1), which declares the method as a variable, and conditions can't declare variables. Call the method through
its class instead, such as Math.max(a, b).` See
[What rules can change](../writing-rules.md#-what-rules-can-change).

**A call through a package-qualified class with something glued to it**, such as `java.lang.Math.abs(1)x`,
fails `load()` with `MVEL's analysis went round in a loop, ...` at line and column 0 after MVEL asked for the
class loader up to 2,132 + the expression's length times from one place: milliseconds, or 48 s for a 4,000-character
argument 50 brackets deep.

**A call after a package-qualified class and a non-ASCII space**, such as `java.lang.String.class`, then U+00A0, then
`(2)`, passes `load()`; a run reaching it fails the rule within milliseconds, even past the deadline, with
`MVEL went round in a loop while running the expression, ...`.
[Deep in a stack](mvel-gotchas.md#-a-first-load-or-run-deep-in-a-stack), both loops can run to 10,000 + 20 ×
length + length² / 4 calls in all. `def` recursion past 10,000 deep, importing a package, on a huge stack, is
misreported as a loop.

**On JDK 21, package-qualified calls nested hundreds deep are slow**, needing a large stack: 450 deep in a
`foreach` took 7 s to load and run (3 s unchecked); 1,000 deep in a condition, 26 s (5 s). JDK 26: 4 s and 10 s.

**A condition that doesn't compile hides its action's errors** until the next `load()`.

**What `load()` doesn't catch:** a missing import or an unknown identifier, unless it's a declared variable's type
or [strong typing](#-strong-typing) is on; see [Classes and imports](#-classes-and-imports). With strong typing,
`new Nosuch()` reads `could not resolve class: Nosuch`; several errors read `(1,5) ...; (1,19) ...` at the first's
position.

## 📑 Compiled copies

Why MVEL needs [compiled copies](../compiled-copies.md), and their cost.

MVEL caches an accessor in each compiled expression the first time it runs. When a later run binds the same fact name
to a different class, as with several implementations of an interface, or a `Map` in one run and a record in another,
MVEL replaces that accessor without synchronization. Two threads sharing it could fail
with a `RuleExecutionException` caused by a `ClassCastException`. So concurrent runs never share one:
each copy holds its own in its session.

A copy is built lazily:

- A session compiles an expression again the **first time that copy runs it**, so a copy pays only for the rules it
  reaches.
- The first session to run an expression takes the form `load()` compiled.
- As it runs, MVEL builds accessors for that session alone.

A copy made at load, with [`copiesAtLoad(n)`](../compiled-copies.md#making-copies-at-load), is
different: `load()` compiles every condition and action into it, reached or not, and the first such copy takes the forms
`load()` compiled. Its accessors are still built as it runs.

An [extra copy](../glossary.md#extra-copy) pays that price and throws it away when the run ends. A rule that
keeps making them, by starting a run of the same engine on another thread under a full
[copy limit](../compiled-copies.md#-limiting-the-copies), recompiles and rebuilds accessors each time.

A session's expressions belong to one run at a time, so any MVEL optimizer is safe.

### MVEL's optimizer

MVEL's JIT, its default optimizer, switches an expression from reflection to a class it generates with ASM once
it has run about 50 times within 100 ms. The engine makes the reflective optimizer MVEL's default for the whole JVM,
other MVEL users included, before it compiles a rule, whatever `-Dmvel2.disable.jit` says; MVEL used before then gets
MVEL's own. Benchmarked, that cost runs of 10 to 100 rules up to 13%; at 1,000 rules the JIT was 1.4 times slower
once warm.

To make the JIT the default again, after your first `load()` of MVEL rules, call
`OptimizerFactory.setDefaultOptimizer(OptimizerFactory.DYNAMIC)` (`org.mvel2.optimizers`). Its failures return:

- A call of an interface's static method, such as `List.of(1, 2)`, fails every run after 50 or more quick ones:
  `must be InterfaceMethodref constant`.
- A class name, `10B` or `10I` as an `Object` argument, such as `output.put('r', String)`, fails one run:
  `Operand stack underflow`.
- The [public-field rows](mvel-gotchas.md#-assignment-gotchas), the
  [50-run shapes](mvel-gotchas.md#-a-rule-failing-for-ever-after-about-50-quick-runs), the loaders below, and a
  [native image](../native-image.md#-mvels-jit-stays-off).

MVEL clears a thread's own optimizer after each use; only one set before the engine's first `load()`, by
`OptimizerFactory.setThreadAccessorOptimizer` or an MVEL compiled set expression, stays, for an inline list or `new`.

### The dynamic optimizer and class loaders

With the JIT the default, MVEL holds two class loaders: the JIT's own, for the JVM's life, whose parent is the
context class loader of the thread that made it the default, and a rule list's, while a static list of up to 1,500
accessors holds one of its accessors. Closing the engine releases neither, so an application discarding
class loaders, on a redeploy, keeps one per cycle. The reflective optimizer holds none.

When a rule's Java code throws, the failure's `getCause()` is what it threw, except where MVEL's exception
stays above it:

- Your own `Map`'s `get()` for `order.id`: until one evaluation of the expression succeeds, and with the JIT once
  more as it compiles it.
- A `toString()` converting an argument, unless the JIT compiled the expression.
- With the JIT, an argument's code, such as `code.value` in `output.put('a', code.value)`, when it compiled the
  argument but not the call around it, which can happen while it compiles and then last.

An exception whose `getMessage()` throws is lost until one evaluation of the expression succeeds, as MVEL reads the
message while wrapping it; see [Calling Java code](mvel-gotchas.md#-calling-java-code).

## 🧵 Virtual threads

On JDK 21 to 23, MVEL rules run from many virtual threads can **deadlock**: MVEL's property cache is guarded by one
JVM-wide monitor, and a virtual thread waiting on a monitor keeps the platform thread carrying it. Once every carrier
is held, no run completes again. The engine's default limit on virtual threads (one copy per two processors) makes that
rarer but doesn't prevent it:

- each engine has its own limit, so several engines' limits add up;
- a run that waited five seconds without a copy coming back takes an extra copy on its own thread;
- with one processor, the limit of one copy equals the one carrier;
- `unlimitedCopies()`, or a `maxCopies(...)` at or above the number of carriers, leaves no carrier free;
  [build slots](../virtual-threads.md#-waiting-for-a-build-slot) don't change that.

So on JDK 21 to 23:

- use JDK 24 or later, where a virtual thread waiting on a monitor releases its carrier (JEP 491). It
  still keeps its carrier while the JVM looks up a class, which MVEL does whenever it compiles an
  expression, including for each new compiled copy (see
  [Making copies at load](../compiled-copies.md#making-copies-at-load));
- otherwise run MVEL rules on platform threads, or keep all your engines' copies together below
  `jdk.virtualThreadScheduler.parallelism`, by default the number of processors.

See [Limiting the copies](../compiled-copies.md#-limiting-the-copies) and
[Virtual threads](../virtual-threads.md).

## 🔒 Security

An MVEL rule has the same access to the JVM as your Java code: it can start processes, read files, open sockets
and use reflection. The engine has no sandbox. A [timeout](../stopping-runs.md#-what-a-timeout-doesnt-do) stops a
run only between rules or when an expression returns: MVEL can't be stopped inside an expression, so
`while (true) {}` blocks the thread for ever. See [Security](../../README.md#-security).
