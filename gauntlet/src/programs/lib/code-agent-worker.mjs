// Gauntlet "Fix the Bug" project sandbox worker.
//
// Runs a small in-memory CommonJS project against its test files. Started by
// src/programs/lib/code-agent-sandbox.ts in a separate Node process with the
// permission model (read access to this file only; no file writes, no child
// processes, no workers, no addons), code generation from strings disabled,
// a small heap and an empty environment.
//
// Every test file runs in a fresh VM context with its own module registry.
// Project code can only `require` other project files plus two shims
// (node:assert/strict and, from test files only, node:test). Nothing from the
// host realm is reachable from project code: the bridge functions below are
// captured in a closure by the bootstrap and only primitives cross the
// boundary. Assertions and the test runner capture the built-ins they use
// before any project code runs, so project code cannot make a failing test
// pass by patching Object.is, Promise.prototype.then or the assert module.
//
// Input (stdin, JSON): { files: {path: source}, run: [testPath], perTestTimeoutMs, maxLogChars }
// Output (stdout, JSON): { results: [{ file, loadError, tests: [{ name, ok, error, ms }] }], log }
import vm from 'node:vm';

const input = await new Promise((resolve) => {
  let data = '';
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', (c) => (data += c));
  process.stdin.on('end', () => resolve(JSON.parse(data)));
});

process.on('unhandledRejection', () => {});
process.on('uncaughtException', (e) => appendLog(`[uncaught] ${String(e && e.message ? e.message : e).slice(0, 300)}`));

const files = input.files || {};
const perTestTimeoutMs = Math.max(50, Math.min(10000, Number(input.perTestTimeoutMs) || 1500));
const maxLogChars = Math.max(0, Number(input.maxLogChars) || 3000);
let log = '';
function appendLog(s) {
  if (log.length >= maxLogChars) return;
  log += s + '\n';
  if (log.length > maxLogChars) log = log.slice(0, maxLogChars) + '…';
}

// The bootstrap runs inside each fresh context before any project code.
const BOOTSTRAP = String.raw`(function (host, KEY) {
  'use strict';
  // ── Captured primordials (project code may patch the globals later) ──
  const O = Object, A = Array, S = String, N = Number, J = JSON, P = Promise, R = Reflect, E = Error, D = Date, M = Map, SetC = Set, RX = RegExp;
  const is = O.is, keys = O.keys, getProto = O.getPrototypeOf, freeze = O.freeze, defineProp = O.defineProperty, hasOwn = O.prototype.hasOwnProperty;
  const isArray = A.isArray, apply = R.apply, thenFn = P.prototype.then, jsonStringify = J.stringify, jsonParse = J.parse;
  const dateGetTime = D.prototype.getTime, dateToISO = D.prototype.toISOString, rxTest = RX.prototype.test, mapGet = M.prototype.get, mapSet = M.prototype.set, mapHas = M.prototype.has;
  const mapEntries = M.prototype.entries, setValues = SetC.prototype.values, mapSize = O.getOwnPropertyDescriptor(M.prototype, 'size').get, setSize = O.getOwnPropertyDescriptor(SetC.prototype, 'size').get;
  const hostCompile = host.compile, hostExists = host.exists, hostSource = host.source, hostLog = host.log, hostSetTimer = host.setTimer, hostClearTimer = host.clearTimer, hostDone = host.done;
  const strSlice = S.prototype.slice, strStarts = S.prototype.startsWith, strSplit = S.prototype.split, strIndexOf = S.prototype.indexOf, strEnds = S.prototype.endsWith;
  const call = (fn, self, ...args) => apply(fn, self, args);
  const own = (o, k) => call(hasOwn, o, k);
  const isDate = (v) => { try { call(dateGetTime, v); return true; } catch { return false; } };
  const isMap = (v) => { try { call(mapSize, v); return true; } catch { return false; } };
  const isSet = (v) => { try { call(setSize, v); return true; } catch { return false; } };
  const isRegExp = (v) => v instanceof RX;

  // ── inspect: short, deterministic value rendering for messages / console ──
  function inspect(v, depth) {
    depth = depth || 0;
    if (v === undefined) return 'undefined';
    if (v === null) return 'null';
    const t = typeof v;
    if (t === 'string') return depth === 0 ? jsonStringify(v) : jsonStringify(v);
    if (t === 'number') return is(v, -0) ? '-0' : S(v);
    if (t === 'bigint') return S(v) + 'n';
    if (t === 'boolean' || t === 'symbol') return S(v);
    if (t === 'function') return '[Function' + (v.name ? ': ' + v.name : '') + ']';
    if (depth > 3) return isArray(v) ? '[Array]' : '[Object]';
    if (isDate(v)) { try { return 'Date(' + call(dateToISO, v) + ')'; } catch { return 'Date(Invalid)'; } }
    if (isRegExp(v)) return S(v);
    if (v instanceof E) return (v.name || 'Error') + ': ' + v.message;
    if (isMap(v)) {
      const parts = [];
      const it = call(mapEntries, v);
      for (let r = it.next(); !r.done; r = it.next()) parts[parts.length] = inspect(r.value[0], depth + 1) + ' => ' + inspect(r.value[1], depth + 1);
      return 'Map(' + parts.length + ') {' + (parts.length ? ' ' + parts.join(', ') + ' ' : '') + '}';
    }
    if (isSet(v)) {
      const parts = [];
      const it = call(setValues, v);
      for (let r = it.next(); !r.done; r = it.next()) parts[parts.length] = inspect(r.value, depth + 1);
      return 'Set(' + parts.length + ') {' + (parts.length ? ' ' + parts.join(', ') + ' ' : '') + '}';
    }
    if (isArray(v)) {
      const parts = [];
      for (let i = 0; i < v.length && i < 30; i++) parts[parts.length] = inspect(v[i], depth + 1);
      if (v.length > 30) parts[parts.length] = '… ' + (v.length - 30) + ' more';
      return '[' + parts.join(', ') + ']';
    }
    const ks = keys(v);
    const parts = [];
    for (let i = 0; i < ks.length && i < 30; i++) {
      const k = ks[i];
      parts[parts.length] = (/^[A-Za-z_$][\w$]*$/.test(k) ? k : jsonStringify(k)) + ': ' + inspect(v[k], depth + 1);
    }
    if (ks.length > 30) parts[parts.length] = '…';
    return '{' + (parts.length ? ' ' + parts.join(', ') + ' ' : '') + '}';
  }
  function short(v) {
    const s = inspect(v);
    return s.length > 400 ? call(strSlice, s, 0, 397) + '…' : s;
  }

  // ── assert ──
  class AssertionError extends E {
    constructor(message, actual, expected, operator) {
      super(message);
      this.name = 'AssertionError';
      this.code = 'ERR_ASSERTION';
      this.actual = actual;
      this.expected = expected;
      this.operator = operator;
    }
  }
  function deepEq(a, b, strict, seen) {
    if (strict ? is(a, b) : (a == b || (a !== a && b !== b))) return true;
    if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return false;
    if (strict && getProto(a) !== getProto(b)) return false;
    if (isArray(a) !== isArray(b)) return false;
    if (isDate(a) || isDate(b)) return isDate(a) && isDate(b) && is(call(dateGetTime, a), call(dateGetTime, b));
    if (isRegExp(a) || isRegExp(b)) return isRegExp(a) && isRegExp(b) && S(a) === S(b);
    if (seen.length > 200) return false;
    for (let i = 0; i < seen.length; i++) if (seen[i][0] === a && seen[i][1] === b) return true;
    seen[seen.length] = [a, b];
    if (isMap(a) || isMap(b)) {
      if (!(isMap(a) && isMap(b)) || call(mapSize, a) !== call(mapSize, b)) return false;
      const it = call(mapEntries, a);
      for (let r = it.next(); !r.done; r = it.next()) {
        if (!call(mapHas, b, r.value[0]) || !deepEq(r.value[1], call(mapGet, b, r.value[0]), strict, seen)) return false;
      }
      return true;
    }
    if (isSet(a) || isSet(b)) {
      if (!(isSet(a) && isSet(b)) || call(setSize, a) !== call(setSize, b)) return false;
      const it = call(setValues, a);
      for (let r = it.next(); !r.done; r = it.next()) if (!b.has(r.value)) return false;
      return true;
    }
    const ka = keys(a), kb = keys(b);
    if (ka.length !== kb.length) return false;
    for (let i = 0; i < ka.length; i++) {
      const k = ka[i];
      if (!own(b, k) || !deepEq(a[k], b[k], strict, seen)) return false;
    }
    return true;
  }
  function fail2(message, actual, expected, operator, defaultMsg) {
    if (message instanceof E) throw message;
    throw new AssertionError(message !== undefined ? S(message) : defaultMsg + '\n  actual:   ' + short(actual) + '\n  expected: ' + short(expected), actual, expected, operator);
  }
  function matchesExpected(err, expected) {
    if (expected === undefined) return true;
    if (isRegExp(expected)) return call(rxTest, expected, S(err && err.message !== undefined ? err.message : err)) || call(rxTest, expected, S(err));
    if (typeof expected === 'function') {
      if (expected.prototype !== undefined && err instanceof expected) return true;
      if (E === expected || (expected.prototype && expected.prototype instanceof E)) return false;
      return expected(err) === true;
    }
    if (expected && typeof expected === 'object') {
      const ks = keys(expected);
      for (let i = 0; i < ks.length; i++) {
        const k = ks[i];
        const want = expected[k];
        const got = err == null ? undefined : err[k];
        if (isRegExp(want) && typeof got === 'string') { if (!call(rxTest, want, got)) return false; }
        else if (!deepEq(got, want, true, [])) return false;
      }
      return true;
    }
    return false;
  }
  function makeAssert(strictMode) {
    const assert = function ok(value, message) { if (!value) fail2(message, value, true, '==', 'The expression evaluated to a falsy value'); };
    assert.ok = function ok(value, message) { if (!value) fail2(message, value, true, '==', 'The expression evaluated to a falsy value'); };
    assert.equal = function equal(a, b, message) {
      if (strictMode ? !is(a, b) : !(a == b || (a !== a && b !== b))) fail2(message, a, b, strictMode ? 'strictEqual' : '==', strictMode ? 'Expected values to be strictly equal:' : 'Expected values to be loosely equal:');
    };
    assert.notEqual = function notEqual(a, b, message) {
      if (strictMode ? is(a, b) : a == b) fail2(message, a, b, 'notStrictEqual', 'Expected "actual" to be strictly unequal to:');
    };
    assert.strictEqual = function strictEqual(a, b, message) { if (!is(a, b)) fail2(message, a, b, 'strictEqual', 'Expected values to be strictly equal:'); };
    assert.notStrictEqual = function notStrictEqual(a, b, message) { if (is(a, b)) fail2(message, a, b, 'notStrictEqual', 'Expected "actual" to be strictly unequal to:'); };
    assert.deepEqual = function deepEqual(a, b, message) { if (!deepEq(a, b, strictMode, [])) fail2(message, a, b, 'deepStrictEqual', 'Expected values to be deep-equal:'); };
    assert.deepStrictEqual = function deepStrictEqual(a, b, message) { if (!deepEq(a, b, true, [])) fail2(message, a, b, 'deepStrictEqual', 'Expected values to be strictly deep-equal:'); };
    assert.notDeepStrictEqual = function notDeepStrictEqual(a, b, message) { if (deepEq(a, b, true, [])) fail2(message, a, b, 'notDeepStrictEqual', 'Expected "actual" not to be strictly deep-equal to:'); };
    assert.notDeepEqual = assert.notDeepStrictEqual;
    assert.match = function match(s, re, message) { if (typeof s !== 'string' || !call(rxTest, re, s)) fail2(message, s, re, 'match', 'The input did not match the regular expression ' + S(re) + ':'); };
    assert.doesNotMatch = function doesNotMatch(s, re, message) { if (typeof s !== 'string' || call(rxTest, re, s)) fail2(message, s, re, 'doesNotMatch', 'The input was expected to not match ' + S(re) + ':'); };
    assert.fail = function fail(message) { if (message instanceof E) throw message; throw new AssertionError(message === undefined ? 'Failed' : S(message), undefined, undefined, 'fail'); };
    assert.throws = function throws(fn, expected, message) {
      if (typeof expected === 'string') { message = expected; expected = undefined; }
      let threw = false, err;
      try { fn(); } catch (e) { threw = true; err = e; }
      if (!threw) throw new AssertionError(message !== undefined ? S(message) : 'Missing expected exception.', undefined, expected, 'throws');
      if (!matchesExpected(err, expected)) throw new AssertionError(message !== undefined ? S(message) : 'The error thrown did not match the expected one.\n  thrown:   ' + short(err) + '\n  expected: ' + short(expected), err, expected, 'throws');
    };
    assert.doesNotThrow = function doesNotThrow(fn, message) {
      try { fn(); } catch (e) { throw new AssertionError(typeof message === 'string' ? message : 'Got unwanted exception: ' + short(e), e, undefined, 'doesNotThrow'); }
    };
    assert.rejects = async function rejects(p, expected, message) {
      if (typeof expected === 'string') { message = expected; expected = undefined; }
      let threw = false, err;
      try { await (typeof p === 'function' ? p() : p); } catch (e) { threw = true; err = e; }
      if (!threw) throw new AssertionError(message !== undefined ? S(message) : 'Missing expected rejection.', undefined, expected, 'rejects');
      if (!matchesExpected(err, expected)) throw new AssertionError(message !== undefined ? S(message) : 'The rejection did not match the expected one.\n  rejected: ' + short(err) + '\n  expected: ' + short(expected), err, expected, 'rejects');
    };
    assert.doesNotReject = async function doesNotReject(p, message) {
      try { await (typeof p === 'function' ? p() : p); } catch (e) { throw new AssertionError(typeof message === 'string' ? message : 'Got unwanted rejection: ' + short(e), e, undefined, 'doesNotReject'); }
    };
    assert.AssertionError = AssertionError;
    assert.strict = assert;
    return freeze(assert);
  }
  const strictAssert = makeAssert(true);
  const looseAssert = makeAssert(false);

  // ── node:test shim ──
  const tests = [];
  const prefix = [];
  const hooks = [[]];
  function register(name, a, b) {
    const fn = typeof a === 'function' ? a : b;
    const opts = typeof a === 'object' && a !== null ? a : {};
    if (typeof fn !== 'function' || opts.skip || opts.todo) return;
    const scoped = [];
    for (let i = 0; i < hooks.length; i++) for (let j = 0; j < hooks[i].length; j++) scoped[scoped.length] = hooks[i][j];
    tests[tests.length] = { name: prefix.concat([S(name)]).join(' › '), fn: fn, before: scoped };
  }
  function describe(name, a, b) {
    const fn = typeof a === 'function' ? a : b;
    if (typeof fn !== 'function') return;
    prefix[prefix.length] = S(name);
    hooks[hooks.length] = [];
    try { fn(); } finally { prefix.length -= 1; hooks.length -= 1; }
  }
  const testFn = function test(name, a, b) { register(name, a, b); };
  testFn.test = testFn;
  testFn.it = testFn;
  testFn.describe = describe;
  testFn.suite = describe;
  testFn.beforeEach = function beforeEach(fn) { if (typeof fn === 'function') { const h = hooks[hooks.length - 1]; h[h.length] = fn; } };
  testFn.skip = function skip() {};
  testFn.todo = function todo() {};
  testFn.only = testFn;
  freeze(testFn);

  // ── console → host log (strings only) ──
  function fmt(args) {
    const parts = [];
    for (let i = 0; i < args.length; i++) parts[parts.length] = typeof args[i] === 'string' ? args[i] : inspect(args[i]);
    return parts.join(' ');
  }
  const consoleObj = freeze({
    log: function log() { hostLog(fmt(arguments)); },
    info: function info() { hostLog(fmt(arguments)); },
    debug: function debug() { hostLog(fmt(arguments)); },
    warn: function warn() { hostLog(fmt(arguments)); },
    error: function error() { hostLog(fmt(arguments)); },
    table: function table(v) { hostLog(inspect(v)); },
  });

  // ── timers (host keeps only numeric ids) ──
  const timers = new M();
  function setTimeout_(fn, ms) {
    if (typeof fn !== 'function') return 0;
    const extra = [];
    for (let i = 2; i < arguments.length; i++) extra[extra.length] = arguments[i];
    const id = hostSetTimer(N(ms) || 0);
    call(mapSet, timers, id, function () { apply(fn, undefined, extra); });
    return id;
  }
  function clearTimeout_(id) {
    if (call(mapHas, timers, id)) { timers.delete(id); hostClearTimer(N(id)); }
  }
  globalThis.setTimeout = setTimeout_;
  globalThis.clearTimeout = clearTimeout_;
  globalThis.setImmediate = function setImmediate(fn) { return setTimeout_.apply(undefined, [fn, 0].concat(A.prototype.slice.call(arguments, 1))); };
  globalThis.clearImmediate = clearTimeout_;
  globalThis.queueMicrotask = function queueMicrotask(fn) { call(thenFn, P.resolve(), function () { fn(); }); };
  globalThis.console = consoleObj;
  globalThis.structuredClone = function structuredClone(v) { return v === undefined ? undefined : jsonParse(jsonStringify(v)); };

  // ── CommonJS loader over the in-memory project ──
  const cache = new M();
  const BLOCKED = 'is not available in the Gauntlet sandbox (no file system, network, processes or timers beyond setTimeout). Only project files, node:assert and node:test can be required.';
  function normalize(p) {
    const out = [];
    const parts = call(strSplit, p, '/');
    for (let i = 0; i < parts.length; i++) {
      const s = parts[i];
      if (s === '' || s === '.') continue;
      if (s === '..') { if (out.length) out.length -= 1; continue; }
      out[out.length] = s;
    }
    return out.join('/');
  }
  function dirOf(p) {
    const i = p.lastIndexOf('/');
    return i < 0 ? '' : call(strSlice, p, 0, i);
  }
  function isTestPath(p) { return call(strStarts, p, 'tests/') || call(strStarts, p, '__hidden__/'); }
  function resolvePath(from, id) {
    const base = normalize((dirOf(from) ? dirOf(from) + '/' : '') + id);
    const candidates = [base, base + '.js', base + '.json', base + '/index.js'];
    for (let i = 0; i < candidates.length; i++) if (hostExists(candidates[i])) return candidates[i];
    return null;
  }
  function makeRequire(from) {
    return function require(id) {
      id = S(id);
      if (id === 'node:test' || id === 'test') {
        if (!isTestPath(from)) throw new E('Cannot require "' + id + '" from ' + from + ': the test runner is only available to test files.');
        return testFn;
      }
      if (id === 'assert/strict' || id === 'node:assert/strict') return strictAssert;
      if (id === 'assert' || id === 'node:assert') return looseAssert;
      if (!(call(strStarts, id, './') || call(strStarts, id, '../') || call(strStarts, id, '/'))) throw new E('Module "' + id + '" ' + BLOCKED);
      const path = resolvePath(from, call(strStarts, id, '/') ? '.' + id : id);
      if (path === null) {
        const err = new E('Cannot find module \'' + id + '\' (required from ' + from + ')');
        err.code = 'MODULE_NOT_FOUND';
        throw err;
      }
      if (isTestPath(path) && !isTestPath(from)) throw new E('Cannot require test file ' + path + ' from ' + from + '.');
      return load(path);
    };
  }
  function load(path) {
    if (call(mapHas, cache, path)) return call(mapGet, cache, path).exports;
    const module = { exports: {}, id: path, filename: path, loaded: false };
    call(mapSet, cache, path, module);
    if (call(strEnds, path, '.json')) {
      try { module.exports = jsonParse(hostSource(path)); } catch (e) { cache.delete(path); throw new E('Invalid JSON in ' + path + ': ' + e.message); }
      return module.exports;
    }
    const wrapper = hostCompile(path);
    if (typeof wrapper === 'string') { cache.delete(path); const err = new SyntaxError(wrapper); throw err; }
    try {
      call(wrapper, module.exports, module.exports, makeRequire(path), module, path, dirOf(path));
    } catch (e) {
      cache.delete(path);
      throw e;
    }
    module.loaded = true;
    return module.exports;
  }

  function errorText(e) {
    if (e === undefined) return 'undefined was thrown';
    if (e === null || (typeof e !== 'object' && typeof e !== 'function')) return 'Thrown: ' + short(e);
    let msg = '';
    try { msg = (e.name ? e.name + ': ' : '') + S(e.message); } catch { msg = 'Error'; }
    let where = [];
    try {
      const lines = call(strSplit, S(e.stack || ''), '\n');
      for (let i = 1; i < lines.length && where.length < 3; i++) {
        const l = lines[i].trim();
        if (/\((src|lib|tests|__hidden__)\//.test(l) || /at (src|lib|tests)\//.test(l) || /\s(src|lib)\/[\w./-]+:\d+/.test(l)) where[where.length] = l;
      }
    } catch {}
    return (msg + (where.length ? '\n    ' + where.join('\n    ') : '')).slice(0, 1200);
  }

  let current = -1;
  const api = {
    load(path) {
      try { load(path); return ''; } catch (e) { return errorText(e); }
    },
    count() { return tests.length; },
    name(i) { return tests[i] ? tests[i].name : ''; },
    run(i) {
      current = i;
      const t = tests[i];
      const finish = (ok, err) => { if (current === i) { current = -1; hostDone(i, ok, ok ? '' : errorText(err)); } };
      const isThenable = (v) => v !== null && (typeof v === 'object' || typeof v === 'function') && typeof v.then === 'function';
      // Hooks and the test body run synchronously where possible, so the
      // synchronous part executes inside this call's VM timeout.
      const step = (k) => {
        for (; k < t.before.length; k++) {
          const hr = t.before[k]();
          if (isThenable(hr)) { const next = k + 1; return call(thenFn, P.resolve(hr), function () { return step(next); }); }
        }
        return t.fn({ name: t.name, diagnostic() {}, skip() {}, todo() {} });
      };
      try {
        const r = step(0);
        if (isThenable(r)) call(thenFn, P.resolve(r), function () { finish(true); }, function (e) { finish(false, e); });
        else finish(true);
      } catch (e) {
        finish(false, e);
      }
    },
    fire(id) {
      const fn = call(mapGet, timers, id);
      if (fn === undefined) return;
      timers.delete(id);
      try { fn(); } catch (e) { hostLog('[timer] ' + errorText(e)); }
    },
  };
  defineProp(globalThis, KEY, { value: freeze(api), enumerable: false, configurable: false, writable: false });
})`;

function randomKey() {
  return '__gauntlet_' + Math.random().toString(36).slice(2, 10) + Date.now().toString(36);
}

async function runTestFile(file) {
  const out = { file, loadError: null, tests: [] };
  // afterEvaluate: the context's promise jobs drain at the end of each evaluation, inside its timeout,
  // so an endless loop after an `await` is caught like a synchronous one.
  const context = vm.createContext({}, { codeGeneration: { strings: false, wasm: false }, microtaskMode: 'afterEvaluate' });
  const KEY = randomKey();
  const timers = new Map();
  let nextTimer = 1;
  let doneCb = null;
  const runIn = (code, timeout = perTestTimeoutMs) => new vm.Script(code).runInContext(context, { timeout });
  const host = {
    compile(path) {
      if (typeof files[path] !== 'string') return `Cannot find module '${path}'`;
      try {
        return new vm.Script(`(function (exports, require, module, __filename, __dirname) {${files[path]}\n})`, { filename: path }).runInContext(context);
      } catch (e) {
        return `${path}: ${String(e && e.message ? e.message : e)}${e && e.stack ? ' ' + (String(e.stack).split('\n').find((l) => l.includes(path)) ?? '') : ''}`.slice(0, 600);
      }
    },
    exists(path) {
      return typeof files[String(path)] === 'string';
    },
    source(path) {
      return String(files[String(path)] ?? '');
    },
    log(s) {
      appendLog(String(s).slice(0, 500));
    },
    setTimer(ms) {
      const id = nextTimer++;
      const handle = setTimeout(() => {
        timers.delete(id);
        try {
          runIn(`${KEY}.fire(${id})`, 1000);
        } catch (e) {
          appendLog(`[timer] ${String(e && e.message ? e.message : e).slice(0, 200)}`);
        }
      }, Math.max(0, Math.min(5000, Number(ms) || 0)));
      timers.set(id, handle);
      return id;
    },
    clearTimer(id) {
      const h = timers.get(Number(id));
      if (h) clearTimeout(h);
      timers.delete(Number(id));
    },
    done(i, ok, err) {
      if (doneCb) doneCb(Number(i), ok === true, String(err ?? ''));
    },
  };
  // Hand the bridge to the bootstrap, which captures it in a closure; it is never a global.
  const boot = new vm.Script(BOOTSTRAP, { filename: 'gauntlet-bootstrap.js' }).runInContext(context);
  boot(host, KEY);

  try {
    const err = runIn(`${KEY}.load(${JSON.stringify(file)})`, 3000);
    if (err) out.loadError = String(err);
  } catch (e) {
    out.loadError = /timed out/i.test(String(e?.message)) ? 'Loading the file timed out (infinite loop at module top level?)' : String(e?.message ?? e).slice(0, 400);
  }
  if (out.loadError) {
    for (const h of timers.values()) clearTimeout(h);
    return out;
  }
  const count = Number(runIn(`${KEY}.count()`, 1000)) || 0;
  for (let i = 0; i < count; i++) {
    const name = String(runIn(`${KEY}.name(${i})`, 1000));
    const started = process.hrtime.bigint();
    const result = await new Promise((resolve) => {
      const timer = setTimeout(() => {
        doneCb = null;
        resolve({ ok: false, error: `Timed out after ${perTestTimeoutMs} ms (a promise that never settles, or an endless loop)` });
      }, perTestTimeoutMs + 50);
      doneCb = (idx, ok, error) => {
        if (idx !== i) return;
        clearTimeout(timer);
        doneCb = null;
        resolve({ ok, error });
      };
      try {
        runIn(`${KEY}.run(${i})`);
      } catch (e) {
        clearTimeout(timer);
        doneCb = null;
        const msg = String(e?.message ?? e);
        resolve({ ok: false, error: /timed out/i.test(msg) ? `Timed out after ${perTestTimeoutMs} ms (endless loop?)` : msg.slice(0, 400) });
      }
    });
    const ms = Number(process.hrtime.bigint() - started) / 1e6;
    out.tests.push({ name, ok: result.ok, error: result.ok ? null : result.error || 'failed', ms: Math.round(ms * 10) / 10 });
  }
  for (const h of timers.values()) clearTimeout(h);
  return out;
}

const results = [];
for (const file of input.run || []) results.push(await runTestFile(String(file)));
process.stdout.write(JSON.stringify({ results, log }), () => process.exit(0));
