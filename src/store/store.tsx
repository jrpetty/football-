import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useReducer,
  type ReactNode,
} from 'react'
import type {
  AppData, Player, Match, Drill, TrainingSession, Lineup, TeamInfo, VideoClip,
} from '../types'
import { buildSeedData, SCHEMA_VERSION } from '../data/seed'

const STORAGE_KEY = 'gaffer.appdata.v1'

// ---------------------------------------------------------------------------
// Persistence
// ---------------------------------------------------------------------------
function loadData(): AppData {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return buildSeedData()
    const parsed = JSON.parse(raw) as AppData
    if (!parsed || parsed.version !== SCHEMA_VERSION || !Array.isArray(parsed.players)) {
      return buildSeedData()
    }
    // Defensive defaults for fields added after first persistence.
    if (!Array.isArray(parsed.videos)) parsed.videos = []
    if (!Array.isArray(parsed.lineups)) parsed.lineups = []
    if (!Array.isArray(parsed.sessions)) parsed.sessions = []
    return parsed
  } catch {
    return buildSeedData()
  }
}

function saveData(data: AppData) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(data))
  } catch {
    // storage full / unavailable — non-fatal for an in-memory session.
  }
}

// ---------------------------------------------------------------------------
// Reducer
// ---------------------------------------------------------------------------
type Action =
  | { type: 'SET_ALL'; data: AppData }
  | { type: 'RESET' }
  | { type: 'UPDATE_TEAM'; team: Partial<TeamInfo> }
  | { type: 'ADD_PLAYER'; player: Player }
  | { type: 'UPDATE_PLAYER'; player: Player }
  | { type: 'REMOVE_PLAYER'; id: string }
  | { type: 'ADD_MATCH'; match: Match }
  | { type: 'UPDATE_MATCH'; match: Match }
  | { type: 'REMOVE_MATCH'; id: string }
  | { type: 'ADD_DRILL'; drill: Drill }
  | { type: 'UPDATE_DRILL'; drill: Drill }
  | { type: 'REMOVE_DRILL'; id: string }
  | { type: 'ADD_SESSION'; session: TrainingSession }
  | { type: 'UPDATE_SESSION'; session: TrainingSession }
  | { type: 'REMOVE_SESSION'; id: string }
  | { type: 'SAVE_LINEUP'; lineup: Lineup }
  | { type: 'REMOVE_LINEUP'; id: string }
  | { type: 'ADD_VIDEO'; video: VideoClip }
  | { type: 'UPDATE_VIDEO'; video: VideoClip }
  | { type: 'REMOVE_VIDEO'; id: string }

function upsert<T extends { id: string }>(list: T[], item: T): T[] {
  const i = list.findIndex((x) => x.id === item.id)
  if (i === -1) return [...list, item]
  const copy = [...list]
  copy[i] = item
  return copy
}

function reducer(state: AppData, action: Action): AppData {
  switch (action.type) {
    case 'SET_ALL':
      return action.data
    case 'RESET':
      return buildSeedData()
    case 'UPDATE_TEAM':
      return { ...state, team: { ...state.team, ...action.team } }
    case 'ADD_PLAYER':
    case 'UPDATE_PLAYER':
      return { ...state, players: upsert(state.players, action.player) }
    case 'REMOVE_PLAYER':
      return { ...state, players: state.players.filter((p) => p.id !== action.id) }
    case 'ADD_MATCH':
    case 'UPDATE_MATCH':
      return { ...state, matches: upsert(state.matches, action.match) }
    case 'REMOVE_MATCH':
      return { ...state, matches: state.matches.filter((m) => m.id !== action.id) }
    case 'ADD_DRILL':
    case 'UPDATE_DRILL':
      return { ...state, drills: upsert(state.drills, action.drill) }
    case 'REMOVE_DRILL':
      return { ...state, drills: state.drills.filter((d) => d.id !== action.id) }
    case 'ADD_SESSION':
    case 'UPDATE_SESSION':
      return { ...state, sessions: upsert(state.sessions, action.session) }
    case 'REMOVE_SESSION':
      return { ...state, sessions: state.sessions.filter((s) => s.id !== action.id) }
    case 'SAVE_LINEUP':
      return { ...state, lineups: upsert(state.lineups, action.lineup) }
    case 'REMOVE_LINEUP':
      return { ...state, lineups: state.lineups.filter((l) => l.id !== action.id) }
    case 'ADD_VIDEO':
    case 'UPDATE_VIDEO':
      return { ...state, videos: upsert(state.videos, action.video) }
    case 'REMOVE_VIDEO':
      return { ...state, videos: state.videos.filter((v) => v.id !== action.id) }
    default:
      return state
  }
}

// ---------------------------------------------------------------------------
// Context + public actions API
// ---------------------------------------------------------------------------
export interface AppActions {
  resetData(): void
  importData(data: AppData): void
  exportData(): AppData
  updateTeam(team: Partial<TeamInfo>): void
  addPlayer(player: Player): void
  updatePlayer(player: Player): void
  removePlayer(id: string): void
  addMatch(match: Match): void
  updateMatch(match: Match): void
  removeMatch(id: string): void
  addDrill(drill: Drill): void
  updateDrill(drill: Drill): void
  removeDrill(id: string): void
  addSession(session: TrainingSession): void
  updateSession(session: TrainingSession): void
  removeSession(id: string): void
  saveLineup(lineup: Lineup): void
  removeLineup(id: string): void
  addVideo(video: VideoClip): void
  updateVideo(video: VideoClip): void
  removeVideo(id: string): void
}

interface AppContextValue {
  data: AppData
  actions: AppActions
}

const AppContext = createContext<AppContextValue | null>(null)

export function AppProvider({ children }: { children: ReactNode }) {
  const [data, dispatch] = useReducer(reducer, undefined, loadData)

  useEffect(() => {
    saveData(data)
  }, [data])

  const actions = useMemo<AppActions>(
    () => ({
      resetData: () => dispatch({ type: 'RESET' }),
      importData: (d) => dispatch({ type: 'SET_ALL', data: d }),
      exportData: () => data,
      updateTeam: (team) => dispatch({ type: 'UPDATE_TEAM', team }),
      addPlayer: (player) => dispatch({ type: 'ADD_PLAYER', player }),
      updatePlayer: (player) => dispatch({ type: 'UPDATE_PLAYER', player }),
      removePlayer: (id) => dispatch({ type: 'REMOVE_PLAYER', id }),
      addMatch: (match) => dispatch({ type: 'ADD_MATCH', match }),
      updateMatch: (match) => dispatch({ type: 'UPDATE_MATCH', match }),
      removeMatch: (id) => dispatch({ type: 'REMOVE_MATCH', id }),
      addDrill: (drill) => dispatch({ type: 'ADD_DRILL', drill }),
      updateDrill: (drill) => dispatch({ type: 'UPDATE_DRILL', drill }),
      removeDrill: (id) => dispatch({ type: 'REMOVE_DRILL', id }),
      addSession: (session) => dispatch({ type: 'ADD_SESSION', session }),
      updateSession: (session) => dispatch({ type: 'UPDATE_SESSION', session }),
      removeSession: (id) => dispatch({ type: 'REMOVE_SESSION', id }),
      saveLineup: (lineup) => dispatch({ type: 'SAVE_LINEUP', lineup }),
      removeLineup: (id) => dispatch({ type: 'REMOVE_LINEUP', id }),
      addVideo: (video) => dispatch({ type: 'ADD_VIDEO', video }),
      updateVideo: (video) => dispatch({ type: 'UPDATE_VIDEO', video }),
      removeVideo: (id) => dispatch({ type: 'REMOVE_VIDEO', id }),
    }),
    [data],
  )

  const value = useMemo(() => ({ data, actions }), [data, actions])
  return <AppContext.Provider value={value}>{children}</AppContext.Provider>
}

// ---------------------------------------------------------------------------
// Hooks
// ---------------------------------------------------------------------------
export function useApp(): AppContextValue {
  const ctx = useContext(AppContext)
  if (!ctx) throw new Error('useApp must be used within <AppProvider>')
  return ctx
}

export function useData(): AppData {
  return useApp().data
}

export function useActions(): AppActions {
  return useApp().actions
}

export function usePlayer(id: string | undefined): Player | undefined {
  const { data } = useApp()
  return data.players.find((p) => p.id === id)
}

export function useMatch(id: string | undefined): Match | undefined {
  const { data } = useApp()
  return data.matches.find((m) => m.id === id)
}
