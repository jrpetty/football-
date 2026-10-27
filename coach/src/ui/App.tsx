import { useCallback, useEffect, useMemo, useState, useSyncExternalStore } from 'react'
import type { PlatformInfo } from '../../shared/bridge.ts'
import { effectiveMode } from '../core/policy.ts'
import { validateProfile, type GameProfile } from '../core/profile/schema.ts'
import { DEFAULT_SETTINGS, MODE_INFO, sanitizeSettings, type CoachSettings } from '../core/settings.ts'
import { getBridge } from '../platform/bridge.ts'
import { BUILT_IN_PROFILES } from '../profiles/index.ts'
import { SourcePicker } from './components/SourcePicker.tsx'
import { CoachSession } from './session.ts'
import { CoachView } from './views/CoachView.tsx'
import { PrivacyView } from './views/PrivacyView.tsx'
import { ReviewView } from './views/ReviewView.tsx'
import { SettingsView } from './views/SettingsView.tsx'
import { TendenciesView } from './views/TendenciesView.tsx'

type Tab = 'coach' | 'review' | 'tendencies' | 'settings' | 'privacy'

export interface ProfileCatalog {
  readonly profiles: readonly GameProfile[]
  readonly rejected: readonly { readonly name: string; readonly errors: readonly string[] }[]
}

/** The simulator is a developer tool; it is offered in dev builds or with ?sim in the URL. */
const SIMULATOR_ENABLED = import.meta.env.DEV || new URLSearchParams(location.search).has('sim')

export function App() {
  const bridge = useMemo(getBridge, [])
  const session = useMemo(() => new CoachSession(bridge), [bridge])
  const state = useSyncExternalStore(session.subscribe, session.getState)
  const [settings, setSettings] = useState<CoachSettings>(DEFAULT_SETTINGS)
  const [info, setInfo] = useState<PlatformInfo | null>(null)
  const [catalog, setCatalog] = useState<ProfileCatalog>({ profiles: BUILT_IN_PROFILES, rejected: [] })
  const [tab, setTab] = useState<Tab>('coach')
  const [picking, setPicking] = useState(false)

  useEffect(() => {
    void bridge.info().then(setInfo)
    void bridge.loadSettings().then((raw) => setSettings(sanitizeSettings(raw)))
    void bridge.listUserProfiles().then((raws) => {
      const profiles = [...BUILT_IN_PROFILES]
      const rejected: { name: string; errors: string[] }[] = []
      for (const raw of raws) {
        const name = typeof raw === 'object' && raw && 'name' in raw ? String((raw as { name: unknown }).name) : 'Unnamed profile'
        const r = validateProfile(raw)
        if (!r.ok) rejected.push({ name, errors: r.errors })
        else if (profiles.some((p) => p.id === r.profile.id)) rejected.push({ name, errors: [`id "${r.profile.id}" is already in use`] })
        else profiles.push(r.profile)
      }
      setCatalog({ profiles, rejected })
    })
    return bridge.onStopRequested(() => void session.stop())
  }, [bridge, session])

  const profile = catalog.profiles.find((p) => p.id === settings.profileId) ?? catalog.profiles[0]
  const profileMode = profile.modes.find((m) => m.id === settings.profileModeId) ?? null
  const effective = effectiveMode(settings.mode, profileMode)

  const updateSettings = useCallback(
    (patch: Partial<CoachSettings>) => {
      setSettings((prev) => {
        const next = sanitizeSettings({ ...prev, ...patch, voice: { ...prev.voice, ...patch.voice } }, prev)
        void bridge.saveSettings(next)
        session.updateSettings(next)
        return next
      })
    },
    [bridge, session],
  )

  const startScreen = useCallback(() => {
    if (info?.shell === 'electron' && !info.systemPicker) setPicking(true)
    else void session.start('screen', profile, settings)
  }, [info, session, profile, settings])

  const onPicked = useCallback(
    async (sourceId: string) => {
      setPicking(false)
      await bridge.chooseSource(sourceId)
      await session.start('screen', profile, settings)
    },
    [bridge, session, profile, settings],
  )

  const capturing = state.phase === 'capturing'
  const messages = state.timeline.filter((e) => e.type === 'message').length

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">S</span>
          Sightline
        </div>
        <div className={`capture-pill${capturing ? ' live' : ''}`} role="status" aria-live="polite">
          <span className="dot" aria-hidden="true" />
          <span className="label">
            {capturing
              ? `Capturing: ${state.source?.label}`
              : state.phase === 'starting'
                ? 'Waiting for you to choose what to share…'
                : 'Not capturing. Nothing is being watched.'}
          </span>
        </div>
        <span className="badge neutral" title={effective.downgradeReason ?? MODE_INFO[effective.mode].summary}>
          {MODE_INFO[effective.mode].label}
        </span>
        <div className="spacer" />
        <button
          type="button"
          className="btn toggle"
          aria-pressed={settings.muted}
          onClick={() => updateSettings({ muted: !settings.muted })}
          title="Hard mute: nothing is spoken; messages still appear here"
        >
          {settings.muted ? '🔇 Muted' : '🔊 Voice on'}
        </button>
        {capturing ? (
          <button type="button" className="btn stop" onClick={() => void session.stop()}>
            ■ Stop capture
            {info?.stopShortcut ? <span className="sr-only"> (shortcut {info.stopShortcut})</span> : null}
          </button>
        ) : (
          <button type="button" className="btn primary" disabled={state.phase === 'starting'} onClick={startScreen}>
            Start capture
          </button>
        )}
      </header>

      <div className="body">
        <nav className="nav" aria-label="Sections">
          {(
            [
              ['coach', 'Coach', null],
              ['review', 'Session review', messages || null],
              ['tendencies', 'Tendencies', state.insights.length || null],
              ['settings', 'Voice & settings', null],
              ['privacy', 'Privacy & fair play', null],
            ] as const
          ).map(([id, label, count]) => (
            <button key={id} type="button" aria-current={tab === id ? 'page' : undefined} onClick={() => setTab(id)}>
              {label}
              {count ? <span className="count">{count}</span> : null}
            </button>
          ))}
          <div className="nav-foot">
            Pixels only. Runs on this computer. No game memory, input, or network access.
            {info?.stopShortcut ? (
              <>
                <br />
                <br />
                Stop capture anytime: <kbd>{info.stopShortcut}</kbd>
              </>
            ) : null}
          </div>
        </nav>

        <main>
          {tab === 'coach' && (
            <CoachView
              state={state}
              session={session}
              settings={settings}
              onSettings={updateSettings}
              catalog={catalog}
              profile={profile}
              effective={effective}
              onStart={startScreen}
              simulator={SIMULATOR_ENABLED}
            />
          )}
          {tab === 'review' && <ReviewView state={state} />}
          {tab === 'tendencies' && (
            <TendenciesView state={state} profile={profile} settings={settings} onSettings={updateSettings} bridge={bridge} />
          )}
          {tab === 'settings' && (
            <SettingsView settings={settings} onSettings={updateSettings} session={session} info={info} catalog={catalog} bridge={bridge} />
          )}
          {tab === 'privacy' && <PrivacyView profile={profile} info={info} />}
        </main>
      </div>

      {picking && <SourcePicker bridge={bridge} onCancel={() => setPicking(false)} onPick={(id) => void onPicked(id)} />}
    </div>
  )
}
