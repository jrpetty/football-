import { useState } from 'react'
import { NavLink, useLocation } from 'react-router-dom'
import { useData } from '../store/store'

interface NavItem {
  to: string
  label: string
  icon: string
  end?: boolean
}

const NAV: { section: string; items: NavItem[] }[] = [
  {
    section: 'Overview',
    items: [
      { to: '/', label: 'Dashboard', icon: '📊', end: true },
      { to: '/analysis', label: 'Team Analysis', icon: '📈' },
    ],
  },
  {
    section: 'Players',
    items: [
      { to: '/squad', label: 'Squad', icon: '👥' },
      { to: '/compare', label: 'Compare', icon: '⚖️' },
      { to: '/development', label: 'Development', icon: '🎯' },
    ],
  },
  {
    section: 'Matches',
    items: [
      { to: '/matches', label: 'Matches', icon: '🗓️' },
      { to: '/scouting', label: 'Scouting', icon: '🔍' },
      { to: '/tactics', label: 'Tactics Board', icon: '♟️' },
      { to: '/set-pieces', label: 'Set Pieces', icon: '🚩' },
    ],
  },
  {
    section: 'Analysis',
    items: [
      { to: '/video', label: 'Video Analysis', icon: '🎬' },
      { to: '/training', label: 'Training', icon: '🏋️' },
    ],
  },
  {
    section: 'System',
    items: [{ to: '/data', label: 'Data & Export', icon: '⚙️' }],
  },
]

const PAGE_TITLES: Record<string, string> = {
  '/': 'Dashboard',
  '/analysis': 'Team Analysis',
  '/squad': 'Squad',
  '/compare': 'Player Comparison',
  '/matches': 'Matches',
  '/scouting': 'Scouting',
  '/tactics': 'Tactics Board',
  '/set-pieces': 'Set Pieces',
  '/development': 'Player Development',
  '/video': 'Video Analysis',
  '/training': 'Training',
  '/data': 'Data & Export',
}

export function Layout({ children }: { children: React.ReactNode }) {
  const data = useData()
  const [open, setOpen] = useState(false)
  const loc = useLocation()
  const base = '/' + (loc.pathname.split('/')[1] ?? '')
  const title = PAGE_TITLES[base === '/' && loc.pathname === '/' ? '/' : base] ?? 'Gaffer'

  return (
    <div className="app-shell">
      <aside className={`sidebar${open ? ' open' : ''}`}>
        <div className="brand">
          <div className="brand-mark">⚽</div>
          <div>
            <div className="brand-name">Gaffer</div>
            <div className="brand-sub">Football Analysis</div>
          </div>
        </div>
        <nav className="nav">
          {NAV.map((group) => (
            <div key={group.section}>
              <div className="nav-section">{group.section}</div>
              {group.items.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.end}
                  className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}
                  onClick={() => setOpen(false)}
                >
                  <span className="ico">{item.icon}</span>
                  {item.label}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>
        <div className="sidebar-foot">
          <div style={{ fontWeight: 700, color: 'var(--text-dim)' }}>{data.team.name}</div>
          <div>{data.team.season} · {data.team.coach}</div>
        </div>
      </aside>

      {open && (
        <div
          onClick={() => setOpen(false)}
          style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 55 }}
        />
      )}

      <div className="main">
        <header className="topbar">
          <button className="btn ghost icon menu-btn" onClick={() => setOpen((o) => !o)} aria-label="Menu">
            ☰
          </button>
          <h1>{title}</h1>
          <div className="topbar-spacer" />
          <span className="badge" style={{ borderColor: 'rgba(56,189,248,0.4)', color: 'var(--accent)' }}>
            {data.team.season}
          </span>
        </header>
        {children}
      </div>
    </div>
  )
}
