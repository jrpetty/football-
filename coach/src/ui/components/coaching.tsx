// Coaching views: the coach's review, how fights are won and lost (from
// killcam/replay tags), numbers against the clock, and the playbook of what
// good players do. All read structured data only.

import { useMemo, useState } from 'react'
import type { BenchmarkResult } from '../../core/analyses/benchmarks.ts'
import { expectedAt } from '../../core/analyses/benchmarks.ts'
import type { FactorResult } from '../../core/analyses/factors.ts'
import type { BattleRecord } from '../../core/analyses/battles.ts'
import type { IntelReport } from '../../core/analyses/intel.ts'
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
