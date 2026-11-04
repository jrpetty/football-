import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Relative base so the build works from any sub-path on GitHub Pages
// (it is published under /quartet/ alongside the other apps).
export default defineConfig({
  plugins: [react()],
  base: './',
  server: { port: 5176, open: false },
  // One bundle (no lazy chunks) so the app also works as a single offline HTML file.
  build: { chunkSizeWarningLimit: 1500, rollupOptions: { output: { inlineDynamicImports: true } } },
})
