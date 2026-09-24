/**
 * The Liar's Table — command parsing, canonical command text and the
 * deterministic check of an accusation's stated reason.
 */
import { extractTagged } from '../../core/extract.ts';
import { claimedCompanions, ROOM_KEYWORDS, SLOT_START_MIN, SLOTS } from './liars-table-world.ts';
import type { EvidenceId, LtWorld } from './liars-table-world.ts';

export type Topic =
  | { kind: 'alibi' }
  | { kind: 'time'; slot: number }
  | { kind: 'suspect'; name: string }
  | { kind: 'object' };

export type LtCommand =
  | { kind: 'ask'; suspect: string; topic: Topic }
  | { kind: 'check'; evidence: EvidenceId }
  | { kind: 'accuse'; suspect: string; reason: string }
  | { kind: 'invalid'; raw: string; why: string };

export function canonical(cmd: LtCommand, w: LtWorld): string {
  switch (cmd.kind) {
    case 'ask': {
      const t = cmd.topic;
      const topic =
        t.kind === 'alibi' ? 'ALIBI' : t.kind === 'time' ? SLOTS[t.slot]! : t.kind === 'suspect' ? t.name : `THE ${w.objectKey}`;
      return `ASK ${cmd.suspect} ABOUT ${topic}`;
    }
    case 'check':
      return `CHECK ${cmd.evidence}`;
    case 'accuse':
      return `ACCUSE ${cmd.suspect}`;
    case 'invalid':
      return cmd.raw.slice(0, 80) || '(nothing)';
  }
}

const COMMAND_LINE = /^[\s>*_`#-]*(?:\d+[.)]\s*)?(ASK|CHECK|ACCUSE)\b(.*)$/i;

function clean(s: string): string {
  return s.replace(/[`*_"“”]/g, '').replace(/\s+/g, ' ').trim();
}

/** Finds the command in a model reply (the last ACTION: line, else the last bare command line). */
export function locateCommand(text: string): { line: string; rest: string } | null {
  const lines = text.split(/\r?\n/);
  const tagged = extractTagged(text, 'ACTION');
  if (tagged !== null) {
    for (let i = lines.length - 1; i >= 0; i--) {
      if (/ACTION\s*[:：]/i.test(lines[i]!)) return { line: tagged, rest: lines.slice(i + 1).join(' ') };
    }
    return { line: tagged, rest: '' };
  }
  for (let i = lines.length - 1; i >= 0; i--) {
    const m = lines[i]!.match(COMMAND_LINE);
    if (m) return { line: `${m[1]}${m[2]}`, rest: lines.slice(i + 1).join(' ') };
  }
  return null;
}

function findSuspect(token: string, w: LtWorld): string | null {
  const t = token.replace(/[^A-Za-z]/g, '').toLowerCase();
  return w.suspects.find((s) => s.name.toLowerCase() === t)?.name ?? null;
}

/** Maps "9:00", "9.00 pm", "21:00", "9pm", "half past eight" to a slot index. */
export function parseSlot(topic: string): number | null {
  const t = topic.toLowerCase().trim();
  if (/half[\s-]past\s+eight|eight[\s-]thirty/.test(t)) return 0;
  if (/half[\s-]past\s+nine|nine[\s-]thirty/.test(t)) return 2;
  if (/^nine(\s*(pm|o'?clock))?$/.test(t)) return 1;
  const m = t.match(/^(\d{1,2})(?:\s*[:.h]\s*(\d{2}))?\s*(?:pm|p\.m\.|o'?clock)?$/);
  if (!m) return null;
  let h = Number(m[1]);
  const min = m[2] === undefined ? 0 : Number(m[2]);
  if (h < 12) h += 12;
  const total = h * 60 + min;
  const idx = SLOT_START_MIN.indexOf(total);
  return idx >= 0 ? idx : null;
}

function parseEvidence(s: string, w: LtWorld): EvidenceId | null {
  const t = s.toLowerCase();
  if (/door|keypad|\blog\b/.test(t)) return 'DOOR LOG';
  if (/witness|statement|butler|maid|footman|housekeeper/.test(t) || t.includes(w.staff.name.toLowerCase())) return 'WITNESS';
  if (/receipt|\bbar\b|\btab\b/.test(t)) return 'RECEIPT';
  if (/cctv|camera|footage|video|still/.test(t)) return 'CCTV';
  return null;
}

export function parseCommand(text: string, w: LtWorld): LtCommand {
  const found = locateCommand(text);
  if (!found) return { kind: 'invalid', raw: clean(text).slice(0, 80), why: 'no ACTION line or command found' };
  const line = found.line.replace(/[`*_]/g, '').trim();
  const raw = clean(line);

  const accuse = line.match(/^ACCUSE\s+([A-Za-z]+)\b[\s,.:;-]*(?:BECAUSE\b[\s:,-]*)?(.*)$/i);
  if (accuse) {
    const suspect = findSuspect(accuse[1]!, w);
    if (!suspect) return { kind: 'invalid', raw, why: `unknown suspect "${accuse[1]}"` };
    const reason = clean(`${accuse[2] ?? ''} ${found.rest}`).slice(0, 800);
    return { kind: 'accuse', suspect, reason };
  }

  const check = raw.match(/^CHECK\s+(?:THE\s+)?(.+)$/i);
  if (check) {
    const evidence = parseEvidence(check[1]!, w);
    return evidence ? { kind: 'check', evidence } : { kind: 'invalid', raw, why: `unknown evidence "${check[1]}"` };
  }

  const ask = raw.match(/^ASK\s+([A-Za-z]+)\s+(?:ABOUT\s+)?(.+?)[.?!]*$/i);
  if (ask) {
    const suspect = findSuspect(ask[1]!, w);
    if (!suspect) return { kind: 'invalid', raw, why: `unknown suspect "${ask[1]}"` };
    const topicRaw = ask[2]!.trim();
    const upper = topicRaw.toUpperCase();
    if (/^(HIS |HER |THEIR )?ALIBI$/.test(upper) || upper === 'WHEREABOUTS') return { kind: 'ask', suspect, topic: { kind: 'alibi' } };
    const slot = parseSlot(topicRaw);
    if (slot !== null) return { kind: 'ask', suspect, topic: { kind: 'time', slot } };
    if (upper.includes(w.objectKey) || /^(THE )?(OBJECT|THEFT)$/.test(upper)) return { kind: 'ask', suspect, topic: { kind: 'object' } };
    const other = findSuspect(topicRaw, w);
    if (other && other !== suspect) return { kind: 'ask', suspect, topic: { kind: 'suspect', name: other } };
    return { kind: 'invalid', raw, why: `unknown topic "${topicRaw}"` };
  }
  return { kind: 'invalid', raw, why: 'not a valid command' };
}

// ─────────────────────────────────────────────────────────────────────────────
// Reason check
// ─────────────────────────────────────────────────────────────────────────────

const SLOT_WORDS: string[][] = [
  ['half past eight', 'half-past eight', 'eight thirty', 'eight-thirty'],
  ["nine o'clock", 'nine oclock', 'nine pm', 'nine p.m'],
  ['half past nine', 'half-past nine', 'nine thirty', 'nine-thirty'],
];

/** True when the text mentions a clock time inside the given half-hour slot. */
export function mentionsSlot(text: string, slot: number): boolean {
  const t = text.toLowerCase();
  if (SLOT_WORDS[slot]!.some((w) => t.includes(w))) return true;
  const start = SLOT_START_MIN[slot]!;
  const inSlot = (h: number, m: number) => {
    const total = (h < 12 ? h + 12 : h) * 60 + m;
    return total >= start && total < start + 30;
  };
  for (const m of t.matchAll(/\b(\d{1,2})\s*[:.h]\s*(\d{2})\b/g)) {
    if (inSlot(Number(m[1]), Number(m[2]))) return true;
  }
  for (const m of t.matchAll(/\b(\d{1,2})\s*(?:pm|p\.m\.|o'?clock)/g)) {
    if (inSlot(Number(m[1]), 0)) return true;
  }
  return false;
}

const SOURCE_PATTERNS: Record<string, RegExp> = {
  CCTV: /cctv|camera|footage|video|stills?\b/i,
  RECEIPT: /receipt|signed for|bar tab|purchase/i,
};

export interface ReasonCheck {
  time: boolean;
  place: boolean;
  source: boolean;
  score: number;
}

/**
 * A reason earns a third each for citing the theft time, the room the thief
 * claimed, and a source that contradicts that claim.
 */
export function checkReason(reason: string, w: LtWorld): ReasonCheck {
  const text = reason.toLowerCase();
  const time = mentionsSlot(text, w.theftSlot);
  const place = new RegExp(`\\b${ROOM_KEYWORDS[w.claimedRoom]}\\b`, 'i').test(text);
  const source = w.contradiction.sources.some((src) => {
    if (src === 'WITNESS') {
      const staffWord = w.staff.title.replace(/^the /, '');
      return /witness|statement/.test(text) || text.includes(staffWord) || text.includes(w.staff.name.toLowerCase());
    }
    const re = SOURCE_PATTERNS[src];
    if (re) return re.test(text);
    return new RegExp(`\\b${src.toLowerCase()}\\b`).test(text);
  });
  const score = ((time ? 1 : 0) + (place ? 1 : 0) + (source ? 1 : 0)) / 3;
  return { time, place, source, score };
}

/**
 * Did the detective actually uncover the lie before accusing? Requires having
 * heard the thief's claim about the theft slot AND seen a source that
 * contradicts it. Efficiency credit is only paid for informed accusations.
 */
export function wasInformed(asked: ReadonlySet<string>, w: LtWorld): boolean {
  const c = w.culprit;
  const t = SLOTS[w.theftSlot]!;
  const companions = claimedCompanions(w, c, w.theftSlot) ?? [];
  const heardClaim =
    asked.has(`ASK ${c} ABOUT ALIBI`) || asked.has(`ASK ${c} ABOUT ${t}`) || companions.some((x) => asked.has(`ASK ${c} ABOUT ${x}`));
  return w.contradiction.sources.some((src) => {
    // The fake bar purchase is only mentioned when the thief is asked about the theft slot itself.
    if (src === 'RECEIPT') return asked.has(`ASK ${c} ABOUT ${t}`) && asked.has('CHECK RECEIPT');
    if (src === 'CCTV' || src === 'WITNESS') return heardClaim && asked.has(`CHECK ${src}`);
    return heardClaim && (asked.has(`ASK ${src} ABOUT ${t}`) || asked.has(`ASK ${src} ABOUT ${c}`));
  });
}
