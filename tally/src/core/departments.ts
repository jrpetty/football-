// ---------------------------------------------------------------------------
// The department registry.
//
// The till prints the same layout every night, so these codes are stable rather
// than discovered — which lets each department own a fixed colour slot for the
// whole life of the app. That matters: colour has to follow the entity, never
// its position in a list. Filter Wine out of the dashboard and Mixers must not
// inherit Wine's colour, or every chart the reader has learned silently lies.
//
// D06 has never appeared on a roll, but the numbering jumps D05 -> D07, so the
// till plainly has one configured with no sales against it. It keeps its slot.
// ---------------------------------------------------------------------------

export interface DepartmentMeta {
  code: string
  /** As the till prints it. */
  printed: string
  /** As a person would say it. */
  label: string
  /** 1-8, indexing the validated categorical palette. Never reassigned. */
  slot: number
}

export const DEPARTMENTS: DepartmentMeta[] = [
  { code: 'D01', printed: 'DRAUGHT BEERS', label: 'Draught beers', slot: 1 },
  { code: 'D02', printed: 'SPIRITS', label: 'Spirits', slot: 2 },
  { code: 'D03', printed: 'WINE', label: 'Wine', slot: 3 },
  { code: 'D04', printed: 'BOTTLED BEERS', label: 'Bottled beers', slot: 4 },
  { code: 'D05', printed: 'MIXERS', label: 'Mixers', slot: 5 },
  { code: 'D06', printed: '', label: 'Department 6', slot: 6 },
  { code: 'D07', printed: 'SUNDRIES', label: 'Sundries', slot: 7 },
  { code: 'D08', printed: 'OPEN FOOD', label: 'Open food', slot: 8 },
]

const BY_CODE = new Map(DEPARTMENTS.map((d) => [d.code, d]))

export function departmentMeta(code: string): DepartmentMeta | undefined {
  return BY_CODE.get(code.toUpperCase())
}

/** A readable name, falling back to whatever the till printed for a new code. */
export function departmentLabel(code: string, printed?: string): string {
  const meta = departmentMeta(code)
  if (meta && meta.printed) return meta.label
  if (printed) {
    // "BOTTLED BEERS" -> "Bottled beers"
    const lower = printed.toLowerCase()
    return lower.charAt(0).toUpperCase() + lower.slice(1)
  }
  return meta?.label ?? code
}

/**
 * The palette slot for a department.
 *
 * A code the till has never printed before falls to slot 8 rather than
 * generating a colour: a made-up ninth hue is indistinguishable from an
 * existing one to a colourblind reader and breaks the palette's guarantees.
 */
export function departmentSlot(code: string): number {
  return departmentMeta(code)?.slot ?? 8
}

/** Registry order, so a chart's legend never reshuffles between nights. */
export function sortByRegistry<T extends { code: string }>(items: T[]): T[] {
  const order = new Map(DEPARTMENTS.map((d, i) => [d.code, i]))
  return [...items].sort(
    (a, b) => (order.get(a.code) ?? 99) - (order.get(b.code) ?? 99) || a.code.localeCompare(b.code),
  )
}
