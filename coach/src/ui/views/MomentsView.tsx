import { useState } from 'react'
import type { PlatformInfo } from '../../../shared/bridge.ts'
import type { CloudStatus } from '../../../shared/cloud.ts'
import type { CoachSettings } from '../../core/settings.ts'
import type { Tab } from '../App.tsx'
import { Notice } from '../components/common.tsx'
import { hotkeyLabel } from '../components/HotkeyField.tsx'
import { clock, usd } from '../format.ts'
import type { CoachSession, Moment, SessionState } from '../session.ts'

interface Props {
  state: SessionState
  session: CoachSession
  settings: CoachSettings
  cloud: CloudStatus
  info: PlatformInfo | null
  onTab: (t: Tab) => void
}

export function MomentsView({ state, session, settings, cloud, info, onTab }: Props) {
  const blocked = session.reviewBlocked()
  const ready = cloud.available && cloud.settings.enabled && cloud.key.set
  const markKey = info?.markShortcut ?? hotkeyLabel(settings.hotkeys.mark)
  const marked = state.moments.filter((m) => !m.auto)
  const auto = state.moments.filter((m) => m.auto)
  const pending = marked.filter((m) => m.review.status === 'idle' || m.review.status === 'error')
  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Decisions</h1>
          <p className="lede">
            Press <kbd>{markKey}</kbd> during a game to mark a moment — a fight you took, a build you chose, a rotation. After the match, the coach looks at
            exactly what was on your screen and tells you what was happening, whether the decision was sound, and what to try next time.
          </p>
        </div>
        {pending.length > 1 ? (
          <button type="button" className="btn primary" disabled={!!blocked || !ready} onClick={() => pending.forEach((m) => void session.reviewMoment(m.id, cloud))}>
            Review all ({pending.length})
          </button>
        ) : null}
      </div>

      {blocked ? <Notice level="info">{blocked}</Notice> : null}
      {!ready ? (
        <Notice level="info">
          Reviews use Claude to read the screenshot.{' '}
          <button type="button" className="btn sm" onClick={() => onTab('settings')}>
            Connect Claude
          </button>
        </Notice>
      ) : null}

      {auto.length ? (
        <KeyMoments moments={auto} origin={state.clockOrigin} onReview={(id) => void session.reviewMoment(id, cloud)} onDelete={(id) => session.deleteMoment(id)} disabled={!!blocked || !ready} />
      ) : null}

      {marked.length === 0 && auto.length ? (
        <p className="muted small">No marked moments: press {markKey} during play to mark one you want a second opinion on.</p>
      ) : marked.length === 0 ? (
        <div className="empty big-empty">
          <div className="keycap-hero">
            {markKey.split('+').map((k, i) => (
              <kbd key={i}>{k}</kbd>
            ))}
          </div>
          No marked moments yet. Start capture, play, and press {markKey} when you want a second opinion.
          {settings.voiceInput ? ' Right after marking, press your talk key to say what you were thinking.' : ''}
        </div>
      ) : (
        <div className="moments">
          {[...marked].reverse().map((m) => (
            <MomentCard key={m.id} m={m} origin={state.clockOrigin} onReview={() => void session.reviewMoment(m.id, cloud)} onDelete={() => session.deleteMoment(m.id)} disabled={!!blocked || !ready} />
          ))}
        </div>
      )}
      <p className="muted small">
        Screenshots of marked moments and key moments stay in memory for this session only and are never written to disk. Key moments reuse the frame already read for the screen, so they cost nothing.
        A review sends that one screenshot to Anthropic with your key.
      </p>
    </div>
  )
}

/** Each death or lost fight, one at a time: the frame it was read from and what was read about it. */
function KeyMoments({ moments, origin, onReview, onDelete, disabled }: { moments: readonly Moment[]; origin: number; onReview: (id: string) => void; onDelete: (id: string) => void; disabled: boolean }) {
  const [pick, setPick] = useState<string | null>(null)
  const i = Math.max(0, pick ? moments.findIndex((m) => m.id === pick) : moments.length - 1)
  const m = moments[Math.min(i, moments.length - 1)]
  const go = (d: number) => setPick(moments[(i + d + moments.length) % moments.length].id)
  return (
    <section className="panel key-moments" aria-labelledby="km-h">
      <div className="panel-head">
        <h2 id="km-h">Key moments</h2>
        <span className="muted small">
          {m.auto?.event === 'death' ? 'Each death' : 'Each fight lost'}, from the frame the coach read it from · {i + 1} of {moments.length}
        </span>
      </div>
      <div
        className="km-view"
        tabIndex={0}
        aria-label="Key moment viewer: left and right arrows to move"
        onKeyDown={(e) => {
          if (e.key === 'ArrowLeft') go(-1)
          if (e.key === 'ArrowRight') go(1)
        }}
      >
        <figure className="km-shot">
          {m.imageUrl ? <img src={m.imageUrl} alt={`${m.auto?.label ?? 'Moment'} at ${clock(m.at - origin)}`} /> : null}
          <button type="button" className="km-nav prev" aria-label="Previous" onClick={() => go(-1)} disabled={moments.length < 2}>
            ‹
          </button>
          <button type="button" className="km-nav next" aria-label="Next" onClick={() => go(1)} disabled={moments.length < 2}>
            ›
          </button>
        </figure>
        <div className="km-side">
          <div>
            <b>{m.auto?.label}</b> <span className="muted">at {clock(m.at - origin)}</span>
          </div>
          {m.auto?.tags.length ? (
            <ul className="km-tags">
              {m.auto.tags.map((t) => (
                <li key={t}>{t}</li>
              ))}
            </ul>
          ) : (
            <p className="muted small">Nothing more was readable about it.</p>
          )}
          {m.review.status === 'done' && m.review.text ? <p className="moment-review small">{m.review.text}</p> : null}
          {m.review.status === 'error' && m.review.text ? <Notice level="error">{m.review.text}</Notice> : null}
          <div className="btn-row">
            {m.review.status === 'reviewing' ? (
              <span className="muted small">Reviewing…</span>
            ) : (
              <button type="button" className="btn sm" onClick={() => onReview(m.id)} disabled={disabled}>
                {m.review.status === 'done' ? 'Review again' : 'Review this'}
              </button>
            )}
            <button type="button" className="btn sm ghost danger" onClick={() => (setPick(null), onDelete(m.id))}>
              Delete
            </button>
          </div>
        </div>
      </div>
      <div className="km-strip" role="list">
        {moments.map((x, j) => (
          <button key={x.id} type="button" role="listitem" className={`km-thumb${j === i ? ' on' : ''}`} onClick={() => setPick(x.id)} aria-label={`${x.auto?.label} at ${clock(x.at - origin)}`}>
            {x.imageUrl ? <img src={x.imageUrl} alt="" /> : null}
            <span>{clock(x.at - origin)}</span>
          </button>
        ))}
      </div>
    </section>
  )
}

function MomentCard({ m, origin, onReview, onDelete, disabled }: { m: Moment; origin: number; onReview: () => void; onDelete: () => void; disabled: boolean }) {
  return (
    <article className="moment">
      <div className="moment-shot">
        {m.imageUrl ? <img src={m.imageUrl} alt={`Your screen at ${clock(m.at - origin)}`} /> : <div className="moment-noshot">No screenshot (demo replay)</div>}
        <span className="moment-time">{clock(m.at - origin)}</span>
      </div>
      <div className="moment-body">
        <div className="moment-meta">
          {m.context.screen ? <span className="badge neutral">{m.context.screen}</span> : null}
          {m.context.score ? (
            <span className="badge neutral">
              {m.context.score.player}–{m.context.score.opponent}
            </span>
          ) : null}
        </div>
        {m.note ? <p className="moment-note">“{m.note}”</p> : <p className="muted small">No note — the coach will work out the decision from the screen.</p>}
        {m.review.status === 'done' && m.review.text ? (
          <div className="moment-review">
            <div className="who">Coach</div>
            <p>{m.review.text}</p>
            {m.review.costUsd ? <div className="muted small">{usd(m.review.costUsd)}</div> : null}
          </div>
        ) : null}
        {m.review.status === 'error' && m.review.text ? <Notice level="error">{m.review.text}</Notice> : null}
        <div className="btn-row">
          {m.review.status === 'reviewing' ? (
            <span className="typing" aria-label="Reviewing">
              <span />
              <span />
              <span />
            </span>
          ) : (
            <button type="button" className="btn sm primary" onClick={onReview} disabled={disabled}>
              {m.review.status === 'done' ? 'Review again' : 'Review this decision'}
            </button>
          )}
          <button type="button" className="btn sm ghost danger" onClick={onDelete}>
            Delete
          </button>
        </div>
      </div>
    </article>
  )
}
