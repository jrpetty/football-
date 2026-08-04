import { defineConfig } from 'vite'

// Build variant for the one-file distributable. Everything is emitted as a
// single IIFE bundle (no ES-module imports, no code splitting) so that once the
// script and stylesheet are inlined, the page runs straight off the filesystem —
// module scripts are blocked by CORS when opened over file://.
export default defineConfig({
  base: './',
  build: {
    outDir: 'dist-single',
    cssCodeSplit: false,
    assetsInlineLimit: 100_000_000, // fold any small assets into the bundle
    rollupOptions: {
      output: {
        format: 'iife',
        inlineDynamicImports: true,
        entryFileNames: 'bundle.js',
        assetFileNames: 'bundle.[ext]',
      },
    },
  },
})
