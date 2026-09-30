# 🔼 Upgrading the contract test kit

What changed in the contract kit's checks from one version to the next, and what a new failure means.

**Who it's for:** language authors.
**You'll be able to:** tell why a language that passed an older kit fails a newer one, and what to fix.
**Before you start:** [The contract test kit](contract-kit.md).

[← Documentation index](../README.md)

---

## 🔼 Upgrading from 2.14

In 2.15.0 five checks got stricter and none was added, so a language that passed the 2.14 kit may now fail. Each new
failure is a real defect:

| Check | Now fails a language that | The defect |
| --- | --- | --- |
| `conditionAssignmentRejected` | Fails a condition that assigns to a fact on its first run only | From the second run on, the condition changes the fact |
| `conditionWritesRejected` | Fails a condition that writes a property or declares a variable on its first run only | From the second run on, the caller's fact changes, or a later expression reads a value no action set |
| `conditionWritesRejected`, once `propertyAssignment()` returns a condition | Rejects a condition's write to a map fact, but calls a bean's setter | The caller's bean changes |
| `outputNotReplaceable` | Fails an action that replaces the output on its first run only | From the second run on, the action replaces the output, and nothing throws |
| `missingPropertyFailsTheRun`, unless `missingFactProperty()` returns `null` | Fails a condition that reads a missing property on its first run only | From the second run on, the property reads as `null`, and the rule silently doesn't fire |
| `compilerClosed`, unless `configure` sets `copiesAtLoad(1)` or more | Has a compiler whose `close()` throws only once `warmUp` has run | The engine only logs it at WARN, and the compiler has usually leaked what it holds |
| `compilerClosed`, unless `configure` sets `copiesAtLoad(1)` or more | Has a `warmUp` that throws only for the compiler a reload makes, while the replaced rules' compiler is still open | A reload fails in any engine that makes copies at load; the check fails with `RuleCompilationException: The 'my' expression language failed to warm up a session: ` + what `warmUp` threw |

The four checks that repeat a run keep one copy of the rules, as `conditionDetail` does. When `load()` succeeds and
the run fails, they run the rule again, which must throw a `RuleExecutionException` naming the rule and its
`CONDITION`, or its `ACTION` in `outputNotReplaceable`.

A second run that doesn't throw fails the check with `... failed the first run but not the second`. Any other
`RuntimeException` fails it with `... failed the second run differently, not with a RuleExecutionException naming
rule r and its condition: ...`, or `action`, carrying what the run threw as the cause.

`compilerClosed` now sets `copiesAtLoad(1)` after `configure`, so the compiler has warmed up a session before it's
closed. The 2.12 row below, "when `configure` sets `copiesAtLoad(1)` or more", no longer applies. A language whose
`newSession()` returns `Session.none()` still has nothing warmed up: the engine calls `warmUp` only for other sessions.

A subclass written for the 2.14 kit still compiles, and no hook was added. `conditionWritesRejected` also runs your
`propertyAssignment()` condition against `ExpressionLanguageContractTest.WritableApplicant`, a new public JavaBean with
`getCreditScore()` and `setCreditScore(int)`, and requires its score unchanged. The condition must also write a bean's
property, as MVEL's `applicant.creditScore = 1` does. If your `configure` declares `applicant`, keep it `Object`.

## 🔼 Upgrading from 2.13

In 2.14.0 `nestedRunInsideACondition`, `nestedRunFailsInsideACondition` and `nestedRunFailsInsideAnAction` were
added, so a language that passed the 2.13 kit may now fail. Each new failure is a real defect:

| Check | Now fails a language that | The defect |
| --- | --- | --- |
| `nestedRunInsideACondition`, once `bothConditions()` returns a condition | Keeps what a condition works on in per-thread state, such as a `ThreadLocal`, and looks it up again after a read that may start a nested run | The condition reads the nested run's facts, and its rule silently doesn't fire |
| `nestedRunFailsInsideACondition`, once `bothConditions()` returns a condition | Puts back a condition's per-thread state when the condition returns, but not in a `finally` | After a nested run fails, the condition that started it reads the failed run's facts |
| `nestedRunFailsInsideAnAction`, once `putFactProperty()` returns an action | Puts back an action's per-thread state when the action returns, but not in a `finally` | After a nested run fails, the action that started it writes to the failed run's output, and nothing throws |
| `nestedRunFailsInsideACondition` and `nestedRunFailsInsideAnAction` | Swallows what a getter threw, or rethrows something that carries it neither as a cause, nor as a suppressed exception, nor by its message | A run that should fail doesn't, or its failure hides what caused it |

A subclass written for the 2.13 kit still compiles. The new hook returns `null` by default, which leaves the two
condition checks off. Override it to turn them on:

- `bothConditions(condition, other)`: a condition that is true when both are, evaluating `condition` first. For
  MVEL, `condition + " && " + other`. If your `configure` declares facts, declare `nest` too; see
  [A language that needs declared facts](contract-kit.md#a-language-that-needs-declared-facts-imports-or-options).

`nestedRunFailsInsideACondition` is also skipped when its nested run neither fails nor reads the `nest.value` that
makes it fail. That happens in a language that evaluates the right side of a condition first, even one that overrides
`bothConditions()`: the nested run stops at `x == 1`, which is false there, so a launcher sees one aborted check.
`nestedRunFailsInsideAnAction`'s nested run always reads it, since its action runs.

Skipped, the two condition checks count as aborted, as `nestedRunInsideAnAction` does, so a launcher that expects
every check found to succeed sees two more aborted checks, three if `putFactProperty()` also returns `null`: override
the hooks, or count aborted checks as passing.

## 🔼 Upgrading from 2.11

In 2.12.0 `actionVariablesStayLocal`, `compilerClosed` and `conditionDetail` got stricter, and
`conditionWritesRejected` and `failedActionVariablesStayLocal` were added, so a language that passed the 2.11 kit may
now fail. Each new failure is a real defect:

| Check | Now fails a language that | The defect |
| --- | --- | --- |
| `actionVariablesStayLocal` | Lets a later run's condition read a variable an earlier run's action declared | A later run's rule fires on a value no rule in that run set |
| `actionVariablesStayLocal`, when `configure` sets `copiesAtLoad(2)` or more | Keeps an action's variable in its session. The check used to miss it: the later run got the other copy, whose session never saw the variable | A later run reads a value that no rule set for it |
| `actionVariablesStayLocal` | Fails the run whose action declares the variable `y` while a fact is also named `y` | An action can't declare a variable with a fact's name, which the check's first part already requires with `x` |
| `compilerClosed`, when `configure` sets `copiesAtLoad(1)` or more | Has a compiler whose `close()` throws only once `warmUp` has run. The check's wrapper used to skip `warmUp` | The engine only logs it at WARN, and the compiler has usually leaked what it holds |
| `conditionWritesRejected`, once `propertyAssignment()` or `conditionDeclaration()` returns a condition | Lets a condition set a property of a fact, or declare a variable | The caller's fact changes, or a later expression in the run reads a value no action set |
| `failedActionVariablesStayLocal`, once `declareVariableThenFail()` returns an action | Clears an action's variables only when the action ends normally, rather than however it ends | The next run on that copy of the rules reads the failed action's variable |
| `conditionDetail`, when `configure` sets `copiesAtLoad(2)` or more | Returns a detail that reads the session when it's printed. The check used to miss it: the other run got the other copy | The run's result reports another run's values |

Why a variable left in a session reaches a later run is in
[Testing a compiler without an engine](beyond-the-contract-kit.md#-testing-a-compiler-without-an-engine).

A subclass written for the 2.11 kit still compiles. Three new hooks return `null` by default, which leaves their parts
off. Override them to turn those parts on:

- `propertyAssignment(fact, property, value)`: a condition that sets the fact's property to the value. For MVEL,
  `fact + "." + property + " = " + value`.
- `conditionDeclaration(name, value)`: a condition that declares a variable holding the value, and is then true. For
  MVEL, `name + " = " + value + "; true"`.
- `declareVariableThenFail(name, value)`: an action that declares a variable holding the value, then fails the run.
  For MVEL, `name + " = " + value + "; Integer.parseInt('not a number')"`.

The action must declare the variable before it fails, or `failedActionVariablesStayLocal` proves nothing.

Two more, `putVariable(key, variable)` and `variableEquals(variable, value)`, default to `putFact` and `factEquals`. If
your variables have a namespace of their own, such as SpEL's `#y`, override them with an action that puts the variable
under the key and a condition that compares it with the value; see [The contract test kit](contract-kit.md).

Skipped, `conditionWritesRejected` and `failedActionVariablesStayLocal` count as aborted, as `nestedRunInsideAnAction`
does, so a launcher that expects every check found to succeed sees two more aborted checks: override the hooks, or
count aborted checks as passing.

When a run's output differs from what the check expected only in a value's type, such as the `Integer` 1 and the
`String` "1", the failure now says so, rather than printing two identical outputs.

## 🔼 Upgrading from 2.10.0

In 2.10.1 `unusableFactNameRejected` got stricter and no check was added, so a subclass that passed the 2.10.0
kit may now fail. The failure is in your subclass's hook, not in your language:

| Check | Now fails a subclass that | The defect |
| --- | --- | --- |
| `unusableFactNameRejected` | Returns a blank name, empty or only whitespace, from `unusableFactName()` | The engine now rejects a blank fact name itself, so the check never reached your `checkFactName`; return a name only your language rejects |

The check fails with `unusableFactName() must return a name the language itself rejects`, as it does for `output`.

## 🔼 Upgrading from 2.9

In 2.10.0 `concurrentRuns` got stricter and `nestedRunInsideAnAction` was added, so a language that passed the 2.9 kit
may now fail. Each new failure is a real defect:

| Check | Now fails a language that | The defect |
| --- | --- | --- |
| `concurrentRuns` | Returns, from its third `newSession()` call on, a session it returned before, when runs overlap enough to need a third | Two runs use one session at once, and the engine closes it twice |
| `concurrentRuns`, once `copyThroughVariable()` returns an action | Keeps an action's variables where every run reaches them, such as in the compiled action or a static. The check is likely, not certain, to catch it: another run must change the variable while the action runs | A run reads another run's value, or fails |
| `nestedRunInsideAnAction`, once `putFactProperty()` returns an action | Keeps a run's state per thread, such as in a `ThreadLocal`, where a run nested inside an expression replaces it | The outer run's value lands in the nested run's output, and nothing throws |

Both hooks return `null` by default, so a subclass written for the 2.9 kit still compiles, and the parts they turn on
stay off. Override them to turn those parts on:

- `copyThroughVariable(key, fact)`: an action that declares a variable holding the fact's value, then puts the
  variable under the key. For MVEL, `"tmp = " + fact + "; output.put('" + key + "', tmp)"`.
- `putFactProperty(key, fact, property)`: an action that puts the fact's property, read through its getter, under the
  key. For MVEL, `"output.put('" + key + "', " + fact + "." + property + ")"`. If your `configure` declares facts,
  declare `nest` too; see
  [A language that needs declared facts](contract-kit.md#a-language-that-needs-declared-facts-imports-or-options).

Skipped, `nestedRunInsideAnAction` counts as aborted, as `usableFactNamesAccepted` does, so a launcher that expects
every check found to succeed sees one more aborted check: override `putFactProperty()`, or count aborted checks as
passing. Like `sessionClosedWhileAnotherRuns`, it also needs a second session on a thread whose first is still in use.

## 🔼 Upgrading from 2.8.9 or earlier

The kit in 2.8.10 has two more checks, both on `Session.close()` and neither skippable, so a language that passed
the 2.8.9 kit may now fail. Each new failure is a real defect:

| Check | Fails a `Session.close()` that | Then |
| --- | --- | --- |
| `sessionClosedWhileAnotherRuns` | Tears down what the compiler's sessions share, or throws at any time during the check | Another session's run fails or gives the wrong output, or the engine only logs the throw at WARN |
| `sessionClosedOnAnotherThread` | Throws when it's called on a thread other than the one that made the session | The engine only logs it at WARN, and what the session holds has usually leaked |

`sessionClosedWhileAnotherRuns` also fails a language that can't make a second session on a thread whose first is
still in use, since the run nested in the check's run needs one.

## 🔼 Upgrading from 2.6

In 2.7.0 five checks got stricter and `usableFactNamesAccepted` was added, so a language that passed the 2.6 kit may
now fail. Each new failure is a real defect, not a kit change to work around:

| Check | Now fails a language that | The defect |
| --- | --- | --- |
| `actionVariablesStayLocal` | Keeps an action's variable in its session | A later rule, or a later run, reads a value that no rule set for it |
| `evaluateAgreesWithDetail` | Compares whole numbers differently in `evaluate` and `evaluateWithDetail`, such as by type in one and by value in the other | A wrapper that calls only `evaluate` matches a `Long`, `Short` or `BigDecimal` fact differently than the engine does |
| `conditionDetail` | Returns a detail that reads the session when it's printed | The run's result reports another run's values; `CompiledCondition` forbids a detail that holds the session |
| `unusableFactNameRejected` | Returns `output` from `unusableFactName()` | The check never reached your `checkFactName`; return a name only your language rejects |
| `compilerClosed` | Has a compiler whose `close()` throws | The engine only logs it at WARN, and the compiler has usually leaked what it holds |

`usableFactNamesAccepted` runs only when `usableFactNames()` returns names, so it can't fail a language that doesn't
override the hook. Skipped, JUnit counts it as aborted, so a launcher that expects every check found to succeed now
sees one more aborted check than with 2.6 (18 found and 17 succeeded with the 2.7 kit, when no other check is
skipped): override `usableFactNames()`, or count aborted checks as passing.
