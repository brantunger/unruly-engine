# 🧬 Fact types

How to declare a fact with its type arguments, such as a `List<String>`, and what the engine and a language make of
the declaration.

**Who it's for:** application developers whose rules read lists or maps, and language authors.
**You'll be able to:** declare a `List<String>` fact, know what a run checks, and read a declared type in a language.
**Before you start:** [Declaring facts](facts.md#-declaring-facts).

[← Documentation index](README.md)

- [Declaring a fact with type arguments](#-declaring-a-fact-with-type-arguments)
- [What a run checks](#-what-a-run-checks)
- [What a language is told](#-what-a-language-is-told)
- [Gotchas](#-gotchas)

---

## 📣 Declaring a fact with type arguments

A class literal can't say what a list holds: `List.class` is all Java lets you write. Pass a `FactType` instead,
created as an anonymous subclass so that Java keeps the type you wrote:

```java
RulesEngine<LoanDecision> engine = RulesEngineBuilder.firstMatch(LoanDecision::new)
        .fact("applicant", Applicant.class)
        .fact("tags", new FactType<List<String>>() { })
        .fact("applicantsById", new FactType<Map<String, Applicant>>() { })
        .build();
```

A language that checks rules when they load can then check what a rule does with an element of `tags`. A bean
needs none of this: declare it by its class, and its getters keep their own generic return types.

`fact(name, type)` takes any `java.lang.reflect.Type` that is a class or a parameterized type, so a
`ParameterizedType` from another library's type token works too, and `facts(map)` takes a
`Map<String, ? extends Type>`. `fact(name, X.class)` is unchanged.

## 🔎 What a run checks

The class, as for any [declared fact](facts.md#-declaring-facts): a `String` for `tags` fails the run with
`Fact 'tags' was declared as java.util.List, but the run supplied a java.lang.String`. A `List<Integer>` passes,
because Java erases a value's type arguments, so the engine can't see what a list holds.

## 🔌 What a language is told

`CompileContext.declaredFacts()` returns a `Map<String, Type>`. Each value is one of two kinds:

| Declared with | The language gets |
| --- | --- |
| A class, `List.class` | That `Class`; a primitive as its wrapper |
| A `FactType`, or a `ParameterizedType` | A `ParameterizedType` whose raw type is a `Class`, such as `java.util.List<java.lang.String>`; never the `FactType` |

`CompileContext.rawClass(type)` returns the class of either kind, `List` for both rows. A language with no use for
type arguments calls it and reads nothing else, as MVEL does: for
[strong typing](languages/mvel.md#-strong-typing), a `List<String>` fact counts as a `List`.

## 🚧 Gotchas

| Gotcha | What happens | Do this instead |
| --- | --- | --- |
| **`new FactType<List<String>>()` without the braces** | It doesn't compile: `FactType` is abstract, because only a subclass keeps its type argument | End it with `{ }` |
| **A `FactType<T>` in a generic method** | `IllegalArgumentException`: `T` is a type variable, which has no class to check a value against | Pass the token in from the caller that knows the type |
| **`List<String>[]`** | `IllegalArgumentException`: an array of a parameterized type has no class literal either | Declare `List[].class`, or a `List<List<String>>` |
| **Casting a declared type to `Class` in a language** | `ClassCastException` once an application declares a fact with type arguments | `CompileContext.rawClass(type)` |
| **Trusting the elements' type** | A `List<String>` fact can hold a list of anything; a rule that reads an element as a `String` fails when it runs | Check what you read, or validate the list before the run |

Only the type itself is checked when it's declared, not its arguments: `new FactType<List<? extends Number>>() { }`
is accepted, and the language is given the wildcard.
