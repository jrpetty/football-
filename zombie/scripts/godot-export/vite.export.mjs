// Dev server for the Godot exporter: serves the game's own TypeScript so the export runs the real generators.
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
export default {
  root: ROOT,
  base: './',
  cacheDir: path.join(ROOT, 'node_modules/.vite-godot-export'),
  server: { port: 5177, strictPort: true, hmr: false, fs: { strict: false } },
  build: { target: 'es2022' },
  plugins: [{
    name: 'export-page',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if (req.url && req.url.startsWith('/__export')) {
          res.setHeader('Content-Type', 'text/html');
          res.end(`<!doctype html><html><body><script type="module" src="/scripts/godot-export/export.ts?t=${Date.now()}"></script></body></html>`);
          return;
        }
        next();
      });
    },
  }],
};
