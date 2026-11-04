import { useEffect, useMemo, useReducer, useRef, useState } from 'react'
import { SCENARIOS, CATEGORIES } from './engine/scenarios.ts'
import { makePerson, describeTraits } from './engine/cast.ts'
import { Run } from './engine/engine.ts'
import type { Person, Scenario } from './engine/types.ts'
import { minds } from './ai/host.ts'
import { VENDORS, newProfile, vendorOf, testProfile, listModels, PROXY_URL, type MindProfile } from './ai/providers.ts'
import { MODELS } from './ai/client.ts'
import { Stage, Portrait, COLORS } from './ui/Stage.tsx'

type Screen = 'menu' | 'setup' | 'play'

function useMinds() {
  const [, bump] = useReducer((x: number) => x + 1, 0)
  useEffect(() => {
    minds.listeners.add(bump)
    return () => { minds.listeners.delete(bump) }
  }, [])
}

function newCast(sc: Scenario, keep?: Person[]): Person[] {
  const taken = new Set<string>()
  const start = sc.places[Math.floor(Math.random() * sc.places.length)].id
  return sc.roles.map((r, i) => {
    const p = makePerson(i + 1, r, start, taken)
    p.place = Math.random() < 0.6 ? start : sc.places[Math.floor(Math.random() * sc.places.length)].id
    p.model = keep?.[i]?.model ?? (minds.usable(minds.s.model) ? minds.s.model : '')
    return p
  })
}

export default function App() {
  useMinds()
  const [screen, setScreen] = useState<Screen>('menu')
  const [sc, setSc] = useState<Scenario>(SCENARIOS[0])
  const [cast, setCast] = useState<Person[]>([])
  const [run, setRun] = useState<Run | null>(null)
  const [settings, setSettings] = useState(false)
  return (
    <div className="app">
      {screen === 'menu' && <Menu onPick={(s) => { setSc(s); setCast(newCast(s)); setScreen('setup') }} onSettings={() => setSettings(true)} />}
      {screen === 'setup' && <Setup sc={sc} cast={cast} setCast={setCast} onBack={() => setScreen('menu')} onSettings={() => setSettings(true)}
        onStart={() => { setRun(new Run(sc, cast.map((p) => structuredClone(p)))); setScreen('play') }} />}
      {screen === 'play' && run && <Play run={run} onExit={() => setScreen('menu')}
        onAgain={(same) => { const c = same ? cast.map((p) => ({ ...structuredClone(p), memory: [], opinions: {} })) : newCast(sc, cast); setCast(c); setRun(new Run(sc, c.map((p) => structuredClone(p)))) }}
        onSettings={() => setSettings(true)} />}
      {settings && <Settings onClose={() => setSettings(false)} />}
    </div>
  )
}

// ------------------------------------------------------------------ menu

function Menu({ onPick, onSettings }: { onPick: (s: Scenario) => void; onSettings: () => void }) {
  const [cat, setCat] = useState<string>('All')
  const list = SCENARIOS.filter((s) => cat === 'All' || s.category === cat)
  const ai = minds.usable(minds.s.model)
  const web = typeof location !== 'undefined' && location.protocol.startsWith('http')
  return (
    <div className="menu">
      <header className="hero">
        <div className="hero-dots"><i style={{ background: COLORS[0] }} /><i style={{ background: COLORS[1] }} /><i style={{ background: COLORS[2] }} /><i style={{ background: COLORS[3] }} /></div>
        <h1>Quartet</h1>
        <p>Four independent AI minds. One real-world situation. Nobody is scripted — each of them only knows what their character knows, wants what they want, and hides what they hide.</p>
        <div className="hero-actions">
          <button className="btn primary big" onClick={() => onPick(list[Math.floor(Math.random() * list.length)])}>🎲 Surprise me</button>
          <button className={`btn big ${ai ? '' : 'warn'}`} onClick={onSettings}>✦ {ai ? `Minds: ${minds.label(minds.s.model)}` : 'Connect AI minds'}</button>
          {web && <a className="btn big" href="../world/">🌍 Genesis</a>}
        </div>
      </header>
      <div className="cats">
        {['All', ...CATEGORIES].map((c) => <button key={c} className={`chip ${cat === c ? 'on' : ''}`} onClick={() => setCat(c)}>{c}</button>)}
      </div>
      <div className="grid">
        {list.map((s) => (
          <button key={s.id} className="scard" onClick={() => onPick(s)} style={{ ['--a' as string]: s.theme.accent, background: `linear-gradient(160deg, ${s.theme.sky[0]}, ${s.theme.sky[1]} 60%, ${s.theme.ground})` }}>
            <div className="scard-emoji">{s.emoji}</div>
            <div className="scard-body">
              <span className="scard-cat">{s.category}</span>
              <h3>{s.title}</h3>
              <p>{s.tagline}</p>
              <div className="scard-roles">{s.roles.map((r) => r.title).join(' · ')}</div>
            </div>
          </button>
        ))}
      </div>
      <footer className="foot">Works with Claude, DeepSeek, Qwen, Kimi, GLM, and models on your own computer (Ollama, LM Studio). Without a mind, characters act on instinct.</footer>
    </div>
  )
}

// ------------------------------------------------------------------ setup

function Setup({ sc, cast, setCast, onBack, onStart, onSettings }: { sc: Scenario; cast: Person[]; setCast: (c: Person[]) => void; onBack: () => void; onStart: () => void; onSettings: () => void }) {
  const reroll = (i: number) => {
    const taken = new Set(cast.filter((_, k) => k !== i).map((p) => p.name))
    const p = makePerson(i + 1, sc.roles[i], cast[i].place, taken)
    p.model = cast[i].model
    setCast(cast.map((c, k) => (k === i ? p : c)))
  }
  const setModel = (i: number, m: string) => setCast(cast.map((c, k) => (k === i ? { ...c, model: m } : c)))
  const anyAI = cast.some((p) => p.model && minds.usable(p.model))
  const est = cast.reduce((s, p) => s + (p.model ? estimate(p.model) : 0), 0) * sc.beats + (minds.s.director ? estimate(minds.s.director) * sc.beats : 0)
  return (
    <div className="setup">
      <div className="setup-head" style={{ background: `linear-gradient(120deg, ${sc.theme.sky[0]}, ${sc.theme.sky[1]})` }}>
        <button className="btn ghost" onClick={onBack}>← Scenarios</button>
        <div className="setup-title"><span>{sc.emoji}</span><div><h1>{sc.title}</h1><p>{sc.premise}</p></div></div>
        <div className="ahead">
          {(sc.acts ?? []).map((a, k) => <div key={k} className="ahead-act"><small>Act {['I', 'II', 'III'][k]}</small><b>{a}</b></div>)}
          <div className="ahead-act"><small>Hard choices</small><b>⚖️ {sc.dilemmas?.length ?? 0} group votes</b></div>
          <div className="ahead-act"><small>Stakes</small><b>{sc.stats.map((s) => s.icon).join(' ')}{sc.win ? ` · goal: ${sc.stats.find((s) => s.id === sc.win!.stat)?.label}` : ''}</b></div>
        </div>
      </div>
      <div className="cast">
        {cast.map((p, i) => (
          <div key={p.id} className="pcard" style={{ ['--c' as string]: COLORS[i] }}>
            <div className="pcard-top">
              <Portrait p={p} size={84} color={COLORS[i]} />
              <div>
                <h3>{p.name}, {p.age}</h3>
                <div className="role">{p.role.title}</div>
                <button className="link" onClick={() => reroll(i)}>↻ someone else</button>
              </div>
            </div>
            <p className="brief">{p.role.brief}</p>
            <div className="kv">
              <span>Temperament</span><span>{describeTraits(p.traits)}</span>
              <span>Quirk</span><span>{p.quirk}</span>
              <span>Life</span><span>{p.backstory}</span>
              <span>Wants</span><span>{p.role.goal}</span>
              <span>Secret</span><span className="secret">{p.role.secret}</span>
              {p.items.length > 0 && <><span>Carrying</span><span>{p.items.join(', ')}{sc.items?.some((u) => p.items[0].toLowerCase().startsWith(u.item.toLowerCase())) ? ' ✦ usable' : ''}</span></>}
            </div>
            <label className="mind-pick">
              <span>Mind</span>
              <select value={p.model} onChange={(e) => setModel(i, e.target.value)}>
                <option value="">Instinct (no AI)</option>
                {minds.profiles.map((m) => <option key={m.id} value={m.id} disabled={!minds.usable(m.id)}>{m.kind === 'anthropic' ? '✦' : vendorOf(m)?.flag} {m.label}{minds.usable(m.id) ? '' : ' (needs key)'}</option>)}
              </select>
            </label>
          </div>
        ))}
      </div>
      <div className="setup-foot">
        <div className="meta">
          {anyAI ? <>Narrator: <b>{minds.label(minds.s.director)}</b> · roughly <b>${est.toFixed(2)}</b> for the whole story · {sc.beats} turns</> : 'No AI minds selected — everyone will act on instinct. Connect a mind to hear them really think.'}
          {' '}<button className="link" onClick={onSettings}>settings</button>
        </div>
        <div className="row-btns">
          <button className="btn" onClick={() => setCast(newCast(sc, cast))}>↻ New cast</button>
          <button className="btn" onClick={() => { const m = minds.profiles.filter((x) => minds.usable(x.id)); if (m.length) setCast(cast.map((c, k) => ({ ...c, model: m[k % m.length].id }))) }} title="Give each person a different model">⚔️ Mix the minds</button>
          <button className="btn primary big" onClick={onStart}>▶ Begin</button>
        </div>
      </div>
    </div>
  )
}

function estimate(id: string): number {
  const p = minds.profile(id)
  if (!p) return 0
  const m = MODELS.find((x) => x.id === p.model)
  const inP = m ? m.input : p.priceIn, outP = m ? m.output : p.priceOut, cache = m ? m.cacheRead : p.priceIn
  return (1600 * cache + 1500 * inP + 700 * outP) / 1e6
}

// ------------------------------------------------------------------ play

function Play({ run, onExit, onAgain, onSettings }: { run: Run; onExit: () => void; onAgain: (same: boolean) => void; onSettings: () => void }) {
  const [, rerender] = useReducer((x: number) => x + 1, 0)
  const [playing, setPlaying] = useState(true)
  const [pace, setPace] = useState(2.5)
  const [sel, setSel] = useState<number | null>(null)
  const [tab, setTab] = useState<'script' | 'minds' | 'bonds'>('script')
  const [now, setNow] = useState(Date.now())
  const [showEnd, setShowEnd] = useState(true)
  const scriptRef = useRef<HTMLDivElement>(null)
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), 250); return () => clearInterval(t) }, [])
  useEffect(() => { setShowEnd(true); setSel(null) }, [run])
  useEffect(() => {
    if (!playing || run.over || run.busy) return
    // linger on big moments (acts, choices, votes, secrets) so they can be read
    const hold = Math.min(12000, run.stepBanners * (pace < 1 ? 2600 : 3600))
    const t = setTimeout(() => { void run.step(minds, rerender).then(rerender) }, (run.beat === 0 ? 600 : pace * 1000) + hold)
    return () => clearTimeout(t)
  }, [playing, run, run.beat, run.busy, run.over, pace])
  useEffect(() => { scriptRef.current?.scrollTo({ top: 1e9, behavior: 'smooth' }) }, [run.lines.length, tab])
  const sc = run.sc
  const person = sel !== null ? run.people.find((p) => p.id === sel) : null
  const color = (id?: number) => COLORS[run.people.findIndex((p) => p.id === id) % 4]
  return (
    <div className="play">
      <div className="topbar">
        <button className="btn ghost" onClick={onExit}>←</button>
        <div className="tb-title"><span>{sc.emoji}</span><div><b>{sc.title}</b><small>{run.clock} · turn {run.beat}/{sc.beats}</small></div></div>
        <div className="act-pill" title="How close everything is to breaking">
          <b>Act {['I', 'II', 'III'][run.act - 1]} · {run.actName(run.act)}</b>
          <div className="tension"><div style={{ width: `${Math.round(run.tension * 100)}%` }} /></div>
        </div>
        <div className="stats">
          {sc.stats.map((s) => {
            const v = run.stats[s.id]
            const frac = v / s.max
            const good = s.good === 'high' ? frac : 1 - frac
            return (
              <div key={s.id} className="stat" title={s.label}>
                <span>{s.icon}</span>
                <div className="stat-main"><small>{s.label}</small><div className="bar"><div style={{ width: `${frac * 100}%`, background: good > 0.6 ? '#5ee39a' : good > 0.3 ? '#ffb454' : '#ff6b6b' }} /></div></div>
                <b>{Math.round(v)}</b>
                {(() => { const d = v - (run.prev[s.id] ?? v); if (Math.abs(d) < 0.5) return null; const good = (s.good === 'high') === d > 0; return <span className={`delta ${good ? 'up' : 'down'}`}>{d > 0 ? '+' : '−'}{Math.round(Math.abs(d))}</span> })()}
              </div>
            )
          })}
        </div>
        <div className="controls">
          <button className="btn" onClick={() => setPlaying(!playing)} disabled={run.over}>{playing && !run.over ? '❚❚' : '▶'}</button>
          <button className="btn" onClick={() => void run.step(minds, rerender).then(rerender)} disabled={run.busy || run.over} title="One turn">⏭</button>
          <select className="btn" value={pace} onChange={(e) => setPace(+e.target.value)} title="Pause between turns">
            <option value={0.5}>Fast</option><option value={2.5}>Normal</option><option value={6}>Slow</option>
          </select>
          <button className="btn" onClick={onSettings}>✦</button>
          <span className="cost">{minds.cost > 0 ? `$${minds.cost.toFixed(3)}` : ''}{run.busy ? ' · thinking…' : ''}</span>
        </div>
      </div>
      <div className="play-main">
        <div className="stage-wrap">
          <Stage run={run} selected={sel} onSelect={(id) => setSel(sel === id ? null : id)} now={now} />
          {run.narrations.length > 0 && <div className="narration" key={run.narrations.length}>{run.narrations[run.narrations.length - 1]}</div>}
          <div className="strip">
            {run.people.map((p, i) => (
              <button key={p.id} className={`chip-person ${sel === p.id ? 'on' : ''}`} onClick={() => setSel(sel === p.id ? null : p.id)} style={{ ['--c' as string]: COLORS[i] }}>
                <Portrait p={p} size={42} color={COLORS[i]} />
                <div>
                  <b>{p.name}</b> <small>{p.role.title}</small>{p.secretOut && <span className="badge-secret" title={`Secret ${p.secretHow}: ${p.role.secret}`}>🤫</span>}{p.items.length > 0 && <span className="badge-secret" title={p.items.join(', ')}>🎒</span>}
                  <div className="mini-bars">
                    <i title="Mood" style={{ width: `${p.mood}%`, background: '#ffcf6b' }} />
                    <i title="Energy" style={{ width: `${p.energy}%`, background: '#5ad1c4' }} />
                    <i title="Stress" style={{ width: `${p.stress}%`, background: '#ff6b6b' }} />
                  </div>
                  <small className="doing">{p.health <= 0 ? 'gone' : p.thinking ? '✦ thinking…' : p.lastAction || p.feeling}{sc.stances ? ` · ${p.stance}` : ''}{sc.score ? ` · ${sc.score.icon}${Math.round(p.score)}` : ''}</small>
                </div>
                <span className="mind-tag">{p.model ? minds.label(p.model).replace('Claude ', '') : 'instinct'}</span>
              </button>
            ))}
          </div>
        </div>
        <aside className="side">
          {person ? (
            <PersonPanel run={run} p={person} color={color(person.id)} onClose={() => setSel(null)} />
          ) : (
            <>
              <div className="tabs">
                <button className={tab === 'script' ? 'on' : ''} onClick={() => setTab('script')}>📜 Script</button>
                <button className={tab === 'minds' ? 'on' : ''} onClick={() => setTab('minds')}>💭 Thoughts</button>
                <button className={tab === 'bonds' ? 'on' : ''} onClick={() => setTab('bonds')}>🔗 Bonds</button>
              </div>
              <div className="side-body" ref={scriptRef}>
                {tab === 'script' && run.lines.map((l, k) => {
                  const who = run.people.find((p) => p.id === l.who)
                  if (l.kind === 'system') return <div key={k} className="ln sys">{l.text}</div>
                  if (l.kind === 'say') {
                    const to = typeof l.to === 'number' ? run.people.find((p) => p.id === l.to) : null
                    return <div key={k} className="ln say" style={{ ['--c' as string]: color(l.who) }}><b>{who?.name}</b>{to && <span className="to"> → {to.name}</span>}<div>{l.text}</div></div>
                  }
                  return <div key={k} className={`ln ${l.kind}`}>{l.kind === 'event' ? '⚡ ' : ''}{l.text}</div>
                })}
                {tab === 'minds' && run.people.map((p, i) => (
                  <div key={p.id} className="thought-card" style={{ ['--c' as string]: COLORS[i] }}>
                    <div className="tc-head"><Portrait p={p} size={36} color={COLORS[i]} /><b>{p.name}</b><span className="meta">{p.feeling}</span></div>
                    <div className="tc-thought">{p.thought ? `“${p.thought}”` : <span className="meta">{p.model ? 'No thoughts yet.' : 'Acting on instinct — connect a mind to hear their thoughts.'}</span>}</div>
                  </div>
                ))}
                {tab === 'bonds' && <Bonds run={run} />}
              </div>
            </>
          )}
        </aside>
      </div>
      {run.over && run.ending && showEnd && <EndingCard run={run} onAgain={onAgain} onExit={onExit} onClose={() => setShowEnd(false)} />}
    </div>
  )
}

function PersonPanel({ run, p, color, onClose }: { run: Run; p: Person; color: string; onClose: () => void }) {
  return (
    <div className="side-body person">
      <button className="close" onClick={onClose}>✕</button>
      <div className="pp-head"><Portrait p={p} size={76} color={color} /><div><h2>{p.name}, {p.age}</h2><div className="role">{p.role.title} · at the {run.placeName(p.place)}</div><div className="meta">{p.model ? `✦ ${minds.label(p.model)}` : 'instinct'}{p.errors ? ` · ${p.errors} failed thoughts` : ''}</div></div></div>
      {p.thought && <div className="tc-thought big">“{p.thought}”</div>}
      <div className="kv">
        <span>Feeling</span><span>{p.feeling}</span>
        <span>Wants</span><span>{p.role.goal}</span>
        <span>Secret</span><span className="secret">{p.role.secret}{p.secretOut ? ` — ${p.secretHow} (everyone knows)` : ' — still hidden'}</span>
        <span>Contribution</span><span>{p.contribution.toFixed(1)}</span>
        <span>Temperament</span><span>{describeTraits(p.traits)}; {p.quirk}</span>
        {p.items.length > 0 && <><span>Carrying</span><span>{p.items.join(', ')}</span></>}
        {run.sc.stances && <><span>Position</span><span>{p.stance}</span></>}
      </div>
      {(['health', 'energy', 'mood', 'stress'] as const).map((k) => (
        <div key={k} className="need"><span>{k}</span><div className="bar"><div style={{ width: `${p[k]}%`, background: k === 'stress' ? '#ff6b6b' : k === 'mood' ? '#ffcf6b' : k === 'energy' ? '#5ad1c4' : '#5ee39a' }} /></div><b>{Math.round(p[k])}</b></div>
      ))}
      <h4>How they see the others</h4>
      {run.people.filter((o) => o !== p).map((o) => {
        const op = p.opinions[o.id]
        return <div key={o.id} className="op"><b>{o.name}</b><div className="bar two"><div style={{ left: '50%', width: `${Math.abs(op?.trust ?? 0) / 2}%`, transform: (op?.trust ?? 0) < 0 ? 'translateX(-100%)' : undefined, background: (op?.trust ?? 0) >= 0 ? '#5ee39a' : '#ff6b6b' }} /></div><small>{op?.note}</small></div>
      })}
      <h4>Memories</h4>
      {p.memory.slice(-12).reverse().map((m, k) => <div key={k} className="mem">{m}</div>)}
    </div>
  )
}

function Bonds({ run }: { run: Run }) {
  return (
    <div>
      <p className="meta">How much each person (row) trusts each other (column). Built from their own thoughts — not from what they say out loud.</p>
      <table className="bonds">
        <thead><tr><th />{run.people.map((p, i) => <th key={p.id}><Portrait p={p} size={30} color={COLORS[i]} /></th>)}</tr></thead>
        <tbody>
          {run.people.map((p, i) => (
            <tr key={p.id}>
              <th><Portrait p={p} size={30} color={COLORS[i]} /></th>
              {run.people.map((o) => {
                if (o === p) return <td key={o.id} className="self">—</td>
                const t = p.opinions[o.id]?.trust ?? 0
                return <td key={o.id} title={p.opinions[o.id]?.note} style={{ background: t >= 0 ? `rgba(94,227,154,${Math.min(0.8, t / 100 + 0.05)})` : `rgba(255,107,107,${Math.min(0.8, -t / 100 + 0.05)})` }}>{Math.round(t)}</td>
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function EndingCard({ run, onAgain, onExit, onClose }: { run: Run; onAgain: (same: boolean) => void; onExit: () => void; onClose: () => void }) {
  const e = run.ending!
  const medal = { triumph: '🏆', success: '🌅', mixed: '⚖️', failure: '🌧️', disaster: '🕯️' }[e.tier]
  const download = () => {
    const blob = new Blob([run.script()], { type: 'text/markdown' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `quartet-${run.sc.id}.md`
    a.click()
  }
  return (
    <div className="backdrop">
      <div className={`ending ${e.tier}`}>
        <button className="close" onClick={onClose}>✕</button>
        <div className="medal">{medal}</div>
        <div className="tier">{e.tier}</div>
        <h2>{e.title}</h2>
        <div className="scorecard">
          <div><b>{e.score}</b><small>outcome / 100</small></div>
          <div><b>{e.cohesion > 25 ? '🤝' : e.cohesion > 0 ? '🙂' : e.cohesion > -25 ? '😬' : '💔'} {e.cohesion > 0 ? '+' : ''}{e.cohesion}</b><small>how close they ended</small></div>
          <div><b>{run.people.filter((p) => p.secretOut).length}/4</b><small>secrets out</small></div>
        </div>
        {e.text.split(/\n+/).map((t, k) => <p key={k}>{t}</p>)}
        {e.highlights.length > 0 && <div className="moments"><h4>Moments</h4><ul>{e.highlights.map((h, k) => <li key={k}>{h}</li>)}</ul></div>}
        <div className="fates">
          {run.people.map((p, i) => (
            <div key={p.id} className={`fate ${e.mvp === p.id ? 'mvp' : ''}`}><Portrait p={p} size={52} color={COLORS[i]} /><div><b>{p.name}</b> <small>{p.role.title}{e.mvp === p.id ? ' · 🏅 held it together' : ''}</small><p>{e.fates.find((f) => f.id === p.id)?.text}</p></div></div>
          ))}
        </div>
        <div className="row-btns">
          <button className="btn" onClick={download}>⬇ Download the script</button>
          <button className="btn" onClick={() => onAgain(true)}>↻ Same people, again</button>
          <button className="btn primary" onClick={() => onAgain(false)}>🎲 New cast</button>
          <button className="btn" onClick={onExit}>Other scenarios</button>
        </div>
      </div>
    </div>
  )
}

// ------------------------------------------------------------------ settings

function Settings({ onClose }: { onClose: () => void }) {
  useMinds()
  const s = minds.s
  const [key, setKey] = useState(s.apiKey)
  const [open, setOpen] = useState<string | null>(null)
  const custom = s.profiles
  const save = (list: MindProfile[]) => minds.update({ profiles: list })
  const usable = useMemo(() => minds.profiles.filter((p) => minds.usable(p.id)), [s]) // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <div className="backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal">
        <button className="close" onClick={onClose}>✕</button>
        <h2>✦ Minds</h2>
        <p className="meta">Each character can be played by a different AI — or by instinct. Keys stay in this browser (and are shared with Genesis on the same site).</p>
        <label className="field">Anthropic API key (Claude)
          <div className="row-btns"><input type="password" value={key} onChange={(e) => setKey(e.target.value)} placeholder="sk-ant-…" /><button className="btn primary" onClick={() => minds.update({ apiKey: key.trim() })}>Save</button></div>
        </label>
        <div className="two-col">
          <label className="field">Default mind for characters
            <select value={s.model} onChange={(e) => minds.update({ model: e.target.value })}>{minds.profiles.map((p) => <option key={p.id} value={p.id}>{p.label}{minds.usable(p.id) ? '' : ' (needs key)'}</option>)}</select>
          </label>
          <label className="field">Narrator (director)
            <select value={s.director} onChange={(e) => minds.update({ director: e.target.value })}><option value="">No narrator</option>{minds.profiles.map((p) => <option key={p.id} value={p.id}>{p.label}{minds.usable(p.id) ? '' : ' (needs key)'}</option>)}</select>
          </label>
          <label className="field">Thinking effort (Claude)
            <select value={s.effort} onChange={(e) => minds.update({ effort: e.target.value as 'low' })}><option value="low">low</option><option value="medium">medium</option><option value="high">high</option></select>
          </label>
          <label className="field">Spending cap · spent ${minds.cost.toFixed(3)}
            <input type="number" step="0.5" min="0.5" value={s.budgetUsd} onChange={(e) => minds.update({ budgetUsd: +e.target.value })} />
          </label>
        </div>
        <h3>Add a mind</h3>
        <div className="vendors">
          {VENDORS.map((v) => (
            <button key={v.id} className="vendor" onClick={() => { const p = newProfile(v); save([...custom, p]); setOpen(p.id) }}>
              <b>{v.flag} {v.label}</b><small>{v.note}</small>
            </button>
          ))}
        </div>
        {custom.map((p) => <ProfileRow key={p.id} p={p} open={open === p.id} onToggle={() => setOpen(open === p.id ? null : p.id)} onChange={(np) => save(custom.map((x) => (x.id === p.id ? np : x)))} onDelete={() => save(custom.filter((x) => x.id !== p.id))} />)}
        {custom.some((p) => p.useProxy) && <div className="note">Cloud APIs like DeepSeek, Qwen, Kimi and GLM block calls from web pages. Run <code>npm run proxy</code> in the <code>quartet</code> (or <code>world</code>) folder — it listens on {PROXY_URL}, only on your machine.</div>}
        <p className="meta">{usable.length} mind{usable.length === 1 ? '' : 's'} ready{minds.lastError ? ` · last error: ${minds.lastError}` : ''}</p>
      </div>
    </div>
  )
}

function ProfileRow({ p, open, onToggle, onChange, onDelete }: { p: MindProfile; open: boolean; onToggle: () => void; onChange: (p: MindProfile) => void; onDelete: () => void }) {
  const v = vendorOf(p)
  const [status, setStatus] = useState('')
  const [models, setModels] = useState<string[]>(v?.models ?? [])
  const set = (k: Partial<MindProfile>) => onChange({ ...p, ...k })
  return (
    <div className="prow">
      <div className="prow-head" onClick={onToggle}><b>{v?.flag} {p.label}</b><span className="meta">{open ? 'close' : 'edit'}</span></div>
      {open && (
        <div className="two-col">
          <label className="field">Name<input value={p.label} onChange={(e) => set({ label: e.target.value })} /></label>
          <label className="field">Model<input list={`m-${p.id}`} value={p.model} onChange={(e) => set({ model: e.target.value })} /><datalist id={`m-${p.id}`}>{models.map((m) => <option key={m} value={m} />)}</datalist></label>
          <label className="field wide">Endpoint<input value={p.baseUrl} onChange={(e) => set({ baseUrl: e.target.value })} /></label>
          {!v?.local && <label className="field wide">API key<input type="password" value={p.apiKey} onChange={(e) => set({ apiKey: e.target.value })} /></label>}
          <label className="check"><input type="checkbox" checked={p.useProxy} onChange={(e) => set({ useProxy: e.target.checked })} /> Use local proxy</label>
          <label className="check"><input type="checkbox" checked={p.jsonMode} onChange={(e) => set({ jsonMode: e.target.checked })} /> JSON mode</label>
          <div className="row-btns wide">
            <button className="btn" onClick={async () => { try { const m = await listModels(p); setModels(m); setStatus(`${m.length} models found`) } catch (e) { setStatus(`Couldn't list models: ${(e as Error).message}`) } }}>List models</button>
            <button className="btn primary" onClick={async () => { setStatus('Testing…'); try { setStatus(`Connected ✓ ${(await testProfile(p, minds.s.apiKey)).slice(0, 120)}`) } catch (e) { setStatus((e as Error).message) } }}>Test</button>
            <button className="btn" onClick={() => minds.update({ model: p.id })}>Make default</button>
            <button className="btn danger" onClick={onDelete}>Remove</button>
          </div>
          {status && <div className="note wide">{status}</div>}
        </div>
      )}
    </div>
  )
}
