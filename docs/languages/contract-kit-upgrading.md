# 🔼 Upgrading the contract test kit

What changed in the contract kit's checks from one version to the next, and what a new failure means.

**Who it's for:** language authors.
**You'll be able to:** tell why a language that passed an older kit fails a newer one, and what to fix.
**Before you start:** [The contract test kit](contract-kit.md).

[← Documentation index](../README.md)

---

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
