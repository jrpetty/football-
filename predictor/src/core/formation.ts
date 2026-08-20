// ---------------------------------------------------------------------------
// What shape does this club actually play?
//
// The first version of the line-up picker imposed a generic shape — at least
// three defenders, at most five midfielders — and filled the rest by minutes.
// That produced Liverpool as 3-6-1, which is not a formation anyone has ever
// set up in. The fix is not a better guess: it is to stop guessing and read
// the shape the club has actually been starting.
//
// Every gameweek row carries the position a player was listed in and whether
// he started, so the eleven a club fielded in a given match reconstructs
// exactly. Take the shape they use most often, weighted toward recent matches
// so a change of manager or system shows through within a few weeks.
//
// One caveat that cannot be engineered away: these are Fantasy Premier League
// position labels, not tactical ones. FPL lists several forwards as
// midfielders, so a real 4-3-3 often reads as 4-5-1 here. That is consistent
// within this data — the same labels are used to pick the side and to derive
// the shape — but it is not a tactics board, and the UI says so.
// ---------------------------------------------------------------------------

import { clamp } from './math.ts'

export interface ClubShape {
  def: number
  mid: number
  fwd: number
  /** e.g. "4-4-2". */
  label: string
  /** How many matches this was read from. */
  sample: number
  /** Share of recent matches using this exact shape, 0-1. */
  share: number
}

/** What a club starts when nothing is known about it. */
export const DEFAULT_SHAPE: ClubShape = {
  def: 4, mid: 4, fwd: 2, label: '4-4-2', sample: 0, share: 0,
}

/** A minimal view of a gameweek row — everything this module needs. */
export interface ShapeRow {
  team: string
  fixtureId: number
  season: string
  date: number
  position: 'GK' | 'DEF' | 'MID' | 'FWD'
  starts: number
  minutes: number
}

function isValidShape(def: number, mid: number, fwd: number): boolean {
  // Ten outfielders, and nothing absurd. A row set missing a substitution or
  // two would otherwise produce a nine-man "formation".
  return def + mid + fwd === 10 && def >= 3 && def <= 5 && mid >= 2 && mid <= 6 && fwd >= 0 && fwd <= 3
}

/**
 * Derive each club's usual starting shape.
 *
 * Recent matches are weighted more heavily, so a club that changed system in
 * January is not held to what it did in August.
 */
export function deriveShapes(rows: readonly ShapeRow[], asOf: number, halfLifeDays = 120): Map<string, ClubShape> {
  const DAY = 86400000

  // Reconstruct each club's starting eleven, match by match.
  const perMatch = new Map<string, { team: string; date: number; counts: Record<string, number> }>()
  for (const r of rows) {
    if (r.starts <= 0 || r.date >= asOf) continue
    const key = `${r.season}#${r.fixtureId}#${r.team}`
    let entry = perMatch.get(key)
    if (!entry) {
      entry = { team: r.team, date: r.date, counts: { GK: 0, DEF: 0, MID: 0, FWD: 0 } }
      perMatch.set(key, entry)
    }
    entry.counts[r.position] = (entry.counts[r.position] ?? 0) + 1
  }

  // Tally weighted shape frequencies per club.
  const tally = new Map<string, Map<string, number>>()
  const totals = new Map<string, number>()
  const samples = new Map<string, number>()

  for (const entry of perMatch.values()) {
    const { DEF = 0, MID = 0, FWD = 0 } = entry.counts
    if (!isValidShape(DEF, MID, FWD)) continue
    const weight = Math.pow(0.5, (asOf - entry.date) / DAY / halfLifeDays)
    const label = `${DEF}-${MID}-${FWD}`
    if (!tally.has(entry.team)) tally.set(entry.team, new Map())
    const clubTally = tally.get(entry.team)!
    clubTally.set(label, (clubTally.get(label) ?? 0) + weight)
    totals.set(entry.team, (totals.get(entry.team) ?? 0) + weight)
    samples.set(entry.team, (samples.get(entry.team) ?? 0) + 1)
  }

  const out = new Map<string, ClubShape>()
  for (const [team, clubTally] of tally) {
    let bestLabel = DEFAULT_SHAPE.label
    let bestWeight = 0
    for (const [label, weight] of clubTally) {
      if (weight > bestWeight) {
        bestWeight = weight
        bestLabel = label
      }
    }
    const [def, mid, fwd] = bestLabel.split('-').map(Number) as [number, number, number]
    const total = totals.get(team) ?? 1
    out.set(team, {
      def, mid, fwd,
      label: bestLabel,
      sample: samples.get(team) ?? 0,
      share: clamp(bestWeight / total, 0, 1),
    })
  }
  return out
}

/**
 * The shapes a club has used, most common first.
 *
 * Useful for the UI: "usually 4-3-3, sometimes 3-4-3" is more honest than
 * quoting a single formation as though it were fixed.
 */
export function shapeVariants(
  rows: readonly ShapeRow[],
  team: string,
  asOf: number,
  limit = 3,
  halfLifeDays = 120,
): { label: string; share: number }[] {
  const perMatch = new Map<string, Record<string, number>>()
  const dates = new Map<string, number>()
  for (const r of rows) {
    if (r.team !== team || r.starts <= 0 || r.date >= asOf) continue
    const key = `${r.season}#${r.fixtureId}`
    const counts = perMatch.get(key) ?? { GK: 0, DEF: 0, MID: 0, FWD: 0 }
    counts[r.position] = (counts[r.position] ?? 0) + 1
    perMatch.set(key, counts)
    dates.set(key, r.date)
  }
  const freq = new Map<string, number>()
  let total = 0
  for (const [key, c] of perMatch) {
    const { DEF = 0, MID = 0, FWD = 0 } = c
    if (!isValidShape(DEF, MID, FWD)) continue
    const label = `${DEF}-${MID}-${FWD}`
    // Weight exactly as deriveShapes does, otherwise the headline formation
    // and the variants beneath it can disagree and the panel reads as broken.
    const weight = Math.pow(0.5, (asOf - (dates.get(key) ?? asOf)) / 86400000 / halfLifeDays)
    freq.set(label, (freq.get(label) ?? 0) + weight)
    total += weight
  }
  return [...freq.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, limit)
    .map(([label, n]) => ({ label, share: total > 0 ? n / total : 0 }))
}
