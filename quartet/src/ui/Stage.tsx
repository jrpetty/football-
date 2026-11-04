// The scene: the places of the scenario painted as a stage, the four people
// standing where they are (walking smoothly when they move), speech bubbles,
// emotes, and a light that follows the time of day.

import type { Run } from '../engine/engine.ts'
import type { Banner, Fx, Person } from '../engine/types.ts'
import { useEffect, useRef, useState } from 'react'
import { Figure, Face, mix, moodOf } from './Figure.tsx'
import { Cast3D, webgl, type CastEntry } from './cast3d.ts'
import type { State } from './rig3d.ts'
import { Backdrop } from './Backdrop.tsx'

const W = 1000, H = 620

export const COLORS = ['#ff8a65', '#4fc3f7', '#ffd166', '#b39ddb']

function hash(s: string): number {
  let h = 2166136261
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619)
  return (h >>> 0) / 4294967296
}

const TINTS = ['#7fb3d5', '#a3d977', '#f5b971', '#c39bd3', '#f1948a', '#76d7c4', '#f7dc6f']
function tint(id: string): string {
  return TINTS[Math.floor(hash(id) * TINTS.length)]
}

function hourOf(clock: string): number | null {
  const m = /(\d{1,2}):(\d{2})/.exec(clock)
  return m ? +m[1] + +m[2] / 60 : /evening|night/i.test(clock) ? 21 : /morning/i.test(clock) ? 9 : null
}

export function Stage({ run, selected, onSelect, now }: { run: Run; selected: number | null; onSelect: (id: number) => void; now: number }) {
  const sc = run.sc
  const stageRef = useRef<HTMLDivElement>(null)
  const castCanvas = useRef<HTMLCanvasElement>(null)
  const cast = useRef<Cast3D | null>(null)
  const [three] = useState(() => webgl() && localStorage.getItem('quartet.view') !== '2d')
  useEffect(() => {
    if (!three || !castCanvas.current || !stageRef.current) return
    try { cast.current = new Cast3D(castCanvas.current, stageRef.current) } catch { cast.current = null }
    return () => { cast.current?.dispose(); cast.current = null }
  }, [three])
  const moved = useRef(new Map<number, { place: string; at: number; from: number }>())
  for (const p of run.people) {
    const m = moved.current.get(p.id)
    if (!m) moved.current.set(p.id, { place: p.place, at: 0, from: 0 })
    else if (m.place !== p.place) { m.from = m.place === '' ? 0 : 1; m.place = p.place; m.at = now }
  }
  const h = hourOf(run.clock)
  const night = sc.theme.night && h !== null ? (h < 6 || h >= 20 ? 0.55 : h < 7.5 ? 0.3 : h >= 18.5 ? 0.25 : 0) : 0
  const lastSpeaker = [...run.lines].reverse().find((l) => l.kind === 'say')?.who
  // slots: people sharing a place stand side by side
  const slots = new Map<number, { x: number; y: number; k: number }>()
  for (const pl of sc.places) {
    const here = run.people.filter((p) => p.place === pl.id)
    here.forEach((p, k) => {
      const n = here.length
      const x = pl.x + pl.w * ((k + 1) / (n + 1))
      const y = pl.y + pl.h * 0.8
      slots.set(p.id, { x, y, k })
    })
  }
  const entries: CastEntry[] = []
  // places where something just happened glow
  const active = new Set<string>()
  for (const f of run.fx) if (now - f.at < 2600 && f.who !== undefined) { const pp = run.people.find((q) => q.id === f.who); if (pp) active.add(pp.place) }
  // cards play one after another, never on top of each other
  const bq = useRef({ run: null as Run | null, idx: -1, start: 0 })
  if (bq.current.run !== run) bq.current = { run, idx: run.banners.length - 2, start: now }
  const q = bq.current
  const curB = run.banners[q.idx]
  if (!curB || now - q.start > bannerLife(curB)) {
    if (q.idx + 1 < run.banners.length) { q.idx = Math.max(q.idx + 1, run.banners.length - 4); q.start = now }
  }
  const shownB = run.banners[q.idx] ?? null
  const bAge = now - q.start
  // the stage itself reacts to what happens: storms shake it, shocks flash
  const b = shownB
  const fresh = b && bAge < 1800
  const weather = fresh && b!.kind === 'event' && /storm|squall|blizzard|wind|rain|gale|wave|sea/i.test(b!.text) ? 'fx-storm' : ''
  const shock = fresh && (b!.kind === 'reveal' || (b!.kind === 'event' && /crash|fire|explo|power|outage|collapse|dark|alarm|sirens?/i.test(b!.text))) ? 'fx-flash' : ''
  const tension = run.tension
  return (
    <div ref={stageRef} className={`stage ${weather} ${shock} ${tension > 0.62 ? 'tense' : ''} act${run.act}`} style={{ aspectRatio: `${W} / ${H}`, ['--tension' as string]: tension.toFixed(2) }}>
      <Backdrop sc={sc} night={night} />
      <svg viewBox={`0 0 ${W} ${H}`} className="stage-svg" preserveAspectRatio="xMidYMid slice">
        <defs>
          <filter id="soft"><feDropShadow dx="0" dy="8" stdDeviation="10" floodOpacity="0.3" /></filter>
          <filter id="propshadow"><feDropShadow dx="2" dy="5" stdDeviation="3" floodOpacity="0.35" /></filter>
        </defs>
        {sc.places.map((pl) => {
          const glow = run.people.some((p) => p.place === pl.id && p.id === selected)
          const busy = active.has(pl.id)
          const tc = tint(pl.id)
          const icons = placeIcons(sc, pl.id)
          const lw = pl.name.length * 8.6 + 30 + icons.length * 20
          return (
            <g key={pl.id} className={`zone ${busy ? 'busy' : ''}`}>
              <rect x={pl.x} y={pl.y} width={pl.w} height={pl.h} rx="28" fill={`url(#zf-${pl.id})`} stroke={glow ? sc.theme.accent : busy ? mix(tc, '#ffffff', 0.4) : 'rgba(255,255,255,0.28)'} strokeWidth={glow ? 3.5 : busy ? 2.6 : 1.4} filter="url(#soft)" />
              <defs>
                <linearGradient id={`zf-${pl.id}`} x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0" stopColor={mix(tc, '#ffffff', 0.6)} stopOpacity={busy ? 0.3 : 0.16} />
                  <stop offset="1" stopColor={mix(tc, '#000000', 0.2)} stopOpacity={busy ? 0.34 : 0.2} />
                </linearGradient>
              </defs>
              <rect x={pl.x + 6} y={pl.y + 6} width={pl.w - 12} height={pl.h - 12} rx="23" fill="none" stroke="rgba(255,255,255,0.12)" strokeWidth="1" />
              <ellipse cx={pl.x + pl.w / 2} cy={pl.y + pl.h * 0.84} rx={pl.w * 0.42} ry={pl.h * 0.1} fill="rgba(0,0,0,0.14)" />
              <g transform={`translate(${pl.x + 14} ${pl.y + 12})`}>
                <rect width={lw} height="28" rx="14" fill="rgba(12,14,24,0.78)" stroke={mix(tc, '#ffffff', 0.2)} strokeOpacity="0.5" />
                <circle cx="14" cy="14" r="5" fill={tc} />
                <text x="25" y="19" fontSize="13.5" fontWeight="700" fill="#fff" style={{ fontFamily: 'Inter, sans-serif' }}>{pl.name}</text>
                {icons.map((ic, k) => <text key={k} x={pl.name.length * 8.6 + 30 + k * 20} y="19.5" fontSize="13">{ic}</text>)}
              </g>
              {pl.props.map((e, k) => {
                const n = pl.props.length
                const x = pl.x + (pl.w * (k + 0.5)) / n - 20 + (hash(pl.id + k) - 0.5) * 24
                const y = pl.y + 84 + hash(pl.id + 'y' + k) * Math.max(10, pl.h * 0.2)
                return <text key={k} className="prop" style={{ animationDelay: `${-hash(pl.id + 'd' + k) * 6}s` }} x={x} y={y} fontSize={34 + hash(e + k) * 16} filter="url(#propshadow)">{e}</text>
              })}
            </g>
          )
        })}
      </svg>
      {three && <canvas ref={castCanvas} className="cast3d" />}
      {run.people.map((p, i) => {
        const s = slots.get(p.id) ?? { x: W / 2, y: H / 2, k: 0 }
        const speaking = p.lastSay !== '' && now < p.bubbleUntil
        const gone = p.health <= 0
        const mv = moved.current.get(p.id)
        const walking = !!mv && mv.at > 0 && now - mv.at < 1700
        const working = !!p.lastAction && !['rested', 'talked', 'moved'].includes(p.lastAction) && now < p.emoteUntil + 2500
        const state: State = gone ? 'gone' : walking ? 'walking' : speaking ? 'talking' : working ? 'working' : p.thinking ? 'pondering' : p.energy < 22 ? 'tired' : moodOf(p)
        const to = speaking && p.lastSayTo ? run.people.find((q) => q.name === p.lastSayTo || p.lastSayTo.startsWith(q.name))?.id ?? null : null
        entries.push({ p, state, talking: speaking, color: COLORS[i % 4], selected: selected === p.id, faceTo: to })
        if (cast.current) { cast.current.entries = entries; cast.current.scenario = sc.id; cast.current.night = night / 0.55; cast.current.accent = sc.theme.accent }
        return (
          <div key={p.id} data-id={p.id} className={`actor ${selected === p.id ? 'sel' : ''} ${walking ? 'walking' : ''} ${three ? 'in3d' : ''}`}
            style={{ left: `${(s.x / W) * 100}%`, top: `${(s.y / H) * 100}%`, ['--c' as string]: COLORS[i % 4] }}
            onClick={() => onSelect(p.id)}>
            {speaking && (
              <div className={`bubble ${lastSpeaker === p.id ? 'latest' : ''}`} style={{ marginBottom: s.k % 2 ? 58 : 0 }}>
                {p.lastSayTo && <b>→ {p.lastSayTo} </b>}{p.lastSay}
              </div>
            )}
            {!speaking && p.thinking && <div className="thinking-dots"><span /><span /><span /></div>}
            {now < p.emoteUntil && <div className="emote">{p.emote}</div>}
            <div className="actor-body">{!three && <Figure p={p} scenario={sc.id} talking={speaking} walking={walking} working={working} gone={gone} />}</div>
            <div className="actor-name">{p.name}</div>
          </div>
        )
      })}
      <Links run={run} slots={slots} now={now} />
      <Floaters run={run} slots={slots} now={now} />
      {weather && <div className="rain-veil" />}
      {shownB && bAge < bannerLife(shownB) && <StageBanner run={run} b={shownB} age={bAge} />}
    </div>
  )
}

type Slots = Map<number, { x: number; y: number; k: number }>
const FIG = 180 // figure height in stage units

/** Icons of the stakes each place's work moves. */
function placeIcons(sc: Run['sc'], id: string): string[] {
  const set = new Set<string>()
  for (const m of sc.moves) if (m.at?.includes(id)) for (const [k, v] of Object.entries(m.effects)) {
    const def = sc.stats.find((x) => x.id === k)
    if (def && (def.good === 'high') === v > 0) set.add(def.icon)
  }
  return [...set].slice(0, 3)
}

/** Numbers and reactions that float up from people as things happen. */
function Floaters({ run, slots, now }: { run: Run; slots: Slots; now: number }) {
  const live: Fx[] = []
  const per = new Map<number, number>()
  for (let i = run.fx.length - 1; i >= 0; i--) {
    const f = run.fx[i]
    if (now - f.at > 2400) break
    const n = per.get(f.who ?? -1) ?? 0
    if (n >= 3) continue
    per.set(f.who ?? -1, n + 1)
    live.unshift(f)
  }
  const stack = new Map<number, number>()
  return (
    <>
      {live.map((f) => {
        const s = f.who !== undefined ? slots.get(f.who) : undefined
        if (!s) return null
        const k = stack.get(f.who!) ?? 0
        stack.set(f.who!, k + 1)
        return <div key={f.id} className={`floater ${f.kind}`} style={{ left: `${(s.x / W) * 100}%`, top: `${((s.y - FIG - 14 - k * 22) / H) * 100}%`, animationDelay: `${k * 0.12}s` }}>{f.text}</div>
      })}
    </>
  )
}

const LINK = { warm: ['#ff8fb1', '💗'], hot: ['#ff5252', '⚡'], help: ['#5ee39a', '🤝'], sway: ['#b39ddb', '🗣️'] } as const

/** Arcs between people for comfort, confrontation, help and persuasion. */
function Links({ run, slots, now }: { run: Run; slots: Slots; now: number }) {
  const live = run.links.filter((l) => now - l.at < 3600)
  if (!live.length) return null
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="links-svg" preserveAspectRatio="xMidYMid slice">
      {live.map((l, i) => {
        const a = slots.get(l.a), b = slots.get(l.b)
        if (!a || !b) return null
        const ax = a.x, ay = a.y - FIG * 0.55, bx = b.x, by = b.y - FIG * 0.55
        const mx = (ax + bx) / 2, my = Math.min(ay, by) - 60 - Math.abs(ax - bx) * 0.15
        const [c, icon] = LINK[l.kind]
        const t = (now - l.at) / 3600
        return (
          <g key={i} opacity={Math.min(1, (1 - t) * 3)}>
            <path d={`M${ax} ${ay} Q${mx} ${my} ${bx} ${by}`} stroke={c} strokeWidth="9" fill="none" opacity="0.18" strokeLinecap="round" />
            <path d={`M${ax} ${ay} Q${mx} ${my} ${bx} ${by}`} stroke={c} strokeWidth="3.5" fill="none" strokeLinecap="round" className={`link ${l.kind}`} />
            <circle cx={(ax + 2 * mx + bx) / 4} cy={(ay + 2 * my + by) / 4} r="17" fill="rgba(12,14,24,0.85)" stroke={c} strokeWidth="2" />
            <text x={(ax + 2 * mx + bx) / 4} y={(ay + 2 * my + by) / 4 + 6} fontSize="17" textAnchor="middle">{icon}</text>
          </g>
        )
      })}
    </svg>
  )
}

function bannerLife(b: Banner): number {
  return b.kind === 'vote' ? 6500 : b.kind === 'dilemma' ? 6000 : b.kind === 'act' ? 3800 : 5200
}

/** Cinematic cards for acts, events, secrets, choices and votes. */
function StageBanner({ run, b, age }: { run: Run; b: Banner; age: number }) {
  const out = age > bannerLife(b) - 500
  if (b.kind === 'act') {
    return (
      <div className={`act-card ${out ? 'out' : ''}`} key={`${b.at}act`}>
        <div className="bars top" /><div className="bars bottom" />
        <div className="act-title">{b.title}</div>
        <div className="act-sub">{b.text}</div>
      </div>
    )
  }
  const finale = b.kind === 'dilemma' && run.sc.dilemmas?.some((d) => d.finale && d.title === b.title)
  const head = { event: '⚡ Something happens', dilemma: finale ? '⚖️ The final choice' : '⚖️ A choice', vote: '🗳️ The group decides', reveal: '🤫 A secret', act: '' }[b.kind]
  return (
    <div className={`stage-banner ${b.kind} ${out ? 'out' : ''}`} key={`${b.at}${b.kind}`}>
      <div className="sb-kind">{head}</div>
      {b.kind !== 'event' && <div className="sb-title">{b.title}</div>}
      <div className="sb-text">{b.text}</div>
      {b.kind === 'dilemma' && b.chips && <div className="sb-options">{b.chips.map((c) => <span key={c}>{c}</span>)}</div>}
      {b.kind === 'vote' && b.votes && (
        <div className="sb-votes">
          {b.votes.map((v) => (
            <div key={v.option} className={`sb-vote ${v.chosen ? 'chosen' : ''}`}>
              <span className="sb-label">{v.chosen ? '✓ ' : ''}{v.label}</span>
              <span className="sb-faces">{v.ids.map((id) => { const p = run.people.find((q) => q.id === id)!; const i = run.people.indexOf(p); return <Portrait key={id} p={p} size={26} color={COLORS[i % 4]} /> })}{!v.ids.length && <i>no votes</i>}</span>
            </div>
          ))}
        </div>
      )}
      {b.chips && b.kind !== 'dilemma' && b.chips.length > 0 && <div className="sb-chips">{b.chips.map((c, k) => <span key={k} className={b.tones?.[k] ?? 'up'}>{c}</span>)}</div>}
    </div>
  )
}

/** A round head-and-shoulders portrait. */
export function Portrait({ p, size = 44, color }: { p: Person; size?: number; color: string }) {
  return (
    <svg viewBox="22 4 76 76" width={size} height={size} style={{ flex: 'none', borderRadius: '50%', background: `radial-gradient(circle at 50% 35%, ${mix(color, '#ffffff', 0.3)}, ${mix(color, '#0b1020', 0.5)})`, filter: p.health <= 0 ? 'grayscale(1)' : undefined }}>
      <path d="M20 84 Q24 66 60 64 Q96 66 100 84 Z" fill={p.look.top} stroke="#1e1a26" strokeWidth="1.5" />
      <path d="M53 58 L67 58 L66 68 L54 68 Z" fill="#00000030" />
      <g transform="translate(0 4)"><Face p={p} uid={`pt${p.id}s${size}`} /></g>
    </svg>
  )
}
