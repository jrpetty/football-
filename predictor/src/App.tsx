import { NavLink, Route, Routes } from 'react-router-dom'
import { SeasonProvider, useSeason } from './data/store.tsx'
import { FreshnessBadge } from './components/primitives.tsx'
import Gameweek from './pages/Gameweek.tsx'
import FixtureDetail from './pages/FixtureDetail.tsx'
import Ratings from './pages/Ratings.tsx'
import ModelReport from './pages/ModelReport.tsx'
import PlayerWatch from './pages/PlayerWatch.tsx'

/** Shows when the pipeline last rebuilt the data. */
function Freshness() {
  const { season } = useSeason()
  if (!season) return null
  return (
    <span style={{ marginLeft: 'auto' }}>
      <FreshnessBadge generatedAt={season.generatedAt} />
    </span>
  )
}

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
          <Freshness />
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
