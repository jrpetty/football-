// Second, independent JavaScript implementation of Engineering: Ladder (all levels), written from the prompt text
// with different data structures from ref.js. It is used to cross-check the hidden tests that are too large for
// the Python oracle, and in the spread as a solid, careful solution that is not fully optimised.
//
//  - Keys are strings (no offline pass): Map key -> {v, d}; the existing keys are also kept in one sorted array,
//    updated by binary search + splice (POPULATE merges its new keys in one pass).
//  - Undo: each open transaction has a log of [key, previous entry or null], replayed backwards on ROLLBACK;
//    COMMIT appends the child's log to its parent's (so a deep chain of commits re-copies logs).
//  - Deadlines: a heap of [deadline, key, entry] with lazy deletion (the entry object must still be current).
//  - Snapshots: every change appends [changeNo, entry] to the key's history; GETAT binary-searches it.
function runStore(commands) {
  const map = new Map();
  const sorted = [];
  const counts = new Map();
  let clock = 0;
  const logs = [];
  let change = 0;
  const history = new Map();
  const snapshots = [];
  const heap = [];

  function lb(arr, s) { let lo = 0, hi = arr.length; while (lo < hi) { const m = (lo + hi) >>> 1; if (arr[m] < s) lo = m + 1; else hi = m; } return lo; }
  function bump(v, by) { const n = (counts.get(v) || 0) + by; if (n) counts.set(v, n); else counts.delete(v); }
  function hpush(item) { heap.push(item); let i = heap.length - 1; while (i) { const p = (i - 1) >>> 1; if (heap[p][0] <= heap[i][0]) break; [heap[p], heap[i]] = [heap[i], heap[p]]; i = p; } }
  function hpop() { const top = heap[0]; const last = heap.pop(); if (heap.length) { heap[0] = last; let i = 0; for (;;) { const l = 2 * i + 1, r = l + 1; let m = i; if (l < heap.length && heap[l][0] < heap[m][0]) m = l; if (r < heap.length && heap[r][0] < heap[m][0]) m = r; if (m === i) break; [heap[m], heap[i]] = [heap[i], heap[m]]; i = m; } } return top; }

  // write(key, entry or null): the single place where a key's entry changes
  function write(key, entry, undo) {
    if (entry && entry.d !== null && entry.d <= clock) entry = null;
    const before = map.has(key) ? map.get(key) : null;
    if (undo && logs.length) logs[logs.length - 1].push([key, before]);
    if (before) { bump(before.v, -1); if (!entry) { map.delete(key); sorted.splice(lb(sorted, key), 1); } }
    if (entry) { bump(entry.v, 1); if (!before) sorted.splice(lb(sorted, key), 0, key); map.set(key, entry); if (entry.d !== null) hpush([entry.d, key, entry]); }
    change++;
    let h = history.get(key); if (!h) history.set(key, (h = []));
    h.push([change, entry]);
  }
  const isCanonical = (s) => s === '0' || /^-?[1-9]\d*$/.test(s);

  const replies = [];
  for (const cmd of commands) {
    const [op, a, b, c] = cmd;
    let out;
    if (op === 'SET') { write(a, { v: b, d: null }, true); out = 'OK'; }
    else if (op === 'GET') { const e = map.get(a); out = e ? e.v : '(nil)'; }
    else if (op === 'DEL') { if (map.has(a)) { write(a, null, true); out = '1'; } else out = '0'; }
    else if (op === 'SETNX') { if (map.has(a)) out = '0'; else { write(a, { v: b, d: null }, true); out = '1'; } }
    else if (op === 'COUNT') out = String(counts.get(a) || 0);
    else if (op === 'SIZE') out = String(map.size);
    else if (op === 'POPULATE') {
      const fresh = [];
      for (let i = 0; i < Number(b); i++) { const k = a + i; if (!map.has(k)) fresh.push(k); }
      // record the changes one by one (undo + history), but merge the sorted array in one pass
      for (const k of fresh) {
        const entry = { v: c, d: null };
        if (logs.length) logs[logs.length - 1].push([k, null]);
        bump(c, 1); map.set(k, entry); change++;
        let h = history.get(k); if (!h) history.set(k, (h = []));
        h.push([change, entry]);
      }
      if (fresh.length) {
        fresh.sort();
        const merged = new Array(sorted.length + fresh.length);
        let i = 0, j = 0, m = 0;
        while (i < sorted.length || j < fresh.length) merged[m++] = j >= fresh.length || (i < sorted.length && sorted[i] < fresh[j]) ? sorted[i++] : fresh[j++];
        sorted.length = 0; for (const k of merged) sorted.push(k);
      }
      out = String(fresh.length);
    }
    else if (op === 'INCR') {
      const e = map.get(a); const cur = e ? e.v : '0';
      if (!isCanonical(cur)) out = 'ERR not an integer';
      else { out = (BigInt(cur) + BigInt(b)).toString(); write(a, { v: out, d: e ? e.d : null }, true); }
    }
    else if (op === 'BEGIN') { logs.push([]); out = 'OK'; }
    else if (op === 'ROLLBACK') {
      if (!logs.length) out = 'NO TRANSACTION';
      else { const log = logs.pop(); for (let i = log.length - 1; i >= 0; i--) write(log[i][0], log[i][1] && { v: log[i][1].v, d: log[i][1].d }, false); out = 'OK'; }
    }
    else if (op === 'COMMIT') {
      if (!logs.length) out = 'NO TRANSACTION';
      else { const log = logs.pop(); if (logs.length) { const p = logs[logs.length - 1]; for (const x of log) p.push(x); } out = 'OK'; }
    }
    else if (op === 'SCAN') {
      const lim = Number(b); const res = [];
      for (let i = lb(sorted, a); i < sorted.length && res.length < lim && sorted[i].startsWith(a); i++) res.push(sorted[i]);
      out = res.join(',');
    }
    else if (op === 'TIME') {
      clock = Number(a);
      while (heap.length && heap[0][0] <= clock) {
        const [, k, entry] = hpop();
        if (map.get(k) === entry) { bump(entry.v, -1); map.delete(k); sorted.splice(lb(sorted, k), 1); }
      }
      out = 'OK';
    }
    else if (op === 'EXPIRE') { const e = map.get(a); if (e) { write(a, { v: e.v, d: clock + Number(b) }, true); out = '1'; } else out = '0'; }
    else if (op === 'TTL') { const e = map.get(a); out = !e ? '-2' : e.d === null ? '-1' : String(e.d - clock); }
    else if (op === 'PERSIST') { const e = map.get(a); if (e && e.d !== null) { write(a, { v: e.v, d: null }, true); out = '1'; } else out = '0'; }
    else if (op === 'RENAME') {
      const e = map.get(a);
      if (!e) out = 'ERR no such key';
      else { if (a !== b) { write(b, { v: e.v, d: e.d }, true); write(a, null, true); } out = 'OK'; }
    }
    else if (op === 'SNAPSHOT') { snapshots.push({ change, clock }); out = String(snapshots.length); }
    else if (op === 'GETAT') {
      const n = /^[1-9]\d{0,15}$/.test(b) ? Number(b) : 0;
      if (n < 1 || n > snapshots.length) out = 'ERR no such snapshot';
      else {
        const s = snapshots[n - 1]; const h = history.get(a) || [];
        let lo = 0, hi = h.length; while (lo < hi) { const m = (lo + hi) >>> 1; if (h[m][0] <= s.change) lo = m + 1; else hi = m; }
        const e = lo ? h[lo - 1][1] : null;
        out = e && (e.d === null || e.d > s.clock) ? e.v : '(nil)';
      }
    }
    else if (op === 'DELPREFIX') {
      const from = lb(sorted, a); let to = from; while (to < sorted.length && sorted[to].startsWith(a)) to++;
      const doomed = sorted.slice(from, to);
      for (const k of doomed) write(k, null, true);
      out = String(doomed.length);
    }
    else if (op === 'COUNTRANGE') out = a >= b ? '0' : String(lb(sorted, b) - lb(sorted, a));
    replies.push(out);
  }
  return replies;
}
