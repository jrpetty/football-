import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { craftedRun } from './helpers/studio-fixture.ts';
import { findHighlights } from '../src/media/highlights.ts';
import { cardData, cardSize, renderCardHtml, CARD_KINDS } from '../src/media/cards.ts';
import { renderPng } from '../src/media/studio.ts';
import { browserAvailable, closeBrowser } from '../src/scoring/browser.ts';

after(() => closeBrowser());

/** Width and height from a PNG's IHDR chunk. */
function pngSize(buf: Buffer): { width: number; height: number } {
  assert.equal(buf.subarray(0, 8).toString('hex'), '89504e470d0a1a0a', 'PNG signature');
  return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
}

test('thumbnail and Shorts templates render to PNG at their exact size in headless Chromium', async (t) => {
  if (!(await browserAvailable())) {
    t.skip('no Chromium / Chrome / Edge available');
    return;
  }
  const { input } = craftedRun();
  const hs = findHighlights(input);
  const data = cardData(input, hs);
  for (const k of CARD_KINDS) {
    const html = renderCardHtml(data, { kind: k.id, style: k.id === 'thumbnail' ? 'versus' : 'neon', testId: 'agentic.island', highlightId: hs[0]!.id });
    const png = await renderPng(html, cardSize(k.id).width, cardSize(k.id).height);
    assert.ok(png, k.id);
    assert.deepEqual(pngSize(png), cardSize(k.id), k.id);
    assert.ok(png.length > 10_000, `${k.id} is not blank`);
  }
});
