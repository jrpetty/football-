// Progress: how the player is doing over time and what to practise.
// - Age of Empires IV with an AoE4World profile linked: the week in numbers,
//   trends (villagers at 10:00, age-up times) and win rates by civilisation,
//   matchup and map, from every public game (also those played without the coach).
// - A practice plan from the player's most frequent weaknesses (any game pack).
// - Age of Empires IV build orders: saved builds, the one to train with, and
//   how the last game went against it.
// - Counter-Strike 2: a review of a match demo, read on this PC.

import { useMemo, useState, useSyncExternalStore } from 'react'
import type { GamePlayerBuild, SightlineBridge } from '../../../shared/bridge.ts'
import { focusStatus, showValue, type PracticeFocus } from '../../core/analyses/practice.ts'
import { buildFindings, stepLabel, type BuildOrder, type StepCheck } from '../../core/aoe4/buildOrder.ts'
import { clock, trends, weeklySummary, winRates, type Trend } from '../../core/aoe4/history.ts'
import { MAX_SPLITS, RESOURCES, splitText, type Resource } from '../../core/aoe4/workers.ts'
import { itemName } from '../../core/aoe4/names.ts'
import type { GameProfile } from '../../core/profile/schema.ts'
import type { CoachSettings } from '../../core/settings.ts'
import type { Tab } from '../App.tsx'
import { Notice } from '../components/common.tsx'
import { Aoe4WorldSetup } from '../components/Accuracy.tsx'
import { DemoReviewPanel } from '../components/DemoReview.tsx'
import type { CoachSession } from '../session.ts'

interface Props {
  session: CoachSession
  profile: GameProfile
  settings: CoachSettings
  onSettings: (p: Partial<CoachSettings>) => void
  bridge: SightlineBridge
  onTab: (t: Tab) => void
}

export function ProgressView(props: Props) {
  const { session, profile, settings } = props
  const state = useSyncExternalStore(session.subscribe, session.getState)
  const aoe4 = profile.id === 'aoe4'
  const cs2 = profile.id === 'cs2'
  return (
    <div className="page">
      <header className="page-head">
        <h1>Progress</h1>
        <p className="lede">
          {aoe4
            ? 'Every public game you play, from AoE4World: your week, your trends and win rates. A practice plan from what most often goes wrong, and build orders to train with.'
            : cs2
              ? 'A review of any match demo, read on this PC, and a practice plan from what most often goes wrong in the games the coach watched.'
              : 'A practice plan from what most often goes wrong in the games the coach watched.'}
        </p>
      </header>
      {aoe4 ? <HistoryPanels {...props} /> : null}
      {cs2 ? <DemoReviewPanel session={session} profile={profile} settings={settings} onSettings={props.onSettings} bridge={props.bridge} /> : null}
      <PracticePanel session={session} profile={profile} settings={settings} />
      {aoe4 ? <BuildsPanel {...props} /> : null}
      {!state.progress.kept && (state.progress.aoe4 || state.progress.practice) ? (
        <Notice level="info">"Remember my games" is off (Settings → Memory): the history and the practice plan last until you close the app. Build orders are kept.</Notice>
      ) : null}
    </div>
  )
}

const civOf = (profile: GameProfile, id: string) => profile.knowledge.roster?.factions.find((f) => f.id === id)?.name ?? id

// --- AoE4World history ------------------------------------------------------------------------

function HistoryPanels({ session, profile, settings, onSettings, bridge }: Props) {
  const state = useSyncExternalStore(session.subscribe, session.getState)
  const { aoe4: h, sync } = state.progress
  const [civ, setCiv] = useState<string>('')
  const [by, setBy] = useState<'civ' | 'opponent' | 'map'>('opponent')
  const roster = profile.knowledge.roster
  const unit = (item: string) => itemName('unit', item, roster)
  const week = useMemo(() => (h ? weeklySummary(h, Date.now() / 1000, { civ: (id) => civOf(profile, id), unit }) : null), [h, profile])
  const series = useMemo(() => (h ? trends(h, civ || null) : []), [h, civ])
  const rows = useMemo(() => (h ? winRates(h, by) : []), [h, by])
  const myCivs = useMemo(() => [...new Set(h?.games.map((g) => g.civ) ?? [])], [h])

  if (!settings.aoe4world.profileId) {
    return (
      <section className="panel" aria-labelledby="hist-h">
        <div className="panel-head">
          <h2 id="hist-h">Your games on AoE4World</h2>
        </div>
        <p className="muted small" style={{ marginTop: 0 }}>
          Link your AoE4World profile and the coach reads every public Ranked and Quick Match game you play, also the ones without the coach running: villagers, age-up times, builds and results.
        </p>
        <Aoe4WorldSetup settings={settings} onSettings={onSettings} bridge={bridge} />
      </section>
    )
  }

  const syncing = sync.status === 'syncing'
  return (
    <>
      <section className="panel" aria-labelledby="week-h">
        <div className="panel-head">
          <h2 id="week-h">Your week</h2>
          <div className="btn-row">
            <span className="muted small">
              {settings.aoe4world.name ?? `AoE4World #${settings.aoe4world.profileId}`}
              {h?.syncedAt ? ` · read ${new Date(h.syncedAt).toLocaleString()}` : ''}
            </span>
            <button type="button" className="btn sm" disabled={syncing} onClick={() => void session.progress.sync()}>
              {syncing ? `Reading… ${sync.total ? `${sync.done} of ${sync.total}` : ''}` : 'Read my games now'}
            </button>
          </div>
        </div>
        {sync.message ? <p className={`small ${sync.status === 'error' ? 'warn-text' : 'muted'}`}>{sync.message}</p> : null}
        {!h || !h.games.length ? (
          <div className="empty small">{syncing ? 'Reading your games…' : 'No games read yet. Only public Ranked and Quick Match games are on AoE4World.'}</div>
        ) : week && week.lines.length ? (
          <ul className="week-lines">
            {week.lines.map((l) => (
              <li key={l}>{l}</li>
            ))}
          </ul>
        ) : (
          <p className="muted small">No games in the last 7 days. Trends and win rates below cover your last {h.games.length} games.</p>
        )}
      </section>

      {h && h.games.length ? (
        <div className="grid-2">
          <section className="panel" aria-labelledby="trend-h2">
            <div className="panel-head">
              <h2 id="trend-h2">Trends</h2>
              <select aria-label="Civilisation" value={civ} onChange={(e) => setCiv(e.target.value)}>
                <option value="">All civilisations</option>
                {myCivs.map((c) => (
                  <option key={c} value={c}>
                    {civOf(profile, c)}
                  </option>
                ))}
              </select>
            </div>
            {series.map((t) => (
              <TrendChart key={t.id} trend={t} />
            ))}
            <p className="muted small" style={{ marginBottom: 0 }}>
              One dot per game with a summary on AoE4World: filled won, hollow lost. The dashed line is the middle of your last 10.
            </p>
          </section>
          <section className="panel" aria-labelledby="wr-h">
            <div className="panel-head">
              <h2 id="wr-h">Win rates</h2>
              <select aria-label="Group by" value={by} onChange={(e) => setBy(e.target.value as typeof by)}>
                <option value="opponent">Against (1v1)</option>
                <option value="civ">Your civilisation</option>
                <option value="map">Map</option>
              </select>
            </div>
            {rows.length ? (
              <table className="hud-table small">
                <thead>
                  <tr>
                    <th>{by === 'map' ? 'Map' : by === 'civ' ? 'As' : 'Against'}</th>
                    <th>Games</th>
                    <th>Won</th>
                    <th>Rate</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.slice(0, 14).map((r) => (
                    <tr key={r.key}>
                      <td>{by === 'map' ? r.key : civOf(profile, r.key)}</td>
                      <td>{r.games}</td>
                      <td>{r.wins}</td>
                      <td>
                        <span className="rate-bar" aria-hidden="true">
                          <span style={{ width: `${Math.round((r.wins / r.games) * 100)}%` }} />
                        </span>{' '}
                        {Math.round((r.wins / r.games) * 100)}%{r.games < 3 ? <span className="muted"> (few games)</span> : null}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <p className="muted small">No finished games yet.</p>
            )}
          </section>
        </div>
      ) : null}
    </>
  )
}

/** One dot per game, oldest left; filled won, hollow lost; the dashed line is the median of the last 10. */
function TrendChart({ trend }: { trend: Trend }) {
  const pts = trend.points.slice(-40)
  const fmt = (v: number) => (trend.unit === 'time' ? clock(v) : String(v))
  if (pts.length < 2) {
    return (
      <div className="trend-chart">
        <div className="trend-head">
          <b>{trend.label}</b>
          <span className="muted small">{pts.length ? `${fmt(pts[0].value)} (one game)` : 'no games with a summary yet'}</span>
        </div>
      </div>
    )
  }
  const W = 560
  const H = 120
  const pad = { l: 44, r: 10, t: 8, b: 16 }
  const vals = pts.map((p) => p.value)
  let min = Math.min(...vals)
  let max = Math.max(...vals)
  if (max - min < (trend.unit === 'time' ? 30 : 4)) {
    const mid = (max + min) / 2
    min = mid - (trend.unit === 'time' ? 15 : 2)
    max = mid + (trend.unit === 'time' ? 15 : 2)
  }
  // Lower times are better: drawn higher, like more villagers.
  const y = (v: number) => {
    const f = (v - min) / (max - min)
    return pad.t + (trend.better === 'higher' ? 1 - f : f) * (H - pad.t - pad.b)
  }
  const x = (i: number) => pad.l + (i / (pts.length - 1)) * (W - pad.l - pad.r)
  const change = trend.recent !== null && trend.before !== null ? trend.recent - trend.before : null
  const better = change === null || change === 0 ? null : trend.better === 'higher' ? change > 0 : change < 0
  return (
    <figure className="trend-chart">
      <div className="trend-head">
        <b>{trend.label}</b>
        <span className="small">
          {trend.recent !== null ? `Last 10: ${fmt(trend.recent)}` : ''}
          {change !== null && trend.before !== null ? (
            <span className={better ? 'ok-text' : better === false ? 'warn-text' : 'muted'}>
              {' '}
              ({change === 0 ? 'same as' : `${trend.unit === 'time' ? `${Math.abs(change)} s` : Math.abs(change)} ${trend.unit === 'time' ? (change < 0 ? 'faster than' : 'slower than') : change > 0 ? 'more than' : 'fewer than'}`} the 10 before: {fmt(trend.before)})
            </span>
          ) : null}
        </span>
      </div>
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`${trend.label}: ${pts.length} games, from ${fmt(pts[0].value)} to ${fmt(pts[pts.length - 1].value)}`}>
        <line className="axis" x1={pad.l} x2={W - pad.r} y1={y(trend.better === 'higher' ? min : max)} y2={y(trend.better === 'higher' ? min : max)} />
        <text className="tick" x={pad.l - 6} y={y(max) + 4} textAnchor="end">
          {fmt(Math.round(max))}
        </text>
        <text className="tick" x={pad.l - 6} y={y(min) + 4} textAnchor="end">
          {fmt(Math.round(min))}
        </text>
        {trend.recent !== null ? <line className="median" x1={x(Math.max(0, pts.length - 10))} x2={W - pad.r} y1={y(trend.recent)} y2={y(trend.recent)} /> : null}
        <polyline className="trend-line" points={pts.map((p, i) => `${x(i)},${y(p.value)}`).join(' ')} />
        {pts.map((p, i) => (
          <circle key={p.gameId} className={p.result === 'win' ? 'dot won' : 'dot lost'} cx={x(i)} cy={y(p.value)} r={4}>
            <title>{`${new Date(p.startedAt * 1000).toLocaleDateString()}: ${fmt(p.value)} (${p.result === 'win' ? 'won' : p.result === 'loss' ? 'lost' : 'no result'})`}</title>
          </circle>
        ))}
      </svg>
    </figure>
  )
}

// --- Practice plan ------------------------------------------------------------------------------

function PracticePanel({ session, profile }: { session: CoachSession; profile: GameProfile; settings: CoachSettings }) {
  const state = useSyncExternalStore(session.subscribe, session.getState)
  const plan = state.progress.practice
  const candidates = useMemo(() => (plan ? [] : session.progress.candidates()), [plan, state.history, state.matches, state.progress.aoe4, profile.id])
  const [picked, setPicked] = useState<string[] | null>(null)
  const chosen = picked ?? candidates.slice(0, 3).map((c) => c.id)
  const current = plan ? session.progress.currentFocus() : null
  return (
    <section className="panel" aria-labelledby="practice-h">
      <div className="panel-head">
        <h2 id="practice-h">Practice plan</h2>
        {plan ? (
          <button type="button" className="btn sm ghost" onClick={() => void session.progress.clearPlan()}>
            {current ? 'End the plan' : 'Clear'}
          </button>
        ) : null}
      </div>
      {plan ? (
        <>
          <p className="muted small" style={{ marginTop: 0 }}>
            Started {new Date(plan.createdAt).toLocaleDateString()}. Each game you play from now on counts for the current focus, then the next one starts.
            {current ? ' The focus is said at the start of a game where live coaching is allowed, and shown on the Coach screen.' : ''}
          </p>
          <ol className="practice-list">
            {plan.foci.map((f) => (
              <FocusRow key={f.id} focus={f} current={f === current} />
            ))}
          </ol>
          {!current ? <Notice level="info">Plan done. Start a new one to see what to work on next.</Notice> : null}
          {!current ? (
            <button type="button" className="btn" onClick={() => void session.progress.clearPlan()}>
              New plan
            </button>
          ) : null}
        </>
      ) : candidates.length ? (
        <>
          <p className="muted small" style={{ marginTop: 0 }}>
            What most often holds you back in your recent games, with a target set from your own numbers. Pick up to three; each gets {candidates[0].games} games.
          </p>
          <ul className="practice-pick">
            {candidates.slice(0, 6).map((c) => (
              <li key={c.id}>
                <label className="switch-row">
                  <input
                    type="checkbox"
                    checked={chosen.includes(c.id)}
                    onChange={(e) => setPicked(e.target.checked ? [...chosen, c.id].slice(-3) : chosen.filter((x) => x !== c.id))}
                  />
                  <span>
                    <b>{c.title}</b>: {c.why} <span className="muted">Target {showValue(c.unit, c.target)}.</span>
                    <br />
                    <span className="muted small">{c.drill}</span>
                  </span>
                </label>
              </li>
            ))}
          </ul>
          <button type="button" className="btn primary" disabled={!chosen.length} onClick={() => void session.progress.startPlan(candidates.filter((c) => chosen.includes(c.id)))}>
            Start the plan
          </button>
        </>
      ) : (
        <NothingToPractise session={session} profile={profile} />
      )}
    </section>
  )
}

function NothingToPractise({ session, profile }: { session: CoachSession; profile: GameProfile }) {
  const m = session.progress.measured()
  if (m.games < 3) {
    return (
      <p className="muted small">
        Not enough games yet: a weakness is picked from 3 or more measured games (games the coach watched{profile.id === 'aoe4' ? ', or your AoE4World games' : ''}). {m.games ? `${m.games} so far.` : ''}
      </p>
    )
  }
  const u = m.usual
  const parts = [u.villagers10 !== null ? `${u.villagers10} villagers at 10:00` : null, u.feudal !== null ? `Feudal at ${clock(u.feudal)}` : null].filter(Boolean)
  return (
    <p className="muted small">
      Nothing stands out across your last {Math.min(m.games, 10)} games{parts.length ? ` (usually ${parts.join(', ')})` : ''}: no number is far enough from its mark to make a plan. Train a build order below, or check back after more games.
    </p>
  )
}

function FocusRow({ focus, current }: { focus: PracticeFocus; current: boolean }) {
  const s = focusStatus(focus)
  return (
    <li className={`practice-focus${current ? ' current' : ''}${s.done ? ' done' : ''}`}>
      <div className="btn-row">
        <b>{focus.title}</b>
        {current ? <span className="badge info">Now</span> : s.done ? <span className={`badge ${s.reachedTarget ? 'ok' : s.improved ? 'info' : 'warn'}`}>{s.reachedTarget ? 'Target reached' : s.improved ? 'Better' : 'Not better yet'}</span> : null}
      </div>
      <span className="small">{s.line}</span>
      <span className="muted small">{focus.drill}</span>
      {focus.results.length ? (
        <span className="muted small">
          Games: {focus.results.map((r) => showValue(focus.unit, r.value)).join(', ')}
        </span>
      ) : null}
    </li>
  )
}

// --- Build orders ---------------------------------------------------------------------------------

function BuildsPanel({ session, profile }: Props) {
  const state = useSyncExternalStore(session.subscribe, session.getState)
  const { builds, aoe4: h } = state.progress
  const factions = (profile.knowledge.roster?.factions ?? []).filter((f) => f.status === 'released')
  const [civ, setCiv] = useState<string>(session.progress.playerCiv() ?? factions[0]?.id ?? '')
  const [busy, setBusy] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [link, setLink] = useState('')
  const [found, setFound] = useState<{ gameId: number; players: GamePlayerBuild[] } | null>(null)
  const active = session.progress.activeBuild(civ)
  const mine = builds.builds.filter((b) => b.civ === civ)
  const myGames = (h?.games ?? []).filter((g) => g.civ === civ && h?.facts[g.gameId]?.build.length).slice(0, 30)
  const vs = active ? session.progress.lastGameAgainst(active) : null
  const playerCiv = session.progress.playerCiv()

  const run = async (what: string, f: () => Promise<string | null>) => {
    setBusy(what)
    setMessage(null)
    try {
      setMessage(await f())
    } finally {
      setBusy(null)
    }
  }

  return (
    <section className="panel" aria-labelledby="builds-h">
      <div className="panel-head">
        <h2 id="builds-h">Build orders</h2>
        <select aria-label="Civilisation" value={civ} onChange={(e) => (setCiv(e.target.value), setFound(null), setMessage(null))}>
          {factions.map((f) => (
            <option key={f.id} value={f.id}>
              {f.name}
            </option>
          ))}
        </select>
      </div>
      <p className="muted small" style={{ marginTop: 0 }}>
        A build is made from a real game: its age-ups, buildings, first units and technologies, and villager counts, with their times. Against the AI (and wherever live coaching is allowed) the coach
        calls the next steps as you play; after any game it shows where you were ahead or behind.{' '}
        {playerCiv !== civ ? <span>Your civilisation on the Coach screen is {playerCiv ? civOf(profile, playerCiv) : 'not set'}: the trainer uses the build for that one.</span> : null}
      </p>

      <div className="build-sources">
        <button type="button" className="btn" disabled={!!busy} onClick={() =>
          void run('suggest', async () => {
            const r = await session.progress.suggestFromAoe4world(civ)
            return r
          })
        }>
          {busy === 'suggest' ? 'Looking on AoE4World…' : 'Suggest one (a strong recent win)'}
        </button>
        <div className="btn-row">
          <input type="text" placeholder="Or paste an AoE4World game link" value={link} onChange={(e) => setLink(e.target.value)} style={{ flex: 1 }} />
          <button type="button" className="btn" disabled={!!busy || link.trim().length < 10} onClick={() =>
            void run('link', async () => {
              const r = await session.progress.playersInGame(link)
              if (!r.ok) return r.error
              setFound({ gameId: r.gameId, players: r.players })
              return null
            })
          }>
            {busy === 'link' ? 'Reading…' : 'Read the game'}
          </button>
        </div>
        {found ? (
          <ul className="acc-found">
            {found.players.map((p) => (
              <li key={p.profileId}>
                <button type="button" className="btn sm ghost" onClick={() => void session.progress.buildFromPlayer(found.gameId, p).then((b) => (setFound(null), setLink(''), b && setCiv(b.civ), setMessage(b ? `Saved "${b.name}" and set it for ${civOf(profile, b.civ)}.` : null)))}>
                  Use {p.name}'s build ({civOf(profile, p.civ)}, {p.result === 'win' ? 'won' : p.result === 'loss' ? 'lost' : 'no result'})
                </button>
              </li>
            ))}
          </ul>
        ) : null}
        {myGames.length ? (
          <div className="btn-row">
            <select aria-label="One of my games" value="" onChange={(e) => e.target.value && void session.progress.buildFromMyGame(Number(e.target.value)).then((b) => setMessage(b ? `Saved "${b.name}".` : null))}>
              <option value="">From one of my games…</option>
              {myGames.map((g) => {
                const f = h!.facts[g.gameId]
                return (
                  <option key={g.gameId} value={g.gameId}>
                    {new Date(g.startedAt * 1000).toLocaleDateString()} · {g.result === 'win' ? 'won' : 'lost'}
                    {g.opponents.length === 1 ? ` vs ${civOf(profile, g.opponents[0])}` : ''}
                    {f.ages.feudal ? ` · Feudal ${clock(f.ages.feudal)}` : ''}
                    {typeof f.villagers[600] === 'number' ? ` · ${f.villagers[600]} villagers at 10:00` : ''}
                  </option>
                )
              })}
            </select>
          </div>
        ) : null}
      </div>
      {message ? <p className="small muted">{message}</p> : null}

      {active ? (
        <div className="build-active">
          <div className="btn-row">
            <b>Training with: {active.name}</b>
            <button type="button" className="btn sm ghost" onClick={() => void session.progress.setActive(civ, null)}>
              Stop using it
            </button>
          </div>
          {vs ? <BuildComparison label={vs.label} checks={vs.checks} /> : <p className="muted small">Play a game as {civOf(profile, civ)} to see it against this build.</p>}
          <BuildSteps build={active} />
          <UpgradeTimings session={session} build={active} />
          <SplitEditor key={active.id} session={session} build={active} />
        </div>
      ) : (
        <p className="muted small">No build set for {civOf(profile, civ)}.</p>
      )}

      {mine.length ? (
        <>
          <h3 className="small">Saved for {civOf(profile, civ)}</h3>
          <ul className="build-list">
            {mine.map((b) => (
              <li key={b.id}>
                <span>
                  {b.name}
                  {b.from?.rating ? <span className="muted small"> · rating {b.from.rating}</span> : null}
                </span>
                <div className="btn-row">
                  {active?.id === b.id ? (
                    <span className="badge ok">In use</span>
                  ) : (
                    <button type="button" className="btn sm" onClick={() => void session.progress.setActive(civ, b.id)}>
                      Train with it
                    </button>
                  )}
                  <button type="button" className="btn sm ghost" onClick={() => void session.progress.deleteBuild(b.id)}>
                    Delete
                  </button>
                </div>
              </li>
            ))}
          </ul>
        </>
      ) : null}
    </section>
  )
}

/** Wheelbarrow, Textiles and the Blacksmith: your last game's times against the build and the game it came from. */
function UpgradeTimings({ session, build }: { session: CoachSession; build: BuildOrder }) {
  const u = session.progress.upgradesAgainst(build)
  if (!u) return null
  const cell = (t: number | null, ref: number | null) => {
    if (t === null) return <span className="muted">–</span>
    const late = ref !== null && t - ref >= 45
    const early = ref !== null && ref - t >= 45
    return <span className={late ? 'warn-text' : early ? 'ok-text' : ''}>{clock(t)}</span>
  }
  return (
    <div className="upgrade-timings">
      <h3 className="small">Upgrade timings</h3>
      <p className="muted small" style={{ marginTop: 0 }}>
        {u.label}, from its AoE4World summary (after the match only). Against the build's steps and the whole game it came from
        {u.sourceLabel ? ` (${u.sourceLabel})` : ''}, which goes on past the build's last step. Red: 45 s or more later than the build (or, without a build step, their game).
      </p>
      {u.rows.length ? (
        <div className="table-scroll">
          <table className="table upgrade-table">
            <thead>
              <tr>
                <th>Upgrade</th>
                <th>Where</th>
                <th>You</th>
                <th>The build</th>
                <th>Their game</th>
              </tr>
            </thead>
            <tbody>
              {u.rows.map((r) => (
                <tr key={r.upgrade.id}>
                  <td>{r.upgrade.name}</td>
                  <td className="muted">{r.upgrade.at}</td>
                  <td>{cell(r.yours, r.build ?? r.source)}</td>
                  <td>{r.build !== null ? clock(r.build) : <span className="muted">–</span>}</td>
                  <td>{r.source !== null ? clock(r.source) : <span className="muted">{build.upgrades ? '–' : 'not kept'}</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <p className="muted small">No Wheelbarrow, Textiles or Blacksmith upgrades in that game or in the build.</p>
      )}
      {u.findings.length ? (
        <ul className="week-lines">
          {u.findings.map((f) => (
            <li key={f}>{f}</li>
          ))}
        </ul>
      ) : null}
      {!build.upgrades && build.from ? <p className="muted small">This build was saved before upgrade times were kept: make it again from the same game to see their game's times.</p> : null}
    </div>
  )
}

const parseClock = (s: string): number | null => {
  const m = /^\s*(\d{1,2}):([0-5]\d)\s*$/.exec(s)
  return m ? Number(m[1]) * 60 + Number(m[2]) : null
}

/** The villagers on each resource the build wants by a few times; checked live against the HUD's numbers. */
function SplitEditor({ session, build }: { session: CoachSession; build: BuildOrder }) {
  const [rows, setRows] = useState<{ at: string; food: number; wood: number; gold: number; stone: number }[]>(() => (build.splits ?? []).map((s) => ({ at: clock(s.atSec), food: s.food, wood: s.wood, gold: s.gold, stone: s.stone })))
  const [note, setNote] = useState<string | null>(null)
  const until = Math.max(600, ...build.steps.map((s) => s.atSec))
  const fromGame = session.progress.splitsFromLastGame(build.civ, until)
  const against = session.progress.splitsAgainst(build)
  const bad = rows.some((r) => parseClock(r.at) === null)
  const set = (i: number, k: 'at' | Resource, v: string) => setRows(rows.map((r, j) => (j === i ? { ...r, [k]: k === 'at' ? v : Math.max(0, Math.min(200, Math.round(Number(v) || 0))) } : r)))
  const save = async () => {
    await session.progress.setSplits(build.id, rows.map((r) => ({ atSec: parseClock(r.at)!, food: r.food, wood: r.wood, gold: r.gold, stone: r.stone })))
    setNote('Saved. It is checked from the next game.')
  }
  return (
    <div className="split-editor">
      <h3 className="small">Villagers on each resource</h3>
      <p className="muted small" style={{ marginTop: 0 }}>
        What the build wants by a few times. At each time, against the AI, the coach compares it with the numbers beside your resources and says the biggest shortfall ("Build wants 6 on wood by 4:00, you have 3"). Needs the game's
        "Show Advanced Resource" UI option.
      </p>
      {rows.length ? (
        <table className="table split-table">
          <thead>
            <tr>
              <th>By</th>
              {RESOURCES.map((r) => (
                <th key={r}>{r[0].toUpperCase() + r.slice(1)}</th>
              ))}
              <th aria-label="Remove" />
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={i}>
                <td>
                  <input type="text" aria-label="Time (m:ss)" value={r.at} onChange={(e) => set(i, 'at', e.target.value)} className={parseClock(r.at) === null ? 'invalid' : ''} size={5} />
                </td>
                {RESOURCES.map((k) => (
                  <td key={k}>
                    <input type="number" min={0} max={200} aria-label={`Villagers on ${k}`} value={r[k]} onChange={(e) => set(i, k, e.target.value)} />
                  </td>
                ))}
                <td>
                  <button type="button" className="btn sm ghost" onClick={() => setRows(rows.filter((_, j) => j !== i))}>
                    Remove
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      <div className="btn-row">
        <button type="button" className="btn sm" disabled={rows.length >= MAX_SPLITS} onClick={() => setRows([...rows, { at: rows.length ? clock((parseClock(rows[rows.length - 1].at) ?? 0) + 120) : '4:00', food: 0, wood: 0, gold: 0, stone: 0 }])}>
          Add a time
        </button>
        {fromGame ? (
          <button type="button" className="btn sm ghost" onClick={() => (setRows(fromGame.splits.map((s) => ({ at: clock(s.atSec), food: s.food, wood: s.wood, gold: s.gold, stone: s.stone }))), setNote(`From your game on ${new Date(fromGame.endedAt).toLocaleDateString()}: what was read every 2 minutes. Save to keep it.`))}>
            Take it from my last game
          </button>
        ) : null}
        <button type="button" className="btn sm primary" disabled={bad} onClick={() => void save()}>
          Save
        </button>
      </div>
      {note ? <p className="small muted">{note}</p> : null}
      {against ? (
        <div className="small">
          <b>Your last game the coach watched ({new Date(against.endedAt).toLocaleDateString()})</b>
          <ul style={{ margin: '4px 0 0', paddingLeft: 18 }}>
            {against.checks.map((c) => (
              <li key={c.want.atSec}>
                {c.line ?? `${clock(c.want.atSec)}: ${c.had ? `on plan (${splitText(c.had)})` : 'not read then'}`}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  )
}

function BuildSteps({ build }: { build: BuildOrder }) {
  const [open, setOpen] = useState(false)
  return (
    <details className="build-steps" open={open} onToggle={(e) => setOpen((e.target as HTMLDetailsElement).open)}>
      <summary className="small">The {build.steps.length} steps</summary>
      <ol className="small">
        {build.steps.map((s, i) => (
          <li key={i}>
            <span className="tl-time">{clock(s.atSec)}</span> {stepLabel(s)}
          </li>
        ))}
      </ol>
    </details>
  )
}

const STATUS_TEXT: Record<StepCheck['status'], string> = { early: 'early', 'on time': 'on time', late: 'late', missing: 'not made', 'not known': '—' }

function BuildComparison({ label, checks }: { label: string; checks: readonly StepCheck[] }) {
  const findings = buildFindings(checks)
  return (
    <div className="build-compare">
      <span className="muted small">{label}</span>
      <ul className="week-lines">
        {findings.map((f) => (
          <li key={f}>{f}</li>
        ))}
      </ul>
      <table className="hud-table small">
        <thead>
          <tr>
            <th>Step</th>
            <th>The build</th>
            <th>You</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {checks.map((c, i) => (
            <tr key={i} className={`step-${c.status.replace(' ', '-')}`}>
              <td>{stepLabel(c.step)}</td>
              <td>{clock(c.step.atSec)}</td>
              <td>{c.step.kind === 'villagers' ? (c.actualCount ?? '—') : c.actualSec !== null ? clock(c.actualSec) : '—'}</td>
              <td>{STATUS_TEXT[c.status]}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
