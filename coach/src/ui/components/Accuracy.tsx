// "Is the coach reading your games right?": what it read, checked against the
// real game. After a match: AoE4World's summary of it (Age of Empires IV,
// opt-in) or the player's own numbers from the post-game statistics; during
// play: the screen readings against the game's own data (Counter-Strike 2) and
// against this PC's reading of the HUD.

import { useState } from 'react'
import type { MatchRecord } from '../../core/analyses/matchRecorder.ts'
import type { GameProfile } from '../../core/profile/schema.ts'
import type { CoachSettings } from '../../core/settings.ts'
import type { TruthReport } from '../../core/truth/aoe4world.ts'
import type { SightlineBridge } from '../../../shared/bridge.ts'
import { dateShort, labelOf } from '../format.ts'
import type { CoachSession, SessionState } from '../session.ts'
import { Notice, Switch } from './common.tsx'

const pct = (a: number, b: number) => (b ? `${Math.round((a / b) * 100)}%` : '—')

function judged(t: TruthReport): { right: number; total: number } {
  const j = t.lines.filter((l) => l.ok !== null)
  return { right: j.filter((l) => l.ok).length, total: j.length }
}

export function AccuracyPanel(props: { state: SessionState; session: CoachSession; profile: GameProfile; settings: CoachSettings; onSettings: (p: Partial<CoachSettings>) => void; bridge: SightlineBridge; matches: readonly MatchRecord[] }) {
  const { state, session, profile, settings, matches } = props
  const aoe4 = profile.id === 'aoe4'
  const checked = matches.filter((m) => m.truth)
  const totals = checked.reduce((acc, m) => {
    const j = judged(m.truth!)
    return { right: acc.right + j.right, total: acc.total + j.total }
  }, { right: 0, total: 0 })
  const readings: Record<string, [number, number]> = {}
  for (const m of checked) for (const [k, [r, n]] of Object.entries(m.truth!.readings)) readings[k] = [(readings[k]?.[0] ?? 0) + r, (readings[k]?.[1] ?? 0) + n]
  const live = state.report?.profileId === profile.id ? (state.report.checks ?? []) : []
  const hud = state.status?.vision.hud ?? null
  const benchKeys = new Set((profile.benchmarks ?? []).map((b) => b.id))
  const recent = [...matches].reverse().slice(0, 8)
  const label = (key: string) => profile.vision?.state.find((s) => s.key === key)?.label ?? labelOf(key)

  return (
    <section className="panel" aria-labelledby="acc-h">
      <div className="panel-head">
        <h2 id="acc-h">Is the coach reading your games right?</h2>
        {checked.length ? <span className={`badge ${totals.total && totals.right / totals.total >= 0.9 ? 'ok' : 'warn'}`}>{`${totals.right} of ${totals.total} right`}</span> : null}
      </div>
      <p className="muted small" style={{ marginTop: 0 }}>
        What the coach read, checked against what really happened.{' '}
        {aoe4 ? 'After a match: AoE4World’s summary of the game (exact villagers, age-ups and resources), or your own numbers from the post-game statistics.' : ''}
        {profile.gameState ? ' During play: the screen readings against the game’s own data.' : ''}
      </p>

      {aoe4 ? <Aoe4WorldSetup {...props} /> : null}

      {checked.length ? (
        <div className="acc-summary">
          <div>
            <b>{pct(totals.right, totals.total)}</b>
            <span className="muted small">
              checkpoints and age-ups right over {checked.length} checked {checked.length === 1 ? 'match' : 'matches'}
            </span>
          </div>
          {Object.entries(readings).map(([k, [r, n]]) => (
            <div key={k}>
              <b>{pct(r, n)}</b>
              <span className="muted small">
                {label(k)} readings right ({r} of {n}, every 20 s)
              </span>
            </div>
          ))}
        </div>
      ) : null}

      {live.length || hud ? (
        <table className="hud-table small">
          <thead>
            <tr>
              <th>This session</th>
              <th>Checked against</th>
              <th>Agreed</th>
              <th>Last difference (screen / other)</th>
            </tr>
          </thead>
          <tbody>
            {live.map((c) => (
              <tr key={`${c.against}-${c.key}`}>
                <td>{label(c.key)}</td>
                <td>{c.against === 'game data' ? 'the game’s own data' : 'this PC’s HUD reading'}</td>
                <td>
                  {c.agreed} of {c.compared} ({pct(c.agreed, c.compared)})
                </td>
                <td>{c.lastMiss ? `${c.lastMiss[0]} / ${c.lastMiss[1]}` : '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}

      {recent.length ? (
        <ul className="acc-matches">
          {recent.map((m) => (
            <MatchCheck key={m.id} match={m} session={session} profile={profile} settings={settings} hasCheckpoints={benchKeys.size > 0} />
          ))}
        </ul>
      ) : (
        <p className="muted small">Matches appear here once one is recorded.</p>
      )}
    </section>
  )
}

export function Aoe4WorldSetup({ settings, onSettings, bridge }: { settings: CoachSettings; onSettings: (p: Partial<CoachSettings>) => void; bridge: SightlineBridge }) {
  const a = settings.aoe4world
  const [query, setQuery] = useState('')
  const [found, setFound] = useState<{ profileId: number; name: string; country: string | null }[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const search = async () => {
    setBusy(true)
    setError(null)
    // A profile number or a link to it is used as it is.
    const id = /(?:players\/)?(\d{3,10})/.exec(query.trim())?.[1]
    if (id && /^\D*\d+\D*$/.test(query.trim())) {
      onSettings({ aoe4world: { profileId: Number(id), name: null, auto: true } })
      setBusy(false)
      return
    }
    const r = await bridge.aoe4worldSearch(query).catch(() => ({ ok: false as const, error: 'Could not reach AoE4World.' }))
    setBusy(false)
    if (!r.ok) setError(r.error)
    else setFound(r.players)
  }
  return (
    <div className="acc-setup">
      {a.profileId ? (
        <div className="btn-row small">
          <span>
            AoE4World profile: <b>{a.name ?? a.profileId}</b>
            {a.name ? <span className="muted"> ({a.profileId})</span> : null}
          </span>
          <button type="button" className="btn sm ghost" onClick={() => onSettings({ aoe4world: { profileId: null, name: null, auto: true } })}>
            Change
          </button>
        </div>
      ) : (
        <>
          <div className="btn-row">
            <input type="text" placeholder="Your in-game name, or your AoE4World profile number or link" value={query} onChange={(e) => setQuery(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && void search()} style={{ flex: 1 }} />
            <button type="button" className="btn" disabled={busy || query.trim().length < 3} onClick={() => void search()}>
              {busy ? 'Looking…' : 'Find me on AoE4World'}
            </button>
          </div>
          <span className="muted small">Only public Ranked and Quick Match games are on AoE4World. Searching sends the name you type; after that only your profile number is sent, and only when a match is checked.</span>
          {error ? <Notice level="warning">{error}</Notice> : null}
          {found ? (
            found.length ? (
              <ul className="acc-found">
                {found.map((p) => (
                  <li key={p.profileId}>
                    <button type="button" className="btn sm ghost" onClick={() => onSettings({ aoe4world: { profileId: p.profileId, name: p.name, auto: true } })}>
                      {p.name} {p.country ? <span className="muted">({p.country.toUpperCase()})</span> : null} <span className="muted">#{p.profileId}</span>
                    </button>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="muted small">No player by that name.</p>
            )
          ) : null}
        </>
      )}
      {a.profileId ? (
        <Switch
          checked={a.auto}
          onChange={(auto) => onSettings({ aoe4world: { ...a, auto } })}
          label={<b>Check each match automatically</b>}
          hint="A few minutes after a match (AoE4World needs that long to build its summary), never during one."
        />
      ) : null}
    </div>
  )
}

function MatchCheck({ match, session, profile, settings, hasCheckpoints }: { match: MatchRecord; session: CoachSession; profile: GameProfile; settings: CoachSettings; hasCheckpoints: boolean }) {
  const [open, setOpen] = useState(false)
  const [entering, setEntering] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [numbers, setNumbers] = useState<Record<number, string>>({})
  const t = match.truth
  const j = t ? judged(t) : null
  const checkpoints = profile.benchmarks?.[0]?.checkpointsSec.filter((c) => c <= match.durationSec + 60) ?? []
  return (
    <li className="acc-match">
      <div className="btn-row small">
        <span>
          {dateShort(match.endedAt)} · <span className={`result ${match.result}`}>{match.result === 'unknown' ? 'Not read' : labelOf(match.result)}</span>
        </span>
        {t && j ? (
          <button type="button" className={`badge ${j.total && j.right === j.total ? 'ok' : 'warn'}`} onClick={() => setOpen(!open)} aria-expanded={open}>
            {j.right} of {j.total} right ({t.source === 'aoe4world' ? 'AoE4World' : 'your numbers'})
          </button>
        ) : (
          <span className="muted">not checked</span>
        )}
        <div style={{ flex: 1 }} />
        {profile.id === 'aoe4' && settings.aoe4world.profileId ? (
          <button
            type="button"
            className="btn sm"
            disabled={busy}
            onClick={() => {
              setBusy(true)
              setMessage(null)
              void session.checkWithAoe4world(match.id).then((r) => {
                setBusy(false)
                setMessage(r.message)
                if (r.ok) setOpen(true)
              })
            }}
          >
            {busy ? 'Checking…' : 'Check with AoE4World'}
          </button>
        ) : null}
        {hasCheckpoints && checkpoints.length ? (
          <button type="button" className="btn sm ghost" onClick={() => setEntering(!entering)}>
            Enter my numbers
          </button>
        ) : null}
      </div>
      {message ? <p className="small muted">{message}</p> : null}
      {entering ? (
        <form
          className="acc-enter"
          onSubmit={(e) => {
            e.preventDefault()
            const real = Object.fromEntries(Object.entries(numbers).flatMap(([k, v]) => (v.trim() && Number.isFinite(Number(v)) ? [[Number(k), Math.round(Number(v))]] : [])))
            if (!Object.keys(real).length) return
            void session.enterRealNumbers(match.id, real).then(() => {
              setEntering(false)
              setOpen(true)
            })
          }}
        >
          <span className="small muted">Villagers from the post-game statistics:</span>
          {checkpoints.map((c) => (
            <label key={c} className="small">
              at {Math.floor(c / 60)}:00{' '}
              <input type="number" min={0} max={300} value={numbers[c] ?? ''} onChange={(e) => setNumbers({ ...numbers, [c]: e.target.value })} style={{ width: 70 }} />
            </label>
          ))}
          <button type="submit" className="btn sm primary">
            Compare
          </button>
        </form>
      ) : null}
      {open && t ? (
        <table className="hud-table small">
          <thead>
            <tr>
              <th>What</th>
              <th>The coach read</th>
              <th>Really</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {t.lines.map((l) => (
              <tr key={l.what}>
                <td>{l.what}</td>
                <td>{l.coach ?? <span className="muted">not read</span>}</td>
                <td>{l.real}</td>
                <td>{l.ok === null ? '' : l.ok ? '✓' : '✗'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </li>
  )
}
