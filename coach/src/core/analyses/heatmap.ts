// Zone heatmaps: where visible events happen (goals conceded, lost balls,
// deaths). Zones are approximate by nature, so a spatial claim is only made
// when enough located events exist and one zone stands clearly above an even
// spread. Unlocated events are counted but never assigned a zone.

import { wilson } from '../evidence.ts'
import type { HeatmapSpec, ZoneSet } from '../profile/schema.ts'
import type { EvidenceGrade, GameEvent } from '../types.ts'

export interface HeatmapClaim {
  readonly grade: Exclude<EvidenceGrade, 'insufficient'>
  readonly cell: string
  readonly cellLabel: string
  readonly count: number
  readonly located: number
}

export interface HeatmapResult {
  readonly spec: HeatmapSpec
  readonly zoneSet: ZoneSet
  readonly total: number
  readonly located: number
  readonly cells: Readonly<Record<string, number>>
  readonly breakdown: Readonly<Record<string, number>>
  readonly claim: HeatmapClaim | null
  readonly topBreakdown: { readonly value: string; readonly count: number } | null
}

interface Recorded {
  readonly heatmap: string
  zone: string | undefined
  attributes: Readonly<Record<string, string | number | boolean>>
  readonly match: number
}

export class HeatmapAnalyzer {
  private readonly specs: readonly HeatmapSpec[]
  private readonly zoneSets: ReadonlyMap<string, ZoneSet>
  private readonly events = new Map<string, Recorded>()
  private match = 0

  constructor(specs: readonly HeatmapSpec[], zoneSets: readonly ZoneSet[]) {
    this.specs = specs
    this.zoneSets = new Map(zoneSets.map((z) => [z.id, z]))
  }

  /** Returns the heatmap ids the event was counted in. */
  record(ev: GameEvent): string[] {
    const hit: string[] = []
    for (const spec of this.specs) {
      if (spec.eventType !== ev.ruleId) continue
      const cells = this.zoneSets.get(spec.zoneSet)?.cells ?? []
      const zone = ev.zone && cells.some((c) => c.id === ev.zone) ? ev.zone : undefined
      this.events.set(`${spec.id}:${ev.uid}`, { heatmap: spec.id, zone, attributes: ev.attributes ?? {}, match: this.match })
      hit.push(spec.id)
    }
    return hit
  }

  /** Add a zone/attributes learned later (e.g. a replay seen after the scoreboard changed). */
  amend(uid: string, zone: string | undefined, attributes: Readonly<Record<string, string | number | boolean>>): void {
    for (const spec of this.specs) {
      const r = this.events.get(`${spec.id}:${uid}`)
      if (!r) continue
      const cells = this.zoneSets.get(spec.zoneSet)?.cells ?? []
      if (zone && cells.some((c) => c.id === zone)) r.zone = zone
      r.attributes = { ...r.attributes, ...attributes }
    }
  }

  newMatch(): void {
    this.match++
  }

  results(scope: 'session' | 'match' = 'session'): HeatmapResult[] {
    return this.specs.map((spec) => this.result(spec, scope))
  }

  result(spec: HeatmapSpec, scope: 'session' | 'match' = 'session'): HeatmapResult {
    const zoneSet = this.zoneSets.get(spec.zoneSet)!
    const cells: Record<string, number> = Object.fromEntries(zoneSet.cells.map((c) => [c.id, 0]))
    const breakdown: Record<string, number> = {}
    let total = 0
    let located = 0
    for (const r of this.events.values()) {
      if (r.heatmap !== spec.id || (scope === 'match' && r.match !== this.match)) continue
      total++
      if (r.zone) {
        cells[r.zone]++
        located++
      }
      if (spec.breakdown) {
        const v = r.attributes[spec.breakdown.attribute]
        if (v !== undefined && v !== 'unknown') breakdown[String(v)] = (breakdown[String(v)] ?? 0) + 1
      }
    }
    const top = Object.entries(cells).sort((a, b) => b[1] - a[1])[0]
    const claim = top ? grade(spec, zoneSet, top[0], top[1], located) : null
    const tb = Object.entries(breakdown).sort((a, b) => b[1] - a[1])[0]
    return {
      spec,
      zoneSet,
      total,
      located,
      cells,
      breakdown,
      claim,
      topBreakdown: tb && tb[1] >= 2 ? { value: tb[0], count: tb[1] } : null,
    }
  }
}

/**
 * Grade a "most of them came from X" claim. Strong and moderate need several
 * events in the top cell and its Wilson lower bound to clear the even-spread
 * share by a margin; two events in one zone is only ever an early sign.
 */
function grade(spec: HeatmapSpec, zs: ZoneSet, cell: string, count: number, located: number): HeatmapClaim | null {
  if (located < spec.minEvents || count < 2) return null
  const share = count / located
  if (share < 0.4) return null
  const even = 1 / zs.cells.length
  const label = zs.cells.find((c) => c.id === cell)?.label ?? cell
  const base = { cell, cellLabel: label, count, located }
  if (count >= 4 && located >= spec.minEvents * 2 && wilson(count, located, 1.645).lo > even * 2) return { grade: 'strong', ...base }
  if (count >= 3 && wilson(count, located, 1.2816).lo > even * 1.5) return { grade: 'moderate', ...base }
  return { grade: 'weak', ...base }
}
