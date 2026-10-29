// Messages between the renderer and the analysis worker. Only structured
// results ever leave the worker, plus — when the player switched cloud vision
// on — one downscaled JPEG per sampling interval, which goes to the main
// process and nowhere else.

import type { EngineStatus, InsightView, SessionReport } from '../core/engine.ts'
import type { MatchRecord } from '../core/analyses/matchRecorder.ts'
import type { PatternSnapshot } from '../core/patterns.ts'
import type { ResolvedPolicy } from '../core/policy/registry.ts'
import type { GameProfile } from '../core/profile/schema.ts'
import type { QueueStats } from '../core/queue.ts'
import type { CoachSettings } from '../core/settings.ts'
import type { Frame, Observation, RoundResult, TimelineEntry, Utterance } from '../core/types.ts'
import type { SamplerStats } from '../core/vision/sampler.ts'
import type { ScreenCatalog, ScreenInfo } from '../core/vision/screens.ts'
import type { StoredMosaic } from '../core/map/minimap.ts'
import type { HudField, HudStatus } from '../core/hud/reader.ts'
import type { GlyphBookData } from '../core/hud/glyphs.ts'

import type { BuildOrder } from '../core/aoe4/buildOrder.ts'
export interface VisionConfig {
  readonly enabled: boolean
  readonly intervalMs: number
  readonly maxInFlight?: number
  /** Width sampled frames are scaled down to (the pack's value when absent). */
  readonly maxWidth?: number
}

export type ToWorker =
  | {
      readonly type: 'start'
      readonly profile: GameProfile
      readonly settings: CoachSettings
      readonly policy: ResolvedPolicy
      readonly history: PatternSnapshot | null
      readonly matchHistory: readonly MatchRecord[]
      readonly context: Readonly<Record<string, string | number>>
      readonly vision: VisionConfig
      /** Screens learned in earlier sessions (menus etc.), recognised locally without a request. */
      readonly screens: ScreenCatalog | null
      /** Maps stitched from the minimap in earlier sessions. */
      readonly mosaics?: readonly StoredMosaic[]
      /** HUD numbers marked by the player, and the digit shapes learned in earlier sessions. */
      readonly hud?: { readonly fields: readonly HudField[]; readonly book: GlyphBookData | null; readonly saves: boolean } | null
      /** The build order to call during play (the build trainer), and the practice focus to say at the start. */
      readonly training?: { readonly build: BuildOrder | null; readonly practice: string | null } | null
      readonly now: number
    }
  /** Encode the latest frame (whole, downscaled) for a question about the screen or a marked moment. */
  | { readonly type: 'snapshot'; readonly requestId: number; readonly maxWidth: number }
  /** A captured video frame. Transferred, then closed by the worker. */
  | { readonly type: 'bitmap'; readonly bitmap: ImageBitmap; readonly timestamp: number }
  /** An already-decoded frame (the pixel simulator). Its buffer is transferred. */
  | { readonly type: 'pixels'; readonly frame: Frame }
  /** Observations from a synthetic vision replay (demo/tests). */
  | { readonly type: 'observations'; readonly observations: Observation[]; readonly now: number }
  /** An update from the game's own data feed (Counter-Strike 2 Game State Integration), at `now` on the capture clock. */
  | { readonly type: 'game-state'; readonly payload: unknown; readonly now: number }
  /** The main process's answer for a frame the worker sampled. */
  | {
      readonly type: 'vision-result'
      readonly frameAt: number
      readonly arrivedAt: number
      readonly response: unknown
      readonly error: string | null
      readonly retryAfterMs?: number
      readonly fatal?: boolean
    }
  | { readonly type: 'vision-config'; readonly vision: VisionConfig; readonly now: number }
  | {
      readonly type: 'settings'
      readonly settings: CoachSettings
      readonly policy: ResolvedPolicy
      readonly context: Readonly<Record<string, string | number>>
      readonly now: number
    }
  | { readonly type: 'capture-problem'; readonly reason: string | null; readonly now: number }
  /** An answer to the player's question, to be delivered under the same policy as any message. */
  | { readonly type: 'answer'; readonly question: string; readonly text: string; readonly author: 'template' | 'ai'; readonly now: number }
  | { readonly type: 'boundary'; readonly kind: 'round-end' | 'match-end'; readonly result: RoundResult; readonly now: number }
  | { readonly type: 'stop'; readonly now: number }
  /**
   * The official overhead map of a map (null: none), as RGBA (transferred): the stitched minimap
   * is placed on it from the next frame.
   */
  | { readonly type: 'official'; readonly map: string; readonly image: { readonly data: Uint8ClampedArray; readonly width: number; readonly height: number } | null }

export interface VisionStatus {
  readonly enabled: boolean
  readonly sampler: SamplerStats
  readonly lastError: string | null
  readonly accepted: number
  readonly dropped: number
  /** Mean recognition latency (frame capture → answer), ms. */
  readonly latencyMs: number
  /** What screen is showing, as last recognised (from memory or by the model). */
  readonly screen: (ScreenInfo & { readonly from: 'memory' | 'model'; readonly at: number }) | null
  readonly knownScreens: number
  /** HUD numbers read on this computer, when the player marked any. */
  readonly hud: (HudStatus & { readonly saving: boolean }) | null
}

export interface WorkerStatus extends EngineStatus {
  readonly queue: QueueStats
  /** Mean analysis time per frame over the last few frames, ms. */
  readonly analysisMs: number
  readonly vision: VisionStatus
}

export type FromWorker =
  | {
      readonly type: 'output'
      readonly entries: TimelineEntry[]
      readonly utterances: Utterance[]
      readonly cancel: string[]
      readonly matches: MatchRecord[]
      readonly status: WorkerStatus | null
      /** Present when the analyses may have changed. */
      readonly report: SessionReport | null
      /** True once a frame has been fully processed: the renderer may send another. */
      readonly frameDone: boolean
    }
  /** A sampled frame for the vision recogniser (cloud vision on). */
  | { readonly type: 'vision-frame'; readonly frameAt: number; readonly jpeg: ArrayBuffer }
  /** The screen catalog changed (a new menu was learned): the renderer saves it. */
  | { readonly type: 'screens'; readonly catalog: ScreenCatalog }
  /** The HUD digit shapes learned so far (kept between sessions). */
  | { readonly type: 'hud-book'; readonly book: GlyphBookData }
  /** The map stitched from the minimap grew: the renderer shows and saves it. */
  | { readonly type: 'mosaic'; readonly mosaic: StoredMosaic }
  | { readonly type: 'snapshot'; readonly requestId: number; readonly jpeg: ArrayBuffer | null; readonly frameAt: number; readonly width: number; readonly height: number }
  | { readonly type: 'stopped'; readonly snapshot: PatternSnapshot; readonly insights: InsightView[]; readonly report: SessionReport }
  | { readonly type: 'error'; readonly message: string }
