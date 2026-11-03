# app/imported — staged source, not compiled
31 files copied **verbatim** from `reference/` for later adaptation into `:app`. Rows: R-17 in `docs/REUSE_MATRIX.md`.
- Nothing here is part of any Gradle build and nothing here counts as integration.
- Layout: `imported/<aria|closepaw>/<original path inside that repository>`.
- Adapt one file at a time into `src/main/kotlin/app/autohost/…` (AutoHost package, attribution header, tests ported), then update its row in `docs/migration-manifest.tsv`. The staged copy may be deleted only after its row says `integrated` or `dropped`.
- ARIA files: licence unconfirmed (P0-40). ClosePaw files: Apache-2.0, keep the notice.
