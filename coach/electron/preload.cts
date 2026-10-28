// The only bridge between the sandboxed renderer and the main process. It
// exposes a fixed, narrow API (see shared/bridge.ts): no Node, no filesystem,
// no shell, no process access, and never the API key. Channel names repeat
// shared/channels.ts because a sandboxed preload cannot import local modules.

// A sandboxed preload must be CommonJS.
const { contextBridge, ipcRenderer } = require('electron') as typeof import('electron')
import type { SightlineBridge } from '../shared/bridge.ts'

const invoke = (channel: string, ...args: unknown[]) => ipcRenderer.invoke(channel, ...args)
const listen = (channel: string, cb: () => void) => {
  const listener = () => cb()
  ipcRenderer.on(channel, listener)
  return () => {
    ipcRenderer.removeListener(channel, listener)
  }
}

const bridge: SightlineBridge = {
  info: () => invoke('sightline:info'),
  listSources: () => invoke('sightline:list-sources'),
  chooseSource: (id) => invoke('sightline:choose-source', id),
  setCaptureState: (active, label) => ipcRenderer.send('sightline:capture-state', active, label),
  loadSettings: () => invoke('sightline:load-settings'),
  saveSettings: (s) => invoke('sightline:save-settings', s),
  loadTendencies: (id) => invoke('sightline:load-tendencies', id),
  saveTendencies: (snap) => invoke('sightline:save-tendencies', snap),
  setPatternVerdict: (id, ep, v) => invoke('sightline:set-pattern-verdict', id, ep, v),
  deletePattern: (id, ep) => invoke('sightline:delete-pattern', id, ep),
  loadMatches: (id) => invoke('sightline:load-matches', id),
  saveMatch: (r) => invoke('sightline:save-match', r),
  deleteMatch: (id, m) => invoke('sightline:delete-match', id, m),
  loadNotes: (id) => invoke('sightline:load-notes', id),
  saveNotes: (id, n) => invoke('sightline:save-notes', id, n),
  forgetPlayerData: (id) => invoke('sightline:forget-player-data', id),
  exportPlayerData: () => invoke('sightline:export-player-data'),
  storageInfo: () => invoke('sightline:storage-info'),
  listUserProfiles: () => invoke('sightline:list-user-profiles'),
  openProfilesFolder: () => invoke('sightline:open-profiles-folder'),
  cloudStatus: () => invoke('sightline:cloud-status'),
  usageSummary: () => invoke('sightline:usage-summary'),
  forgetUsage: () => invoke('sightline:forget-usage'),
  setCloudSettings: (s) => invoke('sightline:set-cloud-settings', s),
  setApiKey: (k) => invoke('sightline:set-api-key', k),
  resetCloudSession: () => invoke('sightline:reset-cloud-session'),
  analyzeFrame: (req) => invoke('sightline:analyze-frame', req),
  ask: (req) => invoke('sightline:ask', req),
  reviewMatch: (req) => invoke('sightline:review-match', req),
  research: (req) => invoke('sightline:research', req),
  loadResearch: (id) => invoke('sightline:load-research', id),
  deleteResearch: (id) => invoke('sightline:delete-research', id),
  voiceStatus: () => invoke('sightline:voice-status'),
  installVoiceModel: () => invoke('sightline:install-voice-model'),
  removeVoiceModel: () => invoke('sightline:remove-voice-model'),
  transcribe: (pcm) => invoke('sightline:transcribe', pcm),
  ttsStatus: () => invoke('sightline:tts-status'),
  installTts: (key) => invoke('sightline:install-tts', key),
  removeTts: (key) => invoke('sightline:remove-tts', key),
  synthesize: (req) => invoke('sightline:synthesize', req),
  gsiStatus: () => invoke('sightline:gsi-status'),
  gsiConfigure: (cfg) => invoke('sightline:gsi-configure', cfg),
  gsiCfg: () => invoke('sightline:gsi-cfg'),
  gsiSaveCfg: () => invoke('sightline:gsi-save-cfg'),
  onGameState: (cb) => {
    const listener = (_e: unknown, payload: unknown) => cb(payload)
    ipcRenderer.on('sightline:game-state', listener)
    return () => {
      ipcRenderer.removeListener('sightline:game-state', listener)
    }
  },
  onStopRequested: (cb) => listen('sightline:stop-requested', cb),
  onPushToTalk: (cb) => {
    const listener = (_e: unknown, action: unknown) => cb(action === 'stop' ? 'stop' : 'start')
    ipcRenderer.on('sightline:push-to-talk', listener)
    return () => {
      ipcRenderer.removeListener('sightline:push-to-talk', listener)
    }
  },
  talkEnded: () => ipcRenderer.send('sightline:talk-ended'),
  onMarkMoment: (cb) => listen('sightline:mark-moment', cb),
  testCloud: () => invoke('sightline:test-cloud'),
  loadScreens: (id) => invoke('sightline:load-screens', id),
  saveScreens: (c) => invoke('sightline:save-screens', c),
  loadMosaics: (id) => invoke('sightline:load-mosaics', id),
  saveMosaic: (id, m) => invoke('sightline:save-mosaic', id, m),
  deleteMosaic: (id, map) => invoke('sightline:delete-mosaic', id, map),
  listOverheads: (id) => invoke('sightline:list-overheads', id),
  loadOverhead: (id, mapId) => invoke('sightline:load-overhead', id, mapId),
  saveOverhead: (id, mapId, png) => invoke('sightline:save-overhead', id, mapId, png),
  deleteOverhead: (id, mapId) => invoke('sightline:delete-overhead', id, mapId),
  fetchOverhead: (id, mapId) => invoke('sightline:fetch-overhead', id, mapId),
  openOverheadPage: (id, mapId) => invoke('sightline:open-overhead-page', id, mapId),
  knowledgeStatus: (id) => invoke('sightline:knowledge-status', id),
  buildKnowledge: (req) => invoke('sightline:build-knowledge', req),
  deleteKnowledge: (id) => invoke('sightline:delete-knowledge', id),
}

contextBridge.exposeInMainWorld('sightline', bridge)
