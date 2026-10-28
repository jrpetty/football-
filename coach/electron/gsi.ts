// Counter-Strike 2 Game State Integration, received in the main process. The
// game posts its own state (the player's health, money, weapons, kills this
// round, the round and the map) to an address named in a cfg file in its cfg
// folder. This listens on 127.0.0.1 only, is off until the player turns it
// on, and accepts an update only when it carries this computer's random token
// (so another program cannot feed the coach made-up data). Updates are
// passed to the window and never written to disk here. Nothing is sent to
// the game: GSI is one-way, and no file of the game's is touched except the
// cfg file the player saves themselves.

import { createServer, type Server } from 'node:http'
import { randomBytes, timingSafeEqual } from 'node:crypto'
import { readFile, writeFile, access } from 'node:fs/promises'
import { join } from 'node:path'
import type { GsiServerStatus } from '../shared/bridge.ts'

export const GSI_DEFAULT_PORT = 31982
export const GSI_CFG_NAME = 'gamestate_integration_sightline.cfg'
const MAX_BODY = 512 * 1024

interface Stored {
  readonly enabled: boolean
  readonly port: number
  readonly token: string
}

export class GsiServer {
  private server: Server | null = null
  private cfg: Stored = { enabled: false, port: GSI_DEFAULT_PORT, token: '' }
  private error: string | null = null
  private lastAt: number | null = null
  private updates = 0
  private readonly file: string
  private readonly onPayload: (body: unknown) => void

  constructor(root: string, onPayload: (body: unknown) => void) {
    this.file = join(root, 'gsi.json')
    this.onPayload = onPayload
  }

  /** Reads the saved setting (a token is made once and kept, so the cfg file stays valid) and starts if it was on. */
  async init(): Promise<void> {
    try {
      const raw = JSON.parse(await readFile(this.file, 'utf8')) as Partial<Stored>
      this.cfg = {
        enabled: raw.enabled === true,
        port: validPort(raw.port) ? raw.port : GSI_DEFAULT_PORT,
        token: typeof raw.token === 'string' && /^[a-f0-9]{32,64}$/.test(raw.token) ? raw.token : newToken(),
      }
    } catch {
      this.cfg = { enabled: false, port: GSI_DEFAULT_PORT, token: newToken() }
      await this.save()
    }
    if (this.cfg.enabled) await this.start()
  }

  async configure(req: unknown): Promise<GsiServerStatus> {
    const r = (req && typeof req === 'object' ? req : {}) as { enabled?: unknown; port?: unknown }
    const enabled = r.enabled === true
    const port = validPort(r.port) ? r.port : this.cfg.port
    const restart = enabled !== this.cfg.enabled || port !== this.cfg.port
    this.cfg = { ...this.cfg, enabled, port }
    await this.save()
    if (restart) {
      await this.stop()
      if (enabled) await this.start()
    }
    return this.status()
  }

  status(): GsiServerStatus {
    return { available: true, enabled: this.cfg.enabled, listening: !!this.server?.listening, port: this.cfg.port, lastAt: this.lastAt, updates: this.updates, error: this.error }
  }

  /** The cfg file the game reads at launch, with this computer's address and token. */
  cfgText(): string {
    return cfgText(this.cfg.port, this.cfg.token)
  }

  async stop(): Promise<void> {
    const s = this.server
    this.server = null
    if (!s) return
    const closed = new Promise<void>((resolve) => s.close(() => resolve()))
    s.closeAllConnections()
    await closed
  }

  private start(): Promise<void> {
    this.error = null
    return new Promise((resolve) => {
      const server = createServer((req, res) => {
        if (req.method !== 'POST') {
          res.writeHead(405).end()
          return
        }
        const chunks: Buffer[] = []
        let size = 0
        req.on('data', (c: Buffer) => {
          size += c.length
          if (size > MAX_BODY) {
            res.writeHead(413).end()
            req.destroy()
            return
          }
          chunks.push(c)
        })
        req.on('end', () => {
          if (size > MAX_BODY) return
          let body: unknown
          try {
            body = JSON.parse(Buffer.concat(chunks).toString('utf8'))
          } catch {
            res.writeHead(400).end()
            return
          }
          if (!tokenMatches(body, this.cfg.token)) {
            res.writeHead(403).end()
            return
          }
          // Answer at once: the game sends nothing more until it hears back.
          res.writeHead(200, { 'content-type': 'text/plain' }).end('ok')
          this.lastAt = Date.now()
          this.updates++
          this.onPayload(body)
        })
      })
      server.on('error', (err: NodeJS.ErrnoException) => {
        this.error = err.code === 'EADDRINUSE' ? `Port ${this.cfg.port} is in use by another program: pick another and save the cfg file again.` : `Could not listen: ${err.message}`
        this.server = null
        resolve()
      })
      server.listen(this.cfg.port, '127.0.0.1', () => {
        this.server = server
        resolve()
      })
    })
  }

  private async save(): Promise<void> {
    try {
      await writeFile(this.file, JSON.stringify(this.cfg), { mode: 0o600 })
    } catch {
      // The listener still works this session; the setting is not kept.
    }
  }
}

function newToken(): string {
  return randomBytes(24).toString('hex')
}

function validPort(p: unknown): p is number {
  return typeof p === 'number' && Number.isInteger(p) && p >= 1024 && p <= 65535
}

function tokenMatches(body: unknown, token: string): boolean {
  const got = (body as { auth?: { token?: unknown } } | null)?.auth?.token
  if (typeof got !== 'string' || got.length !== token.length) return false
  return timingSafeEqual(Buffer.from(got), Buffer.from(token))
}

/** The cfg file (Valve KeyValues). Only the player's own data is asked for; the game sends nothing more while playing anyway. */
export function cfgText(port: number, token: string): string {
  return [
    '"Sightline Coach"',
    '{',
    `  "uri"       "http://127.0.0.1:${port}/"`,
    '  "timeout"   "5.0"',
    '  "buffer"    "0.1"',
    '  "throttle"  "0.25"',
    '  "heartbeat" "10.0"',
    '  "auth"',
    '  {',
    `    "token" "${token}"`,
    '  }',
    '  "data"',
    '  {',
    '    "provider"            "1"',
    '    "map"                 "1"',
    '    "map_round_wins"      "1"',
    '    "round"               "1"',
    '    "player_id"           "1"',
    '    "player_state"        "1"',
    '    "player_weapons"      "1"',
    '    "player_match_stats"  "1"',
    '  }',
    '}',
    '',
  ].join('\r\n')
}

/** The game's cfg folder, when CS2 is installed in a Steam library this can find (Windows). */
export async function findCs2CfgFolder(): Promise<string | null> {
  const tail = join('steamapps', 'common', 'Counter-Strike Global Offensive', 'game', 'csgo', 'cfg')
  const roots = new Set<string>()
  const pf86 = process.env['ProgramFiles(x86)'] ?? 'C:\\Program Files (x86)'
  const steam = join(pf86, 'Steam')
  roots.add(steam)
  // Other Steam libraries are listed in libraryfolders.vdf.
  try {
    const vdf = await readFile(join(steam, 'steamapps', 'libraryfolders.vdf'), 'utf8')
    for (const m of vdf.matchAll(/"path"\s+"([^"]+)"/g)) roots.add(m[1].replace(/\\\\/g, '\\'))
  } catch {
    // No Steam in the usual place.
  }
  for (const r of roots) {
    const dir = join(r, tail)
    try {
      await access(dir)
      return dir
    } catch {
      // Not in this library.
    }
  }
  return null
}
