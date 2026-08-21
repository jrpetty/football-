/**
 * Corrupt the persisted state and check the app still renders.
 *
 * The Build Wizard and the System Health flow now restore their answers from
 * sessionStorage. That makes a stored value an INPUT rather than state: it can
 * come from a stale session written by an older build, from a hand-edited
 * store, or from an entry that was half-written when a tab closed. Trusting it
 * stranded the wizard — a step index of 99 rendered the heading, the step rail
 * and then nothing at all, no active step and no explanation.
 *
 * Every case below must leave a rendered screen, exactly one active step where
 * there is a step rail, and no page errors.
 *
 * Needs a preview server on 4173: npm run build && npx vite preview --port 4173
 * Run: node scripts/sticky-fuzz.mjs
 */
import { chromium } from 'playwright';

const BASE = process.env.RIGCHECK_PREVIEW ?? 'http://localhost:4173';
const CHROME = process.env.CHROME_PATH ?? '/opt/pw-browsers/chromium-1194/chrome-linux/chrome';

const CASES = [
  ['wizard', 'step past the end', [['wiz.step', '99']]],
  ['wizard', 'step negative', [['wiz.step', '-3']]],
  ['wizard', 'step not a number', [['wiz.step', '"three"']]],
  ['wizard', 'step fractional', [['wiz.step', '2.5']]],
  ['wizard', 'reached past the end', [['wiz.reached', '404']]],
  ['wizard', 'gameIds not an array', [['wiz.gameIds', '{"a":1}']]],
  ['wizard', 'gameIds unknown ids', [['wiz.gameIds', '["nope","also-nope"]']]],
  ['wizard', 'budget null', [['wiz.budget', 'null']]],
  ['wizard', 'corrupt json', [['wiz.usage', '{not json']]],
  ['system', 'unknown stage', [['sh.stage', '"nowhere"']]],
  ['system', 'stage not a string', [['sh.stage', '42']]],
  ['system', 'measurements not an array', [['sh.measurements', '"nope"']]],
  ['system', 'fixesApplied not an array', [['sh.fixesApplied', '3']]],
  ['inventory', 'pile missing arrays', [['pile', '{"cpuIds":null}']]],
  ['inventory', 'pile is an array', [['pile', '[]']]],
  ['trade', 'pile null', [['pile', 'null']]],
  ['trade', 'pile is a string', [['pile', '"a pile"']]],
];

const browser = await chromium.launch({ executablePath: CHROME });
const page = await browser.newPage({ viewport: { width: 1440, height: 950 } });
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));

let failed = 0;
for (const [route, name, entries] of CASES) {
  await page.goto(`${BASE}/#/${route}`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(350);
  await page.evaluate((es) => { for (const [k, v] of es) sessionStorage.setItem(k, v); }, entries);
  await page.reload({ waitUntil: 'networkidle' });
  await page.waitForTimeout(650);

  const r = await page.evaluate(() => ({
    panels: document.querySelectorAll('.panel, .consent').length,
    steps: document.querySelectorAll('.step').length,
    active: document.querySelectorAll('.step.on').length,
  }));
  const errs = errors.length;
  errors.length = 0;
  await page.evaluate(() => sessionStorage.clear());

  const ok = r.panels > 0 && errs === 0 && (r.steps === 0 || r.active === 1);
  if (!ok) failed++;
  console.log(
    `${ok ? 'ok  ' : 'FAIL'} ${route.padEnd(10)} ${name.padEnd(26)} ` +
    `panels=${r.panels} activeStep=${r.active}/${r.steps} errors=${errs}`,
  );
}

await browser.close();
console.log(`\n${CASES.length - failed}/${CASES.length} corrupted-state cases degraded gracefully`);
process.exit(failed ? 1 : 0);
