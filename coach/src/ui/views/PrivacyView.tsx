import { useMemo } from 'react'
import type { PlatformInfo } from '../../../shared/bridge.ts'
import { resolvePolicy, statusLabel, type ResolvedPolicy } from '../../core/policy/registry.ts'
import type { GameProfile } from '../../core/profile/schema.ts'
import { PolicyBadge, ScopeChips } from '../components/viz.tsx'
import { REGISTRY, gameIdFor } from '../policy.ts'
import { DEFAULT_SETTINGS } from '../../core/settings.ts'

const ITEMS: readonly { head: string; body: string }[] = [
  {
    head: 'Pixels only',
    body: 'Sightline sees only the window or screen you pick through the system capture flow. It never reads game memory, files, network packets or processes, never injects code, and never draws over the game.',
  },
  {
    head: 'No input, no automation',
    body: 'It cannot press keys, move the mouse, aim, or act for you. Its only output is short spoken and written feedback in its own window.',
  },
  {
    head: 'Frames stay in memory',
    body: 'Frames are downscaled, analysed and discarded within moments. No video, screenshots or audio are recorded. If analysis falls behind, old frames are dropped, not queued.',
  },
  {
    head: 'Network only if you turn it on',
    body: 'By default the desktop app blocks every outgoing request. With cloud features on, sampled frames (downscaled, or only HUD crops) and your questions go to Anthropic with your own key — nowhere else. Speech uses local voices; voice input is recognised on this computer.',
  },
  {
    head: 'What is kept',
    body: 'During a session: the event timeline, in memory. Between sessions, only if you turn on long-term memory: match records, pattern counts, notes and research briefs — encrypted with your OS account, exportable and deletable at any time.',
  },
  {
    head: 'Visible facts, honest confidence',
    body: 'The coach only reasons about what was visible. It reports how sure it is, ignores moments it could not see clearly, and describes patterns as associations, never as proven causes.',
  },
]

export function PrivacyView({ profile, policy, info }: { profile: GameProfile; policy: ResolvedPolicy; info: PlatformInfo | null }) {
  // This pack's modes, each resolved through the registry as it would be today.
  const byMode = useMemo(() => {
    const now = new Date()
    return profile.modes.map((m) => ({
      mode: m,
      policy: resolvePolicy(REGISTRY, {
        gameId: gameIdFor(profile, { ...DEFAULT_SETTINGS, gameId: null }) ?? null,
        modeId: m.id,
        modeFacts: { multiplayer: m.multiplayer, competitive: m.competitive },
        tournament: false,
        now,
      }),
    }))
  }, [profile])

  return (
    <div className="page">
      <div>
        <h1>Policy & privacy</h1>
        <p className="lede">An observant coach, not a cheat. Here is exactly what it can do, where, and on what authority.</p>
      </div>

      <section className="panel" aria-labelledby="now-h">
        <div className="panel-head">
          <h2 id="now-h">Right now: {profile.game.title}</h2>
          <PolicyBadge policy={policy} />
        </div>
        <ScopeChips scope={policy.scope} />
        <ul className="plain small">
          {policy.reasons.map((r) => (
            <li key={r}>{r}</li>
          ))}
        </ul>
        {profile.depth === 'pack' && (
          <table className="table small">
            <caption className="sr-only">What the coach may do in each mode of {profile.game.title}</caption>
            <thead>
              <tr>
                <th>Mode</th>
                <th>Players</th>
                <th>Status</th>
                <th>During play</th>
              </tr>
            </thead>
            <tbody>
              {byMode.map(({ mode, policy: p }) => (
                <tr key={mode.id}>
                  <td>{mode.label}</td>
                  <td>{mode.multiplayer ? (mode.competitive ? 'Competitive multiplayer' : 'Multiplayer') : 'Single-player'}</td>
                  <td>
                    <PolicyBadge policy={p} />
                  </td>
                  <td>{p.scope.has('live') ? 'Live tips allowed (Training mode)' : 'Silent until a break or the end'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <p className="muted small" style={{ marginBottom: 0 }}>
          Your operating system’s permission to capture the screen is not the publisher’s permission to receive coaching. Unknown games
          and modes, records older than {REGISTRY.reviewMaxAgeDays} days, and tournament play all fail closed.
        </p>
      </section>

      <section className="panel" aria-labelledby="reg-h">
        <div className="panel-head">
          <h2 id="reg-h">Policy registry</h2>
          <span className="muted small">Shipped with the app · {REGISTRY.entries.length} records</span>
        </div>
        <p className="small" style={{ marginTop: 0 }}>
          {REGISTRY.note}
        </p>
        <div className="registry">
          {REGISTRY.entries.map((e) => (
            <details key={e.id} className="reg-entry">
              <summary>
                <span className="reg-game">{e.game}</span>
                <span className="muted small">{e.modes.includes('*') ? 'all modes' : e.modeClass ? 'offline single-player modes' : e.modes.join(', ')}</span>
                <span className={`badge ${e.status === 'disabled' ? 'live' : e.status === 'needs_publisher_review' ? 'warn' : e.status === 'post_match_only' ? 'info' : 'ok'}`}>{statusLabel(e.status)}</span>
                {e.basis === 'internal' ? <span className="badge neutral">Sightline rule</span> : <span className="badge info">Publisher source</span>}
              </summary>
              <div className="reg-body small">
                {e.rationale ? <p>{e.rationale}</p> : null}
                <p className="muted">{e.notes}</p>
                {e.sources.map((s) => (
                  <blockquote key={s.url}>
                    “{s.excerpt}”
                    <footer>
                      {s.title} — <span title={s.url}>{s.url}</span> ({s.verification === 'direct' ? 'read directly' : 'seen as a search excerpt; needs human confirmation'})
                    </footer>
                  </blockquote>
                ))}
                <p className="muted">
                  Reviewed {e.reviewedAt.slice(0, 10)} by {e.reviewer}.
                </p>
              </div>
            </details>
          ))}
        </div>
      </section>

      <section className="privacy-grid">
        {ITEMS.map((i) => (
          <div className="panel privacy-item" key={i.head}>
            <div className="head">
              <span aria-hidden="true" style={{ color: 'var(--accent)' }}>
                ✓
              </span>
              {i.head}
            </div>
            <div className="small" style={{ color: 'var(--text-2)' }}>
              {i.body}
            </div>
          </div>
        ))}
      </section>

      <section className="panel" aria-labelledby="limits">
        <div className="panel-head">
          <h2 id="limits">Known limits of “{profile.name}”</h2>
        </div>
        <ul className="plain small">
          {profile.limitations.map((l) => (
            <li key={l}>{l}</li>
          ))}
        </ul>
        {profile.failClosed.length > 0 && (
          <>
            <div className="label">It stops inferring when</div>
            <ul className="plain small">
              {profile.failClosed.map((l) => (
                <li key={l}>{l}</li>
              ))}
            </ul>
          </>
        )}
        {profile.knowledge.prohibitedAdvice.length > 0 && (
          <>
            <div className="label">It will never advise on</div>
            <ul className="plain small">
              {profile.knowledge.prohibitedAdvice.map((l) => (
                <li key={l}>{l}</li>
              ))}
            </ul>
          </>
        )}
      </section>

      {info?.shell === 'browser' ? (
        <p className="muted small">
          You are running the browser preview: the browser’s own share picker applies, data is kept in this browser unencrypted, and cloud
          features, voice input and the global shortcuts need the desktop app.
        </p>
      ) : null}
    </div>
  )
}
