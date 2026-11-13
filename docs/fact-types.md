# 🧬 Fact types

How to declare a fact with its type arguments, such as a `List<String>`, and what the engine and a language make of
the declaration.

**Who it's for:** application developers whose rules read lists or maps, and language authors.
**You'll be able to:** declare a `List<String>` fact, know what a run and MVEL's strong typing check, and read a
declared type in a language.
**Before you start:** [Declaring facts](facts.md#-declaring-facts).

[← Documentation index](README.md)

- [Declaring a fact with type arguments](#-declaring-a-fact-with-type-arguments)
- [What a run checks](#-what-a-run-checks)
- [What a language is told](#-what-a-language-is-told)
- [Strong typing in MVEL](#-strong-typing-in-mvel)
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

In Kotlin, a `FactType<List<String>>` records a wildcard; see
[Kotlin](kotlin.md#-declaring-a-fact-with-type-arguments) for the two forms that don't.

A `FactType` needs no entry in a [native image](native-image.md#-registering-your-classes)'s
`reflect-config.json`. Its constructor reads the generic superclass of the anonymous class, and an image answers
that read without metadata. The native smoke shows it: it declares three facts this way, with no entry for any of
the anonymous classes. Its status line reports two of the types from the default image and from the
[strict](native-image.md#-strict-metadata) one; the third is the list its
[strong typing](#-strong-typing-in-mvel) check reads. Under strong typing, your own collection class may need one;
see [Which facts are accepted](#which-facts-are-accepted).

## 🔎 What a run checks

The class, as for any [declared fact](facts.md#-declaring-facts): a `String` for `tags` fails the run with
`Fact 'tags' was declared as java.util.List, but the run supplied a java.lang.String`. A `List<Integer>` passes,
because Java erases a value's type arguments, so the engine can't see what a list holds.

## 🔌 What a language is told

`CompileContext.declaredFacts()` returns a `Map<String, Type>`. Each value is one of two kinds:

| Declared with | The language gets |
| --- | --- |
| A class, `List.class` | That `Class`; a primitive as its wrapper |
| A `FactType` written with type arguments, `FactType<List<String>>`, or a `ParameterizedType` | A `ParameterizedType` whose raw type is a `Class`, such as `java.util.List<java.lang.String>`; never the `FactType` |
| A `FactType` of a type with none: `FactType<String>`, `FactType<int[]>`, a raw `FactType<List>` | That `Class`: `String.class`, `int[].class`, `List.class`; never the `FactType` |

`CompileContext.rawClass(type)` returns the class of either kind, `List` for the first two rows. A language with
no use for type arguments calls it and reads nothing else. MVEL reads the arguments for one thing, its strong
typing.

## 🦺 Strong typing in MVEL

MVEL's [strong typing](languages/mvel.md#-strong-typing) refuses a fact declared as a `Map` or a `Collection`: with
only the class to go on, it can't check what a rule does with an element. Declare the fact with its type arguments
and it's accepted, and a misspelled member of an element fails `load()` with its line and column:

```java
RulesEngineBuilder.firstMatch(LoanDecision::new)
        .outputType(LoanDecision.class)
        .fact("tags", new FactType<List<String>>() { })
        .fact("applicantsById", new FactType<Map<String, Applicant>>() { })
        .requireDeclaredFacts()
        .option("mvel", "strongTyping", "true")
        .build();
```

`.condition("tags[0].length() > 0")` loads. `.condition("tags[0].lenght() > 0")` fails `load()` with
`RuleCompilationException: ... unable to resolve method using strict-mode: java.lang.String.lenght()`.

MVEL checks an element read in one of three ways. In the examples below, `i` and `id` are two more declared
facts, an `Integer` and a `String`, and `n` is a variable the action sets first, with `n = 0;`.

| Read | List | Map |
| --- | --- | --- |
| By index | `tags[0].length()`, `tags[i].length()` | `applicantsById['a1'].creditScore`, `applicantsById[id].creditScore` |
| By property | | `applicantsById.a1.creditScore` |
| By `get` with a literal | `tags.get(0).length()` | `applicantsById.get('a1').creditScore` |

### Which facts are accepted

Both parts of the declared type have to pass:

| Part | Accepted | Still refused |
| --- | --- | --- |
| The collection or map type | One whose own type parameters are `Collection`'s `E`, or `Map`'s `K` and `V`, under those names and in that order: `List`, `Set`, `Collection`, `ArrayList`, `Deque`, `Map`, `HashMap`, `LinkedHashMap`, `SortedMap`, `EnumMap` | A subclass that fixes, renames or reorders them: `Tags extends ArrayList<String>`, `ByName<V> extends HashMap<String, V>`, `Things<T> extends ArrayList<T>`. Also one that reuses such a name for something else: in a supertype, `Top<E> extends Mid<String, E>`, or as a method's own type variable, `<E> E other()` |
| Each type argument, a map's key too | A class with no type parameters that strong typing accepts as a fact, such as `String`, `Integer` or `Applicant`, or an array of one, `String[]`. `? extends X` is read as `X`, so a [Kotlin](kotlin.md#-declaring-a-fact-with-type-arguments) `List<String>` passes | `Object`, `?`, `? super X`, a type variable, a collection, a map, an array of one of those, and any class that has type parameters: `List<List<String>>`, `Map<String, List<String>>`, `List<Optional<String>>` |

MVEL's strict mode carries one level of type arguments, and reads the next level wrongly, so a nested type stays
refused. The message for a refused fact names its whole type, such as
`java.util.List<java.util.List<java.lang.String>>`; a fact declared without type arguments is named by its class,
as before.

In a [strict](native-image.md#-strict-metadata) native image, your own collection class declared this way,
such as a `Strings<E> extends ArrayList<E>`, needs metadata: the mvel module reads its public methods, so without an
entry `load()` fails with `RuleCompilationException: ... The program tried to reflectively access
com.example.Strings.getMethods() without it being registered for runtime reflection`. Add an entry for the class
to your `reflect-config.json`, with `"queryAllPublicMethods": true`.

A JDK type, such as `List`, `Map` or `ArrayList`, needs no entry in either image, and the default image doesn't
throw.

### Rules it still rejects

MVEL 2.5.4 rejects a few working rules over an accepted fact. Each fails `load()`, not a run, and has a rewrite:

| Rule | Why it fails | Write |
| --- | --- | --- |
| `foreach (String k : applicantsById.keySet()) { n += applicantsById[k].creditScore }` | A member of an element, read in a `foreach` or `for` body | A typed local, `foreach (String k : applicantsById.keySet()) { Applicant a = applicantsById[k]; n += a.creditScore }`, or the values, `foreach (Applicant a : applicantsById.values()) { n += a.creditScore }` |
| `for (int j = 0; j < tags.size(); j++) { n += tags[j].length() }` | The same | `foreach (String t : tags) { n += t.length() }` |
| `applicantsById.get(id).creditScore`, `tags.get(i).length()` | `get` with an argument that isn't a literal | `applicantsById[id].creditScore`, `tags[i].length()` |
| `(length() in tags)` | A projection | A `foreach` with a typed variable, as above |
| `tags.iterator().next().length()`, `tags.subList(0, 1)[0].length()` | An element reached through another call | Index the fact first: `tags[0].length()`. For a `Set`, use a typed `foreach` |

> [!WARNING]
> Don't fix a `for (;;)` loop with a typed local. `for (...) { String s = tags[j]; ... }` loads, then fails the
> run on the loop's second pass with `variable already defined within scope`. Use a `foreach`.

A class a rule names, such as `Applicant` above, needs an
[import](languages/mvel.md#-classes-and-imports). The fact's own methods are fine in a loop body, `tags.size()`
or `applicantsById.containsKey(id)`, and so is an element's member in an `if` or `while` body, as long as
the read isn't inside a `foreach` or `for` at any depth.

### A mistake it doesn't catch

A misspelled property of the list or set itself loads. With `tags` a `List<String>`, `tags.frist == 'a'` and
`tags.bogus.length() > 0` pass `load()`, then fail every run with `could not access: frist; in class: ...`, naming
the value's class. MVEL takes any property it doesn't know on a typed collection for an element, as it does a
map's `m.key`. A bean's own `List<String>` property behaves the same way. Methods, such as `tags.size()`, and
element reads are checked.

### What doesn't change

- **A generic bean** is checked by its class, as before. With a `Box<String>` fact, `box.getValue() == 'a'` loads
  and `box.getValue().length() > 0` fails `load()`: MVEL sees an `Object`.
- **A value that breaks the declaration** still fails only when the rule runs; see the last row of
  [Gotchas](#-gotchas).
- **Division of an `Integer` element is integer division**, as for an `Integer` fact: with a `List<Integer>`
  fact, `counts[0] / 2` drops the fraction. See the Division row of
  [Comparison gotchas](languages/mvel-gotchas.md#-comparison-gotchas).

## 🚧 Gotchas

| Gotcha | What happens | Do this instead |
| --- | --- | --- |
| **`new FactType<List<String>>()` without the braces** | It doesn't compile: `FactType` is abstract, because only a subclass keeps its type argument | End it with `{ }` |
| **A `FactType<T>` in a generic method** | `IllegalArgumentException`: `T` is a type variable, which has no class to check a value against | Pass the token in from the caller that knows the type |
| **`List<String>[]`** | `IllegalArgumentException`: an array of a parameterized type has no class literal either | Declare `List[].class`, or a `List<List<String>>` |
| **Casting a declared type to `Class` in a language** | `ClassCastException` once an application declares a fact with type arguments | `CompileContext.rawClass(type)` |
| **Trusting the elements' type** | A `List<String>` fact can hold a list of anything, and a rule may not fail on it. In MVEL, given a list of `Integer`s, a method call on an element fails the run, as `tags[0].length()` does; `tags.contains('1')` and `tags[0] == 'a'` are `false` with no error; and, without strong typing, `tags[0] == '1'` matches the `Integer` 1, by MVEL's [type coercion](languages/mvel-gotchas.md#-comparison-gotchas); with it, that comparison is `false` | Check what you read, or validate the list before the run. Strong typing doesn't help: nothing fails `load()`, because it checks the rule against the declaration, never the value |

Only the type itself is checked when it's declared, not its arguments: `new FactType<List<? extends Number>>() { }`
is accepted, and the language is given the wildcard.

**For language authors:** a type argument can be a wildcard that the application never wrote. Kotlin's
`FactType<List<String>>` arrives as `List<? extends String>`; see
[Kotlin](kotlin.md#-declaring-a-fact-with-type-arguments). To get an element's class, read a `WildcardType`'s upper
bound, `getUpperBounds()[0]`, and pass that to `rawClass`. Don't pass the argument itself: `rawClass` is for a
declared type, and rejects a wildcard or a type variable with a message that blames the fact's type.
