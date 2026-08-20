// Predicted elevens, drawn on a pitch.
//
// Both sides on one pitch facing each other is how a fan expects to read a
// team sheet, and it makes the shape mismatch — a back three against a front
// two, say — visible at a glance in a way two lists never would.

import type { PredictedLineup, LineupSlot } from '../core/lineup.ts'
import { clubColor, club } from '../config/teams.ts'

const W = 1000
const H = 620
const LINE = 'rgba(255,255,255,0.16)'

/**
 * Where each line sits, as a fraction of the team's half.
 *
 * Forwards stop short of 0.85 so the two front lines do not collide in the
 * centre circle, and keepers sit off the goal line so their names stay inside
 * the frame.
 */
const DEPTH: Record<string, number> = { GK: 0.1, DEF: 0.34, MID: 0.6, FWD: 0.84 }

/**
 * Club colours are chosen for shirts, not for legibility on a dark green
 * pitch — Newcastle's is very nearly black, and rendered as-is their players
 * disappear. Anything too dark is lifted toward white until it reads, keeping
 * the hue so the side is still recognisable.
 */
function pitchColor(hex: string): string {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex)
  if (!m) return hex
  const n = parseInt(m[1]!, 16)
  let r = (n >> 16) & 255
  let g = (n >> 8) & 255
  let b = n & 255
  // Perceptual luminance, 0-1.
  const lum = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
  const FLOOR = 0.32
  if (lum >= FLOOR) return hex
  // Blend toward white by however much is needed to clear the floor.
  const t = Math.min(0.75, (FLOOR - lum) / FLOOR)
  r = Math.round(r + (255 - r) * t)
  g = Math.round(g + (255 - g) * t)
  b = Math.round(b + (255 - b) * t)
  return `rgb(${r}, ${g}, ${b})`
}

interface Placed {
  slot: LineupSlot
  x: number
  y: number
}

/** Lay one side out, mirrored when it is the away team. */
function place(lineup: PredictedLineup, away: boolean): Placed[] {
  const byLine = new Map<string, LineupSlot[]>()
  for (const s of lineup.starters) {
    if (!byLine.has(s.position)) byLine.set(s.position, [])
    byLine.get(s.position)!.push(s)
  }

  const out: Placed[] = []
  for (const [position, players] of byLine) {
    const depth = DEPTH[position] ?? 0.5
    // Each team occupies its own half; the away side is mirrored about the
    // halfway line so the two face each other.
    const x = away ? W - depth * (W / 2) : depth * (W / 2)
    players.forEach((slot, i) => {
      // Spread the line evenly, inset from the touchlines.
      const span = H - 150
      const step = span / (players.length + 1)
      out.push({ slot, x, y: 75 + step * (i + 1) })
    })
  }
  return out
}

function Player({ p, color }: { p: Placed; color: string }) {
  const doubtful = p.slot.playProbability < 0.95
  return (
    <g>
      <circle
        cx={p.x}
        cy={p.y}
        r={14}
        fill={color}
        stroke="rgba(4,18,31,0.85)"
        strokeWidth={2.5}
        opacity={doubtful ? 0.55 : 1}
      />
      {doubtful && (
        <text x={p.x} y={p.y + 4} textAnchor="middle" fontSize={13} fontWeight={800} fill="#04121f">
          ?
        </text>
      )}
      <text
        // Keep long names inside the frame; the away keeper sits close to the
        // touchline and would otherwise be clipped.
        x={Math.max(72, Math.min(W - 72, p.x))}
        y={p.y + 32}
        textAnchor="middle"
        fontSize={13}
        fontWeight={650}
        fill="var(--text)"
        style={{ paintOrder: 'stroke', stroke: 'rgba(4,12,22,0.85)', strokeWidth: 3 }}
      >
        {p.slot.name}
      </text>
      <title>
        {`${p.slot.name} — ${p.slot.position}, ${Math.round(p.slot.startConfidence * 100)}% likely to start` +
          (p.slot.news ? ` · ${p.slot.news}` : '')}
      </title>
    </g>
  )
}

export function LineupPitch({
  homeCode,
  awayCode,
  homeLineup,
  awayLineup,
}: {
  homeCode: string
  awayCode: string
  homeLineup: PredictedLineup
  awayLineup: PredictedLineup
}) {
  const home = place(homeLineup, false)
  const away = place(awayLineup, true)

  return (
    <svg width="100%" viewBox={`0 0 ${W} ${H}`} role="img"
      aria-label={`Predicted line-ups: ${club(homeCode).name} ${homeLineup.formation}, ${club(awayCode).name} ${awayLineup.formation}`}>
      <defs>
        <linearGradient id="turf" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#0f2d22" />
          <stop offset="100%" stopColor="#0b2119" />
        </linearGradient>
      </defs>

      <rect width={W} height={H} rx={12} fill="url(#turf)" />
      {/* Mown stripes — subtle, purely to read as a pitch. */}
      {Array.from({ length: 10 }, (_, i) => (
        <rect key={i} x={(i * W) / 10} y={0} width={W / 10} height={H}
          fill={i % 2 === 0 ? 'rgba(255,255,255,0.014)' : 'transparent'} />
      ))}

      {/* Markings */}
      <rect x={22} y={22} width={W - 44} height={H - 44} fill="none" stroke={LINE} strokeWidth={2} rx={4} />
      <line x1={W / 2} y1={22} x2={W / 2} y2={H - 22} stroke={LINE} strokeWidth={2} />
      <circle cx={W / 2} cy={H / 2} r={68} fill="none" stroke={LINE} strokeWidth={2} />
      <circle cx={W / 2} cy={H / 2} r={3} fill={LINE} />
      <rect x={22} y={H / 2 - 110} width={122} height={220} fill="none" stroke={LINE} strokeWidth={2} />
      <rect x={W - 144} y={H / 2 - 110} width={122} height={220} fill="none" stroke={LINE} strokeWidth={2} />
      <rect x={22} y={H / 2 - 50} width={46} height={100} fill="none" stroke={LINE} strokeWidth={2} />
      <rect x={W - 68} y={H / 2 - 50} width={46} height={100} fill="none" stroke={LINE} strokeWidth={2} />

      {/* Formation labels */}
      <text x={40} y={48} fontSize={15} fontWeight={800} fill={pitchColor(clubColor(homeCode))}>
        {club(homeCode).short} {homeLineup.formation}
      </text>
      <text x={W - 40} y={48} textAnchor="end" fontSize={15} fontWeight={800}
        fill={pitchColor(clubColor(awayCode))}>
        {club(awayCode).short} {awayLineup.formation}
      </text>

      {home.map((p) => <Player key={`h${p.slot.id}`} p={p} color={pitchColor(clubColor(homeCode))} />)}
      {away.map((p) => <Player key={`a${p.slot.id}`} p={p} color={pitchColor(clubColor(awayCode))} />)}
    </svg>
  )
}

/** The caveat that belongs under any predicted eleven. */
export function LineupCaveat({ home, away }: { home: PredictedLineup; away: PredictedLineup }) {
  const priced = [home, away].some((l) => l.basis === 'price')
  return (
    <p className="faint" style={{ fontSize: 12, lineHeight: 1.55, marginTop: 10 }}>
      Predicted from recent minutes and current availability — confirmed team sheets are not
      published until about an hour before kickoff, and this forecast is made days ahead. A faded
      circle marks a player carrying a doubt. Positions come from the Fantasy Premier League
      classification, which sometimes differs from where a player actually lines up, so the shape
      is a guide rather than a tactical read.
      {priced && (
        <>
          {' '}
          One of these sides is newly promoted with no top-flight record in the data, so its eleven
          is inferred from squad valuation and is a rough guess rather than a read on form.
        </>
      )}
    </p>
  )
}
