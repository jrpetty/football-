// Screenshots of the one Business close-up system in demo mode (?mock=1): for a people-and-money test
// (cv-shortlist) and a customers-and-data test (inbox-triage), "See an example" on the Business tests page, the
// Result Inspector, and "See it do this job" on the jobs page; plus compare mode for "Screen job applicants".
// Also checks that every Business test card has at most one "See an example" button.
// Usage: node verification/biz-docs/unified-screens.mts <baseUrl> <outDir>
import { chromium, type Page } from 'playwright-core';
import { existsSync, mkdirSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const base = process.argv[2] ?? 'http://127.0.0.1:5198';
const out = process.argv[3] ?? join(import.meta.dirname, 'unified-shots');
mkdirSync(out, { recursive: true });
const root = '/opt/pw-browsers';
const dir = existsSync(root) ? readdirSync(root).find((d) => /^chromium-\d+$/.test(d)) : undefined;
const exe = dir ? ['chrome-linux64', 'chrome-linux'].map((d) => join(root, dir, d, 'chrome')).find((p) => existsSync(p)) : undefined;
const browser = await chromium.launch({ executablePath: exe });

async function page(): Promise<{ p: Page; errors: string[] }> {
  const p = await browser.newPage({ viewport: { width: 1600, height: 1100 }, deviceScaleFactor: 2 });
  const errors: string[] = [];
  p.on('pageerror', (e) => errors.push(e.message));
  p.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  await p.addInitScript(() => localStorage.setItem('gauntlet.theme', 'dark'));
  return { p, errors };
}

const UNROLL = '.lc-root{position:absolute!important;inset:0 auto auto 0!important;width:100vw!important;display:block!important}.lc-dialog{max-height:none!important;height:auto!important;overflow:visible!important;margin:0 auto}.lc-body{overflow:visible!important}.lc-beat{animation:none!important}';

async function closeUp(name: string, hash: string) {
  const { p, errors } = await page();
  await p.goto(`${base}/?mock=1${hash}`);
  await p.waitForSelector('.lc-dialog', { timeout: 30000 });
  await p.waitForFunction(() => !document.querySelector('.lc-loading'), null, { timeout: 30000 }).catch(() => undefined);
  await p.waitForTimeout(1500);
  const doc = await p.locator('.lc-dialog .cd-root, .lc-dialog .pm-root, .lc-dialog [class*="pm-"]').count();
  await p.addStyleTag({ content: UNROLL });
  await p.waitForTimeout(300);
  await p.locator('.lc-dialog').screenshot({ path: join(out, `${name}.png`) });
  console.log(name, doc ? 'document picture' : 'NO DOCUMENT PICTURE', errors.length ? `errors: ${errors.slice(0, 2).join(' | ')}` : '');
  await p.close();
}

async function viewport(name: string, hash: string, act?: (p: Page) => Promise<void>, el?: string) {
  const { p, errors } = await page();
  await p.setViewportSize({ width: 1600, height: 2000 });
  await p.goto(`${base}/?mock=1${hash}`);
  await p.waitForTimeout(3500);
  if (act) await act(p);
  await p.waitForTimeout(2500);
  const target = el ? p.locator(el).first() : null;
  if (target && (await target.count())) await target.screenshot({ path: join(out, `${name}.png`) });
  else await p.screenshot({ path: join(out, `${name}.png`) });
  const doc = await p.locator('.cd-root, [class*="pm-"]').count();
  console.log(name, doc ? 'document picture' : 'NO DOCUMENT PICTURE', errors.length ? `errors: ${errors.slice(0, 2).join(' | ')}` : '');
  await p.close();
}

// One "See an example" per card.
{
  const { p } = await page();
  await p.goto(`${base}/?mock=1#/business/tests`);
  await p.waitForSelector('.biz-tcard', { timeout: 60000 });
  await p.waitForTimeout(4000);
  const counts = await p.locator('.biz-tcard').evaluateAll((cards) => cards.map((c) => c.querySelectorAll('button').length && [...c.querySelectorAll('button')].filter((b) => /See an example/.test(b.textContent ?? '')).length));
  console.log(`test cards: ${counts.length}, with one example button: ${counts.filter((n) => n === 1).length}, with more than one: ${counts.filter((n) => n > 1).length}`);
  await p.close();
}

for (const [short, test, job] of [
  ['cv-shortlist', 'work.cv-shortlist', 'screen-applicants'],
  ['inbox-triage', 'work.inbox-triage', 'inbox-admin'],
] as const) {
  await closeUp(`${short}-tests-page-example`, `#/business/tests?example=${test}`);
  await viewport(`${short}-result-inspector`, `#/runs/run-2026-10-02-business?test=${test}&c=helios-quill-flash`);
  await viewport(
    `${short}-jobs-see-it`,
    `#/business/jobs?job=${job}`,
    async (p) => {
      // The "See it do this job" whose example is from this test (else the first one).
      const name = short === 'cv-shortlist' ? 'Shortlist the Candidates' : 'Monday Inbox';
      await p.waitForSelector('.bj-see', { timeout: 60000 });
      const row = p.locator('.bj-see', { hasText: name }).first();
      console.log(`  rows with ${name}: ${await p.locator('.bj-see', { hasText: name }).count()}`);
      if (await row.count()) await row.getByRole('button').click();
      else await p.getByRole('button', { name: /See it do this job/ }).first().click();
      await p.waitForTimeout(3500);
      await p.addStyleTag({ content: `${UNROLL}.modal-root{position:absolute!important;inset:0 auto auto 0!important;width:100vw}.modal{max-height:none!important}.modal-body{overflow:visible!important;max-height:none!important}` });
      await p.waitForTimeout(500);
    },
    '.modal, .lc-dialog',
  );
}
await viewport('screen-applicants-compare', '#/business/jobs?job=screen-applicants', async (p) => {
  await p.getByRole('tab', { name: 'Compare two models on this job' }).click();
  await p.waitForTimeout(3000);
});
await browser.close();
