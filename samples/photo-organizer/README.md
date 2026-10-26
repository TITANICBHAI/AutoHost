# Photo organizer — the P11-12 port exercise (plan §18 P11)

A canonical desktop Python script — "walk my camera folder and sort the photos into year-month
folders" — rewritten against the AutoHost SDK with **no hacks**: every place the original
touched the filesystem directly became a capability call, and every gap the capability model
exposed is logged in `docs/PORT_EXERCISE.md` as an SDK/capability issue, exactly as the plan
requires ("never as a hack or shim").

## The port at a glance

| Desktop original | AutoHost port |
|---|---|
| `os.listdir(camera_dir)` | `autohost.media.list("images")` (MediaStore, newest first) |
| `exif/mtime` per file | the row's `added_ms` (the store owns metadata — honest, if coarser) |
| `shutil.copy` into `Photos/<YYYY-MM>/` | `autohost.files.shared.write("primary:Pictures", ...)` under the SAF tree the user granted |
| `os.remove` after copying | `media.delete` **only** with `delete_originals: true` (a copy is the default — deletion is explicit) |

## Run it

Install the task, grant `media` + `files.shared` (Permission Review → pick the Pictures tree),
then run. Default config copies up to 50 images per run into `Photos/<YYYY-MM>/`; the run
result reports copied/skipped/deleted and the per-month counts.

## The honest gaps (summary — full log in docs/PORT_EXERCISE.md)

1. **No recursive directory walk** on shared storage (SAF lists one tree segment at a time) —
   the port switched to the MediaStore index, which is the better source anyway.
2. **EXIF dates are not exposed** by `media.list` (row metadata only: `added_ms`) — a
   capability gap with a proposal: an `exif: bool` flag on `media.list` rows.
3. **No atomic rename/move** across providers (MediaStore → SAF tree) — the port copies and
   deletes as two steps, which is also safer on interrupted runs.
4. **Large files**: `media.read` caps at 10 MB per item — a documented limit for videos; the
   sample restricts itself to images.
5. **The desktop script assumed a local timezone**; the port normalizes timestamps to UTC and
   documents it (month folders can differ from the desktop version by a day at month edges).
