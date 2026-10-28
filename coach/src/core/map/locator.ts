// Where the player is, from their own minimap, through a match: the stitched
// map for the map being played (kept between matches), and on it where each
// death and kill happened, where the killer was, the player's path life by
// life, where enemy pings showed up, and where each callout and height was
// read. Positions are in the stitched map's pixels (see minimap.ts).
//
// A death is read from a killcam or death screen a moment after it happened;
// the place is where the player's own minimap was just before it cut away
// (to the killcam's minimap, or to no minimap). When the killcam shows the
// killer's minimap, the killer is placed where it ends. Pings and positions
// during a killcam are the killer's view, not the player's, and are left out.

import type { VisibleState } from '../types.ts'
import { CELL, MinimapMosaic, readWindow, type MinimapRegion, type StoredMosaic, type TrackPoint, type TrackState } from './minimap.ts'
import type { OfficialMap } from './official.ts'

export interface MinimapSpec {
  /** Where the minimap usually is (normalised to the captured frame). */
  readonly region: MinimapRegion
  /** Player-context key saying whether the minimap is fixed (north up) or rotating; only fixed minimaps are stitched. */
  readonly fixedContextKey?: string
  /** False when the game's player marker is not a bright arrow at the minimap's centre. */
  readonly marker?: boolean
  /** The minimap draws the official overhead map itself (Counter-Strike's radar): placements must match it closely. */
  readonly sameArt?: boolean
}

export interface MapPin {
  readonly kind: 'death' | 'kill'
  readonly piece: number
  readonly x: number
  readonly y: number
  /** Seconds since the match started. */
  readonly t: number
  /** Which life (0 = until the first death). */
  readonly life: number
  /** The callout read there, if any. */
  readonly area?: string | null
  /** Deaths: where the killer was: the end of the killcam's minimap, or the callout the killcam named. */
  readonly killer?: { readonly piece: number; readonly x: number; readonly y: number; readonly from: 'killcam' | 'callout'; readonly spot?: string | null } | null
  /** The killer's weapon (deaths) or the weapon in hand (kills). */
  readonly weapon?: string | null
  readonly range?: string | null
}

/** One match on the stitched map. */
export interface MatchPins {
  readonly map: string
  readonly pins: readonly MapPin[]
  /** The player's own path: [piece, x, y, seconds since the start, life], about one point a second. */
  readonly trail: readonly (readonly [number, number, number, number, number])[]
  /** Enemy pings seen on the minimap, per 8-px cell ("piece:cx:cy" → sightings). */
  readonly enemies: Readonly<Record<string, number>>
}

export interface LocatorStatus {
  readonly enabled: boolean
  /** Why not, when not enabled. */
  readonly reason: string | null
  readonly map: string | null
  readonly state: TrackState | 'off'
  readonly ncc: number
  /** Ms since the minimap was last read well. */
  readonly ageMs: number
  /** Stitched map area, px². */
  readonly seen: number
  readonly pieces: number
  readonly here: { readonly piece: number; readonly x: number; readonly y: number } | null
  /** On the official overhead map: whether one is in use, and the share of this match's path placed on it. */
  readonly official: { readonly placed: number } | null
}

interface RawEvent {
  readonly kind: 'death' | 'kill'
  readonly t: number
  readonly area: string | null
  readonly killerSpot: string | null
  readonly weapon: string | null
  readonly range: string | null
}

/** A gap in the trail longer than this means the minimap was gone (a death screen, a menu). */
const GAP_MS = 1600
/** How far back from the killcam frame the cut-away is looked for. */
const LOOK_BACK_MS = 8000
/** Handed over (to show and save) at most this often while playing. */
const SAVE_EVERY_MS = 6000
const MAX_PINGS = 6000

const key = (name: string) => name.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim()

export class MinimapLocator {
  private readonly spec: MinimapSpec
  private readonly stored = new Map<string, StoredMosaic>()
  private region: MinimapRegion
  private fixed = true
  private mosaic: MinimapMosaic | null = null
  /** Stitched before the map's name is known. */
  private pending: MinimapMosaic | null = null
  private matchStart = -Infinity
  private events: RawEvent[] = []
  private pings: [number, number, number, number][] = []
  private lastFrameAt = 0
  private lastSaveAt = 0
  private lastMapRead = -Infinity
  /** Official overhead maps, by map name. */
  private readonly officials = new Map<string, OfficialMap>()

  constructor(spec: MinimapSpec, opts: { readonly stored?: readonly StoredMosaic[]; readonly region?: MinimapRegion | null; readonly fixed?: boolean } = {}) {
    this.spec = spec
    this.region = opts.region ?? spec.region
    this.fixed = opts.fixed ?? true
    for (const s of opts.stored ?? []) this.stored.set(key(s.map), s)
  }

  configure(region: MinimapRegion | null, fixed: boolean): void {
    this.region = region ?? this.spec.region
    this.fixed = fixed
  }

  private get active(): MinimapMosaic | null {
    return this.mosaic ?? this.pending
  }

  private fresh(name: string, stored: StoredMosaic | null): MinimapMosaic {
    const m = new MinimapMosaic(name, stored)
    m.needMarker = this.spec.marker !== false
    m.sameArt = this.spec.sameArt === true
    return m
  }

  /** One analysed frame. `skip` when a menu or loading screen is showing. */
  frame(frame: { readonly width: number; readonly height: number; readonly data: Uint8ClampedArray; readonly timestamp: number }, skip: boolean): void {
    if (!this.fixed || skip) return
    const t = frame.timestamp
    this.lastFrameAt = t
    if (!this.active) this.pending = this.fresh('', null)
    const m = this.active!
    const w = readWindow(frame, this.region)
    const r = m.track(w, t)
    if (r.point && (r.state === 'tracking' || r.state === 'found') && w.enemies.length && this.pings.length < MAX_PINGS) {
      for (const e of w.enemies) this.pings.push([t, r.point.piece, r.point.x + e.dx, r.point.y + e.dy])
    }
  }

  /** The vision's readings: the map's name, and the callout and height where the player is. */
  observe(visible: VisibleState, keys: { readonly mapKey: string; readonly locationKey: string; readonly elevationKey?: string }): void {
    const mapV = visible.values[keys.mapKey]
    if (mapV && !mapV.conflicted && typeof mapV.value === 'string' && mapV.value.trim() && mapV.observedAt > this.lastMapRead) {
      this.lastMapRead = mapV.observedAt
      this.setMap(mapV.value.trim())
    }
    const m = this.mosaic
    if (!m) return
    const loc = visible.values[keys.locationKey]
    if (loc && !loc.conflicted && typeof loc.value === 'string' && loc.value.trim() && !this.learnedAt.has(`l${loc.observedAt}`)) {
      this.learnedAt.add(`l${loc.observedAt}`)
      const name = loc.value.trim().slice(0, 40)
      // Only on the second reading of the same area in a row: by then the player is in it, not walking in.
      const settled = this.lastLoc?.name === name && loc.observedAt - this.lastLoc.at <= 6000
      this.lastLoc = { name, at: loc.observedAt }
      const at = settled ? m.positionAt(loc.observedAt, 1200) : null
      if (at && at.ncc >= 0.6) m.learnCallout(name, at)
    }
    const el = keys.elevationKey ? visible.values[keys.elevationKey] : undefined
    if (el && !el.conflicted && typeof el.value === 'string' && !this.learnedAt.has(`e${el.observedAt}`)) {
      this.learnedAt.add(`e${el.observedAt}`)
      const at = m.positionAt(el.observedAt, 1200)
      if (at && at.ncc >= 0.6) m.learnHeight(el.value, at)
    }
    if (this.learnedAt.size > 400) this.learnedAt.clear()
  }

  private learnedAt = new Set<string>()
  private lastLoc: { name: string; at: number } | null = null

  /** The map being played (as read on screen). What was stitched before is carried over. */
  setMap(name: string): void {
    if (this.mosaic && key(this.mosaic.map) === key(name)) return
    const prev = this.mosaic
    const next = this.fresh(name, this.stored.get(key(name)) ?? null)
    if (this.pending) {
      next.adopt(this.pending)
      this.pending = null
    }
    if (prev) this.stored.set(key(prev.map), prev.save())
    next.setOfficial(this.officials.get(key(name)) ?? null)
    this.mosaic = next
  }

  /** The official overhead map of a map (null: none). Used from the next frame. */
  setOfficial(name: string, off: OfficialMap | null): void {
    if (off) this.officials.set(key(name), off)
    else this.officials.delete(key(name))
    if (this.mosaic && key(this.mosaic.map) === key(name)) this.mosaic.setOfficial(off)
  }

  /** A death or kill (at the time of the frame it was read from). */
  event(ev: RawEvent): void {
    if (ev.t >= this.matchStart) this.events.push(ev)
  }

  /** The current match on the stitched map. */
  snapshot(): MatchPins | null {
    const m = this.mosaic
    if (!m) return null
    const trail = m.trail.filter((p) => p.t >= this.matchStart)
    if (!trail.length && !this.events.length) return null
    const start = Number.isFinite(this.matchStart) ? this.matchStart : (trail[0]?.t ?? 0)
    const sec = (t: number) => Math.max(0, Math.round((t - start) / 100) / 10)
    const deaths = this.events.filter((e) => e.kind === 'death').sort((a, b) => a.t - b.t)
    // For each death: where the player's own minimap cut away, and the stretch (a killcam) that is not theirs.
    const cuts = deaths.map((d) => ({ d, ...cutAway(trail, d.t) }))
    const owned = (t: number) => !cuts.some((c) => t >= c.from && t < c.until)
    const lifeAt = (t: number) => cuts.filter((c) => c.from <= t).length
    const pins: MapPin[] = []
    for (const c of cuts) {
      // No cut seen (a killcam right next to the player, or no killcam): where they were a moment before it was read.
      const at = c.before ?? m.positionAt(c.d.t - 1500, 1500)
      if (!at) continue
      const p = m.resolve(at.piece, at.x, at.y)
      const callout = c.d.killerSpot ? m.calloutAt(c.d.killerSpot) : null
      const killcam = c.killcamEnd ? m.resolve(c.killcamEnd.piece, c.killcamEnd.x, c.killcamEnd.y) : null
      const killer =
        killcam && killcam.piece === p.piece && Math.hypot(killcam.x - p.x, killcam.y - p.y) < 300
          ? { ...killcam, from: 'killcam' as const, spot: c.d.killerSpot }
          : callout && callout.piece === p.piece
            ? { piece: callout.piece, x: callout.x, y: callout.y, from: 'callout' as const, spot: c.d.killerSpot }
            : null
      pins.push({ kind: 'death', ...p, t: sec(at.t), life: lifeAt(at.t), area: c.d.area, killer, weapon: c.d.weapon, range: c.d.range })
    }
    for (const k of this.events.filter((e) => e.kind === 'kill')) {
      const at = m.positionAt(k.t, 1500)
      if (!at || !owned(at.t)) continue
      pins.push({ kind: 'kill', ...m.resolve(at.piece, at.x, at.y), t: sec(at.t), life: lifeAt(at.t), area: k.area, weapon: k.weapon, range: k.range })
    }
    pins.sort((a, b) => a.t - b.t)
    const path: [number, number, number, number, number][] = []
    let lastT = -Infinity
    for (const p of trail) {
      if (!owned(p.t) || p.t - lastT < 1000) continue
      lastT = p.t
      const r = m.resolve(p.piece, p.x, p.y)
      path.push([r.piece, r.x, r.y, sec(p.t), lifeAt(p.t)])
    }
    const enemies: Record<string, number> = {}
    const lastPing = new Map<string, number>()
    for (const [t, piece, x, y] of this.pings) {
      if (t < this.matchStart || !owned(t)) continue
      const r = m.resolve(piece, x, y)
      const k = `${r.piece}:${Math.floor(r.x / CELL)}:${Math.floor(r.y / CELL)}`
      // The same ping in the same place, frame after frame, counts once every 2 s.
      if (t - (lastPing.get(k) ?? -Infinity) < 2000) continue
      lastPing.set(k, t)
      enemies[k] = (enemies[k] ?? 0) + 1
    }
    return { map: m.map, pins, trail: path, enemies }
  }

  /** The match is over: its pins, and a fresh start for the next one (the stitched map stays). */
  finish(): MatchPins | null {
    const out = this.snapshot()
    this.newMatch()
    return out
  }

  newMatch(): void {
    this.matchStart = this.lastFrameAt
    this.events = []
    this.pings = []
  }

  status(now: number): LocatorStatus {
    const m = this.active
    if (!this.fixed) return { enabled: false, reason: 'rotating', map: this.mosaic?.map ?? null, state: 'off', ncc: 0, ageMs: Infinity, seen: 0, pieces: 0, here: null, official: null }
    return {
      enabled: true,
      reason: null,
      map: this.mosaic?.map ?? null,
      state: m?.last.state ?? 'lost',
      ncc: m?.last.ncc ?? 0,
      ageMs: m && m.last.state !== 'lost' && m.last.state !== 'skipped' ? now - m.last.at : Infinity,
      seen: m?.seenArea ?? 0,
      pieces: m?.pieceCount ?? 0,
      here: this.mosaic?.here() ?? null,
      official: this.mosaic?.hasOfficial ? { placed: this.placedShare(this.mosaic) } : null,
    }
  }

  private placedShare(m: MinimapMosaic): number {
    let n = 0
    let placed = 0
    for (let i = m.trail.length - 1; i >= 0 && m.trail[i].t >= this.matchStart && n < 2000; i--) {
      n++
      if (m.alignmentFor(m.resolve(m.trail[i].piece, 0, 0).piece)) placed++
    }
    return n ? placed / n : 0
  }

  /** The stitched map to save, when it changed (at most every 20 s unless forced). */
  takeSave(now: number, force = false): StoredMosaic | null {
    const m = this.mosaic
    if (!m || !m.dirty || (!force && now - this.lastSaveAt < SAVE_EVERY_MS)) return null
    this.lastSaveAt = now
    m.dirty = false
    const s = m.save()
    this.stored.set(key(m.map), s)
    return s.pieces.length ? s : null
  }
}

/**
 * Where the player's own minimap cut away before a death read at `t`: the
 * last point before a jump (to a killcam's minimap) or a gap (no minimap),
 * within a few seconds; the stretch after it that is not the player's (to the
 * next cut); and where a killcam's minimap ended.
 */
function cutAway(trail: readonly TrackPoint[], t: number): { before: TrackPoint | null; from: number; until: number; killcamEnd: TrackPoint | null } {
  let i = -1
  for (let k = trail.length - 1; k >= 0; k--) if (trail[k].t <= t) {
    i = k
    break
  }
  if (i < 0) return { before: null, from: t, until: t, killcamEnd: null }
  // The minimap was already gone when the death was read.
  if (t - trail[i].t > GAP_MS) return { before: trail[i], from: trail[i].t + 1, until: nextCut(trail, i + 1, t), killcamEnd: null }
  for (let k = i; k > 0 && trail[k].t >= t - LOOK_BACK_MS; k--) {
    const jump = trail[k].jump
    const gap = trail[k].t - trail[k - 1].t > GAP_MS
    if (!jump && !gap) continue
    // A jump just before the death was read: the killcam's minimap. It ends at the next cut.
    const until = nextCut(trail, k + 1, t)
    let end: TrackPoint | null = null
    if (jump) for (let q = k; q < trail.length && trail[q].t < until; q++) end = trail[q]
    return { before: trail[k - 1], from: trail[k].t, until, killcamEnd: end && end.t - trail[k].t >= 800 ? end : null }
  }
  return { before: null, from: t, until: nextCut(trail, i + 1, t), killcamEnd: null }
}

/** The time of the next jump or gap after index `from` (and after `after`), or a few seconds on. */
function nextCut(trail: readonly TrackPoint[], from: number, after: number): number {
  for (let k = Math.max(1, from); k < trail.length; k++) {
    if (trail[k].t <= after) continue
    if (trail[k].jump || trail[k].t - trail[k - 1].t > GAP_MS) return trail[k].t
  }
  return after + 6000
}
