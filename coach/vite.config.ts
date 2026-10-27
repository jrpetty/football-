import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'

// The CSP in index.html allows no remote connections. The dev server needs a
// websocket for hot reload (and its React preamble), so only in dev is that
// opened up; the production build keeps the strict policy.
function csp(dev: boolean): Plugin {
  return {
    name: 'sightline-csp',
    transformIndexHtml: {
      order: 'pre',
      handler(html) {
        const out = html.replaceAll('%DEV_CONNECT%', dev ? ' ws://localhost:5174 http://localhost:5174' : '')
        return dev ? out.replace("script-src 'self'", "script-src 'self' 'unsafe-inline'") : out
      },
    },
  }
}

// The renderer. `base: './'` so the built index.html loads from file:// inside
// Electron. Workers are bundled as ES modules.
export default defineConfig(({ command }) => ({
  plugins: [react(), csp(command === 'serve')],
  base: './',
  build: { outDir: 'dist', target: 'chrome130', sourcemap: true },
  worker: { format: 'es' },
  server: { port: 5174, strictPort: true },
}))
