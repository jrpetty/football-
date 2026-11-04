import { useEffect, useRef, useState, type ReactNode } from 'react'
import { game } from '../game.ts'
import { mind } from '../ai/mind.ts'
import { type Effort } from '../ai/client.ts'
import { ProvidersSection, ProfileSelect } from './Providers.tsx'
import { PRESETS } from '../sim/worldgen.ts'
import { DEFAULT_CONFIG, type WorldConfig } from '../sim/world.ts'
import { ERAS } from '../sim/tech.ts'
import { serialize, deserialize, listSaves, loadSlot, saveToSlot, deleteSlot, type SaveMeta, type SaveFile } from '../sim/save.ts'
import { Toggle, useGameVersion, useMindVersion } from './common.tsx'

function Modal({ onClose, children, wide }: { onClose: () => void; children: ReactNode; wide?: boolean }) {
  useEffect(() => {
    const k = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', k)
    return () => window.removeEventListener('keydown', k)
  }, [onClose])
  return (
    <div className="backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal glass" style={wide ? { width: 'min(900px, 100%)' } : undefined}>
        <button className="close-x" onClick={onClose} aria-label="Close">✕</button>
        {children}
      </div>
    </div>
  )
}

// ------------------------------------------------------------------ Settings

export function SettingsModal({ onClose }: { onClose: () => void }) {
  useMindVersion()
  useGameVersion()
  const s = mind.settings
  const [key, setKey] = useState(s.apiKey)
  const [show, setShow] = useState(false)
  const w = game.world
  const hourly = mind.estimateHourly(game.speed)
  const st = mind.stats
  return (
    <Modal onClose={onClose} wide>
      <h2>✦ Minds & settings</h2>
      <p className="lead">Every person can be driven by their own AI mind — Claude, the Chinese model APIs (DeepSeek, Qwen, Kimi, GLM), or models you download and run yourself (Ollama, LM Studio). They decide what to do from what they see, feel, remember and are told — nothing is scripted.</p>

      <div className="field">
        <label>Anthropic API key (Claude)</label>
        <div style={{ display: 'flex', gap: 8 }}>
          <input className="input" type={show ? 'text' : 'password'} placeholder="sk-ant-…" value={key} onChange={(e) => setKey(e.target.value)} autoComplete="off" spellCheck={false} />
          <button className="btn" onClick={() => setShow(!show)}>{show ? 'Hide' : 'Show'}</button>
          <button className="btn primary" onClick={() => mind.update({ apiKey: key.trim(), enabled: true })}>Save key</button>
        </div>
        <div className="hint">For Claude minds. Stored only in this browser and sent only to Anthropic. Get one at console.anthropic.com. You can skip this and use another provider or a local model below — and with no minds at all, people live on instinct.</div>
      </div>

      <div className="field">
        <Toggle checked={s.enabled} onChange={(v) => mind.update({ enabled: v })} label={<b>Minds awake</b>} />
        {st.pausedReason && <div className="note warn" style={{ marginTop: 8 }}>{st.pausedReason}</div>}
      </div>

      <ProvidersSection />

      <div className="field">
        <label>Mixed minds — give each tribe its own model</label>
        <div className="hint" style={{ marginTop: 0, marginBottom: 8 }}>Pit models against each other and see which civilisation flourishes. (You can also set a model per person in their inspector.)</div>
        {w.tribes.filter((t) => t.alive).map((t) => (
          <div key={t.id} style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6 }}>
            <span style={{ width: 160, color: t.color, fontWeight: 650 }}>{t.emblem} {t.name}</span>
            <ProfileSelect className="input" style={{ padding: '6px 10px' }} value={t.model} onChange={(v) => { t.model = v; game.emit() }} emptyLabel={`Default (${mind.label(s.model)})`} />
          </div>
        ))}
      </div>

      <div className="cols">
        <div className="field">
          <label>Thinking effort</label>
          <div className="filters">
            {(['low', 'medium', 'high'] as Effort[]).map((e) => (
              <button key={e} className={`chip ${s.effort === e ? 'on' : ''}`} onClick={() => mind.update({ effort: e })}>{e}</button>
            ))}
          </div>
          <div className="hint">Low is quick and cheap; higher effort means more deliberate decisions.</div>
        </div>
        <div className="field">
          <label>Spending cap (this session)</label>
          <div className="slider-row">
            <input type="range" min={0.5} max={100} step={0.5} value={s.budgetUsd} onChange={(e) => mind.update({ budgetUsd: +e.target.value })} />
            <b>${s.budgetUsd.toFixed(2)}</b>
          </div>
          <div className="hint">Minds pause (instinct takes over) once ${st.cost.toFixed(3)} spent reaches this.</div>
        </div>
        <div className="field">
          <label>People with Claude minds</label>
          <div className="slider-row">
            <input type="range" min={1} max={80} value={s.maxMinds} onChange={(e) => mind.update({ maxMinds: +e.target.value })} />
            <b>{s.maxMinds}</b>
          </div>
          <div className="hint">Leaders always get one; everyone else lives on instinct.</div>
        </div>
        <div className="field">
          <label>Re-think at least every</label>
          <div className="slider-row">
            <input type="range" min={2} max={24} value={s.thinkEveryHours} onChange={(e) => mind.update({ thinkEveryHours: +e.target.value })} />
            <b>{s.thinkEveryHours} game h</b>
          </div>
          <div className="hint">They also think whenever their plan ends, someone speaks to them, or something big happens.</div>
        </div>
        <div className="field">
          <label>Simultaneous thoughts</label>
          <div className="slider-row">
            <input type="range" min={1} max={16} value={s.maxConcurrent} onChange={(e) => mind.update({ maxConcurrent: +e.target.value })} />
            <b>{s.maxConcurrent}</b>
          </div>
        </div>
        <div className="field">
          <label>Max thoughts per minute</label>
          <div className="slider-row">
            <input type="range" min={2} max={200} value={s.maxPerMinute} onChange={(e) => mind.update({ maxPerMinute: +e.target.value })} />
            <b>{s.maxPerMinute}</b>
          </div>
        </div>
      </div>
      <div className="field">
        <Toggle checked={s.reflections} onChange={(v) => mind.update({ reflections: v })} label="Reflection — every couple of days minds distil lessons, beliefs and a sense of self from their memories" />
        <div style={{ height: 8 }} />
        <Toggle checked={s.chronicler} onChange={(v) => mind.update({ chronicler: v })} label="Historian — a Claude chronicler writes a chapter of history every year" />
      </div>

      <div className="note">
        Estimated cost at {game.speed}× speed: <b>~${hourly.toFixed(2)} per real hour</b> with {mind.label(s.model)}. Spent so far: <b>${st.cost.toFixed(3)}</b> over {st.calls} calls.
        {Object.entries(st.byModel).length > 1 && <> ({Object.entries(st.byModel).map(([m, v]) => `${mind.label(m)}: ${v.calls} / $${v.cost.toFixed(3)}`).join(' · ')})</>}
        <br />Faster game speeds don't cost more: thoughts are rate-limited, and instinct fills the gaps.
      </div>
      <div className="btn-row"><button className="btn primary" onClick={onClose}>Done</button></div>
    </Modal>
  )
}

// ------------------------------------------------------------------ New world

export function NewWorldModal({ onClose, onCreated }: { onClose: () => void; onCreated?: () => void }) {
  const [cfg, setCfg] = useState<WorldConfig>({ ...DEFAULT_CONFIG, seed: Math.floor(Math.random() * 1e9), name: randomName() })
  const set = (p: Partial<WorldConfig>) => setCfg({ ...cfg, ...p })
  return (
    <Modal onClose={onClose} wide>
      <h2>🌍 Create a new world</h2>
      <p className="lead">Every world is generated from a seed: continents, climate, rivers, forests and ore. People begin with nothing but their hands.</p>
      <div className="cols">
        <div className="field">
          <label>World name</label>
          <input className="input" value={cfg.name} onChange={(e) => set({ name: e.target.value })} />
        </div>
        <div className="field">
          <label>Map seed</label>
          <div style={{ display: 'flex', gap: 8 }}>
            <input className="input" type="number" value={cfg.seed} onChange={(e) => set({ seed: +e.target.value || 1 })} />
            <button className="btn" onClick={() => set({ seed: Math.floor(Math.random() * 1e9) })}>🎲</button>
          </div>
        </div>
      </div>
      <div className="field">
        <Toggle checked={!cfg.fixedHistory} onChange={(v) => set({ fixedHistory: !v })} label={<span><b>Unique history</b> — the same seed always makes the same land, but different people and a different story every time. Turn off to replay a seed exactly.</span>} />
      </div>
      <div className="field">
        <label>Land</label>
        <div className="choices">
          {PRESETS.map((p) => (
            <button key={p.id} className={`choice ${cfg.preset === p.id ? 'on' : ''}`} onClick={() => set({ preset: p.id })}>
              <div className="t">{p.name}</div>
              <div className="d">{p.blurb}</div>
            </button>
          ))}
        </div>
      </div>
      <div className="cols">
        <div className="field">
          <label>Size</label>
          <div className="filters">
            {[[112, 'Small'], [160, 'Medium'], [208, 'Large'], [256, 'Huge']].map(([n, l]) => (
              <button key={n} className={`chip ${cfg.size === n ? 'on' : ''}`} onClick={() => set({ size: n as number })}>{l} ({n}²)</button>
            ))}
          </div>
        </div>
        <div className="field">
          <label>Starting bands</label>
          <div className="slider-row"><input type="range" min={1} max={6} value={cfg.tribes} onChange={(e) => set({ tribes: +e.target.value })} /><b>{cfg.tribes}</b></div>
        </div>
        <div className="field">
          <label>People per band</label>
          <div className="slider-row"><input type="range" min={2} max={10} value={cfg.perTribe} onChange={(e) => set({ perTribe: +e.target.value })} /><b>{cfg.perTribe}</b></div>
        </div>
        <div className="field">
          <label>Population limit</label>
          <div className="slider-row"><input type="range" min={20} max={250} step={10} value={cfg.popCap} onChange={(e) => set({ popCap: +e.target.value })} /><b>{cfg.popCap}</b></div>
          <div className="hint">Births slow as the world nears this — keeps performance and AI cost in check.</div>
        </div>
      </div>
      <div className="btn-row">
        <button className="btn" onClick={onClose}>Cancel</button>
        <button className="btn primary" onClick={() => { game.newWorld(cfg); onClose(); onCreated?.() }}>Create world</button>
      </div>
    </Modal>
  )
}

const N1 = ['Terra', 'Gaia', 'Eden', 'Avalon', 'Arden', 'Thule', 'Elysia', 'Pangea', 'Hesper', 'Verdant', 'Aurora', 'Lumen']
const N2 = ['Nova', 'Prime', 'Vale', 'Reach', 'Deep', 'Rising', 'Major', 'Minor', 'Haven', 'Wilds']
function randomName() {
  return `${N1[Math.floor(Math.random() * N1.length)]} ${N2[Math.floor(Math.random() * N2.length)]}`
}

// ------------------------------------------------------------------ Saves

export function SavesModal({ onClose, onLoaded }: { onClose: () => void; onLoaded?: () => void }) {
  const [saves, setSaves] = useState<SaveMeta[]>([])
  const [msg, setMsg] = useState('')
  const file = useRef<HTMLInputElement>(null)
  const refresh = () => listSaves().then(setSaves)
  useEffect(() => { void refresh() }, [])
  const w = game.world
  return (
    <Modal onClose={onClose}>
      <h2>💾 Worlds</h2>
      <p className="lead">Worlds auto-save every two minutes in this browser. Save named copies, or export a file to keep or share.</p>
      <div className="btn-row" style={{ justifyContent: 'flex-start', marginTop: 0, marginBottom: 14 }}>
        <button className="btn primary" onClick={async () => { await saveToSlot(`${w.cfg.name} — ${new Date().toLocaleString()}`, w); setMsg('Saved.'); void refresh() }}>Save “{w.cfg.name}” now</button>
        <button className="btn" onClick={() => {
          const blob = new Blob([JSON.stringify(serialize(w))], { type: 'application/json' })
          const a = document.createElement('a')
          a.href = URL.createObjectURL(blob)
          a.download = `${w.cfg.name.replace(/\s+/g, '-').toLowerCase()}-genesis.json`
          a.click()
          setTimeout(() => URL.revokeObjectURL(a.href), 2000)
        }}>Export file</button>
        <button className="btn" onClick={() => file.current?.click()}>Import file</button>
        <input ref={file} type="file" accept="application/json,.json" hidden onChange={async (e) => {
          const f = e.target.files?.[0]
          if (!f) return
          try {
            const data = JSON.parse(await f.text()) as SaveFile
            game.setWorld(deserialize(data))
            onClose()
            onLoaded?.()
          } catch {
            setMsg('That file could not be read as a Genesis world.')
          }
        }} />
      </div>
      {msg && <div className="note" style={{ marginBottom: 10 }}>{msg}</div>}
      {saves.map((s) => (
        <div key={s.slot} className="row" style={{ cursor: 'default' }}>
          <div className="main">
            <div className="name">{s.slot === 'autosave' ? `⟳ Autosave — ${s.name}` : s.slot}</div>
            <div className="sub">Year {s.year} · {s.pop} people · {ERAS[s.era]?.name} · {new Date(s.savedAt).toLocaleString()}</div>
          </div>
          <button className="icon-btn" onClick={async () => { const ok = await game.load(s.slot); if (ok) { onClose(); onLoaded?.() } else setMsg('Could not load that save.') }}>Load</button>
          <button className="icon-btn" onClick={async () => { await deleteSlot(s.slot); void refresh() }}>🗑</button>
        </div>
      ))}
      {saves.length === 0 && <div className="empty">No saved worlds yet.</div>}
    </Modal>
  )
}

export async function hasAutosave(): Promise<boolean> {
  const s = await listSaves()
  return s.some((x) => x.slot === 'autosave')
}
export { loadSlot }
