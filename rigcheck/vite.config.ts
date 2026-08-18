import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Base path suits GitHub Pages hosting under /<repo>/rigcheck/ if ever deployed;
// override with RIGCHECK_BASE at build time.
export default defineConfig({
  plugins: [react()],
  base: process.env.RIGCHECK_BASE ?? '/',
  build: { outDir: 'dist', sourcemap: true },
  test: {
    globals: true,
    environment: 'node',
    include: ['tests/**/*.test.ts'],
  },
} as never);
