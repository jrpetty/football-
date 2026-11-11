// Runs the exporter page in headless Chromium and writes what it produces into zombie/godot/.
// usage: (from zombie/)  npx vite --config scripts/godot-export/vite.export.mjs &   then   node scripts/godot-export/run.cjs [what,...]
const { chromium } = require('playwright');
const fs = require('fs'); const path = require('path');
const OUT = path.resolve(__dirname, '../../godot');
(async () => {
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const page = await browser.newPage({ viewport: { width: 640, height: 360 } });
  page.on('pageerror', e => console.log('PAGEERROR', e.message, (e.stack || '').split('\n').slice(0, 5).join(' | ')));
  page.on('console', m => { const t = m.text(); if (m.type() === 'error' || t.startsWith('[export]')) console.log(t.slice(0, 600)); });
  let files = 0, bytes = 0;
  await page.exposeFunction('__save', (rel, b64, append) => {
    const f = path.join(OUT, rel); fs.mkdirSync(path.dirname(f), { recursive: true });
    const buf = Buffer.from(b64, 'base64');
    if (append) fs.appendFileSync(f, buf); else { fs.writeFileSync(f, buf); files++; }
    bytes += buf.length;
  });
  await page.goto('http://localhost:5177/__export?what=' + (process.argv[2] || 'all'), { timeout: 180000 });
  await page.waitForFunction(() => window.__exportDone || window.__exportError, null, { timeout: 3600000 });
  const err = await page.evaluate(() => window.__exportError);
  console.log(err ? 'FAILED: ' + err : `done: ${files} files, ${(bytes / 1e6).toFixed(1)} MB`);
  await browser.close();
  process.exit(err ? 1 : 0);
})();
