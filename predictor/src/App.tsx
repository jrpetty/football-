import { NavLink, Route, Routes } from 'react-router-dom'
import { SeasonProvider } from './data/store.tsx'
import Gameweek from './pages/Gameweek.tsx'
import FixtureDetail from './pages/FixtureDetail.tsx'
import Ratings from './pages/Ratings.tsx'
import ModelReport from './pages/ModelReport.tsx'
import PlayerWatch from './pages/PlayerWatch.tsx'

export default function App() {
  return (
    <SeasonProvider>
      <div className="shell">
        <header className="topbar">
          <span className="brand">
            <span className="brand-mark" aria-hidden="true">PO</span>
            Prem Oracle
          </span>
          <nav className="nav">
            <NavLink to="/" end>Predictions</NavLink>
            <NavLink to="/ratings">Ratings</NavLink>
            <NavLink to="/players">Players</NavLink>
            <NavLink to="/model">Report card</NavLink>
          </nav>
        </header>
        <main className="page">
          <Routes>
            <Route path="/" element={<Gameweek />} />
            <Route path="/fixture/:gw/:id" element={<FixtureDetail />} />
            <Route path="/ratings" element={<Ratings />} />
            <Route path="/players" element={<PlayerWatch />} />
            <Route path="/model" element={<ModelReport />} />
            <Route path="*" element={<Gameweek />} />
          </Routes>
        </main>
      </div>
    </SeasonProvider>
  )
}
