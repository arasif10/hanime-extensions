# AGENTS.md — Rules for AI assistants working in this workspace

Read this file completely before doing anything. These rules exist because an AI
session already broke publishing once (2026-08-31: downgraded versions, wrong
signing keys, a debug-signed APK shipped to users). Do not repeat it.

## Repository layout

This workspace holds two independent extension repos:

| Folder | GitHub repo | Content | Signing key |
|---|---|---|---|
| `hanime-extensions/` | `arasif10/hanime-extensions` | NSFW extensions (Hanime, HentaiHaven, OppaiStream) | NSFW key, cert `OU=Extensions` |
| `anime-extensions/` | `arasif10/anime-extensions` | SFW extensions (MovieBox, ToonHub4u, ToonStream, ToonWorld4All) | SFW key, cert `OU=Extensions SFW` |

Each repo has two branches:

- `main` — extension source code (Kotlin, `src/<lang>/<name>/`, `build.gradle` with `extVersionCode`).
- `repo` — published artifacts: `apk/`, `icon/`, `index.json`, `index.min.json`. **This is what AniZen users install from.**

## The ONLY correct way to publish an extension

1. Make the code change in the extension under `src/...`.
2. Bump `extVersionCode` by exactly 1 in that extension's `build.gradle`.
3. Commit to `main` and `git push origin main`.
4. Done. GitHub Actions (`.github/workflows/build_apk.yml`) builds, signs with the
   repo's secret keystore, and publishes APK + index to the `repo` branch via
   `.github/publish_extensions.py`.

### Absolutely forbidden

- **NEVER run `publish_extensions.py` locally.** Local APK folders are stale and
  signed with the wrong key. (This caused the 2026-08-31 incident.)
- **NEVER copy/commit APK files into the `repo` branch by hand.**
- **NEVER publish a debug-signed APK** (cert `CN=Android Debug`).
- **NEVER publish an APK from a build you made locally.** Local builds are for
  compile-checking only — they are signed with whatever keystore happens to be
  around, or with the debug key.
- **NEVER lower a published version.** Before bumping, check the current
  published `code` on the `repo` branch — the new code must be strictly greater.

## Signing facts (memorize)

- The SFW keystore exists **only** in the `anime-extensions` GitHub secret
  (`SIGNING_KEY`). It is NOT on this machine. The hanime key is also CI-only.
- `common.gradle` silently falls back to **debug signing** when
  `signingkey.jks` is missing from the repo root. A local "successful" build is
  therefore usually debug-signed — useless for publishing.
- Verify a published APK's signature with:
  `apksigner verify --print-certs <apk>` (build-tools at
  `C:\Users\Asif\AppData\Local\Android\Sdk\build-tools\<ver>`).
  - hanime repo APKs must show `OU=Extensions`
  - anime repo APKs must show `OU=Extensions SFW`
  - anything showing `CN=Android Debug` is a mistake.

## Index discipline

- `index.json` and `index.min.json` on the `repo` branch must always contain the
  **same set of entries**. CI's publish script looks the package up in BOTH
  files and **silently skips** the extension if either is missing it.
- Do not hand-edit index files after a CI publish; CI owns them. If a manual
  metadata fix is truly needed, fix BOTH files, keep versions/codes identical,
  and say so in the commit message.
- Icons live at `icon/<pkg>.png` (e.g. `icon/eu.kanade.tachiyomi.animeextension.en.reanime.png`).

## Adding a brand-new extension

1. The source must be committed to `main` FIRST (`src/<lang>/<name>/` + icon +
   `build.gradle`). CI cannot build what is not in `main`.
2. A new package is not in the index yet, so the publish script will skip it.
   Add its entry to BOTH index files on the `repo` branch (with a real source id)
   and its icon, in a separate clearly-labeled commit.
3. Push `main` again (or trigger the workflow with `gh workflow run`) so CI
   builds and publishes it signed.

## Git hygiene

- Work on `main`, push when done. Never force-push. Never commit `build/`
  outputs, keystores (`*.jks`, `*.keystore`), or secrets.
- `repo` branch edits are metadata-only and rare; APK files there are CI-owned.

## Post-publish verification checklist (always do this)

1. `gh run list -R arasif10/<repo> --limit 3` — the run for your push succeeded.
2. `https://raw.githubusercontent.com/arasif10/<repo>/repo/index.min.json` shows
   the new version + code.
3. The APK URL returns HTTP 200:
   `https://raw.githubusercontent.com/arasif10/<repo>/repo/apk/<apk-name>`
4. `apksigner verify --print-certs` on the downloaded APK shows the right cert
   for that repo (see Signing facts).

## Environment notes

- Windows + PowerShell. Long `Get-ChildItem` pipelines sometimes lose output —
  write results to a file and read it if a command prints nothing.
- `gh` may return `HTTP 401: Bad credentials` — ask the user to re-auth with
  `gh auth login`, or fall back to `git` + `raw.githubusercontent.com` checks.
- Java/Android SDK: `C:\Users\Asif\AppData\Local\Android\Sdk`. Gradle builds are
  invoked per-module, e.g. `.\gradlew.bat :src:all:oppaistream:assembleRelease`.

## Known state (update this section when it changes)

- 2026-08-31: all extensions in both repos publish cleanly from CI. ReAnime
  (anime-extensions) is published as v14.01 but its APK is DEBUG-SIGNED and its
  source is missing from `main` — do not rebuild/re-publish ReAnime until its
  source is restored to `src/en/reanime/`, then keep `extVersionCode = 1` so
  the broken debug build is replaced like-for-like.
