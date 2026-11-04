// The scene: the places of the scenario painted as a stage, the four people
// standing where they are (walking smoothly when they move), speech bubbles,
// emotes, and a light that follows the time of day.

import type { Run } from '../engine/engine.ts'
import type { Person } from '../engine/types.ts'
import { Figure, Face, mix } from './Figure.tsx'

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
  return (
    <div className="stage" style={{ aspectRatio: `${W} / ${H}` }}>
      <svg viewBox={`0 0 ${W} ${H}`} className="stage-svg" preserveAspectRatio="xMidYMid slice">
        <defs>
          <linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor={sc.theme.sky[0]} />
            <stop offset="1" stopColor={sc.theme.sky[1]} />
          </linearGradient>
          <filter id="soft"><feDropShadow dx="0" dy="6" stdDeviation="8" floodOpacity="0.25" /></filter>
        </defs>
        <rect width={W} height={H} fill="url(#sky)" />
        <rect y={H * 0.48} width={W} height={H * 0.52} fill={mix(sc.theme.ground, '#000000', 0.12)} />
        {sc.places.map((pl) => {
          const glow = run.people.some((p) => p.place === pl.id && p.id === selected)
          return (
            <g key={pl.id} filter="url(#soft)">
              <rect x={pl.x} y={pl.y} width={pl.w} height={pl.h} rx="22" fill={mix(mix(sc.theme.ground, tint(pl.id), 0.18), "#ffffff", 0.06)} stroke={glow ? sc.theme.accent : 'rgba(255,255,255,0.35)'} strokeWidth={glow ? 3 : 1.5} />
              <rect x={pl.x + 8} y={pl.y + pl.h * 0.62} width={pl.w - 16} height={pl.h * 0.38 - 8} rx="16" fill="rgba(0,0,0,0.08)" />
              <rect x={pl.x} y={pl.y} width={pl.w} height={30} rx="14" fill={mix(sc.theme.ground, '#000000', 0.18)} opacity="0.7" />
              <text x={pl.x + 16} y={pl.y + 21} fontSize="15" fontWeight="700" fill="#fff" style={{ fontFamily: 'Inter, sans-serif' }}>{pl.name}</text>
              {pl.props.map((e, k) => {
                // props stand along the back wall, spread evenly with a little jitter
                const n = pl.props.length
                const x = pl.x + (pl.w * (k + 0.5)) / n - 16 + (hash(pl.id + k) - 0.5) * 20
                const y = pl.y + 70 + hash(pl.id + 'y' + k) * Math.max(10, pl.h * 0.22)
                return <text key={k} x={x} y={y} fontSize={26 + hash(e + k) * 14} opacity="0.95">{e}</text>
              })}
            </g>
          )
        })}
        {night > 0 && <rect width={W} height={H} fill="#0a1030" opacity={night} style={{ transition: 'opacity 2s' }} pointerEvents="none" />}
      </svg>
      {run.people.map((p, i) => {
        const s = slots.get(p.id) ?? { x: W / 2, y: H / 2, k: 0 }
        const speaking = p.lastSay !== '' && now < p.bubbleUntil
        const gone = p.health <= 0
        return (
          <div key={p.id} className={`actor ${selected === p.id ? 'sel' : ''} ${p.thinking ? 'thinking' : ''}`}
            style={{ left: `${(s.x / W) * 100}%`, top: `${(s.y / H) * 100}%`, ['--c' as string]: COLORS[i % 4] }}
            onClick={() => onSelect(p.id)}>
            {speaking && (
              <div className={`bubble ${lastSpeaker === p.id ? 'latest' : ''}`} style={{ marginBottom: s.k % 2 ? 58 : 0 }}>
                {p.lastSayTo && <b>→ {p.lastSayTo} </b>}{p.lastSay}
              </div>
            )}
            {!speaking && p.thinking && <div className="thinking-dots"><span /><span /><span /></div>}
            {now < p.emoteUntil && <div className="emote">{p.emote}</div>}
            <div className="actor-body"><Figure p={p} speaking={speaking} working={!!p.lastAction && p.lastAction !== 'rested' && p.lastAction !== 'talked'} gone={gone} /></div>
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
    <svg viewBox="18 0 64 64" width={size} height={size} style={{ flex: 'none', borderRadius: '50%', background: `radial-gradient(circle at 50% 35%, ${mix(color, '#ffffff', 0.3)}, ${mix(color, '#0b1020', 0.5)})`, filter: p.health <= 0 ? 'grayscale(1)' : undefined }}>
      <path d="M24 64 Q26 48 50 47 Q74 48 76 64 Z" fill={p.look.top} />
      <rect x="45" y="42" width="10" height="8" rx="3" fill="#00000022" />
      <Face p={p} />
    </svg>
  )
}
