// Coaching views: the coach's review, how fights are won and lost (from
// killcam/replay tags), numbers against the clock, and the playbook of what
// good players do. All read structured data only.

import { useMemo, useState } from 'react'
import type { BenchmarkResult } from '../../core/analyses/benchmarks.ts'
import { expectedAt } from '../../core/analyses/benchmarks.ts'
import type { FactorResult } from '../../core/analyses/factors.ts'
import type { BattleRecord } from '../../core/analyses/battles.ts'
import type { IntelReport, PastSeen } from '../../core/analyses/intel.ts'
import { clock, dimensionValues, learnedFrom, plansSeen, type Learned, type LearnedTrack, type LearnSource } from '../../core/analyses/learning.ts'
import type { GameProfile, PlaybookEntry } from '../../core/profile/schema.ts'
import type { MatchReview } from '../../core/review.ts'
import { gameClock } from '../format.ts'
import { GradeBadge } from './common.tsx'

// --- Review ------------------------------------------------------------------------------

export function ReviewCard({ review, book, onPlaybook }: { review: MatchReview; book: ReadonlyMap<string, PlaybookEntry>; onPlaybook: (id: string) => void }) {
  return (
    <div className="review">
      <p className="review-headline">{review.headline}</p>
      {review.strengths.length > 0 && (
        <ul className="review-strengths" aria-label="What went well">
          {review.strengths.map((s) => (
            <li key={s}>{s}</li>
          ))}
        </ul>
      )}
      <ol className="review-focus">
        {review.focus.map((f, i) => (
          <li key={i} className="focus-item">
            <span className="focus-num" aria-hidden="true">
              {i + 1}
            </span>
            <div>
              <p className="focus-evidence">{f.evidence}</p>
              {f.principle ? <p className="muted small">{f.principle}</p> : null}
              {f.fix ? (
                <p className="focus-fix">
                  <span className="label">Fix</span> {f.fix}
                </p>
              ) : null}
              {f.drill ? (
                <p className="small">
                  <span className="label">Drill</span> {f.drill}
                </p>
              ) : null}
              {f.playbook && book.has(f.playbook) ? (
                <button type="button" className="link-btn small" onClick={() => onPlaybook(f.playbook!)}>
                  Why, and where this comes from: {book.get(f.playbook)!.title} →
                </button>
              ) : null}
            </div>
          </li>
        ))}
      </ol>
      <p className="review-next">{review.nextMatch}</p>
    </div>
  )
}

// --- Fights ---------------------------------------------------------------------------------

export function FactorCard({ result, onPlaybook }: { result: FactorResult; onPlaybook: (id: string) => void }) {
  const r = result
  const compare = !!r.spec.winEvent
  const rows = r.rows.filter((x) => x.losses + x.wins > 0)
  const max = Math.max(1, ...rows.map((x) => (compare ? x.losses + x.wins : x.losses)))
  const total = r.taggedLosses
  const flagged = new Map(r.findings.map((f) => [f.value, f]))
  return (
    <article className="factor">
      <div className="factor-head">
        <h3>{r.spec.label}</h3>
        <span className="muted small">
          {compare ? `${r.taggedLosses} lost · ${r.taggedWins} won` : `${total} ${plural(r.spec.lossNoun ?? 'deaths', total)}`}
          {r.untagged ? ` · ${r.untagged} not readable` : ''}
        </span>
      </div>
      {rows.length === 0 ? (
        <p className="muted small">Nothing readable yet.</p>
      ) : (
        <div className="factor-rows">
          {rows.map((x) => {
            const f = flagged.get(x.value)
            return (
              <div key={x.value} className={`factor-row${f ? ` ${f.kind}` : ''}`}>
                <span className="factor-label">{x.label}</span>
                <span className="factor-bar" aria-hidden="true">
                  <span className="lost" style={{ width: `${(x.losses / max) * 100}%` }} />
                  {compare ? <span className="won" style={{ width: `${(x.wins / max) * 100}%` }} /> : null}
                </span>
                <span className="factor-n num">{compare ? `${x.losses}–${x.wins}` : `${x.losses} of ${total}`}</span>
              </div>
            )
          })}
        </div>
      )}
      {r.findings.map((f) => (
        <p key={`${f.kind}-${f.value}`} className={`factor-finding ${f.kind}`}>
          <GradeBadge grade={f.grade} /> {f.statement}{' '}
          {f.playbook ? (
            <button type="button" className="link-btn" onClick={() => onPlaybook(f.playbook!)}>
              What to do →
            </button>
          ) : null}
        </p>
      ))}
      {compare ? (
        <div className="factor-legend small muted" aria-hidden="true">
          <span className="key lost" /> lost <span className="key won" /> won
        </div>
      ) : null}
    </article>
  )
}

/** "deaths" → "death", "goals conceded" → "goal conceded" for one. */
function plural(noun: string, n: number): string {
  return n === 1 ? noun.replace(/^(\w+?)s\b/, '$1') : noun
}

// --- Battles ------------------------------------------------------------------------------------

const OUTCOME_LABEL = { won: 'Won', lost: 'Lost', unclear: 'Unclear' } as const

/** One row per fight followed: when, result, army before and at its lowest, and what the frames showed. */
export function BattleLog({ battles, profile }: { battles: readonly BattleRecord[]; profile: GameProfile }) {
  const fight = profile.vision?.events.find((e) => e.type === profile.battles?.event)
  // Only circumstances the coach compares (e.g. not "no enemy siege").
  const label = (key: string, value: string) => profile.factors?.find((x) => x.attribute === key)?.values.find((v) => v.value === value)?.label ?? null
  const keys = (fight?.attributes ?? []).map((a) => a.key).filter((k) => k !== profile.battles?.trendAttribute)
  return (
    <div className="table-wrap">
      <table className="table battles">
        <thead>
          <tr>
            <th>Time</th>
            <th>Result</th>
            <th className="num">Army</th>
            <th>What the frames showed</th>
          </tr>
        </thead>
        <tbody>
          {battles.map((b) => (
            <tr key={b.startAt}>
              <td className="num">{b.gameSec !== null ? gameClock(b.gameSec) : '—'}</td>
              <td>
                <span className={`badge ${b.outcome === 'won' ? 'ok' : b.outcome === 'lost' ? 'live' : 'neutral'}`}>{OUTCOME_LABEL[b.outcome]}</span>
              </td>
              <td className="num">{b.armyBefore !== null && b.armyLow !== null ? `${b.armyBefore} → ${b.armyLow}` : '—'}</td>
              <td className="small">
                {[...keys, 'ending']
                  .map((k) => (b.attributes[k] ? label(k, b.attributes[k]) : null))
                  .filter(Boolean)
                  .join(' · ') || <span className="muted">Nothing clear</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// --- Benchmarks -------------------------------------------------------------------------------

export function BenchmarkChart({ result }: { result: BenchmarkResult }) {
  const s = result.spec
  const end = Math.max(...s.checkpointsSec, ...result.points.map((p) => p.atSec)) + 60
  const refs = s.reference ?? []
  const top = Math.max(expectedAt(s, end), ...refs.map((r) => r.value), ...result.points.map((p) => p.actual)) * 1.1
  const W = 560
  const H = 220
  const pad = { l: 36, r: 12, t: 12, b: 26 }
  const x = (sec: number) => pad.l + (sec / end) * (W - pad.l - pad.r)
  const y = (v: number) => H - pad.b - (v / top) * (H - pad.t - pad.b)
  const curve = Array.from({ length: 41 }, (_, i) => (i / 40) * end)
    .map((t, i) => `${i ? 'L' : 'M'}${x(t).toFixed(1)},${y(expectedAt(s, t)).toFixed(1)}`)
    .join(' ')
  const ticks = [0, Math.round(top / 2), Math.round(top)]
  return (
    <figure className="bench">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`${s.label}: your readings against ${s.expectedPhrase}`}>
        {ticks.map((v) => (
          <g key={v}>
            <line className="grid" x1={pad.l} x2={W - pad.r} y1={y(v)} y2={y(v)} />
            <text className="axis" x={pad.l - 6} y={y(v) + 4} textAnchor="end">
              {v}
            </text>
          </g>
        ))}
        {s.checkpointsSec.map((c) => (
          <text key={c} className="axis" x={x(c)} y={H - 8} textAnchor="middle">
            {gameClock(c)}
          </text>
        ))}
        <path className="expected" d={curve} />
        {refs.map((r) => (
          <circle key={r.atSec} className="ref" cx={x(r.atSec)} cy={y(r.value)} r={4.5}>
            <title>{`${r.label}: about ${r.value} at ${gameClock(r.atSec)}`}</title>
          </circle>
        ))}
        {result.points.map((p) => (
          <g key={p.atSec}>
            <line className="gap" x1={x(p.atSec)} x2={x(p.atSec)} y1={y(p.actual)} y2={y(p.expected)} />
            <circle className={`actual${p.behind ? ' behind' : ''}`} cx={x(p.atSec)} cy={y(p.actual)} r={5.5}>
              <title>{`${gameClock(p.atSec)}: ${p.actual} (expected ${p.expected})`}</title>
            </circle>
          </g>
        ))}
      </svg>
      <figcaption className="bench-legend small muted">
        <span className="key expected" /> {s.expectedPhrase} <span className="key actual" /> you
        {refs.length ? (
          <>
            <span className="key ref" /> {refs[0].label}
          </>
        ) : null}
      </figcaption>
      {result.worst ? <p className="small">{result.worst.statement}</p> : result.points.length ? <p className="small">On track at every checkpoint read.</p> : null}
    </figure>
  )
}

// --- Playbook ----------------------------------------------------------------------------------

const KIND_LABEL = { pro: 'Pro', coach: 'Coach', official: 'Official', guide: 'Guide', forum: 'Forum' } as const

export function Playbook({ profile, linked, open, onToggle }: { profile: GameProfile; linked: ReadonlySet<string>; open: string | null; onToggle: (id: string | null) => void }) {
  const book = profile.knowledge.playbook ?? []
  const tags = useMemo(() => [...new Set(book.flatMap((e) => e.tags[0] ? [e.tags[0]] : []))], [book])
  const [tag, setTag] = useState<string | null>(null)
  const shown = book.filter((e) => !tag || e.tags.includes(tag)).sort((a, b) => Number(linked.has(b.id)) - Number(linked.has(a.id)))
  return (
    <div className="playbook">
      <div className="filters" role="group" aria-label="Filter the playbook">
        <button type="button" className="chip" aria-pressed={tag === null} onClick={() => setTag(null)}>
          All {book.length}
        </button>
        {tags.map((t) => (
          <button key={t} type="button" className="chip" aria-pressed={tag === t} onClick={() => setTag(t)}>
            {t.replace(/-/g, ' ')}
          </button>
        ))}
      </div>
      {shown.map((e) => (
        <details key={e.id} id={`pb-${e.id}`} className={`play${linked.has(e.id) ? ' linked' : ''}`} open={open === e.id} onToggle={(ev) => (ev.currentTarget.open ? onToggle(e.id) : open === e.id && onToggle(null))}>
          <summary>
            <span className="play-title">{e.title}</span>
            {linked.has(e.id) ? <span className="badge warn">From your games</span> : null}
            <span className="muted small play-fix">{e.fix}</span>
          </summary>
          <p className="play-principle">{e.principle}</p>
          {e.why ? (
            <p className="play-why small">
              <span className="label">Why it works</span> {e.why}
            </p>
          ) : null}
          <div className="grid-2">
            <ul className="good" aria-label="What good players do">
              {e.good.map((g) => (
                <li key={g}>{g}</li>
              ))}
            </ul>
            <ul className="bad" aria-label="What to avoid">
              {e.bad.map((b) => (
                <li key={b}>{b}</li>
              ))}
            </ul>
          </div>
          <p className="small">
            <span className="label">How the coach spots it</span> {e.signals.join(' · ')}
          </p>
          <p className="focus-fix">
            <span className="label">Fix</span> {e.fix}
          </p>
          {e.drill ? (
            <p className="small">
              <span className="label">Drill</span> {e.drill}
            </p>
          ) : null}
          <ul className="sources" aria-label="Sources">
            {e.sources.map((s) => (
              <li key={s.url} title={s.url}>
                <span className={`badge kind-${s.kind}`}>{KIND_LABEL[s.kind]}</span> {s.title} <span className="muted">· {domain(s.url)}</span>
              </li>
            ))}
          </ul>
        </details>
      ))}
    </div>
  )
}

function domain(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '')
  } catch {
    return url
  }
}

// --- Scouting intel -------------------------------------------------------------------------------

const AGE_WORD: Record<number, string> = { 1: 'Dark', 2: 'Feudal', 3: 'Castle', 4: 'Imperial' }

/** The opponent as far as the player's screen showed it: civilisation, buildings, army, what they can make, and answers. */
export function IntelPanel({ intel, profile }: { intel: IntelReport; profile: GameProfile }) {
  const faction = intel.faction ? profile.knowledge.roster?.factions.find((f) => f.id === intel.faction!.id) : undefined
  const unitName = (id: string) => profile.knowledge.roster?.units.find((u) => u.id === id)?.name ?? id
  return (
    <div className="intel">
      {faction ? (
        <div className="intel-civ">
          <div className="intel-civ-head">
            <b>{faction.name}</b>
            {faction.status === 'upcoming' ? <span className="badge warn">Upcoming</span> : null}
            {faction.dlc ? <span className="muted small">{faction.dlc}</span> : null}
          </div>
          {faction.uniqueUnits.length ? <p className="small">Unique units: {faction.uniqueUnits.map(unitName).join(', ')}</p> : null}
          {faction.howToPlayAgainst ? (
            <p className="focus-fix">
              <span className="label">Against them</span> {faction.howToPlayAgainst}
            </p>
          ) : null}
        </div>
      ) : (
        <p className="muted small">Their civilisation is read from the loading screen.</p>
      )}
      {intel.opponentAge ? (
        <p className="small">
          <span className="label">Their age</span> {intel.opponentAge.name}{' '}
          <span className="muted">
            {intel.opponentAge.basis === 'seen' ? '(seen on screen)' : intel.opponentAge.basis === 'fielded' ? '(at least: from what they have)' : '(assumed: same as yours)'}
          </span>
        </p>
      ) : null}
      <div className="grid-2">
        <div>
          <div className="label">Buildings seen</div>
          {intel.buildings.length ? (
            <ul className="intel-list">
              {intel.buildings.map((b) => (
                <li key={b.id}>
                  {b.name}
                  {b.count > 1 ? ` ×${b.count}` : ''}
                </li>
              ))}
            </ul>
          ) : (
            <p className="muted small">None yet: scout their base.</p>
          )}
          {intel.likely.some((l) => !l.later) ? (
            <p className="small">
              <span className="label">They can also make</span> {intel.likely.filter((l) => !l.later).map((l) => l.name).join(', ')}
            </p>
          ) : null}
          {intel.likely.some((l) => l.later) ? (
            <p className="small muted">
              <span className="label">Later</span>{' '}
              {intel.likely
                .filter((l) => l.later)
                .map((l) => `${l.name}${l.age ? ` (${AGE_WORD[l.age] ?? `age ${l.age}`})` : ''}`)
                .join(', ')}
            </p>
          ) : null}
        </div>
        <div>
          <div className="label">Army seen</div>
          {intel.units.length ? (
            <ul className="intel-list">
              {intel.units.map((u) => (
                <li key={u.id}>
                  {u.name} <span className="muted">up to {u.count}</span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="muted small">None yet.</p>
          )}
        </div>
      </div>
      {intel.reads.length ? (
        <div className="reads">
          <div className="label">Likely plan</div>
          {intel.reads.slice(0, 2).map((r) => (
            <article key={r.id} className="read">
              <div className="read-head">
                <b>{r.name}</b>
                {r.timing ? <span className="muted small">{r.timing}</span> : null}
              </div>
              <PastBadge past={r.past} />
              <p className="small muted">Seen: {r.evidence.join(', ')}</p>
              <p className="small">{r.threat}</p>
              <ul className="good small">
                {r.response.map((x) => (
                  <li key={x}>{x}</li>
                ))}
              </ul>
              {r.punish ? (
                <p className="small">
                  <span className="label">Punish</span> {r.punish}
                </p>
              ) : null}
            </article>
          ))}
        </div>
      ) : intel.usual.length ? (
        <div className="reads">
          <div className="label">Usual for them now (nothing points to one yet)</div>
          <ul className="small">
            {intel.usual.map((u) => (
              <li key={u.name}>
                <b>{u.name}</b>
                {u.signals ? <span className="muted"> · look for {u.signals}</span> : null} <PastBadge past={u.past} />
              </li>
            ))}
          </ul>
        </div>
      ) : null}
      {intel.theirWindows && (intel.theirWindows.powerSpikes.length || intel.theirWindows.weakWindows.length) ? (
        <p className="small">
          {intel.theirWindows.powerSpikes.length ? (
            <>
              <span className="label">Their spike</span> {intel.theirWindows.powerSpikes[0]}{' '}
            </>
          ) : null}
          {intel.theirWindows.weakWindows.length ? (
            <>
              <span className="label">Their weak window</span> {intel.theirWindows.weakWindows[0]}
            </>
          ) : null}
        </p>
      ) : null}
      {intel.yourPlan ? (
        <div className="your-plan">
          <div className="label">Your {intel.yourPlan.ageName}</div>
          <ul className="small">
            {intel.yourPlan.playAs.map((x) => (
              <li key={x}>{x}</li>
            ))}
          </ul>
        </div>
      ) : null}
      {intel.counters.some((c) => c.build.length) ? (
        <div className="intel-counters">
          {intel.counters
            .filter((c) => c.build.length)
            .map((c) => (
              <p key={c.against} className="small">
                <b>Against {c.against}:</b> {c.build.join(' or ')}
                {c.why ? <span className="muted"> · {c.why}</span> : null}
              </p>
            ))}
        </div>
      ) : null}
    </div>
  )
}

/** "Met in 3 of your 5 games against them": the reference plan, linked to what this player actually meets. */
function PastBadge({ past }: { past: PastSeen | null }) {
  if (!past) return null
  return (
    <span className={`badge ${past.seen ? 'ok' : 'neutral'} past`} title="From your saved games against this civilisation: in how many its tell-tale signs were on your screen">
      {past.of === 1 ? (past.seen ? 'Met in your last game' : 'Not in your last game') : past.seen ? `Met in ${past.seen} of your last ${past.of} games` : `Not in your last ${past.of} games`}
    </span>
  )
}

/**
 * What the coach has learned across games, from what the screen showed: for
 * each side, the usual order (first seen), the usual mix, tallies and how
 * games went with each. Filtered by what the games were (opponent, map…).
 */
export function LearnedPanel({ profile, records, initial }: { profile: GameProfile; records: readonly LearnSource[]; initial?: Readonly<Record<string, string>> }) {
  const dims = useMemo(() => dimensionValues(records, profile), [records, profile])
  const [filter, setFilter] = useState<Record<string, string>>(() => ({ ...(initial ?? {}) }))
  const learned = useMemo(() => learnedFrom(records, profile, filter), [records, profile, filter])
  // The civilisations chosen in the filter, to set the learned games against their reference plans.
  const opponentCiv = filter[profile.learning?.dimensions.find((d) => d.from === 'faction')?.id ?? ''] ?? ''
  const yourCiv = filter[profile.learning?.dimensions.find((d) => d.from === 'context' && d.key === profile.intel?.playerFactionContext)?.id ?? ''] ?? ''
  const tracks = profile.learning?.tracks ?? []
  if (!dims.some((d) => d.values.length)) {
    return (
      <div className="learned">
        <p className="small">
          After each game the coach files what your screen showed under {profile.learning?.dimensions.map((d) => d.label.toLowerCase()).join(', ')}, and learns:
        </p>
        <ul className="small">
          {tracks.map((t) => (
            <li key={t.id}>
              <b>{t.label}</b>
              {t.order ? ' · in what order, by time first seen' : ''}
              {t.composition ? ' · how often each' : ''}
              {t.tally ? ' · with kills and deaths' : ''}
            </li>
          ))}
        </ul>
        <p className="muted small">Nothing learned yet. Play a few games with “Remember my games” on (Settings) so it carries over between sessions.</p>
      </div>
    )
  }
  const side = (who: 'them' | 'you') => (learned?.tracks ?? []).filter((t) => t.side === who)
  return (
    <div className="learned">
      <div className="learned-filters">
        {dims
          .filter((d) => d.values.length)
          .map((d) => (
            <label key={d.id} className="field-inline">
              <span className="label">{d.label}</span>
              <select value={filter[d.id] ?? ''} onChange={(e) => setFilter((f) => ({ ...f, [d.id]: e.target.value }))}>
                <option value="">All</option>
                {d.values.map((v) => (
                  <option key={v.value} value={v.value}>
                    {v.value} ({v.matches})
                  </option>
                ))}
              </select>
            </label>
          ))}
      </div>
      {!learned ? (
        <p className="muted small">No saved games match this choice.</p>
      ) : (
        <>
          <p className="small">
            <b>
              {learned.matches} {learned.matches === 1 ? 'game' : 'games'}
            </b>
            {learned.filterText ? ` ${learned.filterText}` : ''}
            {learned.won + learned.lost ? ` · won ${learned.won}, lost ${learned.lost}` : ''}
            <span className="muted"> · first seen = when it appeared on your screen</span>
          </p>
          <div className="grid-2">
            {(['them', 'you'] as const).map((who) => (
              <div key={who}>
                <h3 className="learned-side">{who === 'them' ? 'Them' : 'You'}</h3>
                {side(who).length ? side(who).map((t) => <LearnedTrackView key={t.id} t={t} />) : <p className="muted small">Not seen yet.</p>}
                <CivPlansSeen profile={profile} learned={learned} side={who} civ={who === 'them' ? opponentCiv : yourCiv} />
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  )
}

function LearnedTrackView({ t }: { t: LearnedTrack }) {
  const order = t.kind === 'age' ? t.order.filter((o) => o.atSec >= 60) : t.order
  return (
    <div className="learned-track">
      <div className="label">
        {t.label} <span className="muted">· {t.matches} {t.matches === 1 ? 'game' : 'games'}</span>
      </div>
      {order.length ? (
        <ol className="build-order">
          {order.map((o) => (
            <li key={o.name}>
              <span>{t.kind === 'age' ? o.name[0].toUpperCase() + o.name.slice(1) : o.name}</span> <span className="muted">{clock(o.atSec)}</span>
            </li>
          ))}
        </ol>
      ) : null}
      {t.kind !== 'age' ? (
        <ul className="learned-items">
          {t.items.slice(0, 6).map((i) => (
            <li key={i.name}>
              <span className="learned-bar" style={{ width: `${Math.round(i.share * 100)}%` }} aria-hidden="true" />
              <span className="learned-name">{i.name.replace(/_/g, ' ')}</span>
              <span className="muted small">
                {t.tally
                  ? `${i.good}–${i.bad}`
                  : t.composition || t.order
                    ? `${i.seenIn} of ${t.matches}${i.firstSec !== null && t.order ? ` · first ${clock(i.firstSec)}` : ''}${i.peak && i.peak > 1 ? ` · up to ${i.peak}` : ''}`
                    : `${i.total}×`}
                {i.won + i.lost ? ` · won ${i.won} of ${i.won + i.lost}` : ''}
              </span>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  )
}

/**
 * A civilisation's usual plans (from the pack), with how many of the chosen
 * games showed each one's tell-tale signs: what you actually meet against
 * them, or what you actually play as them.
 */
function CivPlansSeen({ profile, learned, side, civ }: { profile: GameProfile; learned: Learned; side: 'you' | 'them'; civ: string }) {
  if (!civ) return null
  const plans = plansSeen(profile, civ, learned, side)
  if (!plans.length) return null
  const shown = [...plans].sort((a, b) => b.seen - a.seen || a.age - b.age).slice(0, 6)
  return (
    <div className="learned-track">
      <div className="label">
        {side === 'them' ? `${civ} plans you met` : `${civ} plans you played`} <span className="muted">· from the pack’s plans for {civ}</span>
      </div>
      <ul className="learned-items">
        {shown.map((p) => (
          <li key={p.id}>
            <span className="learned-bar" style={{ width: `${p.of ? Math.round((p.seen / p.of) * 100) : 0}%` }} aria-hidden="true" />
            <span className="learned-name">
              <span className="muted">{ageWord(profile, p.age)}</span> {p.name}
            </span>
            <span className="muted small">{p.seen ? `${p.seen} of ${p.of}` : 'not seen'}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

/** "Feudal" for age 2, from the pack's age names. */
function ageWord(profile: GameProfile, age: number): string {
  const n = profile.intel?.ages?.[age - 1]
  return n ? n[0].toUpperCase() + n.slice(1) : `Age ${age}`
}

