import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useData, useActions } from '../store/store'
import type { DevObjective, ObjectiveStatus } from '../types'
import { genId, fmtDate, objectiveStatusColor } from '../utils/format'
import { Avatar, EmptyState } from '../components/ui/Primitives'
import { StatCard } from '../components/ui/StatCard'
import { DevObjectiveEditor } from '../components/DevObjectiveEditor'

const STATUSES: ObjectiveStatus[] = ['Not started', 'In progress', 'On track', 'Achieved']

function newObjective(): DevObjective {
  return {
    id: '', playerId: '', title: '', area: '', detail: '', status: 'Not started',
    target: '', created: new Date().toISOString().slice(0, 10), reviewDate: '',
  }
}

export function Development() {
  const data = useData()
  const actions = useActions()
  const [editing, setEditing] = useState<DevObjective | null>(null)
  const [statusFilter, setStatusFilter] = useState<ObjectiveStatus | 'all'>('all')

  const playerById = useMemo(() => new Map(data.players.map((p) => [p.id, p])), [data.players])

  const counts = useMemo(() => {
    const c: Record<string, number> = { 'Not started': 0, 'In progress': 0, 'On track': 0, 'Achieved': 0 }
    for (const o of data.objectives) c[o.status] = (c[o.status] ?? 0) + 1
    return c
  }, [data.objectives])

  const shown = data.objectives
    .filter((o) => statusFilter === 'all' || o.status === statusFilter)
    .sort((a, b) => (a.reviewDate || '9999').localeCompare(b.reviewDate || '9999'))

  function save(o: DevObjective) {
    actions.saveObjective({ ...o, id: o.id || genId('ob') })
    setEditing(null)
  }

  return (
    <div className="content">
      <div className="page-head">
        <div>
          <div className="page-title">Player Development</div>
          <div className="page-subtitle">Track development objectives across the squad with targets and review dates.</div>
        </div>
        <button className="btn primary" onClick={() => setEditing(newObjective())}>＋ New objective</button>
      </div>

      <div className="grid grid-4" style={{ marginBottom: 18 }}>
        <StatCard label="Total objectives" value={data.objectives.length} icon="🎯" />
        {STATUSES.map((s) => (
          <StatCard key={s} label={s} value={counts[s]} accent={objectiveStatusColor(s)} />
        ))}
      </div>

      <div className="row gap-sm wrap" style={{ marginBottom: 14 }}>
        <span className={`chip${statusFilter === 'all' ? ' active' : ''}`} style={{ cursor: 'pointer' }} onClick={() => setStatusFilter('all')}>All ({data.objectives.length})</span>
        {STATUSES.map((s) => (
          <span key={s} className={`chip${statusFilter === s ? ' active' : ''}`} style={{ cursor: 'pointer' }} onClick={() => setStatusFilter(s)}>
            <span className="dot" style={{ background: objectiveStatusColor(s) }} /> {s} ({counts[s]})
          </span>
        ))}
      </div>

      {shown.length === 0 ? (
        <div className="card card-pad">
          <EmptyState icon="🎯" title="No objectives yet" hint="Create development objectives here, or from a player's profile." />
        </div>
      ) : (
        <div className="col" style={{ gap: 10 }}>
          {shown.map((o) => {
            const player = playerById.get(o.playerId)
            const c = objectiveStatusColor(o.status)
            return (
              <div key={o.id} className="card card-pad" style={{ borderLeft: `3px solid ${c}` }}>
                <div className="row between center wrap" style={{ gap: 12 }}>
                  <div className="row gap-sm center" style={{ minWidth: 0 }}>
                    {player && <Avatar player={player} size="sm" />}
                    <div style={{ minWidth: 0 }}>
                      <div className="bold">{o.title || '(untitled)'}</div>
                      <div className="faint tiny">
                        {player ? <Link className="link" to={`/squad/${player.id}`}>{player.name}</Link> : 'Unknown player'}
                        {o.area && ` · ${o.area}`}
                        {o.target && ` · target: ${o.target}`}
                      </div>
                    </div>
                  </div>
                  <div className="row gap-sm center">
                    {o.reviewDate && <span className="badge tiny" title="Review date">📅 {fmtDate(o.reviewDate)}</span>}
                    <span className="badge" style={{ borderColor: `${c}55`, color: c }}><span className="dot" style={{ background: c }} /> {o.status}</span>
                    <button className="btn ghost sm" onClick={() => setEditing(o)}>Edit</button>
                    <button className="btn danger sm" onClick={() => actions.removeObjective(o.id)}>✕</button>
                  </div>
                </div>
                {o.detail && <p className="muted tiny" style={{ marginTop: 8 }}>{o.detail}</p>}
              </div>
            )
          })}
        </div>
      )}

      {editing && (
        <DevObjectiveEditor objective={editing} players={data.players} onClose={() => setEditing(null)} onSave={save} />
      )}
    </div>
  )
}
