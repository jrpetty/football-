import { useMemo, useState } from 'react'
import type { CloudStatus } from '../../../shared/cloud.ts'
import { storedInsights, toPatternRecords } from '../../core/memory/records.ts'
import { mistakeLine } from '../../core/language.ts'
import type { GameProfile } from '../../core/profile/schema.ts'
import type { CoachSettings } from '../../core/settings.ts'
import type { PatternRecord } from '../../core/types.ts'
import { GradeBadge, Notice } from '../components/common.tsx'
import { Breakdown, ZoneMap } from '../components/viz.tsx'
import { BattleLog, BenchmarkChart, FactorCard, IntelPanel, LearnedPanel, Playbook, ReviewCard } from '../components/coaching.tsx'
import { MapPanel } from '../components/MapView.tsx'
import { PitchPanel, type PitchMatch } from '../components/PitchView.tsx'
import { useOfficialSource } from '../officialSource.ts'
import { ArsenalPanel } from '../components/ArsenalPanel.tsx'
import { dateShort, formatMs, pct } from '../format.ts'
import type { CoachSession, SessionState } from '../session.ts'
import { policyFor } from '../policy.ts'

interface Props {
  state: SessionState
  session: CoachSession
  profile: GameProfile
  settings: CoachSettings
  cloud: CloudStatus
}

export function InsightsView({ state, session, profile, settings, cloud }: Props) {
  const official = useOfficialSource(session, state.overheads)
  const report = state.report?.profileId === profile.id ? state.report : null
  const now = useMemo(() => new Date(), [state.snapshot, report])
  const mode = settings.profileModeId
  const sessionRecords = useMemo(() => (report ? toPatternRecords(profile, report.insights, state.snapshot, mode, now) : []), [profile, report, state.snapshot, mode, now])
  const storedRecords = useMemo(
    () => toPatternRecords(profile, storedInsights(profile, state.snapshot, mode, now), state.snapshot, mode, now),
    [profile, state.snapshot, mode, now],
  )
  // While a session runs, its insights already include the stored history.
  const patterns = report ? sessionRecords : storedRecords
  const canEdit = settings.saveTendencies && !!state.snapshot
  const book = useMemo(() => new Map((profile.knowledge.playbook ?? []).map((e) => [e.id, e])), [profile])
  const [openPlay, setOpenPlay] = useState<string | null>(null)
  const showPlay = (id: string) => {
    setOpenPlay(id)
    requestAnimationFrame(() => document.getElementById(`pb-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
  }
  // Playbook entries the findings point to: shown first and marked.
  const linked = useMemo(() => {
    const ids = new Set<string>()
    for (const f of report?.factors ?? []) for (const x of f.findings) if (x.playbook) ids.add(x.playbook)
    for (const b of report?.benchmarks ?? []) if (b.worst && b.spec.playbook) ids.add(b.spec.playbook)
    for (const m of report?.mistakes ?? []) if (m.totals.count && m.spec.playbook) ids.add(m.spec.playbook)
    for (const f of state.review.review?.focus ?? []) if (f.playbook) ids.add(f.playbook)
    for (const l of report?.pitch?.insights ?? []) if (l.playbook) ids.add(l.playbook)
    return ids
  }, [report, state.review.review])
  const seenFights = (report?.factors ?? []).filter((f) => f.taggedLosses + f.taggedWins + f.untagged > 0)
  const fights = seenFights.filter((f) => f.taggedLosses + f.taggedWins > 0)
  const unread = seenFights.filter((f) => f.taggedLosses + f.taggedWins === 0)
  const benches = (report?.benchmarks ?? []).filter((b) => b.points.length > 0)
  const deep = cloud.available && cloud.settings.enabled && cloud.key.set
  const capturing = state.phase !== 'idle'
  // In modes without live coaching, what this match shows (their side, where you are and die) waits for the match to end.
  const policy = useMemo(() => policyFor(profile, settings), [profile, settings])
  const held = capturing && !policy.scope.has('live')
  // Saved games plus this session's, once each: what the coach learns from.
  const learnFrom = useMemo(() => {
    const seen = new Set<string>()
    return [...state.history, ...(report?.matches ?? [])].filter((m) => m.gameProfileId === profile.id && (seen.has(m.id) ? false : (seen.add(m.id), true)))
  }, [state.history, report, profile.id])
  // Matches read from the radar (football), oldest first, with the one being played (when it may be shown).
  const pitchMatches = useMemo<PitchMatch[]>(() => {
    const done = learnFrom.flatMap((m) =>
      m.pitch ? [{ id: m.id, label: `${dateShort(m.endedAt)}${m.score ? ` · ${m.score.player}–${m.score.opponent}` : ''}`, record: m.pitch }] : [],
    )
    const now = !held && report?.pitch?.match ? [{ id: 'current', label: 'This match so far', record: report.pitch.match }] : []
    return [...done, ...now]
  }, [learnFrom, report, held])
  // Open on this game's kind (the opponent being played, or the last one), else on every game.
  const learnedStart = useMemo(() => {
    if (report?.learned && Object.keys(report.learned.filter).length) return report.learned.filter
    const dims = [...learnFrom].reverse().find((m) => m.scout)?.scout?.dims ?? {}
    return Object.fromEntries((profile.learning?.dimensions ?? []).filter((d) => d.live && dims[d.id]).map((d) => [d.id, dims[d.id]]))
  }, [report, learnFrom, profile])

  return (
    <div className="page">
      <div>
        <h1>Insights · {profile.game.title}</h1>
        {report && Object.keys(report.context).length ? (
          <p className="small context-line">
            {Object.entries(report.context)
              .map(([k, v]) => `${profile.vision?.state.find((x) => x.key === k)?.label ?? k}: ${v}`)
              .join(' · ')}
          </p>
        ) : null}
        <p className="lede">
          Where things happen, what keeps going wrong, and which habits go with worse outcomes — counted from what was visible,
          with how sure the coach is. Associations, not proof of cause.
        </p>
      </div>

      {!report && !state.snapshot ? (
        <Notice level="info">No data yet for this game. Play a session (or a demo replay from the Coach tab) and come back.</Notice>
      ) : null}

      {report && (
        <section className="panel review-panel" aria-labelledby="review-h">
          <div className="panel-head">
            <h2 id="review-h">Coach’s review</h2>
            <div className="btn-row">
              <button type="button" className="btn sm" onClick={() => void session.reviewSession(cloud, { deep: false })} disabled={state.review.status === 'working'}>
                {state.review.review ? 'Rebuild' : 'Review this session'}
              </button>
              {deep ? (
                <button type="button" className="btn sm primary" onClick={() => void session.reviewSession(cloud, { deep: true })} disabled={state.review.status === 'working'}>
                  {state.review.status === 'working' ? 'Claude is reviewing…' : 'Deeper review with Claude'}
                </button>
              ) : null}
              {state.review.review ? (
                <button type="button" className="btn sm ghost" onClick={() => session.readReview()} disabled={settings.muted}>
                  🔊 Read it to me
                </button>
              ) : null}
            </div>
          </div>
          {state.review.review ? (
            <>
              <ReviewCard review={state.review.review} book={book} onPlaybook={showPlay} />
              <p className="muted small">
                {state.review.review.author === 'ai'
                  ? `Written by ${cloud.settings.answerModel} from this session's counts and the playbook${state.review.costUsd ? ` · $${state.review.costUsd.toFixed(3)}` : ''}. Check it against the numbers below.`
                  : 'Built on this computer from the counts below and the playbook.'}
                {state.review.note ? ` ${state.review.note}` : ''}
              </p>
            </>
          ) : state.review.status === 'ready' ? (
            <div className="empty small">Not enough yet for a review: the coach needs a few deaths, goals or readings it could see clearly.</div>
          ) : (
            <p className="muted small">
              The three things most worth working on, each with what was counted, what good players do instead, the fix and a drill.
              {capturing ? ' In online modes it opens after the match.' : ''}
            </p>
          )}
        </section>
      )}

      {held && (report?.intel || report?.map) ? (
        <Notice level="info">
          In this mode, what the current match shows (their side, where you are, the deaths on this map) opens after the match, so the coach never helps live where
          that is not allowed. Saved games and the reference are below.
        </Notice>
      ) : null}

      {!held && report?.intel && (report.intel.faction || report.intel.buildings.length > 0 || report.intel.units.length > 0) && (
        <section className="panel" aria-labelledby="intel-h">
          <div className="panel-head">
            <h2 id="intel-h">Their side</h2>
            <span className="muted small">Only what your screen showed: scouted buildings, their army in view, the loading screen</span>
          </div>
          <IntelPanel intel={report.intel} profile={profile} />
        </section>
      )}

      {profile.positioning && (
        <section className="panel" aria-labelledby="map-h">
          <div className="panel-head">
            <h2 id="map-h">The map, learned from watching</h2>
            <span className="muted small">Stitched from your own minimap: where you went, fought and died, and where they shot from</span>
          </div>
          <MapPanel profile={profile} records={learnFrom.flatMap((m) => (m.mapModel ? [m.mapModel] : []))} live={held ? null : report?.map} mosaics={state.mosaics} official={official} />
        </section>
      )}

      {profile.pitch ? (
        <section className="panel" aria-labelledby="pitch-h">
          <div className="panel-head">
            <h2 id="pitch-h">The pitch</h2>
            <span className="muted small">Read from the 2D radar: both teams’ shapes, where the ball went, and the seconds before every goal</span>
          </div>
          <PitchPanel profile={profile} matches={pitchMatches} insights={held ? undefined : report?.pitch?.insights} onPlaybook={showPlay} />
        </section>
      ) : null}

      {profile.knowledge.arsenal?.length ? (
        <section className="panel" aria-labelledby="arsenal-h">
          <div className="panel-head">
            <h2 id="arsenal-h">Arsenal</h2>
            <span className="muted small">{profile.knowledge.referenceVersion ?? 'From the pack'} · how each works, its range and numbers, how to beat it</span>
          </div>
          <ArsenalPanel profile={profile} yours={held ? undefined : report?.map?.model?.weapons} killers={held ? undefined : report?.map?.model?.killerWeapons} />
        </section>
      ) : null}

      {profile.learning && (
        <section className="panel" aria-labelledby="learned-h">
          <div className="panel-head">
            <h2 id="learned-h">What the coach has learned</h2>
            <span className="muted small">{settings.saveTendencies ? 'Your saved games plus this session' : 'This session only: turn on “Remember my games” to keep it'}</span>
          </div>
          <LearnedPanel key={JSON.stringify(learnedStart)} profile={profile} records={learnFrom} initial={learnedStart} />
        </section>
      )}

      {report && report.battles.length > 0 && (
        <section className="panel" aria-labelledby="battles-h">
          <div className="panel-head">
            <h2 id="battles-h">Fights this session</h2>
            <span className="muted small">
              {report.battles.filter((b) => b.outcome === 'won').length} won · {report.battles.filter((b) => b.outcome === 'lost').length} lost ·{' '}
              {report.battles.filter((b) => b.outcome === 'unclear').length} unclear · army = population minus villagers
            </span>
          </div>
          <BattleLog battles={report.battles} profile={profile} />
          <p className="muted small">
            A fight is called only when the frames and the army size agree; the rest are unclear. Fights off screen are not seen.
          </p>
        </section>
      )}

      {seenFights.length > 0 && (
        <section className="panel" aria-labelledby="fights-h">
          <div className="panel-head">
            <h2 id="fights-h">How your fights go</h2>
            <span className="muted small">
              {profile.battles ? 'Read from the fight frames and your army size' : 'Read from killcams and replays'} · likely, not certain · unreadable ones are counted, not guessed
            </span>
          </div>
          <div className="factors">
            {fights.map((f) => (
              <FactorCard key={f.spec.id} result={f} onPlaybook={showPlay} />
            ))}
          </div>
          {unread.length ? <p className="muted small">Not readable yet: {unread.map((f) => f.spec.label.toLowerCase()).join(', ')}.</p> : null}
        </section>
      )}

      {benches.length > 0 && (
        <section className="panel" aria-labelledby="bench-h">
          <div className="panel-head">
            <h2 id="bench-h">Against the clock</h2>
            <span className="muted small">Your readings at each checkpoint against a simple, explainable curve</span>
          </div>
          {benches.map((b) => (
            <div key={b.spec.id}>
              <h3 className="bench-title">{b.spec.label}</h3>
              <BenchmarkChart result={b} />
            </div>
          ))}
        </section>
      )}

      {report && report.heatmaps.length > 0 && (
        <section className="panel" aria-labelledby="zones-h">
          <div className="panel-head">
            <h2 id="zones-h">Where it happens</h2>
            <span className="muted small">This session · zones are approximate</span>
          </div>
          <div className="heatmaps large">
            {report.heatmaps.map((h) => (
              <div key={h.spec.id} className="heatmap-tile">
                <div className="hm-title">{h.spec.label}</div>
                <ZoneMap heatmap={h} />
                {h.claim ? (
                  <p className="small claim">
                    Most from <b>{h.claim.cellLabel}</b>: {h.claim.count} of {h.claim.located}.
                  </p>
                ) : null}
                <Breakdown heatmap={h} />
              </div>
            ))}
          </div>
        </section>
      )}

      {report && (profile.mistakes ?? []).length > 0 && (
        <section className="panel" aria-labelledby="mist-h">
          <div className="panel-head">
            <h2 id="mist-h">Mistakes</h2>
            <span className="muted small">Sustained, visible conditions — counted only while the numbers were readable</span>
          </div>
          <table className="table">
            <thead>
              <tr>
                <th>Mistake</th>
                <th className="num">Times</th>
                <th className="num">Total</th>
                <th>In words</th>
              </tr>
            </thead>
            <tbody>
              {report.mistakes.map((m) => (
                <tr key={m.spec.id}>
                  <td>
                    <span className={`sev ${m.spec.severity}`} aria-hidden="true" /> {m.spec.label}
                  </td>
                  <td className="num">{m.totals.count}</td>
                  <td className="num">{m.totals.totalMs ? formatMs(m.totals.totalMs) : '—'}</td>
                  <td className="muted small">{m.totals.count ? mistakeLine(m.spec.summary, m.totals) : 'Not seen'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}

      <section className="panel" aria-labelledby="pat-h">
        <div className="panel-head">
          <h2 id="pat-h">Patterns</h2>
          <span className="muted small">{report ? 'This session plus saved history' : 'From saved history'}</span>
        </div>
        {!settings.saveTendencies ? (
          <p className="muted small">
            Long-term memory is off, so patterns last only for this session and can’t be corrected or kept. Turn it on in Settings to
            build them up across games.
          </p>
        ) : null}
        {patterns.length === 0 ? (
          <div className="empty small">
            No patterns yet. The coach needs to see the same situation several times, handled in different ways, before it can compare them.
          </div>
        ) : (
          <div className="patterns">
            {patterns.map((r) => (
              <PatternCard key={r.id} r={r} canEdit={canEdit} session={session} />
            ))}
          </div>
        )}
      </section>

      {book.size > 0 && (
        <section className="panel" aria-labelledby="pb-h">
          <div className="panel-head">
            <h2 id="pb-h">Playbook: what good players do</h2>
            <span className="muted small">From pro players, coaches, official guides and forums · general guidance, not a reading of your games</span>
          </div>
          <Playbook profile={profile} linked={linked} open={openPlay} onToggle={setOpenPlay} />
        </section>
      )}

      {book.size === 0 && profile.knowledge.concepts.length > 0 && (
        <section className="panel" aria-labelledby="con-h">
          <div className="panel-head">
            <h2 id="con-h">What good looks like</h2>
            <span className="muted small">From the {profile.name} pack · general guidance, not a reading of your games</span>
          </div>
          <div className="concepts">
            {profile.knowledge.concepts.map((c) => (
              <details key={c.id} className="concept">
                <summary>
                  <b>{c.title}</b>
                  <span className="muted small"> — {c.summary}</span>
                </summary>
                <div className="grid-2">
                  <ul className="good">
                    {c.good.map((g) => (
                      <li key={g}>{g}</li>
                    ))}
                  </ul>
                  <ul className="bad">
                    {c.bad.map((b) => (
                      <li key={b}>{b}</li>
                    ))}
                  </ul>
                </div>
              </details>
            ))}
          </div>
        </section>
      )}
    </div>
  )
}

function PatternCard({ r, canEdit, session }: { r: PatternRecord; canEdit: boolean; session: CoachSession }) {
  const total = r.supportingInstances + r.counterInstances
  return (
    <article className={`pattern ${r.status}`}>
      <div className="pattern-head">
        <div className="sequence" aria-label="Visible sequence">
          {r.visibleSequence.map((s, i) => (
            <span key={i} className="seq-step">
              {s}
            </span>
          ))}
        </div>
        <GradeBadge grade={r.grade} />
        {r.status !== 'active' ? <span className="badge warn">{r.status === 'dismissed' ? 'Dismissed' : 'You marked this wrong'}</span> : null}
      </div>
      <p className="pattern-text">{r.explanation}</p>
      <div className="support" aria-label={`${r.supportingInstances} supporting, ${r.counterInstances} counterexamples`}>
        <span className="support-bar">
          <span className="sup" style={{ width: `${total ? (r.supportingInstances / total) * 100 : 0}%` }} />
        </span>
        <span className="small">
          <b>{r.supportingInstances}</b> supporting · <b>{r.counterInstances}</b> counterexamples · {pct(r.confidence)} sure the difference is real · last seen{' '}
          {dateShort(r.lastObservedAt)}
        </span>
      </div>
      <div className="btn-row">
        {r.status === 'active' ? (
          <>
            <button type="button" className="btn sm" disabled={!canEdit} onClick={() => void session.setVerdict(r.id, 'disputed')} title="The coach will stop using it">
              That’s wrong
            </button>
            <button type="button" className="btn sm ghost" disabled={!canEdit} onClick={() => void session.setVerdict(r.id, 'dismissed')}>
              Dismiss
            </button>
          </>
        ) : (
          <button type="button" className="btn sm" disabled={!canEdit} onClick={() => void session.setVerdict(r.id, null)}>
            Restore
          </button>
        )}
        <button type="button" className="btn sm ghost danger" disabled={!canEdit} onClick={() => void session.deletePattern(r.id)}>
          Delete its history
        </button>
      </div>
    </article>
  )
}
