import { useMemo } from 'react'
import { storedInsights, toPatternRecords } from '../../core/memory/records.ts'
import { mistakeLine } from '../../core/language.ts'
import type { GameProfile } from '../../core/profile/schema.ts'
import type { CoachSettings } from '../../core/settings.ts'
import type { PatternRecord } from '../../core/types.ts'
import { GradeBadge, Notice } from '../components/common.tsx'
import { Breakdown, ZoneMap } from '../components/viz.tsx'
import { dateShort, formatMs, pct } from '../format.ts'
import type { CoachSession, SessionState } from '../session.ts'

interface Props {
  state: SessionState
  session: CoachSession
  profile: GameProfile
  settings: CoachSettings
}

export function InsightsView({ state, session, profile, settings }: Props) {
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

  return (
    <div className="page">
      <div>
        <h1>Insights · {profile.game.title}</h1>
        <p className="lede">
          Where things happen, what keeps going wrong, and which habits go with worse outcomes — counted from what was visible,
          with how sure the coach is. Associations, not proof of cause.
        </p>
      </div>

      {!report && !state.snapshot ? (
        <Notice level="info">No data yet for this game. Play a session (or a demo replay from the Coach tab) and come back.</Notice>
      ) : null}

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

      {profile.knowledge.concepts.length > 0 && (
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
