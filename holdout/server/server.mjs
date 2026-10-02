// The Holdout server: serves the game, relays multiplayer frames between
// browsers over WebSockets, and keeps always-on camps.
//
//   node server/build.mjs      build the page and the camp worker
//   node server/server.mjs     run (PORT, DATA_DIR from the environment)
//
// A camp is a room with a code. Its host is either a player's browser (a
// hosted camp: the server only relays) or a worker thread running the camp
// with the game's own code (an always-on camp, saved under DATA_DIR and
// loaded whenever someone joins).
import http from 'node:http'
import zlib from 'node:zlib'
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { Worker } from 'node:worker_threads'
import { WebSocketServer } from 'ws'

const here = path.dirname(fileURLToPath(import.meta.url))
const PORT = +(process.env.PORT || 8080)
const DATA = path.resolve(process.env.DATA_DIR || path.join(here, 'data'))
const CAMPS = path.join(DATA, 'camps')
const PUBLIC = path.join(here, 'public')
const WORKER = path.join(here, 'dist', 'camp.mjs')
const PROTO = 1
const UNLOAD_AFTER = +(process.env.UNLOAD_AFTER_MS || 120e3) // an empty always-on camp goes back to disk
const DEBUG = !!process.env.HOLDOUT_DEBUG
const HOST_GRACE = 90e3 // a hosted camp waits this long for its host to return
const MAX_CAMPS = +(process.env.MAX_CAMPS || 200)
const MAX_LOADED = +(process.env.MAX_LOADED || 12)
fs.mkdirSync(CAMPS, { recursive: true })

const rid = (n = 8) => Array.from(crypto.randomBytes(n), (b) => 'abcdefghijkmnpqrstuvwxyz23456789'[b % 32]).join('')
const newCode = () => rid(5).toUpperCase()
const validCode = (c) => typeof c === 'string' && /^[A-Z0-9]{5}$/.test(c)
const log = (...a) => console.log(new Date().toISOString().slice(0, 19), ...a)
const clean = (t, n) => String(t ?? '').replace(/[\u0000-\u001f<>]/g, '').trim().slice(0, n)

// ---------------------------------------------------------------- the page
let page = null
function loadPage() {
  const file = path.join(PUBLIC, 'index.html')
  if (!fs.existsSync(file)) return (page = null)
  const raw = fs.readFileSync(file)
  page = {
    raw,
    gz: zlib.gzipSync(raw, { level: 9 }),
    br: zlib.brotliCompressSync(raw, { params: { [zlib.constants.BROTLI_PARAM_QUALITY]: 10 } }),
    etag: '"' + crypto.createHash('sha1').update(raw).digest('hex').slice(0, 20) + '"',
  }
  log(`page ${(raw.length / 1024).toFixed(0)} KB, gzip ${(page.gz.length / 1024).toFixed(0)} KB, brotli ${(page.br.length / 1024).toFixed(0)} KB`)
}
loadPage()

// ---------------------------------------------------------------- camps on disk
const INDEX = path.join(CAMPS, 'index.json')
let index = {}
try {
  index = JSON.parse(fs.readFileSync(INDEX, 'utf8'))
} catch {}
function writeIndex() {
  const tmp = INDEX + '.tmp'
  fs.writeFileSync(tmp, JSON.stringify(index))
  fs.renameSync(tmp, INDEX)
}
function saveCamp(code, json, meta) {
  const file = path.join(CAMPS, code + '.json')
  const tmp = file + '.tmp'
  fs.writeFileSync(tmp, json)
  fs.renameSync(tmp, file)
  index[code] = { ...(index[code] || {}), ...meta, updated: Date.now() }
  writeIndex()
}

// ---------------------------------------------------------------- rooms
const rooms = new Map() // code -> room
const clients = new Map() // id -> client
function send(c, m) {
  if (c?.ws.readyState === 1) c.ws.send(JSON.stringify(m))
}

// An always-on camp in its worker.
function startCamp(code, { create = null } = {}) {
  const loaded = [...rooms.values()].filter((r) => r.kind === 'server').length
  if (loaded >= MAX_LOADED) return null
  let state = null
  if (!create) {
    const file = path.join(CAMPS, code + '.json')
    if (!fs.existsSync(file)) return null
    state = fs.readFileSync(file, 'utf8')
  }
  const room = { code, kind: 'server', host: null, guests: new Map(), info: index[code] || null, ready: null, unload: null }
  const worker = new Worker(WORKER, { workerData: { code, create, state } })
  room.worker = worker
  room.ready = new Promise((resolve, reject) => {
    room.readyOk = resolve
    worker.once('error', reject)
  })
  worker.on('message', (m) => {
    if (m.f) {
      const to = m.f.to
      if (to) send(room.guests.get(to), { op: 'f', from: 'server', f: m.f })
      else for (const g of room.guests.values()) send(g, { op: 'f', from: 'server', f: m.f })
    } else if (m.save) saveCamp(code, m.save, m.meta)
    else if (m.info) room.info = { ...m.info, kind: 'server' }
    else if (m.debugReply != null) send(clients.get(room.debugWho), { op: 'debug', id: m.debugReply, result: m.result, error: m.error })
    else if (m.ready) {
      room.info = m.meta
      room.readyOk()
    } else if (m.log) log(m.log)
  })
  worker.on('error', (e) => {
    log(`camp ${code} crashed:`, e)
    for (const g of room.guests.values()) send(g, { op: 'left', id: 'server' })
    rooms.delete(code)
  })
  worker.on('exit', () => rooms.get(code) === room && rooms.delete(code))
  rooms.set(code, room)
  log(`camp ${code} ${create ? 'created' : 'loaded'}`)
  return room
}
function stopCamp(room) {
  if (room.stopping) return
  room.stopping = true
  room.worker.postMessage({ cmd: 'stop' })
  setTimeout(() => room.worker.terminate(), 5000)
  rooms.delete(room.code)
  log(`camp ${room.code} saved and put away`)
}
function emptyCheck(room) {
  clearTimeout(room.unload)
  if (room.kind === 'server' && !room.guests.size) room.unload = setTimeout(() => !room.guests.size && stopCamp(room), UNLOAD_AFTER)
}

// What the lobby lists: hosted camps that advertise, and always-on camps.
function campList() {
  const out = []
  for (const r of rooms.values()) {
    if (r.kind === 'hosted' && r.host && r.info && r.info.public !== false) out.push({ ...r.info, code: r.code, kind: 'hosted', on: r.guests.size + 1 })
  }
  for (const [code, m] of Object.entries(index)) {
    if (m.public === false || m.over) continue
    const live = rooms.get(code)
    out.push({ ...m, code, kind: 'server', on: live ? live.guests.size : 0, live: !!live })
  }
  return out.sort((a, b) => b.on - a.on || (b.updated || 0) - (a.updated || 0)).slice(0, 60)
}

// ---------------------------------------------------------------- http
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x')
  const json = (code, obj) => {
    res.writeHead(code, { 'content-type': 'application/json', 'cache-control': 'no-store' })
    res.end(JSON.stringify(obj))
  }
  if (url.pathname === '/healthz') return res.writeHead(200, { 'content-type': 'text/plain' }), res.end('ok')
  if (url.pathname === '/api/hello') return json(200, { holdout: true, v: PROTO })
  if (url.pathname === '/api/camps') return json(200, { camps: campList() })
  if (url.pathname === '/' || url.pathname === '/index.html') {
    if (!page) return res.writeHead(503), res.end('The game has not been built yet: run node server/build.mjs')
    if (req.headers['if-none-match'] === page.etag) return res.writeHead(304, { etag: page.etag }), res.end()
    const ae = String(req.headers['accept-encoding'] || '')
    const enc = /\bbr\b/.test(ae) ? 'br' : /\bgzip\b/.test(ae) ? 'gzip' : null
    res.writeHead(200, {
      'content-type': 'text/html; charset=utf-8',
      'cache-control': 'no-cache',
      etag: page.etag,
      vary: 'accept-encoding',
      ...(enc ? { 'content-encoding': enc } : {}),
    })
    return res.end(enc === 'br' ? page.br : enc === 'gzip' ? page.gz : page.raw)
  }
  res.writeHead(404, { 'content-type': 'text/plain' })
  res.end('Not found')
})

// ---------------------------------------------------------------- websockets
const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 8 * 1024 * 1024 })
const creations = new Map() // ip -> [times]
wss.on('connection', (ws, req) => {
  const c = { id: rid(), ws, room: null, role: null, alive: true, ip: req.headers['fly-client-ip'] || req.socket.remoteAddress, budget: 400, budgetAt: Date.now() }
  clients.set(c.id, c)
  send(c, { op: 'id', id: c.id })
  ws.on('pong', () => (c.alive = true))
  ws.on('message', (data) => {
    // a generous rate limit: a frame flood gets the line cut
    const now = Date.now()
    c.budget = Math.min(400, c.budget + ((now - c.budgetAt) / 1000) * 120)
    c.budgetAt = now
    if (--c.budget < 0) return ws.close(1008, 'too fast')
    let m
    try {
      m = JSON.parse(data)
    } catch {
      return
    }
    handle(c, m).catch((e) => log('handler error', e))
  })
  ws.on('close', () => leave(c))
})
// drop lines that stopped answering
setInterval(() => {
  for (const c of clients.values()) {
    if (!c.alive) {
      c.ws.terminate()
      continue
    }
    c.alive = false
    c.ws.ping()
  }
}, 25000)

async function handle(c, m) {
  switch (m.op) {
    case 'host': {
      const code = String(m.code || '')
      if (!validCode(code) || c.room) return send(c, { op: 'err', why: 'bad-code' })
      let room = rooms.get(code)
      if (room && (room.kind === 'server' || (room.host && room.token !== m.token))) return send(c, { op: 'err', why: 'code-taken' })
      if (!room) {
        if (index[code]) return send(c, { op: 'err', why: 'code-taken' })
        room = { code, kind: 'hosted', host: null, guests: new Map(), info: null, token: rid(16) }
        rooms.set(code, room)
      }
      clearTimeout(room.gone)
      room.host = c
      c.room = code
      c.role = 'host'
      send(c, { op: 'ok', token: room.token, host: c.id })
      for (const g of room.guests.values()) send(g, { op: 'host', id: c.id })
      return
    }
    case 'join': {
      const code = String(m.code || '').toUpperCase()
      if (!validCode(code) || c.room) return send(c, { op: 'err', why: 'no-camp' })
      let room = rooms.get(code)
      if (!room && index[code]) room = startCamp(code)
      if (!room) return send(c, { op: 'err', why: index[code] ? 'busy' : 'no-camp' })
      if (room.kind === 'hosted' && !room.host) return send(c, { op: 'err', why: 'no-camp' })
      if (room.kind === 'server') {
        try {
          await room.ready
        } catch {
          return send(c, { op: 'err', why: 'no-camp' })
        }
        clearTimeout(room.unload)
      }
      room.guests.set(c.id, c)
      c.room = code
      c.role = 'guest'
      return send(c, { op: 'ok', host: room.kind === 'server' ? 'server' : room.host.id })
    }
    case 'f': {
      const room = rooms.get(c.room)
      if (!room || !m.f) return
      if (c.role === 'host') {
        const out = { op: 'f', from: c.id, f: m.f }
        if (m.to) send(room.guests.get(m.to), out)
        else for (const g of room.guests.values()) send(g, out)
      } else if (room.kind === 'server') room.worker.postMessage({ from: c.id, f: m.f })
      else send(room.host, { op: 'f', from: c.id, f: m.f })
      return
    }
    case 'adv': {
      const room = rooms.get(c.room)
      if (room && c.role === 'host' && m.info && typeof m.info === 'object')
        room.info = { name: clean(m.info.name, 40), day: +m.info.day || 1, pop: +m.info.pop || 0, players: +m.info.players || 1, v: +m.info.v || 0, public: m.info.public !== false }
      return
    }
    case 'lobby':
      return send(c, { op: 'lobby', camps: campList() })
    case 'create': {
      // an always-on camp, made by this player, who becomes its admin
      const now = Date.now()
      const times = (creations.get(c.ip) || []).filter((t) => now - t < 3600e3)
      if (times.length >= 6) return send(c, { op: 'err', why: 'slow-down' })
      if (Object.keys(index).length >= MAX_CAMPS) return send(c, { op: 'err', why: 'full' })
      const pid = clean(m.pid, 24)
      if (!pid) return send(c, { op: 'err', why: 'bad' })
      let code
      do code = newCode()
      while (rooms.has(code) || index[code])
      const create = { pid, pname: clean(m.pname, 20) || 'Survivor', name: clean(m.name, 40) || 'The Holdout', public: m.public !== false, mode: m.mode === 'once' ? 'once' : 'restock' }
      const room = startCamp(code, { create })
      if (!room) return send(c, { op: 'err', why: 'busy' })
      try {
        await room.ready
      } catch {
        return send(c, { op: 'err', why: 'busy' })
      }
      times.push(now)
      creations.set(c.ip, times)
      index[code] = { ...room.info, created: now }
      writeIndex()
      emptyCheck(room)
      return send(c, { op: 'created', code })
    }
    case 'ping':
      return send(c, { op: 'pong', t: m.t })
    case 'debug': {
      if (!DEBUG) return
      const room = rooms.get(String(m.code || '').toUpperCase())
      if (!room?.worker) return send(c, { op: 'debug', id: m.id, error: 'not loaded' })
      room.debugWho = c.id
      return room.worker.postMessage({ debug: String(m.js), id: m.id })
    }
  }
}

function leave(c) {
  clients.delete(c.id)
  const room = rooms.get(c.room)
  if (!room) return
  if (c.role === 'guest') {
    room.guests.delete(c.id)
    if (room.kind === 'server') {
      room.worker.postMessage({ left: c.id })
      emptyCheck(room)
    } else send(room.host, { op: 'left', id: c.id })
  } else if (c.role === 'host' && room.host === c) {
    // the host's browser went away: guests wait a little for it to come back
    room.host = null
    for (const g of room.guests.values()) send(g, { op: 'left', id: c.id })
    room.gone = setTimeout(() => {
      if (room.host) return
      rooms.delete(room.code)
      for (const g of room.guests.values()) g.ws.close(1000, 'host gone')
    }, HOST_GRACE)
  }
}

// ---------------------------------------------------------------- lifecycle
server.listen(PORT, () => log(`Holdout server on :${PORT}, data in ${DATA}`))
let stopping = false
function shutdown() {
  if (stopping) return
  stopping = true
  log('shutting down: saving camps')
  const live = [...rooms.values()].filter((r) => r.kind === 'server')
  for (const r of live) {
    r.worker.on('message', (m) => m.save && saveCamp(r.code, m.save, m.meta))
    r.worker.postMessage({ cmd: 'stop' })
  }
  setTimeout(() => process.exit(0), live.length ? 2500 : 0)
}
process.on('SIGTERM', shutdown)
process.on('SIGINT', shutdown)
