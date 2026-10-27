import { useState } from 'react'
import type { PlatformInfo } from '../../../shared/bridge.ts'
import type { CloudStatus } from '../../../shared/cloud.ts'
import type { EffectiveMode } from '../../core/policy/governor.ts'
import type { ResolvedPolicy } from '../../core/policy/registry.ts'
import type { GameProfile } from '../../core/profile/schema.ts'
import { COACHING_MODES, MODE_INFO, TONE_INFO, TONES, type CoachSettings } from '../../core/settings.ts'
import type { SignalState } from '../../core/types.ts'
import { estimatePerHour, modelPrice } from '../../core/vision/cost.ts'
import { builtInProfile } from '../../profiles/index.ts'
import type { ProfileCatalog, Tab } from '../App.tsx'
import { AlignmentCheck } from '../components/AlignmentCheck.tsx'
import { AskBox, Conversation } from '../components/ask.tsx'
import { Meter, Notice, Segmented, Switch } from '../components/common.tsx'
import { Breakdown, EconomyPanel, PolicyBadge, Scoreboard, ScopeChips, StatePanel, ZoneMap } from '../components/viz.tsx'
import { CATEGORY_TEXT, clock, formatMs, pct, usd, WINDOW_TEXT } from '../format.ts'
import { REGISTRY_GAMES } from '../policy.ts'
import type { Caption, CoachSession, SessionState, SourceChoice } from '../session.ts'

interface Props {
  state: SessionState
  session: CoachSession
  settings: CoachSettings
  onSettings: (patch: Partial<CoachSettings>) => void
  catalog: ProfileCatalog
  profile: GameProfile
  policy: ResolvedPolicy
  effective: EffectiveMode
  cloud: CloudStatus
  info: PlatformInfo | null
  onStart: (kind: SourceChoice, speed?: number) => void
  onTab: (t: Tab) => void
}

export function CoachView(p: Props) {
  return p.state.phase === 'capturing' ? <LiveView {...p} /> : <SetupView {...p} />
}

const cloudReady = (c: CloudStatus) => c.available && c.settings.enabled && c.key.set

// --- Before capture -------------------------------------------------------------------

function SetupView({ state, session, settings, onSettings, catalog, profile, policy, effective, cloud, onStart, onTab }: Props) {
  const mode = profile.modes.find((m) => m.id === settings.profileModeId) ?? null
  const context = settings.context[profile.id] ?? {}
  const visionPack = !!profile.vision
  const est = visionPack
    ? estimatePerHour(cloud.settings.visionModel, profile.vision!.sampleIntervalMs, profile.vision!.maxWidth, Math.round((profile.vision!.maxWidth * 9) / 16))
    : 0

  const demo = (kind: SourceChoice, packId: string, speed: number) => {
    const pack = builtInProfile(packId)
    const modeId = pack.modes.some((m) => m.id === settings.profileModeId) ? settings.profileModeId : pack.modes[0].id
    const next = { ...settings, profileId: pack.id, profileModeId: modeId }
    if (pack.id !== profile.id || modeId !== settings.profileModeId) onSettings({ profileId: pack.id, profileModeId: modeId })
    void session.start(kind, pack, next, cloud, { speed })
  }

  return (
    <div className="page">
      <header className="hero">
        <div>
          <h1>Your coach, watching the game with you</h1>
          <p className="lede">
            Sightline reads only the window you share, remembers what it sees across games, and speaks when it has evidence —
            within what each game's publisher allows.
          </p>
        </div>
      </header>

      {state.error ? <Notice level="error">{state.error}</Notice> : null}
      {state.saved && state.report ? <LastSession state={state} onTab={onTab} /> : null}

      <div className="setup-grid">
        <section className="panel span-2" aria-labelledby="step-game">
          <div className="panel-head">
            <div className="step">
              <span className="step-num">1</span>
              <h2 id="step-game">What are you playing?</h2>
            </div>
          </div>
          <div className="pack-cards" role="radiogroup" aria-label="Game pack">
            {catalog.profiles.map((x) => (
              <button
                key={x.id}
                type="button"
                role="radio"
                aria-checked={x.id === profile.id}
                className="pack-card"
                onClick={() => onSettings({ profileId: x.id, profileModeId: null })}
              >
                <span className="pack-title">{x.depth === 'generic' ? 'Any other game' : x.game.title}</span>
                <span className="pack-sub">{x.depth === 'generic' ? 'Basic visible-state tracking' : x.name}</span>
                <span className="pack-tags">
                  <span className={`badge ${x.depth === 'pack' ? 'ok' : 'neutral'}`}>{x.depth === 'pack' ? 'Deep pack' : 'Generic'}</span>
                  {x.vision ? <span className="badge info">Cloud vision</span> : null}
                  {x.id === 'synthetic-arena' ? <span className="badge neutral">Demo</span> : null}
                </span>
              </button>
            ))}
          </div>
          <div className="grid-2" style={{ marginTop: 16 }}>
            {profile.depth === 'generic' && (
              <div className="field">
                <label htmlFor="game-id">Which game is it?</label>
                <select id="game-id" value={settings.gameId ?? ''} onChange={(e) => onSettings({ gameId: e.target.value || null })}>
                  <option value="">Not listed</option>
                  {REGISTRY_GAMES.filter((g) => !catalog.profiles.some((p) => p.id === g.id)).map((g) => (
                    <option key={g.id} value={g.id}>
                      {g.title}
                    </option>
                  ))}
                </select>
                <span className="muted small">Used only to look up what the publisher allows.</span>
              </div>
            )}
            <div className="field">
              <label htmlFor="game-mode">Mode</label>
              <select id="game-mode" value={settings.profileModeId ?? ''} onChange={(e) => onSettings({ profileModeId: e.target.value || null })}>
                <option value="">Not sure (treated as competitive)</option>
                {profile.modes.map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.label}
                  </option>
                ))}
              </select>
              {mode ? (
                <span className="btn-row small">
                  <span className="badge neutral">{mode.multiplayer ? 'Multiplayer' : 'Single-player'}</span>
                  {mode.competitive ? <span className="badge warn">Competitive</span> : <span className="badge neutral">Casual / offline</span>}
                </span>
              ) : null}
            </div>
            {(profile.playerContext ?? []).map((c) => (
              <div key={c.id} className="field">
                <label htmlFor={`ctx-${c.id}`}>{c.label}</label>
                {c.kind === 'choice' ? (
                  <Segmented
                    label={c.label}
                    value={String(context[c.id] ?? c.default)}
                    options={(c.options ?? []).map((o) => ({ value: o, label: o.charAt(0).toUpperCase() + o.slice(1) }))}
                    onChange={(v) => onSettings({ context: { ...settings.context, [profile.id]: { ...context, [c.id]: v } } })}
                  />
                ) : (
                  <input
                    id={`ctx-${c.id}`}
                    type={c.kind === 'number' ? 'number' : 'text'}
                    value={String(context[c.id] ?? c.default)}
                    maxLength={40}
                    onChange={(e) =>
                      onSettings({
                        context: { ...settings.context, [profile.id]: { ...context, [c.id]: c.kind === 'number' ? Number(e.target.value) || 0 : e.target.value } },
                      })
                    }
                  />
                )}
                <span className="muted small">{c.help}</span>
              </div>
            ))}
          </div>
          <details className="limits">
            <summary className="small muted">What this pack can and cannot see</summary>
            <ul className="plain small">
              {profile.limitations.map((l) => (
                <li key={l}>{l}</li>
              ))}
            </ul>
          </details>
        </section>

        <section className="panel policy-panel" aria-labelledby="policy-h">
          <div className="panel-head">
            <h2 id="policy-h">What the coach may do here</h2>
            <PolicyBadge policy={policy} />
          </div>
          <ScopeChips scope={policy.scope} />
          <p className="small">{policy.reasons[0]}</p>
          {policy.entry ? (
            <p className="muted small">
              Source: {policy.entry.basis === 'publisher' ? `${policy.entry.publisher} published material` : 'Sightline’s own conservative rule (not publisher approval)'} · reviewed{' '}
              {policy.entry.reviewedAt.slice(0, 10)}
            </p>
          ) : null}
          <Switch
            checked={settings.tournament}
            onChange={(tournament) => onSettings({ tournament })}
            label="Tournament or event play"
            hint="Switches every kind of coaching output off. Event rules can be stricter than publishers’."
          />
        </section>

        <section className="panel span-2" aria-labelledby="step-mode">
          <div className="panel-head">
            <div className="step">
              <span className="step-num">2</span>
              <h2 id="step-mode">How should it coach?</h2>
            </div>
          </div>
          <div className="modes" role="radiogroup" aria-labelledby="step-mode">
            {COACHING_MODES.map((m) => (
              <button key={m} type="button" role="radio" aria-checked={settings.mode === m} className="mode-card" onClick={() => onSettings({ mode: m })}>
                <span className="title">
                  {MODE_INFO[m].label}
                  {MODE_INFO[m].live ? <span className="badge live">Live</span> : null}
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
          <div className="btn-row" style={{ marginTop: 14 }}>
            <Segmented label="Coach tone" value={settings.tone} onChange={(tone) => onSettings({ tone })} options={TONES.map((t) => ({ value: t, label: TONE_INFO[t].label }))} />
            <span className="muted small">{TONE_INFO[settings.tone].sample}</span>
          </div>
        </section>

        <section className="panel" aria-labelledby="eyes-h">
          <div className="panel-head">
            <h2 id="eyes-h">How it sees</h2>
          </div>
          {visionPack ? (
            cloudReady(cloud) ? (
              <>
                <p className="small">
                  <span className="badge ok">Cloud vision on</span> Every {formatMs(profile.vision!.sampleIntervalMs)}, one{' '}
                  {profile.vision!.crops?.length ? 'set of HUD crops' : 'downscaled frame'} goes to {modelPrice(cloud.settings.visionModel).label.split(' (')[0]} with your key.
                </p>
                <p className="muted small">
                  About {usd(est)} per hour of play (estimate). Session cap {usd(cloud.settings.sessionCapUsd)}.
                </p>
              </>
            ) : (
              <>
                <p className="small">
                  {profile.game.title} is read by a vision model (scores, minimap, banners). That needs the desktop app, your Anthropic API key, and cloud vision switched on.
                </p>
                <button type="button" className="btn sm" onClick={() => onTab('settings')}>
                  Set up cloud vision
                </button>
                <p className="muted small">Until then, try the synthetic replay below to see what you will get.</p>
              </>
            )
          ) : profile.signals.length ? (
            <p className="small">Reads this pack’s HUD bars and markers on this computer. No network.</p>
          ) : (
            <p className="small">The generic pack cannot read a HUD by itself; tell it round and match results with the buttons during play.</p>
          )}
        </section>
      </div>

      <section className="panel start-panel" aria-labelledby="step-start">
        <div className="panel-head">
          <div className="step">
            <span className="step-num">3</span>
            <h2 id="step-start">Start</h2>
          </div>
        </div>
        <div className="start-row">
          <button type="button" className="btn primary lg" onClick={() => onStart('screen')} disabled={state.phase === 'starting'}>
            Choose the game window
          </button>
          <span className="muted small">
            Your system asks what to share. Prefer the game window to a whole screen. Frames are analysed in memory and never saved.
          </span>
        </div>
        <div className="demo-row">
          <span className="label">Or watch a demo (synthetic, no game needed):</span>
          <button type="button" className="btn" onClick={() => demo('replay-fc', 'ea-fc', 2)} disabled={state.phase === 'starting'}>
            ⚽ FC match replay
          </button>
          <button type="button" className="btn" onClick={() => demo('replay-aoe', 'aoe2-de', 2)} disabled={state.phase === 'starting'}>
            🏰 Age of Empires II replay
          </button>
          <button type="button" className="btn" onClick={() => demo('simulation', 'synthetic-arena', 2)} disabled={state.phase === 'starting'}>
            🎯 Arena simulation
          </button>
        </div>
      </section>
    </div>
  )
}

function LastSession({ state, onTab }: { state: SessionState; onTab: (t: Tab) => void }) {
  const saved = state.saved!
  const r = state.report!
  return (
    <section className="panel last-session">
      <div className="panel-head">
        <h2>Last session</h2>
        <div className="btn-row">
          <button type="button" className="btn sm" onClick={() => onTab('insights')}>
            Insights
          </button>
          <button type="button" className="btn sm" onClick={() => onTab('review')}>
            Timeline
          </button>
          <button type="button" className="btn sm" onClick={() => onTab('ask')}>
            Ask about it
          </button>
        </div>
      </div>
      <div className="stat-row">
        {r.score ? <Stat n={`${r.score.player}–${r.score.opponent}`} l="Last score read" /> : <Stat n={`${r.record.won}–${r.record.lost}`} l="Rounds won–lost" />}
        <Stat n={String(r.matches.length)} l="Matches recorded" />
        <Stat n={String(r.mistakes.reduce((s, m) => s + m.totals.count, 0))} l="Mistakes counted" />
        <Stat n={String(r.insights.filter((i) => i.insight.grade !== 'insufficient').length)} l="Patterns" />
      </div>
      <p className="muted small" style={{ marginBottom: 0 }}>
        {saved.tendencies || saved.matches
          ? `Saved for next time: ${[saved.tendencies ? 'pattern counts' : '', saved.matches ? `${saved.matches} match record${saved.matches > 1 ? 's' : ''}` : ''].filter(Boolean).join(' and ')} (encrypted).`
          : 'Nothing was kept: long-term memory is off. Turn it on in Settings to compare across games.'}
      </p>
    </section>
  )
}

// --- During capture -------------------------------------------------------------------

function LiveView({ state, session, settings, onSettings, profile, policy, effective, cloud, info }: Props) {
  const [aligning, setAligning] = useState(false)
  const st = state.status
  const report = state.report?.profileId === profile.id ? state.report : null
  const now = session.now()
  const latest = state.captions.at(-1) ?? null
  const warnings = latestWarnings(state)
  const isFc = (profile.heatmaps ?? []).some((h) => h.zoneSet === 'goal-area')
  const isAoe = (profile.vision?.state ?? []).some((s) => s.key === 'populationCap')
  const context = { ...Object.fromEntries((profile.playerContext ?? []).map((c) => [c.id, c.default])), ...(settings.context[profile.id] ?? {}) }
  const minute = st?.visible.values.matchMinute?.value

  return (
    <div className="page live">
      <div className="live-head">
        <div>
          <h1>{profile.game.title}</h1>
          <p className="lede">
            {MODE_INFO[effective.mode].label} ·{' '}
            {effective.liveActive
              ? 'brief live tips are on'
              : policy.scope.has('live')
                ? 'quiet during play — switch to Training coach for brief live tips'
                : 'this game and mode allow no live tips — notes come at breaks or after the match'}
          </p>
        </div>
        <div className="btn-row">
          {profile.signals.length > 0 && state.source?.kind === 'screen' ? (
            <button type="button" className="btn sm" onClick={() => setAligning(true)}>
              Check HUD alignment
            </button>
          ) : null}
          <select aria-label="Coaching mode" value={settings.mode} onChange={(e) => onSettings({ mode: e.target.value as CoachSettings['mode'] })}>
            {COACHING_MODES.map((m) => (
              <option key={m} value={m}>
                {MODE_INFO[m].label}
              </option>
            ))}
          </select>
        </div>
      </div>

      {state.error ? <Notice level="error">{state.error}</Notice> : null}
      {state.source?.surface === 'monitor' ? (
        <Notice level="warning">You are sharing a whole screen. Everything on it is analysed (notifications, other apps). Stop and share the game window for better privacy and accuracy.</Notice>
      ) : null}
      {warnings.map((w) => (
        <Notice key={w} level="warning">
          {w}
        </Notice>
      ))}

      <div className="status-strip" role="status">
        <StatusItem label="Moment" value={st ? WINDOW_TEXT[st.window] : '—'} tone={st?.window === 'during_play' ? 'live' : 'info'} />
        <StatusItem label="Policy" value={st ? <PolicyBadge policy={{ status: st.policy.status, scope: st.policy.scope }} /> : '—'} />
        <StatusItem label="Seeing" value={st ? (st.visible.stale ? 'Stale — not inferring' : `Fresh · ${pct(st.visible.confidence)}`) : 'Starting…'} tone={st?.visible.stale ? 'warn' : 'ok'} />
        {st?.vision.enabled ? (
          <StatusItem
            label="Cloud vision"
            value={`${st.vision.sampler.sent} frames · ${(st.vision.latencyMs / 1000).toFixed(1)} s · ${usd(state.visionCostUsd)}`}
            tone={st.vision.lastError ? 'warn' : 'ok'}
          />
        ) : state.source?.kind === 'replay' ? (
          <StatusItem label="Source" value="Synthetic replay" tone="info" />
        ) : (
          <StatusItem label="Frames" value={st ? `${st.framesProcessed} · ${st.analysisMs.toFixed(1)} ms` : '0'} />
        )}
        {st?.vision.screen ? (
          <StatusItem label="Screen" value={`${st.vision.screen.name}${st.vision.screen.from === 'memory' ? ' · known' : ''}`} tone={st.vision.screen.kind === 'gameplay' ? 'live' : 'info'} />
        ) : null}
        <StatusItem label="Time" value={state.startedAt ? clock(now - state.clockOrigin) : '0:00'} />
      </div>

      <div className="live-grid">
        <div className="live-main">
          <CaptionHero caption={latest} onQuiet={() => session.quiet()} muted={settings.muted} />

          {isFc && (
            <section className="panel" aria-labelledby="fc-h">
              <div className="panel-head">
                <h2 id="fc-h">Match</h2>
                <Scoreboard score={st?.score ?? null} minute={typeof minute === 'number' ? minute : null} team={String(context.teamName ?? '')} />
              </div>
              <div className="heatmaps">
                {(report?.heatmaps ?? []).map((h) => (
                  <div key={h.spec.id} className="heatmap-tile">
                    <div className="hm-title">{h.spec.label}</div>
                    <ZoneMap heatmap={h} />
                    <Breakdown heatmap={h} />
                  </div>
                ))}
                {!report?.heatmaps.length && <div className="empty small">Zones appear after the first goal or lost ball.</div>}
              </div>
            </section>
          )}

          {isAoe && st && (
            <section className="panel" aria-labelledby="eco-h">
              <div className="panel-head">
                <h2 id="eco-h">Economy</h2>
                <span className="muted small">From the visible top bar</span>
              </div>
              <EconomyPanel visible={st.visible} context={context} />
            </section>
          )}

          {profile.signals.length > 0 && (
            <section className="panel" aria-labelledby="hud-h">
              <div className="panel-head">
                <h2 id="hud-h">HUD readings</h2>
                <span className={`badge ${st?.hudReadable === false ? 'warn' : 'ok'}`}>{st?.hudReadable === false ? 'Unreadable' : 'Readable'}</span>
              </div>
              <div className="signal-list">
                {profile.signals.map((spec) => (
                  <SignalRow key={spec.id} label={spec.label} state={st?.signals.find((s) => s.id === spec.id)} />
                ))}
              </div>
            </section>
          )}

          <div className="grid-2">
            {(profile.mistakes ?? []).length > 0 && (
              <section className="panel" aria-labelledby="mis-h">
                <div className="panel-head">
                  <h2 id="mis-h">Mistakes this session</h2>
                </div>
                <ul className="mistakes">
                  {(report?.mistakes ?? (profile.mistakes ?? []).map((spec) => ({ spec, totals: { count: 0, totalMs: 0, active: false } }))).map((m) => (
                    <li key={m.spec.id} className={m.totals.active ? 'active' : m.totals.count ? 'seen' : ''}>
                      <span className={`sev ${m.spec.severity}`} aria-hidden="true" />
                      <span className="m-label">{m.spec.label}</span>
                      {m.totals.active ? <span className="badge live">Now</span> : null}
                      <span className="num">{m.totals.count}×</span>
                      <span className="muted small">{m.totals.totalMs ? formatMs(m.totals.totalMs) : ''}</span>
                    </li>
                  ))}
                </ul>
              </section>
            )}
            <section className="panel" aria-labelledby="vs-h">
              <div className="panel-head">
                <h2 id="vs-h">What I can see</h2>
              </div>
              {st ? <StatePanel visible={st.visible} profile={profile} now={now} /> : <div className="empty small">Starting…</div>}
            </section>
          </div>
        </div>

        <aside className="live-side">
          <section className="panel" aria-labelledby="ask-h">
            <div className="panel-head">
              <h2 id="ask-h">Ask the coach</h2>
            </div>
            <AskBox state={state} session={session} profile={profile} settings={settings} cloud={cloud} compact />
            <Conversation entries={state.conversation} limit={3} />
          </section>

          <section className="panel" aria-labelledby="cap-h">
            <div className="panel-head">
              <h2 id="cap-h">Coach said</h2>
              <span className="muted small">{state.captions.length}</span>
            </div>
            {state.captions.length <= 1 ? (
              <div className="empty small">Earlier messages appear here.</div>
            ) : (
              <ol className="captions">
                {state.captions
                  .slice(0, -1)
                  .reverse()
                  .slice(0, 8)
                  .map((c) => (
                    <li key={c.id}>
                      <span className="tl-time">{clock(c.at - state.clockOrigin)}</span>
                      <span>{c.text}</span>
                    </li>
                  ))}
              </ol>
            )}
          </section>

          <section className="panel" aria-labelledby="mom-h">
            <div className="panel-head">
              <h2 id="mom-h">Decisions to review</h2>
              <span className="muted small">{state.moments.length}</span>
            </div>
            <button type="button" className="btn mark-btn" onClick={() => void session.markMoment()}>
              ◆ Mark this moment {info?.markShortcut ? <kbd className="kbd-inline">{info.markShortcut}</kbd> : null}
            </button>
            {state.moments.length ? (
              <div className="moment-strip">
                {state.moments.slice(-4).map((m) =>
                  m.imageUrl ? <img key={m.id} src={m.imageUrl} alt="Marked moment" /> : <span key={m.id} className="moment-dot" title={m.note ?? 'Marked moment'} />,
                )}
              </div>
            ) : (
              <p className="muted small" style={{ marginBottom: 0 }}>
                Mark a fight, a build or a rotation; after the match the coach reviews what was on screen.
              </p>
            )}
          </section>

          <section className="panel" aria-labelledby="ctl-h">
            <div className="panel-head">
              <h2 id="ctl-h">Tell the coach</h2>
            </div>
            <p className="muted small">If the screen didn’t show it clearly:</p>
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
          </section>
        </aside>
      </div>

      {aligning ? <AlignmentCheck session={session} profile={profile} onClose={() => setAligning(false)} /> : null}
    </div>
  )
}

/** The most recent warnings from the engine, while they are recent. */
function latestWarnings(state: SessionState): string[] {
  const out: string[] = []
  for (let i = state.timeline.length - 1; i >= 0 && out.length < 2; i--) {
    const e = state.timeline[i]
    if (e.type === 'notice' && e.level === 'warning' && !out.includes(e.text)) out.push(e.text)
    if (state.timeline.length - i > 40) break
  }
  return out
}

const SPEECH_TEXT: Record<Caption['speech'], string> = {
  waiting: 'About to speak',
  speaking: 'Speaking…',
  finished: 'Spoken',
  interrupted: 'Interrupted',
  expired: 'Dropped: the moment passed',
  unavailable: 'No local voice; shown as text',
  shown: 'Shown, not spoken',
}

function CaptionHero({ caption, onQuiet, muted }: { caption: Caption | null; onQuiet: () => void; muted: boolean }) {
  if (!caption) {
    return (
      <section className="caption-hero idle" aria-live="polite">
        <div className="eq" aria-hidden="true">
          <span />
          <span />
          <span />
        </div>
        <div>
          <div className="quote">Watching quietly.</div>
          <div className="muted small">I speak when I have seen something enough times to be useful, and only when your mode and the game allow it.</div>
        </div>
      </section>
    )
  }
  const speaking = caption.speech === 'speaking' || caption.speech === 'waiting'
  return (
    <section className={`caption-hero${speaking ? ' speaking' : ''}`} aria-live="polite">
      <div className="caption-meta">
        <span className="badge neutral">{caption.requestedByPlayer ? 'Your question' : CATEGORY_TEXT[caption.category]}</span>
        {caption.author === 'ai' ? <span className="badge info">AI-phrased</span> : null}
        <span className="muted small">{muted && caption.speech === 'shown' ? 'Muted' : SPEECH_TEXT[caption.speech]}</span>
        <span style={{ flex: 1 }} />
        {speaking ? (
          <button type="button" className="btn sm" onClick={onQuiet}>
            Quiet
          </button>
        ) : null}
      </div>
      <div className="quote">“{caption.text}”</div>
      <div className="why">
        <b>Based on:</b> {caption.evidence}
      </div>
    </section>
  )
}

function StatusItem({ label, value, tone }: { label: string; value: React.ReactNode; tone?: 'ok' | 'warn' | 'live' | 'info' }) {
  return (
    <div className={`status-item${tone ? ` ${tone}` : ''}`}>
      <span className="label">{label}</span>
      <span className="value">{value}</span>
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

export function Stat({ n, l }: { n: string; l: string }) {
  return (
    <div className="stat">
      <div className="n">{n}</div>
      <div className="l">{l}</div>
    </div>
  )
}

