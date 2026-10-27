import { useCallback, useEffect, useMemo, useState, useSyncExternalStore } from 'react'
import type { PlatformInfo } from '../../shared/bridge.ts'
import { DEFAULT_CLOUD, type CloudStatus } from '../../shared/cloud.ts'
import { effectiveMode } from '../core/policy/governor.ts'
import { suggestProfile, validateProfile, type GameProfile } from '../core/profile/schema.ts'
import { DEFAULT_SETTINGS, MODE_INFO, sanitizeSettings, type CoachSettings } from '../core/settings.ts'
import { getBridge } from '../platform/bridge.ts'
import { BUILT_IN_PROFILES } from '../profiles/index.ts'
import { MicIcon } from './components/common.tsx'
import { SourcePicker } from './components/SourcePicker.tsx'
import { PolicyBadge } from './components/viz.tsx'
import { policyFor } from './policy.ts'
import { CoachSession, type SourceChoice } from './session.ts'
import { AskView } from './views/AskView.tsx'
import { CoachView } from './views/CoachView.tsx'
import { HistoryView } from './views/HistoryView.tsx'
import { InsightsView } from './views/InsightsView.tsx'
import { PrivacyView } from './views/PrivacyView.tsx'
import { ReviewView } from './views/ReviewView.tsx'
import { SettingsView } from './views/SettingsView.tsx'

export type Tab = 'coach' | 'insights' | 'ask' | 'history' | 'review' | 'settings' | 'privacy'

export interface ProfileCatalog {
  readonly profiles: readonly GameProfile[]
  readonly rejected: readonly { readonly name: string; readonly errors: readonly string[] }[]
}

const NO_CLOUD: CloudStatus = {
  available: false,
  settings: DEFAULT_CLOUD,
  key: { set: false, last4: null, persisted: false, encryptionAvailable: false },
  spend: { usd: 0, calls: 0, capUsd: DEFAULT_CLOUD.sessionCapUsd },
}

export function App() {
  const bridge = useMemo(getBridge, [])
  const session = useMemo(() => new CoachSession(bridge), [bridge])
  const state = useSyncExternalStore(session.subscribe, session.getState)
  const [settings, setSettings] = useState<CoachSettings>(DEFAULT_SETTINGS)
  const [loaded, setLoaded] = useState(false)
  const [info, setInfo] = useState<PlatformInfo | null>(null)
  const [cloud, setCloud] = useState<CloudStatus>(NO_CLOUD)
  const [catalog, setCatalog] = useState<ProfileCatalog>({ profiles: BUILT_IN_PROFILES, rejected: [] })
  const [tab, setTab] = useState<Tab>('coach')
  const [picking, setPicking] = useState(false)
  const [suggestion, setSuggestion] = useState<{ profile: GameProfile; window: string } | null>(null)

  const refreshCloud = useCallback(() => void bridge.cloudStatus().then(setCloud).catch(() => undefined), [bridge])

  useEffect(() => {
    void bridge.info().then(setInfo)
    void bridge.loadSettings().then((raw) => {
      setSettings(sanitizeSettings(raw))
      setLoaded(true)
    })
    refreshCloud()
    void bridge.listUserProfiles().then((raws) => {
      const profiles = [...BUILT_IN_PROFILES]
      const rejected: { name: string; errors: string[] }[] = []
      for (const raw of raws) {
        const name = typeof raw === 'object' && raw && 'name' in raw ? String((raw as { name: unknown }).name) : 'Unnamed pack'
        const r = validateProfile(raw)
        if (!r.ok) rejected.push({ name, errors: r.errors })
        else if (profiles.some((p) => p.id === r.profile.id)) rejected.push({ name, errors: [`id "${r.profile.id}" is already in use`] })
        else profiles.splice(profiles.length - 1, 0, r.profile)
      }
      setCatalog({ profiles, rejected })
    })
  }, [bridge, refreshCloud])

  const capturing = state.phase === 'capturing'

  // Global shortcuts: stop capture; push-to-talk.
  useEffect(() => bridge.onStopRequested(() => void session.stop()), [bridge, session])
  useEffect(() => bridge.onPushToTalk(() => void session.toggleTalk(cloud)), [bridge, session, cloud])

  // Spend changes while cloud vision runs.
  useEffect(() => {
    if (!capturing || !cloud.available) return
    const t = setInterval(refreshCloud, 5000)
    return () => clearInterval(t)
  }, [capturing, cloud.available, refreshCloud])

  const profile = catalog.profiles.find((p) => p.id === settings.profileId) ?? catalog.profiles[0]
  const policy = useMemo(() => policyFor(profile, settings), [profile, settings])
  const effective = effectiveMode(settings.mode, policy)

  useEffect(() => {
    if (loaded) void session.select(profile, settings)
  }, [loaded, session, profile, settings])

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

  const start = useCallback(
    (kind: SourceChoice, speed = 1) => {
      if (kind === 'screen' && info?.shell === 'electron' && !info.systemPicker) {
        setPicking(true)
        return
      }
      void session.start(kind, profile, settings, cloud, { speed })
    },
    [info, session, profile, settings, cloud],
  )

  const onPicked = useCallback(
    async (sourceId: string, name: string) => {
      setPicking(false)
      const match = suggestProfile(catalog.profiles, name)
      if (match && match.id !== profile.id) setSuggestion({ profile: match, window: name })
      await bridge.chooseSource(sourceId)
      await session.start('screen', profile, settings, cloud)
    },
    [bridge, session, profile, settings, cloud, catalog.profiles],
  )

  const talkLabel = state.talk === 'recording' ? 'Listening… tap to send' : state.talk === 'transcribing' ? 'Transcribing…' : 'Talk'

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="16" height="16">
              <circle cx="12" cy="12" r="4" fill="currentColor" />
              <path d="M2 12c3-5 6.5-7.5 10-7.5S19 7 22 12c-3 5-6.5 7.5-10 7.5S5 17 2 12Z" fill="none" stroke="currentColor" strokeWidth="2" />
            </svg>
          </span>
          Sightline
        </div>
        <div className={`capture-pill${capturing ? ' live' : ''}`} role="status" aria-live="polite">
          <span className="dot" aria-hidden="true" />
          <span className="label">
            {capturing
              ? `${state.source?.kind === 'screen' ? 'Watching' : 'Demo'}: ${state.source?.label}`
              : state.phase === 'starting'
                ? 'Waiting for you to choose what to share…'
                : 'Not capturing. Nothing is being watched.'}
          </span>
        </div>
        <div className="topbar-policy" title={policy.reasons[0]}>
          <span className="muted small">{profile.game.title}</span>
          <PolicyBadge policy={policy} />
          <span className="badge neutral" title={effective.downgradeReason ?? MODE_INFO[effective.mode].summary}>
            {MODE_INFO[effective.mode].label}
            {effective.downgradeReason ? ' ↓' : ''}
          </span>
        </div>
        <div className="spacer" />
        {settings.voiceInput && (
          <button
            type="button"
            className={`btn talk${state.talk === 'recording' ? ' recording' : ''}`}
            onClick={() => void session.toggleTalk(cloud)}
            disabled={state.talk === 'transcribing'}
            title={info?.talkShortcut ? `Push to talk (${info.talkShortcut})` : 'Push to talk'}
          >
            <MicIcon /> {talkLabel}
          </button>
        )}
        <button
          type="button"
          className="btn toggle"
          aria-pressed={settings.muted}
          onClick={() => updateSettings({ muted: !settings.muted })}
          title="Hard mute: nothing is spoken; messages still appear on screen"
        >
          {settings.muted ? 'Muted' : 'Voice on'}
        </button>
        {capturing ? (
          <button type="button" className="btn stop" onClick={() => void session.stop()}>
            <span className="stop-square" aria-hidden="true" /> Stop capture
            {info?.stopShortcut ? <kbd className="kbd-inline">{info.stopShortcut}</kbd> : null}
          </button>
        ) : (
          <button type="button" className="btn primary" disabled={state.phase === 'starting'} onClick={() => start('screen')}>
            Start capture
          </button>
        )}
      </header>

      <div className="body">
        <nav className="nav" aria-label="Sections">
          {(
            [
              ['coach', 'Coach', capturing ? '●' : null],
              ['insights', 'Insights', state.report?.insights.filter((i) => i.insight.grade !== 'insufficient').length || null],
              ['ask', 'Ask the coach', state.conversation.length || null],
              ['history', 'History', state.history.length || null],
              ['review', 'Session timeline', state.timeline.filter((e) => e.type === 'message').length || null],
              ['settings', 'Settings', null],
              ['privacy', 'Policy & privacy', null],
            ] as const
          ).map(([id, label, count]) => (
            <button key={id} type="button" aria-current={tab === id ? 'page' : undefined} onClick={() => setTab(id)}>
              {label}
              {count ? <span className={`count${count === '●' ? ' live' : ''}`}>{count}</span> : null}
            </button>
          ))}
          <div className="nav-foot">
            Screen pixels only. No game memory, no input, no overlays.
            {info?.stopShortcut ? (
              <>
                <br />
                Stop anytime: <kbd>{info.stopShortcut}</kbd>
              </>
            ) : null}
            {settings.voiceInput && info?.talkShortcut ? (
              <>
                <br />
                Talk: <kbd>{info.talkShortcut}</kbd>
              </>
            ) : null}
          </div>
        </nav>

        <main>
          {state.talkError && (
            <div className="toast" role="alert">
              {state.talkError}
            </div>
          )}
          {suggestion && !capturing && (
            <div className="toast info" role="status">
              “{suggestion.window}” looks like {suggestion.profile.game.title}.{' '}
              <button
                type="button"
                className="btn sm"
                onClick={() => {
                  updateSettings({ profileId: suggestion.profile.id, profileModeId: null })
                  setSuggestion(null)
                }}
              >
                Use the {suggestion.profile.name} pack
              </button>{' '}
              <button type="button" className="btn sm ghost" onClick={() => setSuggestion(null)}>
                Dismiss
              </button>
            </div>
          )}
          {tab === 'coach' && (
            <CoachView
              state={state}
              session={session}
              settings={settings}
              onSettings={updateSettings}
              catalog={catalog}
              profile={profile}
              policy={policy}
              effective={effective}
              cloud={cloud}
              info={info}
              onStart={start}
              onTab={setTab}
            />
          )}
          {tab === 'insights' && <InsightsView state={state} session={session} profile={profile} settings={settings} />}
          {tab === 'ask' && <AskView state={state} session={session} profile={profile} settings={settings} cloud={cloud} onTab={setTab} />}
          {tab === 'history' && <HistoryView state={state} session={session} profile={profile} settings={settings} bridge={bridge} onTab={setTab} />}
          {tab === 'review' && <ReviewView state={state} />}
          {tab === 'settings' && (
            <SettingsView
              settings={settings}
              onSettings={updateSettings}
              session={session}
              info={info}
              catalog={catalog}
              bridge={bridge}
              cloud={cloud}
              onCloud={setCloud}
              profile={profile}
            />
          )}
          {tab === 'privacy' && <PrivacyView profile={profile} policy={policy} info={info} />}
        </main>
      </div>

      {picking && <SourcePicker bridge={bridge} onCancel={() => setPicking(false)} onPick={(id, name) => void onPicked(id, name)} />}
    </div>
  )
}
