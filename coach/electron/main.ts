// The Electron main process. Its job is narrow and mostly defensive:
//
//  - one window, sandboxed, context-isolated, with no Node in the renderer;
//  - screen capture only through the OS/Chromium display-capture path, and
//    only for a source the player explicitly picked moments before;
//  - the renderer cannot reach the network: every http(s)/ws request from it
//    is cancelled. The only network code is the opt-in cloud service (player's
//    own key, api.anthropic.com only) and the opt-in speech-model download,
//    both here in main;
//  - permissions: display capture always; the microphone only when the player
//    turned on voice input;
//  - small JSON storage, with player data encrypted by the OS when possible.
//
// It never enumerates processes, opens a game process, hooks input, or draws
// over any other window.

import {
  app,
  BrowserWindow,
  desktopCapturer,
  globalShortcut,
  ipcMain,
  Menu,
  safeStorage,
  session,
  shell,
  type DesktopCapturerSource,
  type IpcMainInvokeEvent,
} from 'electron'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { CHANNELS } from '../shared/channels.ts'
import type { CaptureSource, PlatformInfo } from '../shared/bridge.ts'
import type { CloudStatus } from '../shared/cloud.ts'
import { validateProfile, type GameProfile } from '../src/core/profile/schema.ts'
import type { CoachSettings } from '../src/core/settings.ts'
import { BUILT_IN_PROFILES } from '../src/profiles/index.ts'
import { KeyStore, type Cipher } from './cloud/keystore.ts'
import { CloudService } from './cloud/service.ts'
import { CaptureConsent, isSourceId } from './consent.ts'
import type { VoiceModelStatus as ModelStatus } from '../shared/bridge.ts'
import { MAX_SYNTH_CHARS, NEURAL_VOICES, type ModelState, type TtsModelKey, type TtsStatus } from '../shared/voices.ts'
import { ASR_MODEL, isInstalled, installModel, removeModel, TTS_MODELS } from './models.ts'
import { TtsThread } from './tts/engine.ts'
import { RecognizerThread, SAMPLE_RATE, MAX_SECONDS } from './stt/recognizer.ts'
import { isProfileId, Storage } from './storage.ts'

const here = dirname(fileURLToPath(import.meta.url))
const DEV_URL = process.env.VITE_DEV_SERVER_URL ?? null
const STOP_SHORTCUT = 'CommandOrControl+Shift+F12'
const TALK_SHORTCUT = 'CommandOrControl+Shift+F11'
const APP_TITLE = 'Sightline Coach'
const MAX_JPEG_BYTES = 3 * 1024 * 1024

const consent = new CaptureConsent()
/** The sources last shown in the picker. Only these can be chosen. */
let offered = new Map<string, DesktopCapturerSource>()
let storage: Storage
let keys: KeyStore
let cloud: CloudService
let cloudSettings = { enabled: false, visionModel: 'claude-opus-5-5', answerModel: 'claude-opus-5-5', sessionCapUsd: 2 }
let settings: CoachSettings | null = null
let recognizer: RecognizerThread | null = null
let modelStatus: ModelStatus = { installed: false, installing: false, progress: 0, error: null, sizeMb: Math.round(ASR_MODEL.archiveBytes / 1e6) }
let tts: TtsThread | null = null
const ttsModels: Record<TtsModelKey, ModelState> = {
  jenny: { installed: false, installing: false, progress: 0, error: null, sizeMb: Math.round(TTS_MODELS.jenny.archiveBytes / 1e6) },
  kokoro: { installed: false, installing: false, progress: 0, error: null, sizeMb: Math.round(TTS_MODELS.kokoro.archiveBytes / 1e6) },
}
/** Measured synthesis speed per model (compute seconds per second of speech), smoothed. */
const ttsRtf: Partial<Record<TtsModelKey, number>> = {}
let win: BrowserWindow | null = null
const shortcuts = { stop: false, talk: false }

/** macOS 15+ has a system picker that Electron can show (ScreenCaptureKit). */
function hasSystemPicker(): boolean {
  if (process.platform !== 'darwin') return false
  const major = Number.parseInt(process.getSystemVersion().split('.')[0] ?? '0', 10)
  return major >= 15
}

function isTrustedSender(e: IpcMainInvokeEvent | Electron.IpcMainEvent): boolean {
  const url = e.senderFrame?.url ?? ''
  return url.startsWith('file://') || (DEV_URL !== null && url.startsWith(DEV_URL))
}

function handle(channel: string, fn: (...args: unknown[]) => unknown): void {
  ipcMain.handle(channel, (e, ...args) => {
    if (!isTrustedSender(e)) throw new Error('Untrusted sender')
    return fn(...args)
  })
}

function osCipher(): Cipher {
  return {
    available: safeStorage.isEncryptionAvailable(),
    encrypt: (text) => safeStorage.encryptString(text),
    decrypt: (data) => safeStorage.decryptString(data),
  }
}

function hardenSession(): void {
  const s = session.defaultSession

  // The renderer cannot send anything anywhere.
  s.webRequest.onBeforeRequest({ urls: ['http://*/*', 'https://*/*', 'ws://*/*', 'wss://*/*'] }, (details, callback) => {
    const allowed = DEV_URL !== null && (details.url.startsWith(DEV_URL) || details.url.startsWith(DEV_URL.replace(/^http/, 'ws')))
    callback({ cancel: !allowed })
  })

  // Display capture always (gated by the consent token below). Chromium asks
  // for "media" with no media types on the getDisplayMedia path. Microphone
  // ("audio" only) is allowed solely when the player enabled voice input;
  // cameras are always refused.
  s.setPermissionRequestHandler((_wc, permission, callback, details) => {
    if (permission === 'display-capture') return callback(true)
    if (permission !== 'media' || !('mediaTypes' in details)) return callback(false)
    const types = details.mediaTypes ?? []
    if (types.length === 0) return callback(true)
    callback(types.length === 1 && types[0] === 'audio' && settings?.voiceInput === true)
  })
  s.setPermissionCheckHandler((_wc, permission) => permission === 'display-capture' || (permission === 'media' && settings?.voiceInput === true))

  s.setDisplayMediaRequestHandler(
    (_request, callback) => {
      // Grant only the source the player just chose in the picker; otherwise deny.
      const sourceId = consent.take()
      const source = sourceId ? offered.get(sourceId) : undefined
      callback(source ? { video: source } : {})
    },
    // On macOS 15+ the OS shows its own picker and permission prompt instead.
    { useSystemPicker: true },
  )
}

function createWindow(): void {
  win = new BrowserWindow({
    width: 1360,
    height: 880,
    minWidth: 1000,
    minHeight: 660,
    title: APP_TITLE,
    backgroundColor: '#0b0e14',
    show: false,
    autoHideMenuBar: true,
    webPreferences: {
      preload: join(here, 'preload.cjs'),
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      webSecurity: true,
      spellcheck: false,
      // Analysis must keep running while the game, not this window, has focus.
      backgroundThrottling: false,
    },
  })
  win.once('ready-to-show', () => win?.show())
  win.on('closed', () => (win = null))
  // The window title is the capture indicator; the page must not overwrite it.
  win.on('page-title-updated', (e) => e.preventDefault())
  // The coach never navigates and never opens other windows.
  win.webContents.on('will-navigate', (e) => e.preventDefault())
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }))

  if (DEV_URL) void win.loadURL(DEV_URL)
  else void win.loadFile(join(here, '..', '..', 'dist', 'index.html'))
}

/** Packs are resolved here from built-ins and validated user packs; the renderer only names one. */
async function resolveProfile(id: unknown): Promise<GameProfile | null> {
  if (!isProfileId(id)) return null
  const builtIn = BUILT_IN_PROFILES.find((p) => p.id === id)
  if (builtIn) return builtIn
  for (const raw of await storage.listUserProfiles()) {
    const r = validateProfile(raw)
    if (r.ok && r.profile.id === id) return r.profile
  }
  return null
}

function sanitizeContext(v: unknown): Record<string, string | number> {
  const out: Record<string, string | number> = {}
  if (typeof v !== 'object' || v === null) return out
  for (const [k, x] of Object.entries(v).slice(0, 20)) {
    if (!/^[a-zA-Z][a-zA-Z0-9]{0,40}$/.test(k)) continue
    if ((typeof x === 'number' && Number.isFinite(x)) || (typeof x === 'string' && x.length <= 80)) out[k] = x
  }
  return out
}

function cloudStatus(): CloudStatus {
  return { available: true, settings: cloudSettings, key: keys.status, spend: cloud.spend }
}

function shortcutLabel(accel: string): string {
  return accel.replace('CommandOrControl', process.platform === 'darwin' ? 'Cmd' : 'Ctrl')
}

function registerIpc(): void {
  handle(CHANNELS.info, (): PlatformInfo => ({
    shell: 'electron',
    platform: process.platform,
    appVersion: app.getVersion(),
    systemPicker: hasSystemPicker(),
    stopShortcut: shortcuts.stop ? shortcutLabel(STOP_SHORTCUT) : null,
    talkShortcut: shortcuts.talk ? shortcutLabel(TALK_SHORTCUT) : null,
  }))

  // --- Capture -------------------------------------------------------------------------
  handle(CHANNELS.listSources, async (): Promise<CaptureSource[]> => {
    const list = () =>
      desktopCapturer.getSources({ types: ['window', 'screen'], thumbnailSize: { width: 320, height: 180 }, fetchWindowIcons: false })
    let sources = await list()
    // The OS screen capturer can fail to initialise on first use (seen with
    // X11) and silently omit every screen. There is always at least one, so
    // retry once when none came back.
    if (!sources.some((s) => s.id.startsWith('screen:'))) {
      await new Promise((r) => setTimeout(r, 300))
      sources = await list()
    }
    // Never offer to capture the coach itself.
    const shareable = sources.filter((s) => !s.name.startsWith(APP_TITLE))
    offered = new Map(shareable.map((s) => [s.id, s]))
    return shareable.map((s) => ({
      id: s.id,
      name: s.name,
      kind: s.id.startsWith('screen:') ? 'screen' : 'window',
      thumbnail: s.thumbnail.isEmpty() ? '' : s.thumbnail.toDataURL(),
    }))
  })
  handle(CHANNELS.chooseSource, (id) => {
    if (!isSourceId(id) || !offered.has(id)) throw new Error('Invalid capture source')
    consent.choose(id)
  })
  ipcMain.on(CHANNELS.captureState, (e, active: unknown, label: unknown) => {
    if (!isTrustedSender(e) || !win) return
    const name = typeof label === 'string' ? label.slice(0, 120) : ''
    win.setTitle(active === true ? `● Capturing ${name} — ${APP_TITLE}` : APP_TITLE)
  })

  // --- Settings and player data ---------------------------------------------------------
  handle(CHANNELS.loadSettings, async () => (settings = await storage.loadSettings()))
  handle(CHANNELS.saveSettings, async (s) => (settings = await storage.saveSettings(s)))
  handle(CHANNELS.loadTendencies, (id) => storage.loadTendencies(id))
  handle(CHANNELS.saveTendencies, (snap) => storage.saveTendencies(snap))
  handle(CHANNELS.setPatternVerdict, (id, ep, v) => storage.setPatternVerdict(id, ep, v))
  handle(CHANNELS.deletePattern, (id, ep) => storage.deletePattern(id, ep))
  handle(CHANNELS.loadMatches, (id) => storage.loadMatches(id))
  handle(CHANNELS.saveMatch, (r) => storage.saveMatch(r))
  handle(CHANNELS.deleteMatch, (id, m) => storage.deleteMatch(id, m))
  handle(CHANNELS.loadNotes, (id) => storage.loadNotes(id))
  handle(CHANNELS.saveNotes, (id, n) => storage.saveNotes(id, n))
  handle(CHANNELS.forgetPlayerData, (id) => storage.forgetPlayerData(id))
  handle(CHANNELS.exportPlayerData, () => storage.exportPlayerData())
  handle(CHANNELS.storageInfo, () => ({ encrypted: storage.encrypted }))
  handle(CHANNELS.listUserProfiles, () => storage.listUserProfiles())
  handle(CHANNELS.openProfilesFolder, async () => {
    await shell.openPath(storage.profilesDir)
  })

  // --- Cloud (opt-in, player's key) -----------------------------------------------------------
  handle(CHANNELS.cloudStatus, () => cloudStatus())
  handle(CHANNELS.setCloudSettings, async (s) => {
    cloudSettings = await storage.saveCloud({ ...cloudSettings, ...(typeof s === 'object' && s ? s : {}) })
    return cloudStatus()
  })
  handle(CHANNELS.setApiKey, async (k) => {
    await keys.set(typeof k === 'string' ? k : null)
    return cloudStatus()
  })
  handle(CHANNELS.resetCloudSession, () => cloud.resetSession())
  handle(CHANNELS.analyzeFrame, async (req) => {
    const r = req as { profileId?: unknown; jpeg?: unknown; context?: unknown } | null
    const profile = await resolveProfile(r?.profileId)
    if (!profile?.vision) return { ok: false, error: 'This pack has no vision schema.', fatal: true }
    if (!(r?.jpeg instanceof ArrayBuffer) || r.jpeg.byteLength === 0 || r.jpeg.byteLength > MAX_JPEG_BYTES) {
      return { ok: false, error: 'Invalid frame.', fatal: true }
    }
    return cloud.analyzeFrame(profile, { base64: Buffer.from(r.jpeg).toString('base64'), mediaType: 'image/jpeg' }, sanitizeContext(r.context))
  })
  handle(CHANNELS.ask, async (req) => {
    const r = req as { profileId?: unknown; question?: unknown; digest?: unknown } | null
    const profile = await resolveProfile(r?.profileId)
    if (!profile) return { ok: false, error: 'Unknown pack.', fatal: true }
    if (typeof r?.question !== 'string' || !r.question.trim() || typeof r.digest !== 'string') return { ok: false, error: 'Invalid question.', fatal: true }
    return cloud.ask(profile, r.question.slice(0, 1000), r.digest.slice(0, 40_000))
  })
  handle(CHANNELS.research, async (req) => {
    const r = req as { profileId?: unknown; gameTitle?: unknown; focus?: unknown } | null
    if (!isProfileId(r?.profileId) || typeof r.gameTitle !== 'string' || !r.gameTitle.trim()) return { ok: false, error: 'Invalid request.', fatal: true }
    const result = await cloud.research(r.gameTitle.slice(0, 120), typeof r.focus === 'string' ? r.focus.slice(0, 300) : '')
    if (result.ok) await storage.saveResearch(r.profileId, result.brief)
    return result
  })
  handle(CHANNELS.loadResearch, (id) => storage.loadResearch(id))
  handle(CHANNELS.deleteResearch, (id) => storage.deleteResearch(id))

  // --- Voice input (opt-in, local recognition) -------------------------------------------------
  handle(CHANNELS.voiceStatus, () => modelStatus)
  handle(CHANNELS.installVoiceModel, async () => {
    if (!settings?.voiceInput) return { ...modelStatus, error: 'Turn on voice input first.' }
    if (modelStatus.installing || modelStatus.installed) return modelStatus
    modelStatus = { ...modelStatus, installing: true, progress: 0, error: null }
    void installModel(app.getPath('userData'), ASR_MODEL, (p) => (modelStatus = { ...modelStatus, progress: p }))
      .then(() => (modelStatus = { ...modelStatus, installed: true, installing: false, progress: 1 }))
      .catch((e: unknown) => (modelStatus = { ...modelStatus, installing: false, error: e instanceof Error ? e.message : String(e) }))
    return modelStatus
  })
  handle(CHANNELS.removeVoiceModel, async () => {
    recognizer?.close()
    recognizer = null
    await removeModel(app.getPath('userData'), ASR_MODEL)
    modelStatus = { ...modelStatus, installed: false, progress: 0 }
    return modelStatus
  })
  handle(CHANNELS.transcribe, async (pcm) => {
    if (!settings?.voiceInput) throw new Error('Voice input is off.')
    if (!modelStatus.installed) throw new Error('The speech model is not installed.')
    if (!(pcm instanceof Float32Array) || pcm.length === 0 || pcm.length > SAMPLE_RATE * MAX_SECONDS) throw new Error('Invalid audio.')
    recognizer ??= new RecognizerThread(app.getPath('userData'))
    return recognizer.transcribe(pcm)
  })

  // --- Neural voices (local text-to-speech) ----------------------------------------------------
  const ttsStatus = (): TtsStatus => ({ available: true, models: { ...ttsModels }, realTimeFactor: { ...ttsRtf } })
  const isTtsKey = (k: unknown): k is TtsModelKey => k === 'jenny' || k === 'kokoro'
  handle(CHANNELS.ttsStatus, () => ttsStatus())
  handle(CHANNELS.installTts, async (key) => {
    if (!isTtsKey(key)) throw new Error('Unknown voice model')
    const m = ttsModels[key]
    if (m.installing || m.installed) return ttsStatus()
    ttsModels[key] = { ...m, installing: true, progress: 0, error: null }
    void installModel(app.getPath('userData'), TTS_MODELS[key], (p) => (ttsModels[key] = { ...ttsModels[key], progress: p }))
      .then(() => (ttsModels[key] = { ...ttsModels[key], installed: true, installing: false, progress: 1 }))
      .catch((e: unknown) => (ttsModels[key] = { ...ttsModels[key], installing: false, error: e instanceof Error ? e.message : String(e) }))
    return ttsStatus()
  })
  handle(CHANNELS.removeTts, async (key) => {
    if (!isTtsKey(key)) throw new Error('Unknown voice model')
    tts?.close()
    tts = null
    await removeModel(app.getPath('userData'), TTS_MODELS[key])
    ttsModels[key] = { ...ttsModels[key], installed: false, progress: 0 }
    delete ttsRtf[key]
    return ttsStatus()
  })
  handle(CHANNELS.synthesize, async (req) => {
    const r = req as { voice?: unknown; text?: unknown; speed?: unknown } | null
    const voice = NEURAL_VOICES.find((v) => v.id === r?.voice)
    if (!voice) throw new Error('Unknown voice')
    if (!ttsModels[voice.model].installed) throw new Error('That voice is not installed.')
    const text = typeof r?.text === 'string' ? r.text.trim().slice(0, MAX_SYNTH_CHARS) : ''
    if (!text) throw new Error('Nothing to say.')
    const speed = typeof r?.speed === 'number' && Number.isFinite(r.speed) ? Math.min(2, Math.max(0.5, r.speed)) : 1
    tts ??= new TtsThread(app.getPath('userData'))
    const { result, ms } = await tts.synthesize(voice.model, text, voice.sid, speed)
    const seconds = result.samples.length / result.sampleRate
    if (seconds > 0.5) {
      const rtf = ms / 1000 / seconds
      ttsRtf[voice.model] = ttsRtf[voice.model] === undefined ? rtf : ttsRtf[voice.model]! * 0.7 + rtf * 0.3
    }
    return result
  })
}

if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.on('second-instance', () => {
    if (win?.isMinimized()) win.restore()
    win?.focus()
  })

  // No webviews or extra windows, whatever a page tries.
  app.on('web-contents-created', (_e, contents) => {
    contents.on('will-attach-webview', (e) => e.preventDefault())
  })

  void app.whenReady().then(async () => {
    const root = app.getPath('userData')
    const cipher = osCipher()
    storage = new Storage(root, cipher)
    await storage.init()
    settings = await storage.loadSettings()
    cloudSettings = await storage.loadCloud()
    keys = new KeyStore(join(root, 'secret.bin'), cipher)
    await keys.load()
    cloud = new CloudService(() => keys.get(), () => cloudSettings)
    modelStatus = { ...modelStatus, installed: await isInstalled(root, ASR_MODEL) }
    for (const key of ['jenny', 'kokoro'] as const) ttsModels[key] = { ...ttsModels[key], installed: await isInstalled(root, TTS_MODELS[key]) }

    if (!DEV_URL) Menu.setApplicationMenu(null)
    hardenSession()
    registerIpc()
    createWindow()

    // Global keys, so the player never has to alt-tab out of a game.
    shortcuts.stop = globalShortcut.register(STOP_SHORTCUT, () => win?.webContents.send(CHANNELS.stopRequested))
    shortcuts.talk = globalShortcut.register(TALK_SHORTCUT, () => win?.webContents.send(CHANNELS.pushToTalk))
  })

  app.on('will-quit', () => {
    globalShortcut.unregisterAll()
    recognizer?.close()
    tts?.close()
  })
  app.on('window-all-closed', () => app.quit())
}
