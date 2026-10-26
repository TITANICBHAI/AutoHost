/**
 * AutoHost JS prelude (P11-01, ADR-076) — the client-side surface of the JavaScript runtime.
 *
 * This file is the SINGLE SOURCE for both hosts:
 *   - the device runtime loads it into QuickJS before the task entry (`:runtime:js` resource);
 *   - the simulator's node shim (`autohost_sim/js/node_shim.cjs`) runs the byte-identical copy
 *     in `autohost_sim/js/prelude.js` (a Python drift test pins the two together).
 *
 * Contract: the engine/host provides ONE global —
 *   __autohost_bridge(op, payloadJson) -> replyJson
 * with reply {"ok":true,"result":<json>} | {"ok":false,"error":{code,message,...}}. Ops are the
 * table in JsBridge.kt / docs/SDK_JS.md. The engine calls __autohost_run_main() after evaluating
 * the task entry; module-capable engines expose the entry's namespace as __autohost_exports.
 *
 * JS v1 surface (deliberately small and spec-locked — ADR-076):
 *   autohost.call(capability, method, args)   — the universal broker verb
 *   autohost.emit(event)                      — run events (redacted host-side)
 *   autohost.log.{debug,info,warn,error}(msg, data?)
 *   autohost.ctx.{config,profile,run,trigger,state(),save,secret,result,progress,now,
 *                 minutesSince,sleep,nextRun,evidence}
 *   autohost.sel.*                            — selector builders (port of the Python SDK sel.py)
 *   autohost.capabilities()                   — live availability describe()
 *   autohost.Result(code, data), autohost.errors.{AutoHostError,NeedsUser,Cancelled,Retryable}
 */
(() => {
  'use strict';

  // --- bridge ------------------------------------------------------------------------------
  function bridge(op, payload) {
    let reply;
    try {
      // Single-arg frame: [op, payloadJson] (keeps the native binding arity simple; ADR-076).
      reply = JSON.parse(__autohost_bridge(JSON.stringify([op, payload === undefined ? null : payload])));
    } catch (e) {
      throw new AutoHostError({ code: 'INTERNAL', message: 'bridge transport failed: ' + String(e) });
    }
    if (!reply || reply.ok === false) {
      const err = (reply && reply.error) || { code: 'INTERNAL', message: 'empty bridge reply' };
      if (err.code === 'CANCELLED') throw new CancelledError(err.message || 'run was cancelled');
      throw new AutoHostError(err);
    }
    return reply.result === undefined ? null : reply.result;
  }

  // --- errors ------------------------------------------------------------------------------
  class AutoHostError extends Error {
    constructor(err) {
      const e = err || {};
      super(e && e.message ? String(e.message) : 'AutoHostError');
      this.name = 'AutoHostError';
      this.code = (e && e.code) || 'INTERNAL';
      this.retryable = !!(e && e.retryable);
      this.reason = (e && e.reason) || null;
      this.fixAction = (e && e.fixAction) || null;
      this.details = (e && e.details) || null;
      this.__autohost_kind = 'autohost';
    }
  }
  class NeedsUser extends Error {
    constructor(reason, message, resumeHint) {
      super(String(message));
      this.name = 'NeedsUser';
      this.reason = String(reason);
      this.resume_hint = resumeHint == null ? null : String(resumeHint);
      this.__autohost_kind = 'needs_user';
    }
  }
  class CancelledError extends Error {
    constructor(message) {
      super(String(message));
      this.name = 'Cancelled';
      this.__autohost_kind = 'cancelled';
    }
  }
  class Retryable extends AutoHostError {
    constructor(message) {
      super({ code: 'INTERNAL', message: String(message), retryable: true });
      this.name = 'Retryable';
    }
  }

  // --- Result ------------------------------------------------------------------------------
  class Result {
    constructor(code, data) {
      this.code = String(code);
      this.data = data || {};
      this.__autohost_result__ = true;
    }
  }

  // --- selectors (port of the Python SDK's sel.py) ------------------------------------------
  function combine(op, a, b) {
    return { op: op, terms: [{ sel: toPlain(a) }, { sel: toPlain(b) }] };
  }
  function toPlain(s) {
    if (s && s.__autohost_selector__) return JSON.parse(JSON.stringify(s.json));
    if (s && typeof s === 'object' && s.op && s.terms) return JSON.parse(JSON.stringify(s));
    throw new Error('expected a selector built with autohost.sel');
  }
  function match(kind, labels, mode, caseSensitive) {
    if (!labels || !labels.length) throw new Error('at least one label is required');
    const spec = { labels: labels.map(String), mode: mode, case_sensitive: !!caseSensitive };
    return { op: 'and', terms: [{ [kind]: spec }] };
  }
  function state(name, value) {
    const t = {};
    t[name] = !!value;
    return { op: 'and', terms: [{ state: t }] };
  }
  function relation(kind, other) {
    return { op: 'and', terms: [{ relation: { kind: kind, selector: toPlain(other) } }] };
  }
  const sel = {
    text(...labels) {
      const opts = labels.length && typeof labels[labels.length - 1] === 'object' && labels[labels.length - 1] !== null && !Array.isArray(labels[labels.length - 1])
        ? labels.pop() : {};
      return wrap(match('text', labels, opts.mode || 'contains', opts.case_sensitive || false));
    },
    desc(...labels) {
      const opts = labels.length && typeof labels[labels.length - 1] === 'object' && labels[labels.length - 1] !== null && !Array.isArray(labels[labels.length - 1])
        ? labels.pop() : {};
      return wrap(match('desc', labels, opts.mode || 'contains', opts.case_sensitive || false));
    },
    id(...ids) {
      const opts = ids.length && typeof ids[ids.length - 1] === 'object' && ids[ids.length - 1] !== null && !Array.isArray(ids[ids.length - 1])
        ? ids.pop() : {};
      return wrap(match('id', ids, opts.mode || 'eq', true));
    },
    cls(...names) {
      const opts = names.length && typeof names[names.length - 1] === 'object' && names[names.length - 1] !== null && !Array.isArray(names[names.length - 1])
        ? names.pop() : {};
      return wrap(match('class', names, opts.mode || 'eq', true));
    },
    pkg(...names) { return wrap(match('pkg', names, 'eq', true)); },
    clickable(value) { return wrap(state('clickable', value === undefined ? true : value)); },
    enabled(value) { return wrap(state('enabled', value === undefined ? true : value)); },
    checked(value) { return wrap(state('checked', value === undefined ? true : value)); },
    selected(value) { return wrap(state('selected', value === undefined ? true : value)); },
    focused(value) { return wrap(state('focused', value === undefined ? true : value)); },
    editable(value) { return wrap(state('editable', value === undefined ? true : value)); },
    scrollable(value) { return wrap(state('scrollable', value === undefined ? true : value)); },
    visible(value) { return wrap(state('visible', value === undefined ? true : value)); },
    boundsInside(left, top, right, bottom) {
      return wrap({ op: 'and', terms: [{ bounds: { left: left | 0, top: top | 0, right: right | 0, bottom: bottom | 0 } }] });
    },
    boundsOverlaps(left, top, right, bottom) {
      return wrap({ op: 'and', terms: [{ bounds: { left: left | 0, top: top | 0, right: right | 0, bottom: bottom | 0, mode: 'overlaps' } }] });
    },
    depth(level) { return wrap({ op: 'and', terms: [{ depth: level | 0 }] }); },
    indexInParent(position) { return wrap({ op: 'and', terms: [{ index_in_parent: position | 0 }] }); },
    drawingOrder(z) { return wrap({ op: 'and', terms: [{ drawing_order: z | 0 }] }); },
    indexAt(other, n) {
      return wrap({ op: 'and', terms: [{ relation: { kind: 'index', selector: toPlain(other), n: n === undefined ? 0 : n | 0 } }] });
    },
    below(other) { return wrap(relation('below', other)); },
    above(other) { return wrap(relation('above', other)); },
    leftOf(other) { return wrap(relation('left_of', other)); },
    rightOf(other) { return wrap(relation('right_of', other)); },
    childOf(other) { return wrap(relation('child_of', other)); },
    containsChild(other) { return wrap(relation('contains_child', other)); },
    containsDescendants(...others) {
      return wrap({
        op: 'and',
        terms: others.map((o) => ({ relation: { kind: 'contains_descendants', selector: toPlain(o) } })),
      });
    },
    and(a, b) { return wrap(combine('and', a, b)); },
    or(a, b) { return wrap(combine('or', a, b)); },
    within(parent) {
      const plain = toPlain(this);
      plain.within = toPlain(parent);
      return wrap(plain);
    },
    nth(index) {
      const plain = toPlain(this);
      plain.nth = index | 0;
      return wrap(plain);
    },
    bfs() {
      const plain = toPlain(this);
      plain.algorithm = 'bfs';
      return wrap(plain);
    },
  };
  function wrap(plain) {
    return { __autohost_selector__: true, json: plain, to_json() { return JSON.parse(JSON.stringify(plain)); }, within: sel.within, nth: sel.nth, bfs: sel.bfs };
  }

  // --- KV stores (the port of the Python SDK's ctx.profile_store / ctx.task_store) -----------
  function kv(scope) {
    return {
      get(key, dflt) {
        const res = bridge('call', { capability: 'store', method: 'get', args: { scope: scope, key: key } });
        return res && Object.prototype.hasOwnProperty.call(res, 'value') ? res.value : dflt;
      },
      set(key, value) {
        bridge('call', { capability: 'store', method: 'set', args: { scope: scope, key: key, value: value } });
      },
      delete(key) {
        bridge('call', { capability: 'store', method: 'delete', args: { scope: scope, key: key } });
      },
      keys(prefix) {
        const res = bridge('call', { capability: 'store', method: 'list', args: { scope: scope, prefix: prefix || '' } });
        return (res && res.keys) || [];
      },
    };
  }

  // --- emit + ctx ---------------------------------------------------------------------------------
  function emit(event) { return bridge('emit', event || {}); }
  let lastStateSnapshot = null;
  function nowIso() {
    const r = bridge('clock', {});
    return r && r.now_iso ? r.now_iso : null;
  }
  const ctx = {
    get config() { return bridge('ctx', { what: 'config' }) || {}; },
    get profile() { return bridge('ctx', { what: 'profile' }) || {}; },
    get run() { return bridge('ctx', { what: 'run' }) || {}; },
    get trigger() { return bridge('ctx', { what: 'trigger' }) || {}; },
    /** JS gets a snapshot (Python shares the live dict): mutate the copy, then ctx.save(copy). */
    state() {
      lastStateSnapshot = JSON.parse(JSON.stringify(bridge('ctx', { what: 'state' }) || {}));
      return lastStateSnapshot;
    },
    save(state) {
      return bridge('state_save', state === undefined ? (lastStateSnapshot || {}) : state);
    },
    secret(name) { return bridge('secret', { name: name }); },
    profileStore: kv('profile'),
    taskStore: kv('task'),
    result(code, data) {
      bridge('result', { code: code, data: data || {} });
      return new Result(code, data);
    },
    progress(fraction, message) {
      emit({ type: 'progress', message: String(message === undefined ? '' : message), data: { fraction: Number(fraction) } });
    },
    now: nowIso,
    minutesSince(iso) {
      const now = Date.parse(nowIso() || '');
      const then = Date.parse(String(iso));
      if (Number.isNaN(now) || Number.isNaN(then)) throw new Error('minutes_since needs ISO timestamps');
      return (now - then) / 60000.0;
    },
    sleep(seconds) { bridge('sleep', { seconds: Number(seconds) }); },
    nextRun(opts) {
      const o = opts || {};
      if ((o.afterMinutes === undefined) === (o.at === undefined)) {
        throw new AutoHostError({ code: 'INVALID_ARGS', message: 'give exactly one of afterMinutes or at' });
      }
      const payload = {};
      if (o.afterMinutes !== undefined) payload.after_minutes = o.afterMinutes | 0;
      if (o.at !== undefined) payload.at = String(o.at);
      bridge('next_run', payload);
    },
    evidence: {
      note(tag, text) {
        emit({ type: 'evidence', data: { kind: 'note', tag: String(tag), text: String(text) } });
      },
      screenshot(tag) {
        let res;
        try {
          res = bridge('call', { capability: 'device.screenshot', method: 'capture', args: { tag: tag } });
        } catch (e) {
          if (e && e.__autohost_kind === 'cancelled') throw e;
          emit({ type: 'log', level: 'warn', message: 'screenshot unavailable', data: { tag: tag, code: e && e.code ? e.code : 'INTERNAL' } });
          return null;
        }
        const path = res && res.path ? res.path : null;
        emit({ type: 'evidence', data: { kind: 'screenshot', tag: tag, path: path } });
        return path;
      },
      uiDump(tag) {
        const res = bridge('call', { capability: 'device.ui', method: 'dump', args: { compact: true } });
        const text = res && res.text ? res.text : '';
        emit({ type: 'evidence', data: { kind: 'ui_dump', tag: tag, text: text } });
        return text;
      },
    },
  };

  // --- log ----------------------------------------------------------------------------------
  function logAt(level, msg, data) {
    emit({ type: 'log', level: level, message: String(msg), data: data || {} });
  }
  const log = {
    debug(msg, data) { logAt('debug', msg, data); },
    info(msg, data) { logAt('info', msg, data); },
    warn(msg, data) { logAt('warn', msg, data); },
    error(msg, data) { logAt('error', msg, data); },
  };

  // --- the autohost namespace ----------------------------------------------------------------
  const autohost = {
    call(capability, method, args) {
      return bridge('call', { capability: capability, method: method, args: args || {} });
    },
    emit(event) { return emit(event || {}); },
    capabilities() { return bridge('capabilities', {}) || {}; },
    ctx: ctx,
    log: log,
    sel: sel,
    Result: Result,
    errors: { AutoHostError: AutoHostError, NeedsUser: NeedsUser, Cancelled: CancelledError, Retryable: Retryable },
  };

  globalThis.autohost = autohost;
  globalThis.__autohost_run_main = __autohost_run_main;

  // --- main() invocation and outcome serialisation -------------------------------------------
  function resolveMain() {
    if (typeof __autohost_exports !== 'undefined' && __autohost_exports && typeof __autohost_exports.main === 'function') {
      return __autohost_exports.main;
    }
    if (typeof main === 'function') return main;
    return null;
  }
  function serializeError(e) {
    if (e && e.__autohost_kind) {
      const out = { kind: e.__autohost_kind, message: e && e.message ? String(e.message) : '' };
      if (e.__autohost_kind === 'autohost' || e.__autohost_kind === 'needs_user') {
        if (e.code) out.code = e.code;
        if (e.reason) out.reason = e.reason;
      }
      if (e.__autohost_kind === 'needs_user' && e.resume_hint) out.resume_hint = e.resume_hint;
      if (e && e.details) out.details = e.details;
      return out;
    }
    const name = e && e.name ? String(e.name) : 'Error';
    const msg = e && e.message !== undefined ? String(e.message) : String(e);
    return { kind: 'exception', message: name + ': ' + msg, stack: e && e.stack ? String(e.stack) : null };
  }
  function __autohost_run_main() {
    try {
      const fn = resolveMain();
      if (!fn) {
        return {
          status: 'error',
          error: {
            kind: 'entry',
            message: "no main() found - a JS task must define main, e.g. `export function main() { ... }`",
          },
        };
      }
      const v = fn();
      if (v && v.__autohost_result__) {
        return { status: 'ok', value: { __result__: { code: v.code, data: v.data } } };
      }
      return { status: 'ok', value: v === undefined ? null : v };
    } catch (e) {
      if (e && e.__autohost_kind === 'cancelled') {
        return { status: 'error', error: { kind: 'cancelled', message: e && e.message ? String(e.message) : 'run was cancelled' } };
      }
      return { status: 'error', error: serializeError(e) };
    }
  }
})();
