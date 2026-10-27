import { useState } from 'react'
import type { EffectiveMode } from '../../core/policy.ts'
import { modeAllowsLiveCoaching, type GameProfile, type ProfileMode } from '../../core/profile/schema.ts'
import { COACHING_MODES, MODE_INFO, TONE_INFO, TONES, type CoachSettings } from '../../core/settings.ts'
import type { SignalState } from '../../core/types.ts'
import { BUILT_IN_PROFILES } from '../../profiles/index.ts'
import type { ProfileCatalog } from '../App.tsx'
import { AlignmentCheck } from '../components/AlignmentCheck.tsx'
import { GradeBadge, Meter, Notice, Segmented } from '../components/common.tsx'
import { DECISION_TEXT, pct } from '../format.ts'
import type { CoachSession, SessionState, SpokenState } from '../session.ts'

interface Props {
  state: SessionState
  session: CoachSession
  settings: CoachSettings
  onSettings: (patch: Partial<CoachSettings>) => void
  catalog: ProfileCatalog
  profile: GameProfile
  effective: EffectiveMode
  onStart: () => void
  simulator: boolean
}

export function CoachView(p: Props) {
  return p.state.phase === 'capturing' ? <LiveView {...p} /> : <SetupView {...p} />
}

// --- Before capture -------------------------------------------------------------------

function SetupView({ state, session, settings, onSettings, catalog, profile, effective, onStart, simulator }: Props) {
  const arena = BUILT_IN_PROFILES.find((x) => x.id === 'synthetic-arena')!
  // The simulation always uses the Synthetic Arena profile, whatever is selected.
  const startSim = (speed: number) => {
    if (profile.id !== arena.id) onSettings({ profileId: arena.id, profileModeId: null })
    void session.start('simulation', arena, { ...settings, profileId: arena.id }, speed)
  }

  return (
    <div className="page">
      <div>
        <h1>Set up your coach</h1>
        <p className="lede">
          Sightline watches only what you choose to share, learns from what it visibly sees you do, and speaks only when it
          has evidence and your settings allow it.
        </p>
      </div>

      {state.error ? <Notice level="error">{state.error}</Notice> : null}
      {state.savedTendencies !== null && state.timeline.length > 0 ? (
        <Notice level="info">
          Session ended. {state.savedTendencies ? 'Tendency counts were saved for next time.' : 'Nothing was saved (long-term memory is off).'}{' '}
          See <b>Session review</b> for what the coach observed and why it spoke or stayed quiet.
        </Notice>
      ) : null}

      <section className="panel" aria-labelledby="step-game">
        <div className="panel-head">
          <div className="step">
            <span className="step-num">1</span>
            <h2 id="step-game">Game profile and mode</h2>
          </div>
          <ValidationBadge profile={profile} />
        </div>
        <div className="grid-2">
          <div className="field">
            <label htmlFor="profile">Game profile</label>
            <select
              id="profile"
              value={profile.id}
              onChange={(e) => {
                const next = catalog.profiles.find((x) => x.id === e.target.value)!
                onSettings({ profileId: next.id, profileModeId: null })
              }}
            >
              {catalog.profiles.map((x) => (
                <option key={x.id} value={x.id}>
                  {x.name}
                </option>
              ))}
            </select>
            <span className="muted small">
              {profile.game.title} · patch {profile.game.patch} · profile v{profile.version}
            </span>
          </div>
          <div className="field">
            <label htmlFor="game-mode">What are you playing?</label>
            <select id="game-mode" value={settings.profileModeId ?? ''} onChange={(e) => onSettings({ profileModeId: e.target.value || null })}>
              <option value="">Not sure (treated as competitive)</option>
              {profile.modes.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.label}
                </option>
              ))}
            </select>
            <ModeFacts mode={profile.modes.find((m) => m.id === settings.profileModeId) ?? null} />
          </div>
        </div>
        <details style={{ marginTop: 14 }}>
          <summary className="small muted">What this profile can and cannot see</summary>
          <ul className="plain small" style={{ marginTop: 8 }}>
            {profile.limitations.map((l) => (
              <li key={l}>{l}</li>
            ))}
          </ul>
        </details>
      </section>

      <section className="panel" aria-labelledby="step-mode">
        <div className="panel-head">
          <div className="step">
            <span className="step-num">2</span>
            <h2 id="step-mode">Coaching mode</h2>
          </div>
        </div>
        <div className="modes" role="radiogroup" aria-labelledby="step-mode">
          {COACHING_MODES.map((m) => (
            <button key={m} type="button" role="radio" aria-checked={settings.mode === m} className="mode-card" onClick={() => onSettings({ mode: m })}>
              <span className="title">
                {MODE_INFO[m].label}
                {MODE_INFO[m].live ? <span className="badge live">Live tips</span> : null}
                {m === 'competitive-safe' ? <span className="badge ok">Default</span> : null}
              </span>
              <span className="desc">{MODE_INFO[m].summary}</span>
            </button>
          ))}
        </div>
        {effective.downgradeReason ? (
          <div style={{ marginTop: 12 }}>
            <Notice level="warning">{effective.downgradeReason}</Notice>
          </div>
        ) : null}
      </section>

      <section className="panel" aria-labelledby="step-tone">
        <div className="panel-head">
          <div className="step">
            <span className="step-num">3</span>
            <h2 id="step-tone">Coach tone</h2>
          </div>
        </div>
        <div className="btn-row">
          <Segmented label="Coach tone" value={settings.tone} onChange={(tone) => onSettings({ tone })} options={TONES.map((t) => ({ value: t, label: TONE_INFO[t].label }))} />
          <span className="muted">{TONE_INFO[settings.tone].sample}</span>
        </div>
      </section>

      <section className="panel" aria-labelledby="step-start">
        <div className="panel-head">
          <div className="step">
            <span className="step-num">4</span>
            <h2 id="step-start">Start</h2>
          </div>
        </div>
        <div className="btn-row">
          <button type="button" className="btn primary lg" onClick={onStart} disabled={state.phase === 'starting'}>
            Choose a window and start
          </button>
          {simulator ? (
            <>
              <button type="button" className="btn" onClick={() => startSim(1)}>
                Try the simulation
              </button>
              <button type="button" className="btn ghost" onClick={() => startSim(4)}>
                Simulation at 4×
              </button>
            </>
          ) : null}
        </div>
        <p className="muted small" style={{ marginBottom: 0 }}>
          Your system will ask what to share. Sightline sees only that window's pixels: never game memory, files, input, or
          network traffic. Frames are analysed in memory and discarded; none are saved.
          {simulator ? ' The simulation draws a fictional HUD (Synthetic Arena) to show how the coach behaves; it is not a real game.' : ''}
        </p>
      </section>
    </div>
  )
}

function ValidationBadge({ profile }: { profile: GameProfile }) {
  const v = profile.validation.status
  return <span className={`badge ${v === 'verified' ? 'ok' : v === 'community' ? 'warn' : 'neutral'}`}>{v === 'reference' ? 'Reference profile' : v === 'community' ? 'Community profile' : 'Verified profile'}</span>
}

function ModeFacts({ mode }: { mode: ProfileMode | null }) {
  if (!mode) return <span className="muted small">Live tips stay off until you say what you are playing.</span>
  const live = modeAllowsLiveCoaching(mode)
  return (
    <span className="btn-row small">
      <span className="badge neutral">{mode.multiplayer ? 'Multiplayer' : 'Single-player'}</span>
      {mode.competitive ? <span className="badge warn">Competitive</span> : null}
      <span className={`badge ${live ? 'ok' : 'neutral'}`}>{live ? 'Live tips allowed' : 'Reflective feedback only'}</span>
      {mode.liveCoaching.note ? <span className="muted">{mode.liveCoaching.note}</span> : null}
    </span>
  )
}

// --- During capture -------------------------------------------------------------------

function LiveView({ state, session, settings, onSettings, profile, effective }: Props) {
  const [aligning, setAligning] = useState(false)
  const st = state.status
  const lastNotice = [...state.timeline].reverse().find((e) => e.type === 'notice')

  return (
    <div className="page">
      <div className="btn-row" style={{ justifyContent: 'space-between' }}>
        <div>
          <h1>Coaching: {profile.game.title}</h1>
          <p className="lede">{MODE_INFO[effective.mode].summary}</p>
        </div>
        <button type="button" className="btn" onClick={() => setAligning(true)}>
          Check HUD alignment
        </button>
      </div>

      {state.error ? <Notice level="error">{state.error}</Notice> : null}
      {st && !st.hudReadable ? (
        <Notice level="warning">Cannot read the HUD right now (menu, loading, spectating, or wrong window?). Nothing is inferred until it is readable.</Notice>
      ) : lastNotice && lastNotice.type === 'notice' && lastNotice.level === 'warning' ? (
        <Notice level="warning">{lastNotice.text}</Notice>
      ) : null}

      <div className="grid-3">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
          <section aria-label="Latest coaching message" aria-live="polite">
            {state.lastMessage ? <MessageCard m={state.lastMessage} onQuiet={() => session.quiet()} /> : (
              <div className="empty">
                Watching quietly. The coach speaks only when it has seen a situation enough times to say something useful, and
                only when your mode allows.
              </div>
            )}
          </section>

          <section className="panel" aria-labelledby="seeing">
            <div className="panel-head">
              <h2 id="seeing">What I can see</h2>
              <span className={`badge ${st?.hudReadable === false ? 'warn' : 'ok'}`}>{st?.hudReadable === false ? 'HUD unreadable' : 'HUD readable'}</span>
            </div>
            <div className="signal-list">
              {profile.signals.map((spec) => (
                <SignalRow key={spec.id} label={spec.label} state={st?.signals.find((s) => s.id === spec.id)} />
              ))}
            </div>
          </section>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
          <section className="panel" aria-labelledby="sess">
            <div className="panel-head">
              <h2 id="sess">Session</h2>
              <span className="muted small">{state.source?.kind === 'simulation' ? 'Simulation' : 'Screen capture'}</span>
            </div>
            <div className="stat-row">
              <Stat n={st ? `${st.record.won}–${st.record.lost}` : '–'} l="Rounds won–lost" />
              <Stat n={st ? String(st.round.index) : '–'} l={st?.round.active ? 'Round (live)' : 'Round'} />
              <Stat n={st ? String(st.framesProcessed) : '0'} l="Frames analysed" />
            </div>
            <p className="muted small" style={{ marginBottom: 0 }}>
              {st ? `${st.analysisMs.toFixed(1)} ms per frame · ${st.queue.dropped + state.framesSkipped} frames skipped to stay current · ` : ''}
              frames are never stored.
            </p>
          </section>

          <section className="panel" aria-labelledby="ctl">
            <div className="panel-head">
              <h2 id="ctl">Controls</h2>
            </div>
            <div className="field" style={{ marginBottom: 14 }}>
              <label htmlFor="live-mode">Coaching mode</label>
              <select id="live-mode" value={settings.mode} onChange={(e) => onSettings({ mode: e.target.value as CoachSettings['mode'] })}>
                {COACHING_MODES.map((m) => (
                  <option key={m} value={m}>
                    {MODE_INFO[m].label}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <span className="label">If the game's HUD doesn't show it, tell the coach yourself</span>
              <div className="btn-row">
                <button type="button" className="btn sm" onClick={() => session.markBoundary('round-end', 'win')}>
                  Round won
                </button>
                <button type="button" className="btn sm" onClick={() => session.markBoundary('round-end', 'loss')}>
                  Round lost
                </button>
                <button type="button" className="btn sm" onClick={() => session.markBoundary('match-end', 'unknown')}>
                  Match over
                </button>
              </div>
            </div>
          </section>
        </div>
      </div>

      {aligning ? <AlignmentCheck session={session} profile={profile} onClose={() => setAligning(false)} /> : null}
    </div>
  )
}

function MessageCard({ m, onQuiet }: { m: SpokenState; onQuiet: () => void }) {
  const speechText: Record<SpokenState['speech'], string> = {
    waiting: 'About to speak',
    speaking: 'Speaking…',
    finished: m.decision.decision === 'spoken' ? 'Spoken' : DECISION_TEXT[m.decision.decision],
    interrupted: 'Interrupted',
    expired: 'Dropped: the moment passed',
    unavailable: 'No local voice available; shown as text',
  }
  return (
    <div className="message-card">
      <div className="meta">
        <span className="badge neutral">{m.message.kind === 'live-cue' ? 'Live tip' : m.message.kind === 'round-review' ? 'Round review' : 'Match summary'}</span>
        <GradeBadge grade={m.message.grade} />
        <span>{speechText[m.speech]}</span>
        <span className="spacer" style={{ flex: 1 }} />
        {m.speech === 'speaking' || m.speech === 'waiting' ? (
          <button type="button" className="btn sm" onClick={onQuiet}>
            Quiet
          </button>
        ) : null}
      </div>
      <div className="quote">“{m.message.text}”</div>
      <div className="why">
        <b>Why {m.decision.decision === 'spoken' ? 'it spoke' : 'it stayed quiet'}:</b> {m.decision.reasons.join('; ')}.
      </div>
    </div>
  )
}

function SignalRow({ label, state }: { label: string; state: SignalState | undefined }) {
  if (!state || !state.known) {
    return (
      <div className="signal">
        <span>{label}</span>
        <Meter value={0} label={`${label}: cannot read`} />
        <span className="value muted">can't read</span>
      </div>
    )
  }
  return (
    <div className="signal">
      <span>{label}</span>
      {state.kind === 'number' ? (
        <Meter value={state.value} label={label} />
      ) : (
        <span>
          <span className={`badge ${state.value ? 'ok' : 'neutral'}`}>{state.value ? 'Visible' : 'Not visible'}</span>
        </span>
      )}
      <span className="value" title={`Visual confidence ${pct(state.confidence)}`}>
        {state.kind === 'number' ? pct(state.value) : ''} <span className="muted small">({pct(state.confidence)})</span>
      </span>
    </div>
  )
}

function Stat({ n, l }: { n: string; l: string }) {
  return (
    <div className="stat">
      <div className="n">{n}</div>
      <div className="l">{l}</div>
    </div>
  )
}
