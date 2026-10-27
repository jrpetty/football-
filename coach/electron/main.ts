// The Electron main process. Its job is narrow and mostly defensive:
//
//  - one window, sandboxed, context-isolated, with no Node in the renderer;
//  - screen capture only through the OS/Chromium display-capture path, and
//    only for a source the player explicitly picked moments before;
//  - no network: every http(s)/ws request is cancelled (local-first by
//    construction, not by promise);
//  - no permissions besides display capture;
//  - small JSON storage for settings and opt-in tendency counts.
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
  session,
  shell,
  type DesktopCapturerSource,
  type IpcMainInvokeEvent,
} from 'electron'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { CHANNELS } from '../shared/channels.ts'
import type { CaptureSource, PlatformInfo } from '../shared/bridge.ts'
import { CaptureConsent, isSourceId } from './consent.ts'
import { Storage } from './storage.ts'

const here = dirname(fileURLToPath(import.meta.url))
const DEV_URL = process.env.VITE_DEV_SERVER_URL ?? null
const STOP_SHORTCUT = 'CommandOrControl+Shift+F12'
const APP_TITLE = 'Sightline Coach'

const consent = new CaptureConsent()
/** The sources last shown in the picker. Only these can be chosen. */
let offered = new Map<string, DesktopCapturerSource>()
let storage: Storage
let win: BrowserWindow | null = null
let stopShortcutRegistered = false

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

function hardenSession(): void {
  const s = session.defaultSession

  // Local-first by construction: the app cannot send anything anywhere.
  s.webRequest.onBeforeRequest({ urls: ['http://*/*', 'https://*/*', 'ws://*/*', 'wss://*/*'] }, (details, callback) => {
    const allowed = DEV_URL !== null && (details.url.startsWith(DEV_URL) || details.url.startsWith(DEV_URL.replace(/^http/, 'ws')))
    callback({ cancel: !allowed })
  })

  // Display capture is the only permission the app ever holds. Chromium asks
  // for "media" with no media types on the getDisplayMedia path; camera and
  // microphone requests name "video"/"audio" and are always refused. The
  // display-media handler below still requires the player's explicit choice.
  s.setPermissionRequestHandler((_wc, permission, callback, details) => {
    const displayOnly = permission === 'media' && 'mediaTypes' in details && (details.mediaTypes ?? []).length === 0
    callback(permission === 'display-capture' || displayOnly)
  })
  s.setPermissionCheckHandler((_wc, permission) => permission === 'display-capture')

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
    width: 1320,
    height: 860,
    minWidth: 980,
    minHeight: 640,
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

function registerIpc(): void {
  handle(CHANNELS.info, (): PlatformInfo => ({
    shell: 'electron',
    platform: process.platform,
    appVersion: app.getVersion(),
    systemPicker: hasSystemPicker(),
    stopShortcut: stopShortcutRegistered ? STOP_SHORTCUT.replace('CommandOrControl', process.platform === 'darwin' ? 'Cmd' : 'Ctrl') : null,
  }))

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

  handle(CHANNELS.loadSettings, () => storage.loadSettings())
  handle(CHANNELS.saveSettings, (s) => storage.saveSettings(s))
  handle(CHANNELS.loadTendencies, (id) => storage.loadTendencies(id))
  handle(CHANNELS.saveTendencies, (snap) => storage.saveTendencies(snap))
  handle(CHANNELS.forgetTendencies, (id) => storage.forgetTendencies(id))
  handle(CHANNELS.listUserProfiles, () => storage.listUserProfiles())
  handle(CHANNELS.openProfilesFolder, async () => {
    await shell.openPath(storage.profilesDir)
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
    storage = new Storage(app.getPath('userData'))
    await storage.init()
    if (!DEV_URL) Menu.setApplicationMenu(null)
    hardenSession()
    registerIpc()
    createWindow()

    // A global "stop capture" key, so the player never has to alt-tab out of a game to stop it.
    stopShortcutRegistered = globalShortcut.register(STOP_SHORTCUT, () => win?.webContents.send(CHANNELS.stopRequested))
  })

  app.on('will-quit', () => globalShortcut.unregisterAll())
  app.on('window-all-closed', () => app.quit())
}
