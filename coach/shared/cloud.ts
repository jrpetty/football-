// Cloud feature types shared by the main process and the renderer. The
// renderer never sees the API key: only whether one is set.

export interface CloudSettings {
  readonly enabled: boolean
  readonly visionModel: string
  readonly answerModel: string
  /** Stop all cloud calls for the rest of the session once this much has been spent (estimate, USD). */
  readonly sessionCapUsd: number
  /**
   * Thinking before each screen reading: 'auto' lets the model think briefly
   * (low effort); 'off' turns it off where the model allows it (Sonnet 5.5,
   * Haiku 5.5). Opus 5.5 always thinks a little.
   */
  readonly visionThinking: VisionThinking
  /** Once the opponent's faction is known, send only this matchup's part of the roster with each frame (less to pay for, same names). */
  readonly matchupReference: boolean
}

export type VisionThinking = 'auto' | 'off'

export const DEFAULT_CLOUD: CloudSettings = {
  enabled: false,
  visionModel: 'claude-opus-5-5',
  answerModel: 'claude-opus-5-5',
  sessionCapUsd: 2,
  visionThinking: 'auto',
  matchupReference: true,
}

/** What screen readings actually used this session, from the API's own token counts. */
export interface VisionUsage {
  readonly calls: number
  readonly usd: number
  readonly input: number
  readonly cacheRead: number
  readonly cacheWrite: number
  readonly output: number
  /** Part of the output spent thinking, when the API reports it. */
  readonly thinking: number
}

export type CloudResult<T> =
  | ({ readonly ok: true; readonly costUsd: number } & T)
  | { readonly ok: false; readonly error: string; readonly retryAfterMs?: number; readonly fatal?: boolean }

export interface ResearchBrief {
  readonly gameTitle: string
  readonly text: string
  readonly sources: readonly { readonly url: string; readonly title: string }[]
  readonly createdAt: string
  readonly model: string
}

export interface KeyStatus {
  readonly set: boolean
  readonly last4: string | null
  /** Saved for future runs (encrypted). */
  readonly persisted: boolean
  readonly encryptionAvailable: boolean
}

export interface CloudStatus {
  /** False in the browser preview: cloud features need the desktop app. */
  readonly available: boolean
  readonly settings: CloudSettings
  readonly key: KeyStatus
  readonly spend: { readonly usd: number; readonly calls: number; readonly capUsd: number; readonly vision?: VisionUsage }
}
