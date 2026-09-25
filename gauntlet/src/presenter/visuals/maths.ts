/**
 * Maths (Competition, Word Problems, Olympiad): a problem card with the
 * question typeset (powers, fractions, square roots, ≤ / ≥ / ×), the model's
 * final number against the key, and a receipt / payslip / bill look for
 * money word problems.
 */
import { finalReply, plainNumber, statedAnswer, viewerText, type CaseVisualInput } from './common.ts';

export type MathSeg =
  | { t: 'text'; v: string }
  | { t: 'sup'; v: string }
  | { t: 'sub'; v: string }
  | { t: 'frac'; num: string; den: string }
  | { t: 'sqrt'; v: string }
  | { t: 'money'; v: string };

const SYMBOLS: Array<[RegExp, string]> = [
  [/<=/g, '≤'],
  [/>=/g, '≥'],
  [/!=/g, '≠'],
  [/->/g, '→'],
  [/(\S) \* (\S)/g, '$1 × $2'],
  [/([A-Za-z0-9)])\*([A-Za-z0-9(])/g, '$1·$2'],
];

const TOKEN = new RegExp(
  [
    /\^\(([^()]{1,24})\)/.source, // ^(…)
    /\^(-?[A-Za-z0-9]{1,8})/.source, // ^2, ^N, ^-2
    /(?<![\w/.])(\d{1,6}|[a-z])\/(\d{1,6}|[a-z])(?![\w/])/.source, // 2/3, m/n
    /sqrt\(([^()]{1,24})\)/.source, // sqrt(2)
    /(?<=[A-Za-z])_([A-Za-z0-9])\b/.source, // a_i
    /((?:[$£€]|USD |EUR )\d[\d,]*(?:\.\d+)?)/.source, // money
  ].join('|'),
  'g',
);

/** Split plain text into typeset segments. Every character of the input is kept (as text or a styled segment). */
export function mathSegments(text: string): MathSeg[] {
  let s = text;
  for (const [re, to] of SYMBOLS) s = s.replace(re, to);
  const out: MathSeg[] = [];
  let last = 0;
  for (const m of s.matchAll(TOKEN)) {
    const i = m.index ?? 0;
    if (i > last) out.push({ t: 'text', v: s.slice(last, i) });
    if (m[1] !== undefined) out.push({ t: 'sup', v: m[1] });
    else if (m[2] !== undefined) out.push({ t: 'sup', v: m[2] });
    else if (m[3] !== undefined) out.push({ t: 'frac', num: m[3], den: m[4]! });
    else if (m[5] !== undefined) out.push({ t: 'sqrt', v: m[5] });
    else if (m[6] !== undefined) out.push({ t: 'sub', v: m[6] });
    else if (m[7] !== undefined) out.push({ t: 'money', v: m[7] });
    last = i + m[0].length;
  }
  if (last < s.length) out.push({ t: 'text', v: s.slice(last) });
  return out;
}

export type PaperStyle = 'receipt' | 'payslip' | 'bill' | null;

/** A money word problem gets a paper look. Detection is purely textual (never changes the content). */
export function paperStyle(prompt: string): PaperStyle {
  const money = (prompt.match(/[$£€]\s?\d/g) ?? []).length;
  if (money < 1) return null;
  if (/\bpaid\b.{0,40}?\bper (?:paid )?hour|\bgross salary\b|\bincome tax\b|\bpayslip\b/i.test(prompt)) return 'payslip';
  if (money < 2) return null;
  if (/\bbill(?:s|ed|ing)?\b|\bsubscription\b/i.test(prompt)) return 'bill';
  if (/\bbuys\b|\bcart\b|\bcheckout\b|\bpays with\b/i.test(prompt)) return 'receipt';
  return null;
}

export interface MathsVisual {
  /** The question with answer-format boilerplate removed, split into paragraphs. */
  paragraphs: string[];
  /** The last paragraph that asks the question (highlighted). */
  askIndex: number;
  key: number;
  keyText: string;
  given: string | null;
  givenValue: number | null;
  ok: boolean;
  /** The model's working (its reply without the FINAL ANSWER line). */
  working: string;
  paper: PaperStyle;
  hedged: boolean;
}

export function mathsVisual(input: CaseVisualInput): MathsVisual | null {
  const key = typeof input.expected === 'number' ? input.expected : plainNumber(String(input.expected ?? ''));
  if (key === null || !input.turns.length) return null;
  const text = viewerText(input.turns[0]!)
    .replace(/Give the final answer as[^\n]*/gi, '')
    .replace(/Give the answer as[^\n]*/gi, '')
    .trim();
  const paragraphs = text.split(/\n\s*\n/).map((p) => p.trim()).filter(Boolean);
  let askIndex = -1;
  paragraphs.forEach((p, i) => {
    if (/\?|\b(?:Find|Compute|Determine|How many|What is)\b/.test(p)) askIndex = i;
  });
  const stated = statedAnswer(input);
  const givenValue = typeof input.detail.parsed === 'number' ? input.detail.parsed : stated ? plainNumber(stated.answer) : null;
  const ok = input.passed === true || (input.passed === null && givenValue !== null && Math.abs(givenValue - key) < 1e-9);
  const reply = finalReply(input);
  const working = reply.replace(/^.*final\s+answer\s*[:：].*$/gim, '').trim();
  return {
    paragraphs,
    askIndex,
    key,
    keyText: String(key),
    given: stated ? stated.answer : null,
    givenValue,
    ok,
    working,
    paper: paperStyle(input.turns[0]!),
    hedged: input.detail.hedged === true,
  };
}

export function mathsHeadline(v: MathsVisual): string {
  if (v.given === null || v.given === '') return 'No final answer given';
  if (v.hedged) return `Hedged between answers — marked wrong`;
  if (v.ok) return `Right: ${v.keyText}`;
  if (v.givenValue === null) return `Answered “${v.given}”, not a number`;
  const d = v.givenValue - v.key;
  const pct = v.key !== 0 ? Math.abs(d / v.key) * 100 : null;
  return pct !== null && pct < 1 ? `Off by ${Math.abs(Number(d.toPrecision(6)))}: close, but exact answers only` : `Wrong: ${v.given} instead of ${v.keyText}`;
}
