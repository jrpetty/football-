import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * A one-file build, for handing the app to someone with no host to put it on.
 *
 * Dynamic imports are folded in so there is a single script to inline, and
 * assets are inlined rather than emitted alongside. The normal build is
 * untouched: it keeps code splitting, so a night spent typing figures never
 * downloads the Claude SDK.
 */
const single = process.env.TALLY_SINGLE === '1'

// A relative base so the same build works at the site root, under /tally/ on
// GitHub Pages, or opened from a folder — the service worker registration in
// main.tsx resolves its own scope the same way.
export default defineConfig({
  base: './',
  plugins: [react()],
  build: single
    ? {
        outDir: 'dist-single',
        sourcemap: false,
        cssCodeSplit: false,
        assetsInlineLimit: 100_000_000,
        rollupOptions: { output: { inlineDynamicImports: true } },
      }
    : { outDir: 'dist', sourcemap: true },
})
