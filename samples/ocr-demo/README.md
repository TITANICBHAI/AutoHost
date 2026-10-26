# OCR Demo
The Gate 10-b reference: `vision.ocr` reads a screenshot region, `vision.template_match` and
`vision.qr_decode` round out the T4 capability. Every result carries `hint: true` — hints, not
proof; text is logged to the run (never auto-evidenced) because screens can contain secrets.
The optional `region` config scopes the read. `needle.png` is not shipped (drop a small crop of
your own screen into the bundle to exercise template match).
