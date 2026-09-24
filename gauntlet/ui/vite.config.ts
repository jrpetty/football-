import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';
import type { IncomingMessage } from 'node:http';

const uiRoot = fileURLToPath(new URL('.', import.meta.url));
const API_TARGET = process.env.GAUNTLET_API ?? 'http://localhost:7777';

const isEventStream = (req: IncomingMessage) => String(req.headers.accept ?? '').includes('text/event-stream');

/** `/api` proxy shared by `vite` (dev) and `vite preview`. Tuned so Server-Sent Events stream unbuffered. */
const apiProxy = {
  '/api': {
    target: API_TARGET,
    changeOrigin: true,
    // Long-lived SSE connections must never be timed out by the proxy.
    timeout: 0,
    proxyTimeout: 0,
    configure(proxy: import('vite').HttpProxy.Server) {
      proxy.on('proxyReq', (proxyReq, req) => {
        if (isEventStream(req)) {
          // Compressed streams are buffered by the encoder — ask upstream for identity encoding.
          proxyReq.setHeader('accept-encoding', 'identity');
          proxyReq.setHeader('cache-control', 'no-cache');
        }
      });
      proxy.on('proxyRes', (proxyRes, _req, res) => {
        const type = String(proxyRes.headers['content-type'] ?? '');
        if (type.includes('text/event-stream')) {
          proxyRes.headers['cache-control'] = 'no-cache, no-transform';
          proxyRes.headers['x-accel-buffering'] = 'no';
          delete proxyRes.headers['content-length'];
          // When the browser disconnects, tear down the upstream stream too.
          res.on('close', () => proxyRes.destroy());
        }
      });
    },
  },
};

export default defineConfig({
  root: uiRoot,
  base: './',
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: false,
    proxy: apiProxy,
    fs: { allow: [fileURLToPath(new URL('..', import.meta.url))] },
  },
  preview: {
    port: 4173,
    proxy: apiProxy,
  },
  build: {
    outDir: fileURLToPath(new URL('./dist', import.meta.url)),
    emptyOutDir: true,
    sourcemap: false,
    target: 'es2022',
    chunkSizeWarningLimit: 900,
  },
});
