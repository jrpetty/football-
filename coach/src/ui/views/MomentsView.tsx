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
  const pending = state.moments.filter((m) => m.review.status === 'idle' || m.review.status === 'error')
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

      {state.moments.length === 0 ? (
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
          {[...state.moments].reverse().map((m) => (
            <MomentCard key={m.id} m={m} origin={state.clockOrigin} onReview={() => void session.reviewMoment(m.id, cloud)} onDelete={() => session.deleteMoment(m.id)} disabled={!!blocked || !ready} />
          ))}
        </div>
      )}
      <p className="muted small">Screenshots of marked moments stay in memory for this session only and are never written to disk. A review sends that one screenshot to Anthropic with your key.</p>
    </div>
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
