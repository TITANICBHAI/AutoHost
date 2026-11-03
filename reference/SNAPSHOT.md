# reference/SNAPSHOT.md — where this came from

Assembled 2026-11-02 (UTC) by the v4 planning pass from archives the owner attached. Archives were extracted unmodified to a scratch location; files were copied byte-for-byte; sha256 was computed with Python `hashlib` over the copies (`INDEX.md`).

| Project | Archive (as attached) | Top folder | Licence status (see `docs/REFERENCES.md` §0) |
|---|---|---|---|
| ClosePaw | `closepaw-0.1.0.zip` | `closepaw-0.1.0/` | Apache-2.0; LICENSE + NOTICE ("Copyright 2026 ClosePaw Authors") present |
| opendroid | `a53f9a1c-opendroid-main.zip` | `opendroid-main/` | Apache-2.0 text, one word changed in §4(d); fork; no NOTICE |
| android-agent | `ef862d75-android-agent-main.zip` | `android-agent-main/` | none |
| sannabotapp | `7a4aeb67-sannabotapp-main.zip` | `sannabotapp-main/` | README "MIT"; no LICENSE; proprietary wake-word SDK |
| ARIA | `aria-ai-cpu-only-main.zip` | `aria-ai-cpu-only-main/` | none; owner UNVERIFIED |
| AutoHost (base of this tree) | `4e5a27cb-AutoHost-main.zip` | `AutoHost-main/` | the owner's project |

Selection rule for snapshots: a file is copied only if a row of `docs/REUSE_MATRIX.md` names it with disposition *adapt*, or it is a design document the matrix cites as a *reference* (ClosePaw capsule/overlay/style docs, the mobile-action doc), or it is a licence/notice. Manifests exclude binary files (images, fonts, native libraries, archives, model files) and symlinks; counts are in `INDEX.md`.

Not verified: that the archives equal the projects' public repositories; that the snapshot files compile in our tree (they are not compiled).

## Added by the 2026-11-03 merge pass
Pack B (`autohost-planning-pack.zip`) carried complete copies of two of these projects. They were not placed in this folder (rule 3 in `README.md` keeps unlicensed source out of it); they are preserved verbatim under `../archive/pack-B/reference/`.

| Project | Archive as recorded by pack B | sha256 of that archive (pack B's record) | Omitted from pack B's copy |
|---|---|---|---|
| ARIA | `aria-ai-cpu-only-main.zip` | `d5e3a5d75d0081c3e62c9a31b0b77bfebdf2220153415349e8c16f70872944c5` | nothing |
| ClosePaw | `closepaw-0_1_0.zip` | `7d1529b4e80a38e14586c85406e5a1bca4e7ce2bcd81bf9503ea94061148c42e` | 6 entries: `.codex`, `.claude`, `.agents`, `.cursorrules`, `GEMINI.md`, `AGENTS.md` (symlinks and ClosePaw's own agent configuration) |

Done in the merge: the 26 ClosePaw files listed in `INDEX.md` §1 were compared byte for byte with pack B's copy and are identical. Not done: the archive hashes above were copied from pack B's README, not recomputed (the original zips are not in this tree).
