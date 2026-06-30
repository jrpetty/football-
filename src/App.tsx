import { Routes, Route } from 'react-router-dom'
import { Layout } from './components/Layout'
import { Dashboard } from './pages/Dashboard'
import { Squad } from './pages/Squad'
import { PlayerProfile } from './pages/PlayerProfile'
import { Compare } from './pages/Compare'
import { Matches } from './pages/Matches'
import { MatchDetail } from './pages/MatchDetail'
import { TeamAnalysis } from './pages/TeamAnalysis'
import { Tactics } from './pages/Tactics'
import { SetPieces } from './pages/SetPieces'
import { Scouting } from './pages/Scouting'
import { Development } from './pages/Development'
import { VideoAnalysis } from './pages/VideoAnalysis'
import { VideoDetail } from './pages/VideoDetail'
import { Training } from './pages/Training'
import { DataManagement } from './pages/DataManagement'

export function App() {
  return (
    <Layout>
      <Routes>
        <Route path="/" element={<Dashboard />} />
        <Route path="/analysis" element={<TeamAnalysis />} />
        <Route path="/squad" element={<Squad />} />
        <Route path="/squad/:playerId" element={<PlayerProfile />} />
        <Route path="/compare" element={<Compare />} />
        <Route path="/matches" element={<Matches />} />
        <Route path="/matches/:matchId" element={<MatchDetail />} />
        <Route path="/tactics" element={<Tactics />} />
        <Route path="/set-pieces" element={<SetPieces />} />
        <Route path="/scouting" element={<Scouting />} />
        <Route path="/development" element={<Development />} />
        <Route path="/video" element={<VideoAnalysis />} />
        <Route path="/video/:videoId" element={<VideoDetail />} />
        <Route path="/training" element={<Training />} />
        <Route path="/data" element={<DataManagement />} />
        <Route path="*" element={<Dashboard />} />
      </Routes>
    </Layout>
  )
}
