// How browsers reach each other. Three carriers, one interface:
//   room  - inside claude.ai: the artifact viewer's real-time room. A camp is
//           a named room; the lobby room lists the camps being hosted.
//   peer  - the standalone file: WebRTC data channels through PeerJS.
//   tabs  - BroadcastChannel between tabs of one browser (testing).
// Every carrier moves small JSON frames. Messages too big for one frame are
// deflated, base64'd and split, then put back together on arrival.
import { Peer } from 'peerjs'

const rid = (n = 8) => Array.from(crypto.getRandomValues(new Uint8Array(n)), (b) => 'abcdefghijkmnpqrstuvwxyz23456789'[b % 32]).join('')

// ---------------------------------------------------------------- codec
const hasZip = typeof CompressionStream !== 'undefined'
async function deflate(str) {
  const s = new Blob([str]).stream().pipeThrough(new CompressionStream('deflate-raw'))
  return new Uint8Array(await new Response(s).arrayBuffer())
}
async function inflate(bytes) {
  const s = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate-raw'))
  return new Response(s).text()
}
function b64(bytes) {
  let s = ''
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000))
  return btoa(s)
}
function unb64(str) {
  const s = atob(str)
  const b = new Uint8Array(s.length)
  for (let i = 0; i < s.length; i++) b[i] = s.charCodeAt(i)
  return b
}

// ---------------------------------------------------------------- base
export class Carrier {
  constructor(max, rate) {
    this.max = max // characters per frame
    this.rate = rate // frames per second (Infinity: no pacing)
    this.id = null
    this.fns = { msg: new Set(), left: new Set(), status: new Set(), host: new Set() }
    this.parts = new Map()
    this.txq = Promise.resolve()
    this.rxq = Promise.resolve()
    this.out = []
    this.tokens = 4
    this.lastPump = performance.now()
    this.timer = null
    this.sent = 0
    this.recv = 0
  }
  on(ev, fn) {
    this.fns[ev].add(fn)
    return () => this.fns[ev].delete(fn)
  }
  fire(ev, ...a) {
    for (const fn of [...this.fns[ev]]) fn(...a)
  }
  // Queue a message. The JSON is taken now, so the caller may keep mutating.
  send(msg, to = null) {
    const j = JSON.stringify(msg)
    this.txq = this.txq.then(async () => {
      for (const f of await this.frames(j)) {
        if (to) f.to = to
        this.out.push(f)
      }
      this.pump()
    })
    return this.txq
  }
  async frames(j) {
    if (j.length <= (this.maxJ ?? this.max)) return [{ j }]
    if (!hasZip) return [{ j }]
    const z = b64(await deflate(j))
    if (z.length <= this.max) return [{ z }]
    const id = rid(5)
    const n = Math.ceil(z.length / this.max)
    return Array.from({ length: n }, (_, i) => ({ p: id, i, n, z: z.slice(i * this.max, (i + 1) * this.max) }))
  }
  pump() {
    clearTimeout(this.timer)
    const now = performance.now()
    this.tokens = Math.min(6, this.tokens + ((now - this.lastPump) / 1000) * this.rate)
    this.lastPump = now
    while (this.out.length && (this.rate === Infinity || this.tokens >= 1)) {
      this.tokens--
      const f = this.out.shift()
      this.sent += JSON.stringify(f).length
      this.raw(f)
    }
    if (this.out.length) this.timer = setTimeout(() => this.pump(), Math.max(16, 1000 / this.rate))
  }
  get backlog() {
    return this.out.length
  }
  // A frame arrived from `from`. Messages are handed on in arrival order.
  take(f, from) {
    if (!f || typeof f !== 'object' || (f.to && f.to !== this.id)) return
    this.rxq = this.rxq.then(async () => {
      try {
        this.recv += JSON.stringify(f).length
        let j = null
        if (typeof f.j === 'string') j = f.j
        else if (typeof f.z === 'string' && f.p == null) j = await inflate(unb64(f.z))
        else if (f.p != null) {
          const key = from + '|' + f.p
          let P = this.parts.get(key)
          if (!P) this.parts.set(key, (P = { got: 0, n: f.n, z: [], at: performance.now() }))
          if (P.z[f.i] == null) {
            P.z[f.i] = f.z
            P.got++
          }
          if (P.got < P.n) return this.prune()
          this.parts.delete(key)
          j = await inflate(unb64(P.z.join('')))
        }
        if (j != null) this.fire('msg', JSON.parse(j), from)
      } catch (e) {
        console.warn('holdout net: bad frame', e)
      }
    })
  }
  prune() {
    const now = performance.now()
    for (const [k, P] of this.parts) if (now - P.at > 20000) this.parts.delete(k)
  }
  close() {
    clearTimeout(this.timer)
  }
}

// ---------------------------------------------------------------- tabs
class TabCarrier extends Carrier {
  constructor() {
    super(1e9, Infinity)
    this.kind = 'tabs'
    this.id = rid()
  }
  async open(code) {
    this.ch = new BroadcastChannel('holdout-net-' + code.toLowerCase())
    this.ch.onmessage = (e) => this.take(e.data?.f, e.data?.from)
    this.fire('status', true)
  }
  raw(f) {
    this.ch.postMessage({ from: this.id, f })
  }
  close() {
    super.close()
    this.ch?.close()
  }
}

// ---------------------------------------------------------------- PeerJS
// The host claims the peer id holdout-<code> on the public PeerJS broker;
// guests connect to it. Data then flows browser to browser.
class PeerCarrier extends Carrier {
  constructor() {
    super(60000, Infinity)
    this.kind = 'peer'
    this.conns = new Map()
  }
  open(code, asHost) {
    const hostId = 'holdout-' + code.toLowerCase()
    return new Promise((resolve, reject) => {
      const fail = (why) => reject(new Error(why))
      const peer = (this.peer = asHost ? new Peer(hostId, { debug: 0 }) : new Peer({ debug: 0 }))
      const t = setTimeout(() => fail('timeout'), 15000)
      peer.on('error', (e) => {
        if (e.type === 'unavailable-id') fail('code-taken')
        else if (e.type === 'peer-unavailable') fail('no-camp')
        else if (e.type === 'network' || e.type === 'server-error' || e.type === 'socket-error') fail('offline')
        else if (!this.ready) fail(e.type || 'error')
        else console.warn('holdout net:', e)
      })
      peer.on('disconnected', () => this.ready && peer.reconnect?.())
      peer.on('open', (id) => {
        this.id = id
        if (asHost) {
          peer.on('connection', (c) => this.attach(c))
          this.ready = true
          clearTimeout(t)
          this.fire('status', true)
          return resolve()
        }
        const c = peer.connect(hostId, { reliable: true, serialization: 'json' })
        c.on('open', () => {
          this.ready = true
          clearTimeout(t)
          this.fire('status', true)
          resolve()
        })
        this.attach(c)
      })
    })
  }
  attach(c) {
    this.conns.set(c.peer, c)
    c.on('data', (f) => this.take(f, c.peer))
    c.on('close', () => {
      this.conns.delete(c.peer)
      this.fire('left', c.peer)
      if (!this.conns.size && this.hostConn === c) this.fire('status', false)
    })
    if (!this.hostConn) this.hostConn = c
  }
  raw(f) {
    if (f.to) return this.conns.get(f.to)?.open && this.conns.get(f.to).send(f)
    for (const c of this.conns.values()) if (c.open) c.send(f)
  }
  close() {
    super.close()
    try {
      this.peer?.destroy()
    } catch {}
  }
}

// ---------------------------------------------------------------- claude.ai room
// Events on the `mp` topic carry frames. A viewer the artifact does not let
// send events can still join: their frames go through their presence, one
// at a time, and the host acknowledges each (the mailbox).
export const LOBBY_KEY = 'camp'
class RoomCarrier extends Carrier {
  constructor(lobby) {
    super(2900, 14)
    // plain JSON gets escaped again inside the event, so keep it shorter
    this.maxJ = 1400
    this.kind = 'room'
    this.lobby = lobby
    this.mail = false
    this.mailq = []
    this.mailSeq = 0
    this.mailSeen = new Map()
  }
  async open(code, asHost) {
    this.isHost = asHost
    this.room = await this.lobby.join('holdout-' + code.toLowerCase())
    const err = (e) => this.fire('status', false, e?.code)
    this.offs = [
      this.room.on('mp', (m) => !m.sameTab && this.take(m.data, m.peer), err),
      this.room.onPeers((ch) => {
        const me = ch.peers.find((p) => p.sameTab)
        if (me) this.id = me.peer
        for (const p of ch.left) this.fire('left', p.peer)
        if (this.isHost) for (const p of [...ch.joined, ...ch.updated]) if (!p.sameTab && p.presence?.mx) this.mailIn(p)
      }, err),
      this.room.onConnection((c) => this.fire('status', c), err),
    ]
    // learn our own peer label
    for (let i = 0; i < 50 && !this.id; i++) {
      this.id = this.room.peers().find((p) => p.sameTab)?.peer || null
      if (!this.id) await new Promise((r) => setTimeout(r, 100))
    }
    if (!this.id) throw new Error('offline')
  }
  raw(f) {
    if (this.mail) return this.mailOut(f)
    this.room.emit('mp', f).catch((e) => {
      if (e?.code !== 'not_permitted') return
      // a host has to be able to send; a guest can post through presence
      if (this.isHost) return this.fire('status', false, 'not_permitted')
      this.mail = true
      this.mailOut(f)
    })
  }
  // ---- mailbox: one frame in presence until the host says it has it
  mailOut(f) {
    this.mailq.push(f)
    if (this.mailq.length === 1) this.mailNext()
  }
  mailNext() {
    const f = this.mailq[0]
    if (!f) return this.room.presence({ mx: null }).catch(() => {})
    this.mailSeq++
    this.room.presence({ mx: [this.mailSeq, JSON.stringify(f)] }).catch(() => {})
    clearTimeout(this.mailT)
    const seq = this.mailSeq
    this.mailT = setTimeout(() => this.mailSeq === seq && this.room.presence({ mx: [seq, JSON.stringify(f)] }).catch(() => {}), 4000)
  }
  mailAck(seq) {
    if (seq !== this.mailSeq || !this.mailq.length) return
    this.mailq.shift()
    this.mailNext()
  }
  mailIn(p) {
    const [seq, j] = p.presence.mx
    if (!(seq > (this.mailSeen.get(p.peer) || 0))) return
    this.mailSeen.set(p.peer, seq)
    try {
      this.take(JSON.parse(j), p.peer)
    } catch {}
    // acknowledge on the topic: the host can always send there
    this.room.emit('mp', { to: p.peer, ma: seq }).catch(() => {})
  }
  take(f, from) {
    if (f && f.ma != null) {
      if (f.to === this.id) this.mailAck(f.ma)
      return
    }
    super.take(f, from)
  }
  // The lobby: hosts list their camp in the shared room's presence.
  advertise(info) {
    return this.lobby.presence({ [LOBBY_KEY]: info }).catch(() => {})
  }
  close() {
    super.close()
    for (const off of this.offs || []) off()
    this.lobby.presence({ [LOBBY_KEY]: null }).catch(() => {})
    this.room?.leave().catch(() => {})
  }
}

// ---------------------------------------------------------------- game server
// Played from the Holdout server (fly.io or any Node host): one WebSocket to
// the server, which relays frames between a camp's host and its guests. The
// host can be a player's browser or the server itself (always-on camps).
export const serverUrl = () => `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`
class WsCarrier extends Carrier {
  constructor() {
    super(250000, Infinity)
    this.kind = 'ws'
    this.waits = []
  }
  connect() {
    return new Promise((resolve, reject) => {
      const ws = (this.ws = new WebSocket(serverUrl()))
      const t = setTimeout(() => reject(new Error('offline')), 10000)
      ws.onmessage = (e) => {
        let m
        try {
          m = JSON.parse(e.data)
        } catch {
          return
        }
        if (m.op === 'id') {
          this.id = m.id
          clearTimeout(t)
          resolve()
        } else if (m.op === 'f') this.take(m.f, m.from)
        else if (m.op === 'left') this.fire('left', m.id)
        else if (m.op === 'host') this.fire('host', m.id)
        else this.waits.shift()?.(m)
      }
      ws.onerror = () => reject(new Error('offline'))
      ws.onclose = () => {
        if (this.closed) return
        this.fire('status', false)
        this.retry()
      }
    })
  }
  ask(msg) {
    return new Promise((resolve) => {
      this.waits.push(resolve)
      this.ws.send(JSON.stringify(msg))
    })
  }
  async open(code, asHost) {
    this.code = code
    this.asHost = asHost
    await this.connect()
    await this.claim()
    this.fire('status', true)
  }
  async claim() {
    const r = await this.ask(this.asHost ? { op: 'host', code: this.code, token: this.token } : { op: 'join', code: this.code })
    if (r.op === 'err') throw new Error(r.why)
    if (r.token) this.token = r.token
    this.hostId = r.host
  }
  // the line dropped: come back with the same code (a host keeps its room)
  async retry() {
    for (let i = 0; !this.closed; i++) {
      await new Promise((r) => setTimeout(r, Math.min(8000, 800 * 2 ** i)))
      try {
        await this.connect()
        await this.claim()
        this.fire('status', true)
        return
      } catch {}
    }
  }
  raw(f) {
    if (this.ws?.readyState === 1) this.ws.send(JSON.stringify({ op: 'f', to: f.to, f }))
  }
  advertise(info) {
    if (this.ws?.readyState === 1) this.ws.send(JSON.stringify({ op: 'adv', info }))
  }
  close() {
    super.close()
    this.closed = true
    try {
      this.ws?.close()
    } catch {}
  }
}
// Talk to the server outside a camp: the camp list, creating server camps.
export async function serverCall(msg) {
  const c = new WsCarrier()
  await c.connect()
  const r = await c.ask(msg)
  c.close()
  return r
}
let serverCheck
// Is this page served by a Holdout server?
export function onServer() {
  if (serverCheck) return serverCheck
  if (!/^https?:$/.test(location.protocol) || window.claude?.use) return (serverCheck = Promise.resolve(false))
  return (serverCheck = fetch('/api/hello', { cache: 'no-store' })
    .then((r) => (r.ok ? r.json() : null))
    .then((j) => !!j?.holdout)
    .catch(() => false))
}

// ---------------------------------------------------------------- choosing
let roomNs
// The claude.ai room, or null outside the viewer.
export function roomLobby() {
  if (roomNs !== undefined) return roomNs
  if (!window.claude?.use) return (roomNs = Promise.resolve(null))
  return (roomNs = window.claude.use('room').catch(() => null))
}
// Which carrier this page uses: ?net=tabs forces the tab channel (tests).
export async function carrierKind() {
  const q = new URLSearchParams(location.search).get('net')
  if (q === 'tabs' || q === 'peer' || q === 'ws') return q
  if (await roomLobby()) return 'room'
  if (await onServer()) return 'ws'
  return 'peer'
}
export async function openCarrier(code, asHost) {
  const kind = await carrierKind()
  let c
  if (kind === 'room') c = new RoomCarrier(await roomLobby())
  else if (kind === 'tabs') c = new TabCarrier()
  else if (kind === 'ws') c = new WsCarrier()
  else c = new PeerCarrier()
  try {
    await c.open(code, asHost)
  } catch (e) {
    c.close()
    throw e
  }
  return c
}
export const newCode = () => rid(5).toUpperCase()
export const newId = rid
