import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// A relative base so the same build works at the site root, under /tally/ on
// GitHub Pages, or opened from a folder — the service worker registration in
// main.tsx resolves its own scope the same way.
export default defineConfig({
  base: './',
  plugins: [react()],
  build: { outDir: 'dist', sourcemap: true },
})
