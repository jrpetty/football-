import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App.tsx'
import { requestPersistence } from './storage/db.ts'
import './styles.css'

const root = document.getElementById('root')
if (root) createRoot(root).render(<StrictMode><App /></StrictMode>)

// Ask the browser not to evict a year of takings under storage pressure. It is
// allowed to refuse, and often does until the app has been used a few times,
// so this is asked on every launch rather than once.
void requestPersistence()

// Registered relative to the page, so the same build works at the site root,
// under /tally/, or from the home screen.
if ('serviceWorker' in navigator && import.meta.env.PROD) {
  window.addEventListener('load', () => {
    // Ignored rather than thrown: a host serving the app without the worker
    // (a preview, a file drop) is still a perfectly working app, just online-only.
    void navigator.serviceWorker
      .register(new URL('sw.js', document.baseURI), { scope: new URL('./', document.baseURI).pathname })
      .catch(() => {})
  })
}
