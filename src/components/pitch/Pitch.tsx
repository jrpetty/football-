import type { ReactNode, CSSProperties } from 'react'

// Vertical pitch. Data coordinates: x 0..100 (left→right),
// y 0..100 (own goal line at 0 → opposition goal at 100).
// The SVG viewBox is 100 wide × 154 tall so 1 unit ≈ 1 unit in metres on
// both axes (real pitch 68m × 105m), keeping circles round.
export const PITCH_W = 100
export const PITCH_H = 154

export const pitchX = (x: number) => x
export const pitchY = (y: number) => PITCH_H * (1 - y / 100)

/** CSS percentage position for an HTML token laid over a .pitch-field. */
export function slotStyle(x: number, y: number): CSSProperties {
  return {
    position: 'absolute',
    left: `${x}%`,
    top: `${(1 - y / 100) * 100}%`,
    transform: 'translate(-50%, -50%)',
  }
}

/** SVG pitch markings (grass + lines) for the 100×154 viewBox. */
export function PitchMarkings({ stripes = true }: { stripes?: boolean }) {
  const line = 'rgba(255,255,255,0.55)'
  const sw = 0.5
  return (
    <>
      <defs>
        <linearGradient id="grass" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#13864a" />
          <stop offset="100%" stopColor="#0c6736" />
        </linearGradient>
      </defs>
      <rect x={0} y={0} width={PITCH_W} height={PITCH_H} fill="url(#grass)" />
      {stripes &&
        Array.from({ length: 8 }).map((_, i) => (
          <rect
            key={i}
            x={0}
            y={(PITCH_H / 8) * i}
            width={PITCH_W}
            height={PITCH_H / 8}
            fill={i % 2 === 0 ? 'rgba(255,255,255,0.035)' : 'transparent'}
          />
        ))}
      <g fill="none" stroke={line} strokeWidth={sw}>
        {/* Outer boundary */}
        <rect x={2} y={2} width={96} height={150} />
        {/* Halfway line */}
        <line x1={2} y1={77} x2={98} y2={77} />
        {/* Centre circle + spot */}
        <circle cx={50} cy={77} r={13} />
        <circle cx={50} cy={77} r={0.8} fill={line} stroke="none" />
        {/* Bottom (own) penalty area */}
        <rect x={20} y={2} width={60} height={24} />
        <rect x={36} y={2} width={28} height={8} />
        <circle cx={50} cy={18} r={0.8} fill={line} stroke="none" />
        <path d="M 38 26 A 13 13 0 0 0 62 26" />
        {/* Bottom goal */}
        <rect x={44} y={0} width={12} height={2} fill="rgba(255,255,255,0.25)" stroke={line} />
        {/* Top (opposition) penalty area */}
        <rect x={20} y={128} width={60} height={24} />
        <rect x={36} y={144} width={28} height={8} />
        <circle cx={50} cy={136} r={0.8} fill={line} stroke="none" />
        <path d="M 38 128 A 13 13 0 0 1 62 128" />
        {/* Top goal */}
        <rect x={44} y={152} width={12} height={2} fill="rgba(255,255,255,0.25)" stroke={line} />
      </g>
    </>
  )
}

/**
 * A standalone SVG pitch. Children should be SVG elements positioned with
 * pitchX()/pitchY() (data coordinates 0..100).
 */
export function Pitch({
  children,
  stripes = true,
  className,
  style,
}: {
  children?: ReactNode
  stripes?: boolean
  className?: string
  style?: CSSProperties
}) {
  return (
    <svg
      viewBox={`0 0 ${PITCH_W} ${PITCH_H}`}
      className={className}
      style={{ width: '100%', height: 'auto', display: 'block', borderRadius: 12, ...style }}
      role="img"
      aria-label="Football pitch"
    >
      <PitchMarkings stripes={stripes} />
      {children}
    </svg>
  )
}

/**
 * A pitch container suited to HTML overlays (draggable tokens etc.).
 * Lay children out absolutely with slotStyle(x, y).
 */
export function PitchField({
  children,
  className,
  stripes = true,
}: {
  children?: ReactNode
  className?: string
  stripes?: boolean
}) {
  return (
    <div
      className={className}
      style={{ position: 'relative', aspectRatio: `${PITCH_W} / ${PITCH_H}`, width: '100%' }}
    >
      <svg
        viewBox={`0 0 ${PITCH_W} ${PITCH_H}`}
        preserveAspectRatio="none"
        style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', borderRadius: 12 }}
      >
        <PitchMarkings stripes={stripes} />
      </svg>
      {children}
    </div>
  )
}
