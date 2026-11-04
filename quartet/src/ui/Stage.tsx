// The scene: the places of the scenario painted as a stage, the four people
// standing where they are (walking smoothly when they move), speech bubbles,
// emotes, and a light that follows the time of day.

import type { Run } from '../engine/engine.ts'
import type { Person } from '../engine/types.ts'
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
  return (
    <div ref={stageRef} className="stage" style={{ aspectRatio: `${W} / ${H}` }}>
      <Backdrop sc={sc} night={night} />
      <svg viewBox={`0 0 ${W} ${H}`} className="stage-svg" preserveAspectRatio="xMidYMid slice">
        <defs>
          <filter id="soft"><feDropShadow dx="0" dy="8" stdDeviation="10" floodOpacity="0.3" /></filter>
          <filter id="propshadow"><feDropShadow dx="2" dy="5" stdDeviation="3" floodOpacity="0.35" /></filter>
        </defs>
        {sc.places.map((pl) => {
          const glow = run.people.some((p) => p.place === pl.id && p.id === selected)
          const tc = tint(pl.id)
          return (
            <g key={pl.id}>
              <rect x={pl.x} y={pl.y} width={pl.w} height={pl.h} rx="26" fill={mix(tc, '#ffffff', 0.55)} fillOpacity="0.16" stroke={glow ? sc.theme.accent : 'rgba(255,255,255,0.45)'} strokeWidth={glow ? 3.5 : 1.6} strokeDasharray={glow ? undefined : '10 8'} filter="url(#soft)" />
              <ellipse cx={pl.x + pl.w / 2} cy={pl.y + pl.h * 0.84} rx={pl.w * 0.42} ry={pl.h * 0.1} fill="rgba(0,0,0,0.12)" />
              <g transform={`translate(${pl.x + 14} ${pl.y + 12})`}>
                <rect width={pl.name.length * 8.6 + 22} height="26" rx="13" fill="rgba(12,14,24,0.72)" />
                <circle cx="13" cy="13" r="4.5" fill={tc} />
                <text x="23" y="18" fontSize="13.5" fontWeight="700" fill="#fff" style={{ fontFamily: 'Inter, sans-serif' }}>{pl.name}</text>
              </g>
              {pl.props.map((e, k) => {
                const n = pl.props.length
                const x = pl.x + (pl.w * (k + 0.5)) / n - 20 + (hash(pl.id + k) - 0.5) * 24
                const y = pl.y + 84 + hash(pl.id + 'y' + k) * Math.max(10, pl.h * 0.2)
                return <text key={k} x={x} y={y} fontSize={34 + hash(e + k) * 16} filter="url(#propshadow)">{e}</text>
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
