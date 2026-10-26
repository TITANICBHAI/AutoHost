---
name: Replit verification workflows
description: Replit workflow and Python package-manager behaviors relevant to local verification commands.
---

When adding one-shot verification workflows, register each as its own console workflow and inspect `.replit` afterward. Replit may also attach new workflows to the existing `Project` parent, which can unexpectedly run unrelated actions (including GitHub push) from the Run button.

**Why:** A parallel parent workflow that included checks and push could trigger a repository push during a routine Run action.

**How to apply:** Keep the default Run button on a safe local check, preserve push as a separate manually started workflow, and confirm the final workflow list/config after registration.

Avoid calling the Python package installer at the repository root when it has no Python project metadata: it may create generic root packaging files and scaffold directories. Keep simulator-only dependencies declared in their owning project area.

**Why:** A root install created generic project metadata rather than a focused dependency declaration.

**How to apply:** Declare test-only dependencies under the relevant Python project and clean up any installer-generated root scaffolding before finishing.

For AutoHost, Replit verification workflows should bootstrap their required tools from project scripts instead of failing when tools are absent. Android workflows prepare a compatible JDK, Gradle, Android SDK packages, and accepted SDK licenses; Python verification creates an isolated cached environment and installs the declared test requirements. Use Replit console workflows as the routine entry points rather than relying on GitHub Actions for every check, and keep GitHub push isolated from the default run.

**Why:** The user explicitly corrected that the workflows must set up their own build and test prerequisites and run from Replit's console.

**How to apply:** Keep each console workflow pointed at its project bootstrap/test script, avoid separate manual dependency-install commands, and preserve a safe default run that does not push to GitHub.
