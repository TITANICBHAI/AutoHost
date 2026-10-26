'use strict';
/**
 * AutoHost sim JS shim (P11-03, ADR-077) — runs a JS task under node against the SAME prelude
 * the device runtime uses, talking the SAME bridge op table (JsBridge.kt / docs/SDK_JS.md).
 *
 * The shim is a dumb transport: it evaluates the prelude, evaluates the entry (ESM via node's
 * require-of-ESM and syntax detection; plain scripts via vm.runInThisContext), invokes
 * __autohost_run_main(), and forwards every `__autohost_bridge` frame to the Python host over
 * stdio (one JSON line per request, one JSON line per reply). The Python host
 * (autohost_sim/js_host.py) serves the ops — config, redaction, cancellation and outcome
 * classification all live there, exactly like execute_with_runtime does for Python tasks.
 *
 * Frame format: a single JSON array string `[op, payloadJson]` (the single-arg contract from
 * ADR-076 — the native QuickJS binding gets the same shape).
 *
 * Usage: node node_shim.cjs <prelude.js> <entry.js>
 * Final line on stdout: {"op":"done","outcome":{...}} — the standard run outcome object.
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');


const [, , preludePath, entryPath] = process.argv;
if (!preludePath || !entryPath) {
  failShim('usage: node node_shim.cjs <prelude.js> <entry.js>');
}

// --- blocking stdio line protocol -----------------------------------------------------------
const pending = [];
const NEWLINE = 0x0a;

function readLine() {
  for (;;) {
    const idx = pending.indexOf(NEWLINE);
    if (idx >= 0) {
      const bytes = pending.splice(0, idx + 1);
      bytes.pop();
      return Buffer.from(bytes).toString('utf8');
    }
    const buf = Buffer.alloc(65536);
    let n;
    try {
      n = fs.readSync(0, buf, 0, buf.length, null);
    } catch (e) {
      if (e.code === 'EAGAIN' || e.code === 'EINTR') continue;
      throw e;
    }
    if (n === 0) return pending.length ? Buffer.from(pending.splice(0)).toString('utf8') : null;
    for (let i = 0; i < n; i++) pending.push(buf[i]);
  }
}

function writeLine(obj) {
  fs.writeSync(1, JSON.stringify(obj) + '\n');
}

// The one native global the prelude needs. Replies flow back synchronously; the Python host
// owns cancellation (a CANCELLED error reply makes the prelude throw CancelledError).
globalThis.__autohost_bridge = function (frameJson) {
  let op = '';
  let payload = null;
  try {
    const frame = JSON.parse(frameJson);
    op = String(frame[0] || '');
    payload = frame.length > 1 ? frame[1] : null;
  } catch (e) {
    return JSON.stringify({ ok: false, error: { code: 'INTERNAL', message: 'bad bridge frame', retryable: false } });
  }
  writeLine({ op: op, payload: payload });
  const reply = readLine();
  return reply === null
    ? JSON.stringify({ ok: false, error: { code: 'INTERNAL', message: 'host closed the protocol', retryable: false } })
    : reply;
};

function failShim(message) {
  writeLine({
    op: 'done',
    outcome: { status: 'error', error: { kind: 'exception', message: 'AutoHostError: ' + message, stack: null } },
  });
  process.exit(0);
}

// --- evaluate prelude + entry, invoke main ---------------------------------------------------
let outcome;
try {
  const preludeSource = fs.readFileSync(preludePath, 'utf8');
  vm.runInThisContext(preludeSource, { filename: 'prelude.js' });

  const entryAbs = path.resolve(entryPath);
  if (!fs.existsSync(entryAbs)) {
    failShim('could not load entry: ' + entryPath);
  }
  const entrySource = fs.readFileSync(entryAbs, 'utf8');
  const ESM_RE = /(^|\n)\s*(import\s|export\s|import\(|import\.meta)/;
  if (ESM_RE.test(entrySource)) {
    // ESM entry: node 24 loads ESM through require() (synchronous, syntax-detected), and
    // relative './lib/*.js' imports resolve from the entry's own path — the same module graph
    // the device runtime serves through its module loader (ADR-076/077).
    const ns = require(entryAbs);
    globalThis.__autohost_exports = ns && typeof ns === 'object' ? ns : {};
  } else {
    // Plain-script entry: a top-level `function main()` lands on the shared global.
    vm.runInThisContext(entrySource, { filename: path.basename(entryAbs) });
  }
  outcome = JSON.parse(vm.runInThisContext('JSON.stringify(__autohost_run_main())', { filename: 'main-invoker' }));
} catch (e) {
  const name = e && e.name ? e.name : 'Error';
  const msg = e && e.message ? String(e.message) : String(e);
  outcome = { status: 'error', error: { kind: 'exception', message: name + ': ' + msg, stack: e && e.stack ? String(e.stack) : null } };
}

writeLine({ op: 'done', outcome: outcome });
process.exit(0);
