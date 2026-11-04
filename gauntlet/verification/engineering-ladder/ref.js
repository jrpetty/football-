// Reference solution for Engineering: Ladder (coding.engineering-ladder), every level.
//
// The level-10 specification is a superset of every lower level's, and no later feature changes what an earlier
// command does, so this one implementation is the reference for all ten levels.
//
// Design (all operations O(log n) or better, amortised):
//  - Offline key compression: every key that can ever exist (SET, SETNX, INCR, RENAME target, POPULATE) is collected
//    first, sorted in JavaScript string order and given an id = its rank. Per-key state lives in arrays by id.
//  - A Fenwick tree over ids marks the keys that exist now: SIZE, SCAN, DELPREFIX and COUNTRANGE are rank queries.
//  - A Map value -> number of existing keys holding it answers COUNT.
//  - Deadlines: a binary min-heap of (deadline, id, version); TIME pops every due entry whose version is current and
//    removes the key at once, so "exists" never needs a lazy check.
//  - Transactions: one Map per open transaction, id -> the key's entry before its FIRST change in that transaction.
//    ROLLBACK restores those entries; COMMIT merges the child map into its parent, keeping the parent's (older) entry,
//    iterating over the smaller of the two maps (small-to-large), so deep nesting with big transactions stays fast.
//  - Snapshots: only when the input has SNAPSHOT, every change appends (change number, value, deadline) to its key's
//    history; GETAT binary-searches it and checks the deadline against the clock of the snapshot.
function runStore(commands) {
  // Globals copied into locals once: inside the test sandbox (a vm context) every global lookup is slow.
  const Infinity = 1 / 0, String = globalThis.String, Number = globalThis.Number, BigInt = globalThis.BigInt, Map = globalThis.Map;

  // ---------- offline: every key that can ever exist ----------
  const cand = []; // candidate keys, possibly repeated
  const popMax = new Map(); // POPULATE prefix -> largest count: its keys are prefix + 0 .. prefix + (count - 1)
  let hasSnapshots = false;
  for (const c of commands) {
    const op = c[0];
    if (op === 'SET' || op === 'SETNX' || op === 'INCR') cand.push(c[1]);
    else if (op === 'RENAME') cand.push(c[2]);
    else if (op === 'POPULATE') { const n = Number(c[2]); if (n > (popMax.get(c[1]) || 0)) popMax.set(c[1], n); }
    else if (op === 'SNAPSHOT') hasSnapshots = true;
  }
  const popStr = new Map(); // prefix -> its key strings (the same string objects are used for every lookup)
  for (const [p, n] of popMax) { const a = new Array(n); for (let i = 0; i < n; i++) cand.push((a[i] = p + i)); popStr.set(p, a); }
  cand.sort();
  const keys = [];
  for (let i = 0; i < cand.length; i++) if (i === 0 || cand[i] !== cand[i - 1]) keys.push(cand[i]);
  const M = keys.length;
  const idOf = new Map();
  for (let i = 0; i < M; i++) idOf.set(keys[i], i);
  const popIds = new Map(); // prefix -> Int32Array of the ids of prefix + 0, prefix + 1, ...
  for (const [p, a] of popStr) { const ids = new Int32Array(a.length); for (let i = 0; i < a.length; i++) ids[i] = idOf.get(a[i]); popIds.set(p, ids); }
  const lowerBound = (s) => { let lo = 0, hi = M; while (lo < hi) { const mid = (lo + hi) >> 1; if (keys[mid] < s) lo = mid + 1; else hi = mid; } return lo; };

  // ---------- per-key state ----------
  const val = new Array(M).fill(undefined); // undefined = the key does not exist
  const dl = new Float64Array(M).fill(Infinity); // deadline (Infinity = none)
  const ver = new Int32Array(M); // bumped on every change, to recognise stale heap entries
  const fen = new Int32Array(M + 1);
  let size = 0;
  const counts = new Map();
  const fenAdd = (i, d) => { for (i++; i <= M; i += i & -i) fen[i] += d; };
  const fenPrefix = (i) => { let s = 0; for (; i > 0; i -= i & -i) s += fen[i]; return s; }; // existing ids < i
  let LOG = 1; while ((1 << LOG) <= M) LOG++;
  const fenSelect = (k) => { // id of the k-th existing key (0-based)
    let pos = 0;
    for (let b = 1 << LOG; b > 0; b >>= 1) if (pos + b <= M && fen[pos + b] <= k) { pos += b; k -= fen[pos]; }
    return pos;
  };

  // ---------- deadlines ----------
  let now = 0;
  const hD = [], hI = [], hV = [];
  const heapPush = (d, i, v) => {
    let j = hD.length; hD.push(d); hI.push(i); hV.push(v);
    while (j > 0) { const p = (j - 1) >> 1; if (hD[p] <= d) break; hD[j] = hD[p]; hI[j] = hI[p]; hV[j] = hV[p]; j = p; }
    hD[j] = d; hI[j] = i; hV[j] = v;
  };
  const heapPop = () => {
    const lastD = hD.pop(), lastI = hI.pop(), lastV = hV.pop();
    if (!hD.length) return;
    let j = 0; const n = hD.length;
    for (;;) {
      let c = 2 * j + 1; if (c >= n) break;
      if (c + 1 < n && hD[c + 1] < hD[c]) c++;
      if (hD[c] >= lastD) break;
      hD[j] = hD[c]; hI[j] = hI[c]; hV[j] = hV[c]; j = c;
    }
    hD[j] = lastD; hI[j] = lastI; hV[j] = lastV;
  };

  // ---------- transactions and history ----------
  const tx = [];
  let seq = 0;
  // history: a key's first change in three flat arrays (most keys, e.g. POPULATE's, change once), later changes in a
  // per-key flat array [seq, value, deadline, seq, value, deadline, ...]
  const h1Seq = hasSnapshots ? new Float64Array(M) : null; // 0 = no change yet (change numbers start at 1)
  const h1Val = hasSnapshots ? new Array(M) : null;
  const h1Dl = hasSnapshots ? new Float64Array(M) : null;
  const hist = hasSnapshots ? new Array(M) : null;
  const snaps = []; // [seq, clock]

  const present = (v) => { size++; counts.set(v, (counts.get(v) || 0) + 1); };
  const absent = (v) => { size--; const n = counts.get(v) - 1; if (n) counts.set(v, n); else counts.delete(v); };

  // The one way the store changes. v === undefined removes the key; a deadline the clock has reached also removes it.
  function setEntry(i, v, d, logUndo) {
    if (v !== undefined && d <= now) { v = undefined; d = Infinity; }
    if (v === undefined) d = Infinity;
    if (logUndo && tx.length) { const top = tx[tx.length - 1]; if (!top.has(i)) top.set(i, [val[i], dl[i]]); }
    const old = val[i];
    if (old !== undefined) absent(old);
    if (v !== undefined) present(v);
    if ((old === undefined) !== (v === undefined)) fenAdd(i, v === undefined ? -1 : 1);
    val[i] = v; dl[i] = d; ver[i]++;
    if (v !== undefined && d !== Infinity) heapPush(d, i, ver[i]);
    if (hist) {
      seq++;
      if (h1Seq[i] === 0) { h1Seq[i] = seq; h1Val[i] = v; h1Dl[i] = d; } else (hist[i] || (hist[i] = [])).push(seq, v, d);
    }
  }

  const CANON = /^(?:0|-?[1-9][0-9]*)$/;
  const out = new Array(commands.length);
  for (let n = 0; n < commands.length; n++) {
    const c = commands[n];
    let r;
    switch (c[0]) {
      case 'SET': setEntry(idOf.get(c[1]), c[2], Infinity, true); r = 'OK'; break;
      case 'GET': { const i = idOf.get(c[1]); r = i === undefined || val[i] === undefined ? '(nil)' : val[i]; break; }
      case 'DEL': { const i = idOf.get(c[1]); if (i !== undefined && val[i] !== undefined) { setEntry(i, undefined, Infinity, true); r = '1'; } else r = '0'; break; }
      case 'SETNX': { const i = idOf.get(c[1]); if (val[i] === undefined) { setEntry(i, c[2], Infinity, true); r = '1'; } else r = '0'; break; }
      case 'COUNT': r = String(counts.get(c[1]) || 0); break;
      case 'SIZE': r = String(size); break;
      case 'POPULATE': {
        const cnt = Number(c[2]); let made = 0;
        const ids = popIds.get(c[1]);
        for (let k = 0; k < cnt; k++) { const i = ids[k]; if (val[i] === undefined) { setEntry(i, c[3], Infinity, true); made++; } }
        r = String(made); break;
      }
      case 'INCR': {
        const i = idOf.get(c[1]); const cur = val[i] === undefined ? '0' : val[i];
        if (!CANON.test(cur)) { r = 'ERR not an integer'; break; }
        const next = String(BigInt(cur) + BigInt(c[2]));
        setEntry(i, next, val[i] === undefined ? Infinity : dl[i], true); r = next; break;
      }
      case 'BEGIN': tx.push(new Map()); r = 'OK'; break;
      case 'ROLLBACK': {
        if (!tx.length) { r = 'NO TRANSACTION'; break; }
        for (const [i, e] of tx.pop()) setEntry(i, e[0], e[1], false);
        r = 'OK'; break;
      }
      case 'COMMIT': {
        if (!tx.length) { r = 'NO TRANSACTION'; break; }
        const child = tx.pop();
        if (tx.length) {
          const parent = tx[tx.length - 1];
          if (child.size > parent.size) { for (const [i, e] of parent) child.set(i, e); tx[tx.length - 1] = child; }
          else for (const [i, e] of child) if (!parent.has(i)) parent.set(i, e);
        }
        r = 'OK'; break;
      }
      case 'SCAN': {
        const p = c[1], limit = Number(c[2]);
        const parts = [];
        let k = fenPrefix(lowerBound(p));
        while (parts.length < limit && k < size) { const i = fenSelect(k++); if (!keys[i].startsWith(p)) break; parts.push(keys[i]); }
        r = parts.join(','); break;
      }
      case 'TIME': {
        now = Number(c[1]);
        while (hD.length && hD[0] <= now) {
          const i = hI[0], v = hV[0]; heapPop();
          if (ver[i] === v && val[i] !== undefined) {
            // expiry is not a change: nothing to undo (a rolled-back key gets its old deadline back, also past) and no history entry
            absent(val[i]); fenAdd(i, -1); val[i] = undefined; dl[i] = Infinity; ver[i]++;
          }
        }
        r = 'OK'; break;
      }
      case 'EXPIRE': { const i = idOf.get(c[1]); if (i !== undefined && val[i] !== undefined) { setEntry(i, val[i], now + Number(c[2]), true); r = '1'; } else r = '0'; break; }
      case 'TTL': { const i = idOf.get(c[1]); r = i === undefined || val[i] === undefined ? '-2' : dl[i] === Infinity ? '-1' : String(dl[i] - now); break; }
      case 'PERSIST': { const i = idOf.get(c[1]); if (i !== undefined && val[i] !== undefined && dl[i] !== Infinity) { setEntry(i, val[i], Infinity, true); r = '1'; } else r = '0'; break; }
      case 'RENAME': {
        const s = idOf.get(c[1]);
        if (s === undefined || val[s] === undefined) { r = 'ERR no such key'; break; }
        if (c[1] !== c[2]) { const t = idOf.get(c[2]); const v = val[s], d = dl[s]; setEntry(t, v, d, true); setEntry(s, undefined, Infinity, true); }
        r = 'OK'; break;
      }
      case 'SNAPSHOT': snaps.push([seq, now]); r = String(snaps.length); break;
      case 'GETAT': {
        const id = c[2].length > 15 ? Infinity : Number(c[2]);
        if (!(id >= 1 && id <= snaps.length)) { r = 'ERR no such snapshot'; break; }
        const [sq, clock] = snaps[id - 1];
        const i = idOf.get(c[1]);
        r = '(nil)';
        if (i === undefined || h1Seq[i] === 0 || h1Seq[i] > sq) break; // no change to this key before the snapshot
        let v = h1Val[i], d = h1Dl[i];
        const h = hist[i];
        if (h) {
          let lo = 0, hi = h.length / 3; // last later change with seq <= sq
          while (lo < hi) { const mid = (lo + hi) >> 1; if (h[3 * mid] <= sq) lo = mid + 1; else hi = mid; }
          if (lo > 0) { v = h[3 * (lo - 1) + 1]; d = h[3 * (lo - 1) + 2]; }
        }
        if (v !== undefined && d > clock) r = v;
        break;
      }
      case 'DELPREFIX': {
        const p = c[1]; const k = fenPrefix(lowerBound(p)); let removed = 0;
        while (k < size) { const i = fenSelect(k); if (!keys[i].startsWith(p)) break; setEntry(i, undefined, Infinity, true); removed++; }
        r = String(removed); break;
      }
      case 'COUNTRANGE': { const lo = c[1], hi = c[2]; r = lo >= hi ? '0' : String(fenPrefix(lowerBound(hi)) - fenPrefix(lowerBound(lo))); break; }
      default: r = 'ERR unknown command';
    }
    out[n] = r;
  }
  return out;
}
