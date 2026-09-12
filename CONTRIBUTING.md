# Contributing to Unruly Engine

First off, thank you for considering contributing to Unruly! It's people like you that make the engine such a great tool.

## Where do I go from here?

If you've noticed a bug or have a question, [search the issue tracker](https://github.com/brantunger/unruly-engine/issues) to see if someone else in the community has already created a ticket. If not, go ahead and [make one](https://github.com/brantunger/unruly-engine/issues/new)!

## Fork & create a branch

If this is something you think you can fix, then [fork Unruly](https://github.com/brantunger/unruly-engine/fork) and create a branch with a descriptive name.

## Implementing your change

When adding a feature or fixing a bug, please ensure you:
1. Write tests to cover your changes.
2. Ensure you haven't broken existing functionality.
3. Keep the test coverage at 100%. Our CI pipeline enforcing this will block merges if coverage drops.

### Building Locally

We use Gradle. To build and run tests:

```bash
# Build the project
./gradlew clean build

# Run static analysis (Checkstyle & PMD) and tests
./gradlew check
```

## Commit and PR titles

PRs are squash-merged using **the PR title as the commit message**, and that message is
the only input to our release automation. Titles must follow
[Conventional Commits](https://www.conventionalcommits.org/):

```
<type>: <lowercase description with no trailing period>
```

The type decides the next version:

| Title | Effect on `1.2.3` |
| --- | --- |
| `fix: guard against a null rule condition` | `1.2.4` — patch |
| `feat: add RuleListener hooks` | `1.3.0` — minor |
| `feat!: remove the Factory interface` | `2.0.0` — major |
| `deps: bump mvel2 to 2.5.4` | no release; listed under Dependencies |
| `docs: clarify stateless semantics` | no release |

Other accepted types: `perf`, `refactor`, `test`, `build`, `ci`, `chore`, `revert`.
A CI check enforces the format, so a malformed title blocks the merge rather than
silently skipping a release.

## Submitting a Pull Request

- Ensure your code passes all checks (`./gradlew check`).
- Give the PR a Conventional Commit title (see above) and a descriptive body.
- Link the PR to the relevant issue.

Once merged, you do nothing further — see [RELEASING.md](RELEASING.md) for how the
version bump, changelog, tag, GitHub Release and Maven Central publish happen.

By contributing, you agree that your contributions will be licensed under its GNU General Public License v3.0.
