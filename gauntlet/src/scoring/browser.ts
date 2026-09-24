import { existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { createHash } from 'node:crypto';

/**
 * Optional headless-browser checks for HTML/SVG artifacts, powered by
 * playwright-core + a local Chromium. When unavailable, browser-only checks
 * are reported as "skipped" and excluded from the score denominator.
 */

type PlaywrightModule = typeof import('playwright-core');
type Browser = Awaited<ReturnType<PlaywrightModule['chromium']['launch']>>;

let browserPromise: Promise<Browser | null> | null = null;
let unavailableReason: string | null = null;

function findChromium(): string | undefined {
  if (process.env.GAUNTLET_CHROMIUM && existsSync(process.env.GAUNTLET_CHROMIUM)) return process.env.GAUNTLET_CHROMIUM;
  const roots = [process.env.PLAYWRIGHT_BROWSERS_PATH, '/opt/pw-browsers', join(process.env.HOME ?? '', '.cache', 'ms-playwright')].filter(Boolean) as string[];
  for (const root of roots) {
    if (!existsSync(root)) continue;
    for (const dir of readdirSync(root).filter((d) => d.startsWith('chromium')).sort().reverse()) {
      for (const rel of ['chrome-linux/chrome', 'chrome-linux64/chrome', 'chrome-mac/Chromium.app/Contents/MacOS/Chromium', 'chrome-win/chrome.exe', 'chrome-linux/headless_shell']) {
        const p = join(root, dir, rel);
        if (existsSync(p)) return p;
      }
    }
  }
  return undefined;
}

export async function getBrowser(): Promise<Browser | null> {
  if (process.env.GAUNTLET_NO_BROWSER === '1') {
    unavailableReason = 'disabled by GAUNTLET_NO_BROWSER';
    return null;
  }
  if (!browserPromise) {
    browserPromise = (async () => {
      let pw: PlaywrightModule;
      try {
        pw = await import('playwright-core');
      } catch {
        unavailableReason = 'playwright-core is not installed';
        return null;
      }
      try {
        return await pw.chromium.launch({ executablePath: findChromium(), headless: true, args: ['--no-sandbox', '--disable-gpu'] });
      } catch (e) {
        unavailableReason = `Chromium failed to launch: ${(e as Error).message.split('\n')[0]}`;
        return null;
      }
    })();
  }
  return browserPromise;
}

export async function browserAvailable(): Promise<boolean> {
  return (await getBrowser()) !== null;
}

export function browserUnavailableReason(): string | null {
  return unavailableReason;
}

export async function closeBrowser(): Promise<void> {
  if (!browserPromise) return;
  const b = await browserPromise;
  browserPromise = null;
  await b?.close().catch(() => {});
}

/** Deterministic Math.random so "with input" and "without input" runs are comparable. */
const SEED_SCRIPT = `(() => { let s = 1234567; Math.random = () => { s = (s + 0x6D2B79F5) | 0; let t = Math.imul(s ^ (s >>> 15), 1 | s); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; })();`;

export interface HtmlProbe {
  pageErrors: string[];
  consoleErrors: string[];
  externalRequests: string[];
  hasCanvasOrSvg: boolean;
  respondsToInput: boolean;
  screenshot: Buffer;
  bodyTextLength: number;
}

async function loadPage(browser: Browser, html: string, withInput: boolean) {
  const context = await browser.newContext({ viewport: { width: 960, height: 640 }, javaScriptEnabled: true });
  const page = await context.newPage();
  const pageErrors: string[] = [];
  const consoleErrors: string[] = [];
  const externalRequests: string[] = [];
  page.on('pageerror', (e) => pageErrors.push(e.message.slice(0, 300)));
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 300));
  });
  await page.route('**/*', (route) => {
    const url = route.request().url();
    if (url.startsWith('data:') || url.startsWith('blob:') || url === 'about:blank') return route.continue();
    externalRequests.push(url);
    return route.abort();
  });
  await page.addInitScript(SEED_SCRIPT);
  await page.setContent(html, { waitUntil: 'load', timeout: 10_000 }).catch((e: Error) => pageErrors.push(`load: ${e.message.slice(0, 200)}`));
  await page.waitForTimeout(600);
  if (withInput) {
    await page.mouse.click(480, 320).catch(() => {});
    for (const key of ['Space', 'Enter', 'ArrowUp', 'ArrowRight', 'ArrowLeft', 'ArrowDown', 'KeyW', 'KeyA', 'KeyS', 'KeyD', 'Space', 'ArrowUp']) {
      await page.keyboard.down(key).catch(() => {});
      await page.waitForTimeout(90);
      await page.keyboard.up(key).catch(() => {});
      await page.waitForTimeout(60);
    }
    await page.mouse.click(480, 200).catch(() => {});
  } else {
    await page.waitForTimeout(12 * 150 + 50);
  }
  await page.waitForTimeout(400);
  const info = (await page
    .evaluate(
      `(() => {
        const els = Array.from(document.querySelectorAll('canvas, svg'));
        const visible = els.some((el) => { const r = el.getBoundingClientRect(); return r.width >= 50 && r.height >= 50; });
        return { visible, textLength: (document.body ? document.body.innerText : '').length };
      })()`,
    )
    .catch(() => ({ visible: false, textLength: 0 }))) as { visible: boolean; textLength: number };
  const screenshot = await page.screenshot({ type: 'png' });
  await context.close();
  return { pageErrors, consoleErrors, externalRequests, hasCanvasOrSvg: info.visible, screenshot, bodyTextLength: info.textLength };
}

/** Load an HTML artifact twice (idle vs. with keyboard/mouse input) and compare. */
export async function probeHtml(html: string): Promise<HtmlProbe | null> {
  const browser = await getBrowser();
  if (!browser) return null;
  const idle = await loadPage(browser, html, false);
  const active = await loadPage(browser, html, true);
  const digest = (b: Buffer) => createHash('sha1').update(b).digest('hex');
  return {
    pageErrors: [...new Set([...idle.pageErrors, ...active.pageErrors])],
    consoleErrors: [...new Set([...idle.consoleErrors, ...active.consoleErrors])],
    externalRequests: [...new Set([...idle.externalRequests, ...active.externalRequests])],
    hasCanvasOrSvg: idle.hasCanvasOrSvg || active.hasCanvasOrSvg,
    respondsToInput: digest(idle.screenshot) !== digest(active.screenshot),
    screenshot: active.screenshot,
    bodyTextLength: active.bodyTextLength,
  };
}

/** Render an SVG to PNG and report whether Chromium could parse it. */
export async function renderSvg(svg: string): Promise<{ ok: boolean; error?: string; png: Buffer } | null> {
  const browser = await getBrowser();
  if (!browser) return null;
  const context = await browser.newContext({ viewport: { width: 640, height: 640 } });
  const page = await context.newPage();
  const src = `data:image/svg+xml;base64,${Buffer.from(svg).toString('base64')}`;
  await page.setContent(`<html><body style="margin:0;background:#fff;display:grid;place-items:center;height:100vh"><img id="i" style="max-width:600px;max-height:600px" src="${src}"></body></html>`);
  const result = (await page
    .evaluate(
      `new Promise((resolve) => {
        const img = document.getElementById('i');
        const done = () => resolve({ ok: img.complete && img.naturalWidth > 0, w: img.naturalWidth });
        if (img.complete) done(); else { img.onload = done; img.onerror = () => resolve({ ok: false, w: 0 }); }
      })`,
    )
    .catch(() => ({ ok: false, w: 0 }))) as { ok: boolean; w: number };
  const png = await page.screenshot({ type: 'png' });
  await context.close();
  return { ok: result.ok, error: result.ok ? undefined : 'Chromium could not render the SVG (malformed or empty)', png };
}
