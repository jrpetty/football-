// The maths of the belts: what every belt should carry once things settle,
// worked out from rates instead of waited for, and what is holding it back.
//
// Everything is per day (a day is SEC_PER_DAY seconds of camp time).
//
//   A building runs B batches a day at most:
//     Bmax = SEC_PER_DAY x rate x belt bonus x season / batch time
//   where rate is its workers' speed plus automation (1.0 = one average
//   worker). Each batch takes in[k] of every input and makes out[k].
//   A belt of tier T carries at most
//     cap = speed / gap x stack x SEC_PER_DAY      (Mk1 96, Mk2 192, Mk3 400)
//   A building then runs as fast as its slowest constraint allows:
//     B = min(Bmax, belted inputs / in[k], room on belted outputs / out[k])
//   Inputs without a belt come from storage, so they never hold it back.
//   What it makes, B x out[k], is shared over the belts leaving it for k:
//   each gets an equal share, and a belt that can take less than its share
//   (full, or its far end needs less) passes the rest to the others.
//   A splitter shares out what comes in the same way; a priority splitter
//   fills its first belt as far as it will go and shares the rest; a merger
//   sends on the sum of what comes in, up to its belt's cap; a hopper passes
//   on what comes in (its tank only evens out bursts, not the daily rate).
//
// Constraints feed back both ways (a slow forge backs up the scrap belt
// that feeds it, which slows the scrap yard), so the whole network is
// settled by repeating the two passes until nothing moves.
import { STATIONS, SEC_PER_DAY, RECIPES, RES } from './data.js'
import { S, capOf } from './state.js'
import { stationRate, power, powerInfo, activeSingle, activeRecipe, recipeUnlocked, seasonMult, passiveMult, stationFlow, linkPower } from './economy.js'
import { isDepot, isNode, nodeKind, splits, firstOut, linkPerDay, beltBonus } from './belts.js'
import { bus } from '../core/util.js'

const EPS = 0.01
const byId = (id) => S.stations.find((s) => s.id === id)

// The recipe a building is running, or would run next.
export function plannedRecipe(st) {
  const D = STATIONS[st.type]
  if (D.recipe) return activeSingle(st)
  if (D.recipes) {
    const ok = (id) => D.recipes[id] && recipeUnlocked(st, id)
    let id = st.curMode && ok(st.curMode) ? st.curMode : st.mode && st.mode !== 'auto' && ok(st.mode) ? st.mode : null
    // on Auto: the product a belt carries away, else the first it can make
    if (!id) id = Object.keys(D.recipes).find((r) => ok(r) && (S.links || []).some((l) => l.from === st.id && D.recipes[r].out[l.res])) || Object.keys(D.recipes).find(ok)
    return id ? activeRecipe(st, id) : null
  }
  if (D.queue) {
    for (const o of st.orders || []) {
      if (o.kind !== 'recipe') continue
      const r = RECIPES.find((x) => x.id === o.recipe)
      if (r) return r
    }
  }
  return null
}

// What one building does in a day, flat out: { maxB, ins, outs } per batch,
// or { fixed: { k: perDay } } for things that just make (or burn) at a rate.
function profile(st, pinfo) {
  if (st.building || st.level < 1 || isDepot(st) || isNode(st)) return null
  const D = STATIONS[st.type]
  if (D.passive) {
    const fixed = {}
    for (const [k, arr] of Object.entries(D.passive)) fixed[k] = arr[st.level - 1] * passiveMult(st)
    return { fixed }
  }
  if (st.type === 'generator' || st.type === 'boiler') {
    const f = stationFlow(st, pinfo)
    const ins = {}
    for (const [k, v] of Object.entries(f)) if (v < 0) ins[k] = -v
    return { maxB: 1, ins, outs: {} }
  }
  const R = plannedRecipe(st)
  if (!R) return null
  const time = Array.isArray(R.time) ? R.time[st.level - 1] : R.time
  const rate = stationRate(st, pinfo)
  const maxB = time > 0 ? (SEC_PER_DAY * rate * beltBonus(st, R) * seasonMult(st)) / time : 0
  return { maxB, ins: R.in || {}, outs: R.out || {}, R }
}

// Share `total` over belts that can each take at most cap[i]: equal shares,
// and whatever one cannot take goes to the rest.
function waterFill(total, caps) {
  const out = caps.map(() => 0)
  let left = total
  let open = caps.map((c, i) => i).filter((i) => caps[i] > EPS)
  while (left > EPS && open.length) {
    const share = left / open.length
    const next = []
    for (const i of open) {
      const take = Math.min(share, caps[i] - out[i])
      out[i] += take
      left -= take
      if (caps[i] - out[i] > EPS) next.push(i)
    }
    if (next.length === open.length) break
    open = next
  }
  return out
}

// Solve the camp's belt network. Returns
//   links: Map(id -> { flow, cap, supply, demand, limit: 'power' | 'belt' | 'target' | 'source' | 'starved', short })
//   stations: Map(id -> { maxB, B, limit: null | { kind: 'input' | 'output', res } })
// Flows are per day in units of the resource.
export function solveFlows() {
  const links = S.links || []
  // fresh, so a belt or node laid this instant already counts
  const pinfo = powerInfo()
  const P = new Map()
  for (const st of S.stations) P.set(st.id, profile(st, pinfo))
  const into = new Map()
  const outof = new Map()
  for (const l of links) {
    if (!into.has(l.to)) into.set(l.to, [])
    if (!outof.has(l.from)) outof.set(l.from, [])
    into.get(l.to).push(l)
    outof.get(l.from).push(l)
  }
  // a belt with no power (or running to or from a splitter or merger with
  // none) carries nothing
  const live = (id) => pinfo.powered.has(id)
  const dead = (l) => !live(l.id) || (isNode(byId(l.from)) && !live(l.from)) || (isNode(byId(l.to)) && !live(l.to))
  const cap = new Map(links.map((l) => [l.id, dead(l) ? 0 : linkPerDay(l.tier, l.res)]))
  // start every belt full and let the constraints pull it down: starting
  // empty, a building that needs two belted inputs would never get going
  const flow = new Map(links.map((l) => [l.id, cap.get(l.id)]))
  const accept = new Map(links.map((l) => [l.id, cap.get(l.id)]))
  const supply = new Map()
  const demand = new Map()
  const runs = new Map()
  const sum = (ls, m = flow) => ls.reduce((a, l) => a + m.get(l.id), 0)

  // How fast a building could run given what its belts let it (ignoring one
  // input `skip`, when working out how much of that input it wants).
  const batches = (st, skip = null) => {
    const p = P.get(st.id)
    if (!p || p.fixed) return { B: 0, limit: null }
    let B = p.maxB
    let limit = null
    for (const [k, v] of Object.entries(p.ins)) {
      if (!(v > 0) || k === skip) continue
      const ls = (into.get(st.id) || []).filter((l) => l.res === k)
      if (!ls.length) continue
      const b = sum(ls) / v
      if (b < B - EPS) {
        B = b
        limit = { kind: 'input', res: k }
      }
    }
    for (const [k, v] of Object.entries(p.outs)) {
      if (!(v > 0)) continue
      const ls = (outof.get(st.id) || []).filter((l) => l.res === k)
      if (!ls.length) continue
      const b = sum(ls, accept) / v
      if (b < B - EPS) {
        B = b
        limit = { kind: 'output', res: k }
      }
    }
    return { B: Math.max(0, B), limit }
  }
  // How much of resource k building st would take off belt l a day.
  const wants = (st, k, l) => {
    if (!st) return 0
    if (isDepot(st)) return (S.res[k] || 0) < capOf(k) - 0.5 ? Infinity : sum((outof.get(st.id) || []).filter((x) => x.res === k))
    const others = (into.get(st.id) || []).filter((x) => x !== l && x.res === k)
    if (splits(st) || nodeKind(st) === 'hopper') return Math.max(0, sum(outof.get(st.id) || [], accept) - sum(others))
    if (nodeKind(st) === 'merge') return Math.max(0, sum(outof.get(st.id) || [], accept) - sum(others))
    if (st.type === 'mast') return Infinity
    const p = P.get(st.id)
    if (!p || p.fixed || !(p.ins[k] > 0)) return 0
    return Math.max(0, batches(st, k).B * p.ins[k] - sum(others))
  }

  for (let iter = 0; iter < 40; iter++) {
    let moved = 0
    // backward: what each belt's far end will take
    for (const l of links) {
      const a = Math.min(cap.get(l.id), wants(byId(l.to), l.res, l))
      demand.set(l.id, a)
      if (Math.abs(a - accept.get(l.id)) > EPS) moved++
      accept.set(l.id, a)
    }
    // forward: what each source puts on its belts
    for (const st of S.stations) {
      const outs = outof.get(st.id)
      if (!outs?.length) continue
      const byRes = new Map()
      for (const l of outs) {
        if (!byRes.has(l.res)) byRes.set(l.res, [])
        byRes.get(l.res).push(l)
      }
      for (const [k, ls] of byRes) {
        let total
        if (isDepot(st)) total = (S.res[k] || 0) >= 1 ? Infinity : sum((into.get(st.id) || []).filter((x) => x.res === k))
        else if (isNode(st)) total = sum(into.get(st.id) || [])
        else {
          const p = P.get(st.id)
          if (!p) total = 0
          else if (p.fixed) total = p.fixed[k] || 0
          else {
            const r = batches(st)
            runs.set(st.id, { maxB: p.maxB, B: r.B, limit: r.limit })
            total = r.B * (p.outs[k] || 0)
          }
        }
        const T = total === Infinity ? 1e9 : total
        let share
        if (nodeKind(st) === 'prio' && ls.length > 1) {
          // the first belt takes all it can; the others share what is left
          const first = firstOut(st, ls)
          const a0 = Math.min(T, accept.get(first.id))
          const rest = ls.filter((l) => l !== first)
          const fill = waterFill(T - a0, rest.map((l) => accept.get(l.id)))
          share = ls.map((l) => (l === first ? a0 : fill[rest.indexOf(l)]))
        } else share = waterFill(T, ls.map((l) => accept.get(l.id)))
        ls.forEach((l, i) => {
          supply.set(l.id, total === Infinity ? Infinity : total / ls.length)
          const f = Math.min(share[i], accept.get(l.id))
          if (Math.abs(f - flow.get(l.id)) > EPS) moved++
          flow.set(l.id, f)
        })
      }
    }
    if (!moved) break
  }
  // and the buildings fed only by belts (nothing leaving them by belt)
  for (const st of S.stations) {
    if (runs.has(st.id)) continue
    const p = P.get(st.id)
    if (!p || p.fixed) continue
    const r = batches(st)
    runs.set(st.id, { maxB: p.maxB, B: r.B, limit: r.limit })
  }
  // What holds each belt back. The far end's real appetite is what it would
  // take if its other inputs kept up (only its own outputs can slow it);
  // the near end is spent when everything it makes for k is already moving.
  const appetite = (l) => {
    const st = byId(l.to)
    const k = l.res
    if (!st || isDepot(st) || isNode(st) || st.type === 'mast') return demand.get(l.id) ?? Infinity
    const p = P.get(st.id)
    if (!p || p.fixed || !(p.ins[k] > 0)) return 0
    let B = p.maxB
    for (const [ko, v] of Object.entries(p.outs)) {
      const ls = (outof.get(st.id) || []).filter((x) => x.res === ko)
      if (ls.length && v > 0) B = Math.min(B, sum(ls, accept) / v)
    }
    const others = (into.get(st.id) || []).filter((x) => x !== l && x.res === k)
    return Math.max(0, B * p.ins[k] - sum(others))
  }
  const spent = (l) => {
    const st = byId(l.from)
    const ls = (outof.get(st?.id) || []).filter((x) => x.res === l.res)
    const tot = isNode(st) ? sum(into.get(st.id) || []) : supply.get(l.id) * ls.length
    return tot !== Infinity && sum(ls) >= tot - 0.5
  }
  const out = new Map()
  for (const l of links) {
    const f = flow.get(l.id)
    const c = cap.get(l.id)
    const want = appetite(l)
    let limit = 'starved'
    if (dead(l)) limit = 'power'
    else if (f >= c - 0.5) limit = 'belt'
    else if (f >= want - 0.5) limit = 'target'
    else if (spent(l)) limit = 'source'
    const short = limit === 'starved' ? runs.get(l.to)?.limit : null
    const src = byId(l.from)
    const outsSrc = outof.get(src?.id) || []
    const share = isNode(src) ? { total: sum(into.get(src.id) || []), n: outsSrc.length, first: nodeKind(src) === 'prio' && outsSrc.length > 1 ? firstOut(src, outsSrc) === l : null } : null
    out.set(l.id, { flow: f, cap: c, supply: supply.get(l.id) ?? 0, demand: want, limit, short, share })
  }
  return { links: out, stations: runs }
}

// One sentence on what holds a belt back, for panels and tooltips.
export function limitText(l, r) {
  const from = byId(l.from)
  const to = byId(l.to)
  const nm = (st) => (st ? STATIONS[st.type].name : '?')
  const res = RES[l.res].name.toLowerCase()
  const f = (v) => (v === Infinity ? 'plenty' : v < 10 ? v.toFixed(1) : Math.round(v))
  if (!r) return ''
  if (r.limit === 'power') {
    const node = [from, to].find((st) => isNode(st) && !power().powered.has(st.id))
    return node ? `The ${nm(node).toLowerCase()} has no power, so nothing gets through. Build or fuel a generator.` : `No power: the belt has stopped. It needs ${linkPower(l).toFixed(1)} power; build or fuel a generator.`
  }
  if (r.limit === 'belt') return `The belt is full: a ${['', 'Mk1', 'Mk2', 'Mk3'][l.tier]} belt carries ${f(r.cap)} ${res} a day. Upgrade it, or split the load over two belts.`
  if (r.limit === 'target') {
    if (isDepot(to)) return `Storage is full of ${res}.`
    if (isNode(to)) return `What the ${nm(to).toLowerCase()} passes on can only take ${f(r.demand)} a day.`
    return r.demand < EPS ? `The ${nm(to)} isn't using ${res} right now.` : `The ${nm(to)} only uses ${f(r.demand)} ${res} a day. More would just queue on the belt.`
  }
  if (r.limit === 'starved') {
    const k = r.short?.res && r.short.res !== l.res ? RES[r.short.res].name.toLowerCase() : null
    return k ? `The ${nm(to)} is short of ${k}, so it only takes ${f(r.flow)} ${res} a day.` : `The ${nm(to)} can't keep up: it takes ${f(r.flow)} ${res} a day.`
  }
  if (isDepot(from)) return `Storage has run out of ${res}.`
  if (isNode(from) && r.share) {
    const part = r.share.n === 2 ? 'half' : r.share.n === 3 ? 'a third' : 'all'
    if (r.share.first === true) return `First in line: this belt takes as much of the ${f(r.share.total)} a day coming in as it can use.`
    if (r.share.first === false) return r.flow < 0.5 ? `Overflow only: the first belt uses all ${f(r.share.total)} a day coming in, so nothing spills over yet.` : `Overflow: this belt gets what the first belt can't take, ${f(r.flow)} of the ${f(r.share.total)} a day.`
    return nodeKind(from) === 'split' && r.share.n > 1 ? `The splitter gets ${f(r.share.total)} a day and gives this belt ${part}: ${f(r.flow)}.` : `Only ${f(r.flow)} a day reaches the ${nm(from).toLowerCase()}.`
  }
  return `The ${nm(from)} makes ${f(r.supply)} ${res} a day for this belt.`
}

// The solution, worked out at most once a second (and again as soon as a
// belt or building changes).
let cached = null
let cachedAt = 0
export function flowsNow() {
  const now = typeof performance !== 'undefined' ? performance.now() : Date.now()
  if (!cached || now - cachedAt > 1000) {
    cached = S ? solveFlows() : { links: new Map(), stations: new Map() }
    cachedAt = now
  }
  return cached
}
export function invalidateFlows() {
  cached = null
}
bus.on('links', invalidateFlows)
bus.on('stations', invalidateFlows)
