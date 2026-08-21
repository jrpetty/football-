// ---------------------------------------------------------------------------
// Loading the committed artifacts.
//
// The site is static: the weekly pipeline writes JSON into public/data and the
// browser reads it. There is no API and no client-side model fitting, so a
// gameweek page is a single fetch and the whole thing works offline once
// cached.
// ---------------------------------------------------------------------------

import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import type {
  SeasonArtifact,
  GameweekArtifact,
  PlayersArtifact,
  AccuracyArtifact,
  SquadsArtifact,
  TransfersArtifact,
} from '../core/schema.ts'

// Vite rewrites BASE_URL at build time; with a relative base this resolves
// correctly whether the app is served from the root or from /predictor/.
const BASE = import.meta.env.BASE_URL

async function loadJson<T>(path: string): Promise<T> {
  // `no-cache` forces a revalidation rather than a blind cache hit. These
  // files are rewritten by the weekly job at the same URLs, so a stale copy
  // would quietly show last week's predictions as though they were current —
  // the one failure this site cannot afford.
  const res = await fetch(`${BASE}data/${path}`, { cache: 'no-cache' })
  if (!res.ok) throw new Error(`Could not load ${path} (HTTP ${res.status})`)
  return (await res.json()) as T
}

interface SeasonState {
  season: SeasonArtifact | null
  accuracy: AccuracyArtifact | null
  error: string | null
  loading: boolean
}

const SeasonContext = createContext<SeasonState>({
  season: null,
  accuracy: null,
  error: null,
  loading: true,
})

export function SeasonProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<SeasonState>({
    season: null,
    accuracy: null,
    error: null,
    loading: true,
  })

  useEffect(() => {
    let cancelled = false
    Promise.all([loadJson<SeasonArtifact>('season.json'), loadJson<AccuracyArtifact>('accuracy.json')])
      .then(([season, accuracy]) => {
        if (!cancelled) setState({ season, accuracy, error: null, loading: false })
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setState({
            season: null,
            accuracy: null,
            error: err instanceof Error ? err.message : String(err),
            loading: false,
          })
        }
      })
    return () => {
      cancelled = true
    }
  }, [])

  return <SeasonContext.Provider value={state}>{children}</SeasonContext.Provider>
}

export function useSeason(): SeasonState {
  return useContext(SeasonContext)
}

/** Load one gameweek's predictions, with a tiny in-memory cache. */
const gameweekCache = new Map<number, GameweekArtifact>()

export function useGameweek(gw: number | null): {
  data: GameweekArtifact | null
  loading: boolean
  error: string | null
} {
  const [data, setData] = useState<GameweekArtifact | null>(
    gw !== null ? (gameweekCache.get(gw) ?? null) : null,
  )
  const [loading, setLoading] = useState(gw !== null && !gameweekCache.has(gw))
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (gw === null) return
    const cached = gameweekCache.get(gw)
    if (cached) {
      setData(cached)
      setLoading(false)
      setError(null)
      return
    }
    let cancelled = false
    setLoading(true)
    setError(null)
    loadJson<GameweekArtifact>(`predictions/gw${gw}.json`)
      .then((json) => {
        gameweekCache.set(gw, json)
        if (!cancelled) {
          setData(json)
          setLoading(false)
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : String(err))
          setLoading(false)
        }
      })
    return () => {
      cancelled = true
    }
  }, [gw])

  return { data, loading, error }
}

let squadsPromise: Promise<SquadsArtifact> | null = null

/** Squad rates, loaded once and shared — every fixture on a page needs them. */
export function useSquads(): { data: SquadsArtifact | null; loading: boolean } {
  const [data, setData] = useState<SquadsArtifact | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    squadsPromise ??= loadJson<SquadsArtifact>('squads.json')
    squadsPromise
      .then((json) => {
        if (!cancelled) {
          setData(json)
          setLoading(false)
        }
      })
      .catch(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  return { data, loading }
}

let transfersPromise: Promise<TransfersArtifact> | null = null

/** Squad movement detected between pipeline runs. */
export function useTransfers(): { data: TransfersArtifact | null; loading: boolean } {
  const [data, setData] = useState<TransfersArtifact | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    transfersPromise ??= loadJson<TransfersArtifact>('transfers.json')
    transfersPromise
      .then((json) => {
        if (!cancelled) {
          setData(json)
          setLoading(false)
        }
      })
      .catch(() => {
        // The ledger is absent until the pipeline has run twice; that is not
        // an error worth surfacing.
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  return { data, loading }
}

let playersPromise: Promise<PlayersArtifact> | null = null

export function usePlayers(): { data: PlayersArtifact | null; loading: boolean } {
  const [data, setData] = useState<PlayersArtifact | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    playersPromise ??= loadJson<PlayersArtifact>('players.json')
    playersPromise
      .then((json) => {
        if (!cancelled) {
          setData(json)
          setLoading(false)
        }
      })
      .catch(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  return { data, loading }
}
