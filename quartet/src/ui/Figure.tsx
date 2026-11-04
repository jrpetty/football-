// Quartet's people: illustrated, jointed and alive.
//
// Each figure is an SVG rig — hips, knees, shoulders, elbows, neck — whose
// joints are driven by CSS animations chosen from what the person is doing
// and feeling: walking, talking with their hands, working, thinking, idling
// (breathing, weight shifts, blinking), slumped when exhausted, bouncing when
// happy, tense when angry. Outfits come from their job (scrubs, chef's whites,
// suits, hoodies, parkas…), with shading, outlines, hands and shoes.

import type { Person } from '../engine/types.ts'

const SKIN = ['#f3d0b0', '#e3b48a', '#c99363', '#a06d45', '#7c5133', '#5b3b25']
const HAIR = ['#2b1d14', '#4a2f1d', '#7a4a24', '#b07a3a', '#e0c07a', '#141414', '#9a3b26']
const INK = '#1e1a26'

export function mix(a: string, b: string, t: number): string {
  const ex = (h: string) => (h.length === 4 ? h.slice(1).split('').map((c) => c + c).join('') : h.slice(1))
  const pa = parseInt(ex(a), 16), pb = parseInt(ex(b), 16)
  const r = Math.round(((pa >> 16) & 255) * (1 - t) + ((pb >> 16) & 255) * t)
  const g = Math.round(((pa >> 8) & 255) * (1 - t) + ((pb >> 8) & 255) * t)
  const bl = Math.round((pa & 255) * (1 - t) + (pb & 255) * t)
  return `#${((1 << 24) | (r << 16) | (g << 8) | bl).toString(16).slice(1)}`
}

type OutfitKind = 'suit' | 'scrubs' | 'labcoat' | 'chef' | 'hoodie' | 'casual' | 'dress' | 'uniform' | 'parka' | 'overalls' | 'gown' | 'apron'

interface Outfit {
  kind: OutfitKind
  top: string
  bottom: string
  shoes: string
  accent: string
  hat?: 'chef' | 'beanie' | 'cap' | 'veil'
  tie?: string
}

export function outfitFor(p: Person, scenarioId: string): Outfit {
  const t = p.role.title.toLowerCase()
  const top = p.look.top
  const cold = scenarioId === 'antarctic' || scenarioId === 'snowbound' || scenarioId === 'lifeboat'
  if (/bride/.test(t)) return { kind: 'gown', top: '#fbf8f2', bottom: '#fbf8f2', shoes: '#e8e1d4', accent: '#f3c6d3', hat: 'veil' }
  if (/chef|baker|pastry|sous/.test(t)) return { kind: 'chef', top: '#f6f4ee', bottom: '#2c2c34', shoes: '#1d1d22', accent: top, hat: /head chef|pastry|baker/.test(t) ? 'chef' : undefined }
  if (/nurse|paramedic|junior doctor|charge/.test(t)) return { kind: 'scrubs', top: /paramedic/.test(t) ? '#2e7d32' : /charge/.test(t) ? '#1a3e72' : '#3f8fa8', bottom: /paramedic/.test(t) ? '#2e7d32' : /charge/.test(t) ? '#1a3e72' : '#3f8fa8', shoes: '#f2f2f2', accent: '#ffffff' }
  if (/consultant|doctor|glaciologist|scientist|psycholog/.test(t)) return cold ? { kind: 'parka', top: '#d84315', bottom: '#37474f', shoes: '#3e2723', accent: '#ffe0b2', hat: 'beanie' } : { kind: 'labcoat', top: '#f7f7f5', bottom: '#3d4656', shoes: '#2a2a30', accent: top }
  if (/pilot|deckhand|police|firefighter|mountain leader/.test(t)) return { kind: 'uniform', top: /police/.test(t) ? '#283a5c' : /firefighter/.test(t) ? '#4e342e' : '#f1f1ec', bottom: '#263042', shoes: '#151515', accent: '#d4af37', hat: /pilot|deckhand/.test(t) ? 'cap' : undefined }
  if (/mechanic|electrician|driver|apprentice/.test(t)) return { kind: 'overalls', top: mix(top, '#1d3557', 0.5), bottom: mix(top, '#1d3557', 0.5), shoes: '#3b2a1e', accent: '#f4a261' }
  if (/student|gamer|youngest|joker|musician|influencer|trainer|volunteer|growth|designer|teen/.test(t)) return cold ? { kind: 'parka', top, bottom: '#2f3646', shoes: '#3b3b3b', accent: '#fafafa', hat: 'beanie' } : { kind: 'hoodie', top, bottom: /trainer/.test(t) ? '#222' : '#3a5a8c', shoes: '#f2f2f2', accent: mix(top, '#ffffff', 0.4) }
  if (/ceo|cto|executive|editor|lawyer|delegate|candidate|manager|accountant|eldest|groom|successful|entrepreneur|station leader|owner|director/.test(t)) return cold ? { kind: 'parka', top: '#1565c0', bottom: '#263238', shoes: '#212121', accent: '#bbdefb', hat: 'beanie' } : { kind: 'suit', top: /groom/.test(t) ? '#1d1f2b' : mix(top, '#1c2233', 0.65), bottom: /groom/.test(t) ? '#1d1f2b' : mix(top, '#1c2233', 0.7), shoes: '#141414', accent: '#f4f4f4', tie: mix(top, '#7b1f2b', 0.3) }
  if (/mother|grandmother|teacher|maid|novelist|hiker|engineer/.test(t) && p.gender === 'woman' && !cold) return { kind: 'dress', top, bottom: top, shoes: '#5d4037', accent: mix(top, '#ffffff', 0.45) }
  if (cold) return { kind: 'parka', top, bottom: '#2f3646', shoes: '#3e2723', accent: '#fafafa', hat: 'beanie' }
  return { kind: 'casual', top, bottom: '#3d4f7a', shoes: '#2b2b2b', accent: mix(top, '#ffffff', 0.35) }
}

export type Mood = 'happy' | 'sad' | 'angry' | 'scared' | 'calm'

export function moodOf(p: Person): Mood {
  const f = p.feeling.toLowerCase()
  if (/angr|furious|irritat|annoy|bitter|resent|frustrat/.test(f)) return 'angry'
  if (/scar|afraid|terrif|panic|anxious|nervous|fear/.test(f)) return 'scared'
  if (/sad|grief|hopeless|exhaust|defeat|lonely|guilt|ashamed|numb|despair/.test(f) || p.mood < 28) return 'sad'
  if (/happy|hope|relie|elat|excit|proud|joy|warm|grate|content|amused/.test(f) || p.mood > 72) return 'happy'
  if (p.stress > 75) return 'scared'
  return 'calm'
}

/** The face alone (also used for portraits). Coordinates: head centred at (60, 40). */
export function Face({ p, talking = false, uid }: { p: Person; talking?: boolean; uid: string }) {
  const L = p.look
  const skin = SKIN[L.skin % 6]
  const hair = p.age > 62 ? '#dedede' : p.age > 52 ? mix(HAIR[L.hair % 7], '#c8c8c8', 0.45) : HAIR[L.hair % 7]
  const mood = moodOf(p)
  const smile = mood === 'happy' ? 1 : mood === 'sad' ? -0.8 : mood === 'angry' ? -0.5 : mood === 'scared' ? -0.3 : (p.mood - 50) / 70
  const browIn = mood === 'angry' ? 4 : 0
  const browUp = mood === 'scared' || mood === 'sad' ? 3 : 0
  const tired = p.energy < 25
  const blinkBegin = `${(p.id * 1.37) % 4}s`
  const fw = 17
  return (
    <g>
      <defs>
        <radialGradient id={`${uid}sk`} cx="40%" cy="35%" r="75%">
          <stop offset="0" stopColor={mix(skin, '#ffffff', 0.18)} />
          <stop offset="1" stopColor={mix(skin, '#000000', 0.12)} />
        </radialGradient>
      </defs>
      {/* hair behind the head */}
      {(L.style === 1 || L.style === 3) && <path d={`M${60 - fw - 4} 34 Q60 2 ${60 + fw + 4} 34 L${60 + fw + 6} ${L.style === 1 ? 78 : 66} Q60 ${L.style === 1 ? 84 : 70} ${60 - fw - 6} ${L.style === 1 ? 78 : 66} Z`} fill={hair} stroke={INK} strokeWidth="1.4" />}
      {/* ears */}
      <ellipse cx={60 - fw} cy="42" rx="3.4" ry="4.8" fill={mix(skin, '#000', 0.08)} stroke={INK} strokeWidth="1.2" />
      <ellipse cx={60 + fw} cy="42" rx="3.4" ry="4.8" fill={mix(skin, '#000', 0.08)} stroke={INK} strokeWidth="1.2" />
      {/* face */}
      <path d={`M${60 - fw} 36 Q${60 - fw} 18 60 18 Q${60 + fw} 18 ${60 + fw} 36 Q${60 + fw} 54 ${60 + 9} 60 Q60 64 ${60 - 9} 60 Q${60 - fw} 54 ${60 - fw} 36 Z`} fill={`url(#${uid}sk)`} stroke={INK} strokeWidth="1.5" />
      {p.health < 50 && <path d={`M${60 - fw} 36 Q${60 - fw} 18 60 18 Q${60 + fw} 18 ${60 + fw} 36 Q${60 + fw} 54 69 60 Q60 64 51 60 Q${60 - fw} 54 ${60 - fw} 36 Z`} fill="#a5d6a7" opacity="0.25" />}
      {(mood === 'happy' || p.traits.extraversion > 0.75) && <><ellipse cx="48" cy="49" rx="4" ry="2.6" fill="#ff8a80" opacity="0.32" /><ellipse cx="72" cy="49" rx="4" ry="2.6" fill="#ff8a80" opacity="0.32" /></>}
      {L.beard && <path d={`M${60 - fw + 1} 42 Q${60 - fw + 2} 60 60 66 Q${60 + fw - 2} 60 ${60 + fw - 1} 42 Q${60 + 8} 54 60 54 Q${60 - 8} 54 ${60 - fw + 1} 42`} fill={hair} stroke={INK} strokeWidth="1.2" />}
      {/* eyes with blinking lids */}
      {[-1, 1].map((sd) => (
        <g key={sd}>
          <ellipse cx={60 + sd * 7} cy="41" rx="3.6" ry={tired ? 1.6 : mood === 'scared' ? 3.6 : 3} fill="#fff" />
          <circle cx={60 + sd * 7} cy="41.4" r="2" fill="#2a1d14" />
          <circle cx={60 + sd * 7 + 0.7} cy="40.6" r="0.6" fill="#fff" />
          <rect x={60 + sd * 7 - 4} y={37} width="8" height="0" fill={skin}>
            <animate attributeName="height" values="0;0;7.5;0;0" keyTimes="0;0.94;0.965;0.99;1" dur="4.3s" begin={blinkBegin} repeatCount="indefinite" />
          </rect>
          {tired && <path d={`M${60 + sd * 7 - 4} 39.6 Q${60 + sd * 7} 38 ${60 + sd * 7 + 4} 39.6`} stroke={INK} strokeWidth="1" fill="none" />}
          <path d={`M${60 + sd * 3} ${33.5 + browIn - browUp * 0.2} Q${60 + sd * 7} ${31 - browUp} ${60 + sd * 11} ${33 - browIn * 0.6 + browUp * 0.4}`} stroke={mix(hair, '#000', 0.25)} strokeWidth="2.2" strokeLinecap="round" fill="none" />
        </g>
      ))}
      {L.glasses && <g fill="rgba(200,230,255,0.15)" stroke="#2b2b33" strokeWidth="1.3"><rect x="49" y="36.5" width="9.5" height="8" rx="3" /><rect x="61.5" y="36.5" width="9.5" height="8" rx="3" /><path d="M58.5 40 L61.5 40" /></g>}
      {/* nose */}
      <path d="M60 43 Q57.6 49 60.6 50" stroke={mix(skin, '#000', 0.3)} strokeWidth="1.3" fill="none" strokeLinecap="round" />
      {/* mouth: talks when speaking */}
      {talking ? (
        <ellipse cx="60" cy="55" rx="4" ry="2.6" fill="#6d2f28" stroke={INK} strokeWidth="1">
          <animate attributeName="ry" values="0.8;3.2;1.4;2.8;0.8" dur="0.45s" repeatCount="indefinite" />
        </ellipse>
      ) : (
        <path d={`M54.5 ${54.5 - smile * 0.8} Q60 ${54.5 + smile * 4} 65.5 ${54.5 - smile * 0.8}`} stroke="#7a3b32" strokeWidth="1.8" fill={smile > 0.6 ? '#fff' : 'none'} strokeLinecap="round" />
      )}
      {p.stress > 78 && <path d="M77 30 q2 4 0 6 q-2 -2 0 -6" fill="#9fd3ff" stroke={INK} strokeWidth="0.6" />}
      {p.age > 55 && <><path d="M50 30 L56 30" stroke={mix(skin, '#000', 0.2)} strokeWidth="0.8" /><path d="M64 30 L70 30" stroke={mix(skin, '#000', 0.2)} strokeWidth="0.8" /></>}
      {/* hair on top */}
      {L.style !== 4 && <path d={`M${60 - fw - 2} 38 Q${60 - fw - 3} 12 60 12 Q${60 + fw + 3} 12 ${60 + fw + 2} 38 Q${60 + fw - 2} 26 ${60 + 4} 24 Q${60 - 6} 30 ${60 - fw + 1} 33 Q${60 - fw - 1} 34 ${60 - fw - 2} 38`} fill={hair} stroke={INK} strokeWidth="1.4" />}
      {L.style === 4 && <path d={`M${60 - fw + 1} 30 Q60 14 ${60 + fw - 1} 30`} fill={hair} opacity="0.4" />}
      {L.style === 2 && <circle cx="60" cy="10" r="7" fill={hair} stroke={INK} strokeWidth="1.4" />}
      {L.style === 5 && [-12, -6, 0, 6, 12].map((k) => <circle key={k} cx={60 + k} cy={15 + Math.abs(k) * 0.4} r="6" fill={hair} stroke={INK} strokeWidth="1" />)}
    </g>
  )
}

/** Full body. viewBox 0 0 120 240, feet at y≈234. */
export function Figure({ p, scenario, talking, walking, working, gone, mirror }: { p: Person; scenario: string; talking: boolean; walking: boolean; working: boolean; gone: boolean; mirror?: boolean }) {
  const uid = `f${p.id}`
  const L = p.look
  const skin = SKIN[L.skin % 6]
  const o = outfitFor(p, scenario)
  const mood = moodOf(p)
  const w = 0.9 + L.build * 0.12
  const sw = 24 * w // half shoulder width
  const hw = 18 * w // half hip width
  const state = gone ? 'gone' : walking ? 'walking' : talking ? 'talking' : working ? 'working' : p.thinking ? 'pondering' : p.energy < 22 ? 'tired' : mood
  const long = o.kind === 'dress' || o.kind === 'gown' || o.kind === 'labcoat'
  const topGrad = `${uid}top`, botGrad = `${uid}bot`
  const delay = `${-((p.id * 0.37) % 2)}s`
  return (
    <svg viewBox="0 0 120 240" width="100%" height="100%" className={`fig ${state}`} style={{ overflow: 'visible', ['--d' as string]: delay, transform: mirror ? 'scaleX(-1)' : undefined }}>
      <defs>
        <linearGradient id={topGrad} x1="0" x2="1">
          <stop offset="0" stopColor={mix(o.top, '#000', 0.22)} />
          <stop offset="0.55" stopColor={o.top} />
          <stop offset="1" stopColor={mix(o.top, '#fff', 0.15)} />
        </linearGradient>
        <linearGradient id={botGrad} x1="0" x2="1">
          <stop offset="0" stopColor={mix(o.bottom, '#000', 0.25)} />
          <stop offset="1" stopColor={mix(o.bottom, '#fff', 0.08)} />
        </linearGradient>
      </defs>
      <ellipse className="shadow" cx="60" cy="234" rx={30 * w} ry="6" fill="rgba(0,0,0,0.28)" />
      <g className="body">
        {/* legs */}
        {[-1, 1].map((sd) => (
          <g key={sd} className={`leg ${sd < 0 ? 'l' : 'r'}`} style={{ transformOrigin: `${60 + sd * 9 * w}px 136px` }}>
            <path d={`M${60 + sd * 9 * w - 8} 132 L${60 + sd * 9 * w + 8} 132 L${60 + sd * 9 * w + 6.5} 182 L${60 + sd * 9 * w - 6.5} 182 Z`} fill={long ? skin : `url(#${botGrad})`} stroke={INK} strokeWidth="1.5" strokeLinejoin="round" />
            <g className="shin" style={{ transformOrigin: `${60 + sd * 9 * w}px 182px` }}>
              <path d={`M${60 + sd * 9 * w - 6.5} 180 L${60 + sd * 9 * w + 6.5} 180 L${60 + sd * 9 * w + 5.5} 226 L${60 + sd * 9 * w - 5.5} 226 Z`} fill={long || o.kind === 'chef' && false ? skin : `url(#${botGrad})`} stroke={INK} strokeWidth="1.5" strokeLinejoin="round" />
              <path d={`M${60 + sd * 9 * w - 7} 224 Q${60 + sd * 9 * w - 7} 233 ${60 + sd * 9 * w + sd * 3} 234 L${60 + sd * 9 * w + sd * 11} 234 Q${60 + sd * 9 * w + sd * 12} 227 ${60 + sd * 9 * w + 6} 223 Z`} fill={o.shoes} stroke={INK} strokeWidth="1.5" strokeLinejoin="round" />
            </g>
          </g>
        ))}
        <g className="torso">
          {/* back arm */}
          <Arm side={-1} o={o} skin={skin} sw={sw} topGrad={topGrad} />
          {/* torso & skirt/coat */}
          <path d={`M${60 - sw} 74 Q60 66 ${60 + sw} 74 L${60 + hw + (long ? 8 : 2)} ${long ? 196 : 138} Q60 ${long ? 202 : 142} ${60 - hw - (long ? 8 : 2)} ${long ? 196 : 138} Z`} fill={`url(#${topGrad})`} stroke={INK} strokeWidth="1.6" strokeLinejoin="round" />
          <OutfitDetail o={o} sw={sw} hw={hw} skin={skin} long={long} />
          {/* front arm */}
          <Arm side={1} o={o} skin={skin} sw={sw} topGrad={topGrad} />
        </g>
        {/* neck & head */}
        <g className="head" style={{ transformOrigin: '60px 70px' }}>
          <path d="M53 58 L67 58 L66 72 L54 72 Z" fill={mix(skin, '#000', 0.1)} stroke={INK} strokeWidth="1.3" />
          <g transform="translate(0 4)">
            <Face p={p} talking={talking} uid={uid} />
            <Hat o={o} />
          </g>
        </g>
      </g>
    </svg>
  )
}

function Arm({ side, o, skin, sw, topGrad }: { side: number; o: Outfit; skin: string; sw: number; topGrad: string }) {
  const x = 60 + side * (sw - 4)
  const sleeveCol = o.kind === 'scrubs' || o.kind === 'casual' ? skin : `url(#${topGrad})`
  const short = o.kind === 'scrubs' || o.kind === 'casual'
  return (
    <g className={`arm ${side < 0 ? 'l' : 'r'}`} style={{ transformOrigin: `${x}px 80px` }}>
      {short && <path d={`M${x - 7} 76 L${x + 7} 76 L${x + 6 + side * 2} 96 L${x - 6 + side * 2} 96 Z`} fill={o.top} stroke={INK} strokeWidth="1.4" strokeLinejoin="round" />}
      <path d={`M${x - 6} ${short ? 94 : 76} L${x + 6} ${short ? 94 : 76} L${x + 5 + side * 3} 118 L${x - 5 + side * 3} 118 Z`} fill={short ? skin : sleeveCol} stroke={INK} strokeWidth="1.4" strokeLinejoin="round" />
      <g className="fore" style={{ transformOrigin: `${x + side * 3}px 117px` }}>
        <path d={`M${x - 5 + side * 3} 116 L${x + 5 + side * 3} 116 L${x + 4 + side * 4} 150 L${x - 4 + side * 4} 150 Z`} fill={o.kind === 'scrubs' || o.kind === 'casual' || o.kind === 'chef' && false ? skin : sleeveCol} stroke={INK} strokeWidth="1.4" strokeLinejoin="round" />
        {(o.kind === 'labcoat' || o.kind === 'suit' || o.kind === 'chef') && <rect x={x - 5 + side * 4} y="146" width="10" height="4" fill={o.kind === 'suit' ? '#f4f4f4' : mix(o.top, '#000', 0.08)} stroke={INK} strokeWidth="1" />}
        <circle className="hand" cx={x + side * 4} cy="155" r="6" fill={skin} stroke={INK} strokeWidth="1.4" />
      </g>
    </g>
  )
}

function OutfitDetail({ o, sw, hw, skin, long }: { o: Outfit; sw: number; hw: number; skin: string; long: boolean }) {
  switch (o.kind) {
    case 'suit':
      return (
        <g>
          <path d="M52 72 L60 98 L68 72" fill="#f4f4f4" stroke={INK} strokeWidth="1.2" />
          {o.tie && <path d="M58 76 L62 76 L64 108 L60 114 L56 108 Z" fill={o.tie} stroke={INK} strokeWidth="1" />}
          <path d={`M${60 - sw + 4} 76 L58 110 L54 140`} stroke={mix(o.top, '#000', 0.35)} strokeWidth="1.4" fill="none" />
          <path d={`M${60 + sw - 4} 76 L62 110 L66 140`} stroke={mix(o.top, '#000', 0.35)} strokeWidth="1.4" fill="none" />
          <circle cx="60" cy="122" r="1.6" fill="#ddd" />
        </g>
      )
    case 'labcoat':
      return (
        <g>
          <path d={`M60 72 L60 196`} stroke={mix(o.top, '#000', 0.25)} strokeWidth="1.2" />
          <path d="M52 72 L60 92 L68 72" fill={o.accent} stroke={INK} strokeWidth="1.2" />
          <rect x="64" y="100" width="12" height="9" rx="2" fill="none" stroke={mix(o.top, '#000', 0.25)} strokeWidth="1" />
          <path d="M66 99 L66 105" stroke="#1565c0" strokeWidth="1.6" />
          <path d="M44 84 Q60 96 76 84" stroke="#3b4a5a" strokeWidth="1.6" fill="none" opacity="0.8" />
        </g>
      )
    case 'scrubs':
      return <g><path d="M53 72 L60 86 L67 72" fill={skin} stroke={INK} strokeWidth="1.2" /><rect x="63" y="94" width="10" height="9" rx="1.5" fill="none" stroke={mix(o.top, '#000', 0.3)} strokeWidth="1" /></g>
    case 'chef':
      return <g>{[86, 100, 114].map((y) => [-5, 5].map((dx) => <circle key={`${y}${dx}`} cx={60 + dx} cy={y} r="1.6" fill="#555" />))}<path d={`M${60 - hw} 130 L${60 + hw} 130 L${60 + hw + 4} 170 L${60 - hw - 4} 170 Z`} fill={mix(o.accent, '#ffffff', 0.4)} stroke={INK} strokeWidth="1.3" /></g>
    case 'hoodie':
      return <g><path d="M50 72 Q60 88 70 72" fill="none" stroke={mix(o.top, '#000', 0.3)} strokeWidth="2" /><path d="M56 80 L55 96 M64 80 L65 96" stroke={o.accent} strokeWidth="1.4" /><path d={`M${60 - 11} 112 L${60 + 11} 112 L${60 + 9} 126 L${60 - 9} 126 Z`} fill="none" stroke={mix(o.top, '#000', 0.3)} strokeWidth="1.3" /></g>
    case 'uniform':
      return <g><path d="M60 72 L60 138" stroke={mix(o.top, '#000', 0.3)} strokeWidth="1.2" />{[84, 98, 112, 126].map((y) => <circle key={y} cx="60" cy={y} r="1.5" fill={o.accent} />)}<rect x={60 - sw + 6} y="74" width="10" height="3" fill={o.accent} /><rect x={60 + sw - 16} y="74" width="10" height="3" fill={o.accent} /><rect x="64" y="88" width="9" height="4" rx="1" fill={o.accent} /></g>
    case 'parka':
      return <g><path d={`M${60 - sw} 76 Q60 62 ${60 + sw} 76`} stroke="#eceff1" strokeWidth="7" fill="none" strokeLinecap="round" /><path d="M60 78 L60 138" stroke={mix(o.top, '#000', 0.35)} strokeWidth="1.5" />{[96, 118].map((y) => <path key={y} d={`M${60 - sw + 2} ${y} L${60 + sw - 2} ${y}`} stroke={mix(o.top, '#000', 0.2)} strokeWidth="1.2" />)}</g>
    case 'overalls':
      return <g><path d={`M${60 - 12} 96 L${60 + 12} 96 L${60 + hw} 138 L${60 - hw} 138 Z`} fill={mix(o.top, '#000', 0.15)} stroke={INK} strokeWidth="1.2" /><path d="M50 74 L50 98 M70 74 L70 98" stroke={o.accent} strokeWidth="2.4" /><rect x="55" y="104" width="10" height="7" rx="1.5" fill="none" stroke={o.accent} strokeWidth="1" /></g>
    case 'dress':
    case 'gown':
      return <g><path d={`M${60 - hw} 120 Q60 126 ${60 + hw} 120`} stroke={o.accent} strokeWidth="3" fill="none" />{o.kind === 'gown' && <path d={`M${60 - hw - 6} 190 Q60 160 ${60 + hw + 6} 190`} stroke="#efe7da" strokeWidth="1.2" fill="none" />}{long && <path d="M52 74 Q60 84 68 74" fill={skin} stroke={INK} strokeWidth="1.1" />}</g>
    default:
      return <g><path d="M52 72 Q60 80 68 72" fill={skin} stroke={INK} strokeWidth="1.2" /><path d={`M${60 - hw} 136 L${60 + hw} 136`} stroke={mix(o.bottom, '#000', 0.2)} strokeWidth="4" /></g>
  }
}

function Hat({ o }: { o: Outfit }) {
  switch (o.hat) {
    case 'chef':
      return <g><rect x="46" y="10" width="28" height="10" fill="#fff" stroke={INK} strokeWidth="1.3" /><circle cx="50" cy="4" r="8" fill="#fff" stroke={INK} strokeWidth="1.3" /><circle cx="60" cy="0" r="9" fill="#fff" stroke={INK} strokeWidth="1.3" /><circle cx="70" cy="4" r="8" fill="#fff" stroke={INK} strokeWidth="1.3" /><rect x="46" y="9" width="28" height="4" fill="#fff" /></g>
    case 'beanie':
      return <g><path d="M42 30 Q42 6 60 6 Q78 6 78 30 Z" fill={o.top} stroke={INK} strokeWidth="1.4" /><rect x="41" y="25" width="38" height="8" rx="3" fill={mix(o.top, '#000', 0.2)} stroke={INK} strokeWidth="1.3" /><circle cx="60" cy="5" r="4" fill={o.accent} stroke={INK} strokeWidth="1" /></g>
    case 'cap':
      return <g><path d="M42 24 Q42 8 60 8 Q78 8 78 24 Z" fill="#263042" stroke={INK} strokeWidth="1.4" /><path d="M40 24 L82 24 L84 28 L38 28 Z" fill="#111" stroke={INK} strokeWidth="1.2" /><circle cx="60" cy="16" r="3" fill="#d4af37" /></g>
    case 'veil':
      return <path d="M44 16 Q60 4 76 16 Q88 60 82 110 L38 110 Q32 60 44 16 Z" fill="rgba(255,255,255,0.45)" stroke="rgba(30,26,38,0.35)" strokeWidth="1" />
    default:
      return null
  }
}
