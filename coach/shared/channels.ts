// IPC channel names. The sandboxed preload cannot import this module at
// runtime, so it repeats these literals; a test keeps the two in sync.

export const CHANNELS = {
  info: 'sightline:info',
  listSources: 'sightline:list-sources',
  chooseSource: 'sightline:choose-source',
  captureState: 'sightline:capture-state',
  loadSettings: 'sightline:load-settings',
  saveSettings: 'sightline:save-settings',
  loadTendencies: 'sightline:load-tendencies',
  saveTendencies: 'sightline:save-tendencies',
  forgetTendencies: 'sightline:forget-tendencies',
  listUserProfiles: 'sightline:list-user-profiles',
  openProfilesFolder: 'sightline:open-profiles-folder',
  stopRequested: 'sightline:stop-requested',
} as const
