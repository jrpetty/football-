// Cloud feature types shared by the main process and the renderer. The
// renderer never sees the API key: only whether one is set.

export interface CloudSettings {
  readonly enabled: boolean
  readonly visionModel: string
  readonly answerModel: string
  /** Stop all cloud calls for the rest of the session once this much has been spent (estimate, USD). */
  readonly sessionCapUsd: number
}

export const DEFAULT_CLOUD: CloudSettings = { enabled: false, visionModel: 'claude-opus-5-5', answerModel: 'claude-opus-5-5', sessionCapUsd: 2 }

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
  readonly spend: { readonly usd: number; readonly calls: number; readonly capUsd: number }
}
