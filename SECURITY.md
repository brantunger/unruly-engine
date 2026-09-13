# 🔒 Security Policy

## Supported versions

Only the **latest release** on [Maven Central](https://central.sonatype.com/artifact/io.github.brantunger/unruly-engine)
receives security fixes. Fixes ship as a new patch release, so upgrading to the newest version is always the
remedy.

## Reporting a vulnerability

**Please don't report security issues in public GitHub issues, discussions or pull requests.**

**Preferred:** [report a vulnerability](https://github.com/brantunger/unruly-engine/security/advisories/new) through
GitHub's private vulnerability reporting. Only you and the maintainer can see the report, and the fix can be
discussed and prepared in a private advisory.

**Alternatively**, email [brant.unger@gmail.com](mailto:brant.unger@gmail.com) with the subject
`unruly-engine security`.

Either way, include:

- the affected version,
- a description of the issue and its impact,
- a minimal reproduction: the rules, the facts and how the engine was called.

Reports are handled on a best-effort basis by the maintainer. You'll be kept informed as the issue is confirmed
and fixed, and credited in the release notes if you'd like.

## Threat model

Rules are **code**, not data. MVEL gives a rule's condition and action the same access to the JVM as your own
Java code, and the engine deliberately provides no sandbox and no timeout. The README's
[Security](README.md#-security) section explains how to deploy it safely.

### ✅ In scope

- A way for the **value of a fact** to be executed as code, or to change which rule logic runs.
- A bypass of a documented guarantee, such as a condition assignment that `setRuleList()` should reject, or an
  action's local variables leaking into another rule, where that leads to a security impact.
- A vulnerability in a dependency (MVEL, SLF4J) that is reachable through the engine's API.

### ❌ Out of scope

- Anything a rule can do because rules run with full JVM access: running processes, reading files, reflection,
  `System.exit()`, and so on.
- Denial of service caused by a rule, such as an infinite loop or excessive memory use.
- Applications that build rules from untrusted input. This is documented as unsafe.
