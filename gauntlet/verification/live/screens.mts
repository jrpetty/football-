// Screenshots of "Watch it think", the live commentary and the OBS live overlay (mock mode, mid-stream) at 1920×1080.
// Usage: node verification/live/screens.mts <baseUrl> [outDir] [only-substring]
// (server started with `node src/cli.ts serve --port <port>`)
import { chromium, type Page } from 'playwright-core';
import { mkdirSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const base = process.argv[2] ?? 'http://127.0.0.1:7777';
const out = process.argv[3] || join(import.meta.dirname, '..', '..', 'docs', 'screenshots', 'next-level', 'live');
const only = process.argv[4];
mkdirSync(out, { recursive: true });

function chromiumPath(): string | undefined {
  const root = '/opt/pw-browsers';
  if (!existsSync(root)) return undefined;
  const dir = readdirSync(root).find((d) => /^chromium-\d+$/.test(d));
  return dir ? join(root, dir, 'chrome-linux', 'chrome') : undefined;
}

const LIVE8 = 'run-2026-09-28-live';
const AGENTS = 'run-2026-09-24-agents';

interface Shot {
  name: string;
  path: string;
  broadcast?: boolean;
  theme?: 'light' | 'dark';
  wait?: number;
  /** Paint a fake scene behind a transparent overlay page. */
  scene?: 'dark' | 'light';
  scrollTo?: string;
  click?: string[];
  /** Wait until a tile shows a verdict before the picture. */
  verdict?: boolean;
  commentary?: 'feed' | 'crawl' | 'off';
}

const shots: Shot[] = [
  { name: 'watch-8-models', path: `/runs/${LIVE8}/watch`, wait: 6500, verdict: true, commentary: 'feed' },
  { name: 'watch-8-models-broadcast', path: `/runs/${LIVE8}/watch`, broadcast: true, wait: 8000, verdict: true, commentary: 'crawl' },
  { name: 'watch-8-models-broadcast-light', path: `/runs/${LIVE8}/watch`, broadcast: true, theme: 'light', wait: 7000, verdict: true, commentary: 'feed' },
  { name: 'watch-6-models-agents-broadcast', path: `/runs/${AGENTS}/watch`, broadcast: true, wait: 7000, commentary: 'crawl' },
  { name: 'watch-light', path: `/runs/${LIVE8}/watch`, theme: 'light', wait: 6000, verdict: true, commentary: 'crawl' },
  { name: 'run-page-live-panel', path: `/runs/${LIVE8}`, wait: 6500, scrollTo: '.lwp', verdict: true },
  { name: 'run-page-live-panel-light', path: `/runs/${AGENTS}`, theme: 'light', wait: 6500, scrollTo: '.lwp' },
  { name: 'overlay-live-on-dark', path: `/overlay/live?run=${LIVE8}`, wait: 9000, scene: 'dark' },
  { name: 'overlay-live-on-light', path: `/overlay/live?run=${LIVE8}&theme=light`, wait: 9000, scene: 'light' },
  { name: 'overlay-live-solid-top', path: `/overlay/live?run=${AGENTS}&theme=solid&pos=top`, wait: 8000, scene: 'dark' },
  { name: 'overlay-live-demo', path: `/overlay/demo?view=live`, wait: 5000, scene: 'light' },
  { name: 'studio-overlay-live', path: `/studio/${LIVE8}?tab=overlay`, wait: 3500, click: ['Live run'] },
];

const browser = await chromium.launch({ executablePath: chromiumPath() });

async function paintScene(page: Page, kind: 'dark' | 'light') {
  await page.evaluate((k) => {
    const d = document.createElement('div');
    d.style.cssText = `position:fixed;inset:0;z-index:-1;display:grid;place-items:center;font:600 42px system-ui;letter-spacing:.04em;${
      k === 'dark'
        ? 'background:radial-gradient(circle at 30% 30%,#27324a,#070a12 70%);color:#56627a'
        : 'background:radial-gradient(circle at 70% 30%,#ffffff,#cfd8e6 75%);color:#8a96aa'
    }`;
    d.textContent = k === 'dark' ? 'Your screen capture (dark scene)' : 'Your screen capture (light scene)';
    document.body.prepend(d);
  }, kind);
}

async function shoot(s: Shot) {
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.addInitScript(
    ({ broadcast, theme, commentary }) => {
      sessionStorage.setItem('gauntlet.broadcast', broadcast ? '1' : '0');
      localStorage.setItem('gauntlet.theme', theme ?? 'dark');
      if (commentary) localStorage.setItem('gauntlet.watch.commentary', JSON.stringify(commentary));
    },
    { broadcast: !!s.broadcast, theme: s.theme, commentary: s.commentary },
  );
  await page.goto(`${base}/?mock=1#${s.path}`);
  await page.waitForSelector('.page, .ov-root, main', { timeout: 15000 }).catch(() => undefined);
  for (const text of s.click ?? []) {
    await page.waitForTimeout(800);
    await page.getByText(text, { exact: true }).first().click().catch(() => console.log(s.name, 'no', text));
  }
  if (s.scene) await paintScene(page, s.scene);
  await page.waitForTimeout(s.wait ?? 3000);
  if (s.verdict) await page.waitForSelector('.wt-verdict', { timeout: 8000 }).catch(() => console.log(s.name, 'no verdict on screen'));
  if (s.scrollTo) await page.locator(s.scrollTo).first().scrollIntoViewIfNeeded().catch(() => undefined);
  if (s.scrollTo)
    await page.evaluate((sel) => {
      const el = document.querySelector(sel);
      if (el) window.scrollTo(0, el.getBoundingClientRect().top + window.scrollY - 76);
    }, s.scrollTo);
  await page.waitForTimeout(300);
  await page.screenshot({ path: join(out, `${s.name}.png`) });
  if (errors.length) console.log(s.name, 'errors:', errors);
  await page.close();
}

for (const s of shots) if (!only || s.name.includes(only)) await shoot(s);
await browser.close();
