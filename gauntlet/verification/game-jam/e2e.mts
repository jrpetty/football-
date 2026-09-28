/**
 * The Game Jam end to end: every sample game in ./samples goes through the REAL scorer (extraction, the genre
 * playtest in headless Chromium, the automatic checks, the judge prompt, verdict parsing, weighting and caps).
 * Only the judges are stand-ins: two scripted panels that answer in the exact format real judges must use, one of
 * them "seeing" the screenshots. The script asserts that the working games pass the automatic checks and that the
 * broken ones (syntax error, frozen loop, blank canvas, a reply cut off by the output limit) are caught.
 *
 *   node verification/game-jam/e2e.mts            run and check
 *   node verification/game-jam/e2e.mts --record   also write the mock-mode fixtures (ui/src/mock/gameJamRecorded.json)
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { scoreResponse, type JudgeCall, type JudgePanel } from '../../src/scoring/index.ts';
import { closeBrowser, getBrowser } from '../../src/scoring/browser.ts';
import { gameJamOf } from '../../src/scoring/game-jam-shared.ts';
import { renderCase } from '../../src/core/registry.ts';
import type { ArtifactKind, PromptTest, ScoreDetail, StopReason } from '../../src/core/types.ts';

const HERE = import.meta.dirname;
const ROOT = join(HERE, '..', '..');
const test = JSON.parse(readFileSync(join(ROOT, 'tests', 'creative', 'game-jam.json'), 'utf8')) as PromptTest;
const caseOf = (genre: string) => test.cases.find((c) => (c.expected as { genre: string }).genre === genre)!;

interface Scripted {
  /** One letter per requirement: P pass, H partial (half), F fail. */
  reqs: string;
  plays: number;
  feel: number;
  creativity: number;
  visuals: number;
  ambition: number;
  verdict: string;
}
interface Entry {
  file: string;
  genre: string;
  /** Expected outcome of the automatic checks. */
  expect: 'works' | 'syntax' | 'frozen' | 'blank' | 'truncated';
  /** Stand-in verdicts of the two scripted judges (the second is a little harsher). */
  judge?: Scripted;
  /** Mock-mode model id and the model it plays in the demo. */
  model: string;
  truncateAt?: number;
}

const ENTRIES: Entry[] = [
  { file: 'flappy-full.html', genre: 'flappy', expect: 'works', model: 'meridian-atlas-4-ultra', judge: { reqs: 'PPPPPPPPPPPHPP', plays: 9, feel: 8, creativity: 8, visuals: 5, ambition: 7, verdict: 'Charming moth theme and tight feel, but flat shapes and simple lighting look like a prototype.' } },
  { file: 'rts-mini.html', genre: 'rts', expect: 'works', model: 'meridian-atlas-4-ultra', judge: { reqs: 'PHFFHHHFHPHHFHF', plays: 5, feel: 4, creativity: 4, visuals: 2, ambition: 3, verdict: 'Harvesting, box-select and fog work, but there is no base building, power or tech tree.' } },
  { file: 'rpg-mini.html', genre: 'rpg', expect: 'works', model: 'meridian-atlas-4-ultra', judge: { reqs: 'HHHFFHPHFFFFHHF', plays: 6, feel: 6, creativity: 5, visuals: 3, ambition: 3, verdict: 'Crisp sword combat with knockback, but only two areas, no boss, shop or saving.' } },
  { file: 'zombie-mini.html', genre: 'zombie', expect: 'works', model: 'meridian-atlas-4-ultra', judge: { reqs: 'HPHHHPHFHHFHFFHFFHH', plays: 6, feel: 6, creativity: 5, visuals: 4, ambition: 3, verdict: 'Tense flashlight nights and noise that draws the dead, but most survival systems are missing.' } },
  { file: 'racing-full.html', genre: 'racing', expect: 'works', model: 'meridian-atlas-4-ultra', judge: { reqs: 'PPPPPPPPPPPPPHP', plays: 9, feel: 8, creativity: 7, visuals: 6, ambition: 8, verdict: 'A coastal OutRun that turns to dusk with slipstream boost; solid, but flat sprites fall short of commercial.' } },
  { file: 'ember-wing.html', genre: 'flappy', expect: 'works', model: 'kestrel-kite-reasoner', judge: { reqs: 'HPPHFPHFFHHHFH', plays: 7, feel: 6, creativity: 6, visuals: 3, ambition: 3, verdict: 'A burning bird with a heat meter is a fun twist, but medals, parallax and sound are missing.' } },
  { file: 'broken-frozen.html', genre: 'rts', expect: 'frozen', model: 'kestrel-kite-reasoner', judge: { reqs: 'FFFFFFFFFFFFFFF', plays: 1, feel: 0, creativity: 2, visuals: 1, ambition: 1, verdict: 'The title draws, then starting a match locks the page in an endless loop.' } },
  { file: 'broken-syntax.html', genre: 'rpg', expect: 'syntax', model: 'kestrel-kite-reasoner', judge: { reqs: 'FFFFFFFFFFFFFFF', plays: 0, feel: 0, creativity: 1, visuals: 0, ambition: 1, verdict: 'A missing bracket stops the script from parsing: nothing ever appears.' } },
  { file: 'zombie-mini.html', genre: 'zombie', expect: 'truncated', truncateAt: 0.55, model: 'kestrel-kite-reasoner' },
  { file: 'racing-topdown-mini.html', genre: 'racing', expect: 'works', model: 'kestrel-kite-reasoner', judge: { reqs: 'HHFHHHFFHHHFFFH', plays: 6, feel: 6, creativity: 4, visuals: 2, ambition: 3, verdict: 'A slidey little top-down rally with skid marks, but the rivals just circle and there is no HUD to speak of.' } },
  { file: 'broken-blank.html', genre: 'flappy', expect: 'blank', model: 'helios-quill-flash', judge: { reqs: 'FFFFFFFFFFFFFF', plays: 1, feel: 0, creativity: 1, visuals: 0, ambition: 0, verdict: 'Runs without errors but draws black on black: there is nothing to see.' } },
];

function verdictText(s: Scripted, harsh: boolean): string {
  const drop = (n: number) => Math.max(0, n - (harsh ? 1 : 0));
  const lines = [...s.reqs].map((l, i) => `R${i + 1}: ${l === 'P' ? 'PASS' : l === 'H' ? 'PARTIAL' : 'FAIL'} — ${l === 'P' ? 'implemented and working' : l === 'H' ? 'present but simpler than asked' : 'missing'}`);
  return [
    'I read the whole file and compared it with the playtest.',
    ...lines,
    `PLAYS: ${drop(s.plays)}`,
    `FEEL: ${drop(s.feel)}`,
    `CREATIVITY: ${s.creativity}`,
    `POLISH: ${drop(s.polish)}`,
    `AMBITION: ${s.ambition}`,
    `VERDICT: ${s.verdict}`,
  ].join('\n');
}

/** The demo's judge pair for each demo model: two of the mock judges, never the contestant's own vendor. */
const JUDGES: Record<string, [string, string]> = {
  'meridian-atlas-4-ultra': ['helios-nova-3-pro', 'kestrel-kite-reasoner'],
  'kestrel-kite-reasoner': ['helios-nova-3-pro', 'meridian-atlas-4-ultra'],
  'helios-quill-flash': ['meridian-atlas-4-ultra', 'kestrel-kite-reasoner'],
};

function panel(s: Scripted | undefined, prompts: string[], model: string): JudgePanel {
  const [a, b] = JUDGES[model]!;
  return {
    ids: [a, b],
    async ask(_system, user, _label, opts): Promise<JudgeCall[]> {
      prompts.push(user);
      lastImages = Array.isArray(opts) ? opts.length : (opts?.images?.length ?? 0);
      if (!s) return [];
      return [
        { judgeId: a, text: verdictText(s, false), sawImages: Boolean(Array.isArray(opts) ? opts.length : opts?.images?.length) },
        { judgeId: b, text: verdictText(s, true), sawImages: false },
      ];
    },
  };
}

function sampleHtml(file: string): string {
  if (file === 'ember-wing.html') {
    // The one-shot games demo's recorded Flappy entry, reused as a second Flappy contestant.
    const rec = JSON.parse(readFileSync(join(ROOT, 'ui', 'src', 'mock', 'visualPassRecorded.json'), 'utf8'));
    return rec['creative.one-shot-games|meridian-atlas-4-ultra|g01'].artifacts['artifact.html'];
  }
  return readFileSync(join(HERE, 'samples', file), 'utf8');
}

async function toJpeg(png: Buffer, width = 640): Promise<string> {
  const browser = await getBrowser();
  const ctx = await browser!.newContext();
  const page = await ctx.newPage();
  const url = (await page.evaluate(
    `(async () => { const i = new Image(); i.src = 'data:image/png;base64,${png.toString('base64')}'; await i.decode(); const c = document.createElement('canvas'); c.width = ${width}; c.height = Math.round(${width} * i.height / i.width); c.getContext('2d').drawImage(i, 0, 0, c.width, c.height); return c.toDataURL('image/jpeg', 0.72); })()`,
  )) as string;
  await ctx.close();
  return url;
}

let lastImages = 0;
const record = process.argv.includes('--record');
const recorded: Record<string, unknown> = {};
let failures = 0;
const check = (ok: boolean, what: string) => {
  if (!ok) failures++;
  console.log(`   ${ok ? 'ok  ' : 'FAIL'} ${what}`);
};

for (const e of ENTRIES) {
  const c = caseOf(e.genre);
  let html = sampleHtml(e.file);
  let response = `\`\`\`html\n${html}\n\`\`\``;
  let stopReason: StopReason = 'end';
  if (e.truncateAt) {
    response = response.slice(0, Math.floor(response.length * e.truncateAt));
    stopReason = 'max_tokens';
    html = response;
  }
  const saved: Record<string, { kind: ArtifactKind; content: string | Buffer }> = {};
  const prompts: string[] = [];
  const t0 = Date.now();
  const rendered = renderCase(test, c);
  const out = await scoreResponse({
    scorer: test.scorer,
    expected: c.expected,
    response,
    stopReason,
    taskText: `[User]\n${rendered.turns[0]}`,
    judges: panel(e.judge, prompts, e.model),
    saveArtifact: (name, kind, content) => {
      saved[name] = { kind, content };
      return { name, kind, file: name, bytes: typeof content === 'string' ? content.length : content.length };
    },
    signal: new AbortController().signal,
  });
  const d = out.detail as ScoreDetail;
  const gj = gameJamOf(d)!;
  const item = (re: RegExp) => d.items?.find((i) => re.test(i.label));
  console.log(`\n${e.model.padEnd(24)} ${e.genre.padEnd(7)} ${e.file.padEnd(26)} → ${(out.score! * 100).toFixed(0).padStart(3)}  ${out.summary}  (${((Date.now() - t0) / 1000).toFixed(1)} s)`);
  for (const i of d.items ?? []) if (!i.passed) console.log(`   · failed: ${i.label}${i.detail ? ` (${i.detail})` : ''}`);
  if (e.expect === 'works') {
    check((d.items ?? []).every((i) => i.passed), 'a working game passes every automatic check');
    check(gj.playtest?.frames.length === 8 && Object.keys(saved).filter((n) => /^playtest-\d+ms\.png$/.test(n)).length === 8, '8 full-HD playtest screenshots saved');
    check(Boolean(gj.playtest?.motion) && 'playtest-motion.png' in saved, 'motion strip saved');
    check(lastImages === 9, 'vision judge got 8 screenshots + the motion strip');
    check(gj.judges.length === 2 && gj.requirements.every((r) => r.verdict !== null), 'both judges parsed, every requirement has a verdict');
    check(prompts[0]?.includes('Screenshots attached in order') ?? false, 'judges are told about the attached screenshots');
  } else if (e.expect === 'syntax') {
    check(item(/JavaScript errors/)?.passed === false, 'syntax error caught by "runs without JavaScript errors"');
    check(item(/not blank/)?.passed === false, 'nothing is drawn');
    check(out.score! <= 0.3, 'capped at 30 or less');
  } else if (e.expect === 'frozen') {
    check(gj.playtest?.hung !== null, 'the endless loop is detected as a frozen page');
    check(item(/still running/)?.passed === false, '"still running after 15 s" fails');
    check(out.score! <= 0.3 && Boolean(gj.cap), 'capped at 30 with a plain reason');
  } else if (e.expect === 'blank') {
    check(item(/not blank/)?.passed === false, 'the blank canvas is caught');
    check(item(/reacts/)?.passed === false, 'input changes nothing visible');
    check(out.score! <= 0.3, 'capped at 30 or less');
  } else if (e.expect === 'truncated') {
    check(gj.truncated && /Ran out of output space/.test(out.summary), 'the cut-off reply is reported as "ran out of output space"');
    check(prompts.length === 0 && Boolean(gj.judgesSkipped), 'judges are not asked for an unfinished file');
    check(out.score! <= 0.25 && out.passed === false, 'scores at most the automatic 25%');
  }
  if (record) {
    const frames: Record<string, string> = {};
    for (const [name, v] of Object.entries(saved)) if (v.kind === 'png' && name.startsWith('playtest-')) frames[name] = await toJpeg(v.content as Buffer, name === 'playtest-motion.png' ? 960 : 640);
    recorded[`creative.game-jam|${e.model}|${c.id}`] = {
      score: out.score,
      passed: out.passed,
      summary: out.summary,
      detail: d,
      html: saved['artifact.html'] ? String(saved['artifact.html'].content) : undefined,
      frames,
    };
  }
}

if (record) {
  const file = join(ROOT, 'ui', 'src', 'mock', 'gameJamRecorded.json');
  writeFileSync(file, JSON.stringify(recorded));
  console.log(`\nwrote ${file} (${(JSON.stringify(recorded).length / 1e6).toFixed(2)} MB)`);
}
await closeBrowser();
console.log(failures ? `\n${failures} check(s) FAILED` : '\nAll Game Jam end-to-end checks passed.');
process.exit(failures ? 1 : 0);
