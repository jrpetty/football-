// Take a player out and watch the forecast move.
//
// The recomputation runs the same model core that produced the published
// numbers, so what a reader sees here is the model's actual answer to "what if
// he is out", not an interpolation. With no edits made, the recomputed figures
// are identical to the published ones — which is the point, and worth being
// able to check.

import { useMemo, useState } from 'react'
import { recomputeFixture, probabilityDelta } from '../core/whatIf.ts'
import type { PlayerRates } from '../core/availability.ts'
import type { FixtureArtifact } from '../core/schema.ts'
import { LineupPitch, LineupCaveat } from './LineupPitch.tsx'
import { ProbBar } from './primitives.tsx'
import { club, clubName, clubColor } from '../config/teams.ts'

function pct(x: number): string {
  return `${(x * 100).toFixed(0)}%`
}

function signed(x: number): string {
  const v = x * 100
  if (Math.abs(v) < 0.05) return '—'
  return `${v > 0 ? '+' : ''}${v.toFixed(1)}pp`
}

function deltaColor(x: number): string {
  if (Math.abs(x) < 0.005) return 'var(--text-faint)'
  return x > 0 ? 'var(--good)' : 'var(--away)'
}

function SquadList({
  code,
  squad,
  removed,
  onToggle,
}: {
  code: string
  squad: readonly PlayerRates[]
  removed: ReadonlySet<number>
  onToggle: (id: number) => void
}) {
  // Most involved first; a reader looks for the players who matter.
  const ordered = useMemo(
    () => [...squad].sort((a, b) => b.minuteShare - a.minuteShare).slice(0, 22),
    [squad],
  )
  return (
    <div>
      <div className="eyebrow row gap-8" style={{ marginBottom: 8, alignItems: 'center' }}>
        <span className="chip-dot" style={{ background: clubColor(code) }} aria-hidden="true" />
        {clubName(code)}
      </div>
      <div className="row wrap gap-6">
        {ordered.map((p) => {
          const out = removed.has(p.id)
          const unavailable = p.status !== 'a'
          return (
            <button
              key={p.id}
              onClick={() => onToggle(p.id)}
              title={
                out
                  ? 'Removed by you — click to put him back'
                  : unavailable
                    ? `${p.news || 'Unavailable'} — click to remove from the squad`
                    : 'Click to take him out'
              }
              style={{
                padding: '4px 9px',
                borderRadius: 7,
                fontSize: 12,
                fontWeight: 600,
                border: `1px solid ${out ? 'rgba(230,103,103,0.4)' : 'var(--border-soft)'}`,
                background: out ? 'rgba(230,103,103,0.12)' : 'var(--panel-2)',
                color: out ? '#f0a0a0' : unavailable ? 'var(--text-faint)' : 'var(--text-dim)',
                textDecoration: out ? 'line-through' : undefined,
              }}
            >
              {p.name}
              {unavailable && !out ? ' ·' : ''}
            </button>
          )
        })}
      </div>
    </div>
  )
}

export function SquadEditor({
  fixture,
  homeSquad,
  awaySquad,
}: {
  fixture: FixtureArtifact
  homeSquad: readonly PlayerRates[]
  awaySquad: readonly PlayerRates[]
}) {
  const [removed, setRemoved] = useState<ReadonlySet<number>>(new Set())

  const toggle = (id: number): void => {
    setRemoved((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const result = useMemo(
    () =>
      recomputeFixture({
        inputs: fixture.recompute,
        homeCode: fixture.home,
        awayCode: fixture.away,
        kickoff: fixture.kickoff,
        homeSquad,
        awaySquad,
        removed,
      }),
    [fixture, homeSquad, awaySquad, removed],
  )

  const edited = removed.size > 0
  const after = result.prediction.probs
  const delta = probabilityDelta(fixture.probs, after)

  // With no edits the recomputed lineup is the published one; once edited it
  // is the model's revised side.
  const homeLineup = edited ? result.homeLineup : fixture.homeLineup
  const awayLineup = edited ? result.awayLineup : fixture.awayLineup

  return (
    <div className="panel panel-pad" style={{ marginBottom: 18 }}>
      <div className="section-title">
        <h3 style={{ fontSize: 16 }}>Predicted line-ups</h3>
        <span className="row gap-8" style={{ alignItems: 'center' }}>
          {edited && (
            <button className="gw-btn" style={{ padding: '0 12px' }} onClick={() => setRemoved(new Set())}>
              Reset
            </button>
          )}
          <span className="faint" style={{ fontSize: 11.5 }}>
            {edited ? `${removed.size} removed` : 'click a player to take him out'}
          </span>
        </span>
      </div>

      {edited && (
        <div
          style={{
            padding: '13px 15px',
            borderRadius: 'var(--radius-sm)',
            background: 'var(--panel-2)',
            border: '1px solid var(--border-soft)',
            marginBottom: 14,
            // The squad chips are below the pitch, so without this the reader
            // clicks a player and the number that changed is off-screen above
            // them — which defeats the point of the feature.
            position: 'sticky',
            top: 72,
            zIndex: 20,
            boxShadow: 'var(--shadow)',
          }}
        >
          <div className="eyebrow" style={{ marginBottom: 9 }}>With your changes</div>
          <ProbBar probs={after} homeCode={fixture.home} awayCode={fixture.away} height={28} />
          <div className="row wrap gap-16" style={{ marginTop: 11, fontSize: 12.5 }}>
            {([
              [club(fixture.home).short, fixture.probs.home, after.home, delta.home],
              ['Draw', fixture.probs.draw, after.draw, delta.draw],
              [club(fixture.away).short, fixture.probs.away, after.away, delta.away],
            ] as const).map(([label, before, now, d]) => (
              <span key={label} className="tabular">
                <span className="faint">{label}</span>{' '}
                <span className="faint">{pct(before)}</span>
                {' → '}
                <strong>{pct(now)}</strong>{' '}
                <span style={{ color: deltaColor(d), fontWeight: 700 }}>{signed(d)}</span>
              </span>
            ))}
            <span className="tabular faint">
              expected goals {fixture.expectedGoalsHome.toFixed(2)}–{fixture.expectedGoalsAway.toFixed(2)}
              {' → '}
              <strong style={{ color: 'var(--text)' }}>
                {result.prediction.expectedGoalsHome.toFixed(2)}–{result.prediction.expectedGoalsAway.toFixed(2)}
              </strong>
            </span>
          </div>
        </div>
      )}

      <LineupPitch
        homeCode={fixture.home}
        awayCode={fixture.away}
        homeLineup={homeLineup}
        awayLineup={awayLineup}
        removed={removed}
        onToggle={toggle}
      />
      <LineupCaveat home={homeLineup} away={awayLineup} />

      <div className="grid-2" style={{ marginTop: 16, gap: 18 }}>
        <SquadList code={fixture.home} squad={homeSquad} removed={removed} onToggle={toggle} />
        <SquadList code={fixture.away} squad={awaySquad} removed={removed} onToggle={toggle} />
      </div>
      <p className="faint" style={{ fontSize: 11.5, marginTop: 11, lineHeight: 1.5 }}>
        Removing a player re-runs the whole forecast in your browser using the same model that
        produced the published numbers — a dot marks someone already flagged out. He still counts
        toward his club's replacement level, so losing a striker hurts a thin squad more than a
        deep one. Nothing you change here is saved or published.
      </p>
    </div>
  )
}
