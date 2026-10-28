// Picks the host bridge: Electron's preload API when present, otherwise a
// browser implementation for the dev preview (browser picker + localStorage,
// no cloud features, no voice input). The browser preview stores the same
// opt-in data as the desktop app, but unencrypted, and says so.

import type { PlayerNotes, SightlineBridge, VoiceModelStatus } from '../../shared/bridge.ts'
import { DEFAULT_CLOUD, type CloudStatus } from '../../shared/cloud.ts'
import type { TtsStatus } from '../../shared/voices.ts'
import { isMatchRecord, type MatchRecord } from '../core/analyses/matchRecorder.ts'
import { deletePattern, migrateSnapshot, setVerdict } from '../core/patterns.ts'
import { sanitizeSettings } from '../core/settings.ts'

const PREFIX = 'sightline.'
/** Same bound as the desktop store. */
const MAX_MATCHES = 200
const KEYS = {
  settings: `${PREFIX}settings`,
  tendencies: (id: string) => `${PREFIX}tendencies.${id}`,
  matches: (id: string) => `${PREFIX}matches.${id}`,
  notes: (id: string) => `${PREFIX}notes.${id}`,
}

function read(key: string): unknown {
  try {
    const raw = localStorage.getItem(key)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

function write(key: string, value: unknown): boolean {
  try {
    localStorage.setItem(key, JSON.stringify(value))
    return true
  } catch {
    // Private mode or storage disabled: nothing persists.
    return false
  }
}

function remove(predicate: (key: string) => boolean): void {
  try {
    for (const key of Object.keys(localStorage)) if (key.startsWith(PREFIX) && predicate(key)) localStorage.removeItem(key)
  } catch {
    // nothing stored
  }
}

const optedIn = () => sanitizeSettings(read(KEYS.settings)).saveTendencies

function matches(profileId: string): MatchRecord[] {
  const v = read(KEYS.matches(profileId))
  return Array.isArray(v) ? v.filter(isMatchRecord) : []
}

const CLOUD_UNAVAILABLE: CloudStatus = {
  available: false,
  settings: DEFAULT_CLOUD,
  key: { set: false, last4: null, persisted: false, encryptionAvailable: false },
  spend: { usd: 0, calls: 0, capUsd: DEFAULT_CLOUD.sessionCapUsd },
}

const NO_VOICE: VoiceModelStatus = { installed: false, installing: false, progress: 0, error: 'Voice input needs the desktop app.', sizeMb: 108 }
const NO_TTS: TtsStatus = {
  available: false,
  models: {
    jenny: { installed: false, installing: false, progress: 0, error: null, sizeMb: 67 },
    kokoro: { installed: false, installing: false, progress: 0, error: null, sizeMb: 103 },
  },
  realTimeFactor: {},
}
const NO_KNOWLEDGE = { building: false, stage: null, error: null, knowledge: null, screens: 0 }
const desktopOnly = { ok: false as const, error: 'Cloud features need the desktop app (your API key never touches a web page).', fatal: true }

export const browserBridge: SightlineBridge = {
  async info() {
    return { shell: 'browser', platform: navigator.platform || 'web', appVersion: 'dev', systemPicker: true, stopShortcut: null, talkShortcut: null, markShortcut: null, shortcutErrors: [] }
  },
  async listSources() {
    return []
  },
  async chooseSource() {},
  setCaptureState(active, label) {
    document.title = active ? `● Capturing ${label ?? ''} — Sightline` : 'Sightline Coach'
  },

  async loadSettings() {
    return read(KEYS.settings)
  },
  async saveSettings(settings) {
    const clean = sanitizeSettings(settings)
    write(KEYS.settings, clean)
    return clean
  },

  async loadTendencies(profileId) {
    return migrateSnapshot(read(KEYS.tendencies(profileId)))
  },
  async saveTendencies(snapshot) {
    const s = migrateSnapshot(snapshot)
    return !!s && optedIn() && write(KEYS.tendencies(s.profileId), s)
  },
  async setPatternVerdict(profileId, episodeId, verdict) {
    const s = migrateSnapshot(read(KEYS.tendencies(profileId)))
    if (!s || !optedIn()) return null
    const next = setVerdict(s, episodeId, verdict, new Date())
    write(KEYS.tendencies(profileId), next)
    return next
  },
  async deletePattern(profileId, episodeId) {
    const s = migrateSnapshot(read(KEYS.tendencies(profileId)))
    if (!s) return null
    const next = deletePattern(s, episodeId, new Date())
    write(KEYS.tendencies(profileId), next)
    return next
  },
  async loadMatches(profileId) {
    return matches(profileId)
  },
  async saveMatch(record) {
    if (!optedIn() || !isMatchRecord(record)) return false
    const list = [...matches(record.gameProfileId).filter((m) => m.id !== record.id), record].slice(-MAX_MATCHES)
    return write(KEYS.matches(record.gameProfileId), list)
  },
  async deleteMatch(profileId, id) {
    write(KEYS.matches(profileId), matches(profileId).filter((m) => m.id !== id))
  },
  async loadNotes(profileId) {
    const v = read(KEYS.notes(profileId)) as PlayerNotes | null
    return v && typeof v.goals === 'string' && typeof v.notes === 'string' ? v : null
  },
  async saveNotes(profileId, notes) {
    if (!optedIn()) return false
    return write(KEYS.notes(profileId), { goals: notes.goals.slice(0, 2000), notes: notes.notes.slice(0, 4000), updatedAt: new Date().toISOString() })
  },
  async forgetPlayerData(profileId) {
    remove((key) => key !== KEYS.settings && (profileId === null || key.endsWith(`.${profileId}`)))
  },
  async exportPlayerData() {
    const out: Record<string, unknown> = { exportedAt: new Date().toISOString(), encryptedAtRest: false, shell: 'browser' }
    try {
      for (const key of Object.keys(localStorage)) if (key.startsWith(PREFIX)) out[key.slice(PREFIX.length)] = read(key)
    } catch {
      // nothing stored
    }
    return JSON.stringify(out, null, 2)
  },
  async storageInfo() {
    return { encrypted: false }
  },

  async listUserProfiles() {
    return []
  },
  async openProfilesFolder() {},

  async cloudStatus() {
    return CLOUD_UNAVAILABLE
  },
  async usageSummary() {
    return null
  },
  async forgetUsage() {},
  async setCloudSettings() {
    return CLOUD_UNAVAILABLE
  },
  async setApiKey() {
    return CLOUD_UNAVAILABLE
  },
  async resetCloudSession() {},
  async analyzeFrame() {
    return desktopOnly
  },
  async ask() {
    return desktopOnly
  },
  async reviewMatch() {
    return desktopOnly
  },
  async research() {
    return desktopOnly
  },
  async loadResearch() {
    return null
  },
  async deleteResearch() {},

  async voiceStatus() {
    return NO_VOICE
  },
  async installVoiceModel() {
    return NO_VOICE
  },
  async removeVoiceModel() {
    return NO_VOICE
  },
  async transcribe() {
    throw new Error('Voice input needs the desktop app.')
  },
  async ttsStatus() {
    return NO_TTS
  },
  async installTts() {
    return NO_TTS
  },
  async removeTts() {
    return NO_TTS
  },
  async synthesize() {
    throw new Error('Neural voices need the desktop app.')
  },

  onStopRequested() {
    return () => {}
  },
  onPushToTalk() {
    return () => {}
  },
  onMarkMoment() {
    return () => {}
  },
  async loadScreens() {
    return null
  },
  async saveScreens() {
    return false
  },
  async knowledgeStatus() {
    return NO_KNOWLEDGE
  },
  async buildKnowledge() {
    return { ...NO_KNOWLEDGE, error: 'Building game knowledge needs the desktop app and an API key.' }
  },
  async deleteKnowledge() {
    return NO_KNOWLEDGE
  },
  async testCloud() {
    return { ok: false as const, error: 'Cloud features need the desktop app.' }
  },
}

export function getBridge(): SightlineBridge {
  return window.sightline ?? browserBridge
}
