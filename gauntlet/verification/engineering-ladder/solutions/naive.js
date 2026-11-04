// Spread solution "correct but naive": every rule right, the obvious data structures. Copies the whole store at
// every BEGIN and SNAPSHOT, checks deadlines lazily, and answers COUNT, SIZE, SCAN, DELPREFIX and COUNTRANGE by
// looking at every key (SCAN sorts the matches every time). Scores 100% wherever the inputs are small, and runs out
// of time on the large hidden tests of the upper levels.
function runStore(commands) {
  let store = new Map(); // key -> { v, d } (d = null: no deadline)
  let clock = 0;
  const stack = [];
  const snaps = [];
  const live = (e, at) => e !== undefined && (e.d === null || at < e.d);
  const get = (k) => { const e = store.get(k); return live(e, clock) ? e : undefined; };
  const copy = (m) => { const c = new Map(); for (const [k, e] of m) c.set(k, { v: e.v, d: e.d }); return c; };
  const canon = (s) => /^(0|-?[1-9][0-9]*)$/.test(s);
  const out = [];
  for (const [op, a, b, c] of commands) {
    let r;
    switch (op) {
      case 'SET': store.set(a, { v: b, d: null }); r = 'OK'; break;
      case 'GET': { const e = get(a); r = e ? e.v : '(nil)'; break; }
      case 'DEL': if (get(a)) { store.delete(a); r = '1'; } else r = '0'; break;
      case 'SETNX': if (get(a)) r = '0'; else { store.set(a, { v: b, d: null }); r = '1'; } break;
      case 'COUNT': { let n = 0; for (const e of store.values()) if (live(e, clock) && e.v === a) n++; r = String(n); break; }
      case 'SIZE': { let n = 0; for (const e of store.values()) if (live(e, clock)) n++; r = String(n); break; }
      case 'POPULATE': { let n = 0; for (let i = 0; i < Number(b); i++) if (!get(a + i)) { store.set(a + i, { v: c, d: null }); n++; } r = String(n); break; }
      case 'INCR': {
        const e = get(a); const cur = e ? e.v : '0';
        if (!canon(cur)) { r = 'ERR not an integer'; break; }
        r = (BigInt(cur) + BigInt(b)).toString(); store.set(a, { v: r, d: e ? e.d : null }); break;
      }
      case 'BEGIN': stack.push(copy(store)); r = 'OK'; break;
      case 'ROLLBACK': if (!stack.length) r = 'NO TRANSACTION'; else { store = stack.pop(); r = 'OK'; } break;
      case 'COMMIT': if (!stack.length) r = 'NO TRANSACTION'; else { stack.pop(); r = 'OK'; } break;
      case 'SCAN': { const ks = []; for (const [k, e] of store) if (live(e, clock) && k.startsWith(a)) ks.push(k); ks.sort(); r = ks.slice(0, Number(b)).join(','); break; }
      case 'TIME': clock = Number(a); r = 'OK'; break;
      case 'EXPIRE': { const e = get(a); if (e) { e.d = clock + Number(b); r = '1'; } else r = '0'; break; }
      case 'TTL': { const e = get(a); r = !e ? '-2' : e.d === null ? '-1' : String(e.d - clock); break; }
      case 'PERSIST': { const e = get(a); if (e && e.d !== null) { e.d = null; r = '1'; } else r = '0'; break; }
      case 'RENAME': { const e = get(a); if (!e) r = 'ERR no such key'; else { if (a !== b) { store.set(b, { v: e.v, d: e.d }); store.delete(a); } r = 'OK'; } break; }
      case 'SNAPSHOT': snaps.push({ m: copy(store), at: clock }); r = String(snaps.length); break;
      case 'GETAT': { const i = Number(b); if (!(i >= 1 && i <= snaps.length && Number.isInteger(i))) r = 'ERR no such snapshot'; else { const s = snaps[i - 1]; const e = s.m.get(a); r = live(e, s.at) ? e.v : '(nil)'; } break; }
      case 'DELPREFIX': { let n = 0; for (const [k, e] of [...store]) if (live(e, clock) && k.startsWith(a)) { store.delete(k); n++; } r = String(n); break; }
      case 'COUNTRANGE': { let n = 0; for (const [k, e] of store) if (live(e, clock) && a <= k && k < b) n++; r = String(n); break; }
    }
    out.push(r);
  }
  return out;
}
