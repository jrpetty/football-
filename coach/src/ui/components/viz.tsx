// Visual building blocks: zone heatmaps (an FC pitch or goal area, or a plain
// grid), the fused visible-state table, the AoE economy panel, a scoreboard,
// sparklines and policy badges. All read structured data only.

import type { HeatmapResult } from '../../core/analyses/heatmap.ts'
import type { MatchRecord } from '../../core/analyses/matchRecorder.ts'
import type { PolicyScope } from '../../core/policy/registry.ts'
import { statusLabel } from '../../core/policy/registry.ts'
import type { GameProfile } from '../../core/profile/schema.ts'
import type { FusedValue, PolicyStatus, VisibleState } from '../../core/types.ts'
import { ago, gameClock, labelOf, pct } from '../format.ts'
import { GradeBadge } from './common.tsx'

// --- Zone maps ----------------------------------------------------------------------

/**
 * Rows are drawn bottom-up (row 0 at the bottom): on the pitch the player
 * attacks upwards; on the goal area the goal is at the bottom, seen from
 * behind the keeper, so the keeper's left is the viewer's left.
 */
export function ZoneMap({ heatmap, compact = false, graded = true }: { heatmap: HeatmapResult; compact?: boolean; graded?: boolean }) {
  const z = heatmap.zoneSet
  const variant = z.id === 'pitch' ? 'pitch' : z.id === 'goal-area' ? 'goal' : 'grid'
  const max = Math.max(1, ...Object.values(heatmap.cells))
  const rows = Array.from({ length: z.rows }, (_, r) => z.cells.slice(r * z.cols, (r + 1) * z.cols))
  const drawn = variant === 'grid' ? rows : [...rows].reverse()
  const claimCell = heatmap.claim?.cell
  return (
    <figure className={`zonemap ${variant}${compact ? ' compact' : ''}`} aria-label={`${heatmap.spec.label}: ${heatmap.located} of ${heatmap.total} placed`}>
      <div className="zm-field" style={{ gridTemplateColumns: `repeat(${z.cols}, 1fr)`, gridTemplateRows: `repeat(${z.rows}, 1fr)` }}>
        {variant === 'pitch' && <PitchLines />}
        {drawn.flat().map((cell) => {
          const n = heatmap.cells[cell.id] ?? 0
          const heat = n / max
          return (
            <div
              key={cell.id}
              className={`zm-cell${cell.id === claimCell ? ' claim' : ''}${n === 0 ? ' zero' : ''}`}
              style={{ ['--heat' as string]: heat.toFixed(3) }}
              title={`${cell.label}: ${n}`}
            >
              {n > 0 ? <span className="zm-n">{n}</span> : null}
              {!compact ? <span className="zm-label">{shortZone(cell.label)}</span> : null}
            </div>
          )
        })}
      </div>
      {variant === 'goal' && <div className="zm-goal" aria-hidden="true" />}
      {!compact && (
        <figcaption className="zm-caption">
          <span>
            {heatmap.located} of {heatmap.total} placed{heatmap.spec.sampled ? ' · sampled' : ''}
          </span>
          {!graded ? null : heatmap.claim ? <GradeBadge grade={heatmap.claim.grade} /> : <span className="badge insufficient">{heatmap.total ? 'Too few to call' : 'Nothing yet'}</span>}
        </figcaption>
      )}
    </figure>
  )
}

function PitchLines() {
  return (
    <svg className="zm-lines" viewBox="0 0 100 150" preserveAspectRatio="none" aria-hidden="true">
      <rect x="2" y="2" width="96" height="146" />
      <line x1="2" y1="75" x2="98" y2="75" />
      <circle cx="50" cy="75" r="11" />
      <rect x="22" y="2" width="56" height="22" />
      <rect x="22" y="126" width="56" height="22" />
      <rect x="38" y="2" width="24" height="8" />
      <rect x="38" y="140" width="24" height="8" />
    </svg>
  )
}

function shortZone(label: string): string {
  return label.replace(/^your /, '').replace(/ third/, '').replace(/inside the box/, 'box').replace(/outside the box/, 'outside')
}

export function Breakdown({ heatmap }: { heatmap: HeatmapResult }) {
  const entries = Object.entries(heatmap.breakdown).filter(([, n]) => n > 0).sort((a, b) => b[1] - a[1])
  if (!heatmap.spec.breakdown || entries.length === 0) return null
  const max = Math.max(...entries.map(([, n]) => n))
  return (
    <div className="breakdown">
      <div className="label">{heatmap.spec.breakdown.label}</div>
      {entries.map(([k, n]) => (
        <div key={k} className="bd-row">
          <span>{labelOf(k)}</span>
          <span className="bd-bar">
            <span style={{ width: `${(n / max) * 100}%` }} />
          </span>
          <span className="num">{n}</span>
        </div>
      ))}
    </div>
  )
}

// --- Visible state ------------------------------------------------------------------

export function StatePanel({ visible, profile, now }: { visible: VisibleState; profile: GameProfile; now: number }) {
  const labels = new Map((profile.vision?.state ?? []).map((s) => [s.key, s.label]))
  const entries = Object.entries(visible.values)
  return (
    <div className="state-panel">
      <div className="state-head">
        <span className={`badge ${visible.stale ? 'warn' : 'ok'}`}>{visible.stale ? 'Stale' : 'Fresh'}</span>
        <span className="muted small">
          Phase: <b>{labelOf(visible.phase)}</b> · confidence {pct(visible.confidence)}
        </span>
      </div>
      {visible.stale && visible.staleReasons.length > 0 && (
        <ul className="stale-reasons">
          {visible.staleReasons.map((r) => (
            <li key={r}>{r}</li>
          ))}
        </ul>
      )}
      {entries.length === 0 ? (
        <div className="empty small">Nothing readable on screen right now.</div>
      ) : (
        <table className="state-table">
          <tbody>
            {entries.map(([key, v]) => (
              <StateRow key={key} label={labels.get(key) ?? labelOf(key)} v={v} now={now} />
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

function StateRow({ label, v, now }: { label: string; v: FusedValue; now: number }) {
  const age = Math.max(0, now - v.observedAt)
  return (
    <tr className={v.conflicted ? 'conflicted' : undefined}>
      <th scope="row">{label}</th>
      <td className="val">{String(v.value)}</td>
      <td className="muted small">{ago(age)}</td>
      <td>
        <span className="conf" style={{ ['--c' as string]: v.confidence.toFixed(2) }} title={`Confidence ${pct(v.confidence)} · ${v.source}`} />
      </td>
      {v.conflicted ? <td className="small warn-text">sources disagree</td> : <td />}
    </tr>
  )
}

// --- Age of Empires economy -----------------------------------------------------------

const RESOURCES = [
  ['food', 'Food', '#e8836b'],
  ['wood', 'Wood', '#b98a5a'],
  ['gold', 'Gold', '#f2c94c'],
  ['stone', 'Stone', '#9aa5b1'],
] as const

export function EconomyPanel({ visible, context }: { visible: VisibleState; context: Readonly<Record<string, string | number>> }) {
  const n = (k: string) => (typeof visible.values[k]?.value === 'number' ? (visible.values[k].value as number) : null)
  const pop = n('population')
  const cap = n('populationCap')
  const idle = n('idleVillagers')
  const t = n('gameTimeSec')
  const age = visible.values.age?.value
  const feudal = Number(context.feudalTargetSec) || 660
  const castle = Number(context.castleTargetSec) || 1260
  const housed = pop !== null && cap !== null && pop >= cap && cap < 200
  const bank = RESOURCES.reduce((s, [k]) => s + (n(k) ?? 0), 0)
  return (
    <div className="economy">
      <div className="res-row">
        {RESOURCES.map(([k, label, color]) => (
          <div key={k} className="res" style={{ ['--res' as string]: color }}>
            <span className="res-dot" aria-hidden="true" />
            <span className="res-label">{label}</span>
            <span className="res-n">{n(k) ?? '—'}</span>
          </div>
        ))}
      </div>
      <div className="eco-grid">
        <div className={`eco-card${housed ? ' alert' : ''}`}>
          <div className="label">Population</div>
          <div className="big">
            {pop ?? '—'}
            <span className="muted"> / {cap ?? '—'}</span>
          </div>
          <div className="meter">
            <span style={{ width: `${pop !== null && cap ? Math.min(100, (pop / cap) * 100) : 0}%`, background: housed ? 'var(--live)' : 'var(--accent)' }} />
          </div>
          <div className="small muted">{housed ? 'Housed — build a house' : 'Room to grow'}</div>
        </div>
        <div className={`eco-card${idle ? ' alert' : ''}`}>
          <div className="label">Idle villagers</div>
          <div className="big">{idle ?? '—'}</div>
          <div className="small muted">{idle ? 'Put them to work' : 'All working'}</div>
        </div>
        <div className={`eco-card${bank > 1500 ? ' warn' : ''}`}>
          <div className="label">Banked</div>
          <div className="big">{bank || '—'}</div>
          <div className="small muted">{bank > 1500 ? 'Spend it' : 'Spending well'}</div>
        </div>
      </div>
      <AgeTrack age={typeof age === 'string' ? age : null} t={t} feudal={feudal} castle={castle} />
    </div>
  )
}

function AgeTrack({ age, t, feudal, castle }: { age: string | null; t: number | null; feudal: number; castle: number }) {
  const end = Math.max(castle + 300, t ?? 0)
  const at = (s: number) => `${Math.min(100, (s / end) * 100)}%`
  return (
    <div className="agetrack" aria-label="Age progress against your targets">
      <div className="agetrack-bar">
        {t !== null && <span className="agetrack-fill" style={{ width: at(t) }} />}
        <span className="agetrack-mark" style={{ left: at(feudal) }} title={`Feudal target ${gameClock(feudal)}`}>
          <span>Feudal {gameClock(feudal)}</span>
        </span>
        <span className="agetrack-mark" style={{ left: at(castle) }} title={`Castle target ${gameClock(castle)}`}>
          <span>Castle {gameClock(castle)}</span>
        </span>
      </div>
      <div className="small muted">
        {t !== null ? `Game time ${gameClock(t)}` : 'Game time not read yet'} · Age: <b>{age ? labelOf(age) : '—'}</b>
      </div>
    </div>
  )
}

// --- Scoreboard ---------------------------------------------------------------------

export function Scoreboard({ score, minute, team }: { score: { player: number; opponent: number } | null; minute: number | null; team: string }) {
  return (
    <div className="scoreboard" aria-label="Score as read from the screen">
      <div className="sb-team">{team || 'You'}</div>
      <div className="sb-score">
        <span>{score?.player ?? '–'}</span>
        <span className="sb-sep">:</span>
        <span>{score?.opponent ?? '–'}</span>
      </div>
      <div className="sb-team right">Opponent</div>
      <div className="sb-min">{minute !== null ? `${minute}'` : 'not read'}</div>
    </div>
  )
}

// --- Sparkline ----------------------------------------------------------------------

export function Sparkline({ values, width = 120, height = 32, unit = '' }: { values: readonly number[]; width?: number; height?: number; unit?: string }) {
  if (values.length < 2) return <span className="muted small">trend after 2 matches</span>
  const min = Math.min(...values)
  const max = Math.max(...values)
  const span = max - min || 1
  const pts = values.map((v, i) => [(i / (values.length - 1)) * (width - 4) + 2, height - 3 - ((v - min) / span) * (height - 6)] as const)
  const last = pts[pts.length - 1]
  return (
    <svg className="spark" width={width} height={height} viewBox={`0 0 ${width} ${height}`} role="img" aria-label={`Trend from ${round(values[0])} to ${round(values[values.length - 1])}${unit}`}>
      <polyline points={pts.map((p) => p.join(',')).join(' ')} />
      <circle cx={last[0]} cy={last[1]} r="2.5" />
    </svg>
  )
}

function round(v: number): string {
  return Number.isInteger(v) ? String(v) : v.toFixed(1)
}

export function StatTrends({ profile, matches }: { profile: GameProfile; matches: readonly MatchRecord[] }) {
  const keys = profile.matchStats?.keys ?? []
  const rows = keys
    .map((k) => ({ k, values: matches.map((m) => m.stats[k.key]).filter((v): v is number => typeof v === 'number') }))
    .filter((r) => r.values.length > 0)
  if (!rows.length) return <div className="empty small">No end-of-match statistics yet. Leave the statistics screen up for a few seconds after a match.</div>
  return (
    <table className="trend-table">
      <tbody>
        {rows.map(({ k, values }) => {
          const last = values[values.length - 1]
          const avg = values.reduce((a, b) => a + b, 0) / values.length
          return (
            <tr key={k.key}>
              <th scope="row">{k.label}</th>
              <td className="num">
                {round(last)}
                {k.unit ?? ''}
              </td>
              <td className="muted small">
                avg {round(avg)}
                {k.unit ?? ''}
              </td>
              <td>
                <Sparkline values={values.slice(-12)} unit={k.unit} />
              </td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}

// --- Policy -------------------------------------------------------------------------

export function policyTone(status: PolicyStatus, scope: Iterable<PolicyScope>): 'ok' | 'info' | 'warn' | 'live' {
  const s = new Set(scope)
  if (status === 'disabled') return 'live'
  if (status === 'needs_publisher_review') return 'warn'
  return s.has('live') ? 'ok' : 'info'
}

export function PolicyBadge({ policy }: { policy: { readonly status: PolicyStatus; readonly scope: Iterable<PolicyScope> } }) {
  return <span className={`badge ${policyTone(policy.status, policy.scope)}`}>{statusLabel(policy.status)}</span>
}

const SCOPES: readonly { id: PolicyScope; label: string }[] = [
  { id: 'live', label: 'During play' },
  { id: 'round_break', label: 'Round breaks' },
  { id: 'post_match', label: 'After the match' },
]

export function ScopeChips({ scope }: { scope: Iterable<PolicyScope> }) {
  const s = new Set(scope)
  return (
    <div className="scope-chips" aria-label="When the coach may speak">
      {SCOPES.map((x) => (
        <span key={x.id} className={`scope ${s.has(x.id) ? 'on' : 'off'}`}>
          <span aria-hidden="true">{s.has(x.id) ? '✓' : '✕'}</span> {x.label}
        </span>
      ))}
    </div>
  )
}
