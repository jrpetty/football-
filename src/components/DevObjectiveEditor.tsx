import { useState } from 'react'
import type { DevObjective, ObjectiveStatus, Player } from '../types'
import { Modal } from './ui/Modal'

const STATUSES: ObjectiveStatus[] = ['Not started', 'In progress', 'On track', 'Achieved']

export function DevObjectiveEditor({
  objective,
  players,
  lockPlayer,
  onClose,
  onSave,
}: {
  objective: DevObjective
  players: Player[]
  /** When true the player can't be changed (editing from a profile). */
  lockPlayer?: boolean
  onClose: () => void
  onSave: (o: DevObjective) => void
}) {
  const [form, setForm] = useState<DevObjective>(objective)
  const set = (patch: Partial<DevObjective>) => setForm((f) => ({ ...f, ...patch }))

  return (
    <Modal
      title={objective.id ? 'Edit objective' : 'New objective'}
      onClose={onClose}
      footer={
        <>
          <button className="btn ghost" onClick={onClose}>Cancel</button>
          <button className="btn primary" onClick={() => onSave(form)} disabled={!form.title.trim() || !form.playerId}>Save objective</button>
        </>
      }
    >
      <div className="col" style={{ gap: 14 }}>
        {!lockPlayer && (
          <div className="field">
            <label>Player</label>
            <select className="select" value={form.playerId} onChange={(e) => set({ playerId: e.target.value })}>
              <option value="">— select player —</option>
              {players.slice().sort((a, b) => a.number - b.number).map((p) => (
                <option key={p.id} value={p.id}>{p.number} · {p.name}</option>
              ))}
            </select>
          </div>
        )}
        <div className="field">
          <label>Objective</label>
          <input className="input" autoFocus value={form.title} placeholder="e.g. Improve defensive work-rate" onChange={(e) => set({ title: e.target.value })} />
        </div>
        <div className="grid grid-2">
          <div className="field">
            <label>Focus area</label>
            <input className="input" value={form.area} placeholder="e.g. Tackles /90" onChange={(e) => set({ area: e.target.value })} />
          </div>
          <div className="field">
            <label>Target</label>
            <input className="input" value={form.target} placeholder="e.g. Top 50% for forwards" onChange={(e) => set({ target: e.target.value })} />
          </div>
        </div>
        <div className="field">
          <label>Detail</label>
          <textarea className="textarea" rows={3} value={form.detail} placeholder="What we’re working on and how…" onChange={(e) => set({ detail: e.target.value })} />
        </div>
        <div className="grid grid-2">
          <div className="field">
            <label>Status</label>
            <select className="select" value={form.status} onChange={(e) => set({ status: e.target.value as ObjectiveStatus })}>
              {STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
          </div>
          <div className="field">
            <label>Review date</label>
            <input className="input" type="date" value={form.reviewDate} onChange={(e) => set({ reviewDate: e.target.value })} />
          </div>
        </div>
      </div>
    </Modal>
  )
}
