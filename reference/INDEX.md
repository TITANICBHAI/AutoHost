# reference/INDEX.md — every file in `reference/`, with provenance and licence status

**Nothing in this folder is compiled, tested or on any Gradle, Python or test source set.** It exists so that an adapter can read the exact upstream text next to the decision that names it (`docs/REUSE_MATRIX.md`). See `reference/README.md` for the rules and `reference/SNAPSHOT.md` for where each file came from. sha256 values were computed over the verbatim copies.

## 1. Verbatim Apache-2.0 snapshots (only files the reuse matrix names)
| Path under `reference/` | Project | Licence status | Bytes | sha256 | Why it is here |
|---|---|---|---:|---|---|
| `closepaw/LICENSE` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 11299 | `34caf3ddbf7945ee22c3fde1d5783762110f54cb42edf2cca0b8b8cf2a4dd746` | license |
| `closepaw/NOTICE` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 3042 | `793112e399f673e9fe244b5ddb78fc8d51c5f737e9ecabe04622b41ada47384d` | notice (keep with any adapted file) |
| `closepaw/app/src/main/kotlin/ai/closepaw/platform/NodeActionPerformer.kt` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 17349 | `95af03f4bdf3f19995a72426e4f4efb1de148b053118984d3c867fe8a57aa924` | RM-A1 |
| `closepaw/app/src/main/kotlin/ai/closepaw/platform/AccessibilityGestureInjector.kt` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 7514 | `12bdfa64d0cf3760e81ba1a2a1d760d7e2d413931233b1bb44b223deb9fb959c` | RM-A2 |
| `closepaw/app/src/main/kotlin/ai/closepaw/tool/action/PointActionExecutorCore.kt` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 12842 | `0523acdcead9dd1e59992aff0fc7f63b30cdafe5864d699bb6dc01de3d5c318e` | RM-A3 |
| `closepaw/app/src/main/kotlin/ai/closepaw/tool/action/ClickExecutor.kt` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 2037 | `d924fa0ddde2861ca8c5e46d1d426e14c67af5f1c0c0183f3054c2849045b67e` | RM-A3 |
| `closepaw/app/src/main/kotlin/ai/closepaw/tool/action/UiChangeDetector.kt` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 6469 | `5913a2984e8fbca0c6c60e32f1c824622c57a51778db4f8b90a158e95845f789` | RM-A4 |
| `closepaw/app/src/main/kotlin/ai/closepaw/tool/AppClassifier.kt` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 5777 | `f06ece148142c3d72a871db9e2e5fe5206f545dfde2fe850e7e2f9278f852c0c` | RM-B1 |
| `closepaw/app/src/main/assets/security/app_tiers.json` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 4709 | `b6cbb1be45965c2974cf0e62552a19328aab13ade48a7e3423d186d1e311397c` | RM-B1 data |
| `closepaw/app/src/main/kotlin/ai/closepaw/tool/PolicyEngine.kt` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 7750 | `328bc9ae08afd754860b9cf4d3ab2443fb036d85b1b5fbdc581ed5af3d3197d2` | RM-B2 |
| `closepaw/app/src/main/kotlin/ai/closepaw/agent/cognition/policy/LoopDetectionPolicy.kt` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 2145 | `7fadd90f74b6c7721edf19ac94993631a7e8fb0ab69c26318512f6557ea95e8e` | RM-C2 |
| `closepaw/app/src/main/kotlin/ai/closepaw/llm/OtherBaseUrlValidator.kt` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 3651 | `fbdd89b0342628f483d50d2ac23787f78479fd58a96b2efb2f0668a4fa47145b` | RM-D1 |
| `closepaw/app/src/test/kotlin/ai/closepaw/platform/NodeActionPerformerTest.kt` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 21738 | `477428a26fb46caed42d1e6c0a98aba6eacdc18c5bdf560b90d53f148cc987bf` | RM-G2 |
| `closepaw/app/src/test/kotlin/ai/closepaw/agent/cognition/policy/LoopDetectionPolicyTest.kt` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 6491 | `c2ee4c8898b4509bf2996e754a808c89a049de1cd2ba774fa4e007256815042d` | RM-G2 |
| `closepaw/app/src/test/kotlin/ai/closepaw/llm/OtherBaseUrlValidatorTest.kt` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 5904 | `dd0c3b2ff1cb4f69f81f13ddccb1505223db4eb53703d8ba729678873ce73bb4` | RM-G2 |
| `closepaw/app/src/test/kotlin/ai/closepaw/llm/CloudStreamRetryPolicyTest.kt` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 13090 | `6d000699a33bec4fd5b7e70d2c4e4000386a55f82b7998d349a9a9b9440eec0d` | RM-G2 |
| `closepaw/app/src/test/kotlin/ai/closepaw/tool/PolicyEngineTest.kt` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 12918 | `50b017bdcc8904e0c82c05d248df50abe06590dbfd2ca2a1ba006b7c3625ee2e` | RM-G2 |
| `closepaw/app/src/test/kotlin/ai/closepaw/tool/AppClassifierSecurityTest.kt` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 4155 | `bd7089b563867b8d59b8d38d8a5fd2c3e0fb807e93a248fd7bc121a4f204b6ba` | RM-G2 |
| `closepaw/app/src/test/kotlin/ai/closepaw/tool/AppClassifierOverrideTest.kt` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 9462 | `85466076efe28cd9959c64c1d5c84af091792dd569d069a98ee6bfc5a07b8c77` | RM-G2 |
| `closepaw/app/src/test/kotlin/ai/closepaw/tool/action/TargetResolverTest.kt` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 14124 | `f44e419524e4f492c7dabc50cd225427c367f389a92f336b83c3da07bb62b119` | RM-G2 |
| `closepaw/doc/main/ui/capsule/architecture.md` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 9849 | `9b4489bf94193eb9ffa71197a7696b2ed3615faff7a6e0436e8910e24eaa779c` | RM-F1 (reference) |
| `closepaw/doc/main/ui/capsule/state_machine.md` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 8182 | `c12e4a452826c7b417c212d6dd13df69c5b1da9d6be50bc13312451838aee022` | RM-F1 (reference) |
| `closepaw/doc/main/ui/overlay.md` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 12838 | `5c230f4e8d90490bf2880148a0a92aa9f7ea7a71c43363dc84938b44c75ce870` | RM-F1 (reference) |
| `closepaw/doc/main/ui/user_interaction.md` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 13044 | `8d642a025847d681df611dda60419225513f7d4f0175c903a21bad99038b38fc` | RM-F1 (reference) |
| `closepaw/doc/main/ui/style.md` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 13835 | `7f1060e7d6e672e69e452c00eefbcf9499d1c13cb02f0d2a6e80495c9b88f6b8` | RM-F2 (reference) |
| `closepaw/doc/main/infra/tool/mobile_action.md` | closepaw | Apache-2.0 (LICENSE+NOTICE below) | 16710 | `8cd42364c1f71875af747876363e2e0449f4fc82aa852b8474af744f8d6ae301` | L34 / ACTION_HARDENING §2 (reference) |
| `opendroid/LICENSE` | opendroid | Apache-2.0 text, one word changed in §4(d) | 11438 | `67aa8eeee7fedd6a6428648d3d45fddf22b9a78004f7d5c25550eaddd4c5f9be` | license (one word changed in §4(d)) |
| `opendroid/app/src/main/java/com/opendroid/ai/core/llm/ModelArtifactIntegrity.kt` | opendroid | Apache-2.0 text, one word changed in §4(d) | 22055 | `272651f43dae96ba331e0cb8e9831ef2a55a56874d2073dd559013fefcccbd4a` | RM-E1 |
| `opendroid/app/src/main/java/com/opendroid/ai/core/llm/ModelDownloadWorker.kt` | opendroid | Apache-2.0 text, one word changed in §4(d) | 16310 | `5a2b90b3c927d4be47b79cb57ba3ac5ec3f6a203de87ef3ed0ea50801ce62904` | RM-E2 |
| `opendroid/app/src/main/java/com/opendroid/ai/core/llm/ModelDownloadRetryPolicy.kt` | opendroid | Apache-2.0 text, one word changed in §4(d) | 795 | `6d031a7b15c776ff6f702d714598279b0ceb87fbc3febec2b91845bc87f84c6c` | RM-E2 |
| `opendroid/app/src/main/java/com/opendroid/ai/core/llm/ModelStoragePaths.kt` | opendroid | Apache-2.0 text, one word changed in §4(d) | 4574 | `4b66307221baba924f8ebb51a589127aa8e3212f592c3f8a01e535891bcdf357` | RM-E2 |
| `opendroid/app/src/main/java/com/opendroid/ai/core/llm/PromptBudget.kt` | opendroid | Apache-2.0 text, one word changed in §4(d) | 1598 | `a27535e577071cd9ae2d121bd74fe453f8203af1873f183c15bce3d6bf7ce2de` | RM-E3 |
| `opendroid/app/src/test/java/com/opendroid/ai/core/llm/ModelArtifactIntegrityTest.kt` | opendroid | Apache-2.0 text, one word changed in §4(d) | 16760 | `6229247d530be33fd892eaaa476a404f8906ef0049f46267ddb72f31fff72a7a` | RM-E1 test |
| `opendroid/app/src/test/java/com/opendroid/ai/core/llm/PromptBudgetTest.kt` | opendroid | Apache-2.0 text, one word changed in §4(d) | 2270 | `a3f58d904f5e9742eda2fe0b71970b7bc1a7f0e0b94a10f79f783c1894b2bd7b` | RM-E3 test |
| `opendroid/docs/qa/qwen-litert-test-plan.md` | opendroid | Apache-2.0 text, one word changed in §4(d) | 11791 | `176dc844fc148956e31a8065143254b544c77bcca40dcdf9c1344a1c67700e6a` | RM-E8 |

Keep `closepaw/LICENSE` and `closepaw/NOTICE` with any ClosePaw-derived file; keep `opendroid/LICENSE` with any opendroid-derived file. Copying a file out of `reference/` into the build is an *adaptation* and follows `docs/REUSE_MATRIX.md` §0 (header, `THIRD_PARTY_NOTICES.md` row, ported test, ladder evidence).

## 2. Manifests (path / bytes / sha256 only — **no source text**) for projects that may not be copied
| File | Project | Licence status | Files listed | Skipped (binary or symlink) |
|---|---|---|---:|---:|
| `manifests/android_agent.tsv` | android-agent | no LICENSE — ideas only | 110 | 1 |
| `manifests/sannabot.tsv` | sannabot | README "MIT", no LICENSE file — ideas only | 224 | 11 |
| `manifests/aria.tsv` | aria | no LICENSE — ideas only | 134 | 1 |

## 3. Findings (the planning agent's own notes)
| File | About |
|---|---|
| `findings/closepaw.md` | ClosePaw: agent loop, tools, action layer, providers, UI, licences |
| `findings/opendroid.md` | opendroid: providers, on-device inference, integrity pipeline, QA plan, overlay issue |
| `findings/android_agent.md` | android-agent: pipeline, approvals, compaction, eval harness |
| `findings/sannabot.md` | sannabotapp: loop, learned hints, services |
| `findings/aria.md` | ARIA: what exists, what is stubbed, why it is an anti-reference |
| `findings/autohost_user.md` | the owner's AutoHost code as attached: structure and static observations |
| `findings/aria_audit_packB.md` | the second planning pack's ARIA audit and licence register (verbatim; its numbering is not ours) |
| `findings/closepaw_assessment_packB.md` | the second planning pack's ClosePaw package-level assessment (verbatim; its numbering is not ours) |

## 4. Pack B material outside this folder (added by the 2026-11-03 merge pass)
| Path | What | Licence status | Archive sha256 (as recorded by pack B, not recomputed) |
|---|---|---|---|
| `../archive/pack-B/reference/closepaw/` | complete ClosePaw 0.1.0 snapshot (914 files); the 26 files listed in §1 are byte-identical to the same paths there (compared in the merge) | Apache-2.0 (LICENSE and NOTICE inside) | `7d1529b4e80a38e14586c85406e5a1bca4e7ce2bcd81bf9503ea94061148c42e` (`closepaw-0_1_0.zip`) |
| `../archive/pack-B/reference/aria/` | complete ARIA snapshot (135 files) | **none; owner UNVERIFIED — provenance only, never copy (D-17)** | `d5e3a5d75d0081c3e62c9a31b0b77bfebdf2220153415349e8c16f70872944c5` (`aria-ai-cpu-only-main.zip`) |
