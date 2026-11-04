import { useEffect, useState } from 'react'
import { game } from '../game.ts'
import { mind } from '../ai/mind.ts'
import { listSaves, type SaveMeta } from '../sim/save.ts'
import { ERAS } from '../sim/tech.ts'
import { useGameVersion } from './common.tsx'
import { Portrait } from './Portrait.tsx'

type Go = 'play' | 'new' | 'saves' | 'settings' | 'about'

/** The title screen. The world keeps living behind it while the camera drifts. */
export function MainMenu({ onGo }: { onGo: (g: Go) => void }) {
  useGameVersion()
  const [auto, setAuto] = useState<SaveMeta | null>(null)
  useEffect(() => {
    void listSaves().then((s) => setAuto(s.find((x) => x.slot === 'autosave') ?? null))
    if (game.renderer) game.renderer.cinematic = true
    return () => { if (game.renderer) game.renderer.cinematic = false }
  }, [])
  const w = game.world
  const lines = w.chronicle.filter((e) => e.kind === 'speech' || e.kind === 'tech' || e.kind === 'birth' || e.kind === 'social').slice(-5).reverse()
  const cast = w.living.filter((a) => w.isAdult(a)).slice(0, 6)
  const web = typeof location !== 'undefined' && location.protocol.startsWith('http')
  return (
    <div className="menu">
      <div className="menu-veil" />
      <div className="menu-col">
        <div className="menu-logo">
          <div className="logo big" />
          <div>
            <h1>Genesis</h1>
            <div className="menu-sub">a civilisation of minds</div>
          </div>
        </div>
        <p className="menu-tag">Every person in this world thinks for themselves. From bare hands to the stars — and never the same story twice.</p>
        <div className="menu-buttons">
          <button className="menu-btn primary" onClick={() => onGo('play')}>
            <span>▶</span><div><b>Enter this world</b><small>{w.cfg.name} · Year {Math.floor(w.time / (12 * 1440)) + 1} · {w.living.length} people · {ERAS[w.era].name}</small></div>
          </button>
          {auto && (
            <button className="menu-btn" onClick={async () => { await game.load('autosave'); onGo('play') }}>
              <span>⟳</span><div><b>Continue</b><small>{auto.name} · Year {auto.year} · {auto.pop} people</small></div>
            </button>
          )}
          <button className="menu-btn" onClick={() => onGo('new')}><span>🌍</span><div><b>New world</b><small>Choose the land, the peoples and the seed</small></div></button>
          <button className="menu-btn" onClick={() => onGo('saves')}><span>💾</span><div><b>Load a world</b><small>Saved worlds and files</small></div></button>
          <button className="menu-btn" onClick={() => onGo('settings')}>
            <span>✦</span><div><b>Minds & providers</b><small>{mind.configured ? `${mind.label(mind.settings.model)} · ${mind.ready ? 'awake' : 'asleep'}` : 'Claude · DeepSeek · Qwen · Kimi · GLM · Ollama · LM Studio'}</small></div>
          </button>
          <button className="menu-btn" onClick={() => onGo('about')}><span>📖</span><div><b>How it works</b><small>What the minds know, and how history unfolds</small></div></button>
          {web && (
            <a className="menu-btn" href="../quartet/"><span>🎭</span><div><b>Quartet</b><small>Four AI minds, one real-world situation — a separate experience</small></div></a>
          )}
        </div>
      </div>
      <div className="menu-side hide-sm">
        <div className="menu-card">
          <div className="section-title" style={{ marginTop: 0 }}>Living right now</div>
          <div className="menu-cast">
            {cast.map((a) => (
              <div key={a.id} className="menu-person">
                <Portrait a={a} size={54} />
                <b>{a.name}</b>
                <small>{a.task?.label || a.feeling}</small>
              </div>
            ))}
          </div>
          <div className="section-title">Meanwhile…</div>
          {lines.map((e, i) => <div key={i} className="menu-line">{e.icon} {e.text}</div>)}
        </div>
      </div>
    </div>
  )
}

export function AboutModal({ onClose }: { onClose: () => void }) {
  return (
    <div className="backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal glass">
        <button className="close-x" onClick={onClose}>✕</button>
        <h2>How Genesis works</h2>
        <div className="features" style={{ marginTop: 14 }}>
          <div className="feature"><div className="i">✦</div><b>Minds of their own</b><span>Each person can be driven by their own AI — Claude, DeepSeek, Qwen, Kimi, GLM, or a model running on your own computer. It only knows what that person sees, feels, remembers and has been told.</span></div>
          <div className="feature"><div className="i">🌍</div><b>An Earth-like world</b><span>Climate from latitude and altitude, rivers, seasons, real resources in realistic places. Sea water is undrinkable; winters kill the unsheltered.</span></div>
          <div className="feature"><div className="i">🔥</div><b>Learning by doing</b><span>Early knowledge comes from practice: working clay by the fire teaches pottery, tending wild plants teaches farming. Sudden insights happen. Geography shapes what is discovered first.</span></div>
          <div className="feature"><div className="i">🌫️</div><b>Fragile knowledge</b><span>Before writing, knowledge lives in memory. A people that dwindles can forget what it knew — and must rediscover it.</span></div>
          <div className="feature"><div className="i">🎲</div><b>Never the same twice</b><span>The land can be replayed from its seed, but the people, their choices and their history are new every time.</span></div>
          <div className="feature"><div className="i">🧬</div><b>Lives & lineages</b><span>Children inherit looks and temperament with mutation. People court, grieve, quarrel, lead, split into new tribes and found beliefs.</span></div>
        </div>
        <p className="meta">Ten eras, 62 technologies, 32 kinds of building and real production chains — from flint to rockets. Instinct keeps everyone alive between thoughts or when no AI is connected.</p>
        <div className="btn-row"><button className="btn primary" onClick={onClose}>Got it</button></div>
      </div>
    </div>
  )
}
