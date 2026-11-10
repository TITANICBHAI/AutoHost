# P11-12 — the port exercise: an existing Python automation onto the SDK

Plan §18 P11 (optional): *"port any existing Python automation by rewriting it against the SDK,
logging every gap as an SDK/capability issue — never as a hack or shim."* This document is that
log. The ported result ships as `samples/photo-organizer` (sim-tested in `test_p11_part2.py`).

## The original (described clean-room)

A canonical desktop utility script, reimplemented from its *behaviour* (no source copied):
walk a camera directory, read each file's date, `shutil.copy` it into
`Photos/<YYYY-MM>/<name>`, optionally delete the original, print a summary. ~60 lines of
stdlib Python that every long-time phone user has wanted on-device at least once.

## The port, decision by decision

| Desktop idiom | AutoHost port | Why |
|---|---|---|
| `os.walk(camera_dir)` | `media.list("image")` | Shared-storage walks need SAF recursion (one segment at a time); the MediaStore index is the platform's own catalog and is faster + more complete |
| `os.path.getmtime` / EXIF DateTimeOriginal | the row's `added_ms` | The store owns metadata; EXIF parsing is out of SDK scope (gap 2 below) |
| `shutil.copy` into folders | `files.shared.write("primary:Pictures", "Photos/<YYYY-MM>/<name>", data)` | SAF tree the user grants once; paths stay inside the tree (containment enforced) |
| `os.remove(original)` | `media.delete(id)` behind `delete_originals` | A copy is the default — deletion is explicit, per-row and reported |
| `print` summary | `ctx.result` + `ctx.evidence.note` | The run result is the user-visible summary; evidence is the durable record |

## The gap log (each gap = an SDK/capability issue, not a workaround)

1. **ISSUE — no recursive directory walk on shared storage.** SAF `list` returns one segment
   per call; a walk is O(depth) round trips with no ordering guarantee. The port switched to
   MediaStore. *Proposal:* a `files.shared.walk` helper (depth-capped, generator-style) for
   tree-shaped use cases.
2. **ISSUE — `media.list` rows carry no EXIF capture time.** `added_ms` is store metadata and
   can drift from the shutter time after copies. Month folders can therefore differ from the
   desktop original for re-saved images. *Proposal:* an optional `exif: bool` flag on
   `media.list` rows exposing DateTimeOriginal when present.
3. **ISSUE — no atomic move/rename across providers.** MediaStore → SAF-tree copies cannot be
   atomic; interrupted runs leave a copy without a delete. The port's two-step (copy now,
   delete on a later explicit run) is *safer*, but a platform-honest `media.move_to_tree`
   would remove the window. *Proposal:* a capability method that moves server-side.
4. **LIMIT (documented) — `media.read` caps at 10 MB per item.** Right for images, wrong for
   videos; the sample restricts itself to images and says so. *Proposal:* chunked reads
   (`offset`/`limit`) for large items.
5. **DECISION — UTC month folders.** The desktop script used the local timezone; the SDK's
   timestamps are UTC and the port normalizes. Month edges shift by a day in some timezones —
   recorded here rather than hidden in a localtime hack. *Note:* a `tz:` config knob would be
   a task-level choice, not an SDK change.

## What needed no workaround

The grants model mapped cleanly: `media` + `media.write` + a `files.shared` tree — one
Permission Review pass, no runtime permission ladders, no storage-permission gymnastics. The
run model (checkpointable, stoppable, evidenced) replaced the desktop script's "hope it
finishes" loop. And the delete guard (default off) is exactly the kind of rail the desktop
script never had.

**Conclusion:** the port needed zero hacks — every gap above is a recorded proposal or a
documented limit. That is the plan's acceptance condition for P11-12.
