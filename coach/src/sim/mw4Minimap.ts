// The Modern Warfare 4 demo's picture: frames with a minimap, in step with
// the synthetic vision replay. A synthetic tac-map stands in for the map; the
// player walks to each area the replay names (callouts placed by the pack's
// rough grid), wanders a little there, dies where the replay says, shows the
// killer's minimap during the killcam, then a moment with no minimap, then
// respawns at one of two spawns. Enemy pings show up now and then. The
// stitching, death pins and killer placement run on these frames exactly as
// on a real capture. Not a real map or match.

import type { Frame, Observation } from '../core/types.ts'
import type { MinimapRegion } from '../core/map/minimap.ts'
import { mulberry32 } from './syntheticArena.ts'
import { drawMinimap, tacMap, type TacMap } from './tacMap.ts'
import type { VisionReplay } from './visionReplay.ts'

const GRID: Readonly<Record<string, readonly [number, number]>> = {
  nw: [1 / 6, 1 / 6],
  n: [0.5, 1 / 6],
  ne: [5 / 6, 1 / 6],
  w: [1 / 6, 0.5],
  c: [0.5, 0.5],
  e: [5 / 6, 0.5],
  sw: [1 / 6, 5 / 6],
  s: [0.5, 5 / 6],
  se: [5 / 6, 5 / 6],
}

const MAP_SIZE = 720
/** Map pixels per minimap pixel. */
const SCALE = 1.4
const SPEED = 60 // map px per second, at most
const DEAD_BEFORE_MS = 1200 // the death happened this long before the killcam frame that reported it
const KILLCAM_MS = 2600 // the killer's minimap shows this long
const BLANK_MS = 1300 // then no minimap until the respawn

interface State {
  readonly x: number
  readonly y: number
  readonly heading: number
  /** 'alive', 'killcam' (the killer's minimap, centred on them), or 'blank'. */
  readonly view: 'alive' | 'killcam' | 'blank'
}

export interface Mw4DemoPicture {
  readonly map: TacMap
  /** Where each named area is on the synthetic map. */
  readonly areas: ReadonlyMap<string, readonly [number, number]>
  /** The player's state at a time (ms on the replay's clock). */
  at(t: number): State & { readonly cx: number; readonly cy: number }
  /** A frame at a time: dark "gameplay", the minimap in its usual place. */
  frame(t: number, width?: number, height?: number): Frame
}

export function mw4DemoPicture(
  replay: VisionReplay,
  opts: { readonly seed: number; readonly region: MinimapRegion; readonly callouts?: readonly { readonly name: string; readonly grid?: string }[] },
): Mw4DemoPicture {
  const rnd = mulberry32(opts.seed ^ 0x5eed)
  const map = tacMap(opts.seed % 1000)
  // Areas: the pack's rough grid, spread where several share a cell; others placed around the middle.
  const areas = new Map<string, [number, number]>()
  const perCell = new Map<string, number>()
  const place = (name: string, grid?: string) => {
    if (areas.has(name)) return
    const g = grid ? GRID[grid] : undefined
    let x: number
    let y: number
    if (g) {
      const k = perCell.get(grid!) ?? 0
      perCell.set(grid!, k + 1)
      const a = k * 2.399963
      const r = k ? 0.06 + 0.03 * Math.sqrt(k) : 0
      ;[x, y] = [g[0] + r * Math.cos(a), g[1] + r * Math.sin(a)]
    } else {
      const a = areas.size * 2.399963
      const r = 0.15 + 0.25 * rnd()
      ;[x, y] = [0.5 + r * Math.cos(a), 0.5 + r * Math.sin(a)]
    }
    areas.set(name, [100 + Math.max(0, Math.min(1, x)) * (MAP_SIZE - 200), 100 + Math.max(0, Math.min(1, y)) * (MAP_SIZE - 200)])
  }
  for (const c of opts.callouts ?? []) place(c.name, c.grid)

  // What the replay says, by time: where the player is read, deaths, killers' spots, enemy pings.
  const reads: { t: number; area: string }[] = []
  const deaths: { t: number; killerSpot: string | null }[] = []
  const pings: { t: number; area: string }[] = []
  for (const b of replay.batches) {
    const obs = (k: string) => b.observations.find((o: Observation) => o.kind === 'state' && o.eventType === k)?.value
    const loc = obs('location')
    if (typeof loc === 'string') {
      place(loc)
      reads.push({ t: b.frameAt, area: loc })
    }
    const death = b.observations.find((o) => o.kind === 'event' && o.eventType === 'death')
    if (death) {
      const spot = obs('killerSpot')
      if (typeof spot === 'string') place(spot)
      deaths.push({ t: b.frameAt, killerSpot: typeof spot === 'string' ? spot : null })
    }
    const enemy = obs('enemyPositions')
    if (typeof enemy === 'string') {
      const name = enemy.replace(/^\d+\s+/, '')
      place(name)
      pings.push({ t: b.frameAt, area: name })
    }
  }
  reads.sort((a, b) => a.t - b.t)
  const spawns: [number, number][] = [
    [130, MAP_SIZE - 140],
    [MAP_SIZE - 140, 130],
  ]

  // Simulate the whole replay at 100 ms steps.
  const STEP = 100
  const end = replay.endsAt + 2000
  const states: State[] = []
  let x = areas.get(reads[0]?.area ?? '')?.[0] ?? spawns[0][0]
  let y = areas.get(reads[0]?.area ?? '')?.[1] ?? spawns[0][1]
  let heading = 0
  let r = 0
  let d = 0
  let lives = 0
  let wander = [0, 0]
  for (let t = 0; t <= end; t += STEP) {
    while (r < reads.length && reads[r].t < t) r++
    const death = deaths[d]
    if (death && t >= death.t - DEAD_BEFORE_MS) {
      const since = t - (death.t - DEAD_BEFORE_MS)
      if (since < KILLCAM_MS) {
        states.push({ x, y, heading, view: 'killcam' })
        continue
      }
      if (since < KILLCAM_MS + BLANK_MS) {
        states.push({ x, y, heading, view: 'blank' })
        continue
      }
      // Respawn.
      ;[x, y] = spawns[lives++ % 2]
      d++
    }
    // Head for the area the next reading names (being there by then), wandering a little around it.
    const next = reads[Math.min(r, reads.length - 1)]
    const anchor = next ? (areas.get(next.area) ?? [x, y]) : [x, y]
    if (t % 3000 === 0) wander = [(rnd() - 0.5) * 50, (rnd() - 0.5) * 50]
    const tx = anchor[0] + wander[0]
    const ty = anchor[1] + wander[1]
    const dx = tx - x
    const dy = ty - y
    const dist = Math.hypot(dx, dy)
    const step = Math.min(dist, (SPEED * STEP) / 1000)
    if (dist > 0.5) {
      x += (dx / dist) * step
      y += (dy / dist) * step
      heading = (Math.atan2(dx, -dy) * 180) / Math.PI
    }
    states.push({ x, y, heading, view: 'alive' })
  }
  const stateAt = (t: number): State => states[Math.max(0, Math.min(states.length - 1, Math.round(t / STEP)))]
  /** The killer, for a killcam: at the replay's killer spot, or a little way off. */
  const killerAt = (t: number, s: State): [number, number] => {
    const death = deaths.find((k) => t >= k.t - DEAD_BEFORE_MS && t < k.t - DEAD_BEFORE_MS + KILLCAM_MS)
    const spot = death?.killerSpot ? areas.get(death.killerSpot) : undefined
    return spot ? [spot[0] + 6, spot[1] - 4] : [s.x + 70, s.y - 40]
  }

  return {
    map,
    areas,
    at(t) {
      const s = stateAt(t)
      const [cx, cy] = s.view === 'killcam' ? killerAt(t, s) : [s.x, s.y]
      return { ...s, cx, cy }
    },
    frame(t, width = 960, height = 540) {
      const data = new Uint8ClampedArray(width * height * 4)
      // "Gameplay": a dark scene that keeps changing.
      const shift = (t / 40) | 0
      for (let yy = 0; yy < height; yy++) {
        const row = 34 + ((yy * 40) / height) | 0
        for (let xx = 0; xx < width; xx++) {
          const o = (yy * width + xx) * 4
          const n = ((xx * 7 + yy * 13 + shift) & 31) - 16
          data[o] = row + n
          data[o + 1] = row + 4 + n
          data[o + 2] = row + 10 + n
          data[o + 3] = 255
        }
      }
      const s = this.at(t)
      if (s.view !== 'blank') {
        const px = Math.round(opts.region.w * width)
        const near = pings.find((p) => t >= p.t - 1500 && t < p.t + 1500)
        const enemies: { x: number; y: number }[] = []
        if (s.view === 'alive' && near) {
          const a = areas.get(near.area)
          if (a) enemies.push({ x: a[0], y: a[1] })
        }
        drawMinimap(map, s.cx, s.cy, px, { scale: SCALE, heading: s.heading, enemies, noise: 0.025, seed: Math.round(t) }, data, width, Math.round(opts.region.x * width), Math.round(opts.region.y * height))
      }
      return { width, height, data, timestamp: t }
    },
  }
}
