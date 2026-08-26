// ---------------------------------------------------------------------------
// Counting the drawer.
//
// Two jobs that sound like one.
//
// COUNTING. The drawer is counted in coins and notes, not in pounds: eleven
// twenties, six tens, a bag of pound coins. Adding that up in your head at
// midnight is where the arithmetic mistakes live, and an arithmetic mistake
// looks exactly like a shortfall — the app would report a missing £20 that was
// never missing, and someone would spend an hour looking for it. So the counting
// is done here, in integers, from what she actually has in front of her.
//
// THE FLOAT. Whatever is left in the drawer to make change with tomorrow is not
// takings. It has to come off before the count is compared with the till, and if
// it does not, every single night reads over by exactly the float — a fault that
// is easy to miss precisely because it is so consistent. It looks like the pub
// is doing well.
//
// So the app holds two different numbers and never confuses them:
//
//     drawer counted  =  takings  +  float
//     takings         =  what the till says should be there
//
// Only the takings figure reconciles. The float is recorded so the night can be
// read back honestly, and because a float that quietly changes size is itself
// worth being able to see.
// ---------------------------------------------------------------------------

export interface Denomination {
  pence: number
  /** "£20", "50p" — how it is spoken about. */
  label: string
  kind: 'note' | 'coin'
}

/** Everything a British till drawer holds, biggest first — the counting order. */
export const DENOMINATIONS: readonly Denomination[] = [
  { pence: 5000, label: '£50', kind: 'note' },
  { pence: 2000, label: '£20', kind: 'note' },
  { pence: 1000, label: '£10', kind: 'note' },
  { pence: 500, label: '£5', kind: 'note' },
  { pence: 200, label: '£2', kind: 'coin' },
  { pence: 100, label: '£1', kind: 'coin' },
  { pence: 50, label: '50p', kind: 'coin' },
  { pence: 20, label: '20p', kind: 'coin' },
  { pence: 10, label: '10p', kind: 'coin' },
  { pence: 5, label: '5p', kind: 'coin' },
  { pence: 2, label: '2p', kind: 'coin' },
  { pence: 1, label: '1p', kind: 'coin' },
]

/** How many of each denomination, keyed by its value in pence. */
export type Tally = Readonly<Record<number, number>>

/**
 * What the counted drawer comes to.
 *
 * Anything that is not a whole, non-negative count of a denomination the app
 * knows about is ignored rather than guessed at — a half-typed "1" mid-keystroke
 * must not make the total flicker through a wrong figure, and a stray key must
 * not silently add a hundred pounds.
 */
export function countTotal(tally: Tally): number {
  let total = 0
  for (const d of DENOMINATIONS) {
    const n = tally[d.pence]
    if (typeof n !== 'number' || !Number.isFinite(n) || n <= 0) continue
    total += Math.floor(n) * d.pence
  }
  return total
}

/** How many notes and coins were counted, for a sanity line under the total. */
export function countPieces(tally: Tally): { notes: number; coins: number } {
  let notes = 0
  let coins = 0
  for (const d of DENOMINATIONS) {
    const n = tally[d.pence]
    if (typeof n !== 'number' || !Number.isFinite(n) || n <= 0) continue
    if (d.kind === 'note') notes += Math.floor(n)
    else coins += Math.floor(n)
  }
  return { notes, coins }
}

export interface DrawerSplit {
  /** Everything in the drawer, float included. */
  drawerPence: number
  /** What stays behind to make change with tomorrow. */
  floatPence: number
  /** Drawer less float — the only figure that reconciles. */
  takingsPence: number
  /**
   * True when the float is more than was in the drawer, which cannot be right.
   * Reported rather than clamped, because silently showing zero takings on a
   * night that took £400 would be worse than saying the figures disagree.
   */
  impossible: boolean
}

/** Split a counted drawer into the takings and the float left behind. */
export function splitDrawer(drawerPence: number, floatPence: number): DrawerSplit {
  const float = Math.max(0, floatPence)
  return {
    drawerPence,
    floatPence: float,
    takingsPence: drawerPence - float,
    impossible: float > drawerPence,
  }
}

/**
 * A sensible mix of change to leave as tomorrow's float.
 *
 * A float is for giving change, so what matters is depth in the small stuff,
 * not the fewest pieces. Filling greedily from the largest note down — the
 * obvious algorithm — hands back a £200 float as four fifties, which cannot
 * make change for a fiver and is the opposite of what a float is for.
 *
 * So the silver and pound coins are laid in first, to a depth a bar actually
 * works from, and only what is left over goes into notes, smallest useful
 * first and capped. Fifties never appear: nobody floats a fifty.
 */
const WORKING_DEPTH: ReadonlyArray<readonly [number, number]> = [
  [100, 20], // £20 in pound coins
  [50, 20],  // £10 in fifties
  [20, 25],  // £5 in twenties
  [10, 20],  // £2 in tens
  [5, 20],   // £1 in fives
]

/** The most of each note worth putting in a float. £50 is deliberately absent. */
const NOTE_CAP: ReadonlyArray<readonly [number, number]> = [
  [2000, 4],
  [1000, 8],
  [500, 10],
  [200, 10],
  [100, 20],
]

export function suggestFloat(targetPence: number): Tally | null {
  if (!Number.isFinite(targetPence) || targetPence <= 0) return null

  const tally: Record<number, number> = {}
  let left = Math.floor(targetPence)

  const take = (pence: number, count: number) => {
    if (count <= 0) return
    tally[pence] = (tally[pence] ?? 0) + count
    left -= count * pence
  }

  // 1. Change to work from.
  for (const [pence, want] of WORKING_DEPTH) take(pence, Math.min(want, Math.floor(left / pence)))

  // 2. The bulk, in notes a bar can break.
  for (const [pence, cap] of NOTE_CAP) take(pence, Math.min(cap, Math.floor(left / pence)))

  // 3. Anything still outstanding, largest piece first so an odd 37p is four
  //     coins rather than thirty-seven — but still never a fifty.
  for (const d of DENOMINATIONS) {
    if (left <= 0) break
    if (d.pence === 5000) continue
    take(d.pence, Math.floor(left / d.pence))
  }

  return left === 0 ? tally : null
}
