// Spread solution "careless": a quick first draft that reads the specification loosely. Gets the basics right but:
// INCR uses Number arithmetic and parseInt (so "007" counts and big values lose digits); COMMIT closes every open
// transaction; ROLLBACK forgets deadlines; deadlines are only checked by GET and TTL (COUNT, SIZE, SCAN and the rest
// still see expired keys); SCAN sorts with localeCompare; RENAME drops the deadline and RENAME a a deletes a;
// GETAT ignores deadlines; DELPREFIX counts expired keys; COUNTRANGE includes the upper bound. Naive data structures.
function runStore(commands) {
  let store = {};
  let ttl = {};
  let clock = 0;
  const stack = [];
  const snaps = [];
  const out = [];
  const has = (k) => Object.prototype.hasOwnProperty.call(store, k);
  for (const [op, a, b, c] of commands) {
    let r = 'OK';
    if (op === 'SET') { store[a] = b; delete ttl[a]; }
    else if (op === 'GET') { if (ttl[a] !== undefined && clock >= ttl[a]) { delete store[a]; delete ttl[a]; } r = has(a) ? store[a] : '(nil)'; }
    else if (op === 'DEL') { r = has(a) ? '1' : '0'; delete store[a]; }
    else if (op === 'SETNX') { if (has(a)) r = '0'; else { store[a] = b; r = '1'; } }
    else if (op === 'COUNT') r = String(Object.values(store).filter((v) => v === a).length);
    else if (op === 'SIZE') r = String(Object.keys(store).length);
    else if (op === 'POPULATE') { let n = 0; for (let i = 0; i < Number(b); i++) if (!has(a + i)) { store[a + i] = c; n++; } r = String(n); }
    else if (op === 'INCR') { const cur = has(a) ? parseInt(store[a], 10) : 0; if (Number.isNaN(cur)) r = 'ERR not an integer'; else { store[a] = String(cur + Number(b)); r = store[a]; } }
    else if (op === 'BEGIN') stack.push({ ...store });
    else if (op === 'ROLLBACK') { if (!stack.length) r = 'NO TRANSACTION'; else store = stack.pop(); }
    else if (op === 'COMMIT') { if (!stack.length) r = 'NO TRANSACTION'; else stack.length = 0; }
    else if (op === 'SCAN') r = Object.keys(store).filter((k) => k.startsWith(a)).sort((x, y) => x.localeCompare(y)).slice(0, Number(b)).join(',');
    else if (op === 'TIME') clock = Number(a);
    else if (op === 'EXPIRE') { if (has(a)) { ttl[a] = clock + Number(b); r = '1'; } else r = '0'; }
    else if (op === 'TTL') { if (ttl[a] !== undefined && clock >= ttl[a]) { delete store[a]; delete ttl[a]; } r = !has(a) ? '-2' : ttl[a] === undefined ? '-1' : String(ttl[a] - clock); }
    else if (op === 'PERSIST') { if (has(a) && ttl[a] !== undefined) { delete ttl[a]; r = '1'; } else r = '0'; }
    else if (op === 'RENAME') { if (!has(a)) r = 'ERR no such key'; else { store[b] = store[a]; delete store[a]; } }
    else if (op === 'SNAPSHOT') { snaps.push({ ...store }); r = String(snaps.length); }
    else if (op === 'GETAT') { const s = snaps[Number(b) - 1]; r = !s ? 'ERR no such snapshot' : Object.prototype.hasOwnProperty.call(s, a) ? s[a] : '(nil)'; }
    else if (op === 'DELPREFIX') { let n = 0; for (const k of Object.keys(store)) if (k.startsWith(a)) { delete store[k]; n++; } r = String(n); }
    else if (op === 'COUNTRANGE') r = String(Object.keys(store).filter((k) => k >= a && k <= b).length);
    out.push(r);
  }
  return out;
}
