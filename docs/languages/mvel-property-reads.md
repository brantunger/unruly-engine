# 🔤 Facts and properties in MVEL

Where an MVEL rule reads a property from a value, and why a fact of the same name fails the run there.

**Who it's for:** rule authors, and application developers who name and declare the facts MVEL rules read.
**You'll be able to:** tell where a name in a rule is a property and where it is a fact, read the message a run fails
with, and choose between declaring a fact, moving a read and renaming.
**Before you start:** [MVEL](mvel.md) and [Declaring facts](../facts.md#-declaring-facts).

[← Documentation index](../README.md)

- [A fact can't take the place of a property](#-a-fact-cant-take-the-place-of-a-property)
- [Where the check applies](#-where-the-check-applies)
- [What the run fails with](#-what-the-run-fails-with)
- [What the check costs](#-what-the-check-costs)
- [Declaring every fact](#-declaring-every-fact)
- [Gotchas](#-gotchas)

---

## 🧭 A fact can't take the place of a property

In `applicant.creditScore`, `applicant` is a fact and `creditScore` is a property of its value. MVEL reads the first
name of every chain as a variable before it reads it as a property, and at the start of an expression nothing else
could be meant.

MVEL also starts a new chain in the middle of an expression, against a value. In `(applicant).creditScore` the rule
means the applicant's `creditScore`, but MVEL would look for a fact named `creditScore` first. The engine doesn't let
a fact take the property's place: a run that supplies such a fact fails the rule, and a run that doesn't reads the
property.

```java
engine.load(List.of(Rule.builder().ruleName("prime-rate")
        .condition("(applicant).creditScore >= 750")
        .action("output.setInterestRate(4.5)").build()));

facts.setValue("applicant", new Applicant("Ada", 780));
facts.setValue("creditScore", 300);
engine.run(facts);                     // RuleExecutionException: Fact 'creditScore' can't be read ...
```

Without the fact `creditScore`, the same run gives `interestRate = 4.5`.

`Applicant` and `LoanDecision` are the classes from the [Quick start](../../README.md#-quick-start).

## 🔍 Where the check applies

There are two kinds of place. After a value that isn't a name, no fact is read, declared or not:

| After | Example | The name that can't be a fact's |
| --- | --- | --- |
| A value in parentheses, a cast or a ternary among them | `(applicant).creditScore`, `(a ? b : c).name` | `creditScore`, `name` |
| A projection | `(amount in loans).size()` | `size` |
| An inline list, map or array | `[1, 2].size()`, `['a': 1].a`, `{1, 2}.length` | `size`, `a`, `length` |
| A string literal | `'abc'.length()` | `length` |
| A new object | `new java.util.ArrayList().size()` | `size` |
| A null-safe first name | `?applicant.name`, `(loan).?applicant.name` | `name`; `applicant` and `name` |

After a null-safe first name, a run fails only once an earlier run of the copy found that name `null`. If its
value isn't `null` in the copy's first run, the run reads the property. No fact is read either way.

Inside a block that MVEL evaluates against a value, a name may be meant either way, so a
[declared fact](../glossary.md#declared-fact) is read as the fact, and a fact nobody declared fails the run:

| Inside | Example | The name that must be declared to be a fact |
| --- | --- | --- |
| A projection's element | `(amount * rate in loans)` | `amount`, `rate` |
| A `with` block | `with (output) { interestRate = baseRate }` | `baseRate` |
| An inline `with` block | `output.{ interestRate = baseRate }` | `baseRate` |
| A function called from a `with` block | `def rate() { baseRate }; with (output) { interestRate = rate() }` | `baseRate` |

Everything else reads facts as before. That includes a chain that starts at a fact, however long
(`applicant.address.city`, `loans[0].amount`, `applicant.getAddress().city`), a projection's collection and its `if`
filter (`loans` and `least` in `($.amount in loans if $.amount > least)`), a `with` block's target, an argument of a
method call outside those blocks, and the body of a `foreach`, an `if` or a function called from outside them.

A `with` block's target is read as the fact inside the block too, where a statement calls the target's method:
with a list fact `notes`, `with (notes) { add('prime') }` runs with `notes` undeclared. An argument of that call is
inside the block: in `with (notes) { add(label) }`, `label` must be declared to be a fact.

The output object is never a fact, and neither is a variable an action assigns, unless a run supplies a fact of
that name. Both are read everywhere.

## 🚨 What the run fails with

The rule fails as it does for any exception: `run()` throws a `RuleExecutionException` naming the rule, with an
`IllegalArgumentException` as its cause, unless the engine's [error handling](../error-handling.md) says otherwise.
After a value that isn't a name:

```text
Failed to evaluate condition for rule 'prime-rate': Fact 'creditScore' can't be read in place of a property: MVEL reads a name after a value in parentheses, a literal, a new object or a null-safe name as the fact of that name, not as the value's property
```

Inside a block:

```text
Failed to execute action for rule 'prime-rate': Fact 'baseRate' can't be read in place of a property: in a with block or a projection's element MVEL reads a name as the fact of that name, not as the value's property, and this fact isn't declared. Declare the fact, or read it outside the block
```

MVEL decides how to read a name the first time a [compiled copy](../glossary.md#compiled-copy) runs the expression,
so the check is made then, and a later run that reads the same way isn't checked again. A run with a fact the engine
refuses is then either refused or reads the property; it never reads the fact.

The same decision holds for a declared fact inside a block. If a copy's first run of the rule left the fact out,
that copy reads the property from then on, in the runs that supply the fact too. So supply a declared fact that a
block reads on every run; with `requireDeclaredFacts()` a run that leaves it out fails.

To make a rule run:

- **declare the fact** with `fact(name, type)`, if the rule reads it inside a block;
- **read the fact outside the block**, into a variable: `r = baseRate; with (output) { interestRate = r }`;
- **rename** the fact, or have the run leave it out, if the rule means the property.

The engine tells these places apart as MVEL runs the rule, in a [native image](../native-image.md) too: the
`propertyReads=refused,refused,read,read` of the image's smoke test says a fact named like a property failed the rule
after a value in parentheses and inside a `with` block, and a projection's collection and a `with` block's own target
were read as facts.

## 🐢 What the check costs

The engine looks at where a name stands only when MVEL asks whether the name is a fact, and only for a name a run
supplied. For most rules MVEL asks while it first builds what reads an expression, so a
[compiled copy](../glossary.md#compiled-copy)'s first run of a rule takes some tens of microseconds longer, and its
later runs take no longer than before.

Three forms make MVEL ask on every read, and each question then costs about 1.5 to 3.5 microseconds:

- `isdef` of a fact;
- a fact read inside a `def` function, where MVEL asks twice, so about 5 microseconds a read;
- any fact read in a condition or action with an `import` of its own.

In a loop that adds up with the elements. A `foreach` over 1,000 elements that calls a function reading one fact
went from 86 microseconds to 5.3 milliseconds a run in one measurement.

## 📣 Declaring every fact

`requireDeclaredFacts()` is the stronger option. A run that supplies a fact nobody declared is then rejected before
any rule runs, so the only facts a rule can meet are the ones the application chose, and inside a block each of them
is read as the fact. After a value that isn't a name, a declared fact still fails the run. See
[Declaring facts](../facts.md#-declaring-facts).

## 🚧 Gotchas

| Gotcha | What happens | Do this instead |
| --- | --- | --- |
| **A compound assignment in a block** | `with (order) { count += 1 }` and `order.{ count += 1 }` fail with `Fact 'order' can't be read in place of a property` when the fact `order` isn't declared: MVEL reads the block's target again inside the block. So does a statement of a nested block, `order.{ self.{ reset() } }` | Declare the fact; for the compound assignment, `count = count + 1` works too |
| **A function called from a projection's element** | `def f() { rate }; (f() in loans)` fails for an undeclared `rate`, though MVEL reads the function's body at the start of the expression | Declare the fact |
| **A declared fact named like a property, inside a block** | `with (output) { interestRate = rate }` reads the declared fact `rate`, not the output's `rate` | Write `output.rate`, or rename the fact |
