import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useData, useActions } from '../store/store'
import { overallRating } from '../analytics/selectors'
import { positionGroupOf } from '../data/formations'
import {
  POSITION_LABEL, genId, clamp,
} from '../utils/format'
import { StatCard } from '../components/ui/StatCard'
import { Modal } from '../components/ui/Modal'
import {
  Avatar, PositionBadge, StatusBadge, EmptyState, attrColor,
  ATTR_ORDER, ATTR_LABEL,
} from '../components/ui/Primitives'
import type { Player, PositionCode, PositionGroup, Foot, PlayerStatus, AttributeKey } from '../types'

// All detailed position codes, ordered by line.
const POSITION_CODES: PositionCode[] = [
  'GK',
  'RB', 'RWB', 'CB', 'LB', 'LWB',
  'CDM', 'CM', 'CAM', 'RM', 'LM',
  'RW', 'LW', 'CF', 'ST',
]

const FOOT_OPTIONS: Foot[] = ['Right', 'Left', 'Both']
const STATUS_OPTIONS: PlayerStatus[] = ['Available', 'Injured', 'Suspended', 'Doubtful']
const GROUP_FILTERS: (PositionGroup | 'All')[] = ['All', 'GK', 'DEF', 'MID', 'FWD']

const COLOR_PALETTE = [
  '#ef4444', '#f97316', '#eab308', '#22c55e', '#14b8a6',
  '#38bdf8', '#3b82f6', '#6366f1', '#a855f7', '#ec4899',
]

type SortKey = 'number' | 'name' | 'rating' | 'value' | 'age'

// A blank player template used to seed the "add" form.
function blankDraft(): Player {
  return {
    id: '',
    name: '',
    number: 0,
    position: 'CM',
    positionGroup: 'MID',
    altPositions: [],
    age: 24,
    heightCm: 180,
    weightKg: 75,
    foot: 'Right',
    nationality: '',
    status: 'Available',
    color: COLOR_PALETTE[5],
    attributes: { pace: 70, shooting: 70, passing: 70, dribbling: 70, defending: 70, physical: 70 },
    notes: '',
    marketValueM: 10,
    joinedYear: new Date().getFullYear(),
  }
}

export function Squad() {
  const data = useData()
  const actions = useActions()
  const navigate = useNavigate()

  const [search, setSearch] = useState('')
  const [group, setGroup] = useState<PositionGroup | 'All'>('All')
  const [sort, setSort] = useState<SortKey>('number')
  const [editing, setEditing] = useState<Player | null>(null)
  const [modalOpen, setModalOpen] = useState(false)

  const players = data.players

  // ---- Summary metrics ---------------------------------------------------
  const summary = useMemo(() => {
    const n = players.length
    const totalValue = players.reduce((s, p) => s + p.marketValueM, 0)
    const avgAge = n ? players.reduce((s, p) => s + p.age, 0) / n : 0
    const avgRating = n ? players.reduce((s, p) => s + overallRating(p), 0) / n : 0
    return { n, totalValue, avgAge, avgRating }
  }, [players])

  // ---- Filter + sort -----------------------------------------------------
  const visible = useMemo(() => {
    const q = search.trim().toLowerCase()
    const filtered = players.filter((p) => {
      if (group !== 'All' && p.positionGroup !== group) return false
      if (q && !p.name.toLowerCase().includes(q)) return false
      return true
    })
    const sorted = [...filtered]
    sorted.sort((a, b) => {
      switch (sort) {
        case 'name': return a.name.localeCompare(b.name)
        case 'rating': return overallRating(b) - overallRating(a)
        case 'value': return b.marketValueM - a.marketValueM
        case 'age': return a.age - b.age
        case 'number':
        default: return a.number - b.number
      }
    })
    return sorted
  }, [players, search, group, sort])

  function openAdd() {
    setEditing(blankDraft())
    setModalOpen(true)
  }

  function openEdit(p: Player) {
    setEditing({ ...p, attributes: { ...p.attributes } })
    setModalOpen(true)
  }

  function closeModal() {
    setModalOpen(false)
    setEditing(null)
  }

  function handleDelete(e: React.MouseEvent, id: string) {
    e.stopPropagation()
    actions.removePlayer(id)
  }

  function handleEditClick(e: React.MouseEvent, p: Player) {
    e.stopPropagation()
    openEdit(p)
  }

  function save(draft: Player) {
    const positionGroup = positionGroupOf(draft.position)
    const next: Player = { ...draft, positionGroup }
    if (next.id) {
      actions.updatePlayer(next)
    } else {
      actions.addPlayer({ ...next, id: genId('p') })
    }
    closeModal()
  }

  return (
    <div className="content">
      <div className="page-head">
        <div>
          <div className="page-title">Squad</div>
          <div className="page-subtitle">
            {summary.n} {summary.n === 1 ? 'player' : 'players'} · total value €{summary.totalValue.toFixed(0)}M
          </div>
        </div>
        <button className="btn primary" onClick={openAdd}>+ Add player</button>
      </div>

      {/* Summary stats */}
      <div className="grid grid-4">
        <StatCard label="Squad size" value={summary.n} icon="👥" />
        <StatCard label="Avg age" value={summary.avgAge.toFixed(1)} unit="yrs" icon="🎂" accent="#38bdf8" />
        <StatCard
          label="Avg overall"
          value={summary.avgRating.toFixed(1)}
          icon="⭐"
          accent={attrColor(summary.avgRating)}
        />
        <StatCard
          label="Total value"
          value={`€${summary.totalValue.toFixed(0)}M`}
          icon="💰"
          accent="#22c55e"
        />
      </div>

      {/* Controls */}
      <div className="card card-pad" style={{ marginTop: 16 }}>
        <div className="row between wrap gap-sm">
          <input
            className="input"
            style={{ maxWidth: 260 }}
            placeholder="Search players…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
          <div className="row gap-sm wrap center">
            <div className="btn-group">
              {GROUP_FILTERS.map((g) => (
                <button
                  key={g}
                  className={group === g ? 'active' : ''}
                  onClick={() => setGroup(g)}
                >
                  {g}
                </button>
              ))}
            </div>
            <select
              className="select"
              style={{ width: 160 }}
              value={sort}
              onChange={(e) => setSort(e.target.value as SortKey)}
            >
              <option value="number">Sort: Number</option>
              <option value="name">Sort: Name</option>
              <option value="rating">Sort: Rating</option>
              <option value="value">Sort: Value</option>
              <option value="age">Sort: Age</option>
            </select>
          </div>
        </div>
      </div>

      {/* Roster table */}
      <div className="card" style={{ marginTop: 16 }}>
        {visible.length === 0 ? (
          <div className="card-pad">
            <EmptyState
              icon="🔍"
              title={players.length === 0 ? 'No players yet' : 'No players match your filters'}
              hint={players.length === 0 ? 'Add your first player to build the squad.' : 'Try clearing the search or position filter.'}
            />
          </div>
        ) : (
          <div className="scroll-x">
            <table className="table clickable">
              <thead>
                <tr>
                  <th className="num">#</th>
                  <th>Player</th>
                  <th className="center-col">Pos</th>
                  <th className="num">Age</th>
                  <th className="center-col">Overall</th>
                  <th>Status</th>
                  <th className="num">Value</th>
                  <th className="center-col">Actions</th>
                </tr>
              </thead>
              <tbody>
                {visible.map((p) => {
                  const ovr = overallRating(p)
                  return (
                    <tr key={p.id} onClick={() => navigate(`/squad/${p.id}`)}>
                      <td className="num mono bold">{p.number}</td>
                      <td>
                        <div className="player-cell">
                          <Avatar player={p} size="sm" />
                          <div>
                            <div className="pname">{p.name}</div>
                            <div className="pmeta">{p.nationality || '—'}</div>
                          </div>
                        </div>
                      </td>
                      <td className="center-col">
                        <PositionBadge group={p.positionGroup} code={p.position} />
                      </td>
                      <td className="num tnum">{p.age}</td>
                      <td className="center-col">
                        <span className="bold tnum" style={{ color: attrColor(ovr), fontSize: 15 }}>{ovr}</span>
                      </td>
                      <td><StatusBadge status={p.status} /></td>
                      <td className="num tnum">€{p.marketValueM}M</td>
                      <td className="center-col">
                        <div className="row gap-sm center" style={{ justifyContent: 'center' }}>
                          <button
                            className="btn ghost sm"
                            onClick={(e) => handleEditClick(e, p)}
                            title="Edit player"
                          >
                            Edit
                          </button>
                          <button
                            className="btn danger sm"
                            onClick={(e) => handleDelete(e, p.id)}
                            title="Remove player"
                          >
                            ✕
                          </button>
                        </div>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {modalOpen && editing && (
        <PlayerForm draft={editing} onCancel={closeModal} onSave={save} />
      )}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Reusable add/edit form (single component for both flows)
// ---------------------------------------------------------------------------
function PlayerForm({
  draft,
  onCancel,
  onSave,
}: {
  draft: Player
  onCancel: () => void
  onSave: (p: Player) => void
}) {
  const [form, setForm] = useState<Player>(draft)
  const isEdit = Boolean(draft.id)
  const nameValid = form.name.trim().length > 0
  const numberValid = Number.isFinite(form.number) && form.number > 0
  const valid = nameValid && numberValid

  function set<K extends keyof Player>(key: K, value: Player[K]) {
    setForm((f) => ({ ...f, [key]: value }))
  }

  function setAttr(key: AttributeKey, value: number) {
    setForm((f) => ({ ...f, attributes: { ...f.attributes, [key]: clamp(value, 1, 99) } }))
  }

  const liveOverall = Math.round(
    ATTR_ORDER.reduce((s, k) => s + form.attributes[k], 0) / ATTR_ORDER.length,
  )

  return (
    <Modal
      title={isEdit ? `Edit ${draft.name || 'player'}` : 'Add player'}
      onClose={onCancel}
      wide
      footer={
        <div className="row gap-sm" style={{ justifyContent: 'flex-end' }}>
          <button className="btn ghost" onClick={onCancel}>Cancel</button>
          <button
            className="btn primary"
            disabled={!valid}
            onClick={() => valid && onSave(form)}
          >
            {isEdit ? 'Save changes' : 'Add player'}
          </button>
        </div>
      }
    >
      <div className="grid grid-2">
        <div className="field">
          <label>Name</label>
          <input
            className="input"
            value={form.name}
            onChange={(e) => set('name', e.target.value)}
            placeholder="Full name"
            autoFocus
          />
          {!nameValid && <span className="tiny text-red">Name is required</span>}
        </div>
        <div className="field">
          <label>Shirt number</label>
          <input
            className="input"
            type="number"
            min={1}
            max={99}
            value={form.number || ''}
            onChange={(e) => set('number', Math.trunc(Number(e.target.value)))}
            placeholder="e.g. 10"
          />
          {!numberValid && <span className="tiny text-red">Number is required</span>}
        </div>

        <div className="field">
          <label>Position</label>
          <select
            className="select"
            value={form.position}
            onChange={(e) => set('position', e.target.value as PositionCode)}
          >
            {POSITION_CODES.map((c) => (
              <option key={c} value={c}>{c} · {POSITION_LABEL[c]}</option>
            ))}
          </select>
        </div>
        <div className="field">
          <label>Foot</label>
          <select
            className="select"
            value={form.foot}
            onChange={(e) => set('foot', e.target.value as Foot)}
          >
            {FOOT_OPTIONS.map((f) => <option key={f} value={f}>{f}</option>)}
          </select>
        </div>

        <div className="field">
          <label>Age</label>
          <input
            className="input"
            type="number"
            min={15}
            max={45}
            value={form.age}
            onChange={(e) => set('age', clamp(Math.trunc(Number(e.target.value)), 15, 45))}
          />
        </div>
        <div className="field">
          <label>Nationality</label>
          <input
            className="input"
            value={form.nationality}
            onChange={(e) => set('nationality', e.target.value)}
            placeholder="e.g. England"
          />
        </div>

        <div className="field">
          <label>Height (cm)</label>
          <input
            className="input"
            type="number"
            min={150}
            max={215}
            value={form.heightCm}
            onChange={(e) => set('heightCm', clamp(Math.trunc(Number(e.target.value)), 150, 215))}
          />
        </div>
        <div className="field">
          <label>Weight (kg)</label>
          <input
            className="input"
            type="number"
            min={50}
            max={120}
            value={form.weightKg}
            onChange={(e) => set('weightKg', clamp(Math.trunc(Number(e.target.value)), 50, 120))}
          />
        </div>

        <div className="field">
          <label>Status</label>
          <select
            className="select"
            value={form.status}
            onChange={(e) => set('status', e.target.value as PlayerStatus)}
          >
            {STATUS_OPTIONS.map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
        </div>
        <div className="field">
          <label>Market value (€M)</label>
          <input
            className="input"
            type="number"
            min={0}
            step={0.5}
            value={form.marketValueM}
            onChange={(e) => set('marketValueM', Math.max(0, Number(e.target.value)))}
          />
        </div>
      </div>

      {/* Accent colour palette */}
      <div className="field" style={{ marginTop: 14 }}>
        <label>Accent colour</label>
        <div className="row gap-sm wrap center">
          {COLOR_PALETTE.map((c) => (
            <button
              key={c}
              type="button"
              onClick={() => set('color', c)}
              aria-label={`Colour ${c}`}
              style={{
                width: 28, height: 28, borderRadius: '50%', cursor: 'pointer',
                background: c, padding: 0,
                border: form.color === c ? '2.5px solid #fff' : '2px solid rgba(255,255,255,0.18)',
                boxShadow: form.color === c ? '0 0 0 3px rgba(56,189,248,0.45)' : 'none',
              }}
            />
          ))}
          <input
            type="color"
            value={form.color}
            onChange={(e) => set('color', e.target.value)}
            aria-label="Custom colour"
            style={{
              width: 34, height: 28, padding: 0, borderRadius: 6,
              border: '2px solid rgba(255,255,255,0.18)', background: 'transparent', cursor: 'pointer',
            }}
          />
        </div>
      </div>

      {/* Attribute sliders */}
      <div className="divider" />
      <div className="row between center" style={{ marginBottom: 10 }}>
        <span className="bold">Attributes</span>
        <span className="row gap-sm center">
          <span className="muted tiny">Overall</span>
          <span className="bold tnum" style={{ color: attrColor(liveOverall), fontSize: 16 }}>{liveOverall}</span>
        </span>
      </div>
      <div className="grid grid-2">
        {ATTR_ORDER.map((k) => (
          <div className="field" key={k}>
            <label className="row between center">
              <span>{ATTR_LABEL[k]}</span>
              <span className="bold tnum" style={{ color: attrColor(form.attributes[k]) }}>{form.attributes[k]}</span>
            </label>
            <input
              className="range"
              type="range"
              min={1}
              max={99}
              value={form.attributes[k]}
              onChange={(e) => setAttr(k, Number(e.target.value))}
              style={{ accentColor: attrColor(form.attributes[k]) }}
            />
          </div>
        ))}
      </div>

      <div className="field" style={{ marginTop: 14 }}>
        <label>Notes</label>
        <textarea
          className="textarea"
          rows={3}
          value={form.notes}
          onChange={(e) => set('notes', e.target.value)}
          placeholder="Scouting / development notes…"
        />
      </div>
    </Modal>
  )
}
