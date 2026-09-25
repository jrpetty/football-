/**
 * Needle in a Haystack — a seeded ~45,000-word chronicle of an invented city
 * with ten planted needles (single facts, 2-hop chains, a 3-way sum and a
 * later-corrected value) plus near-miss distractors. One call; answers are
 * graded deterministically and reported by depth for a heatmap. The hard tier
 * (config.mix) adds 3-hop chains, five-place sums with adversarial decoys and
 * two corrected values in a ~120k-token document.
 */
import type { ProgramContext, ProgramDefinition, ProgramResult, ReplayFrame, Rng } from '../core/types.ts';
import { extractTagged } from '../core/extract.ts';
import { buildCorpusWorld, generateChapters, NameForge, sentenceWords } from './lib/needle-haystack-corpus.ts';
import type { Chapter } from './lib/needle-haystack-corpus.ts';
import { buildNeedleMix, buildNeedles, NeedleEnv } from './lib/needle-haystack-needles.ts';
import type { NeedleKind, NeedleMix, NeedleSpec } from './lib/needle-haystack-needles.ts';
import { containsPhrase, extractNumbers, normalizeText } from './lib/needle-haystack-normalize.ts';

export interface NhConfig {
  targetWords: number;
  needles: number;
  /** Explicit needle mix (hard tier). Null = the standard 6 single / 2 two-hop / 1 sum / 1 correction. */
  mix: NeedleMix | null;
}

const MIX_KEYS: Array<keyof NeedleMix> = ['single', 'twoHop', 'threeHop', 'sum3', 'sum5', 'superseded'];
const MIX_MAX: NeedleMix = { single: 12, twoHop: 4, threeHop: 3, sum3: 2, sum5: 2, superseded: 2 };

export function readConfig(raw: Record<string, unknown>): NhConfig {
  const words = Number(raw.targetWords);
  const needles = Number(raw.needles);
  let mix: NeedleMix | null = null;
  if (raw.mix && typeof raw.mix === 'object') {
    const src = raw.mix as Record<string, unknown>;
    mix = { single: 0, twoHop: 0, threeHop: 0, sum3: 0, sum5: 0, superseded: 0 };
    for (const key of MIX_KEYS) {
      const v = Number(src[key]);
      mix[key] = Number.isInteger(v) ? Math.max(0, Math.min(MIX_MAX[key], v)) : 0;
    }
    if (MIX_KEYS.every((key) => mix![key] === 0)) mix = null;
  }
  return {
    targetWords: Number.isFinite(words) ? Math.round(Math.min(150_000, Math.max(3_000, words))) : 44_000,
    needles: mix ? MIX_KEYS.reduce((a, key) => a + mix![key], 0) : Number.isInteger(needles) ? Math.min(10, Math.max(5, needles)) : 10,
    mix,
  };
}

export interface PlantedNeedle extends NeedleSpec {
  /** Question number (1-based), also the answer tag A<n>. */
  n: number;
  /** Depth of every planted part, % of the document (0 = start). */
  depths: number[];
  /** Depth at which the needle becomes answerable (its deepest part). */
  depth: number;
  distractorDepths: number[];
}

/** A planted sentence with its neighbours, for the replay's "zoom into the passage" view (display only). */
export interface Passage {
  before?: string;
  text: string;
  after?: string;
}

export interface Haystack {
  city: string;
  text: string;
  words: number;
  chapters: number;
  needles: PlantedNeedle[];
  /** Planted sentence (tag n<i>p<j> / n<i>d<j>) → the sentence with its neighbours. */
  passages?: Map<string, Passage>;
}

interface Insertion {
  tag: string;
  text: string;
  depth: number;
}

const round1 = (x: number) => Math.round(x * 10) / 10;

/** Plans target depths (0..1). Needle anchors are stratified; multi-part needles take the deeper slots. */
export function planDepths(rng: Rng, needles: NeedleSpec[]): { parts: number[][]; distractors: number[][] } {
  const k = needles.length;
  const slots = Array.from({ length: k }, (_, i) => (i + 0.5) / k);
  const deep = rng.shuffle(slots.filter((s) => s >= 0.3));
  const assigned: number[] = new Array(k);
  const multiIdx = needles.map((n, i) => (n.kind === 'single' ? -1 : i)).filter((i) => i >= 0);
  for (const i of multiIdx) {
    // More multi-part needles than deep slots (hard tier): fall back to the deepest free slot.
    const slot = deep.shift() ?? Math.max(...slots);
    assigned[i] = slot;
    slots.splice(slots.indexOf(slot), 1);
  }
  const rest = rng.shuffle(slots);
  needles.forEach((n, i) => {
    if (n.kind === 'single') assigned[i] = rest.shift()!;
  });
  const jitter = 0.4 / k;
  const between = (a: number, b: number) => a + rng.next() * Math.max(0, b - a);

  let multiSeen = 0;
  const parts = needles.map((n, i) => {
    const anchor = Math.min(0.99, Math.max(0.01, assigned[i]! + (rng.next() * 2 - 1) * jitter));
    switch (n.kind) {
      case 'single':
        return [anchor];
      case 'multi-hop': {
        const early = between(0.02, anchor - 0.2);
        // Alternate which half of the chain comes first.
        return multiSeen++ % 2 === 0 ? [early, anchor] : [anchor, early];
      }
      case 'three-hop': {
        // Two early hops, the deepest hop at the anchor; which hop is deepest is random.
        const early = [between(0.02, anchor - 0.2), between(0.02, anchor - 0.2)];
        const deepest = rng.int(0, 2);
        return [0, 1, 2].map((j) => (j === deepest ? anchor : early.pop()!));
      }
      case 'aggregate': {
        if (n.parts.length === 3) return [between(0.02, anchor / 2 - 0.05), between(anchor / 2, anchor - 0.08), anchor];
        // k-part sums: spread the earlier parts over [0.02, anchor − 0.08] in equal bands.
        const k = n.parts.length;
        const span = anchor - 0.1;
        return [...Array.from({ length: k - 1 }, (_, j) => between(0.02 + (span * j) / (k - 1), 0.02 + (span * (j + 1)) / (k - 1))), anchor];
      }
      case 'superseded':
        return [between(0.02, anchor - 0.2), anchor];
    }
  });
  const distractors = needles.map((n, i) =>
    n.distractors.map(() => {
      for (let t = 0; t < 30; t++) {
        const d = between(0.02, 0.98);
        if (parts[i]!.every((p) => Math.abs(p - d) >= 0.08)) return d;
      }
      return between(0.02, 0.98);
    }),
  );
  return { parts, distractors };
}

/** Inserts sentences into paragraphs at (approximately) the given depths. */
export function plant(chapters: Chapter[], insertions: Insertion[]): void {
  const paras = chapters.flatMap((c) => c.paragraphs);
  const starts: number[] = [];
  let total = 0;
  for (const p of paras) {
    starts.push(total);
    total += p.reduce((a, s) => a + sentenceWords(s.text), 0);
  }
  for (const ins of [...insertions].sort((a, b) => a.depth - b.depth)) {
    const target = ins.depth * total;
    let pi = 0;
    while (pi + 1 < paras.length && starts[pi + 1]! <= target) pi++;
    const para = paras[pi]!;
    let pos = starts[pi]!;
    let k = 0;
    while (k < para.length && pos + sentenceWords(para[k]!.text) / 2 < target) {
      pos += sentenceWords(para[k]!.text);
      k++;
    }
    para.splice(k, 0, { text: ins.text, tag: ins.tag });
  }
}

/** Renders the document and measures the depth (% of words before it) of every tagged sentence. */
export function render(city: string, firstYear: number, chapters: Chapter[]): { text: string; words: number; depthOf: Map<string, number>; passages: Map<string, Passage> } {
  const lastYear = chapters.length ? Number(chapters[chapters.length - 1]!.title.match(/\((\d+)\)$/)?.[1] ?? firstYear) : firstYear;
  const blocks: string[] = [
    `THE CHRONICLE OF ${city.toUpperCase()}`,
    `Being an account of the city of ${city} from the year ${firstYear} to the year ${lastYear}, set down by its chroniclers.`,
  ];
  const offsets = new Map<string, number>();
  const passages = new Map<string, Passage>();
  let words = blocks.reduce((a, b) => a + sentenceWords(b), 0);
  for (const ch of chapters) {
    blocks.push(ch.title);
    words += sentenceWords(ch.title);
    for (const para of ch.paragraphs) {
      para.forEach((s, k) => {
        if (s.tag) {
          offsets.set(s.tag, words);
          passages.set(s.tag, { before: para[k - 1]?.text, text: s.text, after: para[k + 1]?.text });
        }
        words += sentenceWords(s.text);
      });
      blocks.push(para.map((s) => s.text).join(' '));
    }
  }
  const depthOf = new Map<string, number>();
  for (const [tag, off] of offsets) depthOf.set(tag, round1((off / words) * 100));
  return { text: blocks.join('\n\n'), words, depthOf, passages };
}

export function buildHaystack(rng: Rng, cfg: NhConfig): Haystack {
  const forge = new NameForge(rng.fork('names'));
  const world = buildCorpusWorld(rng.fork('world'), forge, []);
  const env = new NeedleEnv(
    rng.fork('needles'),
    forge,
    world.city,
    world.startYear,
    world.streets.map((s) => s.split(' ')[0]!),
    world.ships,
  );
  const specs = rng.fork('order').shuffle(cfg.mix ? buildNeedleMix(env, cfg.mix) : buildNeedles(env, cfg.needles));
  world.villages = world.villages.filter((v) => !env.usedVillages.includes(v));

  const chapters = generateChapters(rng.fork('prose'), world, cfg.targetWords);
  const plan = planDepths(rng.fork('depths'), specs);
  const insertions: Insertion[] = [];
  specs.forEach((n, i) => {
    n.parts.forEach((text, j) => insertions.push({ tag: `n${i}p${j}`, text, depth: plan.parts[i]![j]! }));
    n.distractors.forEach((text, j) => insertions.push({ tag: `n${i}d${j}`, text, depth: plan.distractors[i]![j]! }));
  });
  plant(chapters, insertions);
  const { text, words, depthOf, passages } = render(world.city, world.startYear, chapters);

  const needles: PlantedNeedle[] = specs.map((n, i) => {
    const depths = n.parts.map((_, j) => depthOf.get(`n${i}p${j}`)!);
    return {
      ...n,
      n: i + 1,
      depths,
      depth: Math.max(...depths),
      distractorDepths: n.distractors.map((_, j) => depthOf.get(`n${i}d${j}`)!),
    };
  });
  return { city: world.city, text, words, chapters: chapters.length, needles, passages };
}

export function buildPrompt(h: Haystack): string {
  const k = h.needles.length;
  const questions = h.needles.map((n) => `Q${n.n}. ${n.question}`).join('\n');
  const format = h.needles.map((n) => `A${n.n}: <answer>`).join('\n');
  return [
    `Below is THE CHRONICLE OF ${h.city.toUpperCase()}, a long history of an invented city (about ${Math.round(h.words / 1000)},000 words). Read it carefully: afterwards you must answer ${k} questions about details buried in it. Everything you need is in the text; none of it can be answered from general knowledge.`,
    '',
    '<chronicle>',
    h.text,
    '</chronicle>',
    '',
    `Answer these ${k} questions using only the chronicle.`,
    h.needles.some((n) => n.kind === 'three-hop')
      ? '- Some answers chain together facts found in two or three different places in the text, and some require adding up figures mentioned in several places. Read carefully: similar names, similar places and look-alike figures are everywhere.'
      : '- Some answers combine facts from two different places in the text, and one requires adding up figures mentioned in several places.',
    '- If the chronicle later corrects an earlier statement, the correction is what counts.',
    '- Beware of similar-looking names and numbers; answer exactly what is asked.',
    '- Keep each answer short: just the name, word, street, village or number. If you cannot find it, write "unknown".',
    '- Give exactly one answer per question, with no alternatives or extra figures. An answer that offers more than one candidate (for example "X or Y") is marked wrong.',
    '',
    questions,
    '',
    `Reply with exactly ${k} lines and nothing else, one per question, in this form:`,
    format,
  ].join('\n');
}

/** Pulls "A3: ..." answers (falls back to "3. ..." / "Q3: ..." lines). */
export function parseAnswers(text: string, k: number): Array<string | null> {
  const out: Array<string | null> = [];
  for (let i = 1; i <= k; i++) {
    let a = extractTagged(text, `A${i}`);
    if (a === null) {
      const re = new RegExp(`^[\\s>*_#-]*(?:Q|A|Answer\\s*)?${i}\\s*[.):：-]\\s*(.+)$`, 'gim');
      let m: RegExpExecArray | null;
      while ((m = re.exec(text)) !== null) a = m[1]!.replace(/\*\*|__|`/g, '').trim();
    }
    out.push(a && a.trim() ? a.trim() : null);
  }
  return out;
}

export type Trap = 'distractor' | 'superseded' | null;

export interface Grade {
  correct: boolean;
  trap: Trap;
  /** The answer offered alternatives ("X or Y", "12/13", two different numbers). */
  hedged: boolean;
}

const ALTERNATIVES = /\b(?:or|either)\b|\//i;

/**
 * Deterministic grading. Answers are normalised (case, punctuation, number
 * words) and matched against aliases. No credit for hedging: an answer that
 * offers alternatives, names the near-miss distractor, or (for numbers)
 * contains more than one distinct value is wrong.
 */
export function gradeAnswer(n: NeedleSpec, raw: string | null): Grade {
  if (raw === null || /^\s*(unknown|n\/a|none|not found)\b/i.test(raw)) return { correct: false, trap: null, hedged: false };
  if (n.numeric !== null) {
    // "14 + 23 + 9 = 46" states the working; the value after the last "=" is the answer.
    const eq = raw.lastIndexOf('=');
    const segment = eq >= 0 ? raw.slice(eq + 1) : raw;
    const values = [...new Set(extractNumbers(segment))];
    const hedged = ALTERNATIVES.test(segment) || values.length > 1;
    const value = values.length === 1 ? values[0]! : undefined;
    const correct = !hedged && value === n.numeric;
    const trap: Trap = values.some((v) => n.rejectNumbers.includes(v)) ? (n.kind === 'superseded' ? 'superseded' : 'distractor') : null;
    return { correct, trap, hedged };
  }
  const norm = normalizeText(raw);
  const hedged = ALTERNATIVES.test(raw);
  const hitReject = n.reject.some((r) => containsPhrase(norm, r));
  const hitAccept = n.accept.some((a) => containsPhrase(norm, a));
  return { correct: hitAccept && !hitReject && !hedged, trap: hitReject ? 'distractor' : null, hedged };
}

const KIND_LABEL: Record<NeedleKind, string> = {
  single: 'fact',
  'multi-hop': '2-hop',
  'three-hop': '3-hop',
  aggregate: 'sum',
  superseded: 'correction',
};
const KIND_PRIORITY: NeedleKind[] = ['three-hop', 'multi-hop', 'superseded', 'aggregate', 'single'];
const BUCKETS = [0, 20, 40, 60, 80];

/** Replay-only data for the document strip and passage zoom: every planted sentence and decoy with its neighbours. */
export function visualData(h: Haystack): Record<string, unknown> {
  const at = (tag: string, depth: number | undefined) => ({ depth: depth ?? null, ...(h.passages?.get(tag) ?? { text: '' }) });
  return {
    words: h.words,
    needles: h.needles.map((n) => ({
      id: `Q${n.n}`,
      parts: n.parts.map((_, j) => at(`n${n.n - 1}p${j}`, n.depths[j])),
      decoys: n.distractors.map((_, j) => at(`n${n.n - 1}d${j}`, n.distractorDepths[j])),
    })),
  };
}

export const program: ProgramDefinition = {
  id: 'needle-haystack',
  name: 'Needle in a Haystack',
  description:
    'A seeded ~45,000-word chronicle of an invented city (≈60k tokens) with ten planted needles spread from 0% to 100% depth: single facts, two 2-hop chains whose halves sit far apart, a sum of figures scattered in three places, and a value that is later corrected — each shadowed by a near-miss distractor. One call; the model answers all questions at once.',
  scoring:
    'Each needle is worth the same: score = correct answers ÷ number of needles. Answers are normalised (case, punctuation, number words → digits) and matched against accepted aliases. An answer is wrong if it names the near-miss distractor, gives the superseded value, or hedges (offers alternatives or more than one number). Results are also broken down by needle type and by the depth at which each needle becomes answerable.',
  defaults: { targetWords: 44_000, needles: 10 },

  async run(ctx: ProgramContext): Promise<ProgramResult> {
    const cfg = readConfig(ctx.config);
    const h = buildHaystack(ctx.rng.fork('needle-haystack'), cfg);
    ctx.artifact('chronicle.txt', 'text', h.text);
    const prompt = buildPrompt(h);

    const reply = await ctx.model.complete({ messages: [{ role: 'user', content: prompt }], maxOutputTokens: ctx.maxOutputTokens, label: 'answers' });
    const text = reply.stopReason === 'refusal' ? '' : reply.text;
    const answers = parseAnswers(text, h.needles.length);

    const graded = h.needles.map((n, i) => ({ n, answer: answers[i] ?? null, ...gradeAnswer(n, answers[i] ?? null) }));
    const correct = graded.filter((g) => g.correct).length;
    const k = h.needles.length;
    const score = Math.round((correct / k) * 1000) / 1000;

    const byKind: Record<string, { correct: number; total: number }> = {};
    for (const g of graded) {
      const e = (byKind[g.n.kind] ??= { correct: 0, total: 0 });
      e.total++;
      if (g.correct) e.correct++;
    }
    const byDepth = BUCKETS.map((lo) => {
      const inBucket = graded.filter((g) => g.n.depth >= lo && (g.n.depth < lo + 20 || (lo === 80 && g.n.depth <= 100)));
      return { bucket: `${lo}–${lo + 20}%`, from: lo, to: lo + 20, correct: inBucket.filter((g) => g.correct).length, total: inBucket.length };
    });

    const misses = graded.filter((g) => !g.correct);
    misses.sort((a, b) => KIND_PRIORITY.indexOf(a.n.kind) - KIND_PRIORITY.indexOf(b.n.kind) || b.n.depth - a.n.depth);
    let summary = `${correct}/${k} needles`;
    if (text.trim() === '' || answers.every((a) => a === null)) summary += ' · no answers given';
    else if (misses.length === 0) summary += ` · perfect recall across ${Math.round(h.words / 1000)}k words`;
    else {
      const m = misses[0]!;
      summary += ` · missed the ${KIND_LABEL[m.n.kind]} at ${Math.round(m.n.depth)}% depth`;
      if (misses.length > 1) summary += ` (+${misses.length - 1} more)`;
    }

    let found = 0;
    const frames: ReplayFrame[] = [...graded]
      .sort((a, b) => a.n.depth - b.n.depth)
      .map((g, i) => {
        if (g.correct) found++;
        const trapNote = g.hedged
          ? ' (hedged between answers)'
          : g.trap === 'superseded'
            ? ' (gave the superseded value)'
            : g.trap === 'distractor'
              ? ' (fell for the distractor)'
              : '';
        return {
          step: i + 1,
          label: `${Math.round(g.n.depth)}% · ${KIND_LABEL[g.n.kind]} · Q${g.n.n}`,
          observation: g.n.question,
          action: g.answer ?? '(no answer)',
          outcome: g.correct ? `Correct (${g.n.expected})` : `Wrong — expected ${g.n.expected}${trapNote}`,
          stats: { depth: Math.round(g.n.depth), found: `${found}/${i + 1}` },
          tone: g.correct ? 'good' : 'bad',
        };
      });

    return {
      score,
      passed: score >= 0.8,
      summary,
      detail: {
        city: h.city,
        words: h.words,
        approxTokens: Math.round(prompt.length / 4),
        chapters: h.chapters,
        answered: answers.filter((a) => a !== null).length,
        correct,
        total: k,
        needles: graded.map((g) => ({
          id: `Q${g.n.n}`,
          kind: g.n.kind,
          topic: g.n.topic,
          question: g.n.question,
          expected: g.n.expected,
          answer: g.answer,
          correct: g.correct,
          trap: g.trap,
          hedged: g.hedged,
          depth: g.n.depth,
          depths: g.n.depths,
          distractorDepths: g.n.distractorDepths,
        })),
        byKind,
        byDepth,
        traps: {
          hedged: graded.filter((g) => g.hedged).length,
          distractor: graded.filter((g) => g.trap === 'distractor').length,
          superseded: graded.filter((g) => g.trap === 'superseded').length,
        },
      },
      replay: {
        title: `Needle in a Haystack — the Chronicle of ${h.city}`,
        gauges: ['depth'],
        frames,
        series: [
          {
            name: 'Accuracy by depth (%)',
            points: byDepth.filter((b) => b.total > 0).map((b) => ({ x: b.from + 10, y: Math.round((b.correct / b.total) * 100) })),
          },
        ],
        visual: { kind: 'needle-haystack', data: visualData(h) },
      },
    };
  },
};
