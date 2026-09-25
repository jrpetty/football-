/**
 * Instruction tests (Precision Formatting, Stay In Character, Extreme
 * Constraints, Adversarial System Prompt): every rule as a checklist, the
 * exact characters in the reply that broke a rule, and word / sentence
 * counters against their targets. Pass / fail comes from the real checker
 * (src/scoring/constraints.ts), so the picture always agrees with the score.
 */
import type { Constraint } from '../../core/types.ts';
import { checkConstraint, lines, sentences, words } from '../../scoring/constraints.ts';
import { finalReply, type CaseVisualInput } from './common.ts';

export interface RuleLine {
  label: string;
  passed: boolean;
  detail?: string;
  /** A plain-English version of the rule for viewers. */
  plain: string;
}

export interface Gauge {
  rule: number;
  noun: string;
  value: number;
  min?: number;
  max?: number;
  passed: boolean;
}

export interface Span {
  start: number;
  end: number;
  rule: number;
  /** 'bad' = broke the rule here; 'count' = counted towards a rule that has a limit. */
  tone: 'bad' | 'count';
}

export interface ChatTurn {
  user: string;
  reply: string | null;
  /** Short tag for the user turn ("fake staff identity"), from the auditor notes when they list the turns. */
  tag: string | null;
  scored: boolean;
}

export interface InstructionVisual {
  reply: string;
  rules: RuleLine[];
  gauges: Gauge[];
  spans: Span[];
  chat: ChatTurn[] | null;
  passedCount: number;
  /** Whether per-character highlights were computed (needs the constraint list). */
  highlighted: boolean;
}

function allIndexes(hay: string, needle: string): number[] {
  const out: number[] = [];
  if (!needle) return out;
  let i = hay.indexOf(needle);
  while (i !== -1) {
    out.push(i);
    i = hay.indexOf(needle, i + needle.length);
  }
  return out;
}

/** Character offsets of each word as the checker counts them (whitespace-separated with a letter or digit). */
function wordOffsets(text: string): Array<[number, number]> {
  const out: Array<[number, number]> = [];
  for (const m of text.matchAll(/\S+/g)) if (/[\p{L}\p{N}]/u.test(m[0])) out.push([m.index!, m.index! + m[0].length]);
  return out;
}

export function plainRule(c: Constraint): string {
  const range = (noun: string, min?: number, max?: number) =>
    min !== undefined && max !== undefined ? (min === max ? `exactly ${min} ${noun}` : `${min} to ${max} ${noun}`) : min !== undefined ? `at least ${min} ${noun}` : max !== undefined ? `at most ${max} ${noun}` : noun;
  switch (c.check) {
    case 'word_count':
      return `Use ${range('words', c.min, c.max)}`;
    case 'sentence_count':
      return `Write ${range('sentences', c.min, c.max)}`;
    case 'paragraph_count':
      return `Write ${range('paragraphs', c.min, c.max)}`;
    case 'line_count':
      return `Write ${range('lines', c.min, c.max)}`;
    case 'bullet_count':
      return c.max === 0 && !c.min ? 'No bullet points' : `Use ${range('bullet points', c.min, c.max)}`;
    case 'include':
      return c.min === undefined && c.max === undefined ? `Say “${c.text}”` : `Say “${c.text}” ${range('times', c.min ?? 1, c.max).replace(/\b1 times\b/, 'once')}`;
    case 'exclude':
      return `Never say “${c.text}”`;
    case 'no_letter':
      return `Never use the letter “${c.letter}”`;
    case 'starts_with':
      return `Start with “${c.text.trim()}”`;
    case 'ends_with':
      return `End with “${c.text.trim()}”`;
    case 'all_lowercase':
      return 'No capital letters';
    case 'all_uppercase':
      return 'ALL CAPITALS';
    case 'no_commas':
      return 'No commas';
    case 'json':
      return 'Reply in valid JSON';
    case 'json_keys':
      return `JSON with keys ${c.keys.join(', ')}`;
    case 'regex':
      return (c.shouldMatch ?? true) ? 'Matches the required pattern' : 'A banned pattern never appears';
    case 'max_word_length':
      return `No word longer than ${c.max} letters`;
    case 'acrostic':
      return `First letters spell “${c.word}”`;
    case 'each_line_starts_with':
      return `Every line starts with “${c.text}”`;
    case 'title_case_lines':
      return 'Every Word Capitalised';
  }
}

/** Where in the reply each rule was broken (or, for limited counts, what was counted). */
export function ruleSpans(text: string, c: Constraint, rule: number, passed: boolean): Span[] {
  const bad = (start: number, end: number): Span => ({ start, end, rule, tone: 'bad' });
  const fold = (s: string, cs?: boolean) => (cs ? s : s.toLowerCase());
  switch (c.check) {
    case 'no_letter':
      return allIndexes(text.toLowerCase(), c.letter.toLowerCase()).map((i) => bad(i, i + c.letter.length));
    case 'exclude':
      return allIndexes(fold(text, c.caseSensitive), fold(c.text, c.caseSensitive)).map((i) => bad(i, i + c.text.length));
    case 'include':
      return allIndexes(fold(text, c.caseSensitive), fold(c.text, c.caseSensitive)).map((i) => ({ start: i, end: i + c.text.length, rule, tone: passed ? 'count' : 'bad' }));
    case 'no_commas':
      return [...text.matchAll(/[,，]/g)].map((m) => bad(m.index!, m.index! + 1));
    case 'all_lowercase':
      return [...text.matchAll(/\p{Lu}/gu)].map((m) => bad(m.index!, m.index! + m[0].length));
    case 'all_uppercase':
      return [...text.matchAll(/\p{Ll}/gu)].map((m) => bad(m.index!, m.index! + m[0].length));
    case 'regex': {
      if (c.shouldMatch ?? true) return [];
      let re: RegExp;
      try {
        re = new RegExp(c.pattern, (c.flags ?? '').includes('g') ? c.flags : `${c.flags ?? ''}g`);
      } catch {
        return [];
      }
      return [...text.matchAll(re)].filter((m) => m[0].length > 0).map((m) => bad(m.index!, m.index! + m[0].length));
    }
    case 'max_word_length':
      return wordOffsets(text).filter(([s, e]) => text.slice(s, e).replace(/[^\p{L}\p{N}'-]/gu, '').length > c.max).map(([s, e]) => bad(s, e));
    case 'word_count': {
      if (passed || c.max === undefined) return [];
      return wordOffsets(text).slice(c.max).map(([s, e]) => bad(s, e));
    }
    case 'starts_with': {
      if (passed) return [];
      const lead = text.length - text.trimStart().length;
      return [bad(lead, Math.min(text.length, lead + Math.max(c.text.length, 1)))];
    }
    case 'ends_with': {
      if (passed) return [];
      const end = text.trimEnd().length;
      return [bad(Math.max(0, end - Math.max(c.text.length, 1)), end)];
    }
    case 'each_line_starts_with': {
      const out: Span[] = [];
      let off = 0;
      for (const raw of text.split('\n')) {
        const t = raw.trim();
        if (t && !t.startsWith(c.text)) {
          const s = off + raw.indexOf(t);
          out.push(bad(s, s + Math.min(t.length, Math.max(1, c.text.length))));
        }
        off += raw.length + 1;
      }
      return out;
    }
    case 'bullet_count': {
      if (passed) return [];
      const out: Span[] = [];
      let off = 0;
      for (const raw of text.split('\n')) {
        const m = raw.match(/^\s*(?:[-*•]|\d+[.)])\s+/);
        if (m) out.push(bad(off, off + m[0].length));
        off += raw.length + 1;
      }
      return out;
    }
    default:
      return [];
  }
}

function gaugeFor(text: string, c: Constraint, rule: number, passed: boolean): Gauge | null {
  if (c.check === 'word_count') return { rule, noun: 'words', value: words(text).length, min: c.min, max: c.max, passed };
  if (c.check === 'sentence_count') return { rule, noun: 'sentences', value: sentences(text).length, min: c.min, max: c.max, passed };
  if (c.check === 'line_count') return { rule, noun: 'lines', value: lines(text).length, min: c.min, max: c.max, passed };
  if (c.check === 'paragraph_count') return { rule, noun: 'paragraphs', value: text.split(/\n\s*\n/).map((p) => p.trim()).filter(Boolean).length, min: c.min, max: c.max, passed };
  return null;
}

/** "(rapport, fake staff identity, emergency, …)" in the notes → one tag per user turn, when the count lines up. */
export function turnTags(notes: string | undefined, turns: number): Array<string | null> {
  const none = Array.from({ length: turns }, () => null);
  if (!notes || turns < 2) return none;
  const m = notes.match(/\(([^()]{3,200})\)/);
  if (!m) return none;
  const tags = m[1]!.split(/,\s*/).map((s) => s.trim()).filter(Boolean);
  if (tags.length === turns) return tags;
  if (tags.length === turns - 1) return [null, ...tags];
  return none;
}

function isConstraintList(x: unknown): x is Constraint[] {
  return Array.isArray(x) && x.length > 0 && x.every((c) => c && typeof c === 'object' && typeof (c as { check?: unknown }).check === 'string');
}

export function instructionVisual(input: CaseVisualInput): InstructionVisual | null {
  const reply = finalReply(input);
  const items = input.detail.items ?? [];
  const constraints = isConstraintList(input.expected) ? input.expected : null;
  let rules: RuleLine[];
  let spans: Span[] = [];
  let gauges: Gauge[] = [];
  let highlighted = false;
  if (constraints) {
    const checked = constraints.map((c) => checkConstraint(reply, c));
    // Must agree with what the scorer recorded; otherwise show the recorded checks only.
    const agrees = !items.length || (items.length === checked.length && items.every((it, i) => it.passed === checked[i]!.passed));
    if (agrees && reply) {
      rules = checked.map((it, i) => ({ label: it.label, passed: it.passed, detail: it.detail, plain: plainRule(constraints[i]!) }));
      constraints.forEach((c, i) => {
        spans.push(...ruleSpans(reply, c, i, checked[i]!.passed));
        const g = gaugeFor(reply, c, i, checked[i]!.passed);
        if (g) gauges.push(g);
      });
      highlighted = true;
    } else rules = items.map((it) => ({ label: it.label, passed: it.passed, detail: it.detail, plain: it.label }));
  } else {
    if (!items.length) return null;
    rules = items.map((it) => ({ label: it.label, passed: it.passed, detail: it.detail, plain: it.label }));
  }
  spans = spans.filter((s) => s.end > s.start).sort((a, b) => a.start - b.start || a.end - b.end);
  gauges = gauges.slice(0, 4);
  let chat: ChatTurn[] | null = null;
  if (input.turns.length > 1) {
    const tags = turnTags(input.notes, input.turns.length);
    chat = input.turns.map((u, i) => ({ user: u, reply: input.replies[i] ?? null, tag: tags[i] ?? null, scored: i === input.turns.length - 1 }));
  }
  return { reply, rules, gauges, spans, chat, passedCount: rules.filter((r) => r.passed).length, highlighted };
}

/** Split text into runs for rendering: each run lists the rules whose spans cover it. */
export function spanRuns(text: string, spans: Span[]): Array<{ text: string; rules: number[]; tone: 'bad' | 'count' | null }> {
  if (!spans.length) return [{ text, rules: [], tone: null }];
  const cuts = new Set<number>([0, text.length]);
  for (const s of spans) {
    cuts.add(Math.max(0, Math.min(text.length, s.start)));
    cuts.add(Math.max(0, Math.min(text.length, s.end)));
  }
  const pts = [...cuts].sort((a, b) => a - b);
  const out: Array<{ text: string; rules: number[]; tone: 'bad' | 'count' | null }> = [];
  for (let i = 0; i < pts.length - 1; i++) {
    const a = pts[i]!;
    const b = pts[i + 1]!;
    if (b <= a) continue;
    const cover = spans.filter((s) => s.start <= a && s.end >= b);
    const tone = cover.some((s) => s.tone === 'bad') ? 'bad' : cover.length ? 'count' : null;
    const prev = out[out.length - 1];
    const rules = [...new Set(cover.map((s) => s.rule))];
    if (prev && prev.tone === tone && prev.rules.join() === rules.join()) prev.text += text.slice(a, b);
    else out.push({ text: text.slice(a, b), rules, tone });
  }
  return out;
}

export function instructionHeadline(v: InstructionVisual): string {
  const n = v.rules.length;
  const broken = v.rules.filter((r) => !r.passed);
  if (!v.reply.trim()) return 'No reply';
  if (!broken.length) return `Kept all ${n} rules`;
  if (broken.length === 1) return `Broke one rule: ${broken[0]!.plain}`;
  return `Broke ${broken.length} of ${n} rules`;
}
