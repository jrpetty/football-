// Your marks on the DayZ map: what you saw (players, shots, danger) and what
// is yours (base, stashes, car, meeting point, notes). Mark by voice or chat
// ("I saw two players near Gorka", "my base is 500 m north of Gorka"), or
// pick a kind here and click the map. Share them with your squad as a code.

import { useState } from 'react'
import type { DayzMapId } from '../../core/dayz/places.ts'
import { ago, isFaded, MARK_INFO, MARK_KINDS, type Mark } from '../../core/dayz/marks.ts'
import type { CoachSession, SessionState } from '../session.ts'

interface Props {
  readonly state: SessionState
  readonly session: CoachSession
  readonly mapId: DayzMapId
  readonly onShow: (m: Mark) => void
  /** The way there from your spot (Chernarus, with a spot set). */
  readonly onGo: ((m: Mark) => void) | null
}

export function MarksPanel({ state, session, mapId, onShow, onGo }: Props) {
  const now = Date.now()
  const mine = state.marks.filter((m) => m.map === mapId)
  const owned = mine.filter((m) => !MARK_INFO[m.kind].sighting).sort((a, b) => a.kind.localeCompare(b.kind))
  const seen = mine.filter((m) => MARK_INFO[m.kind].sighting).sort((a, b) => b.at - a.at)
  const [code, setCode] = useState('')
  const [list, setList] = useState('')
  const [listOpen, setListOpen] = useState(false)
  const [note, setNote] = useState<string | null>(null)
  const pending = state.pendingMark?.map === mapId ? state.pendingMark : null

  const copy = async () => {
    const text = session.marksCode(mine.map((m) => m.id))
    try {
      await navigator.clipboard.writeText(text)
      setNote(`Copied a code with ${mine.length} mark${mine.length === 1 ? '' : 's'}. Paste it to your squad; they paste it here.`)
    } catch {
      setCode(text)
      setNote('Copy the code from the box below.')
    }
  }
  const paste = () => {
    const n = session.importMarks(code)
    setNote(n === null ? 'That is not a marks code (it starts with SLMARKS1:).' : n ? `Added ${n} mark${n === 1 ? '' : 's'} from the code.` : 'You already have all of those marks.')
    if (n !== null) setCode('')
  }

  return (
    <section className="panel" aria-labelledby="dz-marks-h">
      <div className="panel-head">
        <h2 id="dz-marks-h">Your marks</h2>
        <span className="muted small">Only what you told the coach or clicked · kept on this PC</span>
      </div>
      <p className="small muted" style={{ marginTop: 0 }}>
        Say it (“Coach, I saw two players heading north near Gorka”, “my base is 500 m north of Gorka”, “I buried a barrel here”, “parked the car at Berezino”) or pick a kind and click the map. Sightings fade after half an hour and go after a few hours; routes mention recent ones nearby.
      </p>
      <div className="btn-row dz-mark-kinds" role="group" aria-label="Add a mark">
        {MARK_KINDS.filter((k) => k !== 'body').map((k) => (
          <button key={k} type="button" className={`btn sm${pending?.kind === k ? ' primary' : ''}`} onClick={() => session.startMark(pending?.kind === k ? null : k, undefined, mapId)}>
            <span className={`dz-mark-chip dz-mark-${k}`}>{MARK_INFO[k].glyph}</span> {MARK_INFO[k].label}
          </button>
        ))}
      </div>
      {pending ? (
        <p className="small dz-placing">
          <b>Click the map to mark: {pending.label}.</b>{' '}
          <button type="button" className="link-btn" onClick={() => session.startMark(null)}>
            Cancel
          </button>
        </p>
      ) : null}

      {mine.length === 0 ? (
        <p className="small muted">No marks on this map yet.</p>
      ) : (
        <ul className="dz-mark-list">
          {[...owned, ...seen].map((m) => (
            <li key={m.id} className={isFaded(m, now) ? 'faded' : ''}>
              <span className={`dz-mark-chip dz-mark-${m.kind}`} aria-hidden="true">
                {MARK_INFO[m.kind].glyph}
              </span>
              <span className="dz-mark-text small">
                <b>{m.label}</b> <span className="muted">{m.where}</span>
                {MARK_INFO[m.kind].sighting || m.kind === 'body' ? <span className="muted"> · {ago(m.at, now)}</span> : null}
                {m.note ? <span className="muted"> · {m.note}</span> : null}
              </span>
              <span className="dz-mark-actions">
                <button type="button" className="link-btn" onClick={() => onShow(m)}>
                  Show
                </button>
                {onGo ? (
                  <button type="button" className="link-btn" onClick={() => onGo(m)}>
                    Way there
                  </button>
                ) : null}
                <button type="button" className="link-btn" aria-label={`Remove ${m.label}`} onClick={() => session.removeMarks([m.id])}>
                  Remove
                </button>
              </span>
            </li>
          ))}
        </ul>
      )}

      <div className="btn-row">
        {seen.length ? (
          <button type="button" className="btn sm ghost" onClick={() => session.removeMarks(seen.map((m) => m.id))}>
            Clear sightings
          </button>
        ) : null}
        {mine.length ? (
          <button type="button" className="btn sm" onClick={() => void copy()}>
            Share with squad
          </button>
        ) : null}
        <input className="sm" aria-label="Paste a marks code" placeholder="Paste a squad code…" value={code} onChange={(e) => setCode(e.target.value)} style={{ minWidth: 180 }} />
        <button type="button" className="btn sm" disabled={!code.trim()} onClick={paste}>
          Add marks
        </button>
      </div>
      {note ? <p className="small muted">{note}</p> : null}
      <details className="dz-place-list" open={listOpen} onToggle={(e) => setListOpen((e.target as HTMLDetailsElement).open)}>
        <summary className="small">Traders and places of a community server: paste a list</summary>
        <p className="small muted">One per line, a name then where it is: “Green Mountain Trader: 037 050”, “Black market, Kumyrna”, “Safe zone - 500 m north of Stary Sobor”. Then ask “take me to the Green Mountain trader”.</p>
        <textarea className="dz-place-text" rows={4} value={list} onChange={(e) => setList(e.target.value)} aria-label="Places, one per line" placeholder={'Green Mountain Trader: 037 050\nBlack market, Kumyrna'} />
        <button
          type="button"
          className="btn sm"
          disabled={!list.trim()}
          onClick={() => {
            const r = session.importPlaces(list, mapId)
            setNote(`Added ${r.added} place${r.added === 1 ? '' : 's'}.${r.skipped.length ? ` Not understood: ${r.skipped.slice(0, 3).join('; ')}${r.skipped.length > 3 ? '…' : ''}` : ''}`)
            if (!r.skipped.length) setList('')
          }}
        >
          Add places
        </button>
      </details>
    </section>
  )
}
