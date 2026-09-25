// Records mock-mode fixtures for the long-context and drawing visuals by running the REAL programs
// (needle-haystack, chain-of-whispers, draw-it-blind) against scripted demo "models" with different
// strengths and weaknesses. The output is exactly what a real run stores (score, summary, detail,
// replay, SVG artifacts), so mock mode (?mock=1) shows every new visual with honest-looking data.
//
//   node verification/visual-pass/record-mock.mts
//
// Writes ui/src/mock/visualPassRecorded.json. No API keys, no network.
import { readFileSync, writeFileSync } from 'node:fs';
import { browserUnavailableReason, closeBrowser, probeHtml } from '../../src/scoring/browser.ts';
import { demoGame } from './demo-games.mts';
import { demoSvg } from './demo-svgs.mts';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRng, hashString } from '../../src/core/rng.ts';
import type { ChatMessage, ModelHandle, ModelReply, ProgramContext, ProgramDefinition, Rng } from '../../src/core/types.ts';
import { program as needle, buildHaystack, readConfig as readNh } from '../../src/programs/needle-haystack.ts';
import type { PlantedNeedle } from '../../src/programs/needle-haystack.ts';
import { program as whispers, buildStory, readConfig as readCw } from '../../src/programs/chain-of-whispers.ts';
import { factPresent, splitSentences } from '../../src/programs/lib/chain-of-whispers-story.ts';
import { program as draw, buildScene, readConfig as readDib } from '../../src/programs/draw-it-blind.ts';
import { CANVAS, paletteHex } from '../../src/programs/lib/draw-it-blind-scene.ts';
import type { PaletteName, Scene, SceneShape } from '../../src/programs/lib/draw-it-blind-scene.ts';
import { countWords } from '../../src/programs/lib/needle-haystack-normalize.ts';

const here = dirname(fileURLToPath(import.meta.url));
const OUT = join(here, '..', '..', 'ui', 'src', 'mock', 'visualPassRecorded.json');

type Reply = (userText: string, index: number) => string;

function fakeModel(reply: Reply): ModelHandle {
  let index = 0;
  const answer = (messages: ChatMessage[]): ModelReply => {
    const last = messages[messages.length - 1];
    const text = reply(last?.content ?? '', index++);
    return { text, stopReason: 'end', totalMs: 1, ttftMs: 1, outputTokens: Math.ceil(text.length / 4) };
  };
  return {
    complete: async (req) => answer(req.messages),
    chat: () => {
      throw new Error('not used');
    },
  };
}

async function run(p: ProgramDefinition, seed: number, config: Record<string, unknown>, reply: Reply) {
  const artifacts: Record<string, string> = {};
  const ctx: ProgramContext = {
    seed,
    rng: createRng(seed),
    config: { ...(p.defaults ?? {}), ...config },
    model: fakeModel(reply),
    maxOutputTokens: 8000,
    signal: new AbortController().signal,
    artifact(name, kind, content) {
      if (kind === 'svg') artifacts[name] = content;
    },
  };
  const r = await p.run(ctx);
  return { score: r.score, passed: r.passed ?? null, summary: r.summary, detail: r.detail, replay: r.replay, artifacts };
}

// ───────────────────────────── Needle in a Haystack ─────────────────────────────

type NeedlePersona = (n: PlantedNeedle, rng: Rng) => 'right' | 'decoy' | 'unknown' | 'wrong';
const NEEDLE: Record<string, NeedlePersona> = {
  // Reads to the end, but takes one look-alike on the deepest chain.
  'meridian-atlas-4-ultra': (n) => (n.kind === 'three-hop' || n.kind === 'multi-hop') && n.depth > 85 ? 'decoy' : 'right',
  'kestrel-kite-reasoner': () => 'right',
  // Strong early, fades in the back half.
  'helios-nova-3-pro': (n, rng) => (n.depth < 55 ? 'right' : rng.next() < 0.35 ? 'right' : rng.next() < 0.5 ? 'decoy' : 'unknown'),
  // Skims: only the first third, then guesses.
  'helios-quill-flash': (n, rng) => (n.depth < 30 ? 'right' : rng.pick(['decoy', 'unknown', 'wrong', 'decoy'] as const)),
  'obsidian-sable-large': (n, rng) => (n.depth < 70 && rng.next() < 0.8 ? 'right' : rng.pick(['decoy', 'unknown'] as const)),
};

function needleAnswer(n: PlantedNeedle, how: ReturnType<NeedlePersona>): string {
  if (how === 'right') return n.expected;
  if (how === 'unknown') return 'unknown';
  if (how === 'decoy') {
    if (n.reject.length) return n.reject[0]!.replace(/\b\w/g, (c) => c.toUpperCase());
    if (n.rejectNumbers.length) return String(n.rejectNumbers[0]);
  }
  return n.numeric !== null ? String(n.numeric + 7) : 'Harrowgate';
}

async function needleRun(seed: number, config: Record<string, unknown>, who: string) {
  if (who === 'random-baseline') return run(needle, seed, config, (u) => baseline(u, who));
  const h = buildHaystack(createRng(seed).fork('needle-haystack'), readNh({ ...needle.defaults, ...config }));
  const rng = createRng(hashString(`${who}|${seed}`));
  const lines = h.needles.map((n) => `A${n.n}: ${needleAnswer(n, NEEDLE[who]!(n, rng))}`);
  return run(needle, seed, config, () => lines.join('\n'));
}

// ───────────────────────────── Chain of Whispers ─────────────────────────────

const FILLER = [
  'The wind kept up all night and nobody in the town slept much.',
  'People would tell the story for years, each time a little differently.',
  'It was the kind of night that everyone later claimed to remember.',
  'Lanterns bobbed along the harbour wall as neighbours came to watch.',
  'By morning the whole town was talking about what had happened.',
];
const isFiller = (s: string) => FILLER.includes(s.trim());

/** Drops and mutations per summary round (1-based round of the summary step). */
const WHISPER: Record<string, { drop: (round: number) => number; mutate: (round: number) => number }> = {
  'meridian-atlas-4-ultra': { drop: (r) => (r === 3 ? 1 : 0), mutate: (r) => (r === 1 ? 1 : 0) },
  'kestrel-kite-reasoner': { drop: () => 0, mutate: (r) => (r === 3 ? 1 : 0) },
  'helios-nova-3-pro': { drop: (r) => (r >= 3 ? 1 : 0), mutate: (r) => (r === 1 ? 1 : 0) },
  'helios-quill-flash': { drop: () => 2, mutate: () => 1 },
  'obsidian-sable-large': { drop: () => 1, mutate: (r) => (r === 5 ? 1 : 0) },
};

/** Changes a number (or, failing that, swaps a name for a look-alike) so the fact drifts. */
function mutate(s: string): string {
  if (/\d/.test(s)) return s.replace(/\d+/, (d) => String(Number(d) + (Number(d) > 20 ? 11 : 3)));
  return s.replace(/\b([A-Z][a-z]{3,})\b(?![^]*\b[A-Z][a-z]{3,}\b)/, 'Harrow');
}

async function whispersRun(seed: number, config: Record<string, unknown>, who: string) {
  if (who === 'random-baseline') return run(whispers, seed, config, (u) => baseline(u, who));
  const cfg = readCw({ ...whispers.defaults, ...config });
  const story = buildStory({ rng: createRng(seed) }, cfg);
  const plan = WHISPER[who]!;
  const rng = createRng(hashString(`${who}|whispers|${seed}`));
  const inner = (u: string) => {
    const m = u.match(/"""\n([\s\S]*)\n"""/);
    return m ? m[1]! : '';
  };
  return run(whispers, seed, config, (userText, index) => {
    const text = inner(userText);
    if (userText.startsWith('Summarise')) {
      const round = index + 1;
      // First summary: the facts as plain sentences; later: the fact sentences of the previous story.
      let sentences = index === 0 ? story.facts.filter((f) => factPresent(text, f)).map((f) => `${f.canonical}.`) : splitSentences(text).filter((s) => !isFiller(s));
      for (let k = plan.drop(round); k > 0 && sentences.length; k--) sentences.splice(rng.int(0, sentences.length - 1), 1);
      for (let k = plan.mutate(round); k > 0 && sentences.length; k--) {
        const i = rng.int(0, sentences.length - 1);
        sentences[i] = mutate(sentences[i]!);
      }
      return sentences.join(' ');
    }
    let out = splitSentences(text).join(' ');
    let f = 0;
    while (countWords(out) < 330) out += ` ${FILLER[f++ % FILLER.length]}`;
    return out;
  });
}

// ───────────────────────────── Draw It Blind ─────────────────────────────

interface DrawPersona {
  /** Grid the drawer can place shapes on (3 = thirds, 5 = fifths). */
  grid: number;
  /** Shapes it describes (the rest are forgotten). */
  keep: number;
  mergeColours?: boolean;
  /** Writes some digits ("about 80 pixels") that get deleted. */
  digits?: boolean;
  /** Pads the description past the word limit. */
  wordy?: boolean;
  /** Short phrases (fits tight word limits). */
  terse?: boolean;
  /** Draws one shape type wrong (squares become circles). */
  typeSlip?: boolean;
}
const DRAW: Record<string, DrawPersona> = {
  'meridian-atlas-4-ultra': { grid: 5, keep: 12, terse: true },
  'kestrel-kite-reasoner': { grid: 5, keep: 12, mergeColours: true, terse: true },
  'helios-nova-3-pro': { grid: 3, keep: 12, typeSlip: true },
  'helios-quill-flash': { grid: 3, keep: 4, mergeColours: true, digits: true },
  'obsidian-sable-large': { grid: 3, keep: 12, wordy: true },
};
const MERGE: Partial<Record<PaletteName, PaletteName>> = { navy: 'blue', teal: 'green', brown: 'orange', gray: 'black' };

function whereWords(cx: number, cy: number): string {
  const col = cx < CANVAS / 3 ? 'left' : cx > (2 * CANVAS) / 3 ? 'right' : 'centre';
  const row = cy < CANVAS / 3 ? 'top' : cy > (2 * CANVAS) / 3 ? 'bottom' : 'middle';
  if (row === 'middle' && col === 'centre') return 'in the very centre';
  if (row === 'middle') return `halfway down on the ${col}`;
  if (col === 'centre') return `at the ${row} in the middle`;
  return `in the ${row}-${col} area`;
}
function sizeWord(s: SceneShape): string {
  const m = Math.max(s.w, s.h) / CANVAS;
  return m > 0.3 ? 'large' : m > 0.18 ? 'medium' : 'small';
}

function describe(scene: Scene, p: DrawPersona, limit: number): string {
  const shapes = scene.shapes.slice(0, p.keep);
  const parts = shapes.map((s, i) => {
    const colour = (p.mergeColours && MERGE[s.color]) || s.color;
    const dir = s.direction ? ` pointing ${s.direction}` : '';
    const px = p.digits && i === 0 ? ` about ${Math.round(s.w)} pixels wide` : '';
    if (p.terse) {
      const t = `${sizeWord(s)} ${colour} ${s.type}${dir}, ${whereWords(s.cx, s.cy).replace(/^(in|at) (the )?(very )?/, '').replace(/ area$/, '')}.`;
      return t.charAt(0).toUpperCase() + t.slice(1);
    }
    return `A ${sizeWord(s)} ${colour} ${s.type}${dir} sits ${whereWords(s.cx, s.cy)}${px}.`;
  });
  let text = `White square canvas. ${parts.join(' ')}`;
  if (p.wordy) while (countWords(text) <= limit + 12) text += ' Every shape has a clean flat fill and there are no outlines anywhere.';
  return text;
}

function redraw(s: SceneShape, p: DrawPersona, i: number): string {
  const cell = CANVAS / p.grid;
  const snap = (v: number) => (Math.min(p.grid - 1, Math.floor(v / cell)) + 0.5) * cell;
  const size = (v: number) => Math.max(24, Math.round(v / 40) * 40);
  const cx = snap(s.cx);
  const cy = snap(s.cy);
  const w = size(s.w);
  const h = size(s.h);
  const fill = paletteHex((p.mergeColours && MERGE[s.color]) || s.color);
  const type = p.typeSlip && i === 1 && s.type !== 'circle' ? 'circle' : s.type;
  switch (type) {
    case 'circle':
      return `<circle cx="${cx}" cy="${cy}" r="${w / 2}" fill="${fill}"/>`;
    case 'square':
    case 'rectangle':
      return `<rect x="${cx - w / 2}" y="${cy - h / 2}" width="${w}" height="${h}" fill="${fill}"/>`;
    case 'triangle': {
      const a = (((s.angle ?? -90) + 90) * Math.PI) / 180;
      const pts: Array<[number, number]> = [
        [0, -h / 2],
        [w / 2, h / 2],
        [-w / 2, h / 2],
      ];
      return `<polygon points="${pts.map(([x, y]) => `${(cx + x * Math.cos(a) - y * Math.sin(a)).toFixed(1)},${(cy + x * Math.sin(a) + y * Math.cos(a)).toFixed(1)}`).join(' ')}" fill="${fill}"/>`;
    }
    case 'star': {
      const r = Math.max(w, h) / 2;
      const pts = Array.from({ length: 10 }, (_, k) => {
        const rad = k % 2 ? r * 0.45 : r;
        const ang = -Math.PI / 2 + (k * Math.PI) / 5;
        return `${(cx + rad * Math.cos(ang)).toFixed(1)},${(cy + rad * Math.sin(ang)).toFixed(1)}`;
      });
      return `<polygon points="${pts.join(' ')}" fill="${fill}"/>`;
    }
  }
}

async function drawRun(seed: number, config: Record<string, unknown>, who: string) {
  if (who === 'random-baseline') return run(draw, seed, config, (u) => baseline(u, who));
  const cfg = readDib({ ...draw.defaults, ...config });
  const scene = buildScene({ rng: createRng(seed) }, cfg);
  const p = DRAW[who]!;
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="400" height="400" viewBox="0 0 400 400"><rect width="400" height="400" fill="#ffffff"/>${scene.shapes
    .slice(0, p.keep)
    .map((s, i) => redraw(s, p, i))
    .join('')}</svg>`;
  return run(draw, seed, config, (u) => (u.includes('PICTURE') ? describe(scene, p, cfg.descriptionWords) : `\`\`\`svg\n${svg}\n\`\`\``));
}

// ───────────────────────────── Random baseline (mirrors src/providers/mock.ts) ─────────────────────────────

function baseline(userText: string, who: string): string {
  const rng = createRng(hashString(`${who}\n${userText}`));
  if (/svg/i.test(userText) && /draw|illustrat|svg/i.test(userText)) {
    return `\`\`\`svg\n<svg xmlns="http://www.w3.org/2000/svg" width="400" height="400"><circle cx="${rng.int(20, 300)}" cy="${rng.int(20, 300)}" r="40" fill="gray"/></svg>\n\`\`\``;
  }
  if (/\bA\d+\s*:/.test(userText)) {
    const n = Math.max(1, (userText.match(/\bQ\d+\b/g) ?? []).length);
    return Array.from({ length: n }, (_, i) => `A${i + 1}: unknown`).join('\n');
  }
  const words = ['the', 'model', 'result', 'answer', 'benchmark', 'quickly', 'random', 'value', 'system', 'data', 'story', 'river', 'light'];
  return Array.from({ length: 40 + rng.int(0, 40) }, () => rng.pick(words)).join(' ') + '.';
}

// ───────────────────────────── Record ─────────────────────────────

const MODELS = ['meridian-atlas-4-ultra', 'kestrel-kite-reasoner', 'helios-nova-3-pro', 'helios-quill-flash', 'random-baseline'];
const NH_HARD = { targetWords: 76000, mix: { single: 3, twoHop: 2, threeHop: 3, sum5: 2, superseded: 2 } };
const CW_HARD = { cycles: 5, summaryWords: 60, storyWords: 450, storyMaxWords: 500, storyMinWords: 300, facts: 20, story: 'auto' };
const DIB_HARD = { minShapes: 9, maxShapes: 12, descriptionWords: 90, palette: 'extended', overlap: true, rotation: true, positionFalloff: 60, weights: { type: 0.2, color: 0.2, position: 0.4, size: 0.2 }, hyphenSplit: true };

const out: Record<string, unknown> = {};
for (const who of MODELS) {
  out[`long-context.needle-haystack|${who}|seed-101`] = await needleRun(101, { targetWords: 44000, needles: 10 }, who);
  out[`long-context.needle-haystack-hard|${who}|seed-101`] = await needleRun(101, NH_HARD, who);
  out[`long-context.chain-of-whispers|${who}|seed-404`] = await whispersRun(404, {}, who);
  out[`long-context.chain-of-whispers-hard|${who}|seed-404`] = await whispersRun(404, CW_HARD, who);
  out[`visual.draw-it-blind-hard|${who}|seed-101`] = await drawRun(101, DIB_HARD, who);
}
// The demo's own "visual.draw-it-blind" test uses seeds 5 and 8 (the core run shows it too).
for (const who of [...MODELS, 'obsidian-sable-large']) for (const seed of [5, 8]) out[`visual.draw-it-blind|${who}|seed-${seed}`] = await drawRun(seed, {}, who);

// ───────────────────────────── Artifact tests (SVG illustration, one-shot games) ─────────────────────────────
// The checks mirror src/scoring/index.ts (the same labels); the games are probed in real headless Chromium.

const svgTest = JSON.parse(readFileSync(join(here, '..', '..', 'tests', 'visual', 'svg-illustration.json'), 'utf8')) as { cases: Array<{ id: string; scorer: { checks: Array<{ check: string; text?: string; bytes?: number }> } }> };
const gameTest = JSON.parse(readFileSync(join(here, '..', '..', 'tests', 'creative', 'one-shot-games.json'), 'utf8')) as { cases: Array<{ id: string }>; scorer: { checks: Array<{ check: string; bytes?: number }> } };
const JUDGES = ['meridian-atlas-4-ultra', 'helios-nova-3-pro', 'kestrel-kite-reasoner'];
const SVG_FLAW: Record<string, Record<string, 'none' | 'minor' | 'major'>> = {
  'meridian-atlas-4-ultra': { v01: 'none', v02: 'none', v03: 'none', v04: 'none' },
  'kestrel-kite-reasoner': { v01: 'none', v02: 'minor', v03: 'none', v04: 'none' },
  'helios-nova-3-pro': { v01: 'minor', v02: 'major', v03: 'minor', v04: 'minor' },
  'helios-quill-flash': { v01: 'major', v02: 'major', v03: 'major', v04: 'major' },
};
const JUDGE_SCORE = { none: 0.9, minor: 0.7, major: 0.35 };
const GAME_FLAW: Record<string, Record<string, 'none' | 'crash-on-key' | 'no-keys'>> = {
  'meridian-atlas-4-ultra': { g01: 'none', g02: 'none', g03: 'none' },
  'kestrel-kite-reasoner': { g01: 'none', g02: 'none', g03: 'none' },
  'helios-nova-3-pro': { g01: 'none', g02: 'no-keys', g03: 'none' },
  'helios-quill-flash': { g01: 'crash-on-key', g02: 'no-keys', g03: 'crash-on-key' },
};
const GAME_JUDGE = { none: 0.8, 'no-keys': 0.4, 'crash-on-key': 0.2 };
const r3 = (x: number) => Math.round(x * 1000) / 1000;

function judged(base: number, who: string, note: string) {
  return JUDGES.filter((j) => j.split('-')[0] !== who.split('-')[0]).map((j, i) => ({ contestantId: j, score: Math.max(0, Math.min(1, Math.round((base + (i ? -0.1 : 0)) * 10) / 10)), rationale: note }));
}

for (const who of MODELS) {
  for (const c of svgTest.cases) {
    const key = `visual.svg-illustration|${who}|${c.id}`;
    if (who === 'random-baseline') {
      out[key] = { score: 0, passed: false, summary: 'No SVG artifact found', detail: { formatOk: false }, artifacts: {} };
      continue;
    }
    const flaw = SVG_FLAW[who]![c.id]!;
    const svg = demoSvg(c.id, flaw);
    const bytes = Buffer.byteLength(svg);
    const items = c.scorer.checks.map((k) =>
      k.check === 'parses' ? { label: 'SVG renders', passed: true } : k.check === 'max_bytes' ? { label: `≤ ${Math.round(k.bytes! / 1000)} kB`, passed: bytes <= k.bytes!, detail: `${(bytes / 1000).toFixed(1)} kB` } : { label: `contains "${k.text}"`, passed: svg.toLowerCase().includes(k.text!.toLowerCase()) },
    );
    const checkScore = items.filter((i) => i.passed).length / items.length;
    const judge = judged(JUDGE_SCORE[flaw], who, flaw === 'none' ? 'Every numbered requirement is met exactly; clean and recognisable.' : flaw === 'minor' ? 'Recognisable and mostly exact, but one numbered requirement is off.' : 'Several numbered requirements are wrong (counts or positions).');
    const judgeScore = judge.reduce((a, j) => a + j.score, 0) / judge.length;
    const score = r3(0.4 * checkScore + 0.6 * judgeScore);
    out[key] = {
      score,
      passed: score >= 0.7,
      summary: `${items.filter((i) => i.passed).length}/${items.length} checks · judges ${(judgeScore * 10).toFixed(1)}/10`,
      detail: { items, judge, bytes, checkScore: r3(checkScore), judgeScore: r3(judgeScore) },
      artifacts: { 'artifact.svg': svg },
    };
  }
  for (const c of gameTest.cases) {
    const key = `creative.one-shot-games|${who}|${c.id}`;
    if (who === 'random-baseline') {
      out[key] = { score: 0, passed: false, summary: 'No HTML artifact found', detail: { formatOk: false }, artifacts: {} };
      continue;
    }
    const flaw = GAME_FLAW[who]![c.id]!;
    const html = demoGame(c.id, flaw);
    const probe = await probeHtml(html);
    if (!probe) throw new Error(`Chromium is needed to record the game fixtures: ${browserUnavailableReason()}`);
    const bytes = Buffer.byteLength(html);
    const items = gameTest.scorer.checks.map((k) => {
      switch (k.check) {
        case 'parses':
          return { label: 'HTML document parses', passed: true };
        case 'no_external_requests':
          return { label: 'no external requests', passed: probe.externalRequests.length === 0 };
        case 'max_bytes':
          return { label: `≤ ${Math.round(k.bytes! / 1000)} kB`, passed: bytes <= k.bytes!, detail: `${(bytes / 1000).toFixed(1)} kB` };
        case 'runs_without_errors':
          return { label: 'runs without JavaScript errors', passed: probe.pageErrors.length === 0, detail: probe.pageErrors[0] };
        case 'has_canvas_or_svg':
          return { label: 'renders a canvas/SVG', passed: probe.hasCanvasOrSvg };
        default:
          return { label: 'reacts to keyboard/mouse input', passed: probe.respondsToInput };
      }
    });
    const checkScore = items.filter((i) => i.passed).length / items.length;
    const judge = judged(GAME_JUDGE[flaw], who, flaw === 'none' ? 'Full loop works (start, play, game over, restart); responsive controls.' : flaw === 'no-keys' ? 'Draws nicely but ignores the keyboard, so it cannot be played as specified.' : 'Throws an error as soon as a key is pressed; not playable.');
    const judgeScore = judge.reduce((a, j) => a + j.score, 0) / judge.length;
    const score = r3(0.4 * checkScore + 0.6 * judgeScore);
    out[key] = {
      score,
      passed: score >= 0.7,
      summary: `${items.filter((i) => i.passed).length}/${items.length} checks · judges ${(judgeScore * 10).toFixed(1)}/10`,
      detail: { items, judge, bytes, checkScore: r3(checkScore), judgeScore: r3(judgeScore), consoleErrors: probe.consoleErrors.slice(0, 5) },
      artifacts: { 'artifact.html': html },
      screenshot: `data:image/jpeg;base64,${(await jpeg(probe.screenshot)).toString('base64')}`,
    };
  }
}
await closeBrowser();

/** Re-encodes a PNG screenshot as a small JPEG (keeps the mock bundle light). */
async function jpeg(png: Buffer): Promise<Buffer> {
  const { getBrowser } = await import('../../src/scoring/browser.ts');
  const b = await getBrowser();
  const ctx = await b!.newContext({ viewport: { width: 960, height: 640 } });
  const page = await ctx.newPage();
  await page.setContent(`<body style="margin:0"><img src="data:image/png;base64,${png.toString('base64')}" style="display:block"></body>`);
  const out = await page.screenshot({ type: 'jpeg', quality: 62 });
  await ctx.close();
  return out;
}

const json = JSON.stringify(out);
writeFileSync(OUT, json + '\n');
console.log(`Recorded ${Object.keys(out).length} results (${Math.round(json.length / 1024)} kB) into ${OUT}`);
for (const [k, v] of Object.entries(out)) console.log(`  ${k}: ${(v as { summary: string }).summary}`);
