# 🗂️ Facts

Facts are the inputs to a run. Each fact has a **name**, which rules use as a variable, and a **value**, which is
any Java object.

[← Back to README](../README.md)

- [The fact types](#-the-fact-types)
- [Adding facts](#-adding-facts)
- [Naming rules](#-naming-rules)
- [Null and missing facts](#-null-and-missing-facts)
- [Generics](#-generics)
- [Copying and sharing](#-copying-and-sharing)

---

## 🧱 The fact types

```mermaid
classDiagram
    direction LR
    class FactReference~T~ {
        <<interface>>
        getName() String
        getValue() T
    }
    class Fact~T~
    class FactStore~T~ {
        <<interface>>
        getValue(name) T
        setValue(name, value)
        put(FactReference) FactReference
        asMap() Map
    }
    class FactMap~T~
    class Map~String, FactReference~ {
        <<interface>>
    }
    FactReference <|.. Fact
    FactStore <|.. FactMap
    Map <|.. FactMap
    FactStore o-- FactReference : holds
```

| Type | Kind | Role |
| --- | --- | --- |
| `FactReference<T>` | interface | A named value |
| `Fact<T>` | final class | The built-in `FactReference`. Its name and value can't change. |
| `FactStore<T>` | interface | Facts keyed by name: `getValue`, `setValue`, `put`, and a read-only `asMap()` view |
| `FactMap<T>` | class | The built-in `FactStore`, backed by a `HashMap`. It's also a `Map<String, FactReference<T>>`. |

The engine reads the store through `asMap()`: each **key** is a variable name, bound to its fact's **value**.

## ➕ Adding facts

```java
FactStore<Object> facts = new FactMap<>();

// The simplest way: a name and a value
facts.setValue("applicant", new Applicant("Ada", 780));

// Or add a Fact object
facts.put(new Fact<>("order", Map.of("id", 42)));

// Read a value back
Applicant applicant = (Applicant) facts.getValue("applicant");
```

`FactMap` also has constructors that take `Fact` objects (`new FactMap<>(fact1, fact2)`) or copy another map
(`new FactMap<>(otherMap)`).

A `FactStore` isn't a `Map`. To read every fact, use `facts.asMap()`, a read-only view that follows later changes. A
variable declared as `FactMap` has the `Map` methods too, such as `remove` and `clear`.

Any readable property works in a rule. `applicant.creditScore` calls a JavaBean getter (`getCreditScore()`), a
record accessor (`creditScore()`) or looks up a `Map` key.

## 🏷 Naming rules

A rule can only refer to a fact whose name reads as a single variable in its expression language. The table shows
MVEL's rules. `run()` checks each fact against every language the loaded rules use, or against the engine's
default language when the rule list is empty, and another language decides which names it rejects.

| ✅ Allowed | ❌ Rejected | Why |
| --- | --- | --- |
| `applicant`, `order2`, `_ctx` | `my-fact`, `2nd`, `first name` | Not a Java identifier (`my-fact` would read as `my - fact`) |
| `claim` | `empty`, `null`, `true`, `nil`, `in`, `is`, `if`, `def`, `new`, `with`, `contains`, `foreach`, `isdef`, `this`, ... | A reserved MVEL word |
| `math` | `Math`, `String`, `System`, `Integer`, ... | A class MVEL always resolves |
| `date` | `Date` on an engine built with `imports("java.util")` | A class from an import |
| `result` | `output` | Reserved for the output object |

The checks happen in two places, and both throw `IllegalArgumentException`:

- **`FactMap`** rejects a `null` name, a key that differs from the fact's own name
  (`put("claim", new Fact<>("other", 1))`), and two facts with the same name in its constructor.
- **`run()`** rejects any name in the table above, whichever `FactStore` implementation you use.

A `Fact` needs a name: `new Fact<>(null, value)` throws `NullPointerException`.

## 🕳 Null and missing facts

| Situation | In a rule |
| --- | --- |
| A fact whose value is `null` | The variable is `null`, so `coapplicant == null` is `true` |
| No fact with that name in the store | Referring to it throws `unresolvable property or identifier` |

To check whether a fact was supplied at all, use `isdef`. It is also `true` for a fact whose value is `null`, so
check for `null` too before reading a property:

```java
.condition("isdef coapplicant && coapplicant != null && coapplicant.creditScore >= 700")
```

## 🔣 Generics

`run()` accepts a `FactStore` of any type, so a `FactMap<Applicant>` works as well as a `FactStore<Object>`.

## 📋 Copying and sharing

- `setValue` always stores a **new** `Fact`. A `FactMap` copied from another (`new FactMap<>(other)`) can
  therefore be changed with `setValue` without affecting the original.
- The copy is **shallow**. `Fact` objects added with `put` and the values themselves are shared. A `Fact` can't
  change, so sharing one is safe, but a value such as an `Applicant` is the same object in both maps.
- The engine doesn't copy fact values either. If an action calls a method that changes a fact, such as
  `applicant.setCreditScore(0)`, rules that fire later in the same run see the change.

> [!IMPORTANT]
> Build a fresh `FactStore` for each request and don't share one between threads. The engine itself is safe to
> share; see [Thread safety](thread-safety.md).
