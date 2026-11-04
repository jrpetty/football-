// A painted face for every person. Built from their inherited look and read
// live from their state: brows from temperament, mouth from mood, tired eyes
// when exhausted, a flush when sick, grey hair and lines with age, era-
// appropriate clothes. Small enough to sit in a list, rich enough to inspect.

import { game } from '../game.ts'
import { mind } from '../ai/mind.ts'
import { eraOf } from '../sim/tech.ts'
import type { Agent } from '../sim/types.ts'

const SKIN = ['#f1c9a5', '#e0ac7e', '#c68a5a', '#9c6b43', '#7a4f30', '#5a3a24']
const HAIR = ['#2b1d14', '#4a2f1d', '#7a4a24', '#b07a3a', '#d8b46a', '#1a1a1a', '#8a3a24']

export function Portrait({ a, size = 40, ring = true }: { a: Agent; size?: number; ring?: boolean }) {
  const w = game.world
  const L = a.look
  const t = w.tribeById(a.tribeId)
  const color = t?.color ?? '#999'
  const age = a.alive ? w.age(a) : (a.deathT - a.birth) / (12 * 1440)
  const child = age < 14
  const elder = age > 55
  const era = t ? eraOf(w.techs(t.id)) : 0
  const skin = SKIN[L.skin % 6]
  const hair = age > 62 ? '#e3e3e3' : age > 50 ? mix(HAIR[L.hair % 7], '#cfcfcf', 0.5) : HAIR[L.hair % 7]
  const mood = a.alive ? a.mood : 40
  const tr = a.traits
  // brows: stern (low agreeableness) slope inward; worried (high neuroticism) slope up in the middle
  const stern = (0.5 - tr.agreeableness) * 6
  const worry = (tr.neuroticism - 0.5) * 5 + (mood < 35 ? 2 : 0)
  const smile = (mood - 50) / 50 // -1 .. 1
  const tired = a.alive && a.energy < 30
  const sick = a.alive && a.sick > 0
  const garment = era === 0 ? '#8a6a44' : era <= 4 ? color : era <= 6 ? L.accent : '#2f3a4a'
  const faceW = child ? 15 : 13 * (0.94 + L.build * 0.06)
  const faceH = child ? 15 : 16.5
  const id = `p${a.id}`
  const isMind = mind.isMind(a)
  return (
    <svg width={size} height={size} viewBox="0 0 64 64" style={{ flex: 'none', opacity: a.alive ? 1 : 0.55, filter: a.alive ? undefined : 'grayscale(0.85)' }}>
      <defs>
        <radialGradient id={`${id}bg`} cx="50%" cy="35%" r="70%">
          <stop offset="0" stopColor={mix(color, '#ffffff', 0.25)} />
          <stop offset="1" stopColor={mix(color, '#0b1020', 0.55)} />
        </radialGradient>
        <clipPath id={`${id}c`}><circle cx="32" cy="32" r="30" /></clipPath>
      </defs>
      <circle cx="32" cy="32" r="31" fill={`url(#${id}bg)`} stroke={ring ? (isMind ? '#b9a8ff' : 'rgba(255,255,255,0.25)') : 'none'} strokeWidth={isMind ? 2.5 : 1} />
      <g clipPath={`url(#${id}c)`}>
        {/* long hair behind */}
        {(L.style === 1 || L.style === 3) && <path d={`M${32 - faceW - 3} 26 Q32 4 ${32 + faceW + 3} 26 L${32 + faceW + 4} ${L.style === 1 ? 52 : 44} L${32 - faceW - 4} ${L.style === 1 ? 52 : 44} Z`} fill={hair} />}
        {/* shoulders & clothes */}
        <path d="M8 64 Q10 47 32 46 Q54 47 56 64 Z" fill={garment} />
        <path d="M22 47 Q32 54 42 47" fill="none" stroke={era === 0 ? L.accent : mix(garment, '#ffffff', 0.35)} strokeWidth="2.2" />
        {era >= 7 && <path d="M29 48 L32 58 L35 48" fill={L.accent} />}
        <rect x="28" y="38" width="8" height="9" rx="3" fill={mix(skin, '#000000', 0.08)} />
        {/* ears */}
        <ellipse cx={32 - faceW} cy="30" rx="2.4" ry="3.4" fill={mix(skin, '#000000', 0.06)} />
        <ellipse cx={32 + faceW} cy="30" rx="2.4" ry="3.4" fill={mix(skin, '#000000', 0.06)} />
        {/* face */}
        <ellipse cx="32" cy="29" rx={faceW} ry={faceH} fill={skin} />
        {sick && <ellipse cx="32" cy="29" rx={faceW} ry={faceH} fill="#9bd36a" opacity="0.18" />}
        {(tr.extraversion > 0.65 || mood > 75) && <><circle cx={32 - faceW * 0.55} cy="35" r="2.6" fill="#ff7b7b" opacity="0.25" /><circle cx={32 + faceW * 0.55} cy="35" r="2.6" fill="#ff7b7b" opacity="0.25" /></>}
        {L.marks === 2 && [0, 1, 2, 3, 4, 5].map((k) => <circle key={k} cx={26 + (k % 3) * 2.2 + (k > 2 ? 9 : 0)} cy={32 + (k % 2)} r="0.55" fill="#8a5a3a" opacity="0.7" />)}
        {L.marks === 1 && <path d={`M${32 - faceW + 2} 33 L${32 + faceW - 2} 33`} stroke={color} strokeWidth="2" opacity="0.8" />}
        {L.marks === 3 && <path d="M38 22 L41 31" stroke="#9a4a4a" strokeWidth="1.1" opacity="0.8" />}
        {/* eyes */}
        {[-1, 1].map((sd) => (
          <g key={sd}>
            <ellipse cx={32 + sd * 5} cy="29" rx="2.4" ry={tired ? 1.1 : 2.1} fill="#fff" />
            <circle cx={32 + sd * 5 + (a.dir > 0 ? 0.4 : -0.4)} cy={tired ? 29.3 : 29.2} r="1.25" fill="#2a1d14" />
            {tired && <path d={`M${32 + sd * 5 - 2.6} 28.3 Q${32 + sd * 5} 27.2 ${32 + sd * 5 + 2.6} 28.3`} stroke={mix(skin, '#000', 0.3)} strokeWidth="0.9" fill="none" />}
            {/* brows */}
            <path d={`M${32 + sd * 2.6} ${24.8 + sd * 0 + stern * 0.25 - worry * 0.35} L${32 + sd * 7.6} ${24.8 - stern * 0.25 + worry * 0.15}`} stroke={hair === '#e3e3e3' ? '#9a9a9a' : hair} strokeWidth="1.5" strokeLinecap="round" />
          </g>
        ))}
        {/* nose & mouth */}
        <path d="M32 30.5 Q30.8 34 32.4 34.6" stroke={mix(skin, '#000', 0.25)} strokeWidth="0.9" fill="none" />
        <path d={`M${28.2} ${38.2 - smile * 0.6} Q32 ${38.4 + smile * 3.2} ${35.8} ${38.2 - smile * 0.6}`} stroke="#7a3b32" strokeWidth="1.4" fill={smile > 0.45 ? '#fff' : 'none'} strokeLinecap="round" />
        {elder && <><path d="M24 33.5 Q25.5 35 27 34.2" stroke={mix(skin, '#000', 0.22)} strokeWidth="0.6" fill="none" /><path d="M40 34.2 Q38.5 35 37 33.5" stroke={mix(skin, '#000', 0.22)} strokeWidth="0.6" fill="none" /><path d="M27 21 L37 21" stroke={mix(skin, '#000', 0.15)} strokeWidth="0.5" /></>}
        {/* beard */}
        {L.beard && !child && <path d={`M${32 - faceW + 1} 32 Q${32 - faceW + 2} 46 32 47 Q${32 + faceW - 2} 46 ${32 + faceW - 1} 32 Q32 41 ${32 - faceW + 1} 32`} fill={hair} />}
        {/* hair on top */}
        {L.style !== 4 && <path d={`M${32 - faceW - 1} 27 Q${32 - faceW} 10 32 10.5 Q${32 + faceW} 10 ${32 + faceW + 1} 27 Q${32 + 4} 17 32 18 Q${28} 17 ${32 - faceW - 1} 27`} fill={hair} />}
        {L.style === 4 && <path d={`M${32 - faceW + 1} 22 Q32 9 ${32 + faceW - 1} 22`} fill={hair} opacity="0.35" />}
        {L.style === 2 && <circle cx="32" cy="9" r="5.5" fill={hair} />}
        {L.style === 5 && [-8, -4, 0, 4, 8].map((k) => <circle key={k} cx={32 + k} cy={12 + Math.abs(k) * 0.35} r="4.2" fill={hair} />)}
        {L.style === 3 && <><path d={`M${32 - faceW - 1} 30 L${32 - faceW - 2} 50`} stroke={hair} strokeWidth="3.5" strokeLinecap="round" /><path d={`M${32 + faceW + 1} 30 L${32 + faceW + 2} 50`} stroke={hair} strokeWidth="3.5" strokeLinecap="round" /></>}
        {/* leader's circlet */}
        {t?.leaderId === a.id && <path d="M20 15 L22 8 L27 13 L32 6 L37 13 L42 8 L44 15 Z" fill="#ffd34a" stroke="#c99a1a" strokeWidth="0.8" />}
      </g>
      {a.aiPending && <circle cx="54" cy="10" r="5" fill="#b9a8ff"><animate attributeName="opacity" values="1;0.3;1" dur="1s" repeatCount="indefinite" /></circle>}
    </svg>
  )
}

function mix(a: string, b: string, t: number): string {
  const pa = parseInt(a.slice(1), 16), pb = parseInt(b.length === 4 ? b.slice(1).split('').map((c) => c + c).join('') : b.slice(1), 16)
  const r = Math.round(((pa >> 16) & 255) * (1 - t) + ((pb >> 16) & 255) * t)
  const g = Math.round(((pa >> 8) & 255) * (1 - t) + ((pb >> 8) & 255) * t)
  const bl = Math.round((pa & 255) * (1 - t) + (pb & 255) * t)
  return `#${((1 << 24) | (r << 16) | (g << 8) | bl).toString(16).slice(1)}`
}
