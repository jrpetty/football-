/**
 * The Gallery Masterpiece: turning paintings into the exact pixels the judges see.
 *
 *  - Painted in Code: the model's SVG is rendered by headless Chromium at 1536 × 1024 into a PNG (the same
 *    browser the SVG and game tests use; see src/scoring/browser.ts). Scripts cannot run: the SVG is loaded as
 *    an <img>, which never executes code or loads external files.
 *  - Very large pictures are re-encoded as a JPEG copy for the judges (some judge APIs refuse images over ~5 MB);
 *    the stored painting is never changed.
 */
import { createHash } from 'node:crypto';
import { getBrowser, browserUnavailableReason } from '../../scoring/browser.ts';
import { imageInfo } from '../../core/vision.ts';
import type { ChatImage, GeneratedImage } from '../../core/types.ts';

/** Remove anything that could run or fetch: scripts, event handlers, foreignObject, external or data hrefs. */
export function sanitizePaintingSvg(svg: string): string {
  let s = svg
    .replace(/<script[\s\S]*?<\/script\s*>/gi, '')
    .replace(/<script[^>]*\/>/gi, '')
    .replace(/<foreignObject[\s\S]*?<\/foreignObject\s*>/gi, '')
    .replace(/<image\b[^>]*\/>|<image\b[\s\S]*?<\/image\s*>/gi, '')
    .replace(/\son[a-z]+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi, '')
    .replace(/((?:xlink:)?href\s*=\s*["'])\s*(?:javascript|data|https?):[^"']*/gi, '$1#')
    .replace(/@import[^;]*;?/gi, '')
    .replace(/url\(\s*['"]?\s*(?:https?|data|javascript):[^)]*\)/gi, 'none');
  if (!/\sxmlns=/.test(s.slice(0, s.indexOf('>') + 1))) s = s.replace(/<svg\b/i, '<svg xmlns="http://www.w3.org/2000/svg"');
  return s;
}

export interface Raster {
  ok: boolean;
  png: Buffer;
  width: number;
  height: number;
  error?: string;
}

/** Render an SVG to a width × height PNG. Null when no headless browser is available. */
export async function rasterizeSvg(svg: string, width: number, height: number): Promise<Raster | null> {
  const browser = await getBrowser();
  if (!browser) return null;
  const context = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 1 });
  try {
    const page = await context.newPage();
    await page.route('**/*', (route) => (route.request().url().startsWith('data:') ? route.continue() : route.abort()));
    const src = `data:image/svg+xml;base64,${Buffer.from(svg).toString('base64')}`;
    await page.setContent(`<!doctype html><html><body style="margin:0;background:#fff;overflow:hidden"><img id="p" style="display:block;width:${width}px;height:${height}px" src="${src}"></body></html>`, { waitUntil: 'load', timeout: 20_000 });
    const loaded = (await page
      .evaluate(
        `new Promise((resolve) => {
          const img = document.getElementById('p');
          const done = () => resolve(img.complete && img.naturalWidth > 0);
          if (img.complete) done(); else { img.onload = done; img.onerror = () => resolve(false); }
        })`,
      )
      .catch(() => false)) as boolean;
    const png = await page.screenshot({ type: 'png', clip: { x: 0, y: 0, width, height } });
    return { ok: loaded, png, width, height, error: loaded ? undefined : 'Chromium could not render the SVG (malformed or empty)' };
  } finally {
    await context.close().catch(() => {});
  }
}

export function browserReason(): string {
  return browserUnavailableReason() ?? 'headless Chromium is not available';
}

/** Bytes above which the judges get a JPEG copy. */
export const JUDGE_MAX_BYTES = 3_700_000;

/** The picture as the judges receive it: always named "painting", never anything about the artist. */
export async function judgeImage(img: GeneratedImage): Promise<ChatImage> {
  let data = img.data;
  let mediaType = img.mediaType;
  let bytes = img.bytes;
  let width = img.width;
  let height = img.height;
  if (bytes > JUDGE_MAX_BYTES) {
    const smaller = await shrinkForJudges(img).catch(() => null);
    if (smaller) ({ data, mediaType, bytes, width, height } = smaller);
  }
  const buf = Buffer.from(data, 'base64');
  return { name: mediaType === 'image/png' ? 'painting.png' : 'painting.jpg', mediaType, data, bytes, width, height, sha256: createHash('sha256').update(buf).digest('hex') };
}

async function shrinkForJudges(img: GeneratedImage): Promise<GeneratedImage | null> {
  const browser = await getBrowser();
  if (!browser) return null;
  const scale = Math.min(1, 1600 / Math.max(img.width || 1600, img.height || 1600));
  const w = Math.max(1, Math.round((img.width || 1536) * scale));
  const h = Math.max(1, Math.round((img.height || 1024) * scale));
  const context = await browser.newContext({ viewport: { width: w, height: h }, deviceScaleFactor: 1 });
  try {
    const page = await context.newPage();
    await page.setContent(`<!doctype html><html><body style="margin:0;overflow:hidden"><img style="display:block;width:${w}px;height:${h}px" src="data:${img.mediaType};base64,${img.data}"></body></html>`, { waitUntil: 'load', timeout: 20_000 });
    const jpg = await page.screenshot({ type: 'jpeg', quality: 90, clip: { x: 0, y: 0, width: w, height: h } });
    const info = imageInfo(jpg);
    return { mediaType: 'image/jpeg', data: jpg.toString('base64'), width: info?.width || w, height: info?.height || h, bytes: jpg.length };
  } finally {
    await context.close().catch(() => {});
  }
}
