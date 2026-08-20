import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Relative base so the built app works from any sub-path on GitHub Pages
// (it is published under /predictor/ alongside the Gaffer app at the root).
export default defineConfig({
  plugins: [react()],
  base: './',
  server: { port: 5174, open: false },
})
