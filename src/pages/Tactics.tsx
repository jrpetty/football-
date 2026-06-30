import { useMemo, useRef, useState } from 'react'
import { useData, useActions } from '../store/store'
import { FORMATIONS, getFormation } from '../data/formations'
import { PitchField, slotStyle } from '../components/pitch/Pitch'
import { POSITION_GROUP_COLOR, POSITION_LABEL, genId, clamp } from '../utils/format'
import { positionGroupOf } from '../data/formations'
import type { LineupSlot, Player } from '../types'
import { EmptyState } from '../components/ui/Primitives'

interface DragState {
  index: number
  moved: boolean
}

function buildSlots(formation: string): LineupSlot[] {
  const preset = getFormation(formation) ?? FORMATIONS[0]
  return preset.slots.map((s) => ({
    playerId: '',
    position: s.position,
    x: s.x,
    y: s.y,
    isStarter: true,
  }))
}

export function Tactics() {
  const data = useData()
  const actions = useActions()

  const [formation, setFormation] = useState('4-3-3')
  const [slots, setSlots] = useState<LineupSlot[]>(() => buildSlots('4-3-3'))
  const [selected, setSelected] = useState<number | null>(null)
  const [name, setName] = useState('New lineup')
  const fieldRef = useRef<HTMLDivElement>(null)
  const drag = useRef<DragState | null>(null)

  const playerById = useMemo(() => {
    const m = new Map<string, Player>()
    for (const p of data.players) m.set(p.id, p)
    return m
  }, [data.players])

  const assignedIds = new Set(slots.map((s) => s.playerId).filter(Boolean))
  const available = data.players.filter((p) => !assignedIds.has(p.id))

  function changeFormation(f: string) {
    setFormation(f)
    // Keep current player assignments where the new formation has a matching
    // position group, otherwise drop them onto the bench.
    const next = buildSlots(f)
    const pool = slots.map((s) => s.playerId).filter(Boolean)
    const used = new Set<string>()
    for (const slot of next) {
      const group = positionGroupOf(slot.position)
      const match = pool.find((id) => {
        if (used.has(id)) return false
        const pl = playerById.get(id)
        return pl && pl.positionGroup === group
      })
      if (match) {
        slot.playerId = match
        used.add(match)
      }
    }
    setSlots(next)
    setSelected(null)
  }

  function autoFill() {
    const next = slots.map((s) => ({ ...s, playerId: '' }))
    const used = new Set<string>()
    const byGroup = (g: string) =>
      data.players
        .filter((p) => p.positionGroup === g && p.status === 'Available' && !used.has(p.id))
        .sort((a, b) => {
          const sa = Object.values(a.attributes).reduce((x, y) => x + y, 0)
          const sb = Object.values(b.attributes).reduce((x, y) => x + y, 0)
          return sb - sa
        })
    for (const slot of next) {
      const group = positionGroupOf(slot.position)
      const candidate = byGroup(group)[0] ?? data.players.find((p) => !used.has(p.id))
      if (candidate) {
        slot.playerId = candidate.id
        used.add(candidate.id)
      }
    }
    setSlots(next)
  }

  function assignToSelected(playerId: string) {
    if (selected === null) return
    setSlots((prev) => {
      const next = prev.map((s) => ({ ...s }))
      // If this player already occupies another slot, clear it (swap-safe).
      for (const s of next) if (s.playerId === playerId) s.playerId = ''
      next[selected].playerId = playerId
      return next
    })
  }

  function clearSlot(index: number) {
    setSlots((prev) => prev.map((s, i) => (i === index ? { ...s, playerId: '' } : s)))
  }

  // ---- Pointer drag to reposition tokens ---------------------------------
  function onPointerDown(e: React.PointerEvent, index: number) {
    e.preventDefault()
    drag.current = { index, moved: false }
    ;(e.target as HTMLElement).setPointerCapture?.(e.pointerId)
    window.addEventListener('pointermove', onPointerMove)
    window.addEventListener('pointerup', onPointerUp)
  }
  function onPointerMove(e: PointerEvent) {
    const d = drag.current
    const rect = fieldRef.current?.getBoundingClientRect()
    if (!d || !rect) return
    d.moved = true
    const x = clamp(((e.clientX - rect.left) / rect.width) * 100, 3, 97)
    const y = clamp((1 - (e.clientY - rect.top) / rect.height) * 100, 3, 97)
    setSlots((prev) => prev.map((s, i) => (i === d.index ? { ...s, x: Math.round(x), y: Math.round(y) } : s)))
  }
  function onPointerUp() {
    const d = drag.current
    window.removeEventListener('pointermove', onPointerMove)
    window.removeEventListener('pointerup', onPointerUp)
    if (d && !d.moved) setSelected(d.index === selected ? null : d.index)
    drag.current = null
  }

  function save() {
    actions.saveLineup({
      id: genId('l'),
      name: name.trim() || 'Untitled lineup',
      formation,
      slots,
      notes: '',
    })
  }

  function loadLineup(id: string) {
    const l = data.lineups.find((x) => x.id === id)
    if (!l) return
    setFormation(l.formation)
    setSlots(l.slots.map((s) => ({ ...s })))
    setName(l.name)
    setSelected(null)
  }

  const assignedCount = slots.filter((s) => s.playerId).length

  return (
    <div className="content">
      <div className="page-head">
        <div>
          <div className="page-title">Tactics Board</div>
          <div className="page-subtitle">
            Pick a formation, assign players and drag them to fine-tune positions. {assignedCount}/11 assigned.
          </div>
        </div>
        <div className="row gap-sm wrap">
          {data.lineups.length > 0 && (
            <select className="select" style={{ width: 200 }} defaultValue="" onChange={(e) => e.target.value && loadLineup(e.target.value)}>
              <option value="">Load saved lineup…</option>
              {data.lineups.map((l) => (
                <option key={l.id} value={l.id}>{l.name} ({l.formation})</option>
              ))}
            </select>
          )}
          <button className="btn" onClick={autoFill}>⚡ Auto-fill best XI</button>
        </div>
      </div>

      <div className="row" style={{ alignItems: 'flex-start' }}>
        {/* Pitch */}
        <div className="flex-1">
          <div className="card card-pad">
            <div className="row between center wrap" style={{ marginBottom: 14 }}>
              <div className="btn-group">
                {FORMATIONS.map((f) => (
                  <button key={f.name} className={formation === f.name ? 'active' : ''} onClick={() => changeFormation(f.name)}>
                    {f.name}
                  </button>
                ))}
              </div>
              <div className="faint tiny">{getFormation(formation)?.description}</div>
            </div>

            <div ref={fieldRef} style={{ maxWidth: 520, margin: '0 auto' }}>
              <PitchField>
                {slots.map((slot, i) => {
                  const p = slot.playerId ? playerById.get(slot.playerId) : undefined
                  const color = POSITION_GROUP_COLOR[positionGroupOf(slot.position)]
                  const isSel = selected === i
                  return (
                    <div key={i} style={slotStyle(slot.x, slot.y)}>
                      <div
                        onPointerDown={(e) => onPointerDown(e, i)}
                        style={{
                          width: 40, height: 40, borderRadius: '50%',
                          display: 'grid', placeItems: 'center', cursor: 'grab',
                          background: p ? `linear-gradient(135deg, ${p.color}, ${p.color}bb)` : 'rgba(15,23,42,0.7)',
                          border: isSel ? '2.5px solid #fff' : `2px solid ${color}`,
                          boxShadow: isSel ? '0 0 0 4px rgba(56,189,248,0.4)' : '0 4px 10px rgba(0,0,0,0.4)',
                          color: '#fff', fontWeight: 800, fontSize: 14, touchAction: 'none', userSelect: 'none',
                        }}
                      >
                        {p ? p.number : '+'}
                      </div>
                      <div
                        style={{
                          marginTop: 3, textAlign: 'center', fontSize: 10, fontWeight: 600,
                          color: '#fff', textShadow: '0 1px 3px rgba(0,0,0,0.9)', whiteSpace: 'nowrap',
                        }}
                      >
                        {p ? p.name.split(' ').slice(-1)[0] : slot.position}
                      </div>
                    </div>
                  )
                })}
              </PitchField>
            </div>

            <div className="row gap-sm center" style={{ marginTop: 16, justifyContent: 'center' }}>
              <input className="input" style={{ maxWidth: 240 }} value={name} onChange={(e) => setName(e.target.value)} placeholder="Lineup name" />
              <button className="btn primary" onClick={save}>💾 Save lineup</button>
              <button className="btn ghost" onClick={() => changeFormation(formation)}>Reset</button>
            </div>
          </div>
        </div>

        {/* Assignment panel */}
        <div style={{ width: 320, flexShrink: 0 }}>
          <div className="card">
            <div className="card-head">
              <h3>{selected !== null ? `Assign ${POSITION_LABEL[slots[selected].position]}` : 'Squad'}</h3>
              {selected !== null && (
                <button className="btn ghost sm" onClick={() => { clearSlot(selected); }}>Clear slot</button>
              )}
            </div>
            <div className="card-pad" style={{ maxHeight: 560, overflowY: 'auto' }}>
              {selected === null && (
                <p className="faint tiny" style={{ marginBottom: 12 }}>
                  Tap a position on the pitch to assign a player, or drag tokens to reposition them.
                </p>
              )}
              {available.length === 0 ? (
                <EmptyState icon="✅" title="Everyone is on the pitch" />
              ) : (
                <div className="col" style={{ gap: 6 }}>
                  {available.map((p) => (
                    <button
                      key={p.id}
                      onClick={() => assignToSelected(p.id)}
                      disabled={selected === null}
                      className="btn ghost"
                      style={{ justifyContent: 'flex-start', opacity: selected === null ? 0.55 : 1 }}
                    >
                      <span className="pos-pill" style={{ background: POSITION_GROUP_COLOR[p.positionGroup] }}>{p.position}</span>
                      <span style={{ fontWeight: 700 }}>{p.number}</span>
                      <span style={{ flex: 1, textAlign: 'left', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{p.name}</span>
                      {p.status !== 'Available' && <span className="dot" style={{ background: '#eab308' }} title={p.status} />}
                    </button>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
