## 📝 Summary

<!-- What does this PR change, and why? -->

Closes #

## ⚠️ Behavior changes

<!-- Anything existing users will notice: new exceptions, different results, removed API. Delete this section if there are none. -->

## ✅ Checklist

- [ ] The PR title follows [Conventional Commits](https://github.com/brantunger/unruly-engine/blob/main/CONTRIBUTING.md#-commit-and-pr-titles), e.g. `fix: reject a null rule name`
- [ ] Tests cover the change, and fail without it
- [ ] `./gradlew clean build` passes locally (tests, Checkstyle, PMD, 100% coverage and the API compatibility check)
- [ ] Any intended API break is listed in `config/japicmp/accepted-breaks.txt`, and the title has a `!`
- [ ] The README, `docs/` and Javadoc are updated if behavior or the public API changed
