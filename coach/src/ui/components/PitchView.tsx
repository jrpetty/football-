// The pitch, as read from the radar: both teams' average shapes with and
// without the ball (with the formation each makes and the back line's
// height), where each team spent the match, where the ball was lost and won,
// where shots came from, and the last seconds before every goal played back
// on the pitch or in a 3D stadium. The player's team attacks to the right.
// Colour is never the only cue: the player's team is drawn as filled
// circles, the opponent as squares, the ball as a white dot with a ring.

import { lazy, Suspense, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import type { PitchReport } from '../../core/engine.ts'
import { BLOCKS, formationEntries, LINE_GAP, mergeShape, pitchInsights, TEAM_LENGTH, TEAM_WIDTH, zoneXg, type PitchInsight } from '../../core/pitch/insights.ts'
import { PITCH_LENGTH as L, PITCH_WIDTH as WD } from '../../core/pitch/radar.ts'
import { HEAT_COLS, HEAT_ROWS, type PitchFrame, type PitchGoal, type PitchLive, type PitchMatchRecord, type ShapeStats } from '../../core/pitch/tracker.ts'
import type { FormationEntry, GameProfile } from '../../core/profile/schema.ts'
import { gameClock } from '../format.ts'
import { Segmented, Switch } from './common.tsx'

const PitchScene3D = lazy(() => import('./PitchScene3D.tsx'))

type P = readonly [number, number]

/** Team colours (validated categorical slots 1 and 2), each with its own marker shape. */
export const TEAM = { ours: '#3987e5', theirs: '#d95926', ball: '#f4f6fa' } as const

export interface PitchMatch {
  readonly id: string
  readonly label: string
  readonly record: PitchMatchRecord
}

type View = 'shape' | 'heat' | 'moments' | 'goals' | '3d'

/** The opponent's shape in the player's frame (theirs is stored with their own goal at x = 0). */
const mirror = (p: P): P => [L - p[0], WD - p[1]]

// --- The pitch itself --------------------------------------------------------------------------

/** A pitch in metres (105 × 68), with its markings, children drawn on top. */
export function PitchSvg({ children, label, compact = false }: { children?: ReactNode; label: string; compact?: boolean }) {
  const pad = compact ? 2 : 4
  const stripes = 10
  return (
    <svg className={`pitch-svg${compact ? ' compact' : ''}`} viewBox={`${-pad} ${-pad} ${L + 2 * pad} ${WD + 2 * pad}`} role="img" aria-label={label}>
      <rect x={-pad} y={-pad} width={L + 2 * pad} height={WD + 2 * pad} className="pitch-surround" />
      {Array.from({ length: stripes }, (_, i) => (
        <rect key={i} x={(i * L) / stripes} y={0} width={L / stripes} height={WD} className={i % 2 ? 'pitch-stripe b' : 'pitch-stripe a'} />
      ))}
      <g className="pitch-lines">
        <rect x={0} y={0} width={L} height={WD} />
        <line x1={L / 2} y1={0} x2={L / 2} y2={WD} />
        <circle cx={L / 2} cy={WD / 2} r={9.15} />
        <circle cx={L / 2} cy={WD / 2} r={0.35} className="spot" />
        {[0, 1].map((side) => {
          const x = (v: number) => (side ? L - v : v)
          const box = (depth: number, width: number) => `M ${x(0)} ${WD / 2 - width / 2} L ${x(depth)} ${WD / 2 - width / 2} L ${x(depth)} ${WD / 2 + width / 2} L ${x(0)} ${WD / 2 + width / 2}`
          // The arc of the penalty area: the part of the 9.15 m circle round the spot outside the box.
          const dx = 16.5 - 11
          const dy = Math.sqrt(9.15 ** 2 - dx ** 2)
          return (
            <g key={side}>
              <path d={box(16.5, 40.32)} />
              <path d={box(5.5, 18.32)} />
              <circle cx={x(11)} cy={WD / 2} r={0.35} className="spot" />
              <path d={`M ${x(16.5)} ${WD / 2 - dy} A 9.15 9.15 0 0 ${side ? 0 : 1} ${x(16.5)} ${WD / 2 + dy}`} />
              <rect x={side ? L : -1.6} y={WD / 2 - 3.66} width={1.6} height={7.32} className="pitch-goal" />
            </g>
          )
        })}
      </g>
      {children}
    </svg>
  )
}

function Player({ p, team, r = 1.25, label, dim }: { p: P; team: 'ours' | 'theirs'; r?: number; label?: string; dim?: boolean }) {
  const op = dim ? 0.45 : 1
  return (
    <g opacity={op}>
      {team === 'ours' ? (
        <circle cx={p[0]} cy={p[1]} r={r} fill={TEAM.ours} className="pitch-player" />
      ) : (
        <rect x={p[0] - r * 0.9} y={p[1] - r * 0.9} width={r * 1.8} height={r * 1.8} rx={0.3} fill={TEAM.theirs} className="pitch-player" />
      )}
      {label ? (
        <text x={p[0]} y={p[1] + 0.55} className="pitch-num">
          {label}
        </text>
      ) : null}
    </g>
  )
}

function Ball({ p, r = 0.8 }: { p: P; r?: number }) {
  return (
    <g>
      <circle cx={p[0]} cy={p[1]} r={r * 1.7} className="pitch-ball-ring" />
      <circle cx={p[0]} cy={p[1]} r={r} fill={TEAM.ball} className="pitch-ball" />
    </g>
  )
}

/** Players into lines across the pitch (back to front) for drawing the shape. */
function rows(slots: readonly P[]): P[][] {
  const s = [...slots].sort((a, b) => a[0] - b[0])
  const out: P[][] = []
  for (const p of s) {
    const last = out[out.length - 1]
    if (last && p[0] - last[last.length - 1][0] <= 5.5) last.push(p)
    else out.push([p])
  }
  return out.map((r) => [...r].sort((a, b) => a[1] - b[1]))
}

function ShapeLayer({ shape, team, mirrorIt }: { shape: ShapeStats; team: 'ours' | 'theirs'; mirrorIt: boolean }) {
  const slots = shape.slots.map((p) => (mirrorIt ? mirror(p) : p))
  const colour = team === 'ours' ? TEAM.ours : TEAM.theirs
  const line = mirrorIt ? L - shape.line : shape.line
  const xs = slots.map((p) => p[0])
  const ys = slots.map((p) => p[1])
  return (
    <g>
      {/* The team's box: deepest to highest, widest to widest. */}
      {slots.length ? (
        <rect
          x={Math.min(...xs)}
          y={Math.min(...ys)}
          width={Math.max(...xs) - Math.min(...xs)}
          height={Math.max(...ys) - Math.min(...ys)}
          className="pitch-box"
          stroke={colour}
        />
      ) : null}
      <line x1={line} y1={1} x2={line} y2={WD - 1} className="pitch-backline" stroke={colour} />
      {rows(slots).map((r, i) => (
        <polyline key={i} points={r.map((p) => `${p[0]},${p[1]}`).join(' ')} className="pitch-row" stroke={colour} />
      ))}
      {slots.map((p, i) => (
        <Player key={i} p={p} team={team} r={1.5} />
      ))}
    </g>
  )
}

function HeatLayer({ cells, colour }: { cells: readonly number[]; colour: string }) {
  const max = Math.max(1, ...cells)
  const cw = L / HEAT_COLS
  const ch = WD / HEAT_ROWS
  return (
    <g>
      {cells.map((v, i) =>
        v > 0 ? <rect key={i} x={(i % HEAT_COLS) * cw} y={Math.floor(i / HEAT_COLS) * ch} width={cw} height={ch} fill={colour} opacity={0.08 + 0.72 * Math.sqrt(v / max)} /> : null,
      )}
    </g>
  )
}

function Cross({ p, colour }: { p: P; colour: string }) {
  const r = 1
  return (
    <g stroke={colour} strokeWidth={0.45} strokeLinecap="round">
      <line x1={p[0] - r} y1={p[1] - r} x2={p[0] + r} y2={p[1] + r} />
      <line x1={p[0] - r} y1={p[1] + r} x2={p[0] + r} y2={p[1] - r} />
    </g>
  )
}

// --- Stats -------------------------------------------------------------------------------------

function Stat({ label, value, note }: { label: string; value: string; note?: string }) {
  return (
    <div className="pitch-stat">
      <span className="label">{label}</span>
      <b>{value}</b>
      {note ? <span className="muted small">{note}</span> : null}
    </div>
  )
}

const pct = (k: number, n: number) => `${Math.round((100 * k) / Math.max(1e-9, n))}%`
const metres = (v: number | undefined) => (v === undefined || !Number.isFinite(v) ? '—' : `${Math.round(v)} m`)
const block = (line: number) => (line < BLOCKS.low ? 'low block' : line > BLOCKS.high ? 'high line' : 'mid block')

// --- The panel ---------------------------------------------------------------------------------

/** The pitch for one match or all of them, with a picker, views and the lines that go with it. */
export function PitchPanel({
  profile,
  matches,
  insights,
  onPlaybook,
}: {
  profile: GameProfile
  /** Oldest first; the last may be the match being played. */
  matches: readonly PitchMatch[]
  /** Lines for every match (from the engine); recomputed here when one match is picked. */
  insights?: readonly PitchInsight[]
  onPlaybook?: (id: string) => void
}) {
  const [pick, setPick] = useState<string>('all')
  const [view, setView] = useState<View>('shape')
  const [phase, setPhase] = useState<'out' | 'in'>('out')
  const [show, setShow] = useState({ ours: true, theirs: true })
  const [heatOf, setHeatOf] = useState<'ours' | 'theirs' | 'ball'>('ours')
  const [goalAt, setGoalAt] = useState(0)
  const formations = profile.knowledge.formations ?? []
  const book = useMemo(() => new Map((profile.knowledge.playbook ?? []).map((e) => [e.id, e])), [profile])

  const known = useMemo(() => matches.filter((m) => m.record.sides === 'known'), [matches])
  const chosen = useMemo(() => (pick === 'all' ? known : matches.filter((m) => m.id === pick)), [pick, known, matches])
  const recs = useMemo(() => chosen.map((m) => m.record), [chosen])
  const lines = useMemo(() => (pick === 'all' && insights ? insights : pitchInsights(recs, null, 8, formations)), [pick, insights, recs, formations])

  if (!matches.length)
    return (
      <div className="empty small">
        No matches on the radar yet. Play a match with the 2D radar on (Settings in the game: Game Settings › Visual › Radar: 2D), or run the EA SPORTS FC demo from the Coach tab.
      </div>
    )

  const ours = mergeShape(recs.map((r) => r.shape[phase]))
  // "With the ball" for the player is "without" for the opponent.
  const theirs = mergeShape(recs.map((r) => r.theirShape[phase === 'out' ? 'in' : 'out']))
  const ourDef = mergeShape(recs.map((r) => r.shape.out))
  const theirDef = mergeShape(recs.map((r) => r.theirShape.out))
  const sides = recs.every((r) => r.sides === 'known') ? 'known' : 'unknown'
  const nameOurs = sides === 'known' ? 'You' : 'Team A'
  const nameTheirs = sides === 'known' ? 'Opponent' : 'Team B'
  const poss = recs.reduce((a, r) => [a[0] + r.possession.ours, a[1] + r.possession.theirs], [0, 0])
  const terr = recs.reduce((a, r) => [a[0] + r.territory[0], a[1] + r.territory[1], a[2] + r.territory[2]], [0, 0, 0])
  const terrN = terr[0] + terr[1] + terr[2]
  const heat = recs.reduce(
    (a, r) => ({ ball: a.ball.map((v, i) => v + (r.heat.ball[i] ?? 0)), ours: a.ours.map((v, i) => v + (r.heat.ours[i] ?? 0)), theirs: a.theirs.map((v, i) => v + (r.heat.theirs[i] ?? 0)) }),
    { ball: new Array(HEAT_COLS * HEAT_ROWS).fill(0), ours: new Array(HEAT_COLS * HEAT_ROWS).fill(0), theirs: new Array(HEAT_COLS * HEAT_ROWS).fill(0) },
  )
  const events = recs.flatMap((r) => r.events)
  const goals = chosen.flatMap((m) => m.record.goals.map((g, i) => ({ g, match: m, i })))
  const goal = goals[Math.min(goalAt, goals.length - 1)] ?? null
  const shots = events.filter((e) => e.kind === 'shot')
  const theirEntry = theirDef?.formation ? formationEntries(theirDef.formation, formations)[0] : undefined
  const ourEntry = ourDef?.formation ? formationEntries(ourDef.formation, formations)[0] : undefined

  return (
    <div className="pitch-panel">
      <div className="pitch-controls">
        <label className="small">
          <span className="sr-only">Match</span>
          <select value={pick} onChange={(e) => setPick(e.target.value)} aria-label="Which match">
            <option value="all">{known.length === 1 ? 'The match' : `All ${known.length} matches`}</option>
            {[...matches].reverse().map((m) => (
              <option key={m.id} value={m.id}>
                {m.label}
                {m.record.sides === 'known' ? '' : ' (teams not told apart)'}
              </option>
            ))}
          </select>
        </label>
        <Segmented<View>
          label="What to show"
          value={view}
          onChange={setView}
          options={[
            { value: 'shape', label: 'Shape' },
            { value: 'heat', label: 'Heat' },
            { value: 'moments', label: 'Lost & won, shots' },
            { value: 'goals', label: `Goals (${goals.length})` },
            { value: '3d', label: '3D' },
          ]}
        />
      </div>

      {view === 'shape' || view === '3d' ? (
        <div className="pitch-controls">
          <Segmented<'out' | 'in'>
            label="Phase"
            value={phase}
            onChange={setPhase}
            options={[
              { value: 'out', label: `${nameOurs === 'You' ? 'You' : 'A'} without the ball` },
              { value: 'in', label: `${nameOurs === 'You' ? 'You' : 'A'} with the ball` },
            ]}
          />
          <Switch checked={show.ours} onChange={(v) => setShow({ ...show, ours: v })} label={<Key team="ours" text={nameOurs} />} />
          <Switch checked={show.theirs} onChange={(v) => setShow({ ...show, theirs: v })} label={<Key team="theirs" text={nameTheirs} />} />
        </div>
      ) : null}

      {view === 'shape' ? (
        <>
          <PitchSvg label={`Average positions ${phase === 'out' ? 'without' : 'with'} the ball: ${nameOurs} ${ours?.formation || ''}, ${nameTheirs} ${theirs?.formation || ''}`}>
            {show.theirs && theirs ? <ShapeLayer shape={theirs} team="theirs" mirrorIt /> : null}
            {show.ours && ours ? <ShapeLayer shape={ours} team="ours" mirrorIt={false} /> : null}
            <text x={2} y={-1.2} className="pitch-caption">
              ← {nameOurs === 'You' ? 'your goal' : 'A defends'}
            </text>
            <text x={L - 2} y={-1.2} className="pitch-caption end">
              {nameOurs === 'You' ? 'you attack' : 'A attacks'} →
            </text>
          </PitchSvg>
          <div className="pitch-stats">
            {ours ? (
              <>
                <Stat label={`${nameOurs}: shape`} value={ours.formation || 'no clear shape'} note={ourEntry ? ourEntry.name : undefined} />
                <Stat label="Deepest outfield player" value={metres(ours.line)} note={phase === 'out' ? block(ours.line) : 'from your goal'} />
                <Stat label="Length" value={metres(ours.length)} note={`pros ${TEAM_LENGTH.min}–${TEAM_LENGTH.max} m`} />
                <Stat label="Width" value={metres(ours.width)} note={`pros ${TEAM_WIDTH.min}–${TEAM_WIDTH.max} m`} />
                {ours.gap !== undefined ? <Stat label="Back line to midfield" value={metres(ours.gap)} note={`about ${LINE_GAP} m when defending well`} /> : null}
                {phase === 'out' && ours.press !== undefined ? <Stat label="Someone within 5 m of the ball" value={pct(ours.press, 1)} note="of the time they had it" /> : null}
              </>
            ) : (
              <span className="muted small">Not enough frames {phase === 'out' ? 'without' : 'with'} the ball yet.</span>
            )}
            {theirs ? <Stat label={`${nameTheirs}: shape ${phase === 'out' ? 'with' : 'without'} the ball`} value={theirs.formation || 'no clear shape'} /> : null}
          </div>
        </>
      ) : null}

      {view === 'heat' ? (
        <>
          <div className="pitch-controls">
            <Segmented<'ours' | 'theirs' | 'ball'>
              label="Whose heat"
              value={heatOf}
              onChange={setHeatOf}
              options={[
                { value: 'ours', label: nameOurs === 'You' ? 'Your players' : 'Team A' },
                { value: 'theirs', label: nameOurs === 'You' ? 'Their players' : 'Team B' },
                { value: 'ball', label: 'The ball' },
              ]}
            />
          </div>
          <PitchSvg label={`Where ${heatOf === 'ball' ? 'the ball' : heatOf === 'ours' ? nameOurs : nameTheirs} spent the match`}>
            <HeatLayer cells={heat[heatOf]} colour={heatOf === 'ours' ? TEAM.ours : heatOf === 'theirs' ? TEAM.theirs : TEAM.ball} />
          </PitchSvg>
          <div className="pitch-stats">
            <Stat label="Possession on the radar" value={poss[0] + poss[1] ? pct(poss[0], poss[0] + poss[1]) : '—'} note={nameOurs === 'You' ? 'yours' : 'team A'} />
            <div className="pitch-stat wide">
              <span className="label">Where the ball was</span>
              <div className="thirds" role="img" aria-label={`Ball in ${nameOurs === 'You' ? 'your' : 'A’s'} third ${pct(terr[0], terrN)}, middle ${pct(terr[1], terrN)}, ${nameOurs === 'You' ? 'their' : 'B’s'} third ${pct(terr[2], terrN)}`}>
                {terr.map((v, i) => (
                  <span key={i} style={{ flexGrow: Math.max(0.0001, v) }} className={`third t${i}`}>
                    {terrN ? pct(v, terrN) : ''}
                  </span>
                ))}
              </div>
              <span className="muted small">{nameOurs === 'You' ? 'Your third · middle · their third' : 'A’s third · middle · B’s third'}</span>
            </div>
          </div>
        </>
      ) : null}

      {view === 'moments' ? (
        <>
          <PitchSvg label={`Lost balls, regains and shots: ${events.filter((e) => e.kind === 'turnover' && e.team === 'ours').length} lost, ${shots.length} shots`}>
            <rect x={0} y={0} width={L / 3} height={WD} className="pitch-third" />
            {events
              .filter((e) => e.kind === 'turnover' && !e.out)
              .map((e, i) =>
                e.team === 'ours' ? (
                  <Cross key={`t${i}`} p={[e.x, e.y]} colour="var(--live)" />
                ) : (
                  <path key={`t${i}`} d={`M ${e.x} ${e.y - 1.1} L ${e.x + 1} ${e.y + 0.8} L ${e.x - 1} ${e.y + 0.8} Z`} fill="var(--accent)" />
                ),
              )}
            {shots.map((e, i) => {
              const goalX = e.team === 'ours' ? L : 0
              const xg = e.team === 'ours' ? zoneXg(e.x, e.y) : zoneXg(L - e.x, WD - e.y)
              return (
                <g key={`s${i}`}>
                  <line x1={e.x} y1={e.y} x2={goalX} y2={WD / 2} className="pitch-shot" stroke={e.team === 'ours' ? TEAM.ours : TEAM.theirs} />
                  <circle cx={e.x} cy={e.y} r={0.6 + xg * 4} fill="none" stroke={e.team === 'ours' ? TEAM.ours : TEAM.theirs} strokeWidth={0.4} />
                </g>
              )
            })}
          </PitchSvg>
          <ul className="pitch-legend small" aria-label="Legend">
            <li>
              <span className="lg-cross" aria-hidden="true">✕</span> {nameOurs === 'You' ? 'You lost the ball' : 'A lost it'} ({events.filter((e) => e.kind === 'turnover' && e.team === 'ours' && !e.out).length})
            </li>
            <li>
              <span className="lg-tri" aria-hidden="true">▲</span> {nameOurs === 'You' ? 'You won it' : 'B lost it'} ({events.filter((e) => e.kind === 'turnover' && e.team === 'theirs' && !e.out).length})
            </li>
            <li>
              <span className="lg-shot" aria-hidden="true">◯</span> Shots, ring size by the zone’s usual chance of a goal ({shots.filter((e) => e.team === 'ours').length} {nameOurs === 'You' ? 'yours' : 'A'},{' '}
              {shots.filter((e) => e.team === 'theirs').length} {nameOurs === 'You' ? 'theirs' : 'B'})
            </li>
            <li className="muted">The shaded band is {nameOurs === 'You' ? 'your' : 'A’s'} own third. Sampled about twice a second, so quick exchanges are missed.</li>
          </ul>
        </>
      ) : null}

      {view === 'goals' ? (
        goal ? (
          <>
            <div className="pitch-controls">
              <label className="small">
                <span className="sr-only">Goal</span>
                <select value={Math.min(goalAt, goals.length - 1)} onChange={(e) => setGoalAt(Number(e.target.value))} aria-label="Which goal">
                  {goals.map((x, i) => (
                    <option key={i} value={i}>
                      {x.g.for ? (nameOurs === 'You' ? 'Scored' : 'A scored') : nameOurs === 'You' ? 'Conceded' : 'B scored'} · {gameClock(x.g.t)}
                      {pick === 'all' ? ` · ${x.match.label}` : ''}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <GoalReplay goal={goal.g} names={[nameOurs, nameTheirs]} key={`${goal.match.id}-${goal.i}`} />
          </>
        ) : (
          <div className="empty small">No goals with a radar view before them yet.</div>
        )
      ) : null}

      {view === '3d' ? (
        <Suspense fallback={<div className="scene3d loading">Building the stadium…</div>}>
          <Stadium3D
            ours={show.ours && ours ? ours.slots : []}
            theirs={show.theirs && theirs ? theirs.slots.map(mirror) : []}
            goals={goals.map((x) => x.g)}
            label={`The stadium with ${nameOurs}'s average shape ${phase === 'out' ? 'without' : 'with'} the ball`}
          />
        </Suspense>
      ) : null}

      {theirEntry || ourEntry ? (
        <div className="formation-cards">
          {theirEntry ? <FormationCard entry={theirEntry} who={nameOurs === 'You' ? 'Their shape' : 'Team B'} beat /> : null}
          {ourEntry ? <FormationCard entry={ourEntry} who={nameOurs === 'You' ? 'Your shape' : 'Team A'} /> : null}
        </div>
      ) : null}

      {lines.length ? (
        <ul className="pitch-insights">
          {lines.map((l) => (
            <li key={l.id}>
              {l.text}
              {l.playbook && book.has(l.playbook) && onPlaybook ? (
                <>
                  {' '}
                  <button type="button" className="link-btn small" onClick={() => onPlaybook(l.playbook!)}>
                    {book.get(l.playbook)!.title} →
                  </button>
                </>
              ) : null}
            </li>
          ))}
        </ul>
      ) : sides === 'unknown' ? (
        <p className="muted small">
          The two teams were told apart but not which is yours: set your radar colours on the Coach screen (and in the game) so the lines below are about your team.
        </p>
      ) : null}
      <p className="muted small">
        From the radar only: positions about a metre out, sampled twice a second; the pitch is taken as 105 × 68 m. Yardsticks are real-football tracking figures (FC publishes
        none). Associations, not proof of cause.
      </p>
    </div>
  )
}

function Key({ team, text }: { team: 'ours' | 'theirs'; text: string }) {
  return (
    <span className="team-key">
      <svg viewBox="-2 -2 4 4" width="12" height="12" aria-hidden="true">
        {team === 'ours' ? <circle r={1.6} fill={TEAM.ours} /> : <rect x={-1.5} y={-1.5} width={3} height={3} rx={0.3} fill={TEAM.theirs} />}
      </svg>
      {text}
    </span>
  )
}

function FormationCard({ entry, who, beat = false }: { entry: FormationEntry; who: string; beat?: boolean }) {
  return (
    <details className="formation-card">
      <summary>
        <span className="label">{who}</span> <b>{entry.name}</b>
        {entry.unverified ? <span className="badge">Unverified</span> : null}
      </summary>
      {entry.radarLook ? <p className="small">On the radar: {entry.radarLook}</p> : null}
      {entry.who ? <p className="small muted">{entry.who}</p> : null}
      <div className="grid-2 small">
        <div>
          <span className="label">{beat ? 'How to beat it' : 'Where it is weak'}</span>
          <ul>{(beat ? entry.counters : entry.weaknesses).map((x) => <li key={x}>{x}</li>)}</ul>
        </div>
        <div>
          <span className="label">{beat ? 'Where it is strong' : 'What it does well'}</span>
          <ul>{entry.strengths.map((x) => <li key={x}>{x}</li>)}</ul>
        </div>
      </div>
      {entry.sources.length ? (
        <p className="muted small">
          {entry.sources.map((s, i) => (
            <span key={s.url}>
              {i ? ' · ' : ''}
              <a href={s.url} target="_blank" rel="noreferrer">
                {s.title}
              </a>
            </span>
          ))}
        </p>
      ) : null}
    </details>
  )
}

// --- Goal replay -------------------------------------------------------------------------------

/** Positions at a time between two frames (players matched by order within a team, nearest otherwise). */
function frameAt(frames: readonly PitchFrame[], t: number): PitchFrame {
  if (t <= frames[0].t) return frames[0]
  const j = frames.findIndex((f) => f.t >= t)
  if (j < 0) return frames[frames.length - 1]
  const a = frames[j - 1]
  const b = frames[j]
  const k = (t - a.t) / Math.max(1e-6, b.t - a.t)
  const lerp = (p: P, q: P): P => [p[0] + (q[0] - p[0]) * k, p[1] + (q[1] - p[1]) * k]
  const team = (pa: readonly P[], pb: readonly P[]) =>
    pa.map((p) => {
      let best: P | null = null
      let bd = 6
      for (const q of pb) {
        const d = Math.hypot(q[0] - p[0], q[1] - p[1])
        if (d < bd) {
          bd = d
          best = q
        }
      }
      return best ? lerp(p, best) : p
    })
  return { t, ball: a.ball && b.ball ? lerp(a.ball, b.ball) : (a.ball ?? b.ball), ours: team(a.ours, b.ours), theirs: team(a.theirs, b.theirs) }
}

/** The last seconds before a goal, played back on the pitch (play, pause, scrub). */
export function GoalReplay({ goal, names }: { goal: PitchGoal; names: readonly [string, string] }) {
  const frames = goal.frames
  const t0 = frames[0]?.t ?? goal.t
  const t1 = frames[frames.length - 1]?.t ?? goal.t
  const [t, setT] = useState(t0)
  const [playing, setPlaying] = useState(true)
  const raf = useRef(0)
  useEffect(() => {
    if (!playing || frames.length < 2) return
    let last = performance.now()
    const step = (now: number) => {
      const dt = (now - last) / 1000
      last = now
      setT((x) => {
        const n = x + dt
        return n > t1 + 1.2 ? t0 : n
      })
      raf.current = requestAnimationFrame(step)
    }
    raf.current = requestAnimationFrame(step)
    return () => cancelAnimationFrame(raf.current)
  }, [playing, frames.length, t0, t1])
  if (!frames.length) return <div className="empty small">The radar was hidden before this goal.</div>
  const f = frameAt(frames, Math.min(t, t1))
  // The ball's path so far.
  const trail = frames.filter((x) => x.t <= t && x.ball).map((x) => x.ball!)
  const d = goal.defending
  const defender = goal.for ? (names[1] === 'Opponent' ? 'The opponent' : names[1]) : names[0] === 'You' ? 'Your team' : names[0]
  return (
    <div className="goal-replay">
      <PitchSvg label={`${goal.for ? `${names[0]} scored` : `${names[1]} scored`} at ${gameClock(goal.t)}: the ${Math.round(t1 - t0)} seconds before`}>
        {trail.length > 1 ? <polyline points={trail.map((p) => `${p[0]},${p[1]}`).join(' ')} className="pitch-trail" /> : null}
        {goal.shot ? <circle cx={goal.shot[0]} cy={goal.shot[1]} r={1.6} className="pitch-shot-spot" /> : null}
        {f.theirs.map((p, i) => (
          <Player key={`b${i}`} p={p} team="theirs" />
        ))}
        {f.ours.map((p, i) => (
          <Player key={`a${i}`} p={p} team="ours" />
        ))}
        {f.ball ? <Ball p={f.ball} /> : null}
        <text x={L / 2} y={-1.2} className="pitch-caption mid">
          {gameClock(Math.min(t, t1))} · goal at {gameClock(goal.t)}
        </text>
      </PitchSvg>
      <div className="replay-bar">
        <button type="button" className="btn sm" onClick={() => setPlaying(!playing)} aria-label={playing ? 'Pause' : 'Play'}>
          {playing ? '❚❚' : '▶'}
        </button>
        <input type="range" min={t0} max={t1} step={0.05} value={Math.min(t, t1)} onChange={(e) => (setPlaying(false), setT(Number(e.target.value)))} aria-label="Time before the goal" />
        <span className="small muted">{`−${Math.max(0, goal.t - Math.min(t, t1)).toFixed(1)} s`}</span>
      </div>
      {d ? (
        <p className="small">
          {defender} at the shot: {d.behindBall} outfield {d.behindBall === 1 ? 'player' : 'players'} behind the ball, deepest {Math.round(d.line)} m from goal, the team {Math.round(d.length)} m
          long and {Math.round(d.width)} m wide.
        </p>
      ) : null}
    </div>
  )
}

// --- 3D ---------------------------------------------------------------------------------------

function Stadium3D({ ours, theirs, goals, label }: { ours: readonly P[]; theirs: readonly P[]; goals: readonly PitchGoal[]; label: string }) {
  const [replay, setReplay] = useState<number | null>(null)
  const [t, setT] = useState(0)
  const goal = replay !== null ? goals[replay] : null
  useEffect(() => {
    if (!goal || goal.frames.length < 2) return
    const t0 = goal.frames[0].t
    const t1 = goal.frames[goal.frames.length - 1].t
    setT(t0)
    let last = performance.now()
    let id = 0
    const step = (now: number) => {
      const dt = (now - last) / 1000
      last = now
      setT((x) => (x + dt > t1 + 1.2 ? t0 : x + dt))
      id = requestAnimationFrame(step)
    }
    id = requestAnimationFrame(step)
    return () => cancelAnimationFrame(id)
  }, [goal])
  const f = goal && goal.frames.length ? frameAt(goal.frames, Math.min(t, goal.frames[goal.frames.length - 1].t)) : null
  return (
    <div>
      <PitchScene3D ours={f ? f.ours : ours} theirs={f ? f.theirs : theirs} ball={f ? f.ball : null} label={label} />
      {goals.length ? (
        <div className="pitch-controls">
          <label className="small">
            Play back a goal in the stadium:{' '}
            <select value={replay ?? ''} onChange={(e) => setReplay(e.target.value === '' ? null : Number(e.target.value))}>
              <option value="">No — the average shape</option>
              {goals.map((g, i) => (
                <option key={i} value={i}>
                  {g.for ? 'Scored' : 'Conceded'} · {gameClock(g.t)}
                </option>
              ))}
            </select>
          </label>
        </div>
      ) : null}
    </div>
  )
}

// --- Live -------------------------------------------------------------------------------------

/** The radar now: every player and the ball, who has it, and possession so far (Coach screen). */
export function LivePitch({ report }: { report: PitchReport }) {
  const live: PitchLive | null = report.live
  const names = live?.sides === 'known' ? ['You', 'Opponent'] : ['Team A', 'Team B']
  const fresh = !!live && live.ok && live.ageMs < 3000
  const poss = live ? live.possession.ours + live.possession.theirs : 0
  return (
    <div className="live-pitch">
      <PitchSvg compact label={fresh ? `${live!.ours.length} and ${live!.theirs.length} players on the radar${live!.owner ? `, ${live!.owner === 'ours' ? names[0] : names[1]} on the ball` : ''}` : 'Radar not seen'}>
        {fresh
          ? [
              ...live!.theirs.map((p, i) => <Player key={`b${i}`} p={p} team="theirs" r={1.6} />),
              ...live!.ours.map((p, i) => <Player key={`a${i}`} p={p} team="ours" r={1.6} />),
              live!.ball ? <Ball key="ball" p={live!.ball} r={1} /> : null,
            ]
          : null}
      </PitchSvg>
      <div className="live-pitch-line small">
        {fresh ? (
          <>
            <Key team="ours" text={names[0]} /> <Key team="theirs" text={names[1]} />
            <span className="muted">
              {live!.owner ? ` · ${live!.owner === 'ours' ? names[0] : names[1]} on the ball` : ''}
              {poss > 0 ? ` · possession ${pct(live!.possession.ours, poss)}` : ''}
            </span>
          </>
        ) : (
          <span className="muted">Radar not seen (a replay, a menu, or the radar is off or 3D).</span>
        )}
      </div>
      {report.hint ? <p className="warn-text small">⚠ {report.hint}</p> : null}
    </div>
  )
}
