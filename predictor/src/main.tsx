import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { HashRouter } from 'react-router-dom'
import App from './App.tsx'
import './index.css'

// HashRouter rather than BrowserRouter: the app is published to GitHub Pages
// under a sub-path with no server-side rewrites, so a deep link like
// /predictor/fixture/1/5 would 404 on refresh. The hash keeps every route
// resolvable from the one static index.html.
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <HashRouter>
      <App />
    </HashRouter>
  </StrictMode>,
)
