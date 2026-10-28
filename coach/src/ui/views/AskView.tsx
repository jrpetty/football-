import { useEffect, useState, type ReactNode } from 'react'
import type { SightlineBridge } from '../../../shared/bridge.ts'
import type { CloudStatus } from '../../../shared/cloud.ts'
import { KnowledgePanel } from '../components/KnowledgePanel.tsx'
import type { GameProfile } from '../../core/profile/schema.ts'
import type { CoachSettings } from '../../core/settings.ts'
import type { Tab } from '../App.tsx'
import { AskBox, Conversation } from '../components/ask.tsx'
import { Notice } from '../components/common.tsx'
import { jobEstimate } from '../../core/vision/cost.ts'
import { dateShort, modelName, usdRange } from '../format.ts'
import type { CoachSession, SessionState } from '../session.ts'

interface Props {
  state: SessionState
  session: CoachSession
  profile: GameProfile
  settings: CoachSettings
  cloud: CloudStatus
  bridge: SightlineBridge
  onTab: (t: Tab) => void
}

const cloudReady = (c: CloudStatus) => c.available && c.settings.enabled && c.key.set

export function AskView({ state, session, profile, settings, cloud, bridge, onTab }: Props) {
  return (
    <div className="page">
      <div>
        <h1>Ask the coach</h1>
        <p className="lede">
          Ask about your games — where you concede, what keeps going wrong, how a match went. Common questions are answered on this
          computer from what was recorded; open questions go to Claude with a short summary of that data (when cloud features are on).
          During restricted play, answers wait until the match ends.
        </p>
      </div>

      {!cloudReady(cloud) ? (
        <Notice level="info">
          Open-ended questions and game research need cloud features (desktop app + your Anthropic API key).{' '}
          <button type="button" className="btn sm" onClick={() => onTab('settings')}>
            Set up
          </button>
        </Notice>
      ) : null}

      <div className="ask-layout">
        <section className="panel ask-main" aria-labelledby="conv-h">
          <div className="panel-head">
            <h2 id="conv-h">Conversation</h2>
            {state.conversation.length > 0 && (
              <button type="button" className="btn sm ghost" onClick={() => session.clearConversation()}>
                Clear
              </button>
            )}
          </div>
          {state.conversation.length === 0 ? (
            <div className="empty small">Try one of the suggestions below{settings.voiceInput ? ', or press the microphone and ask out loud' : ''}.</div>
          ) : (
            <Conversation entries={state.conversation} />
          )}
          <AskBox state={state} session={session} profile={profile} settings={settings} cloud={cloud} />
        </section>

        <div className="ask-side">
          <KnowledgePanel bridge={bridge} profile={profile} cloud={cloud} />
          <NotesPanel state={state} session={session} profile={profile} settings={settings} />
          <ResearchPanel state={state} session={session} profile={profile} cloud={cloud} />
        </div>
      </div>
    </div>
  )
}

function NotesPanel({ state, session, profile, settings }: { state: SessionState; session: CoachSession; profile: GameProfile; settings: CoachSettings }) {
  const [goals, setGoals] = useState(state.notes?.goals ?? '')
  const [notes, setNotes] = useState(state.notes?.notes ?? '')
  const [saved, setSaved] = useState<'idle' | 'saved' | 'refused'>('idle')
  useEffect(() => {
    setGoals(state.notes?.goals ?? '')
    setNotes(state.notes?.notes ?? '')
  }, [state.notes, profile.id])
  const dirty = goals !== (state.notes?.goals ?? '') || notes !== (state.notes?.notes ?? '')
  return (
    <section className="panel" aria-labelledby="notes-h">
      <div className="panel-head">
        <h2 id="notes-h">Context for the coach</h2>
      </div>
      <div className="field">
        <label htmlFor="goals">What you’re working on</label>
        <textarea id="goals" rows={2} maxLength={2000} value={goals} placeholder="e.g. Stop conceding cutbacks; reach Feudal by 10:30" onChange={(e) => setGoals(e.target.value)} />
      </div>
      <div className="field">
        <label htmlFor="notes">Notes (formation, civ, controls, anything)</label>
        <textarea id="notes" rows={3} maxLength={4000} value={notes} placeholder="e.g. 4-2-3-1, Franks, I play on a controller" onChange={(e) => setNotes(e.target.value)} />
      </div>
      <div className="btn-row">
        <button
          type="button"
          className="btn sm primary"
          disabled={!dirty || !settings.saveTendencies}
          onClick={() => void session.saveNotes({ goals, notes, updatedAt: new Date().toISOString() }).then((ok) => setSaved(ok ? 'saved' : 'refused'))}
        >
          Save
        </button>
        <span className="muted small">
          {!settings.saveTendencies
            ? 'Turn on long-term memory in Settings to keep notes.'
            : saved === 'saved' && !dirty
              ? 'Saved (encrypted). Used when Claude answers.'
              : 'Shared with Claude only as part of a question you ask.'}
        </span>
      </div>
    </section>
  )
}

function ResearchPanel({ state, session, profile, cloud }: { state: SessionState; session: CoachSession; profile: GameProfile; cloud: CloudStatus }) {
  const [focus, setFocus] = useState('')
  const [error, setError] = useState<string | null>(null)
  const r = state.research
  return (
    <section className="panel" aria-labelledby="res-h">
      <div className="panel-head">
        <h2 id="res-h">Game research</h2>
        {r ? <span className="muted small">{dateShort(r.createdAt)}</span> : null}
      </div>
      <p className="muted small">
        Claude searches the web for current, expert guidance on {profile.game.title} (meta, fundamentals, common mistakes) and keeps a cited brief. The
        coach uses it when answering open questions — it never changes what is counted.
      </p>
      <form
        className="ask-input"
        onSubmit={(e) => {
          e.preventDefault()
          setError(null)
          void session.runResearch(focus).then(setError)
        }}
      >
        <input aria-label="Research focus" placeholder="Focus (optional), e.g. defending cutbacks" value={focus} maxLength={200} onChange={(e) => setFocus(e.target.value)} />
        <button type="submit" className="btn" disabled={!cloudReady(cloud) || state.researching}>
          {state.researching ? 'Researching…' : r ? 'Refresh' : 'Research'}
        </button>
      </form>
      {cloudReady(cloud) ? <ResearchCost model={cloud.settings.answerModel} /> : null}
      {error ? <Notice level="error">{error}</Notice> : null}
      {r ? (
        <div className="brief">
          <Prose text={r.text} />
          {r.sources.length > 0 && (
            <>
              <div className="label">Sources</div>
              <ol className="sources-list">
                {r.sources.map((s) => (
                  <li key={s.url}>
                    <span title={s.url}>{s.title || s.url}</span>
                    <span className="muted small"> — {hostOf(s.url)}</span>
                  </li>
                ))}
              </ol>
            </>
          )}
          <div className="btn-row">
            <span className="muted small">By {r.model}. Web content can be wrong or out of date.</span>
            <button type="button" className="btn sm ghost danger" onClick={() => void session.deleteResearch()}>
              Delete brief
            </button>
          </div>
        </div>
      ) : null}
    </section>
  )
}

function hostOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '')
  } catch {
    return url
  }
}

/** A tiny, safe Markdown subset: headings, bullets, bold. No HTML is ever injected. */
function Prose({ text }: { text: string }) {
  const blocks: ReactNode[] = []
  let list: string[] = []
  const flush = () => {
    if (list.length) blocks.push(<ul key={`l${blocks.length}`}>{list.map((li, i) => <li key={i}>{inline(li)}</li>)}</ul>)
    list = []
  }
  for (const raw of text.split('\n')) {
    const line = raw.trim()
    if (!line) {
      flush()
      continue
    }
    const bullet = /^[-*•]\s+(.*)/.exec(line)
    if (bullet) {
      list.push(bullet[1])
      continue
    }
    flush()
    const h = /^#{1,4}\s+(.*)/.exec(line)
    blocks.push(h ? <h3 key={blocks.length}>{inline(h[1])}</h3> : <p key={blocks.length}>{inline(line)}</p>)
  }
  flush()
  return <div className="prose">{blocks}</div>
}

function inline(s: string): ReactNode[] {
  return s.split(/(\*\*[^*]+\*\*)/g).map((part, i) => (part.startsWith('**') && part.endsWith('**') ? <b key={i}>{part.slice(2, -2)}</b> : part))
}

/** What one research run costs with the answers model, before it runs. */
function ResearchCost({ model }: { model: string }) {
  const est = jobEstimate(model, 'research')
  return (
    <p className="muted small">
      {est
        ? `About ${usdRange(est.low, est.high)} a run with ${modelName(model)} (estimate: web searches plus the pages they return). The real cost shows when it finishes.`
        : `Research needs Opus or Sonnet as the answers model (Settings); ${modelName(model)} cannot search with filtering.`}
    </p>
  )
}
