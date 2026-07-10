import { defineConfig } from 'vite'

// Plain Vite — the game is dependency-free vanilla TypeScript + Canvas.
// `base: './'` keeps asset paths relative so it works on GitHub Pages subpaths.
export default defineConfig({
  base: './',
  server: {
    port: 5173,
    open: false,
  },
})
