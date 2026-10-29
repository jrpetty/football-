// Reads a Counter-Strike 2 demo (.dem) the player picked, on this computer,
// with demoparser2 (MIT, native). Parsing takes a second or two of CPU, so it
// runs in a worker thread and the app never freezes. Only the compact
// DemoData below leaves the worker; the demo file is never changed or sent.

import { createRequire } from 'node:module'
import { stat } from 'node:fs/promises'
import { isMainThread, parentPort, Worker, workerData } from 'node:worker_threads'
import type { DemoData, DemoDeath } from '../src/core/cs2/demo.ts'

const require = createRequire(import.meta.url)

/** Demos over this size are refused (a full competitive match is ~100–400 MB). */
const MAX_BYTES = 1.5 * 1024 ** 3

type Row = Record<string, unknown>
interface Parser {
  parseHeader(path: string): Row
  parsePlayerInfo(path: string): Row[]
  parseEvents(path: string, names: string[], player?: string[], other?: string[]): Row[]
  parseTicks(path: string, props: string[], ticks?: number[]): Row[]
}

const EVENTS = ['begin_new_match', 'round_announce_match_start', 'player_death', 'player_hurt', 'player_blind', 'round_end', 'round_freeze_end', 'smokegrenade_detonate', 'flashbang_detonate', 'hegrenade_detonate', 'inferno_startburn']
const NADES: Record<string, DemoData['nades'][number]['type']> = { smokegrenade_detonate: 'smoke', flashbang_detonate: 'flash', hegrenade_detonate: 'he', inferno_startburn: 'fire' }

const str = (v: unknown): string | null => (typeof v === 'string' && v ? v : null)
const num = (v: unknown): number => (typeof v === 'number' && Number.isFinite(v) ? v : 0)
const id = (v: unknown): string | null => {
  const s = typeof v === 'string' ? v : typeof v === 'number' || typeof v === 'bigint' ? String(v) : null
  return s && s !== '0' ? s : null
}
const pos = (e: Row, who: 'user' | 'attacker'): [number, number, number] | null =>
  typeof e[`${who}_X`] === 'number' ? [Math.round(num(e[`${who}_X`])), Math.round(num(e[`${who}_Y`])), Math.round(num(e[`${who}_Z`]))] : null

/** Turn the parser's rows into DemoData (pure; exported for tests). */
export function toDemoData(header: Row, info: Row[], events: Row[], eco: Row[]): DemoData {
  const by = (name: string) => events.filter((e) => e.event_name === name).sort((a, b) => num(a.tick) - num(b.tick))
  // The match proper starts at the last (re)start: a knife round, warm-up or
  // restart before it does not count.
  const ends = by('round_end').filter((e) => e.winner === 'T' || e.winner === 'CT')
  const lastEnd = ends.length ? num(ends[ends.length - 1].tick) : Infinity
  const starts = [...by('begin_new_match'), ...by('round_announce_match_start')].map((e) => num(e.tick)).filter((t) => t < lastEnd)
  const freeze = by('round_freeze_end').map((e) => num(e.tick))
  const start = starts.length ? Math.max(...starts) : (freeze[0] ?? 0)
  const rounds = ends.filter((e) => num(e.tick) > start).map((e) => ({ endTick: num(e.tick), winner: e.winner as 'T' | 'CT', reason: str(e.reason) }))
  const live = (e: Row) => num(e.tick) >= start && (!rounds.length || num(e.tick) <= rounds[rounds.length - 1].endTick)
  const deaths: DemoDeath[] = by('player_death')
    .filter(live)
    .flatMap((e) => {
      const victim = id(e.user_steamid)
      if (!victim) return []
      return [
        {
          tick: num(e.tick),
          attacker: id(e.attacker_steamid),
          victim,
          assister: id(e.assister_steamid),
          weapon: str(e.weapon) ?? 'world',
          headshot: e.headshot === true,
          flashAssist: e.assistedflash === true,
          victimPos: pos(e, 'user') ?? [0, 0, 0],
          attackerPos: pos(e, 'attacker'),
          victimPlace: str(e.user_last_place_name),
          attackerPlace: str(e.attacker_last_place_name),
        },
      ]
    })
  const hurts = by('player_hurt')
    .filter(live)
    .flatMap((e) => {
      const victim = id(e.user_steamid)
      return victim ? [{ tick: num(e.tick), attacker: id(e.attacker_steamid), victim, dmg: num(e.dmg_health), weapon: str(e.weapon) ?? 'world' }] : []
    })
  const blinds = by('player_blind')
    .filter(live)
    .flatMap((e) => {
      const victim = id(e.user_steamid)
      return victim ? [{ tick: num(e.tick), attacker: id(e.attacker_steamid), victim, seconds: Math.round(num(e.blind_duration) * 10) / 10 }] : []
    })
  const nades = events
    .filter((e) => typeof e.event_name === 'string' && NADES[e.event_name] && live(e))
    .map((e) => ({ tick: num(e.tick), type: NADES[e.event_name as string], thrower: id(e.user_steamid), x: Math.round(num(e.x)), y: Math.round(num(e.y)), z: Math.round(num(e.z)) }))
    .sort((a, b) => a.tick - b.tick)
  // Economy at each round's start: the last freeze-time end inside the round.
  const roundStart = new Map<number, number>()
  for (const t of freeze) {
    const n = rounds.findIndex((r) => t <= r.endTick) + 1
    if (n > 0 && t >= start) roundStart.set(t, n)
  }
  const lastStart = new Map<number, number>()
  for (const [t, n] of roundStart) lastStart.set(n, Math.max(t, lastStart.get(n) ?? 0))
  const economy = eco.flatMap((e) => {
    const n = roundStart.get(num(e.tick))
    const steamid = id(e.steamid)
    const team = num(e.team_num)
    if (!n || !steamid || (team !== 2 && team !== 3) || lastStart.get(n) !== num(e.tick)) return []
    return [{ round: n, steamid, team: team as 2 | 3, equip: num(e.current_equip_value), balance: num(e.balance) }]
  })
  const players = info.flatMap((p) => {
    const steamid = id(p.steamid)
    const team = num(p.team_number)
    return steamid && (team === 2 || team === 3) ? [{ steamid, name: str(p.name) ?? steamid }] : []
  })
  return {
    map: str(header.map_name) ?? 'unknown',
    server: str(header.server_name),
    tickRate: 64,
    players,
    rounds,
    deaths,
    hurts,
    blinds,
    nades,
    economy,
  }
}

function parse(path: string): DemoData {
  const p = require('@laihoe/demoparser2') as Parser
  const header = p.parseHeader(path)
  const info = p.parsePlayerInfo(path)
  const events = p.parseEvents(path, EVENTS, ['X', 'Y', 'Z', 'last_place_name', 'team_num'], [])
  const freeze = events.filter((e) => e.event_name === 'round_freeze_end').map((e) => num(e.tick))
  const eco = freeze.length ? p.parseTicks(path, ['current_equip_value', 'balance', 'team_num'], freeze) : []
  return toDemoData(header, info, events, eco)
}

/** Read a demo in a worker thread. */
export async function readDemo(path: string): Promise<{ ok: true; data: DemoData } | { ok: false; error: string }> {
  if (!/\.dem$/i.test(path)) return { ok: false, error: 'That is not a demo file (.dem).' }
  const s = await stat(path).catch(() => null)
  if (!s?.isFile()) return { ok: false, error: 'Could not open that file.' }
  if (s.size > MAX_BYTES) return { ok: false, error: 'That demo is larger than 1.5 GB; it was not read.' }
  return new Promise((resolve) => {
    const w = new Worker(new URL(import.meta.url), { workerData: { cs2demo: true, path } })
    w.once('message', (m: { ok: true; data: DemoData } | { ok: false; error: string }) => resolve(m))
    w.once('error', (e) => resolve({ ok: false, error: `Could not read the demo: ${e.message}` }))
    w.once('exit', (code) => code !== 0 && resolve({ ok: false, error: `Could not read the demo (exit ${code}).` }))
  })
}

if (!isMainThread && (workerData as { cs2demo?: boolean } | null)?.cs2demo && parentPort) {
  const port = parentPort
  try {
    port.postMessage({ ok: true, data: parse((workerData as { path: string }).path) })
  } catch (e) {
    port.postMessage({ ok: false, error: `Could not read the demo: ${e instanceof Error ? e.message : String(e)}` })
  }
}
