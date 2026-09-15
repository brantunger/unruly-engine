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
> still accepted, and only fails at `run()`: with `unresolvable property or identifier` for a class it calls, such as
> `Objects.isNull(x)`, or `could not resolve class` for one it creates, such as `new ArrayList()`.

- A string that is neither a loadable class nor a valid package name, such as `"java.util."`, is rejected with an
  `IllegalArgumentException`, and nothing from that call is imported.
- A class that exists but can't be loaded, for example because a class it extends is missing from the class path,
  is rejected the same way, with the `LinkageError` as the cause. Before 1.7.0 it was imported as a package, and rules
  that used it failed later with `unresolvable property or identifier`.
- A well-formed package name that doesn't exist, such as `"com.nope"`, can't be detected and is accepted.
- An imported class name can no longer be used as a fact name. After `addImport("java.util")`, a fact named `Date`
  is rejected. See [Facts](../facts.md#-naming-rules).
- A single-class import such as `"java.time.LocalDate"` is resolved when `addImport()` is called, with that thread's
  context class loader. A string that loader can't load as a class, but that is a valid package name, is imported as
  a package.
- Classes in imported packages are looked up with the context class loader of the thread that calls `setRuleList()`.
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

## 🔒 Security

An MVEL rule has the same access to the JVM as your own Java code: it can start processes, read files, open sockets
and use reflection. The engine has no sandbox and no timeout. See [Security](../../README.md#-security).
