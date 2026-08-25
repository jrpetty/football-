// ---------------------------------------------------------------------------
// The icon set.
//
// Drawn here rather than pulled from a library or typed as emoji: emoji render
// differently on every phone, cannot take the theme's colour, and sit badly on
// a baseline. These are plain strokes that inherit currentColor, so a tab, a
// verdict and a button can each tint them without any extra plumbing.
//
// Every icon is aria-hidden: the text beside it carries the meaning.
// ---------------------------------------------------------------------------

interface IconProps {
  size?: number
  strokeWidth?: number
}

function Svg({ size = 20, strokeWidth = 1.8, children }: IconProps & { children: React.ReactNode }) {
  return (
    <svg
      className="icon"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {children}
    </svg>
  )
}

/** The brand: a five-bar gate, four strokes and the tally through them. */
export function TallyMark({ size = 24 }: IconProps) {
  return (
    <svg
      className="icon"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2.4}
      strokeLinecap="round"
      aria-hidden="true"
      focusable="false"
    >
      <path d="M5.2 4.5v15M9.6 4.5v15M14 4.5v15M18.4 4.5v15" />
      <path d="M2.6 16.8 21.4 7" strokeWidth={2.6} />
    </svg>
  )
}

/** Tonight — the moon over the last orders. */
export function IconMoon(p: IconProps) {
  return (
    <Svg {...p}>
      <path d="M20.6 13.2A8.4 8.4 0 1 1 10.8 3.4a6.6 6.6 0 0 0 9.8 9.8z" />
    </Svg>
  )
}

/** Trade — takings on the rise, ideally. */
export function IconChart(p: IconProps) {
  return (
    <Svg {...p}>
      <path d="M3.5 4v15.5a1 1 0 0 0 1 1H21" />
      <path d="M7 15.5l4-5 3.5 2.5 5-6.5" />
    </Svg>
  )
}

/** Cellar — the barrel the pints come out of. */
export function IconBarrel(p: IconProps) {
  return (
    <Svg {...p}>
      <path d="M7.5 3h9c1.7 2 2.6 5.2 2.6 9s-.9 7-2.6 9h-9C5.8 19 4.9 15.8 4.9 12s.9-7 2.6-9z" />
      <path d="M5.4 8h13.2M5.4 16h13.2" />
    </Svg>
  )
}

/** Nights — the book the nights are kept in. */
export function IconBook(p: IconProps) {
  return (
    <Svg {...p}>
      <path d="M4.5 5.5A2.5 2.5 0 0 1 7 3h12.5v15H7a2.5 2.5 0 0 0-2.5 2.5z" />
      <path d="M4.5 20.5A2.5 2.5 0 0 1 7 18h12.5v3H7" />
      <path d="M8.5 7.5h7M8.5 11h4.5" />
    </Svg>
  )
}

/** Settings — sliders, which is what the screen actually is. */
export function IconSliders(p: IconProps) {
  return (
    <Svg {...p}>
      <path d="M4 7h9.5M17.5 7H20M4 12h2.5M10.5 12H20M4 17h9.5M17.5 17H20" />
      <circle cx="15.5" cy="7" r="2" />
      <circle cx="8.5" cy="12" r="2" />
      <circle cx="15.5" cy="17" r="2" />
    </Svg>
  )
}

/** The rota — two people, because a pub night is never one. */
export function IconPeople(p: IconProps) {
  return (
    <Svg {...p}>
      <circle cx="9" cy="8" r="3.4" />
      <path d="M2.8 20.2a6.5 6.5 0 0 1 12.4 0" />
      <path d="M16.4 5.2a3.4 3.4 0 0 1 0 6.5" />
      <path d="M18.2 14.4a6.5 6.5 0 0 1 3.1 4.6" />
    </Svg>
  )
}

export function IconCamera(p: IconProps) {
  return (
    <Svg {...p}>
      <path d="M4 8.5h2.6a1 1 0 0 0 .8-.4l1.4-2.1a1 1 0 0 1 .8-.5h4.8a1 1 0 0 1 .8.5l1.4 2.1a1 1 0 0 0 .8.4H20a1 1 0 0 1 1 1V18a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9.5a1 1 0 0 1 1-1z" />
      <circle cx="12" cy="13.4" r="3.1" />
    </Svg>
  )
}

/** The till roll, torn off at the bottom the way they come off the printer. */
export function IconReceipt(p: IconProps) {
  return (
    <Svg {...p}>
      <path d="M6 3h12v18l-2-1.6-2 1.6-2-1.6L10 21l-2-1.6L6 21z" />
      <path d="M9.2 7.5h5.6M9.2 11h5.6M9.2 14.5h3" />
    </Svg>
  )
}

export function IconCheck(p: IconProps) {
  return (
    <Svg {...p}>
      <path d="M4.5 12.8l4.8 4.7L19.5 6.5" />
    </Svg>
  )
}

export function IconAlert(p: IconProps) {
  return (
    <Svg {...p}>
      <path d="M12 3.6 21.4 20H2.6z" />
      <path d="M12 9.8v4" />
      <circle cx="12" cy="16.9" r="0.4" fill="currentColor" stroke="none" />
    </Svg>
  )
}

export function IconClock(p: IconProps) {
  return (
    <Svg {...p}>
      <circle cx="12" cy="12" r="8.6" />
      <path d="M12 7.2V12l3.4 2" />
    </Svg>
  )
}

export function IconChevronRight(p: IconProps) {
  return (
    <Svg {...p}>
      <path d="M9.5 5.5 16 12l-6.5 6.5" />
    </Svg>
  )
}

/** A small tick for list rows, filled so it reads at 14px. */
export function IconTickSmall({ size = 16 }: IconProps) {
  return (
    <svg
      className="icon"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={3}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <path d="M4.5 12.8l4.8 4.7L19.5 6.5" />
    </svg>
  )
}
