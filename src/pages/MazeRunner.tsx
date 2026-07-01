import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  buildMaze, COLS, ROWS, GLADE, idx, isGlade, DAYS_PER_WEEK, dayOfWeek, weekdayName,
  type Maze, type Point,
} from '../game/maze'

// --------------------------------------------------------------- Tuning
const CELL = 24 // px per cell in the main viewport
const VIEW_COLS = 23 // odd → player stays centred
const VIEW_ROWS = 15
const REVEAL_R = 3 // fog-of-war reveal radius (Chebyshev)
const HINT_MS = 3500
const STORAGE_KEY = 'gaffer.maze.v1'
const MM_SCALE = 2 // minimap pixels per cell

// --------------------------------------------------------------- Best scores
interface BestRecord { timeMs: number; moves: number }
type BestMap = Record<number, BestRecord>

function loadBest(): BestMap {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as BestMap) : {}
  } catch {
    return {}
  }
}
function saveBest(b: BestMap) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(b))
  } catch {
    /* storage unavailable — non-fatal */
  }
}

// --------------------------------------------------------------- Helpers
function clamp(v: number, lo: number, hi: number): number {
  return v < lo ? lo : v > hi ? hi : v
}

function fmtTime(ms: number): string {
  const total = Math.floor(ms / 100)
  const tenths = total % 10
  const secs = Math.floor(total / 10) % 60
  const mins = Math.floor(total / 600)
  return `${mins}:${String(secs).padStart(2, '0')}.${tenths}`
}

function revealAround(set: Set<number>, cx: number, cy: number) {
  for (let dy = -REVEAL_R; dy <= REVEAL_R; dy++) {
    for (let dx = -REVEAL_R; dx <= REVEAL_R; dx++) {
      const nx = cx + dx
      const ny = cy + dy
      if (nx < 0 || ny < 0 || nx >= COLS || ny >= ROWS) continue
      set.add(idx(nx, ny))
    }
  }
}

function initialDiscovered(m: Maze): Set<number> {
  const set = new Set<number>()
  // The glade (home plains) is known from the start.
  for (let y = GLADE.y0; y <= GLADE.y1; y++)
    for (let x = GLADE.x0; x <= GLADE.x1; x++) set.add(idx(x, y))
  revealAround(set, m.spawn.x, m.spawn.y)
  return set
}

// --------------------------------------------------------------- Minimap
function Minimap({
  maze, pos, discovered, camX, camY,
}: {
  maze: Maze; pos: Point; discovered: Set<number>; camX: number; camY: number
}) {
  const ref = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    const canvas = ref.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    ctx.fillStyle = '#05070d'
    ctx.fillRect(0, 0, canvas.width, canvas.height)
    for (let y = 0; y < ROWS; y++) {
      for (let x = 0; x < COLS; x++) {
        const i = idx(x, y)
        if (!discovered.has(i)) continue
        if (isGlade(x, y)) ctx.fillStyle = '#1f9d63'
        else if (maze.walls[i]) ctx.fillStyle = '#28344e'
        else ctx.fillStyle = '#5f7796'
        ctx.fillRect(x * MM_SCALE, y * MM_SCALE, MM_SCALE, MM_SCALE)
      }
    }
    // Exit — only once you've found it.
    if (discovered.has(idx(maze.exit.x, maze.exit.y))) {
      ctx.fillStyle = '#22d3ee'
      ctx.fillRect(maze.exit.x * MM_SCALE - 1, maze.exit.y * MM_SCALE - 1, 4, 4)
    }
    // Viewport rectangle.
    ctx.strokeStyle = 'rgba(56,189,248,0.55)'
    ctx.lineWidth = 1
    ctx.strokeRect(camX * MM_SCALE, camY * MM_SCALE, VIEW_COLS * MM_SCALE, VIEW_ROWS * MM_SCALE)
    // Player.
    ctx.fillStyle = '#f59e0b'
    ctx.fillRect(pos.x * MM_SCALE - 1, pos.y * MM_SCALE - 1, 4, 4)
  }, [maze, pos, discovered, camX, camY])

  return (
    <canvas
      ref={ref}
      width={COLS * MM_SCALE}
      height={ROWS * MM_SCALE}
      className="maze-minimap"
      aria-label="Map overview"
    />
  )
}

// --------------------------------------------------------------- Page
export function MazeRunner() {
  const today = useMemo(() => dayOfWeek(new Date()), [])
  const [day, setDay] = useState(today)
  const maze = useMemo(() => buildMaze(day), [day])

  const [pos, setPos] = useState<Point>(maze.spawn)
  const [facing, setFacing] = useState<Point>({ x: 0, y: 1 })
  const [discovered, setDiscovered] = useState<Set<number>>(() => initialDiscovered(maze))
  const [moves, setMoves] = useState(0)
  const [startedAt, setStartedAt] = useState<number | null>(null)
  const [elapsed, setElapsed] = useState(0)
  const [won, setWon] = useState(false)
  const [hint, setHint] = useState(false)
  const [best, setBest] = useState<BestMap>(loadBest)

  // Fresh run whenever the maze (i.e. the day) changes, or on Restart.
  const resetRun = useCallback(() => {
    setPos(maze.spawn)
    setFacing({ x: 0, y: 1 })
    setDiscovered(initialDiscovered(maze))
    setMoves(0)
    setStartedAt(null)
    setElapsed(0)
    setWon(false)
    setHint(false)
  }, [maze])
  useEffect(() => { resetRun() }, [resetRun])

  // Running timer.
  useEffect(() => {
    if (startedAt === null || won) return
    const id = window.setInterval(() => setElapsed(Date.now() - startedAt), 100)
    return () => window.clearInterval(id)
  }, [startedAt, won])

  // Auto-hide the hint.
  useEffect(() => {
    if (!hint) return
    const id = window.setTimeout(() => setHint(false), HINT_MS)
    return () => window.clearTimeout(id)
  }, [hint])

  const move = useCallback(
    (dx: number, dy: number) => {
      if (won) return
      setFacing({ x: dx, y: dy })
      const nx = pos.x + dx
      const ny = pos.y + dy
      if (nx < 0 || ny < 0 || nx >= COLS || ny >= ROWS) return
      if (maze.walls[idx(nx, ny)] === 1) return // blocked by a wall

      setPos({ x: nx, y: ny })
      setMoves((m) => m + 1)
      if (startedAt === null) setStartedAt(Date.now())
      setDiscovered((prev) => {
        const next = new Set(prev)
        revealAround(next, nx, ny)
        return next
      })

      if (nx === maze.exit.x && ny === maze.exit.y) {
        setWon(true)
        const timeMs = startedAt ? Date.now() - startedAt : 0
        const finalMoves = moves + 1
        const prevBest = best[day]
        if (!prevBest || timeMs < prevBest.timeMs) {
          const nb = { ...best, [day]: { timeMs, moves: finalMoves } }
          setBest(nb)
          saveBest(nb)
        }
      }
    },
    [won, pos, maze, startedAt, moves, best, day],
  )

  // Keyboard controls.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const k = e.key.toLowerCase()
      if (k === 'arrowup' || k === 'w') { e.preventDefault(); move(0, -1) }
      else if (k === 'arrowdown' || k === 's') { e.preventDefault(); move(0, 1) }
      else if (k === 'arrowleft' || k === 'a') { e.preventDefault(); move(-1, 0) }
      else if (k === 'arrowright' || k === 'd') { e.preventDefault(); move(1, 0) }
      else if (k === 'r') resetRun()
      else if (k === 'h') setHint(true)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [move, resetRun])

  // Camera follows the player, clamped to the map edges.
  const camX = clamp(pos.x - (VIEW_COLS >> 1), 0, COLS - VIEW_COLS)
  const camY = clamp(pos.y - (VIEW_ROWS >> 1), 0, ROWS - VIEW_ROWS)

  const bestRec = best[day]

  // --------------------------------------------------------------- Cells
  const cells: React.ReactNode[] = []
  for (let vy = 0; vy < VIEW_ROWS; vy++) {
    for (let vx = 0; vx < VIEW_COLS; vx++) {
      const cx = camX + vx
      const cy = camY + vy
      const i = idx(cx, cy)
      const sx = vx * CELL
      const sy = vy * CELL
      let fill: string
      if (!discovered.has(i)) fill = '#0a0e18' // fog of war
      else if (isGlade(cx, cy)) fill = '#123a26' // glade plains
      else if (maze.walls[i]) fill = '#313d59' // stone wall
      else fill = '#0d1524' // corridor floor
      cells.push(
        <rect key={i} x={sx} y={sy} width={CELL} height={CELL} fill={fill} />,
      )
      // A subtle top highlight on discovered walls for a carved-stone look.
      if (discovered.has(i) && maze.walls[i] && !isGlade(cx, cy)) {
        cells.push(
          <rect key={`h${i}`} x={sx} y={sy} width={CELL} height={2} fill="#465577" />,
        )
      }
    }
  }

  // Glade glow (drawn over the glade region, clipped to the board).
  const gladeGlow = (
    <rect
      x={(GLADE.x0 - camX) * CELL}
      y={(GLADE.y0 - camY) * CELL}
      width={(GLADE.x1 - GLADE.x0 + 1) * CELL}
      height={(GLADE.y1 - GLADE.y0 + 1) * CELL}
      fill="url(#gladeGlow)"
      pointerEvents="none"
    />
  )

  // Exit portal (only rendered once discovered).
  const exitDiscovered = discovered.has(idx(maze.exit.x, maze.exit.y))
  const exitNode = exitDiscovered && (
    <rect
      className="exit-portal"
      x={(maze.exit.x - camX) * CELL}
      y={(maze.exit.y - camY) * CELL}
      width={CELL}
      height={CELL}
      fill="url(#exitGlow)"
      stroke="#67e8f9"
      strokeWidth={2}
      rx={3}
    />
  )

  // Hint: the solution path, clipped to the board.
  const hintNode = hint && maze.solution.length > 0 && (
    <polyline
      className="hint-path"
      points={maze.solution
        .map((c) => {
          const px = (c % COLS) - camX
          const py = Math.floor(c / COLS) - camY
          return `${px * CELL + CELL / 2},${py * CELL + CELL / 2}`
        })
        .join(' ')}
      fill="none"
      stroke="rgba(56,189,248,0.9)"
      strokeWidth={3}
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  )

  // Runner glyph + facing chevron.
  const px = (pos.x - camX) * CELL + CELL / 2
  const py = (pos.y - camY) * CELL + CELL / 2
  const chevronAngle = Math.atan2(facing.y, facing.x) * (180 / Math.PI)

  return (
    <div className="content">
      <div className="maze-layout">
        {/* -------------------------------------------------- Board */}
        <div className="maze-board-wrap">
          <svg
            className="maze-board"
            viewBox={`0 0 ${VIEW_COLS * CELL} ${VIEW_ROWS * CELL}`}
            role="img"
            aria-label="Maze"
          >
            <defs>
              <radialGradient id="gladeGlow" cx="50%" cy="50%" r="60%">
                <stop offset="0%" stopColor="rgba(34,197,94,0.28)" />
                <stop offset="100%" stopColor="rgba(34,197,94,0)" />
              </radialGradient>
              <radialGradient id="exitGlow" cx="50%" cy="50%" r="60%">
                <stop offset="0%" stopColor="#a5f3fc" />
                <stop offset="70%" stopColor="#22d3ee" />
                <stop offset="100%" stopColor="#0e7490" />
              </radialGradient>
              <radialGradient id="runnerGlow" cx="50%" cy="50%" r="55%">
                <stop offset="0%" stopColor="#fde68a" />
                <stop offset="60%" stopColor="#f59e0b" />
                <stop offset="100%" stopColor="#b45309" />
              </radialGradient>
            </defs>

            {cells}
            {gladeGlow}
            {hintNode}
            {exitNode}

            {/* Runner */}
            <g className="runner-glow">
              <circle cx={px} cy={py} r={CELL * 0.5} fill="rgba(245,158,11,0.18)" />
              <circle
                cx={px}
                cy={py}
                r={CELL * 0.32}
                fill="url(#runnerGlow)"
                stroke="#fef3c7"
                strokeWidth={1.5}
              />
              <path
                d={`M ${px + CELL * 0.5} ${py} L ${px + CELL * 0.24} ${py - CELL * 0.16} L ${px + CELL * 0.24} ${py + CELL * 0.16} Z`}
                fill="#fef3c7"
                transform={`rotate(${chevronAngle} ${px} ${py})`}
              />
            </g>
          </svg>

          {won && (
            <div className="maze-win">
              <div className="panel">
                <div className="big">🏁</div>
                <h2>You escaped!</h2>
                <p style={{ color: 'var(--text-dim)', marginBottom: 14 }}>
                  Day {day} · {weekdayName(day)} maze cleared in{' '}
                  <strong style={{ color: 'var(--text)' }}>{fmtTime(elapsed)}</strong> and{' '}
                  <strong style={{ color: 'var(--text)' }}>{moves}</strong> moves.
                </p>
                <div style={{ display: 'flex', gap: 8, justifyContent: 'center' }}>
                  <button className="btn primary" onClick={resetRun}>
                    Play again
                  </button>
                  <button
                    className="btn ghost"
                    onClick={() => setDay((d) => (d % DAYS_PER_WEEK) + 1)}
                  >
                    Next day →
                  </button>
                </div>
              </div>
            </div>
          )}
        </div>

        {/* -------------------------------------------------- Sidebar */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div className="card card-pad" style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <div>
              <div style={{ fontSize: 12, color: 'var(--text-faint)', textTransform: 'uppercase', letterSpacing: '0.1em' }}>
                Today
              </div>
              <div style={{ fontSize: 16, fontWeight: 700 }}>
                {weekdayName(today)} · Day {today} of {DAYS_PER_WEEK}
              </div>
              {day !== today && (
                <div style={{ fontSize: 12, color: 'var(--amber)', marginTop: 2 }}>
                  Previewing Day {day} ({weekdayName(day)})
                </div>
              )}
            </div>

            <div className="maze-days" role="group" aria-label="Choose day">
              {Array.from({ length: DAYS_PER_WEEK }, (_, k) => k + 1).map((d) => (
                <button
                  key={d}
                  className={`${d === day ? 'active' : ''} ${d === today ? 'today' : ''}`.trim()}
                  onClick={() => setDay(d)}
                  title={`${weekdayName(d)}${d === today ? ' (today)' : ''}`}
                >
                  {d}
                </button>
              ))}
            </div>
            <div style={{ fontSize: 11.5, color: 'var(--text-faint)' }}>
              The maze reshapes each day and resets to the Day 1 format every Monday.
            </div>
          </div>

          <div className="card card-pad">
            <div className="maze-stats">
              <div className="maze-stat">
                <div className="k">Time</div>
                <div className="v">{fmtTime(elapsed)}</div>
              </div>
              <div className="maze-stat">
                <div className="k">Moves</div>
                <div className="v">{moves}</div>
              </div>
            </div>
            <div className="maze-stats" style={{ marginTop: 10 }}>
              <div className="maze-stat">
                <div className="k">Best time</div>
                <div className="v" style={{ fontSize: 15 }}>
                  {bestRec ? fmtTime(bestRec.timeMs) : '—'}
                </div>
              </div>
              <div className="maze-stat">
                <div className="k">Best moves</div>
                <div className="v" style={{ fontSize: 15 }}>{bestRec ? bestRec.moves : '—'}</div>
              </div>
            </div>
            <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
              <button className="btn ghost sm" style={{ flex: 1 }} onClick={resetRun}>
                ↺ Restart
              </button>
              <button className="btn ghost sm" style={{ flex: 1 }} onClick={() => setHint(true)}>
                💡 Hint
              </button>
            </div>
          </div>

          <div className="card card-pad">
            <div style={{ fontSize: 12, color: 'var(--text-faint)', textTransform: 'uppercase', letterSpacing: '0.1em', marginBottom: 8 }}>
              Map
            </div>
            <Minimap maze={maze} pos={pos} discovered={discovered} camX={camX} camY={camY} />
            <div className="maze-legend" style={{ marginTop: 10 }}>
              <span><span className="sw" style={{ background: '#1f9d63' }} />Glade</span>
              <span><span className="sw" style={{ background: '#5f7796' }} />Path</span>
              <span><span className="sw" style={{ background: '#28344e' }} />Wall</span>
              <span><span className="sw" style={{ background: '#22d3ee' }} />Exit</span>
            </div>
          </div>

          {/* Touch controls */}
          <div className="card card-pad">
            <div className="dpad">
              <span className="sp" />
              <button className="btn ghost" onClick={() => move(0, -1)} aria-label="Up">▲</button>
              <span className="sp" />
              <button className="btn ghost" onClick={() => move(-1, 0)} aria-label="Left">◀</button>
              <span className="sp" />
              <button className="btn ghost" onClick={() => move(1, 0)} aria-label="Right">▶</button>
              <span className="sp" />
              <button className="btn ghost" onClick={() => move(0, 1)} aria-label="Down">▼</button>
              <span className="sp" />
            </div>
            <div style={{ fontSize: 11.5, color: 'var(--text-faint)', textAlign: 'center', marginTop: 10 }}>
              Arrow keys / WASD to run · R to restart · H for a hint
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
