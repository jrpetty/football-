// "Ask the coach": a question box with suggestions and push-to-talk, and the
// question/answer list. Answers say where they came from (this computer, or a
// language model phrasing recorded data) and whether policy is holding them.

import { useState } from 'react'
import type { CloudStatus } from '../../../shared/cloud.ts'
import type { GameProfile } from '../../core/profile/schema.ts'
import type { CoachSettings } from '../../core/settings.ts'
import { usd } from '../format.ts'
import { MicIcon } from './common.tsx'
import type { CoachSession, QaEntry, SessionState } from '../session.ts'

export function suggestedQuestions(profile: GameProfile): string[] {
  const out: string[] = []
  const heatmaps = new Set((profile.heatmaps ?? []).map((h) => h.id))
  if (heatmaps.has('conceded')) out.push('Where do I concede the most?')
  if (heatmaps.has('turnovers')) out.push('Where are my passes failing?')
  if (heatmaps.has('deaths')) out.push('Where am I dying most?')
  if (profile.mistakes?.length) out.push('What mistakes am I making?')
  if (profile.matchStats) out.push('How are my stats trending?')
  if (profile.episodes.length) out.push('What patterns have you seen?')
  out.push('How did that match go?')
  return out.slice(0, 5)
}

export function AskBox(props: { state: SessionState; session: CoachSession; profile: GameProfile; settings: CoachSettings; cloud: CloudStatus; compact?: boolean }) {
  const [text, setText] = useState('')
  const busy = props.state.conversation.some((q) => q.status === 'thinking' && q.topic === null)
  const send = (q: string) => {
    if (!q.trim()) return
    void props.session.ask(q, 'text', props.cloud)
    setText('')
  }
  const talk = props.state.talk
  return (
    <div className={`askbox${props.compact ? ' compact' : ''}`}>
      <form
        className="ask-input"
        onSubmit={(e) => {
          e.preventDefault()
          send(text)
        }}
      >
        <input
          aria-label="Ask the coach"
          placeholder="Ask about your games…"
          value={text}
          maxLength={500}
          onChange={(e) => setText(e.target.value)}
        />
        {props.settings.voiceInput && (
          <button
            type="button"
            className={`btn icon${talk === 'recording' ? ' recording' : ''}`}
            onClick={() => void props.session.toggleTalk(props.cloud)}
            disabled={talk === 'transcribing'}
            aria-label={talk === 'recording' ? 'Stop and send' : 'Ask by voice'}
            title={talk === 'recording' ? 'Listening — click to send' : 'Ask by voice'}
          >
            <MicIcon />
          </button>
        )}
        <button type="submit" className="btn primary" disabled={!text.trim() || busy}>
          Ask
        </button>
      </form>
      <div className="suggestions">
        {suggestedQuestions(props.profile).map((q) => (
          <button key={q} type="button" className="chip" onClick={() => send(q)} disabled={busy}>
            {q}
          </button>
        ))}
      </div>
    </div>
  )
}

const STATUS_TEXT: Record<QaEntry['status'], string> = {
  thinking: 'Thinking…',
  answered: '',
  held: 'Held',
  'not-delivered': 'Not delivered',
  error: 'Could not answer',
}

export function Conversation({ entries, limit }: { entries: readonly QaEntry[]; limit?: number }) {
  const shown = limit ? entries.slice(-limit) : entries
  if (!shown.length) return null
  return (
    <ol className="conversation">
      {shown.map((q) => (
        <li key={q.id}>
          <div className="q">
            <span className="who">You{q.via === 'voice' ? ' (voice)' : ''}</span>
            {q.question}
          </div>
          <div className={`a ${q.status}`}>
            <span className="who">Coach {STATUS_TEXT[q.status] ? <span className={`badge ${q.status === 'error' ? 'live' : q.status === 'held' ? 'warn' : 'neutral'}`}>{STATUS_TEXT[q.status]}</span> : null}</span>
            {q.status === 'answered' && q.answer ? <p>{q.answer}</p> : null}
            {q.status === 'thinking' ? <span className="typing" aria-label="Thinking"><span /><span /><span /></span> : null}
            {q.note ? (
              <div className="muted small">
                {q.author === 'ai' ? 'AI-phrased · ' : ''}
                {q.note}
                {q.costUsd > 0 ? ` · ${usd(q.costUsd)}` : ''}
              </div>
            ) : null}
          </div>
        </li>
      ))}
    </ol>
  )
}
