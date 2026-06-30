import { useMemo, useState } from 'react'
import type { Drill, DrillCategory, Intensity, TrainingSession, AttributeKey } from '../types'
import { useData, useActions } from '../store/store'
import { genId, num } from '../utils/format'
import { Modal } from '../components/ui/Modal'
import { EmptyState, SectionTitle } from '../components/ui/Primitives'
import { StatCard } from '../components/ui/StatCard'

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const DRILL_CATEGORIES: DrillCategory[] = [
  'Warm-up',
  'Technical',
  'Possession',
  'Attacking',
  'Defending',
  'Set Pieces',
  'Physical',
  'Finishing',
  'Transition',
]

const INTENSITIES: Intensity[] = ['Low', 'Medium', 'High']

const ATTR_KEYS: AttributeKey[] = ['pace', 'shooting', 'passing', 'dribbling', 'defending', 'physical']

// Distinct accent per drill category so cards read at a glance.
const CATEGORY_COLOR: Record<DrillCategory, string> = {
  'Warm-up': '#f59e0b',
  Technical: '#06b6d4',
  Possession: '#3b82f6',
  Attacking: '#ef4444',
  Defending: '#8b5cf6',
  'Set Pieces': '#eab308',
  Physical: '#f97316',
  Finishing: '#ec4899',
  Transition: '#d946ef',
}

const INTENSITY_COLOR: Record<Intensity, string> = {
  Low: '#22c55e',
  Medium: '#eab308',
  High: '#ef4444',
}

function categoryColor(c: DrillCategory): string {
  return CATEGORY_COLOR[c] ?? '#94a3b8'
}

function cap(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1)
}

type Tab = 'library' | 'planner'

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

export function Training() {
  const data = useData()
  const actions = useActions()

  const [tab, setTab] = useState<Tab>('library')

  // Library filters
  const [catFilter, setCatFilter] = useState<DrillCategory | 'All'>('All')
  const [intFilter, setIntFilter] = useState<Intensity | 'All'>('All')

  // Modal state
  const [drillModal, setDrillModal] = useState<{ open: boolean; edit: Drill | null }>({
    open: false,
    edit: null,
  })
  const [sessionModal, setSessionModal] = useState<{ open: boolean; edit: TrainingSession | null }>({
    open: false,
    edit: null,
  })

  const drills = data.drills
  const sessions = data.sessions

  // Categories that actually have drills, in canonical order.
  const presentCategories = useMemo(() => {
    const set = new Set(drills.map((d) => d.category))
    return DRILL_CATEGORIES.filter((c) => set.has(c))
  }, [drills])

  const filteredDrills = useMemo(() => {
    return drills.filter(
      (d) =>
        (catFilter === 'All' || d.category === catFilter) &&
        (intFilter === 'All' || d.intensity === intFilter),
    )
  }, [drills, catFilter, intFilter])

  const drillById = useMemo(() => {
    const m = new Map<string, Drill>()
    for (const d of drills) m.set(d.id, d)
    return m
  }, [drills])

  // Summary metrics for the header strip.
  const summary = useMemo(() => {
    const totalMin = drills.reduce((s, d) => s + d.durationMin, 0)
    const plannedMin = sessions.reduce(
      (s, ses) => s + ses.drillIds.reduce((t, id) => t + (drillById.get(id)?.durationMin ?? 0), 0),
      0,
    )
    return { totalMin, plannedMin }
  }, [drills, sessions, drillById])

  return (
    <div className="content">
      <div className="page-head">
        <div>
          <div className="page-title">Training</div>
          <div className="page-subtitle">
            Build a drills library and plan training sessions for the week.
          </div>
        </div>
        <button
          className="btn primary"
          onClick={() =>
            tab === 'library'
              ? setDrillModal({ open: true, edit: null })
              : setSessionModal({ open: true, edit: null })
          }
        >
          {tab === 'library' ? '+ Add drill' : '+ New session'}
        </button>
      </div>

      {/* Summary */}
      <div className="grid grid-4">
        <StatCard label="Drills" value={drills.length} icon="📋" accent="#38bdf8" />
        <StatCard
          label="Library minutes"
          value={summary.totalMin}
          unit="min"
          icon="⏱"
          accent="#a855f7"
        />
        <StatCard label="Sessions" value={sessions.length} icon="🗓" accent="#22c55e" />
        <StatCard
          label="Planned minutes"
          value={summary.plannedMin}
          unit="min"
          icon="🏃"
          accent="#f59e0b"
        />
      </div>

      {/* Tabs */}
      <div className="tabs" style={{ marginTop: 18 }}>
        <div
          className={`tab ${tab === 'library' ? 'active' : ''}`}
          onClick={() => setTab('library')}
        >
          Drill library
        </div>
        <div
          className={`tab ${tab === 'planner' ? 'active' : ''}`}
          onClick={() => setTab('planner')}
        >
          Session planner
        </div>
      </div>

      {tab === 'library' ? (
        <LibraryTab
          drills={filteredDrills}
          totalDrills={drills.length}
          presentCategories={presentCategories}
          catFilter={catFilter}
          intFilter={intFilter}
          onCatFilter={setCatFilter}
          onIntFilter={setIntFilter}
          onAdd={() => setDrillModal({ open: true, edit: null })}
          onEdit={(d) => setDrillModal({ open: true, edit: d })}
          onDelete={(id) => actions.removeDrill(id)}
        />
      ) : (
        <PlannerTab
          sessions={sessions}
          drillById={drillById}
          onNew={() => setSessionModal({ open: true, edit: null })}
          onEdit={(s) => setSessionModal({ open: true, edit: s })}
          onDelete={(id) => actions.removeSession(id)}
        />
      )}

      {drillModal.open && (
        <DrillForm
          edit={drillModal.edit}
          onCancel={() => setDrillModal({ open: false, edit: null })}
          onSave={(d) => {
            if (drillModal.edit) actions.updateDrill(d)
            else actions.addDrill(d)
            setDrillModal({ open: false, edit: null })
          }}
        />
      )}

      {sessionModal.open && (
        <SessionForm
          edit={sessionModal.edit}
          drills={drills}
          onCancel={() => setSessionModal({ open: false, edit: null })}
          onSave={(s) => {
            if (sessionModal.edit) actions.updateSession(s)
            else actions.addSession(s)
            setSessionModal({ open: false, edit: null })
          }}
        />
      )}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Library tab
// ---------------------------------------------------------------------------

function LibraryTab({
  drills,
  totalDrills,
  presentCategories,
  catFilter,
  intFilter,
  onCatFilter,
  onIntFilter,
  onAdd,
  onEdit,
  onDelete,
}: {
  drills: Drill[]
  totalDrills: number
  presentCategories: DrillCategory[]
  catFilter: DrillCategory | 'All'
  intFilter: Intensity | 'All'
  onCatFilter: (c: DrillCategory | 'All') => void
  onIntFilter: (i: Intensity | 'All') => void
  onAdd: () => void
  onEdit: (d: Drill) => void
  onDelete: (id: string) => void
}) {
  return (
    <>
      {/* Filters */}
      <div className="card card-pad" style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        <div className="row wrap gap-sm" style={{ alignItems: 'center' }}>
          <span className="faint tiny bold" style={{ width: 64 }}>
            CATEGORY
          </span>
          <span
            className={`chip ${catFilter === 'All' ? 'active' : ''}`}
            onClick={() => onCatFilter('All')}
            style={{ cursor: 'pointer' }}
          >
            All
          </span>
          {presentCategories.map((c) => {
            const active = catFilter === c
            return (
              <span
                key={c}
                className={`chip ${active ? 'active' : ''}`}
                onClick={() => onCatFilter(c)}
                style={{
                  cursor: 'pointer',
                  borderColor: active ? categoryColor(c) : undefined,
                  color: active ? categoryColor(c) : undefined,
                }}
              >
                {c}
              </span>
            )
          })}
        </div>
        <div className="row wrap gap-sm" style={{ alignItems: 'center' }}>
          <span className="faint tiny bold" style={{ width: 64 }}>
            INTENSITY
          </span>
          <span
            className={`chip ${intFilter === 'All' ? 'active' : ''}`}
            onClick={() => onIntFilter('All')}
            style={{ cursor: 'pointer' }}
          >
            All
          </span>
          {INTENSITIES.map((i) => {
            const active = intFilter === i
            return (
              <span
                key={i}
                className={`chip ${active ? 'active' : ''}`}
                onClick={() => onIntFilter(i)}
                style={{
                  cursor: 'pointer',
                  borderColor: active ? INTENSITY_COLOR[i] : undefined,
                  color: active ? INTENSITY_COLOR[i] : undefined,
                }}
              >
                {i}
              </span>
            )
          })}
        </div>
      </div>

      {/* Grid */}
      {totalDrills === 0 ? (
        <div className="card card-pad" style={{ marginTop: 16 }}>
          <EmptyState
            icon="📋"
            title="No drills in the library"
            hint="Add your first drill to start building training sessions."
          />
        </div>
      ) : drills.length === 0 ? (
        <div className="card card-pad" style={{ marginTop: 16 }}>
          <EmptyState
            icon="🔍"
            title="No drills match these filters"
            hint="Try a different category or intensity."
          />
        </div>
      ) : (
        <div className="grid grid-auto" style={{ marginTop: 16 }}>
          {drills.map((d) => (
            <DrillCard key={d.id} drill={d} onEdit={() => onEdit(d)} onDelete={() => onDelete(d.id)} />
          ))}
        </div>
      )}

      <div className="row" style={{ marginTop: 16, justifyContent: 'flex-end' }}>
        <button className="btn ghost sm" onClick={onAdd}>
          + Add drill
        </button>
      </div>
    </>
  )
}

function DrillCard({
  drill,
  onEdit,
  onDelete,
}: {
  drill: Drill
  onEdit: () => void
  onDelete: () => void
}) {
  const color = categoryColor(drill.category)
  return (
    <div className="card card-pad" style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      <div className="between" style={{ alignItems: 'flex-start', gap: 8 }}>
        <div className="bold" style={{ lineHeight: 1.25, fontSize: 15 }}>
          {drill.name}
        </div>
        <span
          className="badge solid"
          style={{ background: color, color: '#0b1120', whiteSpace: 'nowrap' }}
        >
          {drill.category}
        </span>
      </div>

      {/* Meta chips */}
      <div className="row wrap gap-sm">
        <span className="chip">⏱ {drill.durationMin}'</span>
        <span className="chip">👥 {drill.players}</span>
        <span
          className="badge solid"
          style={{ background: INTENSITY_COLOR[drill.intensity], color: '#0b1120' }}
        >
          {drill.intensity}
        </span>
      </div>

      {drill.description && (
        <div className="muted" style={{ fontSize: 13, lineHeight: 1.4 }}>
          {drill.description}
        </div>
      )}

      {drill.objectives.length > 0 && (
        <div>
          <div className="faint tiny bold" style={{ marginBottom: 4 }}>
            OBJECTIVES
          </div>
          <ul style={{ margin: 0, paddingLeft: 18, fontSize: 13, lineHeight: 1.5 }}>
            {drill.objectives.map((o) => (
              <li key={o} className="muted">
                {o}
              </li>
            ))}
          </ul>
        </div>
      )}

      {drill.focus.length > 0 && (
        <div className="tag-list">
          {drill.focus.map((f) => (
            <span key={f} className="chip" style={{ borderColor: `${color}55`, color }}>
              {cap(f)}
            </span>
          ))}
        </div>
      )}

      {drill.equipment.length > 0 && (
        <div className="faint tiny">🎒 {drill.equipment.join(', ')}</div>
      )}

      <div className="row gap-sm" style={{ marginTop: 'auto', paddingTop: 4, justifyContent: 'flex-end' }}>
        <button className="btn ghost sm" onClick={onEdit}>
          Edit
        </button>
        <button className="btn danger sm" onClick={onDelete}>
          Delete
        </button>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Planner tab
// ---------------------------------------------------------------------------

function PlannerTab({
  sessions,
  drillById,
  onNew,
  onEdit,
  onDelete,
}: {
  sessions: TrainingSession[]
  drillById: Map<string, Drill>
  onNew: () => void
  onEdit: (s: TrainingSession) => void
  onDelete: (id: string) => void
}) {
  // Newest first.
  const ordered = useMemo(
    () => [...sessions].sort((a, b) => b.date.localeCompare(a.date)),
    [sessions],
  )

  return (
    <>
      <SectionTitle
        action={
          <button className="btn ghost sm" onClick={onNew}>
            + New session
          </button>
        }
      >
        Planned sessions
      </SectionTitle>

      {ordered.length === 0 ? (
        <div className="card card-pad">
          <EmptyState
            icon="🗓"
            title="No sessions planned"
            hint="Create a session and pick drills from the library to build it out."
          />
        </div>
      ) : (
        <div className="grid grid-2">
          {ordered.map((s) => (
            <SessionCard
              key={s.id}
              session={s}
              drillById={drillById}
              onEdit={() => onEdit(s)}
              onDelete={() => onDelete(s.id)}
            />
          ))}
        </div>
      )}
    </>
  )
}

function SessionCard({
  session,
  drillById,
  onEdit,
  onDelete,
}: {
  session: TrainingSession
  drillById: Map<string, Drill>
  onEdit: () => void
  onDelete: () => void
}) {
  const items = session.drillIds
    .map((id) => drillById.get(id))
    .filter((d): d is Drill => Boolean(d))
  const total = items.reduce((s, d) => s + d.durationMin, 0)

  return (
    <div className="card card-pad" style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      <div className="between" style={{ alignItems: 'flex-start', gap: 8 }}>
        <div>
          <div className="bold" style={{ fontSize: 15 }}>
            {session.title}
          </div>
          <div className="muted tiny">{fmtDate(session.date)}</div>
        </div>
        <span className="badge solid" style={{ background: '#38bdf8', color: '#0b1120', whiteSpace: 'nowrap' }}>
          {total} min
        </span>
      </div>

      {session.focus && (
        <div className="row gap-sm" style={{ alignItems: 'center' }}>
          <span className="faint tiny bold">FOCUS</span>
          <span className="muted" style={{ fontSize: 13 }}>
            {session.focus}
          </span>
        </div>
      )}

      {items.length > 0 ? (
        <table className="table">
          <tbody>
            {items.map((d) => (
              <tr key={d.id}>
                <td>
                  <span
                    className="dot"
                    style={{ background: categoryColor(d.category), marginRight: 8 }}
                  />
                  {d.name}
                </td>
                <td className="muted tiny">{d.category}</td>
                <td className="num mono">{d.durationMin}'</td>
              </tr>
            ))}
            <tr>
              <td className="bold" colSpan={2}>
                Total
              </td>
              <td className="num mono bold">{total}'</td>
            </tr>
          </tbody>
        </table>
      ) : (
        <div className="faint tiny">No drills selected for this session.</div>
      )}

      {session.notes && (
        <div className="muted" style={{ fontSize: 13, lineHeight: 1.4 }}>
          {session.notes}
        </div>
      )}

      <div className="row gap-sm" style={{ marginTop: 'auto', paddingTop: 4, justifyContent: 'flex-end' }}>
        <button className="btn ghost sm" onClick={onEdit}>
          Edit
        </button>
        <button className="btn danger sm" onClick={onDelete}>
          Delete
        </button>
      </div>
    </div>
  )
}

// Local date formatter — sessions sometimes carry blank dates while drafting.
function fmtDate(iso: string): string {
  if (!iso) return 'No date'
  const d = new Date(iso + 'T00:00:00')
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleDateString('en-GB', { weekday: 'short', day: '2-digit', month: 'short', year: 'numeric' })
}

// ---------------------------------------------------------------------------
// Drill add/edit form
// ---------------------------------------------------------------------------

function DrillForm({
  edit,
  onCancel,
  onSave,
}: {
  edit: Drill | null
  onCancel: () => void
  onSave: (d: Drill) => void
}) {
  const [name, setName] = useState(edit?.name ?? '')
  const [category, setCategory] = useState<DrillCategory>(edit?.category ?? 'Technical')
  const [durationMin, setDurationMin] = useState(String(edit?.durationMin ?? 15))
  const [players, setPlayers] = useState(edit?.players ?? '8-12')
  const [intensity, setIntensity] = useState<Intensity>(edit?.intensity ?? 'Medium')
  const [description, setDescription] = useState(edit?.description ?? '')
  const [objectives, setObjectives] = useState((edit?.objectives ?? []).join('\n'))
  const [equipment, setEquipment] = useState((edit?.equipment ?? []).join('\n'))
  const [focus, setFocus] = useState<AttributeKey[]>(edit?.focus ?? [])

  const nameValid = name.trim().length > 0
  const durationValid = Number(durationMin) > 0
  const valid = nameValid && durationValid

  function toggleFocus(k: AttributeKey) {
    setFocus((prev) => (prev.includes(k) ? prev.filter((x) => x !== k) : [...prev, k]))
  }

  function save() {
    if (!valid) return
    const d: Drill = {
      id: edit?.id ?? genId('d'),
      name: name.trim(),
      category,
      durationMin: Math.max(1, Math.round(Number(durationMin) || 0)),
      players: players.trim() || '—',
      intensity,
      description: description.trim(),
      objectives: objectives
        .split('\n')
        .map((s) => s.trim())
        .filter(Boolean),
      equipment: equipment
        .split('\n')
        .map((s) => s.trim())
        .filter(Boolean),
      focus,
    }
    onSave(d)
  }

  return (
    <Modal
      title={edit ? 'Edit drill' : 'Add drill'}
      onClose={onCancel}
      wide
      footer={
        <div className="row gap-sm" style={{ justifyContent: 'flex-end' }}>
          <button className="btn ghost" onClick={onCancel}>
            Cancel
          </button>
          <button className="btn primary" disabled={!valid} onClick={save}>
            {edit ? 'Save changes' : 'Add drill'}
          </button>
        </div>
      }
    >
      <div className="grid grid-2">
        <div className="field">
          <label>Name</label>
          <input
            className="input"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Rondo 5v2"
            autoFocus
          />
        </div>
        <div className="field">
          <label>Category</label>
          <select
            className="select"
            value={category}
            onChange={(e) => setCategory(e.target.value as DrillCategory)}
          >
            {DRILL_CATEGORIES.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label>Duration (min)</label>
          <input
            className="input"
            type="number"
            min={1}
            value={durationMin}
            onChange={(e) => setDurationMin(e.target.value)}
          />
        </div>
        <div className="field">
          <label>Players</label>
          <input
            className="input"
            value={players}
            onChange={(e) => setPlayers(e.target.value)}
            placeholder="8-12"
          />
        </div>
        <div className="field">
          <label>Intensity</label>
          <select
            className="select"
            value={intensity}
            onChange={(e) => setIntensity(e.target.value as Intensity)}
          >
            {INTENSITIES.map((i) => (
              <option key={i} value={i}>
                {i}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div className="field" style={{ marginTop: 12 }}>
        <label>Description</label>
        <textarea
          className="textarea"
          rows={2}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="Set up a 12x12 grid; possession team keeps the ball..."
        />
      </div>

      <div className="grid grid-2" style={{ marginTop: 12 }}>
        <div className="field">
          <label>Objectives (one per line)</label>
          <textarea
            className="textarea"
            rows={3}
            value={objectives}
            onChange={(e) => setObjectives(e.target.value)}
            placeholder={'Quick ball circulation\nBody shape to receive'}
          />
        </div>
        <div className="field">
          <label>Equipment (one per line)</label>
          <textarea
            className="textarea"
            rows={3}
            value={equipment}
            onChange={(e) => setEquipment(e.target.value)}
            placeholder={'Cones\nBibs\nBalls'}
          />
        </div>
      </div>

      <div className="field" style={{ marginTop: 12 }}>
        <label>Focus attributes</label>
        <div className="row wrap gap-sm">
          {ATTR_KEYS.map((k) => {
            const active = focus.includes(k)
            return (
              <span
                key={k}
                className={`chip ${active ? 'active' : ''}`}
                onClick={() => toggleFocus(k)}
                style={{ cursor: 'pointer' }}
              >
                {active ? '✓ ' : ''}
                {cap(k)}
              </span>
            )
          })}
        </div>
      </div>
    </Modal>
  )
}

// ---------------------------------------------------------------------------
// Session add/edit form
// ---------------------------------------------------------------------------

function SessionForm({
  edit,
  drills,
  onCancel,
  onSave,
}: {
  edit: TrainingSession | null
  drills: Drill[]
  onCancel: () => void
  onSave: (s: TrainingSession) => void
}) {
  const today = new Date().toISOString().slice(0, 10)
  const [date, setDate] = useState(edit?.date ?? today)
  const [title, setTitle] = useState(edit?.title ?? '')
  const [focus, setFocus] = useState(edit?.focus ?? '')
  const [notes, setNotes] = useState(edit?.notes ?? '')
  const [selected, setSelected] = useState<string[]>(edit?.drillIds ?? [])

  const selectedSet = useMemo(() => new Set(selected), [selected])
  const totalMin = useMemo(
    () =>
      drills
        .filter((d) => selectedSet.has(d.id))
        .reduce((s, d) => s + d.durationMin, 0),
    [drills, selectedSet],
  )

  const valid = title.trim().length > 0

  function toggle(id: string) {
    setSelected((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]))
  }

  function save() {
    if (!valid) return
    const s: TrainingSession = {
      id: edit?.id ?? genId('s'),
      date,
      title: title.trim(),
      focus: focus.trim(),
      drillIds: selected,
      notes: notes.trim(),
    }
    onSave(s)
  }

  return (
    <Modal
      title={edit ? 'Edit session' : 'New session'}
      onClose={onCancel}
      wide
      footer={
        <div className="between" style={{ width: '100%', alignItems: 'center' }}>
          <span className="muted tiny">
            {selected.length} drill{selected.length === 1 ? '' : 's'} ·{' '}
            <span className="bold">{num(totalMin)} min</span> total
          </span>
          <div className="row gap-sm">
            <button className="btn ghost" onClick={onCancel}>
              Cancel
            </button>
            <button className="btn primary" disabled={!valid} onClick={save}>
              {edit ? 'Save changes' : 'Create session'}
            </button>
          </div>
        </div>
      }
    >
      <div className="grid grid-2">
        <div className="field">
          <label>Date</label>
          <input
            className="input"
            type="date"
            value={date}
            onChange={(e) => setDate(e.target.value)}
          />
        </div>
        <div className="field">
          <label>Title</label>
          <input
            className="input"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="Matchday -2: pressing"
            autoFocus
          />
        </div>
      </div>

      <div className="field" style={{ marginTop: 12 }}>
        <label>Focus</label>
        <input
          className="input"
          value={focus}
          onChange={(e) => setFocus(e.target.value)}
          placeholder="High press triggers & quick transitions"
        />
      </div>

      <div className="field" style={{ marginTop: 12 }}>
        <div className="between" style={{ alignItems: 'baseline' }}>
          <label>Drills</label>
          <span className="faint tiny">
            {selected.length} selected · {num(totalMin)} min
          </span>
        </div>
        {drills.length === 0 ? (
          <div className="faint tiny">Add drills to the library first.</div>
        ) : (
          <div
            className="panel"
            style={{ maxHeight: 280, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 2 }}
          >
            {drills.map((d) => {
              const on = selectedSet.has(d.id)
              return (
                <label
                  key={d.id}
                  className="row between"
                  style={{
                    alignItems: 'center',
                    gap: 10,
                    padding: '8px 10px',
                    borderRadius: 8,
                    cursor: 'pointer',
                    background: on ? 'rgba(56,189,248,0.12)' : 'transparent',
                  }}
                >
                  <div className="row gap-sm" style={{ alignItems: 'center', minWidth: 0 }}>
                    <input type="checkbox" checked={on} onChange={() => toggle(d.id)} />
                    <span
                      className="dot"
                      style={{ background: categoryColor(d.category) }}
                    />
                    <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{d.name}</span>
                  </div>
                  <div className="row gap-sm" style={{ alignItems: 'center', whiteSpace: 'nowrap' }}>
                    <span className="muted tiny">{d.category}</span>
                    <span className="mono tiny">{d.durationMin}'</span>
                  </div>
                </label>
              )
            })}
          </div>
        )}
      </div>

      <div className="field" style={{ marginTop: 12 }}>
        <label>Notes</label>
        <textarea
          className="textarea"
          rows={2}
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          placeholder="Keep intensity high; rotate keepers after warm-up."
        />
      </div>
    </Modal>
  )
}
