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

## Submitting a Pull Request

- Ensure your code passes all checks (`./gradlew check`).
- Provide a clear and descriptive PR title and description.
- Link the PR to the relevant issue.

By contributing, you agree that your contributions will be licensed under its GNU General Public License v3.0.
