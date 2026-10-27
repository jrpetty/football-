// The only bridge between the sandboxed renderer and the main process. It
// exposes a fixed, narrow API (see shared/bridge.ts) — no Node, no filesystem,
// no shell, no process access. Channel names repeat shared/channels.ts because
// a sandboxed preload cannot import local modules.

// A sandboxed preload must be CommonJS.
const { contextBridge, ipcRenderer } = require('electron') as typeof import('electron')
import type { SightlineBridge } from '../shared/bridge.ts'

const bridge: SightlineBridge = {
  info: () => ipcRenderer.invoke('sightline:info'),
  listSources: () => ipcRenderer.invoke('sightline:list-sources'),
  chooseSource: (id) => ipcRenderer.invoke('sightline:choose-source', id),
  setCaptureState: (active, label) => ipcRenderer.send('sightline:capture-state', active, label),
  loadSettings: () => ipcRenderer.invoke('sightline:load-settings'),
  saveSettings: (s) => ipcRenderer.invoke('sightline:save-settings', s),
  loadTendencies: (id) => ipcRenderer.invoke('sightline:load-tendencies', id),
  saveTendencies: (snap) => ipcRenderer.invoke('sightline:save-tendencies', snap),
  forgetTendencies: (id) => ipcRenderer.invoke('sightline:forget-tendencies', id),
  listUserProfiles: () => ipcRenderer.invoke('sightline:list-user-profiles'),
  openProfilesFolder: () => ipcRenderer.invoke('sightline:open-profiles-folder'),
  onStopRequested: (cb) => {
    const listener = () => cb()
    ipcRenderer.on('sightline:stop-requested', listener)
    return () => ipcRenderer.removeListener('sightline:stop-requested', listener)
  },
}

contextBridge.exposeInMainWorld('sightline', bridge)
