import { useState } from 'react'
import { loadPlayers, upsertPlayer, removePlayer, forgetLessons, newPlayer, type PlayerAI } from '../ai/players.ts'
import { mergePrefs, LEARNED_NAMES } from '../sim/learning.ts'
import type { Learned, Prefs } from '../sim/types.ts'
import { mind } from '../ai/mind.ts'
import { ProfileSelect } from './Providers.tsx'

const SLIDERS: { k: keyof Prefs; lo: string; hi: string }[] = [
  { k: 'war', lo: '🕊️ Peace', hi: 'Conquest ⚔️' },
  { k: 'science', lo: '🏺 Tradition', hi: 'Knowledge 🔭' },
  { k: 'expand', lo: '🏡 Stay home', hi: 'Explore & settle 🧭' },
  { k: 'trade', lo: '🛡️ Keep to ourselves', hi: 'Trade & allies 🤝' },
  { k: 'faith', lo: '⚒️ Practical', hi: 'Faith & culture 🎭' },
  { k: 'growth', lo: '🌱 Small & careful', hi: 'Big families 👪' },
]

const EXAMPLES = [
  'Be peaceful and curious. Trade with everyone, never start a war, and push for science until we reach the stars.',
  'Build an empire. Grow strong, take the good land, and make the weak pay tribute.',
  'Keep our people safe above all: full granaries, strong walls, and allies we can trust.',
]

/**
 * Players and their AIs. Set up before a world begins; once it starts, each AI
 * plays its people alone. With `pick`, players can be chosen to take part.
 */
export function PlayersSection({ picked, setPicked, max }: { picked?: string[]; setPicked?: (ids: string[]) => void; max?: number }) {
  const [list, setList] = useState<PlayerAI[]>(() => loadPlayers())
  const [open, setOpen] = useState<string | null>(null)
  const refresh = () => setList(loadPlayers())
  const save = (p: PlayerAI) => { upsertPlayer(p); refresh() }
  const toggle = (id: string) => {
    if (!picked || !setPicked) return
    if (picked.includes(id)) setPicked(picked.filter((x) => x !== id))
    else if (!max || picked.length < max) setPicked([...picked, id])
  }
  return (
    <div className="field">
      <label>Players — each brings their own AI</label>
      <div className="hint" style={{ marginTop: 0, marginBottom: 8 }}>
        Tell your AI how you'd like it to play, before history begins. Once the world starts it plays your people <b>entirely on its own</b>: no orders, no changes, no takebacks. It learns from its mistakes, and it keeps those lessons for the next world.
      </div>
      {list.map((p) => {
        const on = picked?.includes(p.id)
        const seat = picked ? picked.indexOf(p.id) : -1
        return (
          <div key={p.id} className="card" style={{ borderColor: on ? 'var(--accent)' : undefined }}>
            <h3 style={{ gap: 10 }}>
              {picked && <input type="checkbox" checked={!!on} onChange={() => toggle(p.id)} disabled={!on && !!max && picked.length >= max} title="Plays in this world" />}
              <span>🧑 {p.name}</span>
              <span className="tag">{p.mind === 'genesis-mind' ? '🌱' : '✦'} {mind.label(p.mind)}</span>
              {seat >= 0 && <span className="tag accent">people #{seat + 1}</span>}
              <span className="meta" style={{ marginLeft: 'auto' }}>{p.games.length} world{p.games.length === 1 ? '' : 's'} · {p.lessons.length} lessons</span>
              <button className="btn" style={{ padding: '4px 10px' }} onClick={() => setOpen(open === p.id ? null : p.id)}>{open === p.id ? 'Done' : 'Edit'}</button>
            </h3>
            {p.brief && open !== p.id && <div className="quote">“{p.brief}”</div>}
            {open === p.id && <PlayerEditor p={p} onChange={save} onDelete={() => { removePlayer(p.id); setPicked?.((picked ?? []).filter((x) => x !== p.id)); refresh(); setOpen(null) }} onForget={() => { forgetLessons(p.id); refresh() }} />}
          </div>
        )
      })}
      <button className="btn" onClick={() => {
        const p = newPlayer(`Player ${list.length + 1}`)
        upsertPlayer(p); refresh(); setOpen(p.id)
        if (picked && setPicked && (!max || picked.length < max)) setPicked([...picked, p.id])
      }}>+ New player AI</button>
      {picked && max !== undefined && <div className="hint">{picked.length} of {max} peoples will be played by players' AIs; the rest by the world's own Genesis Mind.</div>}
    </div>
  )
}

function PlayerEditor({ p, onChange, onDelete, onForget }: { p: PlayerAI; onChange: (p: PlayerAI) => void; onDelete: () => void; onForget: () => void }) {
  const set = (x: Partial<PlayerAI>) => onChange({ ...p, ...x })
  const prefs = mergePrefs(p.sliders, p.brief)
  return (
    <div style={{ marginTop: 10 }}>
      <div className="cols">
        <div className="field"><label>Name</label><input className="input" value={p.name} onChange={(e) => set({ name: e.target.value.slice(0, 40) })} /></div>
        <div className="field">
          <label>Mind</label>
          <ProfileSelect className="input" value={p.mind === 'genesis-mind' ? '' : p.mind} onChange={(v) => set({ mind: v || 'genesis-mind' })} emptyLabel="🌱 Genesis Mind (built in, free)" />
        </div>
      </div>
      <div className="field">
        <label>Your brief — how should your AI play?</label>
        <textarea className="input" rows={3} value={p.brief} maxLength={1200} placeholder={EXAMPLES[0]} onChange={(e) => set({ brief: e.target.value })} />
        <div className="filters" style={{ marginTop: 6 }}>
          {EXAMPLES.map((ex, i) => <button key={i} className="chip" onClick={() => set({ brief: ex })}>{['Peaceful scholar', 'Conqueror', 'Guardian'][i]}</button>)}
        </div>
        <div className="hint">Model minds read your brief word for word as their people's founding creed. The Genesis Mind reads it for what you want more or less of, and adds it to the sliders.</div>
      </div>
      {SLIDERS.map(({ k, lo, hi }) => (
        <div key={k} className="pref-row">
          <span className="lo">{lo}</span>
          <input type="range" min={-1} max={1} step={0.1} value={p.sliders[k]} onChange={(e) => set({ sliders: { ...p.sliders, [k]: +e.target.value } })} />
          <span className="hi">{hi}</span>
          <span className="eff" title="With your brief taken into account">{prefs[k] > 0.05 ? '+' : ''}{Math.abs(prefs[k]) < 0.05 ? '·' : prefs[k].toFixed(1)}</span>
        </div>
      ))}
      <div className="field" style={{ marginTop: 10 }}>
        <label>What it has learned{p.games.length ? ` over ${p.games.length} world${p.games.length > 1 ? 's' : ''}` : ''}</label>
        <LearnedBars learned={p.learned} />
        {p.lessons.length > 0 ? (
          <details style={{ marginTop: 6 }}>
            <summary className="meta" style={{ cursor: 'pointer' }}>🧠 {p.lessons.length} lessons</summary>
            {p.lessons.slice().reverse().map((l, i) => <div key={i} className="mem"><span className="ago">{l.world} · y{l.year}</span>{l.text}</div>)}
          </details>
        ) : <div className="hint">Nothing yet. It learns every year it plays, from famine, cold, lost wars, raids and falling behind.</div>}
        {p.games.length > 0 && (
          <details style={{ marginTop: 6 }}>
            <summary className="meta" style={{ cursor: 'pointer' }}>🌍 Worlds played</summary>
            {p.games.slice().reverse().map((g, i) => <div key={i} className="mem"><span className="ago">{g.world}</span>the {g.tribe}: {g.years} years, {g.pop} people, {g.techs} technologies{g.alive ? '' : ' — perished'}</div>)}
          </details>
        )}
      </div>
      <div className="btn-row" style={{ justifyContent: 'flex-start', marginTop: 0 }}>
        <button className="btn" onClick={onForget} disabled={!p.lessons.length}>Forget what it learned</button>
        <button className="btn danger" onClick={onDelete}>Delete this AI</button>
      </div>
    </div>
  )
}

export function LearnedBars({ learned }: { learned: Learned }) {
  const keys = Object.keys(LEARNED_NAMES) as (keyof Learned)[]
  return (
    <div className="learned">
      {keys.map((k) => {
        const v = learned[k]
        const d = v - 1
        return (
          <div key={k} className="lrow" title={`${LEARNED_NAMES[k]} ×${v.toFixed(2)}`}>
            <span>{LEARNED_NAMES[k]}</span>
            <span className="lbar"><span style={{ left: d < 0 ? `${50 + d * 50}%` : '50%', width: `${Math.min(50, Math.abs(d) * 50)}%`, background: d >= 0 ? 'var(--good)' : 'var(--warn)' }} /></span>
            <b>×{v.toFixed(2)}</b>
          </div>
        )
      })}
    </div>
  )
}
