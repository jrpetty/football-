// Saving and loading whole worlds. Typed arrays are base64-encoded; in-flight
// tasks are dropped (people simply pick up their plan again on load). Saves
// live in IndexedDB, which comfortably holds large worlds, and can be
// exported/imported as JSON files.

import { World, type WorldConfig, type Weather } from './world.ts'
import type { Agent, Tribe, Building, Animal, ChronicleEntry, Chapter, WorldEvent, Sample } from './types.ts'

const SAVE_VERSION = 1

function b64(arr: ArrayBufferView): string {
  const bytes = new Uint8Array(arr.buffer, arr.byteOffset, arr.byteLength)
  let s = ''
  const CH = 0x8000
  for (let i = 0; i < bytes.length; i += CH) s += String.fromCharCode(...bytes.subarray(i, i + CH))
  return btoa(s)
}
function unb64(s: string): Uint8Array {
  const bin = atob(s)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

export interface SaveFile {
  v: number
  savedAt: number
  cfg: WorldConfig
  time: number
  rng: number
  nextId: number
  maps: Record<string, string>
  explored: Record<string, string>
  regrow: World['regrow']
  agents: Agent[]
  tribes: Tribe[]
  buildings: Building[]
  animals: Animal[]
  chronicle: ChronicleEntry[]
  chapters: Chapter[]
  events: WorldEvent[]
  samples: Sample[]
  weather: Weather
  weatherUntil: number
  milestones: string[]
}

export function serialize(w: World): SaveFile {
  const explored: Record<string, string> = {}
  for (const [id, arr] of w.explored) explored[id] = b64(arr)
  return {
    v: SAVE_VERSION,
    savedAt: Date.now(),
    cfg: w.cfg,
    time: w.time,
    rng: w.rng.state,
    nextId: w.nextId,
    maps: {
      biome: b64(w.biome), elev: b64(w.elev), temp: b64(w.temp), moist: b64(w.moist), feature: b64(w.feature),
      amount: b64(w.amount), fertility: b64(w.fertility), road: b64(w.road), occ: b64(w.occ),
    },
    explored,
    regrow: w.regrow,
    agents: w.agents.map((a) => ({ ...a, task: null, aiPending: false, speech: null, emote: null })),
    tribes: w.tribes,
    buildings: w.buildings,
    animals: w.animals.filter((a) => a.alive),
    chronicle: w.chronicle,
    chapters: w.chapters,
    events: w.events,
    samples: w.samples,
    weather: w.weather,
    weatherUntil: w.weatherUntil,
    milestones: w.milestones,
  }
}

export function deserialize(s: SaveFile): World {
  const w = new World(s.cfg, true)
  const f32 = (k: string) => new Float32Array(unb64(s.maps[k]).buffer)
  w.biome = unb64(s.maps.biome)
  w.elev = f32('elev')
  w.temp = f32('temp')
  w.moist = f32('moist')
  w.feature = unb64(s.maps.feature)
  w.amount = new Uint16Array(unb64(s.maps.amount).buffer)
  w.fertility = f32('fertility')
  w.road = unb64(s.maps.road)
  w.occ = new Int32Array(unb64(s.maps.occ).buffer)
  for (const id in s.explored) w.explored.set(+id, unb64(s.explored[id]))
  w.time = s.time
  w.rng.state = s.rng
  w.nextId = s.nextId
  w.regrow = s.regrow ?? []
  w.agents = s.agents.map((a) => ({ ...a, journal: a.journal ?? [], emote: null, look: a.look ?? { skin: a.id % 6, hair: a.id % 7, style: a.id % 6, beard: false, height: 1, build: 1, accent: '#a1887f', marks: 0 }, task: null, aiPending: false }))
  w.tribes = s.tribes.map((t) => ({ ...t, insight: t.insight ?? {}, lost: t.lost ?? [] }))
  w.buildings = s.buildings
  w.animals = s.animals
  w.chronicle = s.chronicle
  w.chapters = s.chapters ?? []
  w.events = s.events ?? []
  w.samples = s.samples ?? []
  w.weather = s.weather ?? 'clear'
  w.weatherUntil = s.weatherUntil ?? 0
  w.milestones = s.milestones ?? []
  for (const a of w.agents) w.agentById.set(a.id, a)
  for (const b of w.buildings) w.buildingById.set(b.id, b)
  for (const t of w.tribes) w.techSets.set(t.id, new Set(t.techs))
  w.computeComponents()
  w.buildVersion++
  return w
}

// ------------------------------------------------------------------ IndexedDB

const DB = 'genesis-worlds'
const STORE = 'saves'

function db(): Promise<IDBDatabase> {
  return new Promise((res, rej) => {
    const r = indexedDB.open(DB, 1)
    r.onupgradeneeded = () => r.result.createObjectStore(STORE)
    r.onsuccess = () => res(r.result)
    r.onerror = () => rej(r.error)
  })
}

export interface SaveMeta {
  slot: string
  name: string
  savedAt: number
  year: number
  pop: number
  era: number
}

export async function saveToSlot(slot: string, w: World): Promise<void> {
  const d = await db()
  const data = serialize(w)
  const meta: SaveMeta = { slot, name: w.cfg.name, savedAt: Date.now(), year: Math.floor(w.time / (12 * 1440)) + 1, pop: w.living.length, era: w.era }
  await new Promise<void>((res, rej) => {
    const tx = d.transaction(STORE, 'readwrite')
    tx.objectStore(STORE).put({ meta, data }, slot)
    tx.oncomplete = () => res()
    tx.onerror = () => rej(tx.error)
  })
}

export async function loadSlot(slot: string): Promise<World | null> {
  const d = await db()
  const rec = await new Promise<{ meta: SaveMeta; data: SaveFile } | undefined>((res, rej) => {
    const r = d.transaction(STORE).objectStore(STORE).get(slot)
    r.onsuccess = () => res(r.result)
    r.onerror = () => rej(r.error)
  })
  return rec ? deserialize(rec.data) : null
}

export async function listSaves(): Promise<SaveMeta[]> {
  try {
    const d = await db()
    return await new Promise((res, rej) => {
      const out: SaveMeta[] = []
      const r = d.transaction(STORE).objectStore(STORE).openCursor()
      r.onsuccess = () => {
        const c = r.result
        if (c) { out.push((c.value as { meta: SaveMeta }).meta); c.continue() }
        else res(out.sort((a, b) => b.savedAt - a.savedAt))
      }
      r.onerror = () => rej(r.error)
    })
  } catch {
    return []
  }
}

export async function deleteSlot(slot: string): Promise<void> {
  const d = await db()
  await new Promise<void>((res) => {
    const tx = d.transaction(STORE, 'readwrite')
    tx.objectStore(STORE).delete(slot)
    tx.oncomplete = () => res()
  })
}
