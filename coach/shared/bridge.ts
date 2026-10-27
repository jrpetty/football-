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
import type { CloudResult, CloudSettings, CloudStatus, ResearchBrief } from './cloud.ts'
import type { SynthResult, TtsModelKey, TtsStatus } from './voices.ts'
import type { ScreenCatalog } from '../src/core/vision/screens.ts'
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
  setCloudSettings(settings: Partial<CloudSettings>): Promise<CloudStatus>
  setApiKey(key: string | null): Promise<CloudStatus>
  resetCloudSession(): Promise<void>
  /** Check the saved key with a free call; lists the models it can use. */
  testCloud(): Promise<{ ok: true; models: string[] } | { ok: false; error: string }>
  analyzeFrame(req: { profileId: string; jpeg: ArrayBuffer; context: Record<string, string | number> }): Promise<CloudResult<{ response: VisionResponse }>>
  /** `image`: the current screen (the player asked about it) or a marked moment (`review`). */
  ask(req: { profileId: string; question: string; digest: string; image?: ArrayBuffer; review?: boolean }): Promise<CloudResult<{ text: string }>>
  research(req: { profileId: string; gameTitle: string; focus: string }): Promise<CloudResult<{ brief: ResearchBrief }>>
  loadResearch(profileId: string): Promise<ResearchBrief | null>
  deleteResearch(profileId: string): Promise<void>

  // Game knowledge: screens learned from play, and a researched knowledge base.
  loadScreens(profileId: string): Promise<ScreenCatalog | null>
  saveScreens(catalog: ScreenCatalog): Promise<boolean>
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

  /** The global stop shortcut was pressed. Returns an unsubscribe function. */
  onStopRequested(cb: () => void): () => void
  /** The global push-to-talk shortcut was pressed (toggle). Returns an unsubscribe function. */
  onPushToTalk(cb: () => void): () => void
  /** The global "mark this moment" shortcut was pressed. Returns an unsubscribe function. */
  onMarkMoment(cb: () => void): () => void
}

declare global {
  interface Window {
    sightline?: SightlineBridge
  }
}
