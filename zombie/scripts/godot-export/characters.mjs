// Exports the shared character body for the Godot port: mesh, baked animation clips and the generated shader.
// It bundles and runs the game's own TypeScript (bodyGeo / bodyRig), so the two versions cannot drift.
//   node scripts/godot-export/characters.mjs          (from zombie/)
import { build } from 'esbuild';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import fs from 'node:fs';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../..');
const out = path.join(root, 'node_modules/.cache/godot-export/characters_bake.mjs');
fs.mkdirSync(path.dirname(out), { recursive: true });
await build({
  entryPoints: [path.join(here, 'characters_bake.ts')], outfile: out, bundle: true, platform: 'node', format: 'esm', target: 'node20', logLevel: 'warning',
  plugins: [{
    name: 'stub-materials',     // the body only needs the art-style flag from the renderer's material library
    setup(b) {
      b.onResolve({ filter: /render\/materials$/ }, () => ({ path: 'materials-stub', namespace: 'stub' }));
      b.onLoad({ filter: /.*/, namespace: 'stub' }, () => ({ contents: 'export const LOWPOLY = false;', loader: 'js' }));
    },
  }],
});
const mod = await import(pathToFileURL(out).href + '?t=' + Date.now());
await mod.run({ root, outDir: path.join(root, 'godot/characters'), template: path.join(here, 'characters_shader.template.gdshader') });
