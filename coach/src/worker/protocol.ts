// Messages between the renderer and the analysis worker. Only structured
// results ever leave the worker; frames go in and are released there.

import type { EngineStatus, InsightView } from '../core/engine.ts'
import type { PatternSnapshot } from '../core/patterns.ts'
import type { GameProfile } from '../core/profile/schema.ts'
import type { QueueStats } from '../core/queue.ts'
import type { CoachSettings } from '../core/settings.ts'
import type { Frame, RoundResult, TimelineEntry, Utterance } from '../core/types.ts'

export type ToWorker =
  | {
      readonly type: 'start'
      readonly profile: GameProfile
      readonly settings: CoachSettings
      readonly history: PatternSnapshot | null
      readonly now: number
    }
  /** A captured video frame. Transferred, then closed by the worker. */
  | { readonly type: 'bitmap'; readonly bitmap: ImageBitmap; readonly timestamp: number }
  /** An already-decoded frame (the simulator). Its buffer is transferred. */
  | { readonly type: 'pixels'; readonly frame: Frame }
  | { readonly type: 'settings'; readonly settings: CoachSettings; readonly now: number }
  | { readonly type: 'boundary'; readonly kind: 'round-end' | 'match-end'; readonly result: RoundResult; readonly now: number }
  | { readonly type: 'stop'; readonly now: number }

export interface WorkerStatus extends EngineStatus {
  readonly queue: QueueStats
  /** Mean analysis time per frame over the last few frames, ms. */
  readonly analysisMs: number
}

export type FromWorker =
  | {
      readonly type: 'output'
      readonly entries: TimelineEntry[]
      readonly utterances: Utterance[]
      readonly cancel: string[]
      readonly status: WorkerStatus | null
      /** Present when insights may have changed. */
      readonly insights: InsightView[] | null
      /** True once a frame (or command) has been fully processed: the renderer may send another. */
      readonly frameDone: boolean
    }
  | { readonly type: 'stopped'; readonly snapshot: PatternSnapshot; readonly insights: InsightView[] }
  | { readonly type: 'error'; readonly message: string }
