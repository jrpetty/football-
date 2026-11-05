import { useEffect, useRef, useState } from 'react'
import { game, SPEEDS } from './game.ts'
import { mind } from './ai/mind.ts'
import { ERAS } from './sim/tech.ts'
import { formatClock, SEASONS, SEASON_ICONS, dayInSeason, yearOf, seasonOf, partOfDay } from './sim/time.ts'
import type { Overlay } from './render/renderer.ts'
import { preferredView, setPreferredView, type ViewMode } from './render/view.ts'
import { DEPOSITS } from './sim/data.ts'
import { useGameVersion, useMindVersion } from './ui/common.tsx'
import { PeoplePanel, TribesPanel, MindsPanel, ChroniclePanel, TechPanel, StatsPanel } from './ui/panels.tsx'
import { Inspector } from './ui/Inspector.tsx'
import { SettingsModal, NewWorldModal, SavesModal } from './ui/modals.tsx'
import { MainMenu, AboutModal } from './ui/MainMenu.tsx'

type Tab = 'minds' | 'people' | 'tribes' | 'chronicle' | 'tech' | 'stats'
type ModalKind = 'settings' | 'new' | 'saves' | 'about' | null

const WEATHER_ICON: Record<string, string> = { clear: '☀️', cloudy: '☁️', rain: '🌧️', storm: '⛈️', snow: '🌨️', fog: '🌫️' }

export default function App() {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const [tab, setTab] = useState<Tab>('minds')
  const [sidebar, setSidebar] = useState(() => window.innerWidth > 860)
  const [modal, setModal] = useState<ModalKind>(null)
  const [menu, setMenu] = useState(() => {
    try { return !new URLSearchParams(location.search).has('play') } catch { return true }
  })

  const [view, setView] = useState<ViewMode>(preferredView)
  useEffect(() => {
    const onView = (e: Event) => {
      const v = (e as CustomEvent<ViewMode>).detail
      setPreferredView(v)
      setView(v)
    }
    window.addEventListener('genesis:view', onView)
    return () => window.removeEventListener('genesis:view', onView)
  }, [])

  useEffect(() => {
    const c = canvasRef.current!
    game.attachCanvas(c, view)
    const onResize = () => game.resize()
    window.addEventListener('resize', onResize)
    return () => {
      window.removeEventListener('resize', onResize)
      game.detach()
    }
  }, [view])

  useMapControls(canvasRef, view)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.target as HTMLElement)?.tagName === 'INPUT' || (e.target as HTMLElement)?.tagName === 'TEXTAREA' || (e.target as HTMLElement)?.tagName === 'SELECT') return
      const r = game.renderer
      if (e.key === ' ') { e.preventDefault(); game.togglePause() }
      else if (e.key >= '1' && e.key <= '5') game.setSpeed(SPEEDS[+e.key - 1])
      else if (e.key === 'Escape' && r) { r.selection = { kind: null, id: 0 }; r.follow = false; game.emit() }
      else if ((e.key === 'f' || e.key === 'F') && r) { r.follow = !r.follow; game.emit() }
      else if ((e.key === '+' || e.key === '=') && r) r.zoomAt(r.vw / 2, r.vh / 2, 1.2)
      else if (e.key === '-' && r) r.zoomAt(r.vw / 2, r.vh / 2, 1 / 1.2)
      else if (r && ['w', 'a', 's', 'd', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(e.key)) {
        const d = 120
        const k = e.key
        r.pan(k === 'a' || k === 'ArrowLeft' ? d : k === 'd' || k === 'ArrowRight' ? -d : 0, k === 'w' || k === 'ArrowUp' ? d : k === 's' || k === 'ArrowDown' ? -d : 0)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])


  return (
    <div className="app">
      <canvas key={view} ref={canvasRef} className="map" />
      {menu && <MainMenu onGo={(g) => { if (g === 'play') setMenu(false); else setModal(g) }} />}
      {!menu && <>
      <TopBar onModal={setModal} onMenu={() => setMenu(true)} />
      <button className="icon-btn sidebar-toggle glass" style={{ display: sidebar ? 'none' : undefined }} onClick={() => setSidebar(true)}>☰ Panels</button>
      <div className={`sidebar glass ${sidebar ? '' : 'closed'}`}>
        <Tabs tab={tab} setTab={setTab} onClose={() => setSidebar(false)} />
        <div className="panel-body">
          {tab === 'minds' && <MindsPanel onSettings={() => setModal('settings')} />}
          {tab === 'people' && <PeoplePanel />}
          {tab === 'tribes' && <TribesPanel />}
          {tab === 'chronicle' && <ChroniclePanel />}
          {tab === 'tech' && <TechPanel />}
          {tab === 'stats' && <StatsPanel />}
        </div>
      </div>
      <Inspector />
      <Minimap wide={!sidebar} />
      <Toasts />
      <div className="hud-hint glass hide-md">
        <kbd>Space</kbd> pause · <kbd>1</kbd>–<kbd>5</kbd> speed · <kbd>F</kbd> follow · <kbd>WASD</kbd> pan · click a person to read their mind
      </div>
      </>}
      {modal === 'about' && <AboutModal onClose={() => setModal(null)} />}
      {modal === 'settings' && <SettingsModal onClose={() => setModal(null)} />}
      {modal === 'new' && <NewWorldModal onClose={() => setModal(null)} onCreated={() => setMenu(false)} />}
      {modal === 'saves' && <SavesModal onClose={() => setModal(null)} onLoaded={() => setMenu(false)} />}
    </div>
  )
}

function Tabs({ tab, setTab, onClose }: { tab: Tab; setTab: (t: Tab) => void; onClose: () => void }) {
  useGameVersion()
  useMindVersion()
  const w = game.world
  const tabs: [Tab, string, number | null][] = [
    ['minds', '✦ Minds', mind.stats.inFlight || null],
    ['people', 'People', w.living.length],
    ['tribes', 'Tribes', w.tribes.filter((t) => t.alive).length],
    ['chronicle', 'Chronicle', null],
    ['tech', 'Knowledge', null],
    ['stats', 'Stats', null],
  ]
  return (
    <div className="tabs">
      {tabs.map(([k, label, n]) => (
        <button key={k} className={`tab ${tab === k ? 'on' : ''}`} onClick={() => setTab(k)}>
          {label}
          {n !== null && <span className="count">{n}</span>}
        </button>
      ))}
      <button className="tab" style={{ marginLeft: 'auto' }} onClick={onClose} title="Hide panels">⟨</button>
    </div>
  )
}

function TopBar({ onModal, onMenu }: { onModal: (m: ModalKind) => void; onMenu: () => void }) {
  useGameVersion()
  useMindVersion()
  const w = game.world
  const era = ERAS[w.era]
  const t = w.time
  const st = mind.stats
  const live = mind.ready
  const minds = w.living.filter((a) => mind.isMind(a)).length
  const event = w.events.find((e) => e.end > w.time)
  return (
    <div className="topbar glass">
      <div className="brand" onClick={onMenu} style={{ cursor: 'pointer' }} title="Main menu">
        <div className="logo" />
        <div style={{ minWidth: 0 }}>
          <h1>Genesis</h1>
          <div className="world-name">{w.cfg.name}</div>
        </div>
      </div>
      <div className="sep hide-sm" />
      <div className="clock">
        <div className="big">{SEASON_ICONS[seasonOf(t)]} Year {yearOf(t)} · {SEASONS[seasonOf(t)]} {dayInSeason(t)}</div>
        <div className="small">{formatClock(t)} {partOfDay(t)} · {WEATHER_ICON[w.weather]} {w.weather}{event ? ` · ⚠ ${event.label}` : ''}</div>
      </div>
      <span className="era-badge hide-lg" style={{ color: era.color, borderColor: era.color + '66', background: era.color + '14' }}>{era.name}</span>
      <div className="stat-chip hide-lg"><span className="lbl">Population</span>{w.living.length}</div>
      {(() => {
        const wars = w.dip.wars.filter((x) => !x.ended)
        if (!wars.length) return null
        const names = wars.map((x) => `${w.tribeById(x.a)?.name} vs ${w.tribeById(x.b)?.name}`).join(' · ')
        return <div className="stat-chip war-chip" title={names}><span className="lbl">At war</span>⚔️ {wars.length}</div>
      })()}
      <div className="stat-chip hide-lg"><span className="lbl">Tribes</span>{w.tribes.filter((x) => x.alive).length}</div>
      <div className="spacer" />
      <button
        className={`ai-pill ${live ? 'live' : ''} ${st.pausedReason ? 'warn' : ''}`}
        onClick={() => onModal('settings')}
        title="AI minds — click for settings"
      >
        <span className="dot" />
        {!live ? (st.pausedReason ? 'Minds paused' : 'Minds asleep') : mind.configured ? <>{minds} minds · {st.inFlight} thinking · ${st.cost.toFixed(2)}</> : <>🌱 Genesis Mind · {minds} minds</>}
      </button>
      <div className="speed">
        <button className={game.paused ? 'on' : ''} onClick={() => game.togglePause()} title="Pause (Space)">{game.paused ? '▶' : '❚❚'}</button>
        {SPEEDS.map((s, i) => (
          <button key={s} className={!game.paused && game.speed === s ? 'on' : ''} onClick={() => game.setSpeed(s)} title={`${s}× (key ${i + 1})`}>{s}×</button>
        ))}
      </div>
      <button className="icon-btn" onClick={() => window.dispatchEvent(new CustomEvent('genesis:view', { detail: game.view === '3d' ? '2d' : '3d' }))} title={game.view === '3d' ? 'Switch to the classic 2D map' : 'Switch to the 3D world'}>{game.view === '3d' ? '🗺️' : '🧊'}<span className="hide-md">{game.view === '3d' ? '2D' : '3D'}</span></button>
      <button className="icon-btn hide-sm" onClick={() => onModal('saves')} title="Save / load">💾</button>
      <button className="icon-btn" onClick={onMenu} title="Main menu">☰</button>
      <button className="icon-btn" onClick={() => onModal('new')} title="New world">🌍<span className="hide-md">New</span></button>
    </div>
  )
}

function Minimap({ wide }: { wide: boolean }) {
  const ref = useRef<HTMLCanvasElement>(null)
  const [overlay, setOverlay] = useState<Overlay>(game.renderer?.overlay ?? 'territory')
  useEffect(() => {
    const id = setInterval(() => {
      if (ref.current && game.renderer) game.renderer.renderMinimap(ref.current)
    }, 400)
    return () => clearInterval(id)
  }, [])
  const jump = (e: React.MouseEvent<HTMLCanvasElement>) => {
    const r = e.currentTarget.getBoundingClientRect()
    const w = game.world
    game.renderer?.flyTo(((e.clientX - r.left) / r.width) * w.W, ((e.clientY - r.top) / r.height) * w.H, game.renderer.cam.zoom)
    if (game.renderer) game.renderer.follow = false
  }
  return (
    <div className={`minimap glass ${wide ? 'wide' : ''}`}>
      <canvas ref={ref} onMouseDown={jump} onMouseMove={(e) => e.buttons === 1 && jump(e)} />
      {overlay === 'geology' && <div className="legend">{DEPOSITS.filter((d) => d.id).map((d) => <span key={d.id}><i style={{ background: d.map }} />{d.name.split(' ')[0]}</span>)}<small>bright = found by the selected people</small></div>}
      {overlay === 'diplomacy' && <div className="legend"><span><i style={{ background: '#ff3b3b' }} />war</span><span><i style={{ background: '#4ade80' }} />alliance</span><span><i style={{ background: '#fbbf24' }} />trade</span><span><i style={{ background: '#c084fc' }} />tribute</span></div>}
      {overlay === 'fish' && <div className="legend"><span><i style={{ background: '#00e6c8' }} />plentiful</span><span><i style={{ background: '#ff5a50' }} />fished out</span></div>}
      <div className="controls">
        <select value={overlay} onChange={(e) => { const v = e.target.value as Overlay; setOverlay(v); if (game.renderer) game.renderer.overlay = v }}>
          <option value="territory">Territory</option>
          <option value="none">Plain map</option>
          <option value="fertility">Fertility</option>
          <option value="temperature">Temperature</option>
          <option value="explored">Explored (selected tribe)</option>
          <option value="geology">Geology: what lies underground</option>
          <option value="fish">Fish stocks</option>
          <option value="diplomacy">Diplomacy: alliances & wars</option>
        </select>
        <button className="icon-btn" onClick={() => game.renderer?.zoomAt(game.renderer.vw / 2, game.renderer.vh / 2, 1.25)}>＋</button>
        <button className="icon-btn" onClick={() => game.renderer?.zoomAt(game.renderer.vw / 2, game.renderer.vh / 2, 0.8)}>－</button>
      </div>
    </div>
  )
}

function Toasts() {
  useGameVersion()
  return (
    <div className="toasts">
      {game.toasts.map((t) => (
        <div key={t.id} className={`toast ${t.kind}`} onClick={() => t.agentId && game.selectAgent(t.agentId)}>
          <span style={{ fontSize: 18 }}>{t.icon}</span>
          <span>{t.text}</span>
        </div>
      ))}
    </div>
  )
}

/** Drag to pan, wheel / pinch to zoom, click to select, hover highlight. */
function useMapControls(ref: React.RefObject<HTMLCanvasElement | null>, view: string) {
  useEffect(() => {
    const c = ref.current!
    let down: { x: number; y: number; moved: boolean; turn: boolean } | null = null
    const pointers = new Map<number, { x: number; y: number }>()
    let pinch: { d: number } | null = null
    const pos = (e: PointerEvent | WheelEvent) => {
      const r = c.getBoundingClientRect()
      return { x: e.clientX - r.left, y: e.clientY - r.top }
    }
    const onDown = (e: PointerEvent) => {
      c.setPointerCapture(e.pointerId)
      pointers.set(e.pointerId, pos(e))
      if (pointers.size === 2) {
        const [a, b] = [...pointers.values()]
        pinch = { d: Math.hypot(a.x - b.x, a.y - b.y) }
        down = null
      } else down = { ...pos(e), moved: false, turn: e.button === 2 || (e.button === 0 && e.altKey) }
    }
    const onMove = (e: PointerEvent) => {
      const p = pos(e)
      const r = game.renderer
      if (!r) return
      if (pointers.has(e.pointerId)) pointers.set(e.pointerId, p)
      if (pinch && pointers.size === 2) {
        const [a, b] = [...pointers.values()]
        const d = Math.hypot(a.x - b.x, a.y - b.y)
        r.zoomAt((a.x + b.x) / 2, (a.y + b.y) / 2, d / pinch.d)
        pinch.d = d
        return
      }
      if (down) {
        const dx = p.x - down.x, dy = p.y - down.y
        if (down.moved || Math.hypot(dx, dy) > 4) {
          down.moved = true
          c.classList.add('dragging')
          if (down.turn && r.rotate) r.rotate(dx)
          else r.pan(dx, dy)
          down.x = p.x
          down.y = p.y
        }
      } else {
        const wpt = r.screenToWorld(p.x, p.y)
        r.hover = { x: Math.floor(wpt.x), y: Math.floor(wpt.y) }
      }
    }
    const onUp = (e: PointerEvent) => {
      pointers.delete(e.pointerId)
      if (pointers.size < 2) pinch = null
      c.classList.remove('dragging')
      const r = game.renderer
      if (down && !down.moved && !down.turn && r) {
        const sel = r.pick(down.x, down.y)
        r.selection = sel
        r.follow = false
        game.emit()
      }
      down = null
    }
    const onWheel = (e: WheelEvent) => {
      e.preventDefault()
      const p = pos(e)
      game.renderer?.zoomAt(p.x, p.y, Math.exp(-e.deltaY * 0.0015))
    }
    const onLeave = () => { if (game.renderer) game.renderer.hover = null }
    c.addEventListener('pointerdown', onDown)
    c.addEventListener('pointermove', onMove)
    c.addEventListener('pointerup', onUp)
    c.addEventListener('pointercancel', onUp)
    c.addEventListener('pointerleave', onLeave)
    c.addEventListener('wheel', onWheel, { passive: false })
    return () => {
      c.removeEventListener('pointerdown', onDown)
      c.removeEventListener('pointermove', onMove)
      c.removeEventListener('pointerup', onUp)
      c.removeEventListener('pointercancel', onUp)
      c.removeEventListener('pointerleave', onLeave)
      c.removeEventListener('wheel', onWheel)
    }
  }, [ref, view])
}
