/**
 * End-to-end check of the Grading Station on a real (offline) run.
 *
 *   node verification/grading/e2e.mjs [--shots <dir>]
 *
 * 1. Starts a private server (own data + config folders) with the Random Baseline, a second
 *    baseline contestant and three fake judges (baseline models from three other "vendors"; two can see images).
 * 2. Runs a small real run with NO judges, so every judged answer waits for the station: a game, an SVG,
 *    labels, a JSON answer key, a chart-reading vision test, a simulation replay and The Gallery (paintings).
 * 3. Grades answers in Human mode with the keyboard only (Playwright): labels, J/K, P to play the game,
 *    1–9/0 artistry ratings on a painting, Enter to save, D to dispute an answer key.
 * 4. Turns the fake judges on and grades in AI mode (cost shown in pounds, confirmed first), including a
 *    painting re-judged by the two vision judges only.
 * 5. Checks the grades, official scores, the monthly budget log and the 30-word summaries through the API,
 *    and that the summaries appear in the Grading Station, the result inspector, Run detail, the Presenter
 *    and the Studio facts.
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
models.contestants.push({ id: 'fake-judge-b', label: 'Fake Judge B', vendor: 'JudgeCo B', provider: 'baseline', model: 'judge-b', color: '#64748b', enabled: true, vision: false, pricing: { inputPerM: 0.5, outputPerM: 1.5 } });
models.contestants.push({ id: 'fake-judge-c', label: 'Fake Judge C', vendor: 'JudgeCo C', provider: 'baseline', model: 'judge-c', color: '#475569', enabled: true, vision: true, pricing: { inputPerM: 0.5, outputPerM: 1.5 } });
writeFileSync(join(config, 'models.json'), JSON.stringify(models, null, 2));
const settingsFile = join(config, 'settings.json');
const settings = JSON.parse(readFileSync(settingsFile, 'utf8'));
writeFileSync(settingsFile, JSON.stringify({ ...settings, judges: [] }, null, 2));

const PORT = 7900 + Math.floor(Math.random() * 90);
const env = { ...process.env, GAUNTLET_CONFIG_DIR: config, GAUNTLET_DATA_DIR: join(sandbox, 'data') };
const server = spawn(process.execPath, [join(ROOT, 'src', 'cli.ts'), 'serve', '--port', String(PORT)], { env, stdio: 'ignore' });
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
  const testIds = ['creative.one-shot-games', 'visual.svg-illustration', 'honesty.honesty-trap', 'extraction.structured-json', 'vision.read-the-chart', 'agentic.escape-room', 'art.gallery-masterpiece'];
  const { runId } = await api('POST', '/api/runs', { name: 'Grading e2e', contestantIds: ['random-baseline', 'mock-b'], testIds, repeats: 1 });
  for (;;) {
    const d = await api('GET', `/api/runs/${runId}`);
    if (!d.active && d.manifest.status !== 'queued' && d.manifest.status !== 'running') break;
    await new Promise((r) => setTimeout(r, 500));
  }
  const q0 = (await api('GET', `/api/grading/queue?runId=${runId}`)).items;
  check(q0.some((i) => i.need === 'judge-failed'), `judged answers wait for the station (${q0.filter((i) => i.todo).length} to grade)`);
  check(q0.some((i) => i.need === 'review' && i.kind === 'simulation'), 'simulation replays are listed as machine-scored');
  check(q0.some((i) => i.testId === 'art.gallery-masterpiece' && i.need === 'grade' && i.unit === 'commission'), 'Gallery paintings with no judges wait for a rating');

  const browser = await chromium.launch({ executablePath: chromiumPath() });
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  await ctx.addInitScript(() => {
    // Also runs inside sandboxed game frames, where storage is off limits: ignore it there.
    try {
      localStorage.setItem('gauntlet.rater', '"e2e"');
      localStorage.removeItem('gauntlet.grading.modes');
    } catch {}
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

  // J / K move between answers; P plays the game in its sandbox.
  await page.goto(`${BASE}/#/grading?run=${runId}&test=creative.one-shot-games&show=all`);
  await page.waitForSelector('.uv-cover');
  const keyNow = () => new URL(page.url().replace('#/', '')).searchParams.get('key');
  await page.keyboard.press('j');
  await page.waitForTimeout(400);
  const second = keyNow();
  await page.keyboard.press('k');
  await page.waitForTimeout(400);
  check(second !== null && keyNow() !== second, 'J goes to the next answer and K back');
  await page.waitForSelector('.uv-cover');
  await page.keyboard.press('p');
  await page.waitForSelector('.uv-html-stage.live iframe[sandbox="allow-scripts"]');
  check(true, 'P plays the game in a sandboxed frame (scripts only: no same-origin, no network)');
  if (SHOTS) await page.screenshot({ path: join(SHOTS, 'e2e-game-playing.png') });

  // A painting: six artistry lines rated 8 with the keyboard, then Enter.
  await page.goto(`${BASE}/#/grading?run=${runId}&test=art.gallery-masterpiece`);
  await page.waitForSelector('.hp-crit');
  const paintingKey = keyNow() ?? (await api('GET', `/api/grading/queue?runId=${runId}&testId=art.gallery-masterpiece`)).items[0].key;
  for (let n = 0; n < 6; n++) {
    await page.keyboard.press('8');
    await page.waitForTimeout(80);
  }
  if (SHOTS) await page.screenshot({ path: join(SHOTS, 'e2e-gallery-human.png') });
  await page.keyboard.press('Enter');
  await page.waitForTimeout(900);
  const painted = await api('GET', `/api/grading/item/${runId}/${encodeURIComponent(paintingKey)}`);
  check(painted.result.score === 0.8 && painted.result.humanScores[0].rater === 'e2e', 'the painting’s artistry rating (8/10) is stored as a human score and becomes its score');
  check(/Artistry 8\.0\/10 \(owner\)/.test(painted.result.summary) && painted.official.source === 'human', 'labelled as the owner’s (human) artistry');

  console.log('3. AI mode with fake judges from three other vendors');
  writeFileSync(settingsFile, JSON.stringify({ ...settings, judges: ['fake-judge-a', 'fake-judge-b', 'fake-judge-c'] }, null, 2));
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
  const costText = await page.textContent('body');
  check(!/\$\d/.test(costText ?? ''), 'costs on screen are in pounds (the owner’s currency), never dollars');

  // A painting in AI mode: only the two judges that can see pictures take part.
  const waiting = (await api('GET', `/api/grading/queue?runId=${runId}&testId=art.gallery-masterpiece`)).items.find((i) => i.todo);
  await page.goto(`${BASE}/#/grading?run=${runId}&key=${encodeURIComponent(waiting.key)}&show=all`);
  await page.waitForSelector('.gs-panel');
  await page.keyboard.press('a');
  await page.waitForSelector('.cd-total .cd-v');
  const costDialog = await page.textContent('.modal-root');
  check(/£/.test(costDialog) && /This month:/.test(costDialog), 'the cost is shown in pounds with this month’s budget before anything is spent');
  check(/Fake Judge A/.test(costDialog) && /Fake Judge C/.test(costDialog) && !/Fake Judge B/.test(costDialog), 'only vision judges from other vendors are asked about a painting');
  if (SHOTS) await page.screenshot({ path: join(SHOTS, 'e2e-gallery-ai-cost.png') });
  await page.click('.modal-foot .btn.primary');
  await page.waitForTimeout(3000);
  const judgedPainting = await api('GET', `/api/grading/item/${runId}/${encodeURIComponent(waiting.key)}`);
  check(judgedPainting.result.aiGrades.length === 2 && judgedPainting.result.aiGrades.every((g) => g.images === 1 && /artistry/.test(g.rationale)), 'each vision judge saw the painting and left a rationale');
  check(judgedPainting.result.status === 'ok' && judgedPainting.official.source === 'ai', 'the waiting painting is scored by the station’s judges with the Gallery’s own rubric');

  console.log('4. Money: every paid call is in My budget');
  const budget = await api('GET', '/api/budget');
  const graderSpend = budget.items.filter((i) => /^Grading Station: AI judges/.test(i.name));
  check(graderSpend.length >= 2 && graderSpend.every((i) => i.spentUsd > 0), `AI grading is logged in the monthly budget (${graderSpend.length} entries)`);

  console.log('5. Summaries everywhere');
  const s = await api('GET', `/api/grading/runs/${runId}/summaries`);
  const texts = Object.values(s.template);
  const PAIRS = testIds.length * 2;
  check(texts.length === PAIRS && texts.every((t) => t.split(/\s+/).length <= 30), `a ≤ 30-word summary for every model × test (${PAIRS})`);
  check(texts.every((t) => !/\$\d/.test(t)), 'summaries quote costs in pounds');
  const est = await api('POST', `/api/grading/runs/${runId}/summaries/estimate`, { pairs: ['random-baseline|agentic.escape-room'] });
  await api('POST', `/api/grading/runs/${runId}/summaries`, { pairs: ['random-baseline|agentic.escape-room'], confirmCostUsd: est.totalUsd });
  const s2 = await api('GET', `/api/grading/runs/${runId}/summaries`);
  check(!!s2.ai['random-baseline|agentic.escape-room'], 'an AI-written summary was cached after confirming its cost');
  check((await api('GET', '/api/budget')).items.some((i) => /^AI-written performance summaries/.test(i.name)), 'the AI summary is logged in the monthly budget');
  await page.goto(`${BASE}/#/grading?run=${runId}&test=honesty.honesty-trap&show=all`);
  await page.waitForSelector('.gs-item-head .ps-text');
  check(true, 'the Grading Station shows the summary for the answer on screen');
  await page.goto(`${BASE}/#/runs/${runId}?tab=matrix&test=honesty.honesty-trap&c=random-baseline`);
  await page.waitForSelector('.inspector-summary .ps-text', { timeout: 8000 });
  check(true, 'the result inspector shows the model × test summary');
  await page.goto(`${BASE}/#/runs/${runId}?tab=matrix`);
  await page.waitForSelector('.m-sums .ps-text');
  check((await page.$$('.m-sums .ps-row')).length === PAIRS, 'Run detail shows the summaries under every test row');
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
  check(studio.facts.length === PAIRS, 'the Studio offers the summaries to the script as facts');
  check(errors.length === 0, `no page errors${errors.length ? `: ${errors.join(' | ')}` : ''}`);
  await browser.close();
  console.log('All grading e2e checks passed.');
} finally {
  server.kill();
}
// Exit explicitly: nothing else should keep the process alive after the checks.
process.exit(process.exitCode ?? 0);
