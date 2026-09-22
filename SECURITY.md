# 🔒 Security Policy

## Supported versions

Only the **latest release** on [Maven Central](https://central.sonatype.com/artifact/io.github.brantunger/unruly-engine)
receives security fixes. Fixes ship as a new patch release, so upgrading to the newest version is always the
remedy.

**The 1.x line ends at 2.0.0.** 1.8.0 is the last 1.x release, and once 2.0.0 is out it receives no security fixes.
2.0.0 requires Java 21, so an application on Java 17 to 20 should plan that upgrade rather than stay on 1.x; see
[Migrating from 1.x to 2.0](docs/migrating-to-2.md).

Every jar on Maven Central is signed with the project's GPG key, and from 2.0.0 each also carries a GitHub build
provenance attestation. [RELEASING.md](RELEASING.md#-checking-a-release-by-hand) shows how to check a downloaded
jar against it. From the release after 2.2.0, each GitHub Release also carries a CycloneDX SBOM of each module,
covered by the same attestation; see [The SBOMs](RELEASING.md#the-sboms).

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

Rules are **code**, not data. A condition and an action have whatever access their language gives them; in MVEL,
the same access to the JVM as your own Java code. Either way the engine deliberately provides no sandbox. A
run's timeout stops it only between rules or when an expression returns, so it can't stop an MVEL rule that never
returns. What a rule in another expression language can reach depends on that language. The README's
[Security](README.md#-security) section explains how to deploy it safely.

### In scope

- A way for the **value of a fact** to be executed as code, or to change which rule logic runs.
- A bypass of a documented guarantee, such as a condition assignment that `load()` should reject, or an
  action's local variables leaking into another rule, where that leads to a security impact.
- A vulnerability in a dependency (MVEL, SLF4J) that is reachable through the engine's API.

### Out of scope

- Anything a rule can do because rules run with full JVM access: running processes, reading files, reflection,
  `System.exit()`, and so on.
- What an expression language you add with the builder's `language(...)` lets its rules do. Report that to the
  language's maintainers.
- Denial of service caused by a rule, such as an infinite loop or excessive memory use.
- Applications that build rules from untrusted input. This is documented as unsafe.
