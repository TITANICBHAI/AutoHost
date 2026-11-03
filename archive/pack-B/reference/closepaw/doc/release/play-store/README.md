# Play Store Submission Assets

Final submission-ready assets for ClosePaw on Google Play. All files in this folder are upload-ready; the working source (Next.js editor, raw captures) lives in `marketing/`.

## Files

| Asset | File | Spec |
|-------|------|------|
| App icon (hi-res) | `app-icon-512.png` | 512×512 PNG |
| Feature graphic | `feature-graphic.png` | 1024×500 PNG |
| Phone screenshots | `screenshots/phone-01..06.png` | 1080×1920 PNG, 6 slides |
| Short description | `short-description.txt` | ≤80 chars |
| Full description | `full-description.txt` | ≤4000 chars |

## Screenshot order (Play Store gallery)

1. `phone-01-hero.png` — Hero ("Your phone, on autopilot.")
2. `phone-02-chat.png` — Chat result ("Just say what you need.")
3. `phone-03-capsule.png` — Smart Capsule on Amazon ("Control from inside any app.")
4. `phone-04-visualizer.png` — Gmail with live view (dark)
5. `phone-05-privacy.png` — Privacy by design
6. `phone-06-models.png` — LLM provider settings

## Regenerating

Source-of-truth is `marketing/screenshot-editor/public/slides.html` + raw captures in `marketing/captures/`. To regenerate:

```bash
cd marketing/screenshot-editor && bun run dev
# in another shell:
node export-slides.js
# copy outputs from marketing/export/ → doc/release/play-store/
```

## Pending items (separate tasks in projects/tasks.json)

- `publish-a11y-declaration` — Accessibility Service declaration + demo video
- `publish-data-safety` — Data Safety form
- `publish-privacy-policy` — public Privacy Policy URL
- `publish-play-console-account` — $25 developer account
- `publish-internal-testing` — first submission to internal track
