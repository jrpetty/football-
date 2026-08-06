// A relay for online play.
//
// It knows nothing about football. It pairs people into rooms by a short code
// and forwards every message verbatim, which means the host is still the only
// authority on the simulation — this is a postbox, not a server.
//
//   npm run relay            # listens on 8787
//   npm run relay -- 9000    # or wherever
//
// Then in the game: Play Online → Join a relay → ws://<this machine>:8787
//
// Nothing here is required to play online. Two people can play over WebRTC with
// no server at all by copy-pasting a connection code; this exists for when you
// want a code short enough to say out loud, or more than two players.
import { createServer } from 'node:http'
import { WebSocketServer } from 'ws'

const port = Number(process.argv[2] ?? process.env.PORT ?? 8787)
const rooms = new Map() // code -> { host: ws|null, guests: Set<ws> }

const http = createServer((_req, res) => {
  res.writeHead(200, { 'content-type': 'text/plain' })
  res.end(`open-pitch relay\nrooms: ${rooms.size}\n`)
})
const wss = new WebSocketServer({ server: http })

const send = (ws, o) => {
  if (ws.readyState === 1) ws.send(JSON.stringify(o))
}

wss.on('connection', (ws) => {
  ws.meta = null

  ws.on('message', (raw) => {
    let m
    try {
      m = JSON.parse(raw.toString())
    } catch {
      return
    }

    if (m.rtype === 'join') {
      const code = String(m.room ?? '').toUpperCase().slice(0, 8)
      if (!code) return send(ws, { rtype: 'error', why: 'a room code is required' })
      let room = rooms.get(code)
      if (!room) {
        room = { host: null, guests: new Set() }
        rooms.set(code, room)
      }
      if (m.role === 'host') {
        if (room.host && room.host.readyState === 1) {
          return send(ws, { rtype: 'error', why: `room ${code} already has a host` })
        }
        room.host = ws
      } else {
        if (!room.host) return send(ws, { rtype: 'error', why: `nobody is hosting room ${code}` })
        room.guests.add(ws)
      }
      ws.meta = { code, role: m.role, name: String(m.name ?? 'player').slice(0, 24), id: `${Date.now()}-${Math.random()}` }
      send(ws, { rtype: 'joined', room: code, role: m.role })
      console.log(`[${code}] ${m.role} joined (${room.guests.size} guest(s))`)
      return
    }

    if (m.rtype === 'msg' && ws.meta) {
      const room = rooms.get(ws.meta.code)
      if (!room) return
      const out = { rtype: 'msg', from: ws.meta.id, body: m.body }
      if (ws.meta.role === 'host') {
        // The host's snapshots go to everyone. Directed messages could be added
        // here, but full snapshots are what the client actually wants anyway.
        for (const g of room.guests) send(g, out)
      } else if (room.host) {
        send(room.host, out)
      }
    }
  })

  ws.on('close', () => {
    const meta = ws.meta
    if (!meta) return
    const room = rooms.get(meta.code)
    if (!room) return
    if (meta.role === 'host') {
      for (const g of room.guests) send(g, { rtype: 'peer-left', why: 'the host left' })
      rooms.delete(meta.code)
      console.log(`[${meta.code}] host left, room closed`)
    } else {
      room.guests.delete(ws)
      if (room.host) send(room.host, { rtype: 'msg', from: meta.id, body: { t: 'bye' } })
      console.log(`[${meta.code}] guest left (${room.guests.size} left)`)
    }
  })
})

http.listen(port, () => {
  console.log(`open-pitch relay listening on ws://localhost:${port}`)
  console.log('Share your machine\'s address with whoever is joining, and give them the room code.')
})
