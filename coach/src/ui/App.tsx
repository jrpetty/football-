import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import type { PlatformInfo } from '../../shared/bridge.ts'
import { DEFAULT_CLOUD, type CloudStatus } from '../../shared/cloud.ts'
import { spendAlerts } from '../core/estimate.ts'
import { effectiveMode } from '../core/policy/governor.ts'
import { suggestProfile, validateProfile, type GameProfile } from '../core/profile/schema.ts'
import { DEFAULT_SETTINGS, MODE_INFO, sanitizeSettings, type CoachSettings } from '../core/settings.ts'
import type { UsageSummary } from '../core/usage.ts'
import { getBridge } from '../platform/bridge.ts'
import { BUILT_IN_PROFILES } from '../profiles/index.ts'
import { MicIcon } from './components/common.tsx'
import { Onboarding } from './components/Onboarding.tsx'
import { SourcePicker } from './components/SourcePicker.tsx'
import { PolicyBadge } from './components/viz.tsx'
import { policyFor } from './policy.ts'
import { CoachSession, type SourceChoice } from './session.ts'
import { AskView } from './views/AskView.tsx'
import { CoachView } from './views/CoachView.tsx'
import { HistoryView } from './views/HistoryView.tsx'
import { ProgressView } from './views/ProgressView.tsx'
import { InsightsView } from './views/InsightsView.tsx'
import { MomentsView } from './views/MomentsView.tsx'
import { PrivacyView } from './views/PrivacyView.tsx'
import { ReviewView } from './views/ReviewView.tsx'
import { SettingsView } from './views/SettingsView.tsx'
import { UsageView } from './views/UsageView.tsx'
import { usd } from './format.ts'

export type Tab = 'coach' | 'insights' | 'moments' | 'ask' | 'progress' | 'history' | 'review' | 'usage' | 'settings' | 'privacy'

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
  const [usage, setUsage] = useState<UsageSummary | null>(null)
  const [catalog, setCatalog] = useState<ProfileCatalog>({ profiles: BUILT_IN_PROFILES, rejected: [] })
  const [tab, setTab] = useState<Tab>('coach')
  const [picking, setPicking] = useState(false)
  const [suggestion, setSuggestion] = useState<{ profile: GameProfile; window: string } | null>(null)
  const [setupOpen, setSetupOpen] = useState(() => !new URLSearchParams(location.search).has('nosetup'))
  const mainRef = useRef<HTMLElement>(null)
  // Each section opens at its top.
  useEffect(() => {
    // Braces matter: newer Chromium returns a Promise from scrollTo, which React would take for a cleanup function.
    mainRef.current?.scrollTo(0, 0)
  }, [tab, state.phase])

  const refreshCloud = useCallback(() => void bridge.cloudStatus().then(setCloud).catch(() => undefined), [bridge])
  const refreshUsage = useCallback(() => void bridge.usageSummary().then(setUsage).catch(() => undefined), [bridge])

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
  useEffect(() => bridge.onPushToTalk((action) => void session.talkKey(action, cloud)), [bridge, session, cloud])
  useEffect(() => session.setCloud(cloud), [session, cloud])
  useEffect(() => bridge.onMarkMoment(() => void session.markMoment()), [bridge, session])
  // Keys are re-registered by the shell when they change; show what it actually got.
  const hotkeysKey = JSON.stringify(settings.hotkeys)
  useEffect(() => {
    if (!loaded) return
    const t = setTimeout(() => void bridge.info().then(setInfo), 400)
    return () => clearTimeout(t)
  }, [bridge, loaded, hotkeysKey])

  // Spend changes while cloud vision runs, and with each answer, review or research run.
  const cloudOn = cloud.available && cloud.settings.enabled
  useEffect(() => {
    if (!cloudOn) return
    const t = setInterval(refreshCloud, capturing ? 5000 : 15_000)
    return () => clearInterval(t)
  }, [capturing, cloudOn, refreshCloud])
  // The usage history follows each new call (and opens fresh on its page).
  useEffect(refreshUsage, [refreshUsage, cloud.spend.calls, cloud.settings.monthlyBudgetUsd])
  useEffect(() => {
    if (tab === 'usage') refreshUsage()
  }, [tab, refreshUsage])
  const alerts = spendAlerts(cloud.spend, cloud.settings)

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
  const alwaysOn = settings.voiceInput && settings.listen === 'always'
  const listen = state.listen
  const listenLabel =
    listen.status === 'on'
      ? listen.awaiting
        ? 'Ask now…'
        : settings.wakeWord
          ? 'Say “Coach…”'
          : 'Listening'
      : listen.status === 'paused'
        ? 'Listening paused'
        : listen.status === 'starting'
          ? 'Opening mic…'
          : listen.status === 'error'
            ? 'Mic problem'
            : 'Listening off'

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
        {cloudOn || cloud.spend.calls > 0 ? (
          <button
            type="button"
            className={`spend-chip${alerts.some((a) => a.level === 'stop') ? ' stop' : alerts.length ? ' warn' : ''}`}
            onClick={() => setTab('usage')}
            title={[
              `This session: ${usd(cloud.spend.usd)} of the ${usd(cloud.settings.sessionCapUsd)} cap, ${cloud.spend.calls} calls`,
              cloud.spend.monthUsd !== undefined ? `This month: ${usd(cloud.spend.monthUsd)}${cloud.settings.monthlyBudgetUsd ? ` of ${usd(cloud.settings.monthlyBudgetUsd)}` : ''}` : null,
              cloud.spend.ratePerHour ? `Now about ${usd(cloud.spend.ratePerHour)} an hour` : null,
              ...alerts.map((a) => a.text),
              'Estimates from token counts and list prices. Click for Usage & cost.',
            ]
              .filter(Boolean)
              .join('\n')}
          >
            {alerts.length ? <span aria-hidden="true">⚠</span> : null}
            <b>{usd(cloud.spend.usd)}</b>
            <span className="muted">session</span>
            {capturing && cloud.spend.ratePerHour ? <span className="muted">· {usd(cloud.spend.ratePerHour)}/h</span> : null}
          </button>
        ) : null}
        {alwaysOn && (
          <button
            type="button"
            className={`btn talk listen-chip${listen.status === 'on' ? ' live' : ''}${listen.awaiting ? ' recording' : ''}${listen.status === 'error' ? ' problem' : ''}`}
            aria-pressed={listen.status === 'on'}
            onClick={() => (listen.status === 'error' ? session.retryListening() : session.setListeningPaused(listen.status !== 'paused'))}
            title={
              listen.status === 'error'
                ? `${listen.error ?? 'The microphone could not be opened.'} Click to try again.`
                : listen.status === 'paused'
                  ? 'Always listening is paused. Click to listen again.'
                  : `Always listening${settings.wakeWord ? ': start with “Coach”, then ask' : ''}. Click to pause. The talk key still works.`
            }
          >
            <MicIcon /> {state.talk === 'idle' ? listenLabel : talkLabel}
          </button>
        )}
        {settings.voiceInput && !alwaysOn && (
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
          className={`btn toggle commentary${settings.commentary && !policy.scope.has('live') ? ' held' : ''}`}
          aria-pressed={settings.commentary}
          onClick={() => updateSettings({ commentary: !settings.commentary })}
          title={
            policy.scope.has('live')
              ? 'Live commentary: short spoken lines about what the coach sees, as it happens'
              : settings.profileModeId
                ? `Commentary cannot talk during play in this mode, so it waits for the match to end. ${policy.reasons[0]}`
                : 'The game mode is not set, so it is treated as ranked and commentary stays quiet during play. Pick your mode on the Coach screen.'
          }
        >
          🎙 Commentary{settings.commentary && !policy.scope.has('live') ? (settings.profileModeId ? ': after the match' : ': set your mode') : ''}
        </button>
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
              ['moments', 'Decisions', state.moments.length || null],
              ['ask', 'Ask the coach', state.conversation.length || null],
              ['progress', 'Progress', state.progress.practice && session.progress.currentFocus() ? '◆' : null],
              ['history', 'History', state.history.length || null],
              ['review', 'Session timeline', state.timeline.filter((e) => e.type === 'message').length || null],
              ['usage', 'Usage & cost', null],
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
            {info?.markShortcut ? (
              <>
                <br />
                Mark a moment: <kbd>{info.markShortcut}</kbd>
              </>
            ) : null}
          </div>
        </nav>

        <main ref={mainRef}>
          {state.toast && (
            <div className="toast info" role="status">
              {state.toast}
            </div>
          )}
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
              usage={usage}
              info={info}
              bridge={bridge}
              onStart={start}
              onTab={setTab}
            />
          )}
          {tab === 'insights' && <InsightsView state={state} session={session} profile={profile} settings={settings} cloud={cloud} />}
          {tab === 'moments' && <MomentsView state={state} session={session} settings={settings} cloud={cloud} info={info} onTab={setTab} />}
          {tab === 'ask' && <AskView state={state} session={session} profile={profile} settings={settings} cloud={cloud} bridge={bridge} onTab={setTab} />}
          {tab === 'progress' && <ProgressView session={session} profile={profile} settings={settings} onSettings={updateSettings} bridge={bridge} onTab={setTab} />}
          {tab === 'history' && <HistoryView state={state} session={session} profile={profile} settings={settings} onSettings={updateSettings} bridge={bridge} onTab={setTab} />}
          {tab === 'review' && <ReviewView state={state} />}
          {tab === 'usage' && (
            <UsageView
              summary={usage}
              cloud={cloud}
              onCloud={setCloud}
              bridge={bridge}
              settings={settings}
              profile={profile}
              catalog={catalog}
              capturing={capturing}
              onForgot={refreshUsage}
            />
          )}
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
              usage={usage}
              onUsage={() => setTab('usage')}
            />
          )}
          {tab === 'privacy' && <PrivacyView profile={profile} policy={policy} info={info} />}
        </main>
      </div>

      {loaded && setupOpen && !settings.onboarded && (
        <Onboarding
          bridge={bridge}
          session={session}
          info={info}
          cloud={cloud}
          onCloud={setCloud}
          settings={settings}
          onSettings={updateSettings}
          profiles={catalog.profiles}
          onDone={() => setSetupOpen(false)}
        />
      )}
      {picking && <SourcePicker bridge={bridge} onCancel={() => setPicking(false)} onPick={(id, name) => void onPicked(id, name)} />}
    </div>
  )
}
