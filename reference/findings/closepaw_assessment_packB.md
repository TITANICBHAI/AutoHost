# ClosePaw package-level assessment — from the second planning pack

> **Provenance (merge pass, 2026-11-03).** Copied verbatim from the second planning pack's `docs/REUSE_MATRIX.md` (§B). It was written against that pack's own snapshots (kept under `archive/pack-B/reference/`). Task, ADR and R-numbers in this note are **that pack's**, not this tracker's. Where it disagrees with `docs/REUSE_MATRIX.md`, the matrix wins. ARIA is locked (D-17): never copy code on the strength of this note. UNVERIFIED here — nothing was built.

## B. ClosePaw assessment (package `ai.closepaw`, Apache-2.0)
Main sources under `app/src/main/kotlin/ai/closepaw/`; tests under `app/src/test` and `app/src/androidTest`. "Depends on" counts import statements into other ClosePaw packages (a coupling measure, not a build graph).

| Package | Main files | Main lines | Test files | Depends on (top imports) | Disposition |
|---|---|---|---|---|---|
| `agent` | 29 | 3726 | 30 | history 18, tool 13, llm 11, protocol 10 | Rewrite as `:runtime:agent`; two helpers staged (R-11) |
| `app` | 17 | 3864 | 7 | ui 37, protocol 34, llm 19, auth 7 | Reference only |
| `auth` | 5 | 826 | 3 | llm 2, BuildConfig 1 | Excluded (Codex/ChatGPT sign-in) |
| `browser` | 38 | 6225 | 32 | trace 3 | Defer (R-21) |
| `debug` | 3 | 743 | 0 | platform 9, tool 6, app 4, model 2 | Reference only |
| `history` | 16 | 2474 | 9 | ui 7, llm 2, protocol 2 | Reference (R-15) |
| `llm` | 26 | 4150 | 20 | auth 3, BuildConfig 2, app 1 | Adapt cloud clients (R-10); Leap/Codex files excluded |
| `memory` | 3 | 387 | 2 | – | Defer (AutoHost `store`/`state` cover it) |
| `model` | 1 | 131 | 1 | – | Shared types pulled in by closure only |
| `onboarding` | 8 | 1220 | 7 | protocol 9, app 7, auth 7, llm 6 | Reference only |
| `perception` | 6 | 837 | 3 | model 9, util 2 | Reference only (R-16) |
| `platform` | 32 | 5131 | 12 | model 13, trace 11, perception 8, protocol 7 | Gestures, node actions, screenshot adapt (R-02–R-04); virtual display defer (R-21) |
| `protocol` | 27 | 728 | 0 | llm 1, perception 1 | Shared types pulled in by closure only |
| `session` | 12 | 2427 | 15 | tool 29, history 20, agent 19, protocol 19 | Reference only (coupled to tool/history/agent) |
| `termux` | 4 | 859 | 4 | R 1 | Defer (R-21) |
| `tool` | 41 | 6062 | 31 | platform 29, model 23, protocol 14, agent 7 | Action executors adapt (R-02); policy adapt (R-13); registry reference (R-12) |
| `trace` | 11 | 1243 | 3 | agent 7, history 3, protocol 3, tool 3 | Adapt recorder and redactor (R-14) |
| `ui` | 96 | 16096 | 38 | protocol 50, llm 23, onboarding 19, app 14 | Theme and overlay adapt (R-17, R-18); remaining screens reference (R-19) |
| `util` | 1 | 27 | 0 | – | Pulled in by closure only |

**Android floor.** ClosePaw builds with `minSdk = 31`, `targetSdk = 36`; a build comment says 31 is required by the LiquidAI Leap SDK, so lowering it needs the Leap dependency removed and every API 30+ call checked. AutoHost's floor is 29 (ADR-002): staged files are compiled against minSdk 29 and linted (`NewApi`) before integration. Files with explicit version checks: `platform/AccessibilityPlatform.kt` (S), `platform/AccessibilityScreenshotCapturer.kt` (R, UPSIDE_DOWN_CAKE), `platform/NodeActionPerformer.kt` (R), `platform/virtualdisplay/*` (S, TIRAMISU), `termux/*` (O, TIRAMISU), `app/AgentService*.kt` (TIRAMISU).

**Dependency licences** (from `app/src/main/assets/open_source_licenses.json`, 140 entries): 129 Apache-2.0, 6 MIT, 5 other. Not Apache-2.0:

| Dependency | Version | Licence as reported |
|---|---|---|
| Bouncy Castle ASN.1 Extension and Utility APIs | 1.84 | Bouncy Castle Licence |
| Bouncy Castle PKIX, CMS, EAC, TSP, PKCS, OCSP, CMP, and CRMF APIs | 1.84 | Bouncy Castle Licence |
| Bouncy Castle Provider | 1.84 | Bouncy Castle Licence |
| Compose Icons Lucide | 2.2.1 | MIT License |
| EdDSA-Java | 0.3.0 | CC0 1.0 Universal |
| leap-sdk | 0.9.2 | Leap Terms of Use |
| Shizuku API - aidl | 13.1.5 | MIT License |
| Shizuku API - API | 13.1.5 | MIT License |
| Shizuku API - provider | 13.1.5 | MIT License |
| Shizuku API - shared | 13.1.5 | MIT License |
| SLF4J API Module | 2.0.17 | MIT |

Findings: (1) **`ai.liquid.leap:leap-sdk` is proprietary ("Leap Terms of Use") and is excluded.** (2) ClosePaw's `NOTICE` lists Shizuku API/Provider as Apache-2.0 and Lucide as ISC, while the generated report says MIT for both: verify upstream before redistributing either (P0-40). (3) Fonts (Fraunces, Geist, JetBrains Mono) are third-party; `FONT_ATTRIBUTION.md` is staged with them (R-18) and their licence texts must be confirmed. (4) Apache-2.0 requires keeping notices and marking changed files, and grants no trademark rights: the ClosePaw name and paw glyph are not carried over (`StatusPawGlyph.kt` is not staged).
