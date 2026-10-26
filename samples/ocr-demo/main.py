"""OCR Demo — the Gate 10-b reference task (plan §18 P10).

Reads a screenshot region with vision.ocr, demonstrates template_match and qr_decode, and
treats every result as what the tier contract says it is: a hint. Vision results are NEVER
used as proof of UI state and are never auto-attached to evidence (OCR text may contain
secrets that happen to be on screen — the script decides what to keep).
"""
from autohost import ctx, log, vision

def main():
    region = ctx.config.get("region") or {"left": 0, "top": 0, "width": 400, "height": 80}
    # 1. OCR a region of the current screen. The result carries hint:true on the wire.
    ocr = vision.ocr(region=region)
    lines = [line["text"] for line in ocr["lines"]]
    log.info("ocr done", engine=ocr["engine"], lines=len(lines), hint=ocr["hint"],
             duration_ms=ocr["duration_ms"])
    log.info("ocr text (run log only — not evidence)", text=" | ".join(lines[:10]))

    # 2. Template match a needle over the same region (pure NCC, not rotation/scale invariant —
    #    a documented hint). The needle ships in the task bundle; failures are honest.
    try:
        match = vision.template_match("needle.png", region=region, threshold=0.92)
        log.info("template match", found=match["found"], score=round(match["score"], 3),
                 box=match["box"], hint=match["hint"])
    except Exception as err:  # noqa: BLE001 — the demo reports engine refusals as hints too
        log.warn("template match unavailable", error=str(err)[:200])

    # 3. QR decode of the region (absent code = found:false, not an error).
    qr = vision.qr_decode(region=region)
    log.info("qr decode", found=qr["found"], value=qr.get("value"), hint=qr["hint"])

    return ctx.result("ok", {
        "hint": True,  # the T4 contract rides the run result too
        "ocr_lines": lines,
        "qr_found": qr["found"],
    })
