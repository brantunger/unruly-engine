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
or assigning a property. An assignment such as `output.approved = true` isn't always the same as calling the
setter: it writes a public field of that name first, and picks among overloaded setters in an order that can change
each time the JVM starts; see [Assignment gotchas](mvel-gotchas.md#-assignment-gotchas).

The action's value is ignored, so `output.approved = true; 42` is fine. Assigning to `output` itself,
as in `output = [:]`, fails the rule with a `RuleExecutionException` (`Cannot assign 'output'`).

So does setting a property the output has no setter or public field for, such as a record's component, on an output
that isn't a `Map`: `output.approved = true` fails with `could not access property (approved) in: java.lang.Boolean`,
which names the value's type, not the output's.

Inside a `def` function, `output = ...` doesn't fail: it creates a function-local variable, and the function's
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
- So is a string over 1,000 characters or 64 dot-separated parts, before any lookup.
- So is an existing class that can't load, such as one missing its superclass, with the `LinkageError` as cause.
- A well-formed package that doesn't exist, such as `"com.nope"`, is accepted.
- [`languageImports("mvel", ...)`](README.md#-choosing-a-language-per-rule) fails `load()` and `validate()`.
- An imported class's name can't be a [fact name](#fact-names-mvel-rejects).
- `build()` loads a single-class import with its thread's context class loader; a valid name it can't load is
  imported as a package.
- `load()` looks up classes in imported packages with its thread's context class loader, and `run()` checks fact
  names against it, on any thread.
- A thread without a context class loader uses this library's loader.

Your facts' and output object's classes must be reachable from that loader too; see
[Class loaders](../thread-safety.md#-class-loaders).

An `import pkg.*;` anywhere in a rule's text has the 1,000-character and 64-part limits, checked before any
lookup even if unused. It fails at the name: `failed to compile at line 1, column 8: Can't import '...': it has 65
dot-separated parts, and an import may have at most 64`. One whose `.*` starts past the text's first 32,768
characters fails too: move it earlier, or use `imports(...)`. Inline class imports and `import_static` aren't
size-checked.

In a class directory on a case-insensitive file system, MVEL's check whether `applicant` in `applicant.creditScore`
is a class finds `Applicant.class`; `NoClassDefFoundError: applicant (wrong name: Applicant)` counts as no class,
so `applicant` stays the fact. Any other `NoClassDefFoundError` while a rule compiles fails `load()`, naming the
rule; so does this one for a name over about 1,000 bytes
([#1001](https://github.com/brantunger/unruly-engine/issues/1001)).

## 📁 Facts in MVEL

A rule refers to a fact by its name, as a variable; see [Facts](../facts.md).
`applicant.creditScore` reads a public getter, a record accessor or a public field. A
misspelled property, or a private field with no getter, fails the run with
`could not access: creditScor; in class: com.example.Applicant`.

### Fact names MVEL rejects

`run()` throws `IllegalArgumentException` for a fact whose name MVEL can't read as that fact. A declared one fails
`load()` instead, with a `RuleCompilationException` (`Declared fact 'Math' can't be used: ...`); a blank one fails
`fact()`.

A name must be a Java identifier. `my-fact` would read as `my - fact`, so it, `2nd` and `first name` are rejected with:

```text
'my-fact' is not a valid fact name: rules can only refer to a fact named with a Java identifier
```

MVEL reads these as something else before the facts, so they're rejected too:

| Kind | Names |
| --- | --- |
| Literals | `true` `false` `null` `nil` `empty` |
| Primitive type names | `boolean` `byte` `char` `double` `float` `int` `long` `short` |
| Built-in class names | The 22 in [Classes and imports](#-classes-and-imports), such as `Math`, `String` and `Thread` |
| Operators and keywords | `and` `assert` `contains` `convertable_to` `def` `do` `else` `for` `foreach` `function` `if` `import` `import_static` `in` `instanceof` `is` `isdef` `new` `or` `return` `soundslike` `stacklang` `strsim` `switch` `until` `var` `while` `with` `this` |
| Imported classes | The simple name of an imported class: `LocalDate` for `imports("java.time.LocalDate")`, `Entry` for `imports("java.util.Map.Entry")`. Any class in an imported package: `Date` for `imports("java.util")` |
| Package roots | `java` once a rule uses `java.lang.Integer.MAX_VALUE`. Here MVEL would read the fact in the class's place; see below |

```text
'Math' cannot be used as a fact name: MVEL reads it as a keyword or class name, so rules would never see the fact
```

- The check is case-sensitive: `date` is accepted with `imports("java.util")`, and `Date` when nothing imports it.
- `$x`, `_` and `café` are accepted; `𝒜` (U+1D49C, beyond U+FFFF) isn't, though `imports(...)` accepts it in a
  package name.
- A class in an imported package that can't be loaded isn't read as a class name, so the fact keeps it, as in
  MVEL's own lookup; a [fatal error](../glossary.md#fatal-error) such as an `OutOfMemoryError` leaves `run()` instead.

**Package roots** are per rule list: the first part of each class its rules use, named with its package
outside strings and comments (`new java.util.ArrayList()`), from a package import (`acme` for `Order` with
`imports("acme.orders")`), nested in an imported class (`Map.Entry`) or a rule's `import`.

A field or method of a singly imported or built-in class, such as `Math.PI`, adds nothing, nor does an unused
import. The message says why, even for a class name:

```text
'java' cannot be used as a fact name: the rules use a class whose package starts with 'java', and MVEL would read the fact in the class's place
```

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
`RuleCompilationException: unqualified type ... creditScor`. With strong typing on, `load()` fails, saying why,
unless all of these hold, so MVEL can check everything:

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
`MVEL went round in a loop while running the expression, ...`. `def` recursion past 10,000 deep, importing a package,
on a huge stack, is misreported as a loop.

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
MVEL replaces that accessor without synchronization. Two threads sharing the expression could fail intermittently
with a `RuleExecutionException` caused by a `ClassCastException`. So concurrent runs never share a compiled expression:
each copy holds its own in its session.

A copy is built lazily:

- A session compiles an expression again the **first time that copy runs it**, so a copy pays only for the rules it
  reaches.
- The first session to run an expression takes the form `load()` compiled.
- As it runs, MVEL generates accessor classes for that session alone.

A copy made at load, with [`copiesAtLoad(n)`](../compiled-copies.md#making-copies-at-load), is
different: `load()` compiles every condition and action into it, reached or not, and the first such copy takes the forms
`load()` compiled. Its accessor classes are still generated as it runs.

An [extra copy](../glossary.md#extra-copy) pays that price and throws it away when the run ends. A rule that
keeps making them, by starting a run of the same engine on another thread under a full
[copy limit](../compiled-copies.md#-limiting-the-copies), recompiles and regenerates accessors each time, churning
CPU and metaspace.

A session's expressions belong to one run at a time, so any MVEL optimizer is safe. The engine leaves MVEL's JIT
optimizer on unless you pass `-Dmvel2.disable.jit=true`.

### The dynamic optimizer and class loaders

MVEL's dynamic optimizer registers an accessor the first time an expression runs, in a static list of up to
1,500, dropping the oldest as newer ones arrive. Dropping one frees nothing while the engine holding that
expression is alive: the expression still refers to the accessor.

Class loaders stay reachable through the optimizer in two ways. The optimizer's own class loader, held for the JVM's
life, has as parent the context class loader of whatever set MVEL up first; the engine uses MVEL's class loader.
A rule list's loader is held while its accessors are in the list. Neither closing nor dropping the engine
releases them.

An application that discards class loaders, on a WAR redeploy, a plugin or tenant reload, or a test harness
isolating each case, keeps one per cycle, with its classes and metaspace.

`-Dmvel2.disable.jit=true` switches the dynamic optimizer off, and the loaders are collected. MVEL reads it once, as
its optimizer factory initializes, so it belongs on the JVM's command line, and costs reflective accessors instead of
JIT ones JVM-wide, other MVEL users included.
A GraalVM native image needs it too, or its first condition fails; see
[MVEL's JIT must be off](../native-image.md#-mvels-jit-must-be-off).

The same optimizer is why a class the `load()` thread's context class loader can't reach fails a rule only after about
50 runs in quick succession: until the optimizer steps in, MVEL reads the fact reflectively and the rule works. A
steady trickle never reaches that burst, so tests pass and production fails. See
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
  expression, including each time a run makes a new compiled copy (see
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
