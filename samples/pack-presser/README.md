# Press Button (app pack edition) — P11-04 (ADR-080)

The archetype task with the brittle knowledge pulled OUT of the task and into an **app pack**
(`.aapack`, plan §7.10): the pack carries named selectors, the Screen signature, locale labels
and tips for the target app; the task only names them (`uses_packs`). Fix the selector once in
the pack and every task that references it picks the fix up on its next run — the run config
carries the pack version that was actually used, so evidence stays honest.

## The pack (for the demo)

```json
{
  "schema": 1,
  "id": "app.autohost.sample.presser-pack",
  "target": "com.example.presser",
  "version": "1.1.0",
  "selectors": {
    "login_button": {
      "op": "and",
      "terms": [{ "text": { "labels": ["Log in"], "mode": "eq", "case_sensitive": false } }]
    }
  },
  "screens": { "login": { "markers": ["Log in", "Username"] } },
  "labels": { "en": { "login_button": "Log in" }, "de": { "login_button": "Anmelden" } },
  "tips": ["The login screen auto-focuses the username field."]
}
```

Zip that as `pack.json` inside `presser-pack.aapack` and install it via Settings → Backup &
packs → Install pack. The run injects it into the task's config as `config.packs`.

## What the task does

1. reads the pack payload (`autohost.packs.pack`) and logs its version;
2. launches the target app (grant scopes use `${config.target_package}` as usual);
3. resolves `login_button` from the pack (`packs.selector`) and its label with the locale
   fallback (`packs.label`) — en → de → first available;
4. waits for the button, clicks it, records evidence, returns `pressed` with the pack version.

If the pack is missing at run start the host warns and the run fails honestly with
`PackMissing` (never a hardcoded fallback).
