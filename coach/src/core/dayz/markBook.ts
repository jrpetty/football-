// The shape of the DayZ map marks kept on disk, and the check that a file is
// one. Kept apart from the rest of the marks code so the main process can
// check files without loading the map data.

import type { DayzMapId } from './places.ts'

export const MARK_KIND_IDS = ['player', 'shots', 'danger', 'base', 'stash', 'vehicle', 'loot', 'rally', 'body', 'note', 'trader', 'poi'] as const
const MAP_IDS: readonly DayzMapId[] = ['chernarus', 'livonia', 'sakhal']

export type MarkKind = (typeof MARK_KIND_IDS)[number]

export interface Mark {
  readonly id: string
  readonly kind: MarkKind
  readonly map: DayzMapId
  readonly x: number
  readonly y: number
  /** Short, e.g. "2 players heading north", "Base", "Car (Olga)". */
  readonly label: string
  /** Where it is, in words ("500 m north of Gorka"). */
  readonly where: string
  readonly note?: string
  /** When it was marked (ms). */
  readonly at: number
  /** Players seen. */
  readonly count?: number
  /** Which way they were going. */
  readonly heading?: string
}

export interface MarkBook {
  readonly version: 1
  readonly marks: readonly Mark[]
  /** The player's pace on foot against the estimate, one number per timed trip (newest last). */
  readonly pace?: readonly number[]
}

export const EMPTY_MARKS: MarkBook = { version: 1, marks: [] }

/** A book read from disk: kept only if it is well formed. */
export function isMarkBook(v: unknown): v is MarkBook {
  if (!v || typeof v !== 'object') return false
  const b = v as { version?: unknown; marks?: unknown; pace?: unknown }
  if (b.version !== 1 || !Array.isArray(b.marks) || b.marks.length > 2000) return false
  if (b.pace !== undefined && (!Array.isArray(b.pace) || b.pace.length > 50 || !b.pace.every((x) => typeof x === 'number' && Number.isFinite(x) && x > 0 && x < 5))) return false
  return b.marks.every(isMark)
}

export function isMark(v: unknown): v is Mark {
  if (!v || typeof v !== 'object') return false
  const m = v as Record<string, unknown>
  return (
    typeof m.id === 'string' &&
    m.id.length <= 64 &&
    (MARK_KIND_IDS as readonly string[]).includes(m.kind as string) &&
    MAP_IDS.includes(m.map as DayzMapId) &&
    Number.isFinite(m.x) &&
    Number.isFinite(m.y) &&
    typeof m.label === 'string' &&
    m.label.length <= 120 &&
    typeof m.where === 'string' &&
    m.where.length <= 160 &&
    (m.note === undefined || (typeof m.note === 'string' && m.note.length <= 300)) &&
    Number.isFinite(m.at) &&
    (m.count === undefined || Number.isInteger(m.count)) &&
    (m.heading === undefined || typeof m.heading === 'string')
  )
}

