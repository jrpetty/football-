// Random event streams that exercise every rule (prices clustered so orders cross often).
export function randomEvents(R, m, { spread = 5, mid = 100, maxQty = 20, idStart = 1 } = {}) {
  const ev = []; let nextId = idStart; const ids = []; const limitIds = [];
  for (let i = 0; i < m; i++) {
    const r = R.next(); const sd = R.chance(0.5) ? 'BUY' : 'SELL';
    const price = mid + R.int(-spread, spread); const qty = R.int(1, maxQty);
    if (r < 0.34) { const id = nextId++; ev.push(['LIMIT', id, sd, price, qty]); ids.push(id); limitIds.push(id); }
    else if (r < 0.46) { const id = nextId++; ev.push(['ICEBERG', id, sd, price, qty + R.int(0, 2 * maxQty), R.int(1, Math.max(1, Math.floor(maxQty / 2)))]); ids.push(id); }
    else if (r < 0.56) { const id = nextId++; ev.push(['MARKET', id, sd, qty]); ids.push(id); }
    else if (r < 0.70) { const id = nextId++; ev.push(['STOP', id, sd, mid + R.int(-spread, spread), qty]); ids.push(id); }
    else if (r < 0.82) { ev.push(['CANCEL', R.chance(0.85) && ids.length ? R.pick(ids) : nextId + R.int(0, 5)]); }
    else { const id = R.chance(0.7) && limitIds.length ? R.pick(limitIds) : (ids.length ? R.pick(ids) : 1); ev.push(['AMEND', id, R.chance(0.5) ? price : mid + R.int(-spread, spread), R.int(1, maxQty + 5)]); }
  }
  return ev;
}
