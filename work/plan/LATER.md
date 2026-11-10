# LATER — after 0.1 alpha (one session each; same loop)

- **Record → Save → Schedule.** Recorder ends with *Name it → Save as an installed task* (`ManifestFactory` kind `flow`, vectors
  already include two flow cases) → *Run once?* → `ScheduleForm`. Today it saves into the adb dev folder.
- **Online examples gallery, no server.** `tools/make_index.py` zips each `samples/<x>` to `<id>-<version>.aatask`, computes SHA-256,
  writes `index.json` (format = `RepoIndex` v1: id, version, title, runtime, source, sha256, min_host_api, notes). Host the files as static
  files on the owner's site (e.g. `https://tbtechs.pages.dev/autohost/`). App: *Examples → Online* fetches over HTTPS, shows
  Install/Update/Up to date/Can't use (logic exists in `RepoIndex.select`), verifies the SHA-256, then runs the normal review.
- **"When…" recipes:** presets on top of `TriggerForm` (S10).
- **AI:** (1) *Ask an AI to write it*: copy a ready prompt (SDK cheat-sheet generated from the stubs) → user pastes the code into *Paste a
  script*. (2) BYOK presets: pick provider → paste key → test → pick model from `GET /models`; base URLs must be re-checked in each
  provider's current docs and recorded with a date in `docs/AI_PRESETS.md`. Own server: only `localhost` (Termux on the same phone, or
  `adb reverse`) or HTTPS — Android's network security config matches hosts, not address ranges **[from memory: confirm]**, so plain
  `http://192.168.x.x` stays unsupported. (3) Agent "Ask" UX: app picker instead of package names; plain limits.
- **Connect my computer:** one Developer screen with the `adb forward` line, the CLI line with the token (copy buttons), a Test button.
- **ImportActivity (C-06) leftovers:** drop BROWSABLE and the `file` scheme; confirm Permission Review is mandatory before first run.
- **ChainRunner.await:** make it suspend with `withTimeoutOrNull` instead of `Thread.sleep(200)`.
- **Vision matcher:** replace the fixture needle with `v = if (((x/8)+(y/5))%2==0) 200 else 40` (grey), and make the coarse pass work on
  average-pooled images; refine at full resolution in ±stride. Oracle (expected: score 1.0 at (77,33) for exact and +25 brightness):
  ```python
  def pool(a, s):  # average-pool by s
      H, W = a.shape; return a[:H//s*s, :W//s*s].reshape(H//s, s, W//s, s).mean(axis=(1, 3))
  def match_pooled(hg, ng, s):
      hp, npool = pool(hg.astype(float), s), pool(ng.astype(float), s)
      best = max(((ncc_at(hp, npool, x, y), x, y) for y in range(hp.shape[0]-npool.shape[0]+1)
                  for x in range(hp.shape[1]-npool.shape[1]+1)))
      bx, by = best[1]*s, best[2]*s
      return max(((ncc_at(hg, ng, x, y), x, y)
                  for y in range(max(0, by-s), min(hg.shape[0]-ng.shape[0], by+s)+1)
                  for x in range(max(0, bx-s), min(hg.shape[1]-ng.shape[1], bx+s)+1)))
  ```
  Also convert both images to gray `IntArray`s once (today luma is recomputed per pixel per window: ≈2.8 billion conversions for a
  1080×2400 screenshot with a 100×100 needle at stride 4 — unmeasured, probably slow on a phone).
