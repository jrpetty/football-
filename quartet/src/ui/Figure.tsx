// A whole person, drawn. Faces read their state: brows from temperament and
// stress, mouth from mood (and open while speaking), heavy lids when
// exhausted, a pale-green tinge when hurt. Posture slumps as energy drains.

import type { Person } from '../engine/types.ts'

const SKIN = ['#f3d0b0', '#e3b48a', '#c99363', '#a06d45', '#7c5133', '#5b3b25']
const HAIR = ['#2b1d14', '#4a2f1d', '#7a4a24', '#b07a3a', '#e0c07a', '#141414', '#9a3b26']

export function mix(a: string, b: string, t: number): string {
  const ex = (h: string) => (h.length === 4 ? h.slice(1).split('').map((c) => c + c).join('') : h.slice(1))
  const pa = parseInt(ex(a), 16), pb = parseInt(ex(b), 16)
  const r = Math.round(((pa >> 16) & 255) * (1 - t) + ((pb >> 16) & 255) * t)
  const g = Math.round(((pa >> 8) & 255) * (1 - t) + ((pb >> 8) & 255) * t)
  const bl = Math.round((pa & 255) * (1 - t) + (pb & 255) * t)
  return `#${((1 << 24) | (r << 16) | (g << 8) | bl).toString(16).slice(1)}`
}

export function Face({ p, speaking = false }: { p: Person; speaking?: boolean }) {
  const L = p.look
  const skin = SKIN[L.skin % 6]
  const hair = p.age > 62 ? '#dcdcdc' : p.age > 52 ? mix(HAIR[L.hair % 7], '#c8c8c8', 0.45) : HAIR[L.hair % 7]
  const smile = (p.mood - 50) / 50
  const stern = (0.5 - p.traits.agreeableness) * 5
  const worry = (p.stress - 50) / 12 + (p.traits.neuroticism - 0.5) * 3
  const tired = p.energy < 28
  const hurt = p.health < 50
  const fw = 13 * (0.95 + L.build * 0.05)
  return (
    <g>
      {(L.style === 1 || L.style === 3) && <path d={`M${50 - fw - 3} 30 Q50 4 ${50 + fw + 3} 30 L${50 + fw + 4} ${L.style === 1 ? 52 : 46} L${50 - fw - 4} ${L.style === 1 ? 52 : 46} Z`} fill={hair} />}
      <ellipse cx={50 - fw} cy="31" rx="2.4" ry="3.4" fill={mix(skin, '#000', 0.07)} />
      <ellipse cx={50 + fw} cy="31" rx="2.4" ry="3.4" fill={mix(skin, '#000', 0.07)} />
      <ellipse cx="50" cy="30" rx={fw} ry="16" fill={skin} />
      {hurt && <ellipse cx="50" cy="30" rx={fw} ry="16" fill="#a5d6a7" opacity="0.22" />}
      {p.stress > 75 && <circle cx={50 + fw - 2} cy="20" r="1.6" fill="#9fd3ff" opacity="0.9" />}
      {(p.mood > 72 || p.traits.extraversion > 0.75) && <><circle cx={50 - fw * 0.55} cy="36" r="2.6" fill="#ff8a80" opacity="0.28" /><circle cx={50 + fw * 0.55} cy="36" r="2.6" fill="#ff8a80" opacity="0.28" /></>}
      {[-1, 1].map((sd) => (
        <g key={sd}>
          <ellipse cx={50 + sd * 5} cy="30" rx="2.5" ry={tired ? 1 : 2.2} fill="#fff" />
          <circle cx={50 + sd * 5} cy={tired ? 30.3 : 30.2} r="1.3" fill="#2a1d14" />
          <path d={`M${50 + sd * 2.4} ${25.5 + stern * 0.25 - worry * 0.3} L${50 + sd * 7.8} ${25.3 - stern * 0.25 + worry * 0.15}`} stroke={hair} strokeWidth="1.5" strokeLinecap="round" />
        </g>
      ))}
      {L.glasses && <g fill="none" stroke="#333" strokeWidth="0.9"><circle cx="45" cy="30" r="3.6" /><circle cx="55" cy="30" r="3.6" /><path d="M48.6 30 L51.4 30" /></g>}
      <path d="M50 31.5 Q48.8 35 50.4 35.6" stroke={mix(skin, '#000', 0.25)} strokeWidth="0.9" fill="none" />
      {speaking ? (
        <ellipse cx="50" cy="39.6" rx="3" ry={1.6 + Math.max(0, smile) * 0.8} fill="#6d2f28" />
      ) : (
        <path d={`M46.2 ${39.2 - smile * 0.6} Q50 ${39.4 + smile * 3.2} 53.8 ${39.2 - smile * 0.6}`} stroke="#7a3b32" strokeWidth="1.4" fill={smile > 0.45 ? '#fff' : 'none'} strokeLinecap="round" />
      )}
      {p.age > 55 && <path d="M43 22 L57 22" stroke={mix(skin, '#000', 0.15)} strokeWidth="0.5" />}
      {L.beard && <path d={`M${50 - fw + 1} 33 Q${50 - fw + 2} 47 50 48 Q${50 + fw - 2} 47 ${50 + fw - 1} 33 Q50 42 ${50 - fw + 1} 33`} fill={hair} />}
      {L.style !== 4 && <path d={`M${50 - fw - 1} 28 Q${50 - fw} 11 50 11.5 Q${50 + fw} 11 ${50 + fw + 1} 28 Q54 18 50 19 Q46 18 ${50 - fw - 1} 28`} fill={hair} />}
      {L.style === 4 && <path d={`M${50 - fw + 1} 23 Q50 10 ${50 + fw - 1} 23`} fill={hair} opacity="0.35" />}
      {L.style === 2 && <circle cx="50" cy="10" r="5.5" fill={hair} />}
      {L.style === 5 && [-8, -4, 0, 4, 8].map((k) => <circle key={k} cx={50 + k} cy={13 + Math.abs(k) * 0.35} r="4.3" fill={hair} />)}
    </g>
  )
}

/** Full body, 100×160. */
export function Figure({ p, speaking, working, gone }: { p: Person; speaking: boolean; working: boolean; gone: boolean }) {
  const L = p.look
  const skin = SKIN[L.skin % 6]
  const top = L.top
  const slump = p.energy < 25 ? 4 : 0
  const w = L.build
  return (
    <svg viewBox="0 0 100 160" width="100%" height="100%" style={{ overflow: 'visible', filter: gone ? 'grayscale(1) opacity(0.4)' : undefined }}>
      <ellipse cx="50" cy="156" rx={22 * w} ry="4" fill="rgba(0,0,0,0.25)" />
      <g transform={`translate(0 ${slump}) rotate(${slump ? 3 : 0} 50 150)`}>
        {/* legs */}
        <path d={`M${43 - 2 * w} 104 L${41 - 2 * w} 152 L${48} 152 L50 110 L52 152 L${59 + 2 * w} 152 L${57 + 2 * w} 104 Z`} fill="#2f3646" />
        <rect x={39 - 2 * w} y="148" width="10" height="5" rx="2" fill="#1d1d22" />
        <rect x={51 + 2 * w} y="148" width="10" height="5" rx="2" fill="#1d1d22" />
        {/* torso */}
        <path d={`M${50 - 17 * w} 58 Q50 50 ${50 + 17 * w} 58 L${50 + 14 * w} 108 L${50 - 14 * w} 108 Z`} fill={top} />
        <path d={`M44 54 L50 64 L56 54`} fill={mix(top, '#fff', 0.35)} />
        {/* arms */}
        <path d={`M${50 - 16 * w} 60 Q${50 - 24 * w} 82 ${50 - 20 * w} 104`} stroke={top} strokeWidth={9 * w} fill="none" strokeLinecap="round" />
        <circle cx={50 - 20 * w} cy="106" r="4" fill={skin} />
        {working ? (
          <>
            <path d={`M${50 + 16 * w} 60 Q${50 + 30 * w} 66 ${50 + 30 * w} 46`} stroke={top} strokeWidth={9 * w} fill="none" strokeLinecap="round">
              <animateTransform attributeName="transform" type="rotate" values={`0 ${50 + 16 * w} 60;14 ${50 + 16 * w} 60;0 ${50 + 16 * w} 60`} dur="0.8s" repeatCount="indefinite" />
            </path>
          </>
        ) : (
          <>
            <path d={`M${50 + 16 * w} 60 Q${50 + 24 * w} 82 ${50 + 20 * w} 104`} stroke={top} strokeWidth={9 * w} fill="none" strokeLinecap="round" />
            <circle cx={50 + 20 * w} cy="106" r="4" fill={skin} />
          </>
        )}
        <rect x="45" y="44" width="10" height="10" rx="3" fill={mix(skin, '#000', 0.08)} />
        <g transform={speaking ? 'translate(0 -1)' : undefined}><Face p={p} speaking={speaking} /></g>
      </g>
    </svg>
  )
}
