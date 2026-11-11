// Preview-only Vite config: serves /preview/models.html and falls back to a local
// materials stub while src/render/materials.ts is missing or incomplete.
import { defineConfig } from 'vite';
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(__dirname, '..');

export default defineConfig({
  root,
  server: { port: 5193 },
  plugins: [
    {
      name: 'materials-fallback',
      enforce: 'pre',
      resolveId(source, importer) {
        if (!source.endsWith('render/materials') || !importer || !importer.includes('/src/models/')) return null;
        const real = path.join(root, 'src/render/materials.ts');
        const ok = !process.env.FORCE_STUB && fs.existsSync(real) &&
          /export\s+function\s+getMaterial/.test(fs.readFileSync(real, 'utf8'));
        return ok ? real : path.join(root, 'preview/materials-stub.ts');
      },
    },
  ],
});
