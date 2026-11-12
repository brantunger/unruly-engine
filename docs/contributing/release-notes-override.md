# 📰 The release-notes override block

How to write the block in a pull request's body that gives the changelog one entry for each change the PR holds, and
how to check and repair the result.

**Who it's for:** the maintainer, and anyone who writes a PR body.
**You'll be able to:** write an override block that keeps every entry and every breaking-change text, avoid the
shapes that lose them without an error, and repair a changelog before its release is cut.
**Before you start:** [RELEASING.md](../../RELEASING.md#-the-normal-flow), for the release flow the block feeds.

[← Documentation index](../README.md)

- [When the block is needed](#-when-the-block-is-needed)
- [Writing the block](#-writing-the-block)
- [The rules](#-the-rules)
- [Gotchas](#-gotchas)
- [Checking the release PR](#-checking-the-release-pr)
- [When the changelog is wrong](#-when-the-changelog-is-wrong)
- [Questions you might not think to ask](#-questions-you-might-not-think-to-ask)

---

## 🧭 When the block is needed

The repository squash-merges a PR with its title alone, so a PR gives release-please one commit message, and the
changelog one entry. [Commit and PR titles](../../CONTRIBUTING.md#-commit-and-pr-titles) covers the title.

A PR that holds several releasable changes needs an entry for each. The PR from `develop` to `main` for a major
version is the usual case. Its body carries an **override block**: release-please reads the block in place of the
commit message, and each entry in the block becomes a changelog entry.

A merged PR with one change can take a block too, to reword its entry before the release is cut.

Most PRs need no block. One rule still applies to them: never write the block's start marker in the body. See
[Gotchas](#-gotchas).

## 📐 Writing the block

The block goes anywhere in the PR body, after any prose. This one holds two breaking changes and a fix:

```text
BEGIN_COMMIT_OVERRIDE
BEGIN_NESTED_COMMIT
feat!: fact types are a Type, so a language sees a List<String> element type (#2)

BREAKING CHANGE: `declaredFacts()` returns `Map<String, Type>`.
END_NESTED_COMMIT
BEGIN_NESTED_COMMIT
feat!: the api.exception package is gone (#1)

BREAKING CHANGE: change the imports.
END_NESTED_COMMIT
BEGIN_NESTED_COMMIT
fix: read Map keys as order['size'] (#3)
END_NESTED_COMMIT
END_COMMIT_OVERRIDE
```

Each entry is what the PR title of that change would be, in the
[Conventional Commits](https://www.conventionalcommits.org/) form, with a `BREAKING CHANGE:` footer when it breaks
something. From 2.35.4, release-please makes this 3.0.0 and writes the Markdown below, shown without its links.
GitHub shows `&lt;String&gt;` as `<String>`.

```text
### ⚠ BREAKING CHANGES

* `declaredFacts()` returns `Map<String, Type>`.
* change the imports.

### Features

* fact types are a Type, so a language sees a List&lt;String&gt; element type (#2)
* the api.exception package is gone (#1)

### Bug Fixes

* read Map keys as order['size'] (#3)
```

## 📏 The rules

| Rule | Why |
| --- | --- |
| Wrap every entry in `BEGIN_NESTED_COMMIT` and `END_NESTED_COMMIT` | A blank line starts a new entry only before a type with no `!`, so a `feat!:` entry runs into the one before it |
| Give a breaking entry exactly one `BREAKING CHANGE:` footer, after a blank line | An entry keeps one breaking-change text: a second footer replaces the first, and with no footer the subject is used |
| In a `BREAKING CHANGE:` footer, put a type written with `<...>` in backticks | The footer's text reaches the notes as written, and GitHub drops what looks like an HTML tag, such as the `<String>` of `List<String>` |
| Leave the entry's subject as it is, with no escapes for `<...>` | release-please escapes the subject itself, outside backticks |
| Write `BEGIN_COMMIT_OVERRIDE` in a PR body only as the block's first line | The first one in the body starts the block, wherever it is |
| Write each entry as a plain line, not as a bullet | A line that starts with `- ` isn't a Conventional Commits message |
| Close the block with `END_COMMIT_OVERRIDE` | Without it, the rest of the body is read as part of the block |
| Put a `Release-As: X.Y.Z` footer only on an entry, inside its nested markers and after a blank line | It's ignored on a line of its own after the last entry, and anywhere outside the block |

A `!` entry already makes the next version a major one, so a major release needs no `Release-As:` footer.

## 🚧 Gotchas

> [!WARNING]
> release-please reports none of these. It logs a message it can't read at debug level only, and leaves the commit
> out of the changelog.

| Gotcha | What happens | Do this instead |
| --- | --- | --- |
| **The start marker in prose** | A sentence that names `BEGIN_COMMIT_OVERRIDE` before the block, or in a PR with no block, starts the block there. The rest of the sentence isn't a commit message, so the PR's commit is usually dropped: a `fix:` PR gets no changelog entry and, alone, opens no release PR. If only white space follows the marker before the real block, the block is ignored and the title is used | Call it "the override block" in prose, in every PR. Naming the end marker or the nested markers is harmless |
| **Entries as a bullet list** | No entry is read, and the commit is dropped | Plain lines, each in the nested markers |
| **No end marker** | Text after the block is read as part of it. Of three one-line entries followed by a checklist, one was kept | Close the block |
| **Breaking entries separated by blank lines** | The entries run together. Of two entries with a footer each, the first lost its text, took the second's, and the second showed its own subject | Wrap each entry in the nested markers |
| **Two `BREAKING CHANGE:` footers on one entry** | Only the last is kept | One entry for each breaking change |
| **A `<...>` type outside backticks in a footer** | GitHub drops what it reads as an HTML tag: the notes show `List` for `List<String>` | Backticks in the footer |

These were run on release-please 17.6.0, the version the release workflow's pinned `release-please-action` v5.0.0
bundles. Line ends don't matter: a body with CRLF line ends gives the same notes.

## 🔎 Checking the release PR

After the merge, read the release PR that release-please opens or updates, `chore(main): release X.Y.Z`:

1. The version is the one you expect.
2. Every `feat` and `fix` entry of the block is listed, under Features or Bug Fixes. Entries of the other types are
   hidden, as their PR titles are.
3. Every breaking entry is also under BREAKING CHANGES, with its own text.
4. A type written with `<...>` shows in full.

If an entry is missing or wrong, or no release PR opened, go to the next section before you merge the release PR.

## 🧯 When the changelog is wrong

release-please reads the merged PR's body again on every run, so correct the body and run the workflow:

```bash
gh pr edit <number> --body-file body.md
gh workflow run release.yml --ref main
```

`<number>` is the merged PR that holds the block, not the release PR. The run brings the release PR up to date, or
opens one. `--ref main` makes the branch explicit: the `release-please` job runs only on `main`.

> [!IMPORTANT]
> This works only until the release PR is merged. After that, edit `CHANGELOG.md` and the GitHub Release's notes by
> hand.

This step hasn't been run end to end in this repository yet. It rests on how release-please 17.6.0 fetches a
commit's pull request, and on its README.

## ❓ Questions you might not think to ask

### Does the PR title still matter when the body has a block?

Yes, for the title check, and it's still the commit message on `main`. The changelog and the next version come from
the block alone: a PR titled `chore:` whose block holds a `feat!:` entry cuts a major release.

### Why does a `Release-As:` footer work only inside the block?

The repository squash-merges with the PR title only, so a commit body never reaches `main`, and release-please
looks for the footer in the commit message. The block is read in place of that message, so a footer on one of its
entries counts. See [The rules](#-the-rules).

### Can a release-please upgrade change any of this?

Yes. Its README documents the override block but not the nested markers, which are only in its source. After a
`release-please-action` bump that moves the bundled release-please version, run the script in
[#1408](https://github.com/brantunger/unruly-engine/issues/1408) again and compare its output with the one there.
