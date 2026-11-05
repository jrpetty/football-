// Demo consistency of the Business document pictures (?mock=1): for every result of the Business demo run and the
// Business ladders demo run on a test drawn as paperwork, the run's score (model buttons, leaderboards) must equal
// the full result's score (close-up, inspector, jobs page), and opening results in a different order must give the
// same replies (up to eight answers per test). Usage: node verification/biz-docs/demo-consistency.mts <baseUrl>
import { chromium } from 'playwright-core';
import { existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
const root = '/opt/pw-browsers';
const dir = existsSync(root) ? readdirSync(root).find((d) => /^chromium-\d+$/.test(d)) : undefined;
const exe = dir ? ['chrome-linux64', 'chrome-linux'].map((d) => join(root, dir, d, 'chrome')).find((p) => existsSync(p)) : undefined;
const b = await chromium.launch({ executablePath: exe });
async function sweep(reverse: boolean): Promise<Record<string, { lite: number | null; full: number | null; reply: string }>> {
  const p = await b.newPage();
  await p.goto(`${process.argv[2] ?? 'http://127.0.0.1:5198'}/?mock=1#/`);
  await p.waitForTimeout(3000);
  const out = await p.evaluate(async (rev) => {
    const m = await import('/src/mock/mockServer.ts' as string);
    const reg = await import('/src/business/docs/docVisuals.ts' as string);
    const ids = new Set(Object.keys(reg.BUSINESS_DOC_VISUALS));
    const res: Record<string, { lite: number | null; full: number | null; reply: string }> = {};
    for (const run of ['run-2026-10-02-business', 'run-2026-10-21-business-ladders']) {
      const d = (await m.handle('GET', `/api/runs/${run}`)) as { results: Array<{ key: string; testId: string; score: number | null; status: string }> };
      // Up to eight answers per test (every model on the first cases): enough to cover all of them quickly.
      const per = new Map<string, number>();
      const mine = d.results.filter((r) => ids.has(r.testId) && r.status === 'ok' && (per.set(r.testId, (per.get(r.testId) ?? 0) + 1).get(r.testId) ?? 0) <= 8);
      if (rev) mine.reverse();
      for (const r of mine) {
        const f = (await m.handle('GET', `/api/runs/${run}/results/${encodeURIComponent(r.key)}`)) as { score: number | null; transcript: Array<{ response: string }> };
        res[`${run}|${r.key}`] = { lite: r.score, full: f.score, reply: f.transcript.map((t) => t.response).join('\n') };
      }
    }
    return { res, n: ids.size };
  }, reverse);
  await p.close();
  console.log(`doc tests in the registry: ${out.n}`);
  return out.res;
}
const a = await sweep(false);
const z = await sweep(true);
let bad = 0;
const tests = new Set<string>();
for (const [k, v] of Object.entries(a)) {
  tests.add(k.split('::')[1] ?? k);
  if (v.lite !== v.full) {
    bad++;
    if (bad < 8) console.log(`score differs ${k}: run ${v.lite} vs result ${v.full}`);
  }
  if (z[k]?.reply !== v.reply) {
    bad++;
    if (bad < 8) console.log(`reply depends on order: ${k}`);
  }
}
console.log(`${Object.keys(a).length} results across ${tests.size} tests checked · ${bad} problems`);
await b.close();
process.exit(bad ? 1 : 0);
