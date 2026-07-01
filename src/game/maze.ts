// ===========================================================================
// Maze Runner — deterministic maze engine
// ---------------------------------------------------------------------------
// A huge map with a central open "Glade" (plains) of 8 chunks, ringed by a
// maze. The maze layout is a pure function of the day-of-week (1..7) only, so:
//   • every week repeats the same 7-day sequence,
//   • Day 1 is always the canonical "first format",
//   • when a week ends the maze returns to Day 1's format automatically.
//
// The module is pure (no React, no DOM) so it can be unit-tested and reasoned
// about in isolation. Solvability is guaranteed by construction and verified
// with a breadth-first search on every build.
// ===========================================================================

// --------------------------------------------------------------- Dimensions
export const CHUNK = 8 // cells per chunk edge
export const MAP_CHUNKS_X = 12
export const MAP_CHUNKS_Y = 10
export const GLADE_CHUNKS_X = 4 // 4 × 2 = 8 chunks of central plains
export const GLADE_CHUNKS_Y = 2
export const DAYS_PER_WEEK = 7

export const COLS = MAP_CHUNKS_X * CHUNK // 96
export const ROWS = MAP_CHUNKS_Y * CHUNK // 80

// Centred glade (in cell coordinates), inclusive bounds.
const GLADE_CX0 = ((MAP_CHUNKS_X - GLADE_CHUNKS_X) / 2) * CHUNK // 32
const GLADE_CY0 = ((MAP_CHUNKS_Y - GLADE_CHUNKS_Y) / 2) * CHUNK // 32
export const GLADE = {
  x0: GLADE_CX0,
  y0: GLADE_CY0,
  x1: GLADE_CX0 + GLADE_CHUNKS_X * CHUNK - 1, // 63
  y1: GLADE_CY0 + GLADE_CHUNKS_Y * CHUNK - 1, // 47
}

// Deterministic per-day seeds. Index 0 → Day 1 (the canonical first format).
// A fixed weekly schedule keeps the whole thing reproducible and reset-safe.
const DAY_SEEDS = [
  0x1a2b3c4d, 0x5e6f7a8b, 0x9c0d1e2f, 0x3a4b5c6d, 0x7e8f9a0b, 0xb1c2d3e4, 0xf5061728,
]

// --------------------------------------------------------------- Public shape
export interface Point {
  x: number
  y: number
}

export interface Maze {
  day: number // 1..7
  cols: number
  rows: number
  walls: Uint8Array // 1 = wall, 0 = floor, indexed y * cols + x
  glade: typeof GLADE
  spawn: Point
  exit: Point
  solution: number[] // cell indices from spawn → exit (BFS shortest path)
}

// --------------------------------------------------------------- Helpers
export function idx(x: number, y: number): number {
  return y * COLS + x
}

export function isGlade(x: number, y: number): boolean {
  return x >= GLADE.x0 && x <= GLADE.x1 && y >= GLADE.y0 && y <= GLADE.y1
}

function inBounds(x: number, y: number): boolean {
  return x >= 0 && y >= 0 && x < COLS && y < ROWS
}

// mulberry32 — small, fast, deterministic PRNG.
function mulberry32(seed: number): () => number {
  let a = seed >>> 0
  return function () {
    a |= 0
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

// Snap to the nearest odd value that is a valid maze node line, i.e. within
// [1, largest-odd ≤ hi-2]. Nodes live on odd coordinates.
function snapOdd(v: number, hi: number): number {
  let n = Math.round(v)
  if (n % 2 === 0) n -= 1 // round down to odd
  if (n < 1) n = 1
  const max = hi % 2 === 0 ? hi - 3 : hi - 2 // largest odd ≤ hi-2
  if (n > max) n = max
  return n
}

// The exit sits on the outer border. Its position rotates clockwise around the
// perimeter by day, giving a structured day-to-day change. Day 1 → top-centre.
function exitForDay(d: number): Point {
  const oc = (f: number) => snapOdd(COLS * f, COLS) // odd column
  const or = (f: number) => snapOdd(ROWS * f, ROWS) // odd row
  const ring: Point[] = [
    { x: oc(0.5), y: 0 }, // Day 1 — top centre
    { x: COLS - 1, y: or(0.28) }, // Day 2 — right upper
    { x: COLS - 1, y: or(0.72) }, // Day 3 — right lower
    { x: oc(0.72), y: ROWS - 1 }, // Day 4 — bottom right
    { x: oc(0.28), y: ROWS - 1 }, // Day 5 — bottom left
    { x: 0, y: or(0.72) }, // Day 6 — left lower
    { x: 0, y: or(0.28) }, // Day 7 — left upper
  ]
  return ring[d]
}

// --------------------------------------------------------------- Generation
// Recursive-backtracker perfect maze on a grid of nodes at odd coordinates.
// Walls between nodes live at the even coordinate between them. Every node is
// reachable from every other (a "perfect" maze), which guarantees solvability
// once the glade and exit are attached.
function carveMaze(walls: Uint8Array, rng: () => number): void {
  const nodeXs: number[] = []
  for (let x = 1; x <= COLS - 2; x += 2) nodeXs.push(x)
  const nodeYs: number[] = []
  for (let y = 1; y <= ROWS - 2; y += 2) nodeYs.push(y)
  const maxX = nodeXs[nodeXs.length - 1]
  const maxY = nodeYs[nodeYs.length - 1]

  const visited = new Uint8Array(COLS * ROWS)
  // Start from the node nearest the glade centre so the maze roots at home.
  const startX = snapOdd((GLADE.x0 + GLADE.x1) / 2, COLS)
  const startY = snapOdd((GLADE.y0 + GLADE.y1) / 2, ROWS)

  const stack: Point[] = [{ x: startX, y: startY }]
  visited[idx(startX, startY)] = 1
  walls[idx(startX, startY)] = 0

  const dirs = [
    { dx: 0, dy: -2 },
    { dx: 2, dy: 0 },
    { dx: 0, dy: 2 },
    { dx: -2, dy: 0 },
  ]

  while (stack.length) {
    const cur = stack[stack.length - 1]
    // Collect unvisited neighbours two cells away.
    const options: { nx: number; ny: number; wx: number; wy: number }[] = []
    for (const dir of dirs) {
      const nx = cur.x + dir.dx
      const ny = cur.y + dir.dy
      if (nx < 1 || ny < 1 || nx > maxX || ny > maxY) continue
      if (visited[idx(nx, ny)]) continue
      options.push({ nx, ny, wx: cur.x + dir.dx / 2, wy: cur.y + dir.dy / 2 })
    }
    if (!options.length) {
      stack.pop()
      continue
    }
    const pick = options[Math.floor(rng() * options.length)]
    walls[idx(pick.wx, pick.wy)] = 0 // knock down the wall between
    walls[idx(pick.nx, pick.ny)] = 0 // open the neighbour node
    visited[idx(pick.nx, pick.ny)] = 1
    stack.push({ x: pick.nx, y: pick.ny })
  }
}

// Breadth-first shortest path over floor cells. Returns cell indices from
// start → goal, or null if unreachable.
function bfsPath(walls: Uint8Array, start: Point, goal: Point): number[] | null {
  const startI = idx(start.x, start.y)
  const goalI = idx(goal.x, goal.y)
  if (walls[startI] || walls[goalI]) return null
  const prev = new Int32Array(COLS * ROWS).fill(-1)
  const seen = new Uint8Array(COLS * ROWS)
  const queue = new Int32Array(COLS * ROWS)
  let head = 0
  let tail = 0
  queue[tail++] = startI
  seen[startI] = 1
  const steps = [
    [0, -1],
    [1, 0],
    [0, 1],
    [-1, 0],
  ]
  while (head < tail) {
    const cur = queue[head++]
    if (cur === goalI) break
    const cx = cur % COLS
    const cy = (cur - cx) / COLS
    for (const [dx, dy] of steps) {
      const nx = cx + dx
      const ny = cy + dy
      if (!inBounds(nx, ny)) continue
      const ni = idx(nx, ny)
      if (seen[ni] || walls[ni]) continue
      seen[ni] = 1
      prev[ni] = cur
      queue[tail++] = ni
    }
  }
  if (!seen[goalI]) return null
  const path: number[] = []
  let node = goalI
  while (node !== -1) {
    path.push(node)
    if (node === startI) break
    node = prev[node]
  }
  return path.reverse()
}

// Join a border exit to the maze by carving a short stub inward until it meets
// an existing floor cell. The exit's varying coordinate is snapped to an odd
// node line, so this reaches the network within one or two cells.
function attachExit(walls: Uint8Array, exit: Point): void {
  let dx = 0
  let dy = 0
  if (exit.y === 0) dy = 1
  else if (exit.y === ROWS - 1) dy = -1
  else if (exit.x === 0) dx = 1
  else if (exit.x === COLS - 1) dx = -1

  walls[idx(exit.x, exit.y)] = 0
  let x = exit.x + dx
  let y = exit.y + dy
  let guard = 0
  while (inBounds(x, y) && guard++ < 6) {
    if (walls[idx(x, y)] === 0) break // reached the maze network
    walls[idx(x, y)] = 0
    x += dx
    y += dy
  }
}

// Carve a straight-ish corridor from a → b as a solvability fail-safe. In
// practice the perfect maze always connects, so this only runs defensively.
function carveTunnel(walls: Uint8Array, a: Point, b: Point): void {
  let { x, y } = a
  const clampX = (v: number) => Math.max(0, Math.min(COLS - 1, v))
  const clampY = (v: number) => Math.max(0, Math.min(ROWS - 1, v))
  let guard = 0
  while ((x !== b.x || y !== b.y) && guard++ < COLS * ROWS) {
    walls[idx(x, y)] = 0
    if (x !== b.x) x = clampX(x + Math.sign(b.x - x))
    else if (y !== b.y) y = clampY(y + Math.sign(b.y - y))
  }
  walls[idx(b.x, b.y)] = 0
}

// --------------------------------------------------------------- Build
export function buildMaze(day: number): Maze {
  const d = (((day - 1) % DAYS_PER_WEEK) + DAYS_PER_WEEK) % DAYS_PER_WEEK // 0..6
  const walls = new Uint8Array(COLS * ROWS).fill(1) // everything solid to start
  const rng = mulberry32(DAY_SEEDS[d])

  carveMaze(walls, rng)

  // Open the central glade — 8 chunks of plains, fully walkable.
  for (let y = GLADE.y0; y <= GLADE.y1; y++) {
    for (let x = GLADE.x0; x <= GLADE.x1; x++) {
      walls[idx(x, y)] = 0
    }
  }

  const spawn: Point = {
    x: Math.floor((GLADE.x0 + GLADE.x1) / 2),
    y: Math.floor((GLADE.y0 + GLADE.y1) / 2),
  }

  // Punch the exit through the border and stub it into the maze network.
  const exit = exitForDay(d)
  attachExit(walls, exit)

  let solution = bfsPath(walls, spawn, exit)
  if (!solution) {
    // Defensive repair — should never trigger with a perfect maze.
    carveTunnel(walls, exit, spawn)
    solution = bfsPath(walls, spawn, exit) ?? []
  }

  return { day: d + 1, cols: COLS, rows: ROWS, walls, glade: GLADE, spawn, exit, solution }
}

// --------------------------------------------------------------- Calendar
const WEEKDAY_NAMES = [
  'Monday',
  'Tuesday',
  'Wednesday',
  'Thursday',
  'Friday',
  'Saturday',
  'Sunday',
]

// Map a JS Date (Sun=0..Sat=6) to the maze day-of-week (Mon=1..Sun=7).
export function dayOfWeek(date: Date): number {
  return ((date.getDay() + 6) % 7) + 1
}

export function weekdayName(day: number): string {
  return WEEKDAY_NAMES[(((day - 1) % DAYS_PER_WEEK) + DAYS_PER_WEEK) % DAYS_PER_WEEK]
}
