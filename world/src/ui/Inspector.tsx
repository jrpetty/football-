import { useState } from 'react'
import { game } from '../game.ts'
import { mind } from '../ai/mind.ts'
import { ProfileSelect } from './Providers.tsx'
import { personality } from '../ai/prompts.ts'
import { BIOMES, FEATURES, ITEMS, TREE_FEATURES, type Item } from '../sim/data.ts'
import { BUILDINGS, TOOL_NAMES } from '../sim/buildings.ts'
import { ERAS, eraOf } from '../sim/tech.ts'
import { SKILL_NAMES, type Agent } from '../sim/types.ts'
import { YEAR } from '../sim/time.ts'
import { Avatar, Bar, Need, Section, ago, shortDate, useGameVersion } from './common.tsx'

export function Inspector() {
  useGameVersion()
  const r = game.renderer
  if (!r || !r.selection.kind || r.selection.kind === 'tribe') return null
  const close = () => {
    r.selection = { kind: null, id: 0 }
    r.follow = false
    game.emit()
  }
  return (
    <div className="inspector glass">
      <button className="close-x" onClick={close} aria-label="Close">✕</button>
      {r.selection.kind === 'agent' && <AgentView id={r.selection.id} />}
      {r.selection.kind === 'building' && <BuildingView id={r.selection.id} />}
      {r.selection.kind === 'tile' && <TileView i={r.selection.id} />}
    </div>
  )
}

function AgentView({ id }: { id: number }) {
  const w = game.world
  const a = w.agentById.get(id)
  const [q, setQ] = useState('')
  const [answer, setAnswer] = useState<{ q: string; a: string; err?: boolean } | null>(null)
  const [asking, setAsking] = useState(false)
  if (!a) return <div className="empty">They are gone.</div>
  const t = w.tribeById(a.tribeId)
  const isLeader = t?.leaderId === a.id
  const age = Math.floor(a.alive ? w.age(a) : (a.deathT - a.birth) / YEAR)
  const partner = a.partnerId ? w.agentById.get(a.partnerId) : undefined
  const isMind = mind.isMind(a)
  const rels = Object.entries(a.relations)
    .map(([oid, rel]) => ({ o: w.agentById.get(+oid), rel }))
    .filter((x) => x.o)
    .sort((p, q2) => Math.abs(q2.rel.affinity) * (q2.rel.familiarity + 10) - Math.abs(p.rel.affinity) * (p.rel.familiarity + 10))
    .slice(0, 8)

  const ask = async () => {
    if (!q.trim()) return
    setAsking(true)
    try {
      const text = await mind.interview(a, q.trim())
      setAnswer({ q: q.trim(), a: text })
      setQ('')
    } catch (e) {
      setAnswer({ q: q.trim(), a: (e as Error).message, err: true })
    } finally {
      setAsking(false)
    }
  }

  return (
    <>
      <div className="insp-head">
        <Avatar a={a} size="lg" />
        <div style={{ minWidth: 0 }}>
          <h2>{a.name} {isLeader && '👑'}</h2>
          <div className="sub">
            {a.sex === 'female' ? '♀' : '♂'} {age} years · <span style={{ color: t?.color }}>{t?.emblem} {t?.name}</span> · gen {a.generation}
          </div>
          <div style={{ marginTop: 4, display: 'flex', gap: 5, flexWrap: 'wrap' }}>
            {!a.alive && <span className="tag bad">Died of {a.deathCause}</span>}
            {a.alive && (isMind ? <span className="tag accent">{mind.mindOf(a) === 'genesis-mind' ? '🌱' : '✦'} {mind.label(mind.mindOf(a))}{a.aiPending ? ' · thinking…' : ''}</span> : <span className="tag">Instinct{mind.configured ? ' (no mind slot)' : ''}</span>)}
            {a.alive && <span className="tag">{a.feeling}</span>}
            {a.sick > 0 && <span className="tag warn">sick</span>}
          </div>
        </div>
      </div>
      {a.alive && (
        <div className="insp-actions">
          <button className={`icon-btn ${game.renderer?.follow ? 'primary' : ''}`} onClick={() => { if (game.renderer) { game.renderer.follow = !game.renderer.follow; game.emit() } }}>
            🎥 {game.renderer?.follow ? 'Following' : 'Follow'}
          </button>
          {!w.cfg.handsOff && <ProfileSelect className="icon-btn" value={a.model} onChange={(v) => { a.model = v; game.emit() }} emptyLabel="Mind: tribe default" />}
        </div>
      )}

      {a.thought && <div className="quote" style={{ marginTop: 12 }}>“{a.thought}”</div>}
      {a.speech && performance.now() < a.speech.until && <div className="answer" style={{ marginTop: 6 }}>💬 {a.speech.text}</div>}

      <div className="kv" style={{ marginTop: 10 }}>
        <span className="k">Doing</span><span>{a.alive ? (a.task?.label || 'idle') : '—'}</span>
        {a.plan.length > 0 && <><span className="k">Plan</span><span>{a.plan.map((p) => `${p.action}${p.target ? ' ' + p.target : ''}`).join(' → ')}</span></>}
        <span className="k">Goal</span><span>{a.goal || <span className="meta">—</span>}</span>
        {a.selfImage && <><span className="k">Sees self as</span><span>{a.selfImage}</span></>}
        {a.beliefs && <><span className="k">Believes</span><span>{a.beliefs}</span></>}
        <span className="k">Family</span>
        <span>
          {partner ? <a onClick={() => game.selectAgent(partner.id)} style={{ cursor: 'pointer', color: 'var(--accent)' }}>💞 {partner.name}</a> : 'single'}
          {a.children.length > 0 && <> · {a.children.length} child{a.children.length > 1 ? 'ren' : ''}</>}
        </span>
        <span className="k">Home</span><span>{a.homeId ? BUILDINGS[w.buildingById.get(a.homeId)?.type ?? 'hut'].name : 'none'}</span>
      </div>

      {a.alive && (
        <Section title="Ask them anything">
          <div className="meta" style={{ marginBottom: 4 }}>You listen from outside their world — they won't remember you asked.</div>
          <div className="ask">
            <input value={q} placeholder="What do you dream about?" onChange={(e) => setQ(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && ask()} disabled={asking} />
            <button className="icon-btn primary" onClick={ask} disabled={asking || !q.trim()}>{asking ? '…' : 'Ask'}</button>
          </div>
          {answer && (
            <div className="answer" style={answer.err ? { borderColor: 'rgba(255,107,107,0.4)' } : undefined}>
              <div className="meta" style={{ marginBottom: 4 }}>“{answer.q}”</div>
              {answer.a}
            </div>
          )}
        </Section>
      )}

      {a.alive && (
        <Section title="Body">
          <Need label="Health" value={a.health} />
          <Need label="Fed" value={a.hunger} invert />
          <Need label="Hydrated" value={a.thirst} invert />
          <Need label="Energy" value={a.energy} />
          <Need label="Warmth" value={a.warmth} />
          <Need label="Social" value={a.social} />
          <Need label="Mood" value={a.mood} />
        </Section>
      )}

      <Section title="Personality">
        <div className="meta" style={{ marginBottom: 6 }}>{personality(a)}</div>
        <div className="grid2">
          {Object.entries(a.traits).map(([k, v]) => (
            <div key={k}>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11 }}><span className="meta">{k}</span><span>{Math.round(v * 100)}</span></div>
              <Bar value={v * 100} color="var(--accent)" />
            </div>
          ))}
        </div>
      </Section>

      <Section title="Skills">
        <div className="grid2">
          {SKILL_NAMES.map((s) => (
            <div key={s}>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11 }}><span className="meta">{s}</span><span>{Math.round(a.skills[s])}</span></div>
              <Bar value={a.skills[s]} color="var(--accent-2)" />
            </div>
          ))}
        </div>
      </Section>

      <Section title="Carrying">
        <div className="inv">
          <span>🛠 {TOOL_NAMES[a.toolTier]}</span>
          <span>🧥 {a.clothing > 60 ? 'clothed' : a.clothing > 20 ? 'worn clothes' : 'no clothes'}</span>
          {Object.entries(a.inv).filter(([, n]) => (n ?? 0) > 0).map(([k, n]) => <span key={k}>{ITEMS[k as Item].icon} {k} {Math.floor(n ?? 0)}</span>)}
        </div>
      </Section>

      {a.reflections.length > 0 && (
        <Section title="Life lessons">
          {a.reflections.slice(-5).reverse().map((r, i) => <div key={i} className="mem">🌙 {r}</div>)}
        </Section>
      )}

      {rels.length > 0 && (
        <Section title="Relationships">
          {rels.map(({ o, rel }) => (
            <div key={o!.id} className="row" style={{ padding: '4px 6px' }} onClick={() => game.selectAgent(o!.id)}>
              <Avatar a={o!} />
              <div className="main">
                <div className="name" style={{ fontSize: 12 }}>{o!.name}{!o!.alive && ' †'}{o!.id === a.partnerId && ' 💞'}</div>
                <Bar value={(rel.affinity + 100) / 2} color={rel.affinity > 20 ? 'var(--good)' : rel.affinity < -20 ? 'var(--danger)' : 'var(--muted)'} />
              </div>
              <div className="right">{Math.round(rel.affinity)}</div>
            </div>
          ))}
        </Section>
      )}

      {a.journal.length > 0 && (
        <Section title="Inner journal">
          {a.journal.slice(-6).reverse().map((j, i) => (
            <div key={i} className="mem"><span className="ago">{shortDate(j.t)}</span><i>{j.text}</i>{j.say && <div>💬 {j.say}</div>}{j.reasoning && <details className="reasoning" open={i === 0}><summary>🧠 Reasoning</summary>{j.reasoning}</details>}</div>
          ))}
        </Section>
      )}

      <Section title="Memories">
        {a.memories.slice(-14).reverse().map((m, i) => (
          <div key={i} className="mem"><span className="ago">{ago(m.t)}</span>{m.imp >= 7 ? <b>{m.text}</b> : m.text}</div>
        ))}
        {a.memories.length === 0 && <div className="meta">No memories yet.</div>}
      </Section>

      <Section title="Life's work">
        <div className="kv">
          <span className="k">Gathered</span><span>{a.stats.gathered}</span>
          <span className="k">Built</span><span>{a.stats.built}</span>
          <span className="k">Crafted</span><span>{a.stats.crafted}</span>
          <span className="k">Research</span><span>{Math.round(a.stats.research)} pts</span>
          <span className="k">Words spoken</span><span>{a.stats.talks}</span>
          <span className="k">Children</span><span>{a.stats.kids}</span>
          <span className="k">Decisions</span><span>{a.aiDecisions} by Claude</span>
        </div>
      </Section>
    </>
  )
}

function BuildingView({ id }: { id: number }) {
  const w = game.world
  const b = w.buildingById.get(id)
  if (!b) return <div className="empty">This building is gone.</div>
  const def = BUILDINGS[b.type]
  const t = w.tribeById(b.tribeId)
  const owner = w.agentById.get(b.ownerId)
  const residents = w.agents.filter((a) => a.alive && a.homeId === b.id)
  return (
    <>
      <div className="insp-head">
        <div className="avatar lg" style={{ background: t?.color ?? '#888' }}>🏠</div>
        <div>
          <h2>{def.name}</h2>
          <div className="sub"><span style={{ color: t?.color }}>{t?.emblem} {t?.name}</span>{owner && <> · raised by {owner.name}</>}</div>
        </div>
      </div>
      <p className="meta" style={{ marginTop: 12 }}>{def.desc}</p>
      <div className="kv">
        <span className="k">Status</span><span>{b.done ? `Completed ${shortDate(b.builtT)}` : `Under construction — ${Math.round((b.progress / def.hours) * 100)}%`}</span>
        {!b.done && <><span className="k"></span><Bar value={b.progress} max={def.hours} color="var(--good)" /></>}
        {b.type === 'farm' && <><span className="k">Crop</span><span>{b.planted ? (b.crop >= 1 ? 'Ripe — ready to harvest' : `Growing ${Math.round(b.crop * 100)}%`) : 'Unplanted'}</span></>}
        {b.type === 'pasture' && <><span className="k">Herd</span><span>{b.crop.toFixed(1)} animals</span></>}
        {def.housing && <><span className="k">Housing</span><span>{residents.length} / {def.housing}</span></>}
        {def.storage && <><span className="k">Storage</span><span>+{def.storage}</span></>}
        {def.research && <><span className="k">Research</span><span>×{def.research}</span></>}
        <span className="k">Condition</span><span>{Math.round(b.hp)}%</span>
      </div>
      {residents.length > 0 && (
        <Section title="Residents">
          {residents.map((a) => (
            <div key={a.id} className="row" onClick={() => game.selectAgent(a.id)}>
              <Avatar a={a} />
              <div className="main"><div className="name">{a.name}</div><div className="sub">{a.task?.label || 'idle'}</div></div>
            </div>
          ))}
        </Section>
      )}
    </>
  )
}

function TileView({ i }: { i: number }) {
  const w = game.world
  const x = i % w.W, y = Math.floor(i / w.W)
  const b = BIOMES[w.biome[i]]
  const f = FEATURES[w.feature[i]]
  const explorers = w.tribes.filter((t) => t.alive && w.isExplored(t.id, i))
  const temp = w.feltTemp(x + 0.5, y + 0.5)
  return (
    <>
      <div className="insp-head">
        <div className="avatar lg" style={{ background: `rgb(${b.color.join(',')})` }}>🗺️</div>
        <div>
          <h2>{b.name}</h2>
          <div className="sub">Tile {x}, {y}</div>
        </div>
      </div>
      <div className="kv" style={{ marginTop: 12 }}>
        <span className="k">Elevation</span><span>{w.elev[i] < 0 ? `${Math.round(-w.elev[i] * 200)} m deep` : `${Math.round(w.elev[i] * 3000)} m`}</span>
        <span className="k">Feels like</span><span>{temp < 0.15 ? 'freezing' : temp < 0.32 ? 'cold' : temp < 0.55 ? 'mild' : temp < 0.75 ? 'warm' : 'hot'}</span>
        <span className="k">Rainfall</span><span>{Math.round(w.moist[i] * 100)}%</span>
        <span className="k">Fertility</span><span>{Math.round(w.fertility[i] * 100)}%</span>
        <span className="k">Water</span><span>{b.water ? (b.fresh ? 'fresh' : 'salt — undrinkable') : '—'}</span>
        {f.name && <><span className="k">Feature</span><span>{f.name}{f.item ? ` — ${w.amount[i]} ${f.item}${TREE_FEATURES.has(w.feature[i]) ? '' : ''}` : ''}</span></>}
        <span className="k">Known to</span><span>{explorers.length ? explorers.map((t) => t.name).join(', ') : 'nobody yet'}</span>
      </div>
    </>
  )
}

export function eraColor(tribeId: number): string {
  return ERAS[eraOf(game.world.techs(tribeId))].color
}

export type { Agent }
