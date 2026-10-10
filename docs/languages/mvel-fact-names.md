# 🔤 MVEL fact names

Which fact names MVEL rejects, which facts it checks at all, and where that check is only a best effort.

**Who it's for:** rule authors, and application developers who name the facts MVEL rules read.
**You'll be able to:** pick fact names MVEL rules can read, tell why a name was rejected, and know which names MVEL
leaves unchecked.
**Before you start:** [MVEL](mvel.md) and [Naming rules](../facts.md#-naming-rules).

[← Documentation index](../README.md)

- [How MVEL checks a fact's name](#-how-mvel-checks-a-facts-name)
- [Fact names MVEL rejects](#-fact-names-mvel-rejects)
- [Which fact names MVEL checks](#-which-fact-names-mvel-checks)
- [The name output](#-the-name-output)

---

## 🧭 How MVEL checks a fact's name

A run checks each fact's name against the rule list it runs, after the engine's own checks in
[Naming rules](../facts.md#-naming-rules). MVEL takes part only for a rule list with an MVEL rule, or an empty one
while MVEL is the [default language](../glossary.md#default-language). Then, in this order:

1. **`output` is rejected**, whatever the rules read: it's the output object's name. See
   [The name output](#-the-name-output).
2. **MVEL checks the names its rules' text holds.** A fact whose name the text of the list's MVEL conditions and
   actions holds, as a name or in a word, must be one MVEL can read as that fact; see
   [Fact names MVEL rejects](#-fact-names-mvel-rejects). Any other fact isn't checked by MVEL; see
   [Which fact names MVEL checks](#-which-fact-names-mvel-checks). A word too long to scan makes MVEL check every
   fact ([When MVEL checks every fact](#when-mvel-checks-every-fact)).

```mermaid
flowchart TD
    A["A fact of the run"] -- "checked" --> B{"An MVEL rule in the list, or an empty list with MVEL the default?"}
    B -- "no" --> C["MVEL doesn't check it"]
    B -- "yes" --> D{"Named output?"}
    D -- "yes" --> E["run() throws IllegalArgumentException"]
    D -- "no" --> S{"Was every word of the MVEL rules' text short enough to scan?"}
    S -- "yes" --> F{"Does the MVEL rules' text hold the name?"}
    S -- "no" --> G
    F -- "no" --> C
    F -- "yes" --> G{"A name MVEL can read as that fact?"}
    G -- "yes" --> H["Accepted"]
    G -- "no" --> E
    class A step
    class B,D,S,F,G decision
    class C,H ok
    class E fail
    classDef step     fill:#e0e7ff,stroke:#6366f1,color:#1e1b4b
    classDef decision fill:#fef3c7,stroke:#d97706,color:#451a03
    classDef ok       fill:#d1fae5,stroke:#059669,color:#064e3b
    classDef fail     fill:#ffe4e6,stroke:#e11d48,color:#4c0519
```

A rejected fact fails `run()` with `IllegalArgumentException` before any condition runs. A
[declared fact](../facts.md#-declaring-facts) is checked the same way when a rule list is compiled: `load()` throws a
`RuleCompilationException` for it, and `validate()` returns one. A blank name fails `fact()`.

When a rule of the list fails to compile, MVEL can't say what its rules read, so `load()` and `validate()` check
every declared fact's name with it, whatever the text holds: their failures can then include one such as
`Declared fact 'empty' can't be used`.

> [!NOTE]
> Up to 2.28.0, MVEL checked every fact's name, whatever its rules' text held, and reserved `output` for every rule
> list, so a declared `output` failed `build()`. From 2.29.0 to 2.29.4, its scan missed some names MVEL reads whole,
> so a run accepted a fact it now rejects, such as `my-fact` with `my-fact-1 == 0`, or `a.b` with `a.b--`. From 2.29.5
> to 2.29.7, it also rejected some names no rule can read, such as `&!false` with `true&&!false`, which it now accepts.

## 🚫 Fact names MVEL rejects

These rules apply to a fact whose name the rule list's MVEL text holds. `run()` throws `IllegalArgumentException` for
such a fact whose name MVEL can't read as that fact. A declared one fails `load()` instead, with a
`RuleCompilationException` (`Declared fact 'Math' can't be used: ...`).

A name must be a Java identifier. `my-fact` would read as `my - fact`, so it is rejected with the message below. So is
`2nd` where a rule's text holds it, such as in a comment: code that reads it, such as `2nd == 1`, fails `load()` with
`invalid number literal: 2nd`.

```text
'my-fact' is not a valid fact name: rules can only refer to a fact named with a Java identifier
```

MVEL reads these as something else before the facts, so they're rejected too:

| Kind | Names |
| --- | --- |
| Literals | `true` `false` `null` `nil` `empty` |
| Primitive type names | `boolean` `byte` `char` `double` `float` `int` `long` `short` |
| Built-in class names | The 22 in [Classes and imports](mvel.md#-classes-and-imports), such as `Math`, `String` and `Thread` |
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

A root that comes from an import, and so isn't written in the rule, is rejected only where a rule's text also
holds the name, even in a comment. That's `acme` for `Order` with `imports("acme.orders")`, or `java` for `Map.Entry`
with `imports("java.util.Map")`. Where no rule's text holds `acme`, a fact `acme` is accepted, and the rules still
read the class.

A field or method of a singly imported or built-in class, such as `Math.PI`, adds nothing, nor does an unused
import. The message says why, even for a class name:

```text
'java' cannot be used as a fact name: the rules use a class whose package starts with 'java', and MVEL would read the fact in the class's place
```

## 🔍 Which fact names MVEL checks

As `load()` or `validate()` compiles each condition and action, MVEL scans its text for the names a rule could read a
fact by. Strings and comments are scanned too. The names are:

- each identifier, such as `applicant` and `creditScore` in `applicant.creditScore`;
- each piece and each span of a word, as below;
- each backslash alone, and the first half alone of each character Java stores as two `char`s, as MVEL can read
  either as a name.

A **word** is a run of text between MVEL's whitespace, which is every character up to U+0020: a space or a line break
ends a word, and a no-break space doesn't. A character is **part of an identifier** if it's a letter, a digit, `_`, `$`,
or another character Java allows in an identifier.

The **pieces** of a word are the ones 2.29.4 found: the whole word, and its parts between any of
`( ) [ ] { } , ; ' " = < > ! & | ? :`, split once with the comma, once without it, and once without it but with
`* / + %`; then each piece's parts between dots. So `a.b('s') == 1` gives `a.b('s')`, and `(,a) == 5` gives `(,a)`.

A span of a word starts at any of these places:

- the word's start;
- just after one of `( ) [ ] { } , ; ' " = < > ! & | ? : * / + % - .`;
- just after an `isdef` that starts the word or follows one of those characters, when the next character isn't part
  of an identifier, as in `isdef#a`.

From each start, a span ends at the word's end, and another just before each later character that isn't part of an
identifier. `my-fact-1` gives `my`, `fact`, `1`, `my-fact`, `fact-1` and `my-fact-1`. A fact `my-fact` is then
checked, and rejected, though MVEL reads `my-fact-1` as `my` minus `fact` minus 1. In the same way, `a.b--` gives
`a.b`, and `isdef#a` gives `#a`.

A span that holds one of `( ) [ ] { } , ; ' " = < > ! & | ? : * / + %` after its first character is left out, unless
MVEL may still read it whole:

- it stands before `++`, `--` or an assignment operator not followed by `=`, with only MVEL's whitespace between:
  `my]--` gives `my]`;
- `isdef` reads it, glued to `isdef` or as the next word.

So `(,a) == 5` gives `,a`, and the piece `(,a)`, but not `(,a` or `,a)`.

MVEL then checks only the facts named in that set. So, unless
[MVEL checks every fact](#when-mvel-checks-every-fact):

- another language's rules in the same list can read a fact `in`, when no MVEL rule's text holds `in`;
- a name with a space or a line break, such as `first name`, is never rejected by MVEL, as no piece, span or
  identifier holds one;
- an empty rule list, with MVEL as the default language, checks no name against MVEL's rules; `output` is still
  rejected.

### When MVEL checks every fact

In a word with many minus signs, dots, `++`, `--` or assignments, or with several `isdef`s, the spans can grow as the
square of the characters that aren't part of an identifier. So the scan gives up on any word of more than 1,000
characters, or with more than 64 such characters, each one just after `isdef` counted twice: the most characters and
dot-separated parts an import may have. A word at both limits gives up to 2,145 names, scanned in about 0.4 ms.

Once one condition or action gives up, MVEL can't tell which facts the rule list reads, so it checks every fact, as
it did up to 2.28.0: each run's facts and each declared fact. A fact such as `in` or `Math`, which no MVEL rule names,
is then rejected for that rule list. Such a word is most often a long string literal without spaces, such as Base64
data. To keep the narrower check, pass that value as a fact instead.

`load()` and `validate()` log this once each time they compile such a rule list, at DEBUG on the engine's logger
`io.github.brantunger.unruly.engine`, naming the first such condition or action and its rule:

```text
MVEL checks every fact for this rule list: the condition of rule 'm' has a word of more than 1000 characters, too long to scan for the names it reads
```

For the other limit, the line says
`a word with more than 64 characters that aren't part of an identifier, each one just after isdef counted twice`. See
[Logging setup](../listeners-and-logging.md#-logging-setup).

### A best effort both ways

The scan finds extra names, such as the words of a comment, and spans MVEL reads as several names, such as `my-fact`
in `my-fact-1`. That's harmless: a fact by such a name is checked, as every fact was up to 2.28.0. It leaves out the
spans MVEL can't read as one name, such as `&!false` in `true&&!false`, so a fact by such a name is accepted, as from
2.29.0 to 2.29.4.

It can miss a name with MVEL's whitespace inside it, as no word holds one. A line break is such a character, and
`isdef` reads a name up to a comment, even one on a later line. The condition `isdef ,a//c`, a line break, then
`/**/` reads a fact named by that whole text, from `,a` to `/**/`. Unless
[MVEL checks every fact](#when-mvel-checks-every-fact), a fact by such a name isn't checked, so the rule reads it.

> [!WARNING]
> This departs from the [`factNamesRead()` contract](custom.md#-fact-names), which asks a language for more names,
> never fewer. Don't rely on MVEL to reject a fact whose name holds a line break or another character up to U+0020.

## 📤 The name output

MVEL's actions see the output object as `output`, so MVEL reserves the name, but only for a rule list with an MVEL
rule, or an empty one while MVEL is the default language. For such a list:

- a run rejects a fact `output`, whatever the rules read, with
  `'output' is reserved for the output object and cannot be used as a fact name`;
- `load()` throws, and `validate()` returns, a `RuleCompilationException` for a declared `output`, with
  `'output' is reserved for the output object and cannot be declared as a fact`. `build()` accepts the declaration.

A rule list with no MVEL rule, such as one of only another language's rules, accepts a fact `output`, unless another
of the engine's languages reserves it; see [Fact names](custom.md#-fact-names). `Output` and `OUTPUT` are ordinary
names.
