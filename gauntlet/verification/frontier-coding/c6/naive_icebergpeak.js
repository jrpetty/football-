// Plausible slip: an incoming iceberg only trades its displayed peak before resting.
// Reference for c6: per-side price heaps (lazy deletion) + per-price FIFO queues ordered by priority number.
// A priority number is always larger than every number handed out before, so appending to a price queue keeps it sorted.
function runExchange(events) {
  class Heap { // min-heap of numbers
    constructor() { this.a = []; }
    push(x) { const a = this.a; a.push(x); let i = a.length - 1; while (i > 0) { const p = (i - 1) >> 1; if (a[p] <= a[i]) break; [a[p], a[i]] = [a[i], a[p]]; i = p; } }
    peek() { return this.a[0]; }
    pop() { const a = this.a; const top = a[0]; const last = a.pop(); if (a.length) { a[0] = last; let i = 0; for (;;) { const l = 2 * i + 1, r = l + 1; let m = i; if (l < a.length && a[l] < a[m]) m = l; if (r < a.length && a[r] < a[m]) m = r; if (m === i) break; [a[m], a[i]] = [a[i], a[m]]; i = m; } } return top; }
    get size() { return this.a.length; }
  }
  const side = { BUY: { levels: new Map(), heap: new Heap(), sign: -1 }, SELL: { levels: new Map(), heap: new Heap(), sign: 1 } };
  const byId = new Map(); // resting orders
  const stops = [];       // dormant stops in submission order (removed entries are spliced out)
  const trades = [];
  let prio = 0, last = null;
  // level: { q: [orders], head: index of first live entry, count: live orders }
  function level(s, price, create) {
    let lv = s.levels.get(price);
    if (!lv && create) { lv = { q: [], head: 0, count: 0 }; s.levels.set(price, lv); s.heap.push(s.sign * price); }
    return lv;
  }
  function rest(o) { o.prio = ++prio; o.live = true; const lv = level(side[o.side], o.price, true); lv.q.push(o); lv.count++; byId.set(o.id, o); }
  function unrest(o) { o.live = false; byId.delete(o.id); const s = side[o.side]; const lv = s.levels.get(o.price); lv.count--; if (lv.count === 0) s.levels.delete(o.price); }
  function bestOrder(s) {
    while (s.heap.size) {
      const price = s.sign * s.heap.peek();
      const lv = s.levels.get(price);
      if (!lv) { s.heap.pop(); continue; }
      while (!lv.q[lv.head].live) lv.head++;
      return lv.q[lv.head];
    }
    return null;
  }
  function match(id, sd, q, limit) {
    const opp = side[sd === 'BUY' ? 'SELL' : 'BUY'];
    while (q > 0) {
      const o = bestOrder(opp);
      if (!o) break;
      if (limit !== null && (sd === 'BUY' ? o.price > limit : o.price < limit)) break;
      const x = Math.min(q, o.disp);
      q -= x; o.disp -= x; o.rem -= x;
      trades.push(sd === 'BUY' ? [id, o.id, o.price, x] : [o.id, id, o.price, x]);
      last = o.price;
      if (o.rem === 0) unrest(o);
      else if (o.disp === 0) {
        // refresh: move to the back of its level with a new priority number
        const lv = opp.levels.get(o.price);
        o.live = false; // old slot becomes dead
        const fresh = { ...o, disp: Math.min(o.peak, o.rem), prio: ++prio, live: true };
        lv.q.push(fresh); byId.set(o.id, fresh);
      }
    }
    return q;
  }
  function limitOrder(id, sd, price, qty, kind, peak) {
    const q = match(id, sd, qty, price);
    if (q > 0) rest({ id, side: sd, price, rem: q, disp: kind === 'I' ? Math.min(peak, q) : q, kind, peak });
  }
  for (const ev of events) {
    switch (ev[0]) {
      case 'LIMIT': limitOrder(ev[1], ev[2], ev[3], ev[4], 'L', 0); break;
      case 'ICEBERG': { // slip: an incoming iceberg only trades its visible peak
        const vis = Math.min(ev[5], ev[4]); const q = match(ev[1], ev[2], vis, ev[3]); const rem = ev[4] - (vis - q);
        rest({ id: ev[1], side: ev[2], price: ev[3], rem, disp: Math.min(ev[5], rem), kind: 'I', peak: ev[5] }); break; }
      case 'MARKET': match(ev[1], ev[2], ev[3], null); break;
      case 'STOP': stops.push({ id: ev[1], side: ev[2], trigger: ev[3], qty: ev[4] }); break;
      case 'CANCEL': {
        const o = byId.get(ev[1]);
        if (o) unrest(o);
        else { const k = stops.findIndex((s) => s.id === ev[1]); if (k >= 0) stops.splice(k, 1); }
        break;
      }
      case 'AMEND': {
        const o = byId.get(ev[1]);
        if (!o || o.kind !== 'L') break;
        const [, id, price, qty] = ev;
        if (price === o.price && qty <= o.rem) { o.rem = o.disp = qty; break; }
        unrest(o);
        limitOrder(id, o.side, price, qty, 'L', 0);
        break;
      }
    }
    for (;;) {
      if (last === null) break;
      const k = stops.findIndex((s) => (s.side === 'BUY' ? last >= s.trigger : last <= s.trigger));
      if (k < 0) break;
      const s = stops.splice(k, 1)[0];
      match(s.id, s.side, s.qty, null);
    }
  }
  const levels = (s, desc) => [...s.levels.entries()].map(([p, lv]) => {
    let t = 0; for (let i = lv.head; i < lv.q.length; i++) if (lv.q[i].live) t += lv.q[i].disp; return [p, t];
  }).sort((a, b) => (desc ? b[0] - a[0] : a[0] - b[0]));
  return { trades, bids: levels(side.BUY, true), asks: levels(side.SELL, false), stops: stops.map((s) => s.id) };
}
