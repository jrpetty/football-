import { useMemo, useState } from 'react'
import type { TimelineEntry } from '../../core/types.ts'
import { GradeBadge } from '../components/common.tsx'
import { CATEGORY_TEXT, clock, DECISION_TEXT, pct, timelineKind, WINDOW_TEXT, type TimelineKind } from '../format.ts'
import type { SessionState } from '../session.ts'

const FILTERS: readonly { id: TimelineKind | 'all'; label: string }[] = [
  { id: 'all', label: 'Everything' },
  { id: 'spoken', label: 'Spoken' },
  { id: 'held', label: 'Not spoken' },
  { id: 'inferred', label: 'Inferred' },
  { id: 'observed', label: 'Observed' },
  { id: 'note', label: 'Notes' },
]

const KIND_LABEL: Record<TimelineKind, string> = {
  observed: 'Observed',
  inferred: 'Inferred',
  spoken: 'Spoken',
  held: 'Not spoken',
  note: 'Note',
}

export function ReviewView({ state }: { state: SessionState }) {
  const [filter, setFilter] = useState<TimelineKind | 'all'>('all')
  const counts = useMemo(() => {
    const c: Record<TimelineKind, number> = { observed: 0, inferred: 0, spoken: 0, held: 0, note: 0 }
    for (const e of state.timeline) c[timelineKind(e)]++
    return c
  }, [state.timeline])
  const shown = useMemo(
    () => [...state.timeline].reverse().filter((e) => filter === 'all' || timelineKind(e) === filter),
    [state.timeline, filter],
  )
  const summary = [...state.timeline].reverse().find((e) => e.type === 'message' && e.message.category === 'post_event' && e.decision.decision !== 'suppressed')

  return (
    <div className="page">
      <div>
        <h1>Session review</h1>
        <p className="lede">
          Everything the coach observed on screen, what it inferred from it and how sure it was, and why it did or did not speak.
          This timeline lives in memory and is cleared when the next session starts.
        </p>
      </div>

      {summary && summary.type === 'message' ? (
        <section className="message-card" aria-label="Match summary">
          <div className="meta">
            <span className="badge neutral">Latest review</span>
            <span>{DECISION_TEXT[summary.decision.decision]}</span>
          </div>
          <div className="quote">{summary.message.text}</div>
          <div className="muted small">Associations in what was visible, not proof of cause.</div>
        </section>
      ) : null}

      <section className="panel">
        <div className="stat-row">
          <Stat n={counts.observed} l="Visible events" />
          <Stat n={counts.inferred} l="Inferences" />
          <Stat n={counts.spoken} l="Spoken" />
          <Stat n={counts.held} l="Held back" />
        </div>
      </section>

      <section className="panel" aria-labelledby="tl">
        <div className="panel-head">
          <h2 id="tl">Timeline</h2>
          <div className="filters" role="group" aria-label="Filter timeline">
            {FILTERS.map((f) => (
              <button key={f.id} type="button" className="chip" aria-pressed={filter === f.id} onClick={() => setFilter(f.id)}>
                {f.label}
              </button>
            ))}
          </div>
        </div>
        {shown.length === 0 ? (
          <div className="empty">{state.timeline.length === 0 ? 'No session yet. Start capture from the Coach tab.' : 'Nothing in this view.'}</div>
        ) : (
          <ol className="timeline">
            {shown.slice(0, 400).map((e, i) => (
              <Item key={`${e.at}-${i}`} e={e} origin={state.clockOrigin} />
            ))}
          </ol>
        )}
        {shown.length > 400 ? <p className="muted small">Showing the latest 400 of {shown.length}.</p> : null}
      </section>
    </div>
  )
}

function Item({ e, origin }: { e: TimelineEntry; origin: number }) {
  const kind = timelineKind(e)
  return (
    <li className="tl-item">
      <span className="tl-time">{clock(e.at - origin)}</span>
      <span className={`tl-kind ${kind}`}>{KIND_LABEL[kind]}</span>
      <div className="tl-main">{body(e)}</div>
    </li>
  )
}

function body(e: TimelineEntry) {
  switch (e.type) {
    case 'event':
      return (
        <>
          <div className="tl-title">
            <span>{e.event.label}</span>
            {e.event.manual ? <span className="badge neutral">marked by you</span> : null}
            {e.event.source === 'vision-model' ? <span className="badge info">vision model</span> : null}
          </div>
          <div className="tl-detail">
            {e.event.evidence}
            {e.event.zone ? ` · zone ${e.event.zone}` : ''} · confidence {pct(e.event.confidence)}
          </div>
        </>
      )
    case 'episode':
      return (
        <>
          <div className="tl-title">{e.summary}</div>
          <div className="tl-detail">
            {e.episode.excluded ? 'Kept for the record, not counted as evidence. ' : 'Counted as evidence. '}
            Confidence {pct(e.episode.confidence)}.
          </div>
        </>
      )
    case 'insight':
      return (
        <>
          <div className="tl-title">
            <GradeBadge grade={e.insight.grade} />
            <span>Pattern updated</span>
          </div>
          <div className="tl-detail">{e.statement}</div>
        </>
      )
    case 'message':
      return (
        <>
          <div className="tl-title">
            <span className="badge neutral">{CATEGORY_TEXT[e.message.category]}</span>
            {e.message.author === 'ai' ? <span className="badge info">AI-phrased</span> : null}
            <span>“{e.message.text}”</span>
          </div>
          <div className="tl-detail">Based on: {e.message.evidenceSummary}</div>
          <div className="why">
            <b>{DECISION_TEXT[e.decision.decision]}</b> ({WINDOW_TEXT[e.decision.window].toLowerCase()}): {e.decision.reasons.join('; ')}.
          </div>
        </>
      )
    case 'notice':
      return <div className="tl-detail" style={{ color: e.level === 'warning' ? 'var(--warn)' : undefined }}>{e.text}</div>
  }
}

function Stat({ n, l }: { n: number; l: string }) {
  return (
    <div className="stat">
      <div className="n">{n}</div>
      <div className="l">{l}</div>
    </div>
  )
}
