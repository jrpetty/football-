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
  }
}

export function saveSettings(s: Settings): void {
  write('apiKey', s.apiKey.trim())
  write('model', s.model)
  write('engine', s.engine)
  write('tolerance', String(s.tolerancePence))
  write('keepPhotos', String(s.keepPhotos))
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
