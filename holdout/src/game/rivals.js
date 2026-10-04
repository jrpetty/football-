// Rival camps: other always-on camps on the same server. A camp can send
// another a trade (its side held back until they answer), a gift, an
// alliance, or a raiding party. Everything is settled by the two camps'
// own games on the server: the sender's game checks and holds what it
// sends (rivalSend), the target's game settles it (rivalReceive) and
// answers, and the sender's game takes the answer (rivalReply).
import { S, day, log, pay, gain, canAfford, addMoraleEvent, survivorStats, canFight } from './state.js'
import { RES } from './data.js'
import { uid, rand, clamp } from '../core/util.js'

export const RIVAL = {
  // a new camp is left alone for its first days, and a camp can only be
  // raided by the same rival every so often
  safeDays: 5,
  raidGap: 2,
  maxParty: 6,
  // what a winning party carries off: a share of each, up to so much a head
  take: 0.15,
  carry: 30,
  loot: ['food', 'water', 'scrap', 'metal', 'wood', 'pammo', 'rammo', 'shells', 'meds', 'fuel', 'parts'],
}
export function rivals() {
  S.rivals ||= { allies: {}, out: [], inbox: [], log: [], lastRaid: {} }
  return S.rivals
}
const me = () => ({ code: S.mp?.code, name: S.mp?.name || 'A camp' })
function note(text, kind = '') {
  const R = rivals()
  R.log.unshift({ t: S.time, day: day(), text, kind })
  R.log = R.log.slice(0, 40)
  log(text, kind || 'story')
}
const cleanRes = (o) => {
  const out = {}
  for (const [k, v] of Object.entries(o || {})) if (RES[k] && Number.isFinite(+v) && +v > 0) out[k] = Math.min(9999, Math.round(+v))
  return out
}
const resText = (o) =>
  Object.entries(o || {})
    .map(([k, v]) => `${v} ${RES[k]?.name.toLowerCase() || k}`)
    .join(', ') || 'nothing'

// How hard a party hits: each fighter's weapon, health and skill.
export function partyPower(list) {
  let p = 0
  for (const s of list) {
    const st = survivorStats(s)
    p += (st.dmg * (st.gun ? 1.4 : 1) * (0.6 + 0.4 * ((s.hp ?? 100) / st.maxHp)) + st.maxHp * 0.08) * (st.gun ? 1 + st.acc * 0.5 : 1)
  }
  return p
}
// How hard a camp is to break into.
export function campDefence() {
  const lvl = S.fence?.level || 0
  const towers = S.stations.filter((x) => x.type === 'watchtower' && x.level > 0).length
  const turrets = S.stations.filter((x) => x.type === 'turret' && x.level > 0).length
  const home = S.survivors.filter((s) => s.status === 'ok' && canFight(s))
  return lvl * 14 + towers * 10 + turrets * 14 + partyPower(home) * 0.75
}
export function raidable(target) {
  const R = rivals()
  if (R.allies[target]) return 'You are allied with them'
  const last = R.lastRaid[target]
  if (last != null && day() - last < RIVAL.raidGap) return `You raided them on day ${last}: wait until day ${last + RIVAL.raidGap}`
  return null
}

// ---------------------------------------------------------------- sending (our game)
// op: offer {give, want} · gift {give} · ally · unally · raid {ids} · answer {id, yes}
// Returns { c2c } to route to the target camp, or { err }.
export function rivalSend(pid, m) {
  if (!S.mp?.server) return { err: 'Rival camps need an always-on camp on the server' }
  const to = String(m.to || '').toUpperCase()
  if (!/^[A-Z0-9]{5}$/.test(to) || to === S.mp.code) return { err: 'No such camp' }
  const R = rivals()
  const from = me()
  const who = S.mp.players?.[pid]?.name || 'Someone'
  if (m.op === 'offer' || m.op === 'gift') {
    const give = cleanRes(m.give)
    const want = m.op === 'offer' ? cleanRes(m.want) : {}
    if (!Object.keys(give).length && !Object.keys(want).length) return { err: 'Nothing to send' }
    if (!canAfford(give)) return { err: 'The camp hasn’t got that much' }
    pay(give)
    const id = uid('rv')
    if (m.op === 'offer') R.out.push({ id, kind: 'offer', to, give, want, at: S.time })
    note(m.op === 'offer' ? `${who} offered ${m.toName || to} ${resText(give)} for ${resText(want)}.` : `${who} sent ${m.toName || to} ${resText(give)}.`)
    return { c2c: { to, from, msg: { kind: m.op, id, give, want, by: who } } }
  }
  if (m.op === 'ally' || m.op === 'unally') {
    if (m.op === 'unally') {
      delete R.allies[to]
      note(`${who} ended the alliance with ${m.toName || to}.`, 'bad')
    } else note(`${who} asked ${m.toName || to} for an alliance.`)
    return { c2c: { to, from, msg: { kind: m.op, by: who } } }
  }
  if (m.op === 'raid') {
    const why = raidable(to)
    if (why) return { err: why }
    if (day() < RIVAL.safeDays) return { err: `Your camp is too new: wait until day ${RIVAL.safeDays}` }
    const ids = (Array.isArray(m.ids) ? m.ids : []).slice(0, RIVAL.maxParty)
    const party = ids.map((id) => S.survivors.find((s) => s.id === id)).filter((s) => s && s.status === 'ok' && canFight(s))
    if (!party.length) return { err: 'Pick who goes' }
    for (const s of party) {
      s.status = 'raiding'
      s.job = null
    }
    const id = uid('rv')
    R.out.push({ id, kind: 'raid', to, ids: party.map((s) => s.id), at: S.time })
    R.lastRaid[to] = day()
    note(`${party.map((s) => s.first).join(', ')} set out to raid ${m.toName || to}.`)
    return { c2c: { to, from, msg: { kind: 'raid', id, power: partyPower(party), n: party.length, by: who } } }
  }
  if (m.op === 'answer') {
    const e = R.inbox.find((x) => x.id === m.id && !x.done)
    if (!e) return { err: 'That’s been settled' }
    const yes = !!m.yes
    if (e.kind === 'offer' && yes) {
      if (!canAfford(e.want)) return { err: 'The camp hasn’t got what they want' }
      pay(e.want)
      gain(e.give)
      note(`${who} took the trade from ${e.from.name}: ${resText(e.give)} for ${resText(e.want)}.`, 'good')
    } else if (e.kind === 'ally' && yes) {
      R.allies[e.from.code] = e.from.name
      note(`${who} agreed to an alliance with ${e.from.name}.`, 'good')
    } else note(`${who} turned down ${e.from.name}’s ${e.kind === 'ally' ? 'alliance' : 'trade'}.`)
    e.done = yes ? 'yes' : 'no'
    return { c2c: { to: e.from.code, from, msg: { kind: 'answer', id: e.id, of: e.kind, yes, want: e.want } } }
  }
  return { err: 'Unknown' }
}

// ---------------------------------------------------------------- receiving (their game)
// A rival's message reaches this camp. Returns a reply to route back, or null.
export function rivalReceive(from, msg) {
  if (!from?.code || !msg) return null
  const R = rivals()
  const name = String(from.name || from.code).slice(0, 40)
  if (msg.kind === 'offer') {
    R.inbox.unshift({ id: msg.id, kind: 'offer', from: { code: from.code, name }, give: cleanRes(msg.give), want: cleanRes(msg.want), by: msg.by, at: S.time })
    R.inbox = R.inbox.slice(0, 30)
    note(`${name} offers ${resText(msg.give)} for ${resText(msg.want)}. (Rival camps)`)
    return null
  }
  if (msg.kind === 'gift') {
    gain(cleanRes(msg.give))
    note(`${name} sent a gift: ${resText(msg.give)}.`, 'good')
    if (R.allies[from.code]) addMoraleEvent(`A gift from ${name}`, 2, 1)
    return null
  }
  if (msg.kind === 'ally') {
    if (R.allies[from.code]) return { kind: 'answer', id: null, of: 'ally', yes: true }
    R.inbox.unshift({ id: uid('rv'), kind: 'ally', from: { code: from.code, name }, by: msg.by, at: S.time })
    note(`${name} proposes an alliance. (Rival camps)`)
    return null
  }
  if (msg.kind === 'unally') {
    delete R.allies[from.code]
    note(`${name} ended the alliance.`, 'bad')
    return null
  }
  if (msg.kind === 'answer') return rivalReply(from, msg)
  if (msg.kind === 'raid') {
    // too new, or a friend: they turn back at the gate
    if (day() < RIVAL.safeDays || R.allies[from.code]) {
      note(`Raiders from ${name} turned back at the gate.`)
      return { kind: 'raidDone', id: msg.id, won: false, turned: true, loot: {}, hurt: 0 }
    }
    const atk = Math.max(1, +msg.power || 1) * rand(0.75, 1.25)
    const def = campDefence() * rand(0.75, 1.25)
    const won = atk > def
    const loot = {}
    if (won) {
      let room = RIVAL.carry * clamp(+msg.n || 1, 1, RIVAL.maxParty)
      for (const k of RIVAL.loot) {
        const v = Math.floor(Math.min(room, (S.res[k] || 0) * RIVAL.take))
        if (v < 1) continue
        loot[k] = v
        room -= v
        if (room <= 0) break
      }
      pay(loot)
      // someone at the wall gets hurt
      const home = S.survivors.filter((s) => s.status === 'ok' && canFight(s))
      if (home.length && Math.random() < 0.5) {
        const v = home[Math.floor(Math.random() * home.length)]
        v.status = 'injured'
        v.hp = Math.min(v.hp ?? 100, 30)
      }
      addMoraleEvent(`Raided by ${name}`, -8, 2)
      note(`Raiders from ${name} got over the wall and took ${resText(loot)}.`, 'bad')
    } else {
      addMoraleEvent(`Drove off raiders from ${name}`, 4, 1.5)
      note(`Raiders from ${name} came at the wall and were driven off.`, 'good')
    }
    const hurt = won ? (Math.random() < 0.35 ? 1 : 0) : Math.max(1, Math.round((+msg.n || 1) * rand(0.3, 0.7)))
    return { kind: 'raidDone', id: msg.id, won, loot, hurt }
  }
  if (msg.kind === 'raidDone') return rivalReply(from, msg)
  return null
}

// ---------------------------------------------------------------- answers (our game)
export function rivalReply(from, msg) {
  const R = rivals()
  const name = String(from?.name || from?.code || 'They').slice(0, 40)
  if (msg.kind === 'answer') {
    if (msg.of === 'ally') {
      if (msg.yes) {
        R.allies[from.code] = name
        note(`${name} agreed to an alliance.`, 'good')
      } else note(`${name} turned down the alliance.`)
      return null
    }
    const o = R.out.find((x) => x.id === msg.id && x.kind === 'offer')
    if (!o) return null
    R.out = R.out.filter((x) => x !== o)
    if (msg.yes) {
      gain(o.want)
      note(`${name} took the trade: ${resText(o.give)} for ${resText(o.want)}.`, 'good')
    } else {
      gain(o.give)
      note(`${name} turned the trade down; the goods came back.`)
    }
    return null
  }
  if (msg.kind === 'raidDone') {
    const o = R.out.find((x) => x.id === msg.id && x.kind === 'raid')
    if (!o) return null
    R.out = R.out.filter((x) => x !== o)
    const party = o.ids.map((id) => S.survivors.find((s) => s.id === id)).filter(Boolean)
    let hurt = Math.min(+msg.hurt || 0, party.length)
    for (const s of party) {
      s.status = 'ok'
      if (hurt > 0) {
        s.status = 'injured'
        s.hp = Math.min(s.hp ?? 100, 35)
        hurt--
      }
    }
    if (msg.turned) note(`The raiding party turned back from ${name}: no way in.`)
    else if (msg.won) {
      gain(cleanRes(msg.loot))
      addMoraleEvent(`A raid on ${name} paid off`, 3, 1)
      note(`The raid on ${name} paid off: ${resText(msg.loot)} carried home.`, 'good')
    } else {
      addMoraleEvent(`Beaten back from ${name}`, -4, 1.5)
      note(`The raid on ${name} failed. ${party.filter((s) => s.status === 'injured').map((s) => s.first).join(', ') || 'Everyone'} came back hurt.`, 'bad')
    }
    return null
  }
  return null
}
// A rival camp that can't be reached: what was held back comes home.
export function rivalBounce(msg) {
  const R = rivals()
  const o = R.out.find((x) => x.id === msg?.id)
  if (!o) return
  R.out = R.out.filter((x) => x !== o)
  if (o.kind === 'offer') gain(o.give)
  if (o.kind === 'raid') for (const id of o.ids) {
    const s = S.survivors.find((x) => x.id === id)
    if (s?.status === 'raiding') s.status = 'ok'
  }
  note(`Couldn’t reach that camp: ${o.kind === 'raid' ? 'the party came back' : 'the goods came back'}.`)
}
// Once a day: anything sent out that never got an answer (a lost message,
// a server restart) comes home.
export function rivalStale() {
  if (!S.rivals) return
  for (const o of [...S.rivals.out]) if (S.time - (o.at || 0) > 360) rivalBounce({ id: o.id })
}
