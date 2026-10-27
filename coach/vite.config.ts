import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The renderer. `base: './'` so the built index.html loads from file:// inside
// Electron. Workers are bundled as ES modules.
export default defineConfig({
  plugins: [react()],
  base: './',
  build: { outDir: 'dist', target: 'chrome130', sourcemap: true },
  worker: { format: 'es' },
  server: { port: 5174, strictPort: true },
})
