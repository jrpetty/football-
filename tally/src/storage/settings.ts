// ---------------------------------------------------------------------------
// Settings.
//
// localStorage rather than the database: these are small, they are read on
// every render, and losing them costs a re-typed API key rather than a night's
// takings. Every access is wrapped, because in a private window localStorage
// exists and throws on use.
// ---------------------------------------------------------------------------

import { DEFAULT_TOLERANCE_PENCE } from '../core/reconcile.ts'

const PREFIX = 'tally.'

/** Which reader to try first. 'off' means she types every figure herself. */
export type EnginePreference = 'vision' | 'device' | 'off'

export interface Settings {
  apiKey: string
  model: string
  engine: EnginePreference
  /** Slack allowed before a night is called short or over. */
  tolerancePence: number
  /** Keep the photographs alongside the figures, as an audit trail. */
  keepPhotos: boolean
  /** Hours the week is meant to come to. 0 means no target is set. */
  weeklyHoursTarget: number
  /** The float usually left in the drawer, prefilled each night. 0 for none. */
  standingFloatPence: number
  /** Where the pub is, for the weather. Empty name means none set. */
  place: { name: string; latitude: number; longitude: number }
}

export const AI_MODELS = [
  { id: 'claude-opus-5', label: 'Claude Opus 5', hint: 'Best on a bad photograph' },
  { id: 'claude-sonnet-5', label: 'Claude Sonnet 5', hint: 'Balanced — a good default' },
  { id: 'claude-haiku-4-5', label: 'Claude Haiku 4.5', hint: 'Fastest and cheapest' },
] as const

export const DEFAULT_SETTINGS: Settings = {
  apiKey: '',
  model: 'claude-sonnet-5',
  engine: 'vision',
  tolerancePence: DEFAULT_TOLERANCE_PENCE,
  weeklyHoursTarget: 0,
  standingFloatPence: 0,
  place: { name: '', latitude: 0, longitude: 0 },
  keepPhotos: true,
}

function read(key: string): string | null {
  try {
    return localStorage.getItem(PREFIX + key)
  } catch {
    return null
  }
}

function write(key: string, value: string): void {
  try {
    localStorage.setItem(PREFIX + key, value)
  } catch {
    /* private browsing, or the quota is full — the app still works */
  }
}

/**
 * Read the stored tolerance, falling back to the default.
 *
 * Separated out and exported because getting it wrong is silent and costly:
 * `Number(null)` is 0, not NaN, so a naive read turns "never set" into "warn
 * about every single penny" — which is precisely the behaviour the tolerance
 * exists to prevent, arriving by default on every fresh install.
 */
export function parseTolerance(raw: string | null): number {
  if (raw === null || raw.trim() === '') return DEFAULT_SETTINGS.tolerancePence
  const n = Number(raw)
  if (!Number.isFinite(n) || n < 0) return DEFAULT_SETTINGS.tolerancePence
  return Math.round(n)
}

/** A weekly hours target. Zero, or absent, means no target at all. */
function parseHours(raw: string | null): number {
  if (raw === null) return DEFAULT_SETTINGS.weeklyHoursTarget
  const n = Number(raw)
  if (!Number.isFinite(n) || n < 0 || n > 1000) return DEFAULT_SETTINGS.weeklyHoursTarget
  return Math.round(n * 10) / 10
}

/** The stored location. Anything malformed reads as "not set". */
function parsePlace(raw: string | null): Settings['place'] {
  if (!raw) return DEFAULT_SETTINGS.place
  try {
    const p = JSON.parse(raw) as Record<string, unknown>
    const name = typeof p.name === 'string' ? p.name : ''
    const latitude = typeof p.latitude === 'number' ? p.latitude : NaN
    const longitude = typeof p.longitude === 'number' ? p.longitude : NaN
    if (!name || !Number.isFinite(latitude) || !Number.isFinite(longitude)) return DEFAULT_SETTINGS.place
    return { name, latitude, longitude }
  } catch {
    return DEFAULT_SETTINGS.place
  }
}

/**
 * The standing float. Its own parser rather than the tolerance one, whose
 * fallback is five pence — which would put a phantom 5p float on every night.
 */
function parseFloat_(raw: string | null): number {
  if (raw === null) return DEFAULT_SETTINGS.standingFloatPence
  const n = Number(raw)
  if (!Number.isFinite(n) || n < 0) return DEFAULT_SETTINGS.standingFloatPence
  return Math.round(n)
}

export function loadSettings(): Settings {
  const engine = read('engine')
  return {
    apiKey: read('apiKey') ?? DEFAULT_SETTINGS.apiKey,
    model: read('model') ?? DEFAULT_SETTINGS.model,
    engine:
      engine === 'vision' || engine === 'device' || engine === 'off'
        ? engine
        : DEFAULT_SETTINGS.engine,
    tolerancePence: parseTolerance(read('tolerance')),
    keepPhotos: (read('keepPhotos') ?? 'true') === 'true',
    weeklyHoursTarget: parseHours(read('weeklyHoursTarget')),
    place: parsePlace(read('place')),
    standingFloatPence: parseFloat_(read('standingFloat')),
  }
}

export function saveSettings(s: Settings): void {
  write('apiKey', s.apiKey.trim())
  write('model', s.model)
  write('engine', s.engine)
  write('tolerance', String(s.tolerancePence))
  write('keepPhotos', String(s.keepPhotos))
  write('weeklyHoursTarget', String(s.weeklyHoursTarget))
  write('place', JSON.stringify(s.place))
  write('standingFloat', String(s.standingFloatPence))
}

/**
 * Whether a model accepts the effort control.
 *
 * Transcribing a receipt is not a reasoning problem — it is a copying problem —
 * so the models that take an effort setting are told to spend little on it.
 * That cuts both the bill and the wait. Haiku has no effort control and errors
 * if given one, so it is left alone.
 */
export function supportsEffort(model: string): boolean {
  return model === 'claude-opus-5' || model === 'claude-sonnet-5'
}

export function hasApiKey(s: Settings): boolean {
  return s.apiKey.trim().length > 10
}

/**
 * The engine that will actually run, which is not always the one preferred:
 * Claude cannot run without a key, and nothing runs without a connection.
 */
export function effectiveEngine(s: Settings, online = navigator.onLine): EnginePreference {
  if (s.engine === 'off') return 'off'
  if (s.engine === 'vision') {
    if (!hasApiKey(s)) return 'off'
    if (!online) return 'off'
    return 'vision'
  }
  return 'device'
}


/**
 * The settings a backup carries — everything except the key.
 *
 * A backup gets emailed, and an API key sitting in an inbox is a key in the
 * wrong place. Re-typing it on a new copy is a few seconds; the alternative is
 * a secret with a life of its own.
 */
export function settingsForBackup(): Record<string, unknown> {
  const { apiKey: _key, ...rest } = loadSettings()
  return rest
}

/** Put backed-up settings back, keeping whatever key is already on this device. */
export function restoreSettings(stored: Record<string, unknown> | null): void {
  if (!stored) return
  const current = loadSettings()
  saveSettings({
    ...current,
    ...(stored as Partial<Settings>),
    // Never taken from a file, since a backup never carries one.
    apiKey: current.apiKey,
  })
}
