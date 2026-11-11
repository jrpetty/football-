// Inline the Vite build output into one self-contained HTML file (zombie/rekindle.html)
// so the game can be opened by double-clicking, with no server.
import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const dist = join(root, 'dist');
let html = readFileSync(join(dist, 'index.html'), 'utf8');
const assets = readdirSync(join(dist, 'assets'));
for (const f of assets) {
  const body = readFileSync(join(dist, 'assets', f), 'utf8');
  if (f.endsWith('.css')) {
    html = html.replace(new RegExp(`<link[^>]+href="\\./assets/${f.replace('.', '\\.')}"[^>]*>`), () => `<style>${body}</style>`);
  } else if (f.endsWith('.js')) {
    const safe = body.replace(/<\/script/g, '<\\/script');
    html = html.replace(new RegExp(`<script[^>]+src="\\./assets/${f.replace('.', '\\.')}"[^>]*></script>`), () => `<script type="module">${safe}</script>`);
  }
}
const out = join(root, 'rekindle.html');
writeFileSync(out, html);
console.log(`wrote ${out} (${(html.length / 1024 / 1024).toFixed(2)} MB)`);
