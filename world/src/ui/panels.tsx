import { met, status as dipStatus, strength as dipStrength, militaryTier, type Status } from '../sim/diplomacy.ts'
import { useMemo, useState } from 'react'
import { game } from '../game.ts'
import { mind } from '../ai/mind.ts'
import { ERAS, TECHS, TECH_BY_ID, availableTechs, eraOf } from '../sim/tech.ts'
import { BUILDINGS } from '../sim/buildings.ts'
import { ITEMS, invTotal, type Item } from '../sim/data.ts'
import { formatDate, YEAR } from '../sim/time.ts'
import type { ChronicleEntry } from '../sim/types.ts'
import { Avatar, Bar, LineChart, Section, ago, shortDate, useGameVersion, useMindVersion } from './common.tsx'

// ------------------------------------------------------------------ People

export function PeoplePanel() {
  useGameVersion()
  const w = game.world
  const [q, setQ] = useState('')
  const [filter, setFilter] = useState<'all' | 'minds' | 'leaders' | 'children' | 'fallen'>('all')
  const sel = game.renderer?.selection
  const leaders = new Set(w.tribes.map((t) => t.leaderId))
  let list = w.agents.filter((a) => (filter === 'fallen' ? !a.alive : a.alive))
  if (filter === 'minds') list = list.filter((a) => mind.isMind(a))
  if (filter === 'leaders') list = list.filter((a) => leaders.has(a.id))
  if (filter === 'children') list = list.filter((a) => !w.isAdult(a))
  if (q) list = list.filter((a) => a.name.toLowerCase().includes(q.toLowerCase()) || (w.tribeById(a.tribeId)?.name.toLowerCase().includes(q.toLowerCase()) ?? false))
  list.sort((a, b) => (filter === 'fallen' ? b.deathT - a.deathT : Number(leaders.has(b.id)) - Number(leaders.has(a.id)) || Number(b.aiPending) - Number(a.aiPending) || a.tribeId - b.tribeId || a.birth - b.birth))
  return (
    <>
      <input className="search" placeholder="Search people or tribes…" value={q} onChange={(e) => setQ(e.target.value)} />
      <div className="filters">
        {(['all', 'minds', 'leaders', 'children', 'fallen'] as const).map((f) => (
          <button key={f} className={`chip ${filter === f ? 'on' : ''}`} onClick={() => setFilter(f)}>
            {f === 'all' ? 'Living' : f === 'minds' ? '✦ Claude minds' : f[0].toUpperCase() + f.slice(1)}
          </button>
        ))}
      </div>
      {list.slice(0, 200).map((a) => {
        const t = w.tribeById(a.tribeId)
        return (
          <div key={a.id} className={`row ${sel?.kind === 'agent' && sel.id === a.id ? 'sel' : ''}`} onClick={() => game.selectAgent(a.id)}>
            <Avatar a={a} />
            <div className="main">
              <div className="name">
                {a.name}
                {leaders.has(a.id) && <span title="Leader">👑</span>}
                {a.sick > 0 && a.alive && <span title="Sick">🤒</span>}
              </div>
              <div className="sub">
                {a.alive ? (a.aiPending ? '✦ thinking…' : a.task?.label || 'idle') : `died of ${a.deathCause}, ${shortDate(a.deathT)}`}
              </div>
            </div>
            <div className="right">
              <div style={{ color: t?.color }}>{t?.name}</div>
              <div>{Math.floor(w.age(a) - (a.alive ? 0 : (w.time - a.deathT) / YEAR))} yrs</div>
            </div>
          </div>
        )
      })}
      {list.length === 0 && (
        <div className="empty">
          <div className="big">🌾</div>
          {filter === 'minds' ? 'No Claude minds yet — add your API key in Settings.' : 'Nobody here.'}
        </div>
      )}
    </>
  )
}

// ------------------------------------------------------------------ Tribes

const STATUS_LOOK: Record<Status, [string, string]> = {
  war: ['⚔️ At war', '#ff6b6b'], alliance: ['🤝 Allies', '#4ade80'], trade: ['🐫 Trading', '#fbbf24'], tribute: ['💰 Tribute', '#c084fc'],
  friendly: ['🙂 Friendly', '#86efac'], neutral: ['😐 Neutral', '#cbd5e1'], tense: ['😠 Tense', '#fb923c'], hostile: ['😡 Hostile', '#f87171'], unknown: ['', ''],
}

/** How one people stands with every other it has met: opinion, pacts, strength and any war. */
function Relations({ tribe }: { tribe: number }) {
  const w = game.world
  const mine = dipStrength(w, tribe)
  const rows = w.tribes.filter((o) => o.alive && o.id !== tribe && met(w, tribe, o.id))
  const wars = w.dip.wars.filter((x) => !x.ended && (x.a === tribe || x.b === tribe))
  if (!rows.length) return <div className="meta" style={{ marginTop: 8 }}>Has not met another people yet.</div>
  return (
    <div style={{ marginTop: 10 }} onClick={(e) => e.stopPropagation()}>
      <div className="meta">Relations · fights with {militaryTier(w.techs(tribe)).name}</div>
      {rows.map((o) => {
        const st = dipStatus(w, tribe, o.id)
        const r = Math.round(w.tribeRelation(tribe, o.id))
        const ratio = mine / Math.max(1, dipStrength(w, o.id))
        const [label, col] = STATUS_LOOK[st]
        const war = wars.find((x) => x.a === o.id || x.b === o.id)
        const score = war ? (war.a === tribe ? war.score : -war.score) : 0
        const trib = w.dip.pacts.find((p) => p.kind === 'tribute' && ((p.a === tribe && p.b === o.id) || (p.b === tribe && p.a === o.id)))
        return (
          <div key={o.id} className="rel-row">
            <span style={{ color: o.color, fontWeight: 700 }}>{o.emblem} {o.name}</span>
            <span className="rel-status" style={{ color: col, borderColor: col }}>{label}</span>
            <div className="rel-bar" title={`Opinion ${r}`}><div style={{ left: r < 0 ? `${50 + r / 2}%` : '50%', width: `${Math.abs(r) / 2}%`, background: r < 0 ? '#f87171' : '#4ade80' }} /></div>
            <span className="meta" title="Military strength compared with theirs">{ratio > 1.5 ? '💪 far stronger' : ratio > 1.1 ? '💪 stronger' : ratio > 0.9 ? '⚖️ even' : ratio > 0.6 ? '⚠️ weaker' : '⚠️ far weaker'}</span>
            {war && <div className="meta rel-war">{war.reason} · {war.battles} battles · dead {war.dead[tribe] ?? 0} vs {war.dead[o.id] ?? 0} · {score > 20 ? 'winning' : score < -20 ? 'losing' : 'undecided'}<div className="rel-bar war"><div style={{ left: '50%', width: `${Math.abs(score) / 2}%`, transform: score < 0 ? 'translateX(-100%)' : undefined, background: score < 0 ? '#f87171' : '#4ade80' }} /></div></div>}
            {trib && <div className="meta">{trib.payer === tribe ? 'Pays them tribute' : 'Receives their tribute'} until {formatDate(trib.until)}</div>}
          </div>
        )
      })}
    </div>
  )
}

export function TribesPanel() {
  useGameVersion()
  const w = game.world
  const tribes = [...w.tribes].sort((a, b) => Number(b.alive) - Number(a.alive) || w.members(b.id).length - w.members(a.id).length)
  return (
    <>
      {tribes.map((t) => {
        const mem = w.members(t.id)
        const leader = w.agentById.get(t.leaderId)
        const known = w.techs(t.id)
        const era = eraOf(known)
        const rs = t.research ? TECH_BY_ID[t.research.tech] : null
        const blds = w.tribeBuildings(t.id).filter((b) => b.type !== 'farm' && b.type !== 'pasture').length
        const farms = w.tribeBuildings(t.id, 'farm').length
        const stores = Object.entries(t.storage).filter(([, n]) => (n ?? 0) >= 1).sort((a, b) => (b[1] ?? 0) - (a[1] ?? 0)).slice(0, 10)
        return (
          <div key={t.id} className="card" style={{ opacity: t.alive ? 1 : 0.5, borderLeft: `3px solid ${t.color}`, cursor: 'pointer' }} onClick={() => game.focusTribe(t.id)}>
            <h3>
              <span style={{ fontSize: 18 }}>{t.emblem}</span>
              <span style={{ color: t.color }}>{t.name}</span>
              <span className="tag" style={{ marginLeft: 'auto', color: ERAS[era].color }}>{ERAS[era].name}</span>
            </h3>
            <div className="meta" style={{ marginBottom: 8 }}>
              {t.alive ? `${mem.length} people · led by ${leader?.alive ? leader.name : '—'} · founded ${formatDate(t.foundedT)}` : 'Extinct'}
              {t.launched && ' · 🚀 reached space'}
            </div>
            {t.values && <div className="quote">“{t.values}”</div>}
            {t.beliefs && <div className="kv"><span className="k">Beliefs</span><span>{t.beliefs}</span></div>}
            {t.laws.length > 0 && (
              <div style={{ marginTop: 6 }}>
                <div className="meta">Laws</div>
                <ol style={{ margin: '2px 0 0', paddingLeft: 18 }}>
                  {t.laws.map((l, i) => <li key={i} style={{ fontSize: 12 }}>{l}</li>)}
                </ol>
              </div>
            )}
            <div className="kv" style={{ marginTop: 8 }}>
              <span className="k">Knowledge</span><span>{known.size} technologies</span>
              <span className="k">Researching</span>
              <span>{rs ? <>{rs.name} <span className="meta">({Math.round((w.techProgress(t, rs.id) / rs.cost) * 100)}%)</span></> : '—'}</span>
              <span className="k">Buildings</span><span>{blds} + {farms} fields</span>
              <span className="k">Stores</span><span>{Math.floor(invTotal(t.storage))} / {w.storageCap(t.id)}</span>
              <span className="k">Mind</span><span>{t.model ? `✦ ${mind.label(t.model)}` : 'default mind'}</span>
            </div>
            {rs && <div style={{ marginTop: 6 }}><Bar value={w.techProgress(t, rs.id)} max={rs.cost} color={t.color} /></div>}
            {stores.length > 0 && (
              <div className="inv" style={{ marginTop: 8 }}>
                {stores.map(([k, n]) => <span key={k}>{ITEMS[k as Item].icon} {Math.floor(n ?? 0)}</span>)}
              </div>
            )}
            {t.alive && <Relations tribe={t.id} />}
            {t.works.length > 0 && (
              <details style={{ marginTop: 8 }} onClick={(e) => e.stopPropagation()}>
                <summary className="meta" style={{ cursor: 'pointer' }}>📜 {t.works.length} written works</summary>
                {t.works.slice().reverse().map((wk, i) => (
                  <div key={i} style={{ marginTop: 8 }}>
                    <b>{wk.title}</b> <span className="meta">by {wk.author}, {shortDate(wk.t)}</span>
                    {wk.text && <div className="quote" style={{ whiteSpace: 'pre-wrap' }}>{wk.text}</div>}
                  </div>
                ))}
              </details>
            )}
          </div>
        )
      })}
    </>
  )
}

// ------------------------------------------------------------------ Minds feed

export function MindsPanel({ onSettings }: { onSettings: () => void }) {
  useMindVersion()
  useGameVersion()
  const [kind, setKind] = useState<'all' | 'speech' | 'reflection'>('all')
  let items = mind.feed.slice().reverse()
  if (kind === 'speech') items = items.filter((f) => f.say)
  if (kind === 'reflection') items = items.filter((f) => f.kind === 'reflection')
  const st = mind.stats
  return (
    <>
      {!mind.configured && (
        <div className="card" style={{ borderColor: 'rgba(155,140,255,0.4)' }}>
          <h3>✦ Give them minds</h3>
          <div className="meta" style={{ marginBottom: 10 }}>
            Right now everyone runs on instinct. Add a Claude key, a DeepSeek / Qwen / Kimi / GLM key, or point it at a model running on your own computer — and each person gets their own mind.
          </div>
          <button className="btn primary" onClick={onSettings}>Connect a mind</button>
        </div>
      )}
      {mind.configured && (
        <div className="card">
          <div className="kv">
            <span className="k">Status</span>
            <span>{st.pausedReason ? <span style={{ color: 'var(--warn)' }}>{st.pausedReason}</span> : mind.ready ? <span style={{ color: 'var(--good)' }}>Live — {st.inFlight} thinking now</span> : 'Paused'}</span>
            <span className="k">Thoughts</span><span>{st.ok} ({st.errors} failed)</span>
            <span className="k">Tokens</span><span>{fmtK(st.usage.input + st.usage.cacheRead + st.usage.cacheWrite)} in · {fmtK(st.usage.output)} out · {fmtK(st.usage.cacheRead)} cached</span>
            <span className="k">Spend</span><span>${st.cost.toFixed(3)} of ${mind.settings.budgetUsd.toFixed(2)} budget</span>
          </div>
          {st.lastError && Date.now() - st.lastErrorT < 60000 && <div className="note warn" style={{ marginTop: 8 }}>{st.lastError}</div>}
        </div>
      )}
      <div className="filters">
        {(['all', 'speech', 'reflection'] as const).map((k) => (
          <button key={k} className={`chip ${kind === k ? 'on' : ''}`} onClick={() => setKind(k)}>
            {k === 'all' ? 'All thoughts' : k === 'speech' ? '💬 Spoken' : '🌙 Reflections'}
          </button>
        ))}
      </div>
      {items.slice(0, 80).map((f) => (
        <div key={f.id} className={`feed-item ${f.kind === 'error' ? 'error' : ''}`} onClick={() => game.selectAgent(f.agentId)}>
          <div className="head">
            <span style={{ width: 10, height: 10, borderRadius: 5, background: f.color, display: 'inline-block' }} />
            <span className="who">{f.name}</span>
            <span className="meta">{f.tribe}</span>
            <span className="when">{f.kind === 'reflection' ? '🌙 ' : ''}{shortDate(f.t)}</span>
          </div>
          {f.kind === 'error' ? (
            <div className="meta">⚠ {f.thought}</div>
          ) : (
            <>
              <div className="thought">“{f.thought}”</div>
              {f.say && <div className="say">💬 {f.sayTo && !/^(everyone|)$/i.test(f.sayTo) ? <b>to {f.sayTo}: </b> : null}{f.say}</div>}
              {f.plan && <div className="plan">→ {f.plan}</div>}
            </>
          )}
          <div className="meta" style={{ marginTop: 4, fontSize: 10.5 }}>{f.model}</div>
        </div>
      ))}
      {items.length === 0 && mind.configured && <div className="empty"><div className="big">✦</div>Thoughts will appear here as people think.</div>}
      {(!mind.ready || items.length < 6) && <Overheard />}
    </>
  )
}

function Overheard() {
  const w = game.world
  const lines = w.chronicle.filter((e) => e.kind === 'speech').slice(-30).reverse()
  if (!lines.length) return null
  return (
    <Section title={mind.ready ? 'Overheard' : 'Overheard (instinct chatter)'}>
      <div className="log">
        {lines.map((e, i) => (
          <div key={i} className="log-item clickable" onClick={() => e.agentId && game.selectAgent(e.agentId)}>
            <span className="t">{shortDate(e.t)}</span>
            <span>{e.text}</span>
          </div>
        ))}
      </div>
    </Section>
  )
}

function fmtK(n: number): string {
  return n > 1e6 ? `${(n / 1e6).toFixed(2)}M` : n > 1e3 ? `${(n / 1e3).toFixed(1)}k` : String(n)
}

// ------------------------------------------------------------------ Chronicle

const KINDS: { k: ChronicleEntry['kind'] | 'all'; label: string }[] = [
  { k: 'all', label: 'All' },
  { k: 'milestone', label: '🏛️ Milestones' },
  { k: 'tech', label: '💡 Discoveries' },
  { k: 'tribe', label: '🚩 Politics' },
  { k: 'social', label: '💞 Social' },
  { k: 'birth', label: '👶 Births' },
  { k: 'death', label: '🕯️ Deaths' },
  { k: 'speech', label: '💬 Speech' },
  { k: 'build', label: '🏗️ Building' },
  { k: 'event', label: '🌋 Events' },
]

export function ChroniclePanel() {
  useGameVersion()
  const w = game.world
  const [kind, setKind] = useState<ChronicleEntry['kind'] | 'all' | 'history'>('history')
  const [writing, setWriting] = useState(false)
  const entries = kind === 'all' ? w.chronicle.filter((e) => e.kind !== 'speech') : w.chronicle.filter((e) => e.kind === kind)
  const year = Math.floor(w.time / YEAR) + 1
  return (
    <>
      <div className="filters">
        <button className={`chip ${kind === 'history' ? 'on' : ''}`} onClick={() => setKind('history')}>📖 History</button>
        {KINDS.map((k) => (
          <button key={k.k} className={`chip ${kind === k.k ? 'on' : ''}`} onClick={() => setKind(k.k)}>{k.label}</button>
        ))}
      </div>
      {kind === 'history' ? (
        <>
          <div className="card">
            <div className="meta" style={{ marginBottom: 8 }}>
              A Claude historian writes a chapter at the end of every year from what happened. {mind.settings.chronicler ? '' : '(Turned off in Settings.)'}
            </div>
            <button
              className="btn"
              disabled={writing || !mind.ready}
              onClick={async () => {
                setWriting(true)
                await mind.writeChronicle(w, year)
                setWriting(false)
              }}
            >
              {writing ? 'Writing…' : `✍️ Write the story of year ${year} so far`}
            </button>
          </div>
          {w.chapters.slice().reverse().map((c, i) => (
            <div key={i} className="chapter">
              <h4>{c.title}</h4>
              {c.text.split(/\n+/).map((p, j) => <p key={j}>{p}</p>)}
            </div>
          ))}
          {w.chapters.length === 0 && <div className="empty"><div className="big">📖</div>No chapters yet. History is still being made.</div>}
        </>
      ) : (
        <div className="log">
          {entries.slice(-300).reverse().map((e, i) => (
            <div key={i} className={`log-item ${e.agentId ? 'clickable' : ''}`} onClick={() => e.agentId && game.selectAgent(e.agentId)}>
              <span className="t">{shortDate(e.t)}</span>
              <span className="ic">{e.icon}</span>
              <span>{e.text}</span>
            </div>
          ))}
          {entries.length === 0 && <div className="empty">Nothing yet.</div>}
        </div>
      )}
    </>
  )
}

// ------------------------------------------------------------------ Knowledge

export function TechPanel() {
  useGameVersion()
  const w = game.world
  const alive = w.tribes.filter((t) => t.alive)
  const [tid, setTid] = useState<number>(alive[0]?.id ?? 0)
  const t = w.tribeById(tid) ?? alive[0]
  if (!t) return <div className="empty">No tribes remain.</div>
  const known = w.techs(t.id)
  const avail = new Set(availableTechs(known).map((x) => x.id))
  const era = ERAS[eraOf(known)]
  return (
    <>
      <div className="filters">
        {alive.map((x) => (
          <button key={x.id} className={`chip ${x.id === t.id ? 'on' : ''}`} onClick={() => setTid(x.id)}>
            {x.emblem} {x.name}
          </button>
        ))}
      </div>
      <div className="card">
        <div className="kv">
          <span className="k">Era</span><span style={{ color: era.color, fontWeight: 700 }}>{era.name}</span>
          <span className="k">Known</span><span>{known.size} of {TECHS.length}</span>
          <span className="k">Focus</span><span>{t.research ? `${TECH_BY_ID[t.research.tech]?.name} (${Math.round((w.techProgress(t, t.research.tech) / (TECH_BY_ID[t.research.tech]?.cost ?? 1)) * 100)}%)` : 'nothing'}</span>
          <span className="k">Memory</span><span>{known.has('writing') ? '📜 written down — safe' : '🗣️ oral tradition — fragile if the tribe dwindles'}</span>
        </div>
        <div className="meta" style={{ marginTop: 8 }}>Ideas grow from practice as well as study: the % shows how close each idea is. Geography speeds some ideas and slows others.</div>
        <div style={{ marginTop: 8 }}><Bar value={known.size} max={TECHS.length} color={era.color} /></div>
      </div>
      {ERAS.map((e, i) => {
        const techs = TECHS.filter((x) => x.era === i)
        return (
          <div key={e.name} className="era-block">
            <div className="era-head"><span style={{ width: 10, height: 10, borderRadius: 3, background: e.color, display: 'inline-block' }} />{e.name}</div>
            <div className="techs">
              {techs.map((x) => {
                const cur = t.research?.tech === x.id
                const pct = Math.min(99, Math.round((w.techProgress(t, x.id) / x.cost) * 100))
                const lost = t.lost.includes(x.id) && !known.has(x.id)
                const cls = known.has(x.id) ? 'known' : cur ? 'current' : avail.has(x.id) ? 'avail' : ''
                return (
                  <span key={x.id} className={`tech ${cls}`} style={known.has(x.id) ? { background: e.color } : undefined} title={`${x.name} — ${x.desc}\nCost ${x.cost}${x.req.length ? `\nRequires: ${x.req.map((r) => TECH_BY_ID[r]?.name).join(', ')}` : ''}`}>
                    {lost && '🌫️ '}{x.name}
                    {!known.has(x.id) && pct > 0 && ` ${pct}%`}
                  </span>
                )
              })}
            </div>
          </div>
        )
      })}
    </>
  )
}

// ------------------------------------------------------------------ Stats

export function StatsPanel() {
  useGameVersion()
  const w = game.world
  const s = w.samples
  const step = Math.max(1, Math.floor(s.length / 120))
  const pick = <T,>(arr: T[]) => arr.filter((_, i) => i % step === 0 || i === arr.length - 1)
  const ss = pick(s)
  const living = w.living
  const adults = living.filter((a) => w.isAdult(a))
  const counts = useMemo(() => {
    const m = new Map<string, number>()
    for (const b of w.buildings) if (b.done) m.set(b.type, (m.get(b.type) ?? 0) + 1)
    return [...m.entries()].sort((a, b) => b[1] - a[1])
  }, [w.buildings.length, game.version]) // eslint-disable-line react-hooks/exhaustive-deps
  const gens = new Map<number, number>()
  for (const a of living) gens.set(a.generation, (gens.get(a.generation) ?? 0) + 1)
  return (
    <>
      <div className="cols" style={{ marginBottom: 10 }}>
        <div className="card" style={{ marginBottom: 0 }}><div className="meta">Population</div><div className="big-num">{living.length}</div><div className="meta">{adults.length} adults · {living.length - adults.length} children</div></div>
        <div className="card" style={{ marginBottom: 0 }}><div className="meta">Ever lived</div><div className="big-num">{w.agents.length}</div><div className="meta">{w.agents.length - living.length} have died</div></div>
      </div>
      <Section title="Population">
        <LineChart series={[{ label: 'People', color: '#5ad1c4', values: ss.map((x) => x.pop) }, { label: 'Avg mood', color: '#ffcf6b', values: ss.map((x) => x.avgMood) }]} />
      </Section>
      <Section title="Progress">
        <LineChart series={[{ label: 'Most technologies', color: '#9b8cff', values: ss.map((x) => x.techs) }, { label: 'Buildings', color: '#ff8f6b', values: ss.map((x) => x.buildings) }]} />
      </Section>
      <Section title="Food in stores">
        <LineChart series={[{ label: 'Food units', color: '#7ee0a0', values: ss.map((x) => x.food) }]} height={90} />
      </Section>
      <Section title="By tribe">
        {w.tribes.filter((t) => t.alive).map((t) => {
          const n = w.members(t.id).length
          return (
            <div key={t.id} style={{ marginBottom: 8 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12 }}><span>{t.emblem} {t.name}</span><span className="meta">{n} people · {t.techs.length} techs</span></div>
              <Bar value={n} max={Math.max(1, living.length)} color={t.color} />
            </div>
          )
        })}
      </Section>
      <Section title="Generations alive">
        <div className="inv">{[...gens.entries()].sort((a, b) => a[0] - b[0]).map(([g, n]) => <span key={g}>Gen {g}: {n}</span>)}</div>
      </Section>
      <Section title="Structures">
        <div className="inv">{counts.map(([k, n]) => <span key={k}>{BUILDINGS[k as keyof typeof BUILDINGS].name} ×{n}</span>)}</div>
      </Section>
      <Section title="Simulation">
        <div className="kv">
          <span className="k">Frame rate</span><span>{Math.round(game.fps)} fps</span>
          <span className="k">Sim cost</span><span>{game.simMsPerFrame.toFixed(1)} ms / frame</span>
          <span className="k">World</span><span>{w.W}×{w.H} tiles · seed {w.cfg.seed}</span>
          <span className="k">Started</span><span>{ago(0)}</span>
        </div>
      </Section>
    </>
  )
}
