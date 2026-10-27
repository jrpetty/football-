// The capture picker for Electron on platforms without an OS picker (Windows,
// Linux, macOS < 15). It lists what the OS reports as shareable windows and
// screens; choosing one authorises exactly one capture request.

import { useCallback, useEffect, useState } from 'react'
import type { CaptureSource, SightlineBridge } from '../../../shared/bridge.ts'
import { Modal, Segmented } from './common.tsx'

export function SourcePicker(props: { bridge: SightlineBridge; onPick: (id: string) => void; onCancel: () => void }) {
  const [sources, setSources] = useState<CaptureSource[] | null>(null)
  const [kind, setKind] = useState<'window' | 'screen'>('window')
  const [selected, setSelected] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const refresh = useCallback(() => {
    setError(null)
    props.bridge
      .listSources()
      .then(setSources)
      .catch((e: unknown) => setError(String(e)))
  }, [props.bridge])
  useEffect(refresh, [refresh])

  const shown = sources?.filter((s) => s.kind === kind) ?? []
  const choice = sources?.find((s) => s.id === selected)

  return (
    <Modal title="Choose what to share" onClose={props.onCancel}>
      <div>
        <h1>Choose what Sightline can see</h1>
        <p className="lede">
          Pick the game window (or a screen). Sightline will see only its pixels, analyse them on this computer, and keep
          nothing but the events it recognises. You can stop at any time.
        </p>
      </div>
      <div className="btn-row">
        <Segmented
          label="Source type"
          value={kind}
          onChange={setKind}
          options={[
            { value: 'window', label: 'Windows' },
            { value: 'screen', label: 'Screens' },
          ]}
        />
        <button type="button" className="btn sm ghost" onClick={refresh}>
          Refresh
        </button>
      </div>
      {error ? <p className="muted">Could not list windows: {error}</p> : null}
      {sources === null ? (
        <p className="muted">Looking for windows…</p>
      ) : shown.length === 0 ? (
        <div className="empty">No {kind === 'window' ? 'windows' : 'screens'} available to share.</div>
      ) : (
        <div className="sources" role="radiogroup" aria-label="Shareable sources">
          {shown.map((s) => (
            <button
              key={s.id}
              type="button"
              role="radio"
              aria-checked={selected === s.id}
              className="source"
              onClick={() => setSelected(s.id)}
              onDoubleClick={() => props.onPick(s.id)}
            >
              {s.thumbnail ? <img src={s.thumbnail} alt="" /> : <div className="thumb" />}
              <span className="name" title={s.name}>
                {s.name}
              </span>
            </button>
          ))}
        </div>
      )}
      <div className="btn-row" style={{ justifyContent: 'flex-end' }}>
        <span className="muted small" style={{ marginRight: 'auto' }}>
          Tip: borderless or fullscreen games line up best with game profiles.
        </span>
        <button type="button" className="btn ghost" onClick={props.onCancel}>
          Cancel
        </button>
        <button type="button" className="btn primary" disabled={!choice} onClick={() => choice && props.onPick(choice.id)}>
          {choice ? `Share “${truncate(choice.name, 32)}”` : 'Share'}
        </button>
      </div>
    </Modal>
  )
}

function truncate(s: string, n: number): string {
  return s.length > n ? `${s.slice(0, n - 1)}…` : s
}
