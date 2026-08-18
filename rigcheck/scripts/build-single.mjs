// Inline the Vite output into one self-contained HTML file, mirroring the
// gaffer.html pattern this repository already uses. Everything — catalogue
// data, engine, UI — rides inside the JS bundle, so the file works from
// file://, a USB stick, or any static host with no assets alongside it.
import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const dist = new URL('../dist', import.meta.url).pathname;
let html = readFileSync(join(dist, 'index.html'), 'utf8');

const assets = readdirSync(join(dist, 'assets'));
for (const f of assets) {
  const content = readFileSync(join(dist, 'assets', f), 'utf8');
  if (f.endsWith('.css')) {
    html = html.replace(new RegExp(`<link[^>]*href="/assets/${f}"[^>]*>`), () => `<style>${content}</style>`);
  } else if (f.endsWith('.js')) {
    html = html.replace(
      new RegExp(`<script type="module"[^>]*src="/assets/${f}"[^>]*></script>`),
      () => `<script type="module">${content.replace(/<\/script>/g, '<\\/script>')}</script>`,
    );
  }
}
// Inline the favicon as a data URI so file:// use produces no failed loads.
try {
  const icon = readFileSync(join(dist, 'favicon.svg'), 'utf8');
  html = html.replace(
    /<link rel="icon"[^>]*>/,
    `<link rel="icon" type="image/svg+xml" href="data:image/svg+xml,${encodeURIComponent(icon)}" />`,
  );
} catch { /* icon optional */ }

if (/src="\/assets\//.test(html) || /href="\/assets\//.test(html)) {
  console.error('un-inlined asset reference remains'); process.exit(1);
}
const out = new URL('../rigcheck.html', import.meta.url).pathname;
writeFileSync(out, html);
console.log(`wrote ${out} (${(html.length / 1024).toFixed(0)} KB)`);
