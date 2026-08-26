// ---------------------------------------------------------------------------
// The question box.
//
// Eighteen cards of charts answer the questions the app thought of; this one
// answers the question she actually has, in her own words — "what did we take
// last August bank holiday?" — from her own records, on her own key. The
// conversation stays on screen so a follow-up ("and the year before?") lands
// with its context, and the data pack is cached between turns so the follow-up
// costs pennies of a penny.
// ---------------------------------------------------------------------------

import { useEffect, useMemo, useRef, useState } from 'react'
import { buildAskPack, type AskData } from '../core/askContext.ts'
import { askTally, describeAskError, type AskTurn } from '../ai/ask.ts'
import { loadSettings } from '../storage/settings.ts'

const SUGGESTIONS = [
  'What was our best night ever?',
  'What did we take last bank holiday?',
  'Are Fridays getting better or worse?',
]

export function AskCard({ data }: { data: AskData }) {
  const [question, setQuestion] = useState('')
  const [turns, setTurns] = useState<AskTurn[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const abortRef = useRef<AbortController | null>(null)
  const endRef = useRef<HTMLDivElement>(null)

  useEffect(() => () => abortRef.current?.abort(), [])

  // Built once per data change, not per keystroke.
  const pack = useMemo(() => buildAskPack(data), [data])
  const hasKey = loadSettings().apiKey.trim().length > 0

  async function ask(asked: string) {
    const q = asked.trim()
    if (!q || busy) return
    if (!hasKey) {
      setError(describeAskError(new Error('NO_KEY')))
      return
    }
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller

    const history = turns
    setTurns([...history, { role: 'user', text: q }])
    setQuestion('')
    setBusy(true)
    setError('')
    try {
      const answer = await askTally({ question: q, pack: pack.text, history, signal: controller.signal })
      if (controller.signal.aborted) return
      setTurns((t) => [...t, { role: 'assistant', text: answer }])
    } catch (err) {
      if (controller.signal.aborted) return
      // The question goes back in the box rather than being lost, and the
      // failed turn comes off the transcript so a retry is one tap.
      setTurns(history)
      setQuestion(q)
      setError(describeAskError(err))
    } finally {
      if (!controller.signal.aborted) setBusy(false)
    }
  }

  // A new answer lands off the bottom of the card; bring it into view.
  useEffect(() => {
    if (turns.length > 0) endRef.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }, [turns.length])

  return (
    <section className="card">
      <div className="card-head">
        <h2>Ask the till</h2>
        <span className="hint">answered from your own records</span>
      </div>

      {turns.length === 0 && (
        <p className="note" style={{ marginTop: 0 }}>
          Anything the saved nights can answer — takings, items, variance, the cellar, who was
          on. It only speaks from what is stored here{pack.nightCount > 0 ? ` (${pack.nightCount} night${pack.nightCount === 1 ? '' : 's'} so far)` : ''}, and says so when the records run out.
        </p>
      )}

      {turns.length > 0 && (
        <div className="ask-thread" aria-live="polite">
          {turns.map((t, i) => (
            <div key={i} className={`ask-turn ${t.role}`}>
              {t.text}
            </div>
          ))}
          {busy && (
            <div className="ask-turn assistant thinking">
              <span className="spinner" /> Reading the records…
            </div>
          )}
          <div ref={endRef} />
        </div>
      )}

      <form
        className="ask-row"
        onSubmit={(e) => {
          e.preventDefault()
          void ask(question)
        }}
      >
        <input
          type="text"
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          placeholder="What did we take last bank holiday?"
          aria-label="Ask a question about the records"
          disabled={busy}
        />
        <button type="submit" className="btn-primary" disabled={busy || question.trim() === ''}>
          Ask
        </button>
      </form>

      {turns.length === 0 && !busy && (
        <div className="ask-chips">
          {SUGGESTIONS.map((s) => (
            <button key={s} type="button" className="chip" onClick={() => void ask(s)}>
              {s}
            </button>
          ))}
        </div>
      )}

      {error && <p className="note bad" role="status">{error}</p>}

      {turns.length > 0 && !busy && (
        <div className="alts">
          <button type="button" className="btn-small" onClick={() => { setTurns([]); setError('') }}>
            Start afresh
          </button>
        </div>
      )}

      <p className="note" style={{ marginBottom: 0 }}>
        Answers come from Claude on your own key, reading only what this app has saved — each
        question costs a fraction of a penny. Check anything that matters against the night itself.
      </p>
    </section>
  )
}
