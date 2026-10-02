// Personal lockers in a multiplayer camp. Each player keeps resources and
// items of their own, apart from camp storage; they can put things in, take
// them out for the camp, give them to a friend, or offer a trade. The host
// carries out every change (applyLockerOp), so nothing is spent twice.
//
// An item in a locker carries `locker: pid` (it stays in S.items, so it can
// still be worn by its owner's survivors). Resources sit in
// S.mp.lockers[pid].res. Trade offers live in S.mp.trades.
import { S, NET, itemOf, ownerOf } from '../game/state.js'
import { RES } from '../game/data.js'
import { uid } from '../core/util.js'

export const MAX_OFFERS = 6
export const lockerRes = (pid = NET.pid) => S.mp?.lockers?.[pid]?.res || {}
export const lockerItems = (pid = NET.pid) => S.items.filter((it) => it.locker === pid)
// may this player's survivors wear it? (camp items, or their own)
export const usableBy = (it, pid = NET.pid) => !it?.locker || it.locker === pid
export const sharedItems = () => S.items.filter((it) => !it.locker)
export const tradesFor = (pid = NET.pid) => (S.mp?.trades || []).filter((t) => t.to === pid || t.from === pid)
const amount = (n) => Math.max(0, Math.floor(+n || 0))
const cleanRes = (o) => {
  const out = {}
  for (const [k, v] of Object.entries(o || {})) if (RES[k] && amount(v) > 0) out[k] = amount(v)
  return out
}
const cleanItems = (a) => [...new Set((Array.isArray(a) ? a : []).map(String))].slice(0, 20)

// whoever wears it puts it down first
function takeOff(it) {
  const s = ownerOf(it.uid)
  if (s) for (const k of Object.keys(s.equip)) if (s.equip[k] === it.uid) s.equip[k] = null
}
const holds = (pid, give) => cleanItems(give.items).every((u) => itemOf(u)?.locker === pid) && Object.entries(cleanRes(give.res)).every(([k, n]) => (lockerRes(pid)[k] || 0) >= n)
function move(from, to, give) {
  for (const u of cleanItems(give.items)) {
    const it = itemOf(u)
    takeOff(it)
    it.locker = to
  }
  const a = (S.mp.lockers[from] ??= { res: {} }).res
  const b = (S.mp.lockers[to] ??= { res: {} }).res
  for (const [k, n] of Object.entries(cleanRes(give.res))) {
    a[k] -= n
    if (a[k] <= 0) delete a[k]
    b[k] = (b[k] || 0) + n
  }
}
export const describe = (give) => {
  const parts = cleanItems(give?.items).length ? [`${cleanItems(give.items).length} item${cleanItems(give.items).length === 1 ? '' : 's'}`] : []
  for (const [k, n] of Object.entries(cleanRes(give?.res))) parts.push(`${n} ${RES[k].short || RES[k].name.toLowerCase()}`)
  return parts.join(', ') || 'nothing'
}

// Host side: carry out one player's locker op. Returns null, or why not.
export function applyLockerOp(pid, m) {
  if (!S.mp || !S.mp.players[pid]) return 'not in this camp'
  const L = (S.mp.lockers ??= {})
  const mine = (L[pid] ??= { res: {} })
  S.mp.trades ??= []
  switch (m.op) {
    case 'keepItem': {
      const it = itemOf(String(m.item))
      if (!it || it.locker) return 'that is not in camp storage'
      const s = ownerOf(it.uid)
      if (s && S.mp.owner[s.id] && S.mp.owner[s.id] !== pid) return `${s.first} carries that, and another player leads them`
      // only your own survivors wear what is in your locker
      if (s && S.mp.owner[s.id] !== pid) takeOff(it)
      it.locker = pid
      return null
    }
    case 'shareItem': {
      const it = itemOf(String(m.item))
      if (!it || it.locker !== pid) return 'that is not in your locker'
      delete it.locker
      return null
    }
    case 'keepRes': {
      const k = String(m.res)
      const n = amount(m.n)
      if (!RES[k] || !n) return 'nothing to move'
      if ((S.res[k] || 0) < n) return `not enough ${RES[k].name.toLowerCase()} in camp`
      S.res[k] -= n
      mine.res[k] = (mine.res[k] || 0) + n
      return null
    }
    case 'shareRes': {
      const k = String(m.res)
      const n = amount(m.n)
      if (!RES[k] || !n) return 'nothing to move'
      if ((mine.res[k] || 0) < n) return 'not that much in your locker'
      mine.res[k] -= n
      if (mine.res[k] <= 0) delete mine.res[k]
      S.res[k] = (S.res[k] || 0) + n
      return null
    }
    case 'give': {
      const to = String(m.to)
      if (to === pid || !S.mp.players[to]) return 'no such player'
      const give = { items: m.items, res: m.res }
      if (!holds(pid, give)) return 'that is not all in your locker any more'
      move(pid, to, give)
      return null
    }
    case 'offer': {
      const to = String(m.to)
      if (to === pid || !S.mp.players[to]) return 'no such player'
      if (S.mp.trades.filter((t) => t.from === pid).length >= MAX_OFFERS) return 'too many open offers'
      const give = { items: cleanItems(m.give?.items), res: cleanRes(m.give?.res) }
      const want = { items: cleanItems(m.want?.items), res: cleanRes(m.want?.res) }
      if (!give.items.length && !Object.keys(give.res).length && !want.items.length && !Object.keys(want.res).length) return 'an empty offer'
      if (!holds(pid, give)) return 'that is not all in your locker'
      if (!holds(to, want)) return 'they do not have all of that'
      S.mp.trades.push({ id: uid('t'), from: pid, to, give, want, at: Date.now() })
      return null
    }
    case 'accept': {
      const t = S.mp.trades.find((x) => x.id === m.id)
      if (!t || t.to !== pid) return 'that offer is gone'
      if (!holds(t.from, t.give) || !holds(t.to, t.want)) {
        S.mp.trades = S.mp.trades.filter((x) => x !== t)
        return 'one side no longer has everything: the offer is off'
      }
      move(t.from, t.to, t.give)
      move(t.to, t.from, t.want)
      S.mp.trades = S.mp.trades.filter((x) => x !== t)
      return null
    }
    case 'decline':
    case 'cancel': {
      const t = S.mp.trades.find((x) => x.id === m.id)
      if (!t || (t.to !== pid && t.from !== pid)) return null
      S.mp.trades = S.mp.trades.filter((x) => x !== t)
      return null
    }
  }
  return 'unknown'
}
// survivors who wear something from another player's locker put it down
// (after a forget, say): tidy up on the host now and then
export function tidyLockers() {
  if (!S.mp?.lockers) return
  // a player the camp forgot: what they kept goes back to the camp
  for (const [pid, L] of Object.entries(S.mp.lockers)) {
    if (S.mp.players[pid]) continue
    for (const [k, n] of Object.entries(L.res || {})) S.res[k] = (S.res[k] || 0) + n
    delete S.mp.lockers[pid]
  }
  for (const it of S.items) {
    if (!it.locker) continue
    if (!S.mp.players[it.locker]) {
      delete it.locker
      continue
    }
    const s = ownerOf(it.uid)
    if (s && S.mp.owner[s.id] && S.mp.owner[s.id] !== it.locker) takeOff(it)
  }
  S.mp.trades = (S.mp.trades || []).filter((t) => S.mp.players[t.from] && S.mp.players[t.to])
}
