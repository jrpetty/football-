// Shared presentational pieces. Hand-rolled SVG throughout — no chart library,
// which keeps the bundle small and every mark under our own control.

import type { ReactNode } from 'react'
import { clubColor, clubName, club } from '../config/teams.ts'

export function TeamChip({ code, reverse = false }: { code: string; reverse?: boolean }) {
  // Both names are rendered and CSS picks one. Truncating "Bournemouth" to
  // "Bournemou…" on a phone is worse than showing "BOU", and swapping in
  // JavaScript on resize would reflow the whole board.
  return (
    <span className="team-chip" style={reverse ? { flexDirection: 'row-reverse' } : undefined}>
      <span className="team-bar" style={{ background: clubColor(code) }} aria-hidden="true" />
      <span className="team-name team-name-full">{clubName(code)}</span>
      <span className="team-name team-name-short" aria-hidden="true">{club(code).short}</span>
    </span>
  )
}

export function TeamCode({ code }: { code: string }) {
  return (
    <span className="chip" title={clubName(code)}>
      <span className="chip-dot" style={{ background: clubColor(code) }} aria-hidden="true" />
      {club(code).short}
    </span>
  )
}

/**
 * Home / draw / away as a diverging bar.
 *
 * These three are an ordered scale with a real neutral in the middle, not
 * three unrelated categories — so the encoding is blue to gray to red rather
 * than three arbitrary hues. Percentages are printed inside every segment
 * wide enough to hold them, so identity never rests on colour alone.
 */
export function ProbBar({
  probs,
  homeCode,
  awayCode,
  showLegend = true,
  height = 30,
}: {
  probs: { home: number; draw: number; away: number }
  homeCode?: string
  awayCode?: string
  showLegend?: boolean
  height?: number
}) {
  const segments = [
    { key: 'home', value: probs.home, color: 'var(--home)', label: 'Home' },
    { key: 'draw', value: probs.draw, color: 'var(--draw)', label: 'Draw' },
    { key: 'away', value: probs.away, color: 'var(--away)', label: 'Away' },
  ]
  return (
    <div>
      <div className="probbar" style={{ height }} role="img"
        aria-label={`Home win ${(probs.home * 100).toFixed(0)} percent, draw ${(probs.draw * 100).toFixed(0)} percent, away win ${(probs.away * 100).toFixed(0)} percent`}>
        {segments.map((s) => (
          <div
            key={s.key}
            className="probbar-seg"
            style={{ width: `${s.value * 100}%`, background: s.color }}
            title={`${s.label}: ${(s.value * 100).toFixed(1)}%`}
          >
            {s.value >= 0.11 && <span>{Math.round(s.value * 100)}%</span>}
          </div>
        ))}
      </div>
      {showLegend && (
        <div className="probbar-legend">
          <span>{homeCode ? club(homeCode).short : 'Home'}</span>
          <span>Draw</span>
          <span>{awayCode ? club(awayCode).short : 'Away'}</span>
        </div>
      )}
    </div>
  )
}

export function Stat({
  label,
  value,
  sub,
  accent,
}: {
  label: string
  value: ReactNode
  sub?: ReactNode
  accent?: string
}) {
  return (
    <div className="stat">
      <div className="stat-label">{label}</div>
      <div className="stat-value" style={accent ? { color: accent } : undefined}>
        {value}
      </div>
      {sub && <div className="stat-sub">{sub}</div>}
    </div>
  )
}

export type UpsetRating = 'toss-up' | 'unlikely' | 'live' | 'dangerous'

export function UpsetBadge({ rating }: { rating: UpsetRating }) {
  if (rating === 'unlikely') return null
  if (rating === 'toss-up') return <span className="badge badge-neutral">Too close to call</span>
  return (
    <span className={rating === 'dangerous' ? 'badge badge-danger' : 'badge badge-live'}>
      {rating === 'dangerous' ? 'Upset danger' : 'Upset live'}
    </span>
  )
}

/**
 * The scoreline to lead with.
 *
 * The single most likely exact score is often a draw even when the model
 * favours a win, so quoting it beside a "62% home" bar reads as a
 * contradiction. Showing the most likely score *given the most likely
 * outcome* keeps the headline internally consistent; the true global mode is
 * still on the match page in the full grid.
 */
export function headlineScore(f: {
  probs: { home: number; draw: number; away: number }
  modalScore: { home: { home: number; away: number }; draw: { home: number; away: number }; away: { home: number; away: number } }
}): { home: number; away: number } {
  const { home, draw, away } = f.probs
  if (home >= draw && home >= away) return f.modalScore.home
  if (away >= draw && away >= home) return f.modalScore.away
  return f.modalScore.draw
}

/** Form run as coloured letters — W/D/L is already the label, colour is a second channel. */
export function FormRun({ form }: { form: string[] }) {
  if (form.length === 0) return <span className="faint" style={{ fontSize: 12 }}>—</span>
  return (
    <span className="row gap-6" style={{ display: 'inline-flex' }}>
      {form.slice(0, 6).map((r, i) => (
        <span
          key={i}
          style={{
            width: 18, height: 18, borderRadius: 5, display: 'grid', placeItems: 'center',
            fontSize: 10.5, fontWeight: 800,
            background:
              r === 'W' ? 'rgba(25,158,112,0.22)' : r === 'D' ? 'rgba(124,135,152,0.22)' : 'rgba(230,103,103,0.2)',
            color: r === 'W' ? '#5fcfa6' : r === 'D' ? '#aab4c4' : '#f0a0a0',
          }}
          title={r === 'W' ? 'Win' : r === 'D' ? 'Draw' : 'Loss'}
        >
          {r}
        </span>
      ))}
    </span>
  )
}

export function formatKickoff(ms: number): string {
  return new Date(ms).toLocaleString(undefined, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  })
}

export function formatTimeOnly(ms: number): string {
  return new Date(ms).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
}

export function formatDayLabel(ms: number): string {
  return new Date(ms).toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' })
}
