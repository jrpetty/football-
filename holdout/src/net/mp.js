// Multiplayer. One browser hosts the camp: it runs the simulation, keeps the
// save and is the final word on every change. Friends join with the camp's
// code. Each guest gets the whole camp once, then a compact diff a few times
// a second. Whatever a guest does in the interface changes their copy at
// once; a diff of their copy against what the host last said goes to the
// host, which checks it (whose survivors, enough resources?), applies it and
// says so in its next diff. A guest's own runs play out on their machine and
// what they bring home arrives the same way.
import { S, setState, NET, getS, day } from '../game/state.js'
import { RES, GAME_MIN_PER_SEC, BELTS } from '../game/data.js'
import { beltSpeed } from '../game/belts.js'
import { bus, uid } from '../core/util.js'
import { diff, applyFields, clone } from './delta.js'
import { openCarrier, newId } from './transport.js'
import { view } from '../render/view.js'

export const PROTO = 1
export const COLORS = ['#e8b54a', '#5fb2ea', '#e3685b', '#7bc66a', '#c58be6', '#ec9347', '#4fd3c1', '#ea70aa']

const ADD = { add: true }
const set = (...k) => new Set(k)
// What the host shares. Belt items and history travel on their own.
const HOST_POL = { skip: set('settings', 'saved', 'hist'), kids: { links: { each: { skip: set('items', 'moved') } } } }
// Keys only the host's simulation changes.
const HOST_ONLY = set('settings', 'saved', 'hist', 'time', 'weather', 'raid', 'nextRaid', 'speed', 'mp', 'over', 'seed', 'version', 'created', 'cityLocs')
const GUEST_POL = { skip: HOST_ONLY, kids: { res: { all: ADD }, stats: { all: ADD }, signal: { kids: { paid: { all: ADD } } }, links: { each: { skip: set('items', 'moved', 'flow', 'jam') } } } }
// Bringing a guest's copy in line with the host's (exact, no sums).
const FIX_POL = { skip: set('settings', 'saved', 'hist', 'time'), kids: { links: { each: { skip: set('items') } } } }
const LOCAL = set('settings', 'time')
// A guest fighting a horde for the camp (the raid captain) owns the raid.
const CAPT_SKIP = new Set([...HOST_ONLY].filter((k) => k !== 'raid'))
const GUEST_POL_CAPT = { ...GUEST_POL, skip: CAPT_SKIP }
const FIX_POL_CAPT = { ...FIX_POL, skip: set('settings', 'saved', 'hist', 'time', 'raid') }
const LOCAL_CAPT = set('settings', 'time', 'raid')

// Host events that guests hear too (their listeners only draw and toast).
const FORWARD = set('goal', 'levelup', 'perkReady', 'infected', 'turned', 'season', 'crafted', 'broken', 'event', 'weather', 'newDay', 'recruit', 'raidWarn', 'raidResolved', 'victory', 'gameover', 'death', 'built', 'produced', 'expanded', 'recruitJoined', 'recruitGone', 'log', 'signalPhase', 'raidStart')
const GUEST_EV = set('goal', 'levelup', 'perkReady', 'infected', 'turned', 'season', 'crafted', 'broken', 'event', 'weather', 'newDay', 'recruit', 'raidWarn', 'victory', 'gameover', 'death', 'built', 'produced', 'expanded', 'recruitJoined', 'recruitGone', 'log')

const DELTA_EVERY = 0.35
const now = () => performance.now() / 1000

// ---------------------------------------------------------------- identity
const ID_KEY = 'holdout.player'
export function me() {
  let p = null
  try {
    p = JSON.parse(localStorage.getItem(ID_KEY) || 'null')
  } catch {}
  if (!p?.pid) p = { pid: 'p' + newId(10), name: '' }
  // ?as=Name plays as someone else in this browser (several tabs, testing)
  const as = new URLSearchParams(location.search).get('as')
  if (as) p = { pid: 'p-' + as.toLowerCase().replace(/[^a-z0-9]/g, ''), name: as }
  return p
}
export function saveMe(p) {
  try {
    localStorage.setItem(ID_KEY, JSON.stringify(p))
  } catch {}
}
// What the title screen asked for, kept across reloads of this tab.
const INTENT_KEY = 'holdout.mpintent'
export function readIntent() {
  try {
    return JSON.parse(sessionStorage.getItem(INTENT_KEY) || 'null')
  } catch {
    return null
  }
}
export function writeIntent(i) {
  try {
    if (i) sessionStorage.setItem(INTENT_KEY, JSON.stringify(i))
    else sessionStorage.removeItem(INTENT_KEY)
  } catch {}
}

// A fresh camp's multiplayer record.
export function initMp(code, p) {
  S.mp = { code, host: p.pid, players: { [p.pid]: { name: p.name || 'Host', color: COLORS[0], since: day() } }, owner: {} }
}

// Survivors and stations travel by id; everything else as plain data.
function packArgs(args) {
  return args.map((a) => {
    if (!a || typeof a !== 'object') return a
    if (a.skills && a.id) return { $s: a.id, first: a.first, name: a.name }
    if (a.type && a.id && 'x' in a && 'z' in a) return { $st: a.id }
    if (a.uid) return { $it: a.uid, type: a.type, q: a.q }
    try {
      const j = JSON.stringify(a)
      return j.length < 1500 ? JSON.parse(j) : null
    } catch {
      return null
    }
  })
}
function unpackArgs(args) {
  return args.map((a) => {
    if (!a || typeof a !== 'object') return a
    if (a.$s) return getS(a.$s) || { id: a.$s, first: a.first, name: a.name, skills: {}, equip: {} }
    if (a.$st) return S.stations.find((x) => x.id === a.$st) || null
    if (a.$it) return S.items.find((x) => x.uid === a.$it) || a
    return a
  })
}

// Bus events a diff should raise, so scenes and panels catch up.
function raiseFor(node, quiet) {
  if (!node || node[0] !== 2) return
  const f = node[1]
  const ev = new Set()
  const st = f.stations
  if (st) {
    if (st[0] !== 3 || st[2].a || st[2].d) ev.add('stations')
    else for (const n of Object.values(st[2].m || {})) if (n[0] !== 2 || ['level', 'building', 'rot', 'x', 'z', 'type'].some((k) => k in n[1])) ev.add('stations')
  }
  const ln = f.links
  if (ln && (ln[0] !== 3 || ln[2].a || ln[2].d || Object.values(ln[2].m || {}).some((n) => n[0] !== 2 || 'tier' in n[1] || 'res' in n[1]))) ev.add('links')
  if (f.survivors || f.items) ev.add('change')
  if (f.fence && (f.fence[0] !== 2 || Object.keys(f.fence[1]).some((k) => k !== 'hp') || !quiet)) ev.add('fence')
  if (f.expansions || f.expanding) ev.add('expansions')
  if (f.vehicles) ev.add('vehicles')
  if (f.outposts) ev.add('outposts')
  if (f.story) ev.add('story')
  for (const e of ev) bus.emit(e)
}

// ================================================================ session
export class Session {
  constructor(game, role, code, who) {
    this.game = game
    this.role = role
    this.code = code
    this.pid = who.pid
    this.name = who.name
    this.c = null
    this.chat = []
    this.cams = {}
    // an always-on camp's host is the server itself, not a player
    this.server = !!who.server
    this.online = new Set(this.server ? [] : [who.pid])
    this.status = 'connecting'
    this.t = { delta: 0, belts: 0, hist: 0, cam: 0, flush: 0, beat: 0, owners: 0 }
    this.lastHist = ''
    NET.role = role
    NET.pid = who.pid
    // host
    this.peers = new Map() // peer -> {pid, seen}
    this.acks = {}
    this.coopRuns = {} // run leader -> survivors they answer for while out
    this.n = 0
    this.shadow = null
    this.outEv = []
    // guest
    this.H = null
    this.seq = 0
    this.pending = []
    this.hostPeer = null
    this.lastN = 0
    this.heard = now()
  }

  // ---------------------------------------------------------------- start
  async host(carrier = null) {
    this.shadow = clone(this.view())
    this.hookBus()
    this.c = carrier
    // after a reload the broker can hold the old connection's name for a few
    // seconds: try again before giving up
    for (let tries = 0; !this.c; tries++) {
      try {
        this.c = await openCarrier(this.code, true)
      } catch (e) {
        if (e.message === 'code-taken' && tries < 5) {
          await new Promise((r) => setTimeout(r, 3000))
          continue
        }
        this.status = 'offline'
        this.error = e.message
        throw e
      }
    }
    this.status = 'live'
    this.c.on('msg', (m, from) => this.onHost(m, from))
    this.c.on('left', (peer) => this.dropPeer(peer, 'left'))
    this.c.on('status', (ok, code) => {
      if (code === 'not_permitted' && this.status !== 'offline') {
        this.status = 'offline'
        return this.game.ui?.netProblem('not_permitted')
      }
      if (this.status !== 'offline') this.status = ok ? 'live' : 'reconnecting'
    })
    this.advertise()
    return this
  }
  // Join: resolves with the camp once the host has sent it.
  async join() {
    this.c = await openCarrier(this.code, false)
    this.c.on('msg', (m, from) => this.onGuest(m, from))
    this.c.on('status', (ok) => (this.status = ok ? 'live' : 'reconnecting'))
    this.c.on('left', (peer) => peer === this.hostPeer && this.hostLost())
    this.c.on('host', (id) => {
      // the host's browser came back on a new line: same camp, new address
      this.hostPeer = id
      this.heard = now()
      this.askResync()
    })
    return new Promise((resolve, reject) => {
      this.joined = resolve
      const hello = () => this.c.send({ t: 'hello', pid: this.pid, name: this.name, v: PROTO })
      hello()
      const iv = setInterval(() => (this.H ? clearInterval(iv) : hello()), 4000)
      setTimeout(() => {
        if (this.H) return
        clearInterval(iv)
        reject(new Error(this.refused || 'no-camp'))
      }, 16000)
      this.refuse = (why) => {
        clearInterval(iv)
        reject(new Error(why))
      }
    })
  }
  // The camp as shared: no settings, no save stamp.
  view() {
    const o = {}
    for (const k in S) if (!HOST_POL.skip.has(k)) o[k] = S[k]
    return o
  }
  close() {
    this.status = 'gone'
    try {
      if (this.role === 'host') this.c?.send({ t: 'bye' })
      else this.c?.send({ t: 'bye' }, this.hostPeer)
    } catch {}
    setTimeout(() => this.c?.close(), 150)
    this.unhook?.()
  }

  // ---------------------------------------------------------------- host
  hookBus() {
    const orig = bus.emit
    const self = this
    bus.emit = function (ev, ...args) {
      orig.call(this, ev, ...args)
      if (FORWARD.has(ev) && self.peers.size) self.outEv.push([ev, packArgs(args)])
    }
    this.unhook = () => (bus.emit = orig)
  }
  advertise() {
    if (!this.c?.advertise) return
    const players = Object.keys(S.mp.players).length
    this.c.advertise({ code: this.code, name: S.mp.name || (S.mp.players[this.pid]?.name || 'A') + "'s camp", day: day(), pop: S.survivors.length, on: this.online.size, players, v: PROTO, kind: this.server ? 'server' : 'hosted', public: S.mp.public !== false })
  }
  onHost(m, from) {
    if (!m || typeof m !== 'object') return
    const P = this.peers.get(from)
    if (P) P.seen = now()
    if (m.t === 'hello') return this.welcome(m, from)
    if (!P) return m.t !== 'bye' && this.c.send({ t: 'who' }, from)
    const pid = P.pid
    switch (m.t) {
      case 'patch':
        return this.takePatch(pid, from, m)
      case 'cam':
        this.cams[pid] = { x: +m.x || 0, z: +m.z || 0, w: String(m.w || '').slice(0, 60) }
        this.camsDirty = true
        return
      case 'chat':
        return this.say(pid, String(m.text || '').slice(0, 240))
      case 'resync':
        return this.sendSnap(from, pid)
      case 'rcmd': {
        // a horde fought by a captain: their game carries the order out
        const cap = S.raid?.captain
        if (cap && cap !== this.pid) return cap !== pid && this.c.send({ ...m, pid }, this.peerOf(cap))
        return this.game.base?.raidCommand?.(pid, m)
      }
      case 'rz':
        if (S.raid?.captain === pid) this.c.send(m)
        return
      case 'raidDone':
        if (S.raid?.captain !== pid && this.lastCaptain !== pid) return
        this.lastCaptain = null
        S.raid = null
        return this.game.raidOver?.(m.report || {}, !!m.finale)
      case 'captainNo':
        if (S.raid?.captain === pid) this.captainLost()
        return
      case 'admin':
        return this.adminOp(pid, m)
      case 'relay':
        return this.routeRelay(pid, m.to, m.d)
      case 'bye':
        return this.dropPeer(from, 'bye')
    }
  }
  // ---- messages between players (co-op runs), always through the host,
  // which also notes who answers for which survivors while a run is out
  relay(to, d) {
    if (this.role === 'host') return this.routeRelay(this.pid, to, d)
    this.c?.send({ t: 'relay', to, d }, this.hostPeer)
  }
  routeRelay(from, to, d) {
    if (!d || typeof d !== 'object') return
    if (d.k === 'runGo') this.coopRuns[from] = new Set(Object.values(d.roster || {}).flat().map(String))
    else if (d.k === 'runClose') delete this.coopRuns[from]
    // the leader's last word on everyone's survivors may still be on its way
    else if (d.k === 'runEnd') {
      const set = this.coopRuns[from]
      setTimeout(() => this.coopRuns[from] === set && delete this.coopRuns[from], 15000)
    }
    // a run's frames are worth dropping when the line backs up: the next one
    // says it all again
    if (d.k === 'rf' && this.c?.backlog > 8) return
    const targets = to === '*' ? [...this.online] : [].concat(to)
    for (const p of targets) {
      if (p === from) continue
      if (p === this.pid) {
        if (!this.server) this.game.coop?.onRelay(from, d)
        continue
      }
      const peer = this.peerOf(p)
      if (peer) this.c.send({ t: 'relayed', from, d }, peer)
    }
  }
  peerOf(pid) {
    for (const [peer, P] of this.peers) if (P.pid === pid) return peer
    return null
  }
  // ---- the camp's admin: the host, or the player who made a server camp
  isAdmin(pid = this.pid) {
    return (this.role === 'host' && pid === this.pid) || (!!S.mp?.admin && S.mp.admin === pid)
  }
  adminOp(pid, m) {
    if (!this.isAdmin(pid)) return
    if (m.op === 'assign') return this.assign(String(m.sid), m.pid ? String(m.pid) : null)
    if (m.op === 'kick') return this.kick(String(m.pid))
    if (m.op === 'forget') return this.forget(String(m.pid))
    if (m.op === 'speed' && [0, 1, 2, 4].includes(m.v)) {
      S.speed = m.v
      this.t.delta = 0
    }
  }
  // ---- raid captains: with nobody's browser running the camp, a horde is
  // handed to a player who is in camp to fight live for everyone
  raidTo(R) {
    const inCamp = [...this.online].filter((p) => p !== this.pid && this.peerOf(p) && /^In camp/.test(this.cams[p]?.w || ''))
    const pick = inCamp.find((p) => p === S.mp.admin) || inCamp[0]
    if (!pick) return false
    S.raid = { count: R.count, killed: 0, spawned: 0, t: 0, acc: 0, captain: pick, lvl: R.lvl, blood: !!R.blood, finale: !!R.finale, size: R.size, side: 's' }
    this.raidR = R
    this.lastCaptain = pick
    this.c.send({ t: 'captain', R }, this.peerOf(pick))
    this.say(null, `The horde is at the wall. ${S.mp.players[pick]?.name || 'Someone'} has the defence.`)
    this.t.delta = 0
    return true
  }
  captainLost() {
    const R = S.raid
    if (!R) return
    const left = { ...(this.raidR || {}), count: Math.max(1, R.count - (R.killed || 0)), lvl: R.lvl, blood: R.blood, finale: R.finale }
    S.raid = null
    this.lastCaptain = null
    this.game.raidAuto?.(left)
  }
  welcome(m, from) {
    if (m.v !== PROTO) return this.c.send({ t: 'full', why: 'version' }, from)
    const pid = String(m.pid || '').slice(0, 24)
    if (!pid || pid === this.pid) return this.c.send({ t: 'full', why: 'self' }, from)
    if ((this.kicked?.[pid] || 0) > now()) return this.c.send({ t: 'full', why: 'refused' }, from)
    // a reload or a second tab replaces the old connection
    for (const [peer, P] of this.peers) if (P.pid === pid && peer !== from) this.peers.delete(peer)
    const fresh = !this.peers.has(from)
    // a hello repeated while the camp is still on its way gets no second copy
    const old = this.peers.get(from)
    if (old && now() - (old.snapAt || 0) < 8) return
    this.peers.set(from, { pid, seen: now() })
    const players = S.mp.players
    const used = new Set(Object.values(players).map((p) => p.color))
    if (!players[pid]) players[pid] = { name: '', color: COLORS.find((c) => !used.has(c)) || COLORS[Object.keys(players).length % COLORS.length], since: day() }
    players[pid].name = String(m.name || 'Survivor').slice(0, 20)
    this.acks[pid] = 0
    this.online.add(pid)
    this.sendSnap(from, pid)
    if (fresh) {
      this.say(null, `${players[pid].name} joined the camp.`)
      this.game.ui?.toast(`${players[pid].name} joined`, 'good')
    }
    this.advertise()
  }
  sendSnap(peer, pid) {
    const P = this.peers.get(peer)
    if (P) {
      if (now() - (P.snapAt || 0) < 4) return
      P.snapAt = now()
    }
    this.tickDelta(true)
    this.c.send({ t: 'snap', n: this.n, s: this.shadow, hist: S.hist || null, belts: this.beltFrame(), you: pid, chat: this.chat.slice(-20) }, peer)
  }
  dropPeer(peer, why) {
    const P = this.peers.get(peer)
    if (!P) return
    this.peers.delete(peer)
    if ([...this.peers.values()].some((x) => x.pid === P.pid)) return
    this.online.delete(P.pid)
    delete this.cams[P.pid]
    this.camsDirty = true
    // when they come back they hear what happened since
    if (S.mp.players[P.pid]) S.mp.players[P.pid].left = Math.round(S.time)
    const name = S.mp.players[P.pid]?.name || 'Someone'
    if (S.raid?.captain === P.pid) this.captainLost()
    // whoever was out on a run with them comes home with nothing
    const run = this.coopRuns[P.pid]
    delete this.coopRuns[P.pid]
    // (unless a friend still out leads the run they are on)
    const away = new Set()
    for (const [p, set] of Object.entries(this.coopRuns)) if (this.online.has(p)) for (const id of set) away.add(id)
    const back = S.survivors.filter((s) => s.status === 'mission' && !away.has(s.id) && (S.mp.owner[s.id] === P.pid || run?.has(s.id)))
    for (const s of back) s.status = 'ok'
    for (const v of S.vehicles || []) if (v.out === P.pid) v.out = false
    if (back.length) bus.emit('change')
    this.say(null, `${name} ${why === 'bye' ? 'left' : 'lost connection'}.${back.length ? ` Their squad made it back to camp.` : ''}`)
    this.game.ui?.toast(`${name} left`, '')
    this.advertise()
  }
  // A guest's change: check it, apply it, acknowledge it.
  takePatch(pid, peer, m) {
    const seq = +m.seq || 0
    if (seq <= (this.acks[pid] || 0)) return
    const node = m.d
    let why = null
    try {
      why = node?.[0] === 2 ? this.admit(pid, node) : 'bad patch'
      if (!why) {
        applyFields(S, node[1], { keepOrder: true })
        this.afterPatch(pid, node)
        raiseFor(node)
        bus.emit('netPatch', pid, node)
      }
    } catch (e) {
      console.warn('holdout net: patch failed', e)
      why = 'error'
    }
    this.acks[pid] = seq
    if (why) this.c.send({ t: 'rej', seq, why }, peer)
    this.t.delta = Math.min(this.t.delta, 0.05)
  }
  admit(pid, node) {
    const f = node[1]
    const captain = !!S.raid && S.raid.captain === pid
    for (const k of Object.keys(f)) if (HOST_ONLY.has(k) && !(captain && k === 'raid')) delete f[k]
    if (f.res) {
      if (f.res[0] !== 2) delete f.res
      else
        for (const [k, n] of Object.entries(f.res[1])) {
          if (n[0] !== 4 || !(k in RES)) {
            delete f.res[1][k]
            continue
          }
          if (n[1] < 0 && (S.res[k] || 0) + n[1] < -0.5) return `not enough ${RES[k].name.toLowerCase()}`
        }
    }
    const sv = f.survivors
    if (sv) {
      if (sv[0] !== 3) return 'bad patch'
      const P = sv[2]
      // the captain of a horde fight answers for every defender
      const run = this.coopRuns[pid]
      const mine = (id) => {
        const o = S.mp.owner[id]
        return captain || run?.has(id) || !o || o === pid || !S.mp.players[o]
      }
      if (P.m) for (const id of Object.keys(P.m)) if (!mine(id)) delete P.m[id]
      if (P.d) P.d = P.d.filter(mine)
      delete P.o
    }
    return null
  }
  afterPatch(pid, node) {
    const P = node[1].survivors?.[2]
    for (const [, s] of P?.a || []) if (s?.id && !S.mp.owner[s.id]) S.mp.owner[s.id] = pid
  }
  // The diff since the last one went out, to everyone.
  tickDelta(force = false, beat = false) {
    if (!this.peers.size && !force) return
    const node = diff(this.shadow, this.view(), HOST_POL)
    const ev = this.outEv
    const on = [...this.online].sort().join()
    if (!node && !ev.length && !this.camsDirty && on === this.lastOn && !this.acksDirty() && !beat) return
    if (node) applyFields(this.shadow, node[1])
    this.n++
    this.outEv = []
    const msg = { t: 'd', n: this.n, ack: { ...this.acks } }
    if (node) msg.d = node
    if (ev.length) msg.ev = ev
    if (this.camsDirty || on !== this.lastOn) {
      msg.cams = this.cams
      msg.on = [...this.online]
      this.camsDirty = false
      this.lastOn = on
    }
    this.sentAcks = JSON.stringify(this.acks)
    this.c?.send(msg)
    this.t.beat = 0
  }
  acksDirty() {
    return JSON.stringify(this.acks) !== this.sentAcks
  }
  // Items on belts: positions only, a few times a minute is plenty.
  beltFrame() {
    const out = {}
    for (const l of S.links || []) if (l.items?.length) out[l.id] = l.items.map((x) => Math.round(x * 20) / 20)
    return out
  }
  say(pid, text) {
    if (!text) return
    const msg = { t: 'chat', id: uid('c'), pid, text, at: Date.now() }
    // a guest's own line comes back from the host with everyone else's
    if (this.role === 'host') {
      this.addChat(msg)
      this.c?.send(msg)
    } else if (pid) this.c?.send({ t: 'chat', text }, this.hostPeer)
    else this.addChat(msg)
  }
  addChat(msg) {
    this.chat.push(msg)
    if (this.chat.length > 60) this.chat.shift()
    this.game.ui?.netChat?.(msg)
  }

  // ---------------------------------------------------------------- guest
  onGuest(m, from) {
    if (!m || typeof m !== 'object') return
    if (m.t === 'snap') {
      if (m.you !== this.pid) return
      this.hostPeer = from
      return this.takeSnap(m)
    }
    if (m.t === 'full') return this.refuse?.(m.why === 'version' ? 'version' : m.why === 'self' ? 'self' : 'refused')
    if (from !== this.hostPeer || this.status === 'gone') return
    this.heard = now()
    if (this.status === 'lost') this.status = 'live'
    switch (m.t) {
      case 'd':
        return this.takeDelta(m)
      case 'b':
        return this.takeBelts(m.l)
      case 'h':
        S.hist = m.h
        return
      case 'chat':
        return this.addChat(m)
      case 'rej':
        this.forceFull = true
        return this.game.ui?.toast(`The host turned that down: ${m.why}`, 'bad')
      case 'rz':
        return this.captain ? null : this.game.base?.raidMirror?.(m)
      case 'relayed':
        return this.game.coop?.onRelay(m.from, m.d)
      case 'captain':
        return this.game.captainRaid?.(m.R) ? (this.captain = true) : this.c.send({ t: 'captainNo' }, this.hostPeer)
      case 'rcmd':
        return this.captain && this.game.base?.raidCommand?.(m.pid, m)
      case 'who':
        return this.c.send({ t: 'hello', pid: this.pid, name: this.name, v: PROTO })
      case 'bye':
        return this.hostLost(true)
      case 'kick':
        this.status = 'gone'
        this.c.close()
        return this.game.netGone?.('The host took you out of the camp.')
    }
  }
  takeSnap(m) {
    this.H = m.s
    this.lastN = m.n
    this.heard = now()
    if (this.joined) {
      // first contact: this is the camp
      const st = clone(m.s)
      st.settings = this.localSettings()
      st.hist = m.hist || { res: {} }
      setState(st)
      this.shadow = clone(m.s)
      this.takeBelts(m.belts)
      for (const c of m.chat || []) this.chat.push(c)
      this.status = 'live'
      const r = this.joined
      this.joined = null
      return r(st)
    }
    if (m.hist) S.hist = m.hist
    this.reconcile(null)
    raiseFor([2, { stations: [0], links: [0], survivors: [0], fence: [0], expansions: [0], vehicles: [0] }])
  }
  // A guest's own settings: from their last multiplayer game, or from their
  // single-player camp.
  localSettings() {
    const def = { sound: true, quality: 'high', tilt: true, edgePan: true }
    try {
      const mp = JSON.parse(localStorage.getItem('holdout.mpsettings') || 'null')
      if (mp) return { ...def, ...mp }
      const solo = JSON.parse(localStorage.getItem('holdout.save.v2') || 'null')?.settings
      return { ...def, ...(solo || {}) }
    } catch {
      return def
    }
  }
  takeDelta(m) {
    if (m.n <= this.lastN) return
    if (m.n !== this.lastN + 1) return this.askResync()
    this.lastN = m.n
    if (m.d) applyFields(this.H, m.d[1])
    const acked = m.ack?.[this.pid] || 0
    const before = this.pending.length
    this.pending = this.pending.filter((p) => p.seq > acked)
    // anything just confirmed (or turned down) means a full look: the host's
    // word replaces ours
    if (m.d || this.pending.length !== before) this.reconcile(m.d, this.pending.length !== before || this.forceFull)
    this.forceFull = false
    if (m.cams) this.cams = m.cams
    if (m.on) this.online = new Set(m.on)
    if (m.d?.[1].time) {
      this.hostTime = this.H.time
      this.hostAt = now()
    }
    if (m.d) raiseFor(m.d, true)
    for (const [ev, args] of m.ev || []) this.hostEvent(ev, args)
  }
  askResync() {
    if (now() - (this.resyncAt || 0) < 3) return
    this.resyncAt = now()
    this.c.send({ t: 'resync' }, this.hostPeer)
  }
  // Bring this copy up to the host's, keeping changes still on their way.
  reconcile(node, full = false) {
    this.flush()
    const local = this.captain ? LOCAL_CAPT : LOCAL
    if (node && !full && !this.pending.length) {
      applyFields(S, node[1], { skip: local })
      applyFields(this.shadow, node[1])
      return
    }
    const target = clone(this.H)
    for (const p of this.pending) applyFields(target, p.d[1], { keepOrder: true })
    const fix = diff(S, target, this.captain ? FIX_POL_CAPT : FIX_POL, '', true)
    if (fix) applyFields(S, fix[1], { skip: local })
    this.shadow = target
  }
  // Anything this player changed since the last look goes to the host.
  flush() {
    if (!this.shadow || !this.hostPeer) return
    const node = diff(this.shadow, S, this.captain ? GUEST_POL_CAPT : GUEST_POL)
    if (!node) return
    applyFields(this.shadow, node[1])
    const seq = ++this.seq
    this.pending.push({ seq, d: node })
    this.c.send({ t: 'patch', seq, d: node }, this.hostPeer)
  }
  takeBelts(l) {
    if (!l) return
    for (const ln of S.links || []) ln.items = l[ln.id] ? l[ln.id].slice() : []
  }
  hostEvent(ev, args) {
    const a = unpackArgs(args || [])
    if (ev === 'raidStart') return this.game.base?.mirrorRaid?.(true, a[0])
    if (ev === 'raidResolved') {
      if (this.captainReported) return (this.captainReported = false)
      return bus.emit('raidResolved', a[0])
    }
    if (ev === 'signalPhase') return this.game.ui?.toast(a[0] >= 5 ? 'The Signal reaches the coast. Hold one more night.' : `The Signal: phase ${a[0]} complete`, a[0] >= 5 ? 'bad' : 'good')
    if (!GUEST_EV.has(ev) || a.some((x) => x === null && ev !== 'log')) return
    bus.emit(ev, ...a)
  }
  hostLost(bye = false) {
    if (this.status === 'gone') return
    this.status = bye ? 'gone' : 'lost'
    if (bye) this.game.netGone?.('The host closed the camp.')
  }
  // Guests run a light clock between diffs; belts glide along on their own.
  guestTick(simDt) {
    // follow the host's clock: never more than a few seconds ahead of the
    // last time it spoke, and catch up quickly when far behind
    const rate = GAME_MIN_PER_SEC
    if (this.hostTime == null) S.time += simDt * rate
    else {
      const want = this.hostTime + Math.min(now() - this.hostAt, 3) * (S.raid ? 1 : S.speed ?? 1) * rate
      S.time = Math.min(S.time + simDt * rate, want + 1)
      if (want - S.time > 90) S.time = want
      else if (want > S.time) S.time += (want - S.time) * Math.min(1, simDt * 1.5)
    }
    for (const l of S.links || []) {
      const it = l.items
      if (!it?.length || !BELTS[l.tier]) continue
      const gap = BELTS[l.tier].gap
      let lim = l.len
      for (let i = 0; i < it.length; i++) {
        it[i] = Math.min(it[i] + beltSpeed(l.tier) * simDt, lim)
        lim = it[i] - gap
      }
    }
  }

  // ---------------------------------------------------------------- every frame
  update(dt) {
    if (!this.c) return
    const t = this.t
    for (const k in t) t[k] -= dt
    if (this.role === 'host') {
      if (t.delta <= 0) {
        t.delta = DELTA_EVERY
        this.tickDelta()
      }
      // a diff now and then even when nothing moved: guests know we're here
      if (t.beat <= -2.5 && this.peers.size) this.tickDelta(false, true)
      if (t.belts <= 0 && this.peers.size) {
        t.belts = 1.2
        this.c.send({ t: 'b', l: this.beltFrame() })
      }
      if (t.hist <= 0 && this.peers.size) {
        t.hist = 30
        const j = JSON.stringify(S.hist || null)
        if (j !== this.lastHist) {
          this.lastHist = j
          this.c.send({ t: 'h', h: S.hist })
        }
      }
      if (t.owners <= 0) {
        t.owners = 20
        this.tidyOwners()
        // a silent line is dropped; through the server a closed socket says
        // so itself, so only a long silence counts there
        const quiet = this.c?.kind === 'ws' || this.c?.kind === 'server' ? 90 : 40
        for (const [peer, P] of this.peers) if (now() - P.seen > quiet) this.dropPeer(peer, 'timeout')
        this.advertise()
      }
      if (t.cam <= 0 && !this.server) {
        t.cam = 1
        const c = this.myCam()
        if (JSON.stringify(c) !== JSON.stringify(this.cams[this.pid])) {
          this.cams[this.pid] = c
          this.camsDirty = true
        }
      }
      return
    }
    // guest
    if (!this.hostPeer || this.status === 'gone') return
    if (t.flush <= 0) {
      t.flush = 0.25
      this.flush()
    }
    if (t.cam <= 0) {
      t.cam = 1
      const c = this.myCam()
      const j = JSON.stringify(c)
      if (j !== this.lastCam || t.beat <= -4) {
        this.lastCam = j
        t.beat = 0
        this.c.send({ t: 'cam', ...c }, this.hostPeer)
      }
    }
    const quiet = now() - this.heard
    if (quiet > 10 && this.status === 'live') this.status = 'lost'
    if (quiet > 10 && quiet % 5 < dt) this.askResync()
    if (quiet > 60) this.game.netGone?.('Lost the connection to the host.')
  }
  // Where this player is looking, and what they are doing.
  myCam() {
    const g = this.game
    const at = g.scene === g.base ? view.rig.target : g.baseCam || { x: 56, z: 56 }
    const w = g.mission ? `On a run: ${g.mission.loc?.name || 'the city'}` : g.scene === g.map ? 'Looking at the map' : 'In camp'
    return { x: Math.round(at.x * 10) / 10, z: Math.round(at.z * 10) / 10, w }
  }
  tidyOwners() {
    const ids = new Set(S.survivors.map((s) => s.id))
    for (const id of Object.keys(S.mp.owner)) if (!ids.has(id)) delete S.mp.owner[id]
  }
  // ---------------------------------------------------------------- shared actions
  // the captain's fight is over: what happened goes to the host
  captainDone(report, finale) {
    if (!this.captain) return
    this.flush()
    this.captain = false
    this.captainReported = true
    this.c.send({ t: 'raidDone', report: { count: report.count, killed: report.killed, injured: report.injured, dead: report.dead, lost: report.lost, won: report.won }, finale }, this.hostPeer)
  }
  assign(sid, pid) {
    if (this.role !== 'host') return this.isAdmin() && this.c.send({ t: 'admin', op: 'assign', sid, pid }, this.hostPeer)
    if (pid) S.mp.owner[sid] = pid
    else delete S.mp.owner[sid]
    this.t.delta = 0
  }
  kick(pid) {
    if (this.role !== 'host') return this.isAdmin() && this.c.send({ t: 'admin', op: 'kick', pid }, this.hostPeer)
    // kept out for a minute, so a reconnecting tab does not slip straight back
    ;(this.kicked ||= {})[pid] = now() + 60
    for (const [peer, P] of this.peers) if (P.pid === pid) {
      this.c.send({ t: 'kick' }, peer)
      this.dropPeer(peer, 'bye')
    }
  }
  forget(pid) {
    if (this.role !== 'host') return this.isAdmin() && this.c.send({ t: 'admin', op: 'forget', pid }, this.hostPeer)
    if (pid === this.pid || this.online.has(pid)) return
    for (const [sid, o] of Object.entries(S.mp.owner)) if (o === pid) delete S.mp.owner[sid]
    delete S.mp.players[pid]
    this.t.delta = 0
  }
  rename(name) {
    this.name = name
    if (this.role === 'host') S.mp.players[this.pid].name = name
    else this.c?.send({ t: 'hello', pid: this.pid, name, v: PROTO }, this.hostPeer)
  }
  setSpeed(v) {
    if (this.role === 'host') S.speed = v
    else if (this.isAdmin()) this.c.send({ t: 'admin', op: 'speed', v }, this.hostPeer)
  }
  raidOrder(sid, x, z) {
    this.c?.send({ t: 'rcmd', sid, x, z }, this.hostPeer)
  }
  stats() {
    return { sent: this.c?.sent || 0, recv: this.c?.recv || 0, backlog: this.c?.backlog || 0, pending: this.pending.length, n: this.role === 'host' ? this.n : this.lastN }
  }
}
