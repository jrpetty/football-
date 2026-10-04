// One always-on camp, run in its own worker thread with the game's own code.
// The worker is the camp's host: it runs the simulation, answers players
// through the main thread's WebSocket relay, hands hordes to a player in camp
// (or fights them out on paper) and sends its state back to be saved.
import './globals.mjs'
import { parentPort, workerData } from 'node:worker_threads'
import * as ST from '../src/game/state.js'
import * as EC from '../src/game/economy.js'
import { RES, SEC_PER_DAY, LOCATIONS } from '../src/game/data.js'
import { bus } from '../src/core/util.js'
import { genCity } from '../src/world/city.js'
import * as RV from '../src/game/rivals.js'
import { wireDiaries } from '../src/game/diary.js'
import { Session, COLORS, PROTO } from '../src/net/mp.js'
import { Carrier } from '../src/net/transport.js'

const { code, create, state } = workerData
// the survivors keep their diaries here too: this worker is the camp's host
wireDiaries()
const OFFLINE_DIV = 15
const OFFLINE_MAX = 3 * SEC_PER_DAY
const post = (m) => parentPort.postMessage(m)
const log = (t) => post({ log: `[${code}] ${t}` })

class WorkerCarrier extends Carrier {
  constructor() {
    super(250000, Infinity)
    this.kind = 'server'
    this.id = 'server'
  }
  raw(f) {
    post({ f })
  }
  advertise(info) {
    post({ info })
  }
}

const S = () => ST.S
// ---- the camp
ST.NET.role = 'host'
ST.NET.pid = 'server'
let away = null
if (create) {
  ST.newGame()
  EC.initSchedules()
  ST.S.mode = create.mode === 'once' ? 'once' : 'restock'
  ST.setSite(create.site || 'lumber')
  ST.S.mp = { code, host: 'server', admin: create.pid, server: true, name: create.name, public: create.public !== false, players: { [create.pid]: { name: create.pname, color: COLORS[0], since: 1 } }, owner: {} }
  ST.log(`${create.name}: ${create.pname} and three others made it to the old lumber yard.`, 'story')
} else {
  ST.load(JSON.parse(state))
  away = catchUp()
}
const city = genCity(S().seed)
S().cityLocs = city.locs.map((l) => ({ id: l.id, type: l.type, level: l.level, name: l.name, district: l.lot?.district || 'residential', ...(l.minor ? { minor: true } : {}) }))

// While a camp sat on disk its crew kept working, slowly, as in single player.
function catchUp() {
  const secs = (Date.now() - (S().saved || Date.now())) / 1000
  if (secs < 120) return null
  const sim = Math.min(secs / OFFLINE_DIV, OFFLINE_MAX)
  const before = { ...S().res }
  const keep = { time: S().time, weather: { ...S().weather } }
  for (let left = sim; left > 0; left -= 1) EC.econTick(Math.min(1, left), { offline: true })
  S().time = keep.time
  S().weather = keep.weather
  const diff = Object.entries(S().res).map(([k, v]) => [k, v - (before[k] || 0)]).filter(([, v]) => Math.abs(v) >= 1).sort((a, b) => Math.abs(b[1]) - Math.abs(a[1]))
  const hrs = secs / 3600
  ST.log(`While everyone was away (${hrs >= 1 ? `${hrs.toFixed(1)} h` : `${Math.round(secs / 60)} min`}) the crew kept working: ${diff.slice(0, 6).map(([k, v]) => `${v > 0 ? '+' : ''}${Math.round(v)} ${RES[k].name.toLowerCase()}`).join(', ') || 'nothing much changed'}.`, 'story')
  return secs
}

// ---- the session: this worker hosts, players are guests
const game = {
  ui: null, base: null, map: null, mission: null, scene: 'server', baseCam: { x: 56, z: 56 },
  netGone() {},
  // a message for a rival camp (game/rivals.js): the main thread routes it
  c2c(m) {
    post({ c2c: m })
  },
  // a captain finished the fight
  raidOver(report, finale) {
    EC.scheduleRaid()
    if (finale) EC.finishGame(!!report.won)
    bus.emit('raidResolved', { count: report.count || 0, killed: report.killed || 0, injured: report.injured || [], dead: report.dead || [], lost: report.lost || {}, ratio: report.won ? 1 : 0.4, won: !!report.won })
  },
  // nobody to fight it live: the camp holds or it doesn't
  raidAuto(R) {
    EC.autoResolveRaid(R)
    EC.scheduleRaid()
  },
}
const net = new Session(game, 'host', code, { pid: 'server', name: S().mp.name || 'Camp', server: true })
await net.host(new WorkerCarrier())
bus.on('raidStart', (R) => {
  if (!net.raidTo(R)) game.raidAuto(R)
})
bus.on('gameover', () => log('the camp fell'))

// ---- frames from players, commands from the main thread
parentPort.on('message', (m) => {
  if (m.f) net.c.take(m.f, m.from)
  else if (m.c2c) {
    // a rival camp's message, or its answer to ours
    try {
      const reply = RV.rivalReceive(m.c2c.from, m.c2c.msg)
      if (reply) post({ c2c: { to: m.c2c.from.code, from: { code, name: S().mp.name || 'A camp' }, msg: reply } })
      net.t.delta = 0
      save()
    } catch (e) {
      log('rival error ' + (e.stack || e))
    }
  } else if (m.c2cBounce) {
    RV.rivalBounce(m.c2cBounce)
    net.t.delta = 0
  }
  else if (m.left) net.dropPeer(m.left, 'left')
  else if (m.cmd === 'stop') {
    save()
    setTimeout(() => process.exit(0), 50)
  } else if (m.cmd === 'save') save()
  else if (m.debug && process.env.HOLDOUT_DEBUG) {
    // test hook, only with HOLDOUT_DEBUG set: run code against the camp
    try {
      const r = new Function('ST', 'EC', 'net', 'S', 'bus', m.debug)(ST, EC, net, ST.S, bus)
      post({ debugReply: m.id, result: r === undefined ? null : JSON.parse(JSON.stringify(r)) })
    } catch (e) {
      post({ debugReply: m.id, error: String(e.stack || e) })
    }
  }
})

function save() {
  S().saved = Date.now()
  post({ save: JSON.stringify(S()), meta: meta() })
}
function meta() {
  const s = S()
  return { code, name: s.mp.name || 'A camp', day: ST.day(), pop: s.survivors.length, players: Object.keys(s.mp.players).length, on: net.online.size, public: s.mp.public !== false, over: !!s.over, v: PROTO, kind: 'server' }
}

// ---- the clock: real time while someone is here, slow and waiting when not
let last = performance.now()
let saveT = 30
let infoT = 0
let idle = 0
setInterval(() => {
  const now = performance.now()
  const dt = Math.min(0.5, (now - last) / 1000)
  last = now
  try {
    if (S().over) return
    if (net.online.size) {
      const sim = dt * (S().raid ? 1 : S().speed ?? 1)
      for (let left = sim; left > 0; left -= 0.25) EC.econTick(Math.min(0.25, left))
    } else {
      // nobody here: a fifteenth of the pace, and the clock stands still
      idle += dt / OFFLINE_DIV
      if (idle >= 1) {
        const keep = { time: S().time, weather: { ...S().weather } }
        EC.econTick(idle, { offline: true })
        S().time = keep.time
        S().weather = keep.weather
        idle = 0
      }
    }
    net.update(dt)
  } catch (e) {
    log('tick error ' + (e.stack || e))
  }
  saveT -= dt
  infoT -= dt
  if (saveT <= 0) {
    saveT = 30
    save()
  }
  if (infoT <= 0) {
    infoT = 5
    post({ info: meta() })
  }
}, 50)
save()
post({ ready: true, meta: meta() })
if (away) log(`back after ${Math.round(away / 60)} min away`)
