// The unit of the gameweek board: one match, readable at a glance, clickable
// through to the full breakdown.

import { Link } from 'react-router-dom'
import type { FixtureArtifact } from '../core/schema.ts'
import { ProbBar, TeamChip, UpsetBadge, formatTimeOnly, headlineScore } from './primitives.tsx'
import { clubName, club } from '../config/teams.ts'

export function FixtureCard({ fixture }: { fixture: FixtureArtifact }) {
  const f = fixture
  const score = headlineScore(f)
  const played = f.finished && f.homeGoals !== null && f.awayGoals !== null

  // Lead with the single most useful non-obvious fact about the match.
  const keyAbsence =
    f.evidence.find((e) => e.category === 'availability' && e.magnitude !== 'minor') ?? null
  const leadEvidence = keyAbsence ?? f.evidence.find((e) => e.category === 'form') ?? null

  return (
    <Link to={`/fixture/${f.gameweek}/${f.id}`} className="fixture-card">
      <div className="fixture-head">
        <span className="eyebrow">{formatTimeOnly(f.kickoff)}</span>
        <span className="row gap-6">
          {played && <span className="badge badge-neutral">Full time</span>}
          <UpsetBadge rating={f.upset.rating} />
        </span>
      </div>

      <div className="fixture-teams">
        <span className="fixture-side">
          <TeamChip code={f.home} />
        </span>
        <span className="fixture-score">
          {played ? `${f.homeGoals}–${f.awayGoals}` : `${score.home}–${score.away}`}
        </span>
        <span className="fixture-side away">
          <TeamChip code={f.away} reverse />
        </span>
      </div>

      <ProbBar probs={f.probs} homeCode={f.home} awayCode={f.away} showLegend={false} height={26} />

      <div className="fixture-meta">
        <span className="mono">
          xG {f.expectedGoalsHome.toFixed(2)} – {f.expectedGoalsAway.toFixed(2)}
        </span>
        <span>
          {played
            ? f.result?.correctOutcome
              ? 'Called it'
              : 'Missed'
            : f.probs.home >= f.probs.draw && f.probs.home >= f.probs.away
              ? `${club(f.home).short} ${(f.probs.home * 100).toFixed(0)}%`
              : f.probs.away >= f.probs.draw
                ? `${club(f.away).short} ${(f.probs.away * 100).toFixed(0)}%`
                : `Draw ${(f.probs.draw * 100).toFixed(0)}%`}
        </span>
      </div>

      {leadEvidence && (
        <div
          className="faint"
          style={{ marginTop: 10, fontSize: 12, lineHeight: 1.45, borderTop: '1px solid var(--border-soft)', paddingTop: 9 }}
        >
          {leadEvidence.headline}
        </div>
      )}

      {!played && (f.upset.rating === 'live' || f.upset.rating === 'dangerous') && (
        <div style={{ marginTop: 8, fontSize: 11.5, color: 'var(--text-faint)' }}>
          {clubName(f.upset.underdog === clubName(f.home) ? f.home : f.away)} win{' '}
          {(f.upset.upsetProbability * 100).toFixed(0)}% of the time
        </div>
      )}
    </Link>
  )
}
