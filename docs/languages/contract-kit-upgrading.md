# 🔼 Upgrading the contract test kit

What changed in the contract kit's checks from one version to the next, and what a new failure means.

**Who it's for:** language authors.
**You'll be able to:** tell why a language that passed an older kit fails a newer one, and what to fix.
**Before you start:** [The contract test kit](contract-kit.md).

[← Documentation index](../README.md)

---

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

## 🔼 Upgrading from 2.8.8

The kit after 2.8.8 has two more checks, both on `Session.close()` and neither skippable, so a language that passed
the 2.8.8 kit may now fail. Each new failure is a real defect:

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
