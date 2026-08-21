// Optional written preview.
//
// Deliberately sits below the model's own reasoning on the page: the bullets
// above are the substance, this is a readable rendering of them. With no API
// key the panel explains itself and nothing breaks.

import { useRef, useState } from 'react'
import {
  AI_MODELS, describeAiError, getModel, hasApiKey,
  setApiKey, setModel, streamNarrative,
} from '../ai/client.ts'
import type { FixtureArtifact } from '../core/schema.ts'

export function NarrativePanel({ fixture }: { fixture: FixtureArtifact }) {
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [keyInput, setKeyInput] = useState('')
  const [hasKey, setHasKey] = useState(hasApiKey())
  const [model, setModelState] = useState(getModel())
  const abortRef = useRef<AbortController | null>(null)

  const saveKey = (): void => {
    setApiKey(keyInput)
    setHasKey(hasApiKey())
    setKeyInput('')
    setError(null)
  }

  const generate = async (): Promise<void> => {
    setBusy(true)
    setError(null)
    setText('')
    const controller = new AbortController()
    abortRef.current = controller
    try {
      await streamNarrative({ fixture, onText: setText, signal: controller.signal })
    } catch (err) {
      setError(describeAiError(err))
    } finally {
      setBusy(false)
      abortRef.current = null
    }
  }

  const stop = (): void => abortRef.current?.abort()

  return (
    <div className="panel panel-pad">
      <div className="section-title">
        <h3 style={{ fontSize: 16 }}>Written preview</h3>
        <span className="faint" style={{ fontSize: 11.5 }}>optional · uses your own API key</span>
      </div>

      {!hasKey ? (
        <>
          <p className="muted" style={{ fontSize: 13, lineHeight: 1.6, marginBottom: 12 }}>
            Claude can turn the factors above into a few paragraphs of prose. It is given only the
            model's own numbers and is told not to invent any others, so it cannot contradict the
            forecast. Everything else on this page works without it.
          </p>
          <div className="row gap-8 wrap">
            <input
              type="password"
              value={keyInput}
              onChange={(e) => setKeyInput(e.target.value)}
              placeholder="sk-ant-..."
              aria-label="Anthropic API key"
              className="gw-btn"
              style={{ flex: 1, minWidth: 220, padding: '0 12px', textAlign: 'left' }}
            />
            <button className="gw-btn active" style={{ padding: '0 16px' }} onClick={saveKey}>
              Save key
            </button>
          </div>
          <p className="faint" style={{ fontSize: 11.5, marginTop: 9, lineHeight: 1.5 }}>
            Stored in this browser only and sent directly to Anthropic — this site has no server.
          </p>
        </>
      ) : (
        <>
          <div className="row gap-8 wrap" style={{ marginBottom: 13 }}>
            <button className="gw-btn active" style={{ padding: '0 16px' }} onClick={busy ? stop : generate}>
              {busy ? 'Stop' : text ? 'Write it again' : 'Write a preview'}
            </button>
            <select
              className="gw-btn"
              style={{ padding: '0 10px', minWidth: 165 }}
              value={model}
              onChange={(e) => {
                setModel(e.target.value)
                setModelState(e.target.value)
              }}
            >
              {AI_MODELS.map((m) => (
                <option key={m.id} value={m.id}>{m.label}</option>
              ))}
            </select>
            <button
              className="gw-btn"
              style={{ padding: '0 12px' }}
              onClick={() => {
                setApiKey('')
                setHasKey(false)
                setText('')
              }}
            >
              Forget key
            </button>
          </div>

          {error && (
            <div className="note" style={{ borderColor: 'rgba(230,103,103,0.3)', background: 'rgba(230,103,103,0.07)' }}>
              <span className="note-icon" style={{ color: 'var(--away)' }}>!</span>
              <span>{error}</span>
            </div>
          )}

          {text && (
            <div style={{ fontSize: 14, lineHeight: 1.72, color: 'var(--text-dim)' }}>
              {text.split('\n\n').filter(Boolean).map((para, i) => (
                <p key={i} style={{ marginBottom: 12 }}>{para}</p>
              ))}
              {busy && <span className="faint" style={{ fontSize: 12 }}>writing…</span>}
            </div>
          )}

          {!text && !busy && !error && (
            <p className="muted" style={{ fontSize: 13, lineHeight: 1.6 }}>
              Claude is given the probabilities, the ranked factors and the absences from this page —
              and nothing else — then asked for two or three paragraphs.
            </p>
          )}
        </>
      )}
    </div>
  )
}
