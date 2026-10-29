// The contract between the renderer and its host shell. In Electron the
// preload script exposes it as `window.sightline`; in a plain browser (dev
// preview) the renderer supplies a local-storage implementation without cloud
// features. Nothing in this interface can touch a game process or input
// devices; network access exists only through the cloud calls, which run in
// the main process with the player's key and only when the player enabled them.

import type { MatchRecord } from '../src/core/analyses/matchRecorder.ts'
import type { PatternSnapshot, PatternVerdict } from '../src/core/patterns.ts'
import type { CoachSettings } from '../src/core/settings.ts'
import type { VisionResponse } from '../src/core/vision/contract.ts'
import type { MatchReview } from '../src/core/review.ts'
import type { CloudResult, CloudSettings, CloudStatus, ResearchBrief } from './cloud.ts'
import type { UsageSummary } from '../src/core/usage.ts'
import type { SynthResult, TtsModelKey, TtsStatus } from './voices.ts'
import type { ScreenCatalog } from '../src/core/vision/screens.ts'
import type { StoredMosaic } from '../src/core/map/minimap.ts'
import type { GlyphBookData } from '../src/core/hud/glyphs.ts'
import type { SummaryPlayer } from '../src/core/truth/aoe4world.ts'

/** An official overhead map image kept for a map. */
export interface OverheadInfo {
  readonly mapId: string
  readonly bytes: number
  readonly savedAt: string
}

export type OverheadFetch =
  | { readonly ok: true; readonly page: string; readonly candidates: readonly { readonly url: string; readonly bytes: Uint8Array }[] }
  | { readonly ok: false; readonly error: string }
import type { KnowledgeItem, KnowledgeSectionId } from '../src/core/knowledge/base.ts'

export interface KnowledgeStatus {
  readonly building: boolean
  readonly stage: string | null
  readonly error: string | null
  readonly knowledge: {
    readonly gameVersion: string
    readonly createdAt: string
    readonly model: string
    readonly items: number
    readonly sections: number
    readonly sources: number
    /** Size on disk (compressed). */
    readonly bytes: number
    readonly costUsd: number | null
    readonly sectionsList: readonly { readonly id: KnowledgeSectionId; readonly items: readonly KnowledgeItem[] }[]
  } | null
  /** Screens (menus, loading, scoreboards…) learned for this game. */
  readonly screens: number
}

export interface CaptureSource {
  readonly id: string
  readonly name: string
  readonly kind: 'screen' | 'window'
  /** A small preview image (data URL), shown in the picker only, never stored. */
  readonly thumbnail: string
}

export interface PlatformInfo {
  readonly shell: 'electron' | 'browser'
  readonly platform: string
  readonly appVersion: string
  /** True when the OS provides its own capture picker (macOS 15+ under Electron, or any browser). */
  readonly systemPicker: boolean
  /** The global "stop capture" shortcut, if one is registered. */
  readonly stopShortcut: string | null
  /** The global push-to-talk shortcut, if one is registered. */
  readonly talkShortcut: string | null
  /** The global "mark this moment" shortcut, if one is registered. */
  readonly markShortcut: string | null
  /** Keys the OS refused (another program owns them). */
  readonly shortcutErrors: readonly string[]
}

export interface PlayerNotes {
  readonly goals: string
  readonly notes: string
  readonly updatedAt: string
}

export interface VoiceModelStatus {
  readonly installed: boolean
  readonly installing: boolean
  /** 0..1 while downloading. */
  readonly progress: number
  readonly error: string | null
  readonly sizeMb: number
}

/** Counter-Strike 2 Game State Integration: the local listener's state. */
export interface GsiServerStatus {
  /** False in the browser preview (the desktop app only). */
  readonly available: boolean
  readonly enabled: boolean
  readonly listening: boolean
  readonly port: number
  /** When the last update came (epoch ms). */
  readonly lastAt: number | null
  readonly updates: number
  readonly error: string | null
}

export interface SightlineBridge {
  info(): Promise<PlatformInfo>
  /** Windows/screens the player may choose from (Electron without a system picker only). */
  listSources(): Promise<CaptureSource[]>
  /** Record the player's explicit choice; it authorises exactly one capture request, briefly. */
  chooseSource(id: string): Promise<void>
  /** Tell the shell whether capture is active, for its title indicator. */
  setCaptureState(active: boolean, label: string | null): void

  loadSettings(): Promise<unknown>
  saveSettings(settings: CoachSettings): Promise<CoachSettings>

  // Long-term memory: the shell refuses to save unless the player opted in.
  loadTendencies(profileId: string): Promise<PatternSnapshot | null>
  saveTendencies(snapshot: PatternSnapshot): Promise<boolean>
  setPatternVerdict(profileId: string, episodeId: string, verdict: PatternVerdict | null): Promise<PatternSnapshot | null>
  deletePattern(profileId: string, episodeId: string): Promise<PatternSnapshot | null>
  loadMatches(profileId: string): Promise<MatchRecord[]>
  saveMatch(record: MatchRecord): Promise<boolean>
  deleteMatch(profileId: string, id: string): Promise<void>
  loadNotes(profileId: string): Promise<PlayerNotes | null>
  saveNotes(profileId: string, notes: PlayerNotes): Promise<boolean>
  forgetPlayerData(profileId: string | null): Promise<void>
  exportPlayerData(): Promise<string>
  storageInfo(): Promise<{ readonly encrypted: boolean }>

  /** Raw user-installed pack JSON; the renderer validates it. */
  listUserProfiles(): Promise<unknown[]>
  openProfilesFolder(): Promise<void>

  // Cloud features (desktop app only, off by default).
  cloudStatus(): Promise<CloudStatus>
  /** What the cloud has cost: today, this month, all time, by feature, model and game (numbers only). */
  usageSummary(): Promise<UsageSummary | null>
  /** Clears the usage history (not the bill). */
  forgetUsage(): Promise<void>
  setCloudSettings(settings: Partial<CloudSettings>): Promise<CloudStatus>
  setApiKey(key: string | null): Promise<CloudStatus>
  resetCloudSession(): Promise<void>
  /** Check the saved key with a free call; lists the models it can use. */
  testCloud(): Promise<{ ok: true; models: string[] } | { ok: false; error: string }>
  analyzeFrame(req: { profileId: string; jpeg: ArrayBuffer; context: Record<string, string | number> }): Promise<CloudResult<{ response: VisionResponse }>>
  /** `image`: the current screen (the player asked about it) or a marked moment (`review`). */
  ask(req: { profileId: string; question: string; digest: string; image?: ArrayBuffer; review?: boolean }): Promise<CloudResult<{ text: string }>>
  /** A coach's review of the session (Claude), validated in the main process. */
  reviewMatch(req: { profileId: string; digest: string }): Promise<CloudResult<{ review: MatchReview }>>
  research(req: { profileId: string; gameTitle: string; focus: string }): Promise<CloudResult<{ brief: ResearchBrief }>>
  loadResearch(profileId: string): Promise<ResearchBrief | null>
  deleteResearch(profileId: string): Promise<void>

  // Game knowledge: screens learned from play, and a researched knowledge base.
  loadScreens(profileId: string): Promise<ScreenCatalog | null>
  saveScreens(catalog: ScreenCatalog): Promise<boolean>
  /** The HUD digit shapes learned on this player's screen (Read numbers on this PC). */
  loadHudBook(profileId: string): Promise<GlyphBookData | null>
  saveHudBook(profileId: string, book: GlyphBookData): Promise<boolean>
  /** After a match, when the player asks: AoE4World's summary of the game that ended closest to it. */
  aoe4worldSummary(profileId: number, endedAt: string, durationSec: number): Promise<{ ok: true; gameId: number; player: SummaryPlayer } | { ok: false; error: string }>
  /** Find one's AoE4World profile by in-game name. */
  aoe4worldSearch(query: string): Promise<{ ok: true; players: { profileId: number; name: string; country: string | null }[] } | { ok: false; error: string }>
  /** Maps stitched from the player's minimap, for one game (images of the map only, no frames). */
  loadMosaics(profileId: string): Promise<StoredMosaic[]>
  saveMosaic(profileId: string, mosaic: StoredMosaic): Promise<boolean>
  deleteMosaic(profileId: string, map: string): Promise<void>
  /**
   * Official overhead maps (the game's own tactical map images), kept per map on this computer
   * once the player imports or downloads them; never bundled with the app.
   */
  listOverheads(profileId: string): Promise<OverheadInfo[]>
  loadOverhead(profileId: string, mapId: string): Promise<Uint8Array | null>
  /** A PNG (the renderer re-encodes whatever was imported). */
  saveOverhead(profileId: string, mapId: string, png: Uint8Array): Promise<boolean>
  deleteOverhead(profileId: string, mapId: string): Promise<void>
  /** Fetches the map's official guide page (from the pack) and the images on it that may be its overhead map. */
  fetchOverhead(profileId: string, mapId: string): Promise<OverheadFetch>
  /** Opens the map's official guide page in the browser. */
  openOverheadPage(profileId: string, mapId: string): Promise<boolean>
  knowledgeStatus(profileId: string): Promise<KnowledgeStatus>
  /** Starts building in the background; poll knowledgeStatus for progress. */
  buildKnowledge(req: { profileId: string; focus: string }): Promise<KnowledgeStatus>
  deleteKnowledge(profileId: string): Promise<KnowledgeStatus>

  // Voice input (opt-in): local speech recognition; the model is downloaded once on request.
  voiceStatus(): Promise<VoiceModelStatus>
  installVoiceModel(): Promise<VoiceModelStatus>
  removeVoiceModel(): Promise<VoiceModelStatus>
  /** 16 kHz mono PCM in [-1, 1]; returns the recognised text. */
  transcribe(pcm: Float32Array): Promise<string>

  // Neural voices (local): downloaded once on request, then synthesised on this computer.
  ttsStatus(): Promise<TtsStatus>
  installTts(model: TtsModelKey): Promise<TtsStatus>
  removeTts(model: TtsModelKey): Promise<TtsStatus>
  synthesize(req: { voice: string; text: string; speed: number }): Promise<SynthResult>

  // Counter-Strike 2 Game State Integration: a listener on this computer only (127.0.0.1), off until turned on.
  gsiStatus(): Promise<GsiServerStatus>
  gsiConfigure(cfg: { enabled: boolean; port: number }): Promise<GsiServerStatus>
  /** The cfg file's text (with this computer's token), and the game's cfg folder when found. */
  gsiCfg(): Promise<{ text: string; fileName: string; folder: string | null }>
  /** Saves the cfg file where the player picks (the game's cfg folder when found). */
  gsiSaveCfg(): Promise<{ ok: boolean; path?: string; error?: string }>
  /** An update from the game. Returns an unsubscribe function. */
  onGameState(cb: (payload: unknown) => void): () => void

  /** The global stop shortcut was pressed. Returns an unsubscribe function. */
  onStopRequested(cb: () => void): () => void
  /**
   * The global talk key: "start" on a press, "stop" on the next tap or when a held key is let go
   * (src/speech/talkKey.ts). Returns an unsubscribe function.
   */
  onPushToTalk(cb: (action: 'start' | 'stop') => void): () => void
  /** Recording ended without the talk key (time limit, the on-screen button), so its next press starts again. */
  talkEnded(): void
  /** The global "mark this moment" shortcut was pressed. Returns an unsubscribe function. */
  onMarkMoment(cb: () => void): () => void
}

declare global {
  interface Window {
    sightline?: SightlineBridge
  }
}
