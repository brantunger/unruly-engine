# 🎫 The release-please token

How the release workflow's `release-please` job gets the fine-grained personal access token it runs with, and what
to do when the token is missing, expires or is revoked.

**Who it's for:** the maintainer.
**You'll be able to:** create the token with the right permissions, store it as the `RELEASE_PLEASE_TOKEN` secret,
rotate it, and recover a release that a bad token stopped.
**Before you start:** [RELEASING.md](../../RELEASING.md#-the-normal-flow), for the release flow the token is part of.

[← Documentation index](../README.md)

- [Why a token](#-why-a-token)
- [Creating the token](#-creating-the-token)
- [Rotating the token](#-rotating-the-token)
- [When it fails](#-when-it-fails)

---

## 🧭 Why a token

The `release-please` job in [`release.yml`](../../.github/workflows/release.yml) runs with a fine-grained personal
access token (PAT), stored as the repository secret `RELEASE_PLEASE_TOKEN`. There's no fallback to `GITHUB_TOKEN`:
a release PR opened with it gets a CI run that waits for an approval and no title check at all.

The PAT acts as its owner, so the release PR is authored by the maintainer, and it gets a CI run and the title
check as any other PR does, though CI runs only [one build leg](build-and-gates.md#-ci) on it. The ruleset on
`main` requires no approving review, so the maintainer can still merge it.

> [!IMPORTANT]
> Create the secret before a `release.yml` that reads it reaches `main`. Merging that change is itself a push to
> `main`, and every push to `main` runs `release.yml`.

A tag or Release created with a PAT, unlike one created with `GITHUB_TOKEN`, triggers `on: push: tags` and
`on: release` workflows. None exist today, so a workflow added with either trigger will run on every release.

## 🔧 Creating the token

1. Open **Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token**.
2. Name it, say `unruly-engine release-please`, and set **Resource owner** to `brantunger`.
3. Pick an **Expiration** date and note it: an expired token stops releases.
4. Under **Repository access**, choose **Only select repositories** and pick `brantunger/unruly-engine`.
5. Under **Repository permissions**, set the three in the table below and nothing else, then generate the token.
6. Store it. `gh` prompts for the value, which keeps the token out of the shell history:

```bash
gh secret set RELEASE_PLEASE_TOKEN --repo brantunger/unruly-engine
```

| Permission | Access | What release-please does with it |
| --- | --- | --- |
| Contents | Read and write | Commits to the release PR's branch, creates the tag and the GitHub Release |
| Pull requests | Read and write | Finds, opens and updates the release PR, labels it and comments on it |
| Metadata | Read-only | Mandatory on every fine-grained token; release-please reads the repository with it |

Issues isn't needed: the label and comment calls accept either Issues or Pull requests write. Nor is Workflows,
which only matters for a change to `.github/workflows/`; a release PR changes `gradle.properties`, the README,
`.release-please-manifest.json` and `CHANGELOG.md`.

## 📅 Rotating the token

Before the token expires, create a new one the same way, store it with the same `gh secret set` command, and delete
the old token on GitHub. The next run of `release.yml` uses the new one.

## 🧯 When it fails

| Symptom in the `release-please` job | Cause | What to do |
| --- | --- | --- |
| The `Require the release-please token` step fails: `The RELEASE_PLEASE_TOKEN repository secret is missing or empty` | No secret, or an empty one | Create the token and the secret as above, then **Re-run failed jobs**. |
| The release-please step fails with a 401 (`Bad credentials`) or a 403 | The token expired or was revoked | Create a new token, `gh secret set` it, then **Re-run failed jobs**, or start a new run with `gh workflow run release.yml --ref main`. |

In both cases release-please changes nothing: no release PR is opened or updated, no release is cut, and `publish`
is skipped. A token that is valid but lacks a permission can instead fail partway, on the first call that needs it;
the step's log names the request.

Nothing is lost. release-please reads `main` as it is, so the next run that succeeds releases a merged release PR
still labelled `autorelease: pending`. A failure in the `release-please` job after the Release exists is different:
a re-run can't repair it. See the migration guides row of
[When a publish half-completes](../../RELEASING.md#-when-a-publish-half-completes).
