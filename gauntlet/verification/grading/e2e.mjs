/**
 * End-to-end check of the Grading Station on a real (offline) run.
 *
 *   node verification/grading/e2e.mjs [--shots <dir>]
 *
 * 1. Starts a private server (own data + config folders) with the Random Baseline, a second
 *    baseline contestant and two fake judges (baseline models from two other "vendors").
 * 2. Runs a small real run with NO judges, so every judged answer waits for the station.
 * 3. Grades answers in Human mode with the keyboard only (Playwright), then turns the fake
 *    judges on and grades the rest in AI mode (cost shown, confirmed with Enter).
 * 4. Checks the grades, official scores and 30-word summaries through the API, and that the
 *    summaries appear in the Run detail page and the Presenter.
 * Needs playwright-core and a Chromium (GAUNTLET_CHROMIUM or /opt/pw-browsers).
 */
import { spawn } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const shotsArg = process.argv.indexOf('--shots');
const SHOTS = shotsArg > 0 ? process.argv[shotsArg + 1] : null;
const { chromium } = await import('playwright-core');

function chromiumPath() {
  if (process.env.GAUNTLET_CHROMIUM) return process.env.GAUNTLET_CHROMIUM;
  const base = '/opt/pw-browsers';
  for (const d of existsSync(base) ? readdirSync(base).filter((x) => /^chromium-\d/.test(x)) : []) {
    const p = join(base, d, 'chrome-linux', 'chrome');
    if (existsSync(p)) return p;
  }
  return undefined;
}

const sandbox = mkdtempSync(join(tmpdir(), 'gauntlet-grading-e2e-'));
const config = join(sandbox, 'config');
cpSync(join(ROOT, 'config'), config, { recursive: true });
const models = JSON.parse(readFileSync(join(config, 'models.json'), 'utf8'));
models.contestants.push({ id: 'mock-b', label: 'Mock B', vendor: 'Test Labs', provider: 'baseline', model: 'random-b', color: '#e879f9', enabled: true, pricing: { inputPerM: 0, outputPerM: 0 } });
models.contestants.push({ id: 'fake-judge-a', label: 'Fake Judge A', vendor: 'JudgeCo A', provider: 'baseline', model: 'judge-a', color: '#94a3b8', enabled: true, vision: true, pricing: { inputPerM: 0.5, outputPerM: 1.5 } });
models.contestants.push({ id: 'fake-judge-b', label: 'Fake Judge B', vendor: 'JudgeCo B', provider: 'baseline', model: 'judge-b', color: '#64748b', enabled: true, pricing: { inputPerM: 0.5, outputPerM: 1.5 } });
writeFileSync(join(config, 'models.json'), JSON.stringify(models, null, 2));
const settingsFile = join(config, 'settings.json');
const settings = JSON.parse(readFileSync(settingsFile, 'utf8'));
writeFileSync(settingsFile, JSON.stringify({ ...settings, judges: [] }, null, 2));

const PORT = 7900 + Math.floor(Math.random() * 90);
const env = { ...process.env, GAUNTLET_CONFIG_DIR: config, GAUNTLET_DATA_DIR: join(sandbox, 'data') };
const server = spawn(process.execPath, [join(ROOT, 'src', 'cli.ts'), 'serve', '--port', String(PORT)], { env, stdio: 'pipe' });
const BASE = `http://127.0.0.1:${PORT}`;
const api = async (method, path, body) => {
  const r = await fetch(BASE + path, { method, headers: body ? { 'content-type': 'application/json' } : {}, body: body ? JSON.stringify(body) : undefined });
  const j = await r.json();
  if (!r.ok) throw new Error(`${method} ${path}: ${j.error}`);
  return j;
};
const check = (ok, msg) => {
  if (!ok) throw new Error(`FAILED: ${msg}`);
  console.log(`  ✓ ${msg}`);
};

try {
  for (let i = 0; i < 50; i++) {
    try {
      await api('GET', '/api/meta');
      break;
    } catch {
      await new Promise((r) => setTimeout(r, 200));
    }
  }
  console.log('1. Real run (no judges configured)');
  const testIds = ['creative.one-shot-games', 'visual.svg-illustration', 'honesty.honesty-trap', 'extraction.structured-json', 'vision.read-the-chart', 'agentic.escape-room'];
  const { runId } = await api('POST', '/api/runs', { name: 'Grading e2e', contestantIds: ['random-baseline', 'mock-b'], testIds, repeats: 1 });
  for (;;) {
    const d = await api('GET', `/api/runs/${runId}`);
    if (!d.active && d.manifest.status !== 'queued' && d.manifest.status !== 'running') break;
    await new Promise((r) => setTimeout(r, 500));
  }
  const q0 = (await api('GET', `/api/grading/queue?runId=${runId}`)).items;
  check(q0.some((i) => i.need === 'judge-failed'), `judged answers wait for the station (${q0.filter((i) => i.todo).length} to grade)`);
  check(q0.some((i) => i.need === 'review' && i.kind === 'simulation'), 'simulation replays are listed as machine-scored');

  const browser = await chromium.launch({ executablePath: chromiumPath() });
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  await ctx.addInitScript(() => {
    localStorage.setItem('gauntlet.rater', '"e2e"');
    localStorage.removeItem('gauntlet.grading.modes');
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));

  console.log('2. Human mode, keyboard only');
  await page.goto(`${BASE}/#/grading?run=${runId}&test=honesty.honesty-trap`);
  await page.waitForSelector('.hp-labels');
  for (let n = 0; n < 3; n++) {
    await page.keyboard.press('3'); // label 3 (PARTIAL)
    await page.waitForTimeout(150);
    await page.keyboard.press('Enter');
    await page.waitForTimeout(900);
  }
  if (SHOTS) await page.screenshot({ path: join(SHOTS, 'e2e-human.png') });
  const afterHuman = (await api('GET', `/api/grading/queue?runId=${runId}`)).items.filter((i) => i.humans > 0);
  check(afterHuman.length === 3, 'three answers graded with 3 + Enter');
  const graded = await api('GET', `/api/grading/item/${runId}/${encodeURIComponent(afterHuman[0].key)}`);
  check(graded.result.status === 'ok' && graded.result.score === 0.5 && graded.result.humanScores[0].blind === true, 'grade stored as a blind human score (PARTIAL = 50) and made official');
  check(graded.official.source === 'human', 'official score comes from the human grade (no AI verdict in the run)');

  // The machine-scored JSON answer: a human can only dispute.
  await page.goto(`${BASE}/#/grading?run=${runId}&test=extraction.structured-json&show=all`);
  await page.waitForSelector('.gs-panel');
  await page.keyboard.press('d');
  await page.keyboard.type('Check the date format rule.');
  await page.keyboard.press('Control+Enter');
  await page.waitForTimeout(800);
  const disputed = (await api('GET', `/api/grading/queue?runId=${runId}`)).items.filter((i) => i.disputes > 0);
  check(disputed.length === 1, 'a dispute was flagged on a machine-scored answer (score unchanged)');

  console.log('3. AI mode with fake judges from two other vendors');
  writeFileSync(settingsFile, JSON.stringify({ ...settings, judges: ['fake-judge-a', 'fake-judge-b'] }, null, 2));
  await page.goto(`${BASE}/#/grading?run=${runId}&test=visual.svg-illustration`);
  await page.waitForSelector('.gs-panel');
  await page.click('button[title*="AI judges from"]');
  await page.waitForTimeout(400);
  await page.keyboard.press('a');
  await page.waitForSelector('.cd-total .cd-v');
  if (SHOTS) await page.screenshot({ path: join(SHOTS, 'e2e-ai-cost.png') });
  await page.click('.modal-foot .btn.primary');
  await page.waitForTimeout(4000);
  if (SHOTS) await page.screenshot({ path: join(SHOTS, 'e2e-ai-graded.png') });
  const ai = (await api('GET', `/api/grading/queue?runId=${runId}`)).items.filter((i) => i.ais > 0);
  check(ai.length >= 1, `answers graded by AI judges after confirming the cost (${ai.length})`);
  const aiItem = await api('GET', `/api/grading/item/${runId}/${encodeURIComponent(ai[0].key)}`);
  check(aiItem.result.aiGrades.every((g) => g.vendor.startsWith('JudgeCo') && g.rationale), 'per-judge rationales stored, judges from other vendors');
  check(aiItem.result.status === 'ok' && typeof aiItem.result.score === 'number', 'the judge-failed answer now has an official score');

  console.log('4. Summaries everywhere');
  const s = await api('GET', `/api/grading/runs/${runId}/summaries`);
  const texts = Object.values(s.template);
  check(texts.length === 12 && texts.every((t) => t.split(/\s+/).length <= 30), 'a ≤ 30-word summary for every model × test');
  const est = await api('POST', `/api/grading/runs/${runId}/summaries/estimate`, { pairs: ['random-baseline|agentic.escape-room'] });
  await api('POST', `/api/grading/runs/${runId}/summaries`, { pairs: ['random-baseline|agentic.escape-room'], confirmCostUsd: est.totalUsd });
  const s2 = await api('GET', `/api/grading/runs/${runId}/summaries`);
  check(!!s2.ai['random-baseline|agentic.escape-room'], 'an AI-written summary was cached after confirming its cost');
  await page.goto(`${BASE}/#/runs/${runId}?tab=matrix`);
  await page.waitForSelector('.m-sums .ps-text');
  check((await page.$$('.m-sums .ps-row')).length === 12, 'Run detail shows the summaries under every test row');
  if (SHOTS) await page.screenshot({ path: join(SHOTS, 'e2e-run-detail.png') });
  await page.goto(`${BASE}/#/present/${runId}`);
  await page.waitForTimeout(1500);
  for (let i = 0; i < 12 && !(await page.$('.rr-perf')); i++) {
    await page.keyboard.press('ArrowRight');
    await page.waitForTimeout(700);
  }
  check(!!(await page.$('.rr-perf')), 'the Presenter shows a summary line on the results slide');
  if (SHOTS) await page.screenshot({ path: join(SHOTS, 'e2e-presenter.png') });
  const studio = await api('GET', `/api/studio/${runId}`);
  check(studio.facts.length === 12, 'the Studio offers the summaries to the script as facts');
  check(errors.length === 0, `no page errors${errors.length ? `: ${errors.join(' | ')}` : ''}`);
  await browser.close();
  console.log('All grading e2e checks passed.');
} finally {
  server.kill();
}
