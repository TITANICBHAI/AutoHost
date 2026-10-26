"""Photo organizer — the P11-12 port exercise (plan §18 P11, optional).

The original is a canonical desktop Python script every phone user eventually wants:
"sort my camera photos into year-month folders". On a PC it walks a directory and renames
files. On Android, the same job is a MEDIA STORE job — the port lands on AutoHost's
capability model instead of filesystem access:

  - the desktop script's `os.listdir(camera_dir)`   → `autohost.media.list("image")`
  - the desktop script's `shutil.copy` into folders → `files.shared` writes under the
    granted tree (Pictures), organized as `Photos/<YYYY-MM>/<name>`
  - the desktop script's `os.remove`                → `media.delete` ONLY when the user
    explicitly sets delete_originals (a copy, never a silent move)

Every gap found during the port is logged in `docs/PORT_EXERCISE.md` as an SDK/capability
issue — per the plan, never papered over with a hack or shim.
"""
import datetime
import posixpath

from autohost import ctx, device, log
from autohost.media import list as media_list
from autohost.media import read as media_read
from autohost.media import delete as media_delete
from autohost.files import shared


def main():
    max_items = int(ctx.config.get("max_items") or 50)
    delete_originals = bool(ctx.config.get("delete_originals"))

    listed = media_list(kind="image", limit=max_items)
    rows = listed.get("media", [])
    if not rows:
        log.info("no images to organize")
        return ctx.result("nothing_to_do", {"scanned": 0})

    copied = 0
    skipped = 0
    folders = {}
    for row in rows:
        name = row.get("display_name") or "img-{}.jpg".format(row.get("id"))
        added_ms = int(row.get("added_ms") or 0)
        stamp = datetime.datetime.fromtimestamp(added_ms / 1000, tz=datetime.timezone.utc)
        folder = "{:04d}-{:02d}".format(stamp.year, stamp.month)
        try:
            data = media_read(row["id"])
        except Exception as exc:  # unreadable rows are skipped honestly, one by one
            log.warn("read failed", id=row.get("id"), error=str(exc))
            skipped += 1
            continue
        target = posixpath.join("Photos", folder, name)
        shared.write("primary:Pictures", target, data)
        copied += 1
        folders[folder] = folders.get(folder, 0) + 1

    deleted = 0
    if delete_originals:
        for row in rows:
            # Deletion is explicit, per-row and reported — the copy is the source of truth first.
            try:
                media_delete(row["id"])
                deleted += 1
            except Exception as exc:
                log.warn("delete failed", id=row.get("id"), error=str(exc))

    ctx.evidence.note("organized", f"copied {copied} image(s) into {len(folders)} month folder(s), skipped {skipped}, deleted {deleted}")
    return ctx.result("organized", {"copied": copied, "skipped": skipped, "deleted": deleted, "folders": folders})
