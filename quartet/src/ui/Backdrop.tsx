// Painted, gently animated scenery behind each scenario's stage.

import type { Scenario } from '../engine/types.ts'
import { mix } from './Figure.tsx'

type Scene = 'beach' | 'storm' | 'office' | 'home' | 'hall' | 'hospital' | 'snow' | 'desert' | 'neon' | 'kitchen'

export function sceneFor(id: string): Scene {
  switch (id) {
    case 'stranded': return 'beach'
    case 'lifeboat': return 'storm'
    case 'startup': case 'newsroom': case 'campaign': return 'office'
    case 'flatshare': case 'will': case 'house': case 'wedding': case 'bakery': return 'home'
    case 'jury': case 'summit': return 'hall'
    case 'er': return 'hospital'
    case 'antarctic': case 'snowbound': return 'snow'
    case 'roadtrip': return 'desert'
    case 'escape': return 'neon'
    case 'kitchen': return 'kitchen'
    default: return 'home'
  }
}

const rnd = (n: number) => {
  const x = Math.sin(n * 127.1) * 43758.5453
  return x - Math.floor(x)
}

export function Backdrop({ sc, night }: { sc: Scenario; night: number }) {
  const scene = sceneFor(sc.id)
  const [s0, s1] = sc.theme.sky
  return (
    <svg viewBox="0 0 1000 620" className="backdrop-svg" preserveAspectRatio="xMidYMid slice">
      <defs>
        <linearGradient id="bg-sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor={s0} /><stop offset="1" stopColor={s1} /></linearGradient>
        <linearGradient id="bg-floor" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor={mix(sc.theme.ground, '#000', 0.05)} /><stop offset="1" stopColor={mix(sc.theme.ground, '#000', 0.28)} /></linearGradient>
        <radialGradient id="bg-sun"><stop offset="0" stopColor="#fff6c8" /><stop offset="0.4" stopColor="#ffe082" stopOpacity="0.9" /><stop offset="1" stopColor="#ffe082" stopOpacity="0" /></radialGradient>
        <radialGradient id="bg-lamp"><stop offset="0" stopColor="#fff3d0" stopOpacity="0.7" /><stop offset="1" stopColor="#fff3d0" stopOpacity="0" /></radialGradient>
        <filter id="bg-blur"><feGaussianBlur stdDeviation="6" /></filter>
        <pattern id="bg-planks" width="120" height="34" patternUnits="userSpaceOnUse"><rect width="120" height="34" fill={mix(sc.theme.ground, '#7a5232', 0.55)} /><path d="M0 33.5 H120 M60 0 V34" stroke="rgba(0,0,0,0.18)" strokeWidth="2" /><path d="M10 12 q20 -4 40 0" stroke="rgba(255,255,255,0.07)" strokeWidth="2" fill="none" /></pattern>
        <pattern id="bg-tiles" width="46" height="46" patternUnits="userSpaceOnUse"><rect width="46" height="46" fill="#e8eef1" /><path d="M0 45.5 H46 M45.5 0 V46" stroke="#c6d2d8" strokeWidth="1.5" /></pattern>
        <pattern id="bg-wall" width="40" height="40" patternUnits="userSpaceOnUse"><rect width="40" height="40" fill={mix(sc.theme.accent, '#f5ead8', 0.82)} /><path d="M20 0 V40" stroke={mix(sc.theme.accent, '#f5ead8', 0.6)} strokeWidth="6" /><circle cx="20" cy="20" r="3" fill={mix(sc.theme.accent, '#ffffff', 0.4)} opacity="0.6" /></pattern>
        <pattern id="bg-wood" width="80" height="620" patternUnits="userSpaceOnUse"><rect width="80" height="620" fill="#5a3b26" /><rect x="6" y="20" width="68" height="250" rx="4" fill="#6b4630" stroke="#3e2818" strokeWidth="3" /></pattern>
      </defs>
      {scene === 'beach' && <Beach />}
      {scene === 'storm' && <Storm />}
      {scene === 'office' && <Office accent={sc.theme.accent} />}
      {scene === 'home' && <Home />}
      {scene === 'hall' && <Hall flags={sc.id === 'summit'} />}
      {scene === 'hospital' && <Hospital />}
      {scene === 'snow' && <Snow />}
      {scene === 'desert' && <Desert />}
      {scene === 'neon' && <Neon />}
      {scene === 'kitchen' && <Kitchen />}
      {night > 0 && <rect width="1000" height="620" fill="#070d2a" opacity={night} style={{ transition: 'opacity 2s' }} />}
    </svg>
  )
}

function Clouds({ n = 4, color = '#ffffff', op = 0.85 }: { n?: number; color?: string; op?: number }) {
  return (
    <>
      {Array.from({ length: n }, (_, k) => (
        <g key={k} className="cloud" style={{ animationDuration: `${70 + k * 25}s`, animationDelay: `${-k * 23}s` }}>
          <g transform={`translate(0 ${40 + rnd(k) * 120}) scale(${0.7 + rnd(k + 9) * 0.7})`} opacity={op}>
            <ellipse cx="60" cy="30" rx="60" ry="22" fill={color} />
            <ellipse cx="30" cy="22" rx="32" ry="20" fill={color} />
            <ellipse cx="90" cy="18" rx="36" ry="24" fill={color} />
          </g>
        </g>
      ))}
    </>
  )
}

function Palm({ x, y, s = 1, flip = false }: { x: number; y: number; s?: number; flip?: boolean }) {
  return (
    <g transform={`translate(${x} ${y}) scale(${flip ? -s : s} ${s})`}>
      <path d="M0 0 Q-10 -90 18 -180" stroke="#7a5a32" strokeWidth="14" fill="none" strokeLinecap="round" />
      <path d="M0 0 Q-10 -90 18 -180" stroke="#9a7444" strokeWidth="6" fill="none" strokeDasharray="4 10" />
      <g className="sway">
        {[-140, -100, -60, -20, 20, 60].map((a, k) => (
          <path key={k} d={`M18 -180 q${Math.cos((a * Math.PI) / 180) * 60} ${-30 + Math.sin((a * Math.PI) / 180) * 30} ${Math.cos((a * Math.PI) / 180) * 110} ${30 + Math.sin((a * Math.PI) / 180) * 50}`} stroke={k % 2 ? '#2f8f46' : '#3fa957'} strokeWidth="13" fill="none" strokeLinecap="round" />
        ))}
        <circle cx="14" cy="-170" r="7" fill="#6b4a2b" /><circle cx="24" cy="-168" r="7" fill="#5e4026" />
      </g>
    </g>
  )
}

function Beach() {
  return (
    <g>
      <rect width="1000" height="620" fill="url(#bg-sky)" />
      <circle cx="820" cy="90" r="120" fill="url(#bg-sun)" />
      <circle cx="820" cy="90" r="34" fill="#fff8d6" />
      <Clouds n={4} />
      <path d="M0 250 Q120 236 240 250 T480 250 T720 248 T1000 250 V620 H0 Z" fill="#2a8fb8" />
      <path d="M560 252 q40 -30 90 -6 q30 -16 60 6 Z" fill="#3c7a5a" opacity="0.6" />
      {[0, 1, 2].map((k) => (
        <path key={k} className="wave" style={{ animationDelay: `${-k * 2}s` }} d={`M-40 ${270 + k * 26} q30 -8 60 0 t60 0 t60 0 t60 0 t60 0 t60 0 t60 0 t60 0 t60 0 t60 0 t60 0 t60 0 t60 0 t60 0 t60 0 t60 0 t60 0 t60 0`} stroke="rgba(255,255,255,0.45)" strokeWidth="3" fill="none" />
      ))}
      <path d="M0 340 Q250 320 500 336 T1000 330 V620 H0 Z" fill="#ead9a4" />
      <path d="M0 340 Q250 320 500 336 T1000 330" stroke="rgba(255,255,255,0.8)" strokeWidth="6" fill="none" className="wave" />
      {Array.from({ length: 60 }, (_, k) => <circle key={k} cx={rnd(k) * 1000} cy={360 + rnd(k + 50) * 260} r={1 + rnd(k + 3) * 2} fill="rgba(140,110,60,0.25)" />)}
      <Palm x={30} y={420} s={1.15} />
      <Palm x={975} y={430} s={1.05} flip />
    </g>
  )
}

function Storm() {
  return (
    <g>
      <rect width="1000" height="620" fill="url(#bg-sky)" />
      <rect width="1000" height="620" fill="#fff" opacity="0" className="flash">
        <animate attributeName="opacity" values="0;0;0.55;0;0.3;0;0" keyTimes="0;0.86;0.87;0.89;0.9;0.92;1" dur="9s" repeatCount="indefinite" />
      </rect>
      <Clouds n={5} color="#2c3a4c" op={0.9} />
      <path d="M0 200 Q100 170 200 200 T400 196 T600 205 T800 190 T1000 200 V620 H0 Z" fill="#163650" />
      {[0, 1, 2, 3].map((k) => (
        <path key={k} className="wave" style={{ animationDuration: `${4 + k}s`, animationDelay: `${-k}s` }} d={`M-60 ${230 + k * 40} q50 -26 100 0 t100 0 t100 0 t100 0 t100 0 t100 0 t100 0 t100 0 t100 0 t100 0 t100 0 t100 0 V620 H-60 Z`} fill={mix('#1a4466', '#0b1d2c', k / 4)} opacity="0.9" />
      ))}
      {/* the lifeboat itself */}
      <g className="wave" style={{ animationDuration: '5s' }}>
        <path d="M40 260 Q500 220 960 260 L900 470 Q500 500 100 470 Z" fill="#e8622c" stroke="#7a2e12" strokeWidth="6" />
        <path d="M70 290 Q500 255 930 290" stroke="#f6f0e4" strokeWidth="10" fill="none" />
        <path d="M110 470 Q500 500 890 470 L880 495 Q500 525 120 495 Z" fill="#b8481f" />
      </g>
      {Array.from({ length: 70 }, (_, k) => <line key={k} className="fall" style={{ animationDuration: `${0.6 + rnd(k) * 0.5}s`, animationDelay: `${-rnd(k + 3)}s` }} x1={rnd(k + 7) * 1000} y1="0" x2={rnd(k + 7) * 1000 - 8} y2="22" stroke="rgba(200,220,255,0.45)" strokeWidth="1.5" />)}
    </g>
  )
}

function Skyline() {
  return (
    <g>
      {Array.from({ length: 14 }, (_, k) => {
        const w = 50 + rnd(k) * 50, h = 80 + rnd(k + 20) * 150, x = k * 75
        return (
          <g key={k}>
            <rect x={x} y={250 - h} width={w} height={h} fill={mix('#2b3a55', '#11182a', rnd(k + 4))} />
            {Array.from({ length: Math.floor(h / 18) * 3 }, (_, j) => (
              <rect key={j} className={rnd(k * 31 + j) > 0.92 ? 'flicker' : undefined} x={x + 6 + (j % 3) * (w / 3.2)} y={250 - h + 8 + Math.floor(j / 3) * 18} width="8" height="10" fill={rnd(k * 17 + j) > 0.45 ? '#ffe9a8' : '#3d4a66'} opacity="0.85" />
            ))}
          </g>
        )
      })}
    </g>
  )
}

function Office({ accent }: { accent: string }) {
  return (
    <g>
      <rect width="1000" height="620" fill="#2a3042" />
      {/* floor-to-ceiling windows */}
      <rect x="0" y="0" width="1000" height="280" fill="url(#bg-sky)" />
      <Clouds n={3} op={0.35} />
      <Skyline />
      {[0, 1, 2, 3, 4, 5].map((k) => <rect key={k} x={k * 200 - 6} y="0" width="12" height="285" fill="#1b2030" />)}
      <rect x="0" y="270" width="1000" height="16" fill="#1b2030" />
      {/* ceiling lights */}
      {[150, 500, 850].map((x) => <g key={x}><rect x={x - 60} y="0" width="120" height="8" fill="#e8eef6" /><ellipse cx={x} cy="40" rx="140" ry="60" fill="url(#bg-lamp)" /></g>)}
      <rect x="0" y="286" width="1000" height="334" fill={mix(accent, '#31384d', 0.85)} />
      {Array.from({ length: 12 }, (_, k) => <path key={k} d={`M${k * 90} 286 L${k * 90 - 120} 620`} stroke="rgba(255,255,255,0.04)" strokeWidth="2" />)}
      <g transform="translate(965 300)"><rect x="-14" y="0" width="28" height="36" rx="4" fill="#8d6e63" /><path d="M0 0 q-24 -40 -6 -70 M0 0 q20 -36 6 -66 M0 0 q-4 -50 0 -80" stroke="#4caf50" strokeWidth="9" fill="none" strokeLinecap="round" className="sway" /></g>
    </g>
  )
}

function Home() {
  return (
    <g>
      <rect width="1000" height="300" fill="url(#bg-wall)" />
      <rect x="0" y="290" width="1000" height="14" fill="#7a5a3e" />
      {/* a window to the outside */}
      <g transform="translate(420 40)">
        <rect width="170" height="150" rx="6" fill="url(#bg-sky)" stroke="#6b4a2b" strokeWidth="10" />
        <path d="M85 0 V150 M0 75 H170" stroke="#6b4a2b" strokeWidth="6" />
        <path d="M-12 -6 Q20 70 -6 160 L-24 160 L-24 -6 Z" fill="#b5523b" opacity="0.85" />
        <path d="M182 -6 Q150 70 176 160 L194 160 L194 -6 Z" fill="#b5523b" opacity="0.85" />
      </g>
      {/* pictures on the wall */}
      {[[90, 70, 70, 54], [200, 90, 46, 60], [720, 64, 90, 64], [860, 96, 50, 50]].map(([x, y, w, h], k) => (
        <g key={k}><rect x={x} y={y} width={w} height={h} fill="#f4ecdc" stroke="#5b3f26" strokeWidth="5" /><path d={`M${x + 6} ${y + h - 8} l${w * 0.3} ${-h * 0.4} l${w * 0.2} ${h * 0.2} l${w * 0.3} ${-h * 0.35}`} stroke={['#5a8f6a', '#c06c4c', '#4a6fa5', '#b0884a'][k]} strokeWidth="4" fill="none" /></g>
      ))}
      <ellipse cx="505" cy="10" rx="90" ry="50" fill="url(#bg-lamp)" />
      <rect x="0" y="304" width="1000" height="316" fill="url(#bg-planks)" />
      <rect x="0" y="304" width="1000" height="316" fill="url(#bg-floor)" opacity="0.35" />
    </g>
  )
}

function Hall({ flags }: { flags: boolean }) {
  const cols = ['#c62828', '#1565c0', '#2e7d32', '#f9a825', '#6a1b9a', '#00838f']
  return (
    <g>
      <rect width="1000" height="320" fill="url(#bg-wood)" />
      {[60, 300, 700, 940].map((x) => <g key={x}><rect x={x - 22} y="0" width="44" height="320" fill="#d8ccb4" /><rect x={x - 30} y="0" width="60" height="20" fill="#c4b79c" /><rect x={x - 30} y="300" width="60" height="20" fill="#c4b79c" /></g>)}
      {flags ? cols.map((c, k) => <g key={k} transform={`translate(${380 + k * 48} 40)`}><rect x="0" y="0" width="4" height="150" fill="#bfa76a" /><path d="M4 6 q18 6 36 0 v40 q-18 6 -36 0 Z" fill={c} className="sway" /></g>)
        : <g transform="translate(500 120)"><circle r="70" fill="#c9a24a" stroke="#7a5a1e" strokeWidth="6" /><circle r="52" fill="none" stroke="#7a5a1e" strokeWidth="3" /><path d="M-30 10 h60 M0 -30 v60 M-24 -16 l48 0" stroke="#7a5a1e" strokeWidth="5" /></g>}
      <rect x="0" y="320" width="1000" height="300" fill="#6d1f22" />
      <rect x="0" y="320" width="1000" height="300" fill="url(#bg-floor)" opacity="0.4" />
      <rect x="120" y="330" width="760" height="290" fill="#8a2a2e" opacity="0.6" />
    </g>
  )
}

function Hospital() {
  return (
    <g>
      <rect width="1000" height="320" fill="#d7e6e8" />
      <rect x="0" y="200" width="1000" height="22" fill="#4fa3a5" />
      <rect x="0" y="0" width="1000" height="16" fill="#eef4f6" />
      {[120, 380, 640, 900].map((x, k) => <g key={x}><rect x={x - 70} y="16" width="140" height="10" fill="#ffffff" className={k === 2 ? 'flicker' : undefined} /><ellipse cx={x} cy="50" rx="160" ry="70" fill="url(#bg-lamp)" /></g>)}
      <g transform="translate(470 70)"><rect width="80" height="80" rx="10" fill="#fff" stroke="#c62828" strokeWidth="4" /><path d="M40 16 V64 M16 40 H64" stroke="#c62828" strokeWidth="14" /></g>
      <rect x="40" y="100" width="150" height="70" rx="6" fill="#26343a" /><path d="M50 140 l20 0 l8 -20 l10 40 l10 -30 l8 10 l70 0" stroke="#69f0ae" strokeWidth="3" fill="none" className="flicker" />
      <rect x="0" y="320" width="1000" height="300" fill="url(#bg-tiles)" />
      <rect x="0" y="320" width="1000" height="300" fill="url(#bg-floor)" opacity="0.25" />
    </g>
  )
}

function Snow() {
  return (
    <g>
      <rect width="1000" height="620" fill="url(#bg-sky)" />
      {Array.from({ length: 80 }, (_, k) => <circle key={k} className="twinkle" style={{ animationDelay: `${-rnd(k) * 3}s` }} cx={rnd(k) * 1000} cy={rnd(k + 99) * 230} r={0.8 + rnd(k + 5) * 1.6} fill="#fff" />)}
      <path className="aurora" d="M-20 120 Q200 40 420 110 T820 90 T1040 120 L1040 170 Q820 140 620 170 T200 160 T-20 180 Z" fill="#5cf0b4" opacity="0.35" filter="url(#bg-blur)" />
      <path className="aurora" style={{ animationDelay: '-4s' }} d="M-20 90 Q260 20 520 80 T1040 70 L1040 110 Q760 90 520 120 T-20 130 Z" fill="#9b7cff" opacity="0.25" filter="url(#bg-blur)" />
      <path d="M0 290 L120 160 L220 250 L340 120 L470 260 L600 150 L720 240 L850 130 L1000 270 V620 H0 Z" fill="#c3d1e6" />
      <path d="M340 120 L370 150 L350 148 L330 160 Z M850 130 L880 162 L860 158 L840 170 Z M120 160 L140 182 L118 178 Z" fill="#fff" />
      <path d="M0 330 Q250 300 500 320 T1000 310 V620 H0 Z" fill="#eef3fa" />
      {Array.from({ length: 70 }, (_, k) => <circle key={k} className="fall" style={{ animationDuration: `${5 + rnd(k) * 6}s`, animationDelay: `${-rnd(k + 1) * 10}s` }} cx={rnd(k + 33) * 1000} cy="0" r={1.2 + rnd(k + 4) * 2.2} fill="#fff" opacity="0.85" />)}
    </g>
  )
}

function Desert() {
  return (
    <g>
      <rect width="1000" height="620" fill="url(#bg-sky)" />
      <circle cx="240" cy="230" r="150" fill="url(#bg-sun)" />
      <circle cx="240" cy="230" r="46" fill="#ffd28a" />
      <path d="M0 250 L60 250 L80 170 L190 170 L210 250 L560 250 L580 200 L640 196 L660 250 L1000 250 V620 H0 Z" fill="#b5523b" />
      <path d="M0 270 Q500 240 1000 268 V620 H0 Z" fill="#d9a066" />
      <path d="M0 296 L1000 280 L1000 330 L0 352 Z" fill="#3b3b3f" />
      <g className="roaddash">{Array.from({ length: 16 }, (_, k) => <rect key={k} x={k * 80} y={314 - k * 0.6} width="40" height="5" fill="#f5d36b" />)}</g>
      {[[60, 420], [900, 400], [760, 520]].map(([x, y], k) => <g key={k} transform={`translate(${x} ${y})`}><rect x="-9" y="-80" width="18" height="80" rx="9" fill="#4c8a4a" /><rect x="-34" y="-58" width="12" height="34" rx="6" fill="#4c8a4a" /><rect x="-34" y="-34" width="30" height="12" rx="6" fill="#4c8a4a" /><rect x="22" y="-66" width="12" height="30" rx="6" fill="#4c8a4a" /><rect x="6" y="-44" width="28" height="12" rx="6" fill="#4c8a4a" /></g>)}
    </g>
  )
}

function Neon() {
  return (
    <g>
      <rect width="1000" height="620" fill="#140f22" />
      {Array.from({ length: 8 }, (_, k) => <rect key={k} x={k * 125} y="0" width="2" height="320" fill="rgba(255,255,255,0.03)" />)}
      <rect x="60" y="40" width="880" height="10" rx="5" fill="#ff4fd8" opacity="0.85" />
      <rect x="60" y="40" width="880" height="10" rx="5" fill="#ff4fd8" filter="url(#bg-blur)" className="flicker" />
      <rect x="60" y="290" width="880" height="6" rx="3" fill="#41e6ff" opacity="0.8" />
      <rect x="60" y="290" width="880" height="6" rx="3" fill="#41e6ff" filter="url(#bg-blur)" />
      {[150, 380, 610, 840].map((x, k) => <g key={x} transform={`translate(${x} 110)`}><rect x="-40" y="0" width="80" height="120" rx="6" fill="#211a36" stroke="#3b2f63" strokeWidth="3" /><text x="0" y="74" textAnchor="middle" fontSize="44" fill={['#ffd166', '#41e6ff', '#ff4fd8', '#7cf7c3'][k]} opacity="0.8" style={{ fontFamily: 'monospace' }}>{['?', '7', 'Ω', '#'][k]}</text></g>)}
      <rect x="0" y="320" width="1000" height="300" fill="#1c1630" />
      <rect x="0" y="320" width="1000" height="300" fill="url(#bg-floor)" opacity="0.2" />
    </g>
  )
}

function Kitchen() {
  return (
    <g>
      <rect width="1000" height="320" fill="url(#bg-tiles)" />
      <rect x="0" y="0" width="1000" height="320" fill="#e9dccb" opacity="0.35" />
      <rect x="0" y="40" width="1000" height="10" fill="#8d8f93" />
      {Array.from({ length: 9 }, (_, k) => <g key={k} transform={`translate(${70 + k * 108} 50)`}><path d="M0 0 V22" stroke="#555" strokeWidth="3" /><circle cx="0" cy={40 + (k % 3) * 6} r={16 + (k % 2) * 6} fill="#3a3a3f" stroke="#222" strokeWidth="3" /><rect x="14" y={36 + (k % 3) * 6} width="26" height="6" rx="3" fill="#3a3a3f" /></g>)}
      {[220, 520, 820].map((x) => Array.from({ length: 4 }, (_, j) => <circle key={`${x}${j}`} className="fall" style={{ animationDirection: 'reverse', animationDuration: `${3 + j}s`, animationDelay: `${-j * 0.8}s` }} cx={x + j * 12 - 18} cy="300" r={14 + j * 4} fill="#ffffff" opacity="0.1" />))}
      <rect x="0" y="320" width="1000" height="300" fill="#4a4e57" />
      <rect x="0" y="320" width="1000" height="300" fill="url(#bg-floor)" opacity="0.3" />
      {Array.from({ length: 12 }, (_, k) => <path key={k} d={`M${k * 90} 320 L${k * 90 - 120} 620`} stroke="rgba(255,255,255,0.05)" strokeWidth="2" />)}
    </g>
  )
}
