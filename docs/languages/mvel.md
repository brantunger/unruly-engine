# ⚡ MVEL

MVEL is the engine's default expression language: a rule is written in MVEL when its `language` is unset or
`"mvel"`. MVEL looks like Java, with some extra operators and looser typing.

[← Writing rules](../writing-rules.md) · [← Back to README](../../README.md)

- [MVEL cheat sheet](#-mvel-cheat-sheet)
- [Classes and imports](#-classes-and-imports)
- [Comparison gotchas](#-comparison-gotchas)
- [Security](#-security)

---

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

### Mostly in actions

| To | Write |
| --- | --- |
| Run several statements | `output.approved = true; output.interestRate = 4.5` |
| Branch | `if (applicant.creditScore > 700) { output.tier = 'high' } else { output.tier = 'low' }` |
| Loop | `total = 0; foreach (n : [1, 2, 3]) { total += n }; output.total = total` |
| Define a function | `def bonus(score) { score / 100 }; output.bonus = bonus(applicant.creditScore)` |
| Make several calls on one object | `with (output) { put('a', 1), put('b', 2) }` |

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

> [!NOTE]
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
> import is still accepted, and only fails at `run()`: with `unresolvable property or identifier` for a class it calls, such as
> `Objects.isNull(x)`, or `could not resolve class` for one it creates, such as `new ArrayList()`.

- A string that is neither a loadable class nor a valid package name, such as `"java.util."`, is rejected with an
  `IllegalArgumentException` from `build()`, and no engine is built.
- A class that exists but can't be loaded, for example because a class it extends is missing from the class path,
  is rejected the same way, with the `LinkageError` as the cause. Before 1.6.1 it was imported as a package, and rules
  that used it failed later with `unresolvable property or identifier`.
- A well-formed package name that doesn't exist, such as `"com.nope"`, can't be detected and is accepted.
- An imported class name can no longer be used as a fact name. On an engine built with `imports("java.util")`, a fact named `Date`
  is rejected. See [Facts](../facts.md#-naming-rules).
- A single-class import such as `"java.time.LocalDate"` is resolved by `build()`, with the building thread's
  context class loader. A string that loader can't load as a class, but that is a valid package name, is imported as
  a package.
- Classes in imported packages are looked up with the context class loader of the thread that calls `load()`.
  Fact names are checked against that class loader too, on whichever thread calls `run()`.
- A thread without a context class loader uses this library's own class loader instead.

## 🚧 Comparison gotchas

MVEL compares values more loosely than Java, which can make a condition match, or not match, unexpectedly.

| Gotcha | Example | Do this instead |
| --- | --- | --- |
| 🔤 **Enums vs strings** | `order.status == 'SHIPPED'` is always `false` when `status` is an enum, with no error | `order.status.name() == 'SHIPPED'` |
| 🔢 **Type coercion** | `'1' == 1` is `true`. A `BigDecimal` of `1.00` equals `1`. | Compare values of the same type when the difference matters |
| 🔠 **String ordering** | A String fact `"10"` compared as `s > 9` is `true`, but `'10' > '9'` compares text and is `false` | Convert first: `Integer.parseInt(s) > 9` |
| 🕳 **`empty`** | `s == empty` is `true` for `""`, and `n == empty` is `true` for `0` | Use `== ''` or `== 0` when you mean exactly that |
| ❓ **Missing facts** | A fact that isn't in the store throws `unresolvable property or identifier`, so `x == null` can't test for it | `isdef x && x > 1` |
| 🔒 **Facts whose class isn't public** | `applicant.score` on a package-private record fails with `could not access field`, even on the class path | Make the record public, or have it implement a public interface that declares `score()` |

## 🔒 Security

An MVEL rule has the same access to the JVM as your own Java code: it can start processes, read files, open sockets
and use reflection. The engine has no sandbox and no timeout. See [Security](../../README.md#-security).
