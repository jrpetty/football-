import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useData, useActions } from '../store/store'
import { opponentNames, oppositionMoments, headToHead } from '../analytics/selectors'
import type { ScoutingReport, TagCategory } from '../types'
import { genId, fmtDate, RESULT_COLOR, resultOf } from '../utils/format'
import { fmtClock } from '../utils/video'
import { tagMeta } from '../data/tags'
import { EmptyState, FormPips } from '../components/ui/Primitives'

const BLANK = (opponent: string): ScoutingReport => ({
  id: '', opponent, threats: '', weaknesses: '', setPieces: '', gameplan: '', formation: '', updated: '',
})

export function Scouting() {
  const data = useData()
  const actions = useActions()

  const opponents = opponentNames(data)
  const [selected, setSelected] = useState<string | null>(opponents[0] ?? null)
  const [newOpp, setNewOpp] = useState('')

  const existing = useMemo(
    () => (selected ? data.scouting.find((s) => s.opponent === selected) : undefined),
    [data.scouting, selected],
  )
  const [form, setForm] = useState<ScoutingReport>(() => existing ?? BLANK(selected ?? ''))

  // Reload the form whenever the selected opponent (or its saved report) changes.
  useEffect(() => {
    setForm(existing ?? BLANK(selected ?? ''))
  }, [selected, existing])

  const moments = selected ? oppositionMoments(data, selected) : []
  const h2h = selected ? headToHead(data, selected) : null
  const dirty = selected != null && JSON.stringify(form) !== JSON.stringify(existing ?? BLANK(selected))

  function save() {
    if (!selected) return
    const report: ScoutingReport = {
      ...form,
      id: form.id || existing?.id || genId('sc'),
      opponent: selected,
      updated: new Date().toISOString().slice(0, 10),
    }
    actions.saveScouting(report)
  }
  function addOpponent() {
    const name = newOpp.trim()
    if (!name) return
    setNewOpp('')
    setSelected(name)
  }

  const set = (patch: Partial<ScoutingReport>) => setForm((f) => ({ ...f, ...patch }))

  // group moments by category
  const byCat = new Map<TagCategory, typeof moments>()
  for (const m of moments) {
    const arr = byCat.get(m.tag.category) ?? []
    arr.push(m)
    byCat.set(m.tag.category, arr)
  }

  return (
    <div className="content">
      <div className="page-head">
        <div>
          <div className="page-title">Opposition Scouting</div>
          <div className="page-subtitle">Build a match dossier from your tagged footage and head-to-head history.</div>
        </div>
      </div>

      {opponents.length === 0 ? (
        <div className="card card-pad">
          <EmptyState icon="🔍" title="No opponents yet" hint="Add matches or video footage, then build a scouting report here." />
          <div className="row gap-sm center" style={{ justifyContent: 'center', marginTop: 10 }}>
            <input className="input" style={{ maxWidth: 240 }} placeholder="New opponent name" value={newOpp} onChange={(e) => setNewOpp(e.target.value)} />
            <button className="btn primary" onClick={addOpponent}>Add opponent</button>
          </div>
        </div>
      ) : (
        <div className="row" style={{ alignItems: 'flex-start' }}>
          {/* Opponent list */}
          <div style={{ width: 240, flexShrink: 0 }}>
            <div className="card">
              <div className="card-head"><h3>Opponents</h3></div>
              <div className="card-pad" style={{ display: 'flex', flexDirection: 'column', gap: 6, maxHeight: 420, overflowY: 'auto' }}>
                {opponents.map((o) => {
                  const hasReport = data.scouting.some((s) => s.opponent === o)
                  return (
                    <button key={o} className={`btn ${selected === o ? 'primary' : 'ghost'}`} style={{ justifyContent: 'space-between' }} onClick={() => setSelected(o)}>
                      <span style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{o}</span>
                      {hasReport && <span title="Report saved">📋</span>}
                    </button>
                  )
                })}
              </div>
              <div className="card-pad" style={{ borderTop: '1px solid var(--border-soft)' }}>
                <div className="field">
                  <label>Add opponent</label>
                  <div className="row gap-sm">
                    <input className="input" placeholder="Name" value={newOpp} onChange={(e) => setNewOpp(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && addOpponent()} />
                    <button className="btn" onClick={addOpponent}>+</button>
                  </div>
                </div>
              </div>
            </div>
          </div>

          {/* Report */}
          <div className="flex-1">
            {selected && h2h && (
              <>
                <div className="card card-pad" style={{ marginBottom: 16 }}>
                  <div className="row between center wrap" style={{ gap: 12 }}>
                    <div>
                      <h2 style={{ fontSize: 22 }}>{selected}</h2>
                      <div className="muted tiny" style={{ marginTop: 2 }}>
                        {h2h.played > 0 ? `Played ${h2h.played} · ${h2h.wins}W ${h2h.draws}D ${h2h.losses}L · ${h2h.goalsFor}–${h2h.goalsAgainst} goals` : 'No previous meetings recorded'}
                        {existing?.updated && ` · report updated ${fmtDate(existing.updated)}`}
                      </div>
                    </div>
                    <div className="row gap-sm center">
                      {h2h.matches.length > 0 && <FormPips form={h2h.matches.slice(0, 6).reverse().map((m) => resultOf(m.goalsFor, m.goalsAgainst))} />}
                      {existing && <button className="btn danger sm" onClick={() => { actions.removeScouting(existing.id); }}>Delete report</button>}
                      <button className="btn primary sm" onClick={save} disabled={!dirty}>{dirty ? '💾 Save report' : 'Saved'}</button>
                    </div>
                  </div>
                </div>

                <div className="grid grid-2" style={{ alignItems: 'start' }}>
                  <div className="col gap-lg">
                    <ReportField label="⚠️ Threats" hint="Dangerous players & patterns" value={form.threats} onChange={(v) => set({ threats: v })} />
                    <ReportField label="🎯 Weaknesses to exploit" value={form.weaknesses} onChange={(v) => set({ weaknesses: v })} />
                  </div>
                  <div className="col gap-lg">
                    <div className="card card-pad">
                      <div className="field">
                        <label>Likely shape</label>
                        <input className="input" placeholder="e.g. 4-2-3-1" value={form.formation} onChange={(e) => set({ formation: e.target.value })} />
                      </div>
                    </div>
                    <ReportField label="🚩 Their set pieces" value={form.setPieces} onChange={(v) => set({ setPieces: v })} />
                    <ReportField label="📋 Our game plan" value={form.gameplan} onChange={(v) => set({ gameplan: v })} />
                  </div>
                </div>

                {/* Footage */}
                <div className="card card-pad" style={{ marginTop: 16 }}>
                  <div className="card-head">
                    <h3>Opposition footage</h3>
                    <span className="sub">{moments.length} tagged moments</span>
                  </div>
                  {moments.length === 0 ? (
                    <p className="muted tiny" style={{ marginTop: 8 }}>
                      No opposition moments tagged for {selected} yet. On the{' '}
                      <Link className="link" to="/video">Video Analysis</Link> screen, tag moments as “Opposition”.
                    </p>
                  ) : (
                    <div className="col gap-lg" style={{ marginTop: 8 }}>
                      {[...byCat.entries()].map(([cat, list]) => {
                        const meta = tagMeta(cat)
                        return (
                          <div key={cat}>
                            <div className="row gap-sm center" style={{ marginBottom: 6 }}>
                              <span className="bold" style={{ color: meta.color }}>{meta.icon} {cat}</span>
                              <span className="badge tiny">{list.length}</span>
                            </div>
                            <div className="col" style={{ gap: 6 }}>
                              {list.map(({ video, tag }) => (
                                <Link key={tag.id} to={`/video/${video.id}?t=${Math.floor(tag.time)}`} className="panel" style={{ padding: 9, display: 'block', borderLeft: `3px solid ${meta.color}` }}>
                                  <div className="row between center" style={{ gap: 8 }}>
                                    <span className="badge mono" style={{ borderColor: meta.color, color: meta.color }}>▶ {fmtClock(tag.time)}</span>
                                    <span className="bold tiny" style={{ flex: 1 }}>{tag.title || '(untitled)'}</span>
                                    <span className="faint tiny">{video.title}</span>
                                  </div>
                                  {tag.note && <div className="tiny muted" style={{ marginTop: 3 }}>{tag.note}</div>}
                                </Link>
                              ))}
                            </div>
                          </div>
                        )
                      })}
                    </div>
                  )}
                </div>

                {/* Recent meetings */}
                {h2h.matches.length > 0 && (
                  <div className="card card-pad" style={{ marginTop: 16 }}>
                    <div className="card-head"><h3>Recent meetings</h3></div>
                    <div className="scroll-x" style={{ marginTop: 6 }}>
                      <table className="table">
                        <thead><tr><th>Date</th><th>Venue</th><th className="center-col">Result</th><th className="num">Poss</th><th className="num">xG</th></tr></thead>
                        <tbody>
                          {h2h.matches.map((m) => {
                            const r = resultOf(m.goalsFor, m.goalsAgainst)
                            return (
                              <tr key={m.id}>
                                <td className="mono tiny"><Link className="link" to={`/matches/${m.id}`}>{fmtDate(m.date)}</Link></td>
                                <td>{m.venue}</td>
                                <td className="center-col"><span className="form-pip" style={{ background: RESULT_COLOR[r], margin: '0 auto' }}>{m.goalsFor}-{m.goalsAgainst}</span></td>
                                <td className="num tnum">{m.possession}%</td>
                                <td className="num tnum">{m.xgFor}–{m.xgAgainst}</td>
                              </tr>
                            )
                          })}
                        </tbody>
                      </table>
                    </div>
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

function ReportField({ label, hint, value, onChange }: { label: string; hint?: string; value: string; onChange: (v: string) => void }) {
  return (
    <div className="card card-pad">
      <div className="card-head" style={{ padding: 0, border: 'none', marginBottom: 8 }}>
        <h3 style={{ fontSize: 14 }}>{label}</h3>
        {hint && <span className="sub">{hint}</span>}
      </div>
      <textarea className="textarea" rows={4} value={value} placeholder="Add your analysis…" onChange={(e) => onChange(e.target.value)} />
    </div>
  )
}
