import { useState } from 'react'
import { useData, useActions } from '../store/store'
import { RoutineBoard } from '../components/pitch/RoutineBoard'
import { genId } from '../utils/format'
import type { SetPiece, SetPieceType, ArrowKind, TokenKind, RoutineToken } from '../types'
import { EmptyState } from '../components/ui/Primitives'

const TYPES: SetPieceType[] = [
  'Attacking Corner', 'Defending Corner', 'Attacking Free-kick', 'Defending Free-kick', 'Throw-in', 'Kick-off', 'Penalty',
]
const ARROW_KINDS: { kind: ArrowKind; label: string }[] = [
  { kind: 'run', label: 'Run' },
  { kind: 'pass', label: 'Pass' },
  { kind: 'dribble', label: 'Dribble' },
]
const ARROW_COLORS = ['#38bdf8', '#fbbf24', '#22c55e', '#ef4444', '#ffffff']
const PALETTE: { kind: TokenKind; label: string; icon: string }[] = [
  { kind: 'player', label: 'Player', icon: '🔵' },
  { kind: 'opponent', label: 'Opponent', icon: '⚫' },
  { kind: 'ball', label: 'Ball', icon: '⚽' },
  { kind: 'cone', label: 'Cone', icon: '🔶' },
]

const blank = (): SetPiece => ({ id: '', name: 'New routine', type: 'Attacking Corner', tokens: [], arrows: [], notes: '' })

export function SetPieces() {
  const data = useData()
  const actions = useActions()

  const [routine, setRoutine] = useState<SetPiece>(blank)
  const [mode, setMode] = useState<'move' | 'draw'>('move')
  const [arrowKind, setArrowKind] = useState<ArrowKind>('run')
  const [arrowColor, setArrowColor] = useState('#38bdf8')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [filterType, setFilterType] = useState<SetPieceType | 'all'>('all')

  const selectedToken = routine.tokens.find((t) => t.id === selectedId) ?? null

  function addToken(kind: TokenKind) {
    const playerCount = routine.tokens.filter((t) => t.kind === 'player').length
    const tok: RoutineToken = {
      id: genId('tk'),
      kind,
      label: kind === 'player' ? String(playerCount + 1) : '',
      x: 50,
      y: kind === 'ball' ? 50 : 60,
    }
    setRoutine((r) => ({ ...r, tokens: [...r.tokens, tok] }))
    setMode('move')
    setSelectedId(tok.id)
  }
  function setTokenLabel(label: string) {
    if (!selectedToken) return
    setRoutine((r) => ({ ...r, tokens: r.tokens.map((t) => (t.id === selectedToken.id ? { ...t, label } : t)) }))
  }
  function deleteSelected() {
    if (!selectedToken) return
    setRoutine((r) => ({ ...r, tokens: r.tokens.filter((t) => t.id !== selectedToken.id) }))
    setSelectedId(null)
  }

  function save() {
    const id = routine.id || genId('sp')
    const sp = { ...routine, id, name: routine.name.trim() || 'Untitled routine' }
    actions.saveSetPiece(sp)
    setRoutine(sp)
  }
  function loadRoutine(sp: SetPiece) {
    setRoutine(JSON.parse(JSON.stringify(sp)))
    setSelectedId(null)
    setMode('move')
  }

  const library = filterType === 'all' ? data.setPieces : data.setPieces.filter((s) => s.type === filterType)
  const isDirty = !routine.id || JSON.stringify(data.setPieces.find((s) => s.id === routine.id)) !== JSON.stringify(routine)

  return (
    <div className="content">
      <div className="page-head">
        <div>
          <div className="page-title">Set-Piece Designer</div>
          <div className="page-subtitle">Place players, the ball and opponents, then draw runs, passes and dribbles. Save routines to your playbook.</div>
        </div>
        <div className="row gap-sm">
          <button className="btn ghost" onClick={() => { setRoutine(blank()); setSelectedId(null) }}>＋ New</button>
          <button className="btn primary" onClick={save} disabled={!isDirty}>💾 Save routine</button>
        </div>
      </div>

      <div className="row" style={{ alignItems: 'flex-start' }}>
        {/* Board + toolbar */}
        <div className="flex-1">
          <div className="card card-pad">
            {/* Toolbar */}
            <div className="row between center wrap" style={{ gap: 10, marginBottom: 12 }}>
              <div className="btn-group">
                <button className={mode === 'move' ? 'active' : ''} onClick={() => setMode('move')}>✋ Move</button>
                <button className={mode === 'draw' ? 'active' : ''} onClick={() => setMode('draw')}>✏️ Draw</button>
              </div>
              <div className="row gap-sm center wrap">
                {PALETTE.map((p) => (
                  <button key={p.kind} className="btn sm" onClick={() => addToken(p.kind)} title={`Add ${p.label}`}>{p.icon} {p.label}</button>
                ))}
              </div>
            </div>

            {mode === 'draw' && (
              <div className="row between center wrap" style={{ gap: 10, marginBottom: 12, padding: '8px 10px', background: 'rgba(56,189,248,0.06)', borderRadius: 10 }}>
                <div className="btn-group">
                  {ARROW_KINDS.map((k) => (
                    <button key={k.kind} className={arrowKind === k.kind ? 'active' : ''} onClick={() => setArrowKind(k.kind)}>{k.label}</button>
                  ))}
                </div>
                <div className="row gap-sm center">
                  {ARROW_COLORS.map((c) => (
                    <button key={c} onClick={() => setArrowColor(c)} title={c} style={{ width: 22, height: 22, borderRadius: '50%', background: c, cursor: 'pointer', border: arrowColor === c ? '2px solid #fff' : '2px solid rgba(255,255,255,0.25)' }} />
                  ))}
                </div>
                <div className="row gap-sm center">
                  <button className="btn sm ghost" disabled={routine.arrows.length === 0} onClick={() => setRoutine((r) => ({ ...r, arrows: r.arrows.slice(0, -1) }))}>↺ Undo</button>
                  <button className="btn sm ghost" disabled={routine.arrows.length === 0} onClick={() => setRoutine((r) => ({ ...r, arrows: [] }))}>Clear arrows</button>
                </div>
              </div>
            )}

            <div style={{ maxWidth: 460, margin: '0 auto' }}>
              <RoutineBoard
                tokens={routine.tokens}
                arrows={routine.arrows}
                mode={mode}
                arrowKind={arrowKind}
                arrowColor={arrowColor}
                selectedId={selectedId}
                onTokensChange={(t) => setRoutine((r) => ({ ...r, tokens: t }))}
                onArrowsChange={(a) => setRoutine((r) => ({ ...r, arrows: a }))}
                onSelect={setSelectedId}
              />
            </div>

            <div className="legend" style={{ justifyContent: 'center', marginTop: 12 }}>
              <span className="item"><svg width="28" height="8"><line x1="2" y1="4" x2="26" y2="4" stroke="#9fb0c8" strokeWidth="2" /></svg> Run</span>
              <span className="item"><svg width="28" height="8"><line x1="2" y1="4" x2="26" y2="4" stroke="#9fb0c8" strokeWidth="2" strokeDasharray="5 3" /></svg> Pass</span>
              <span className="item"><svg width="28" height="8"><line x1="2" y1="4" x2="26" y2="4" stroke="#9fb0c8" strokeWidth="2" strokeDasharray="1 3" /></svg> Dribble</span>
              <span className="faint tiny">{mode === 'draw' ? 'Drag on the pitch to draw' : 'Drag tokens to position · tap a token to select'}</span>
            </div>
          </div>
        </div>

        {/* Sidebar */}
        <div style={{ width: 320, flexShrink: 0 }}>
          <div className="card card-pad">
            <div className="field" style={{ marginBottom: 12 }}>
              <label>Routine name</label>
              <input className="input" value={routine.name} onChange={(e) => setRoutine((r) => ({ ...r, name: e.target.value }))} />
            </div>
            <div className="field" style={{ marginBottom: 12 }}>
              <label>Type</label>
              <select className="select" value={routine.type} onChange={(e) => setRoutine((r) => ({ ...r, type: e.target.value as SetPieceType }))}>
                {TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
              </select>
            </div>
            <div className="field">
              <label>Coaching notes</label>
              <textarea className="textarea" rows={3} value={routine.notes} placeholder="Roles, triggers, key points…" onChange={(e) => setRoutine((r) => ({ ...r, notes: e.target.value }))} />
            </div>

            {selectedToken && (
              <div className="panel" style={{ padding: 12, marginTop: 12 }}>
                <div className="row between center" style={{ marginBottom: 8 }}>
                  <span className="bold tiny">Selected {selectedToken.kind}</span>
                  <button className="btn danger sm" onClick={deleteSelected}>Delete</button>
                </div>
                {(selectedToken.kind === 'player' || selectedToken.kind === 'opponent') && (
                  <div className="field">
                    <label>Label</label>
                    <input className="input" value={selectedToken.label} maxLength={3} placeholder="No." onChange={(e) => setTokenLabel(e.target.value)} />
                  </div>
                )}
              </div>
            )}
          </div>

          {/* Library */}
          <div className="card" style={{ marginTop: 16 }}>
            <div className="card-head">
              <h3>Playbook</h3>
              <span className="badge">{data.setPieces.length}</span>
            </div>
            <div className="card-pad" style={{ paddingBottom: 8 }}>
              <select className="select" value={filterType} onChange={(e) => setFilterType(e.target.value as SetPieceType | 'all')}>
                <option value="all">All types</option>
                {TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
              </select>
            </div>
            <div style={{ maxHeight: 360, overflowY: 'auto', padding: '0 12px 12px' }}>
              {library.length === 0 ? (
                <EmptyState icon="♟️" title="No routines saved" hint="Design one and hit Save." />
              ) : (
                <div className="col" style={{ gap: 6 }}>
                  {library.map((sp) => (
                    <div key={sp.id} className={`panel${routine.id === sp.id ? '' : ''}`} style={{ padding: 10, borderLeft: routine.id === sp.id ? '3px solid var(--accent)' : '3px solid transparent' }}>
                      <div className="row between center" style={{ gap: 8 }}>
                        <div style={{ minWidth: 0, cursor: 'pointer' }} onClick={() => loadRoutine(sp)}>
                          <div className="bold" style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{sp.name}</div>
                          <div className="faint tiny">{sp.type} · {sp.tokens.length} tokens · {sp.arrows.length} arrows</div>
                        </div>
                        <div className="row gap-sm">
                          <button className="btn ghost sm" onClick={() => loadRoutine(sp)}>Open</button>
                          <button className="btn danger sm" onClick={() => actions.removeSetPiece(sp.id)}>✕</button>
                        </div>
                      </div>
                    </div>
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
