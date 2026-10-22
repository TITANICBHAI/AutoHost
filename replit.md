# AutoHost Android Workspace

Implementation workspace for AutoHost, an Android-native platform for user-written phone automations. Product requirements, architecture, schemas, and progress tracker are in `plan/`.

## Run & Operate

- `pnpm --filter @workspace/api-server run dev` — run the API server (port 5000)
- `pnpm run typecheck` — full typecheck across all packages
- `pnpm run build` — typecheck + build all packages
- `pnpm --filter @workspace/api-spec run codegen` — regenerate API hooks and Zod schemas from the OpenAPI spec
- `pnpm --filter @workspace/db run push` — push DB schema changes (dev only)
- Required env: `DATABASE_URL` — Postgres connection string
- `scripts/push-to-github.sh https://github.com/OWNER/REPO.git [branch]` — push the current commit using the `GITHUB_PERSONAL_ACCESS_TOKEN` Replit Secret

## Stack

- pnpm workspaces, Node.js 24, TypeScript 5.9
- API: Express 5
- DB: PostgreSQL + Drizzle ORM
- Validation: Zod (`zod/v4`), `drizzle-zod`
- API codegen: Orval (from OpenAPI spec)
- Build: esbuild (CJS bundle)

## Where things live

- `plan/AGENTS.md` and `plan/README.md` — implementation instructions and reading order
- `plan/plan.md` and `plan/docs/` — product plan, architecture, UI, API, and verification requirements
- `plan/docs/PROGRESS.md` — current phase and next unfinished task
- `plan/schemas/`, `plan/samples/`, `plan/sdk-python/` — starter contracts and examples
- `scripts/push-to-github.sh` — HTTPS push helper; does not store a remote or credential

## Architecture decisions

_Populate as you build — non-obvious choices a reader couldn't infer from the code (3-5 bullets)._

## Product

AutoHost hosts user-written Python automations and declarative flows on Android, with permissioned device capabilities, task profiles, triggers, schedules, run history, and recovery. The attached plan is the source of truth for implementation scope.

## User preferences

- GitHub pushes must use HTTPS and a PAT stored in Replit Secrets; do not use OAuth, SSH, or embed credentials in source, commands, or remotes.
- The GitHub repository will be specified later.

## Gotchas

_Populate as you build — sharp edges, "always run X before Y" rules._

## Pointers

- See the `pnpm-workspace` skill for workspace structure, TypeScript setup, and package details
