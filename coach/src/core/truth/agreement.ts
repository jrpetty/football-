// How often the screen reading (the cloud model) agrees with a more direct
// source of the same number: the game's own data (Counter-Strike 2's Game
// State Integration) or this computer's reading of the HUD. A reading is
// compared with the other source's value at that moment; moments when the
// other source was changing are skipped, since either could be right.

import type { Observation, ObservationSource } from '../types.ts'

/** Moments within this long of a change in the other source are not compared. */
const CHANGE_MARGIN_MS = 1500
const KEEP = 6

export interface AgreementRow {
  readonly key: string
  /** The direct source compared against: the game's data, or this PC's HUD reading. */
  readonly against: 'game data' | 'this PC'
  readonly agreed: number
  readonly compared: number
  /** The last disagreement, for the player to judge: [screen, other]. */
  readonly lastMiss: readonly [string, string] | null
}

const AGAINST: Partial<Record<ObservationSource, AgreementRow['against']>> = { 'game-api': 'game data', pixel: 'this PC' }

type Change = { value: string; at: number }

function same(a: string | number | boolean, b: string | number | boolean): boolean {
  if (typeof a === 'number' && typeof b === 'number') return Math.abs(a - b) < 1e-6
  return String(a).trim().toLowerCase() === String(b).trim().toLowerCase()
}

export class SourceAgreement {
  private readonly history = new Map<string, Change[]>()
  private readonly rows = new Map<string, { agreed: number; compared: number; lastMiss: [string, string] | null }>()
  private readonly raw = new Map<string, (string | number | boolean)[]>()

  record(o: Observation): void {
    if (o.kind !== 'state' || o.value === undefined) return
    const direct = AGAINST[o.source]
    if (direct) {
      // A direct source: remember its changes.
      const k = `${o.source}|${o.eventType}`
      const list = this.history.get(k) ?? []
      const rawList = this.raw.get(k) ?? []
      if (!list.length || !same(rawList[rawList.length - 1], o.value)) {
        list.push({ value: String(o.value), at: o.timestampMs })
        rawList.push(o.value)
        this.history.set(k, list.slice(-KEEP))
        this.raw.set(k, rawList.slice(-KEEP))
      }
      return
    }
    if (o.source !== 'vision-model') return
    for (const source of Object.keys(AGAINST) as ObservationSource[]) {
      const k = `${source}|${o.eventType}`
      const list = this.history.get(k)
      const rawList = this.raw.get(k)
      if (!list?.length || !rawList) continue
      // The other source's value at the moment of the screen reading, unless it was changing then.
      if (list.some((c) => Math.abs(c.at - o.timestampMs) < CHANGE_MARGIN_MS)) continue
      let i = -1
      for (let j = 0; j < list.length; j++) if (list[j].at <= o.timestampMs) i = j
      if (i < 0) continue
      const rowKey = `${AGAINST[source]}|${o.eventType}`
      const row = this.rows.get(rowKey) ?? { agreed: 0, compared: 0, lastMiss: null }
      row.compared++
      if (same(rawList[i], o.value)) row.agreed++
      else row.lastMiss = [String(o.value), String(rawList[i])]
      this.rows.set(rowKey, row)
    }
  }

  results(): AgreementRow[] {
    return [...this.rows].map(([k, r]) => {
      const [against, key] = k.split('|') as [AgreementRow['against'], string]
      return { key, against, agreed: r.agreed, compared: r.compared, lastMiss: r.lastMiss }
    })
  }
}
