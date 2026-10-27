// Picks the host bridge: Electron's preload API when present, otherwise a
// browser implementation for the dev preview (browser picker + localStorage).

import type { SightlineBridge } from '../../shared/bridge.ts'
import { isPatternSnapshot } from '../core/patterns.ts'
import { sanitizeSettings } from '../core/settings.ts'

const SETTINGS_KEY = 'sightline.settings'
const TENDENCIES_KEY = (id: string) => `sightline.tendencies.${id}`

function read(key: string): unknown {
  try {
    const raw = localStorage.getItem(key)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

function write(key: string, value: unknown): void {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // Private mode or storage disabled: settings simply do not persist.
  }
}

export const browserBridge: SightlineBridge = {
  async info() {
    return { shell: 'browser', platform: navigator.platform || 'web', appVersion: 'dev', systemPicker: true, stopShortcut: null }
  },
  async listSources() {
    return []
  },
  async chooseSource() {},
  setCaptureState(active, label) {
    document.title = active ? `● Capturing ${label ?? ''} — Sightline` : 'Sightline Coach'
  },
  async loadSettings() {
    return read(SETTINGS_KEY)
  },
  async saveSettings(settings) {
    const clean = sanitizeSettings(settings)
    write(SETTINGS_KEY, clean)
    return clean
  },
  async loadTendencies(profileId) {
    const v = read(TENDENCIES_KEY(profileId))
    return isPatternSnapshot(v) ? v : null
  },
  async saveTendencies(snapshot) {
    const settings = sanitizeSettings(read(SETTINGS_KEY))
    if (!settings.saveTendencies) return false
    write(TENDENCIES_KEY(snapshot.profileId), snapshot)
    return true
  },
  async forgetTendencies(profileId) {
    try {
      for (const key of Object.keys(localStorage)) {
        if (profileId ? key === TENDENCIES_KEY(profileId) : key.startsWith('sightline.tendencies.')) localStorage.removeItem(key)
      }
    } catch {
      // nothing stored
    }
  },
  async listUserProfiles() {
    return []
  },
  async openProfilesFolder() {},
  onStopRequested() {
    return () => {}
  },
}

export function getBridge(): SightlineBridge {
  return window.sightline ?? browserBridge
}
