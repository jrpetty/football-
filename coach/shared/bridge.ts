// The contract between the renderer and its host shell. In Electron the
// preload script exposes it as `window.sightline`; in a plain browser (dev
// preview) the renderer supplies a local-storage implementation. Nothing in
// this interface can touch a game process, input devices, or the network.

import type { PatternSnapshot } from '../src/core/patterns.ts'
import type { CoachSettings } from '../src/core/settings.ts'

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
}

export interface SightlineBridge {
  info(): Promise<PlatformInfo>
  /** Windows/screens the player may choose from (Electron without a system picker only). */
  listSources(): Promise<CaptureSource[]>
  /** Record the player's explicit choice; it authorises exactly one capture request, briefly. */
  chooseSource(id: string): Promise<void>
  /** Tell the shell whether capture is active, for its title/tray indicator. */
  setCaptureState(active: boolean, label: string | null): void

  loadSettings(): Promise<unknown>
  saveSettings(settings: CoachSettings): Promise<CoachSettings>

  /** Long-term tendencies. The shell refuses to save unless the player opted in. */
  loadTendencies(profileId: string): Promise<PatternSnapshot | null>
  saveTendencies(snapshot: PatternSnapshot): Promise<boolean>
  forgetTendencies(profileId: string | null): Promise<void>

  /** Raw user-installed profile JSON; the renderer validates it. */
  listUserProfiles(): Promise<unknown[]>
  openProfilesFolder(): Promise<void>

  /** The global stop shortcut was pressed. Returns an unsubscribe function. */
  onStopRequested(cb: () => void): () => void
}

declare global {
  interface Window {
    sightline?: SightlineBridge
  }
}
