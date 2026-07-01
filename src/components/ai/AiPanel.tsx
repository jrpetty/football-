import { useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import type { Analysis } from '../../ai/analyses'
import { hasApiKey, getModel, modelLabel, streamAnalysis, describeAiError } from '../../ai/client'
import { Markdown } from './Markdown'

export function AiPanel({
  title = 'AI Analysis',
  build,
  onSave,
  saveLabel = 'Save to notes',
}: {
  title?: string
  /** Built lazily on click so it always reflects the latest data. */
  build: () => Analysis
  onSave?: (text: string) => void
  saveLabel?: string
}) {
  const [text, setText] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [usedAi, setUsedAi] = useState(false)
  const [saved, setSaved] = useState(false)
  const abortRef = useRef<AbortController | null>(null)

  const keyed = hasApiKey()

  async function generate() {
    setError('')
    setSaved(false)
    const analysis = build()
    if (!keyed) {
      setText(analysis.heuristic)
      setUsedAi(false)
      return
    }
    setUsedAi(true)
    setLoading(true)
    setText('')
    const ctrl = new AbortController()
    abortRef.current = ctrl
    try {
      await streamAnalysis({
        system: analysis.system,
        user: analysis.user,
        onText: (full) => setText(full),
        signal: ctrl.signal,
        maxTokens: analysis.maxTokens,
        effort: analysis.effort,
      })
    } catch (err) {
      if (!ctrl.signal.aborted) {
        setError(describeAiError(err))
        // Fall back to the offline summary so the panel is still useful.
        if (!text) setText(analysis.heuristic)
        setUsedAi(false)
      }
    } finally {
      setLoading(false)
      abortRef.current = null
    }
  }

  function stop() {
    abortRef.current?.abort()
    setLoading(false)
  }

  return (
    <div className="card">
      <div className="card-head">
        <h3>✨ {title}</h3>
        <div className="row gap-sm center">
          {usedAi && keyed && <span className="badge tiny" style={{ borderColor: 'rgba(139,92,246,0.4)', color: 'var(--violet)' }}>{modelLabel(getModel())}</span>}
          {!loading && (
            <button className="btn primary sm" onClick={generate}>{text ? '↻ Regenerate' : '✨ Generate'}</button>
          )}
          {loading && <button className="btn sm" onClick={stop}>■ Stop</button>}
        </div>
      </div>
      <div className="card-pad">
        {!keyed && (
          <p className="tiny faint" style={{ marginBottom: text ? 12 : 0 }}>
            Showing an instant data summary. Add your Anthropic API key in{' '}
            <Link className="link" to="/data">Data &amp; Export</Link> for full AI-written analysis.
          </p>
        )}
        {error && <p className="tiny" style={{ color: 'var(--amber)', marginBottom: 10 }}>{error}</p>}
        {!text && !loading && (
          <p className="muted tiny">Generate a coaching write-up from this data{keyed ? ` with ${modelLabel(getModel())}` : ''}.</p>
        )}
        {(text || loading) && (
          <>
            <Markdown text={text || '…'} />
            {loading && <span className="tiny faint">▍ generating…</span>}
            {!loading && text && (
              <div className="row gap-sm" style={{ marginTop: 12 }}>
                <button className="btn ghost sm" onClick={() => { navigator.clipboard?.writeText(text); }}>Copy</button>
                {onSave && (
                  <button className="btn ghost sm" onClick={() => { onSave(text); setSaved(true) }}>{saved ? '✓ Saved' : saveLabel}</button>
                )}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}
