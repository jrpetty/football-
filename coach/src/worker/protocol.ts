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

export interface VisionConfig {
  readonly enabled: boolean
  readonly intervalMs: number
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
      readonly now: number
    }
  /** A captured video frame. Transferred, then closed by the worker. */
  | { readonly type: 'bitmap'; readonly bitmap: ImageBitmap; readonly timestamp: number }
  /** An already-decoded frame (the pixel simulator). Its buffer is transferred. */
  | { readonly type: 'pixels'; readonly frame: Frame }
  /** Observations from a synthetic vision replay (demo/tests). */
  | { readonly type: 'observations'; readonly observations: Observation[]; readonly now: number }
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
  | { readonly type: 'boundary'; readonly kind: 'round-end' | 'match-end'; readonly result: RoundResult; readonly now: number }
  | { readonly type: 'stop'; readonly now: number }

export interface VisionStatus {
  readonly enabled: boolean
  readonly sampler: SamplerStats
  readonly lastError: string | null
  readonly accepted: number
  readonly dropped: number
  /** Mean recognition latency (frame capture → answer), ms. */
  readonly latencyMs: number
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
  | { readonly type: 'stopped'; readonly snapshot: PatternSnapshot; readonly insights: InsightView[]; readonly report: SessionReport }
  | { readonly type: 'error'; readonly message: string }
