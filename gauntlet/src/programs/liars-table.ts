/**
 * The Liar's Table — a seeded dinner-party theft. The model interrogates the
 * suspects with enumerated questions, checks evidence, and must name the one
 * suspect whose story is contradicted, using as few questions as possible.
 * Config scales it from the standard case (5 suspects, 3 slots, 12 questions)
 * to hard cases (7 suspects, 4 slots, a door-log gap, two mistaken witnesses).
 */
import type { ProgramContext, ProgramDefinition, ProgramResult, ReplayFrame } from '../core/types.ts';
import {
  answerAbout,
  answerAlibi,
  answerObject,
  answerTime,
  EVIDENCE_IDS,
  evidenceText,
  generateWorld,
  LIE_VARIANTS,
  endTime,
  roomPhrase,
  SLOTS,
  slotsOf,
} from './lib/liars-table-world.ts';
import type { LieVariant, LtWorld } from './lib/liars-table-world.ts';
import { canonical, checkReason, parseCommand, wasInformed } from './lib/liars-table-parse.ts';
import type { LtCommand, ReasonCheck } from './lib/liars-table-parse.ts';

/** Questions at or below this count earn full efficiency credit. */
const PAR = 3;
const W_ACCUSE = 0.6;
const W_EFFICIENCY = 0.25;
const W_REASON = 0.15;

export interface LtConfig {
  questionBudget: number;
  variant: LieVariant | 'auto';
  /** When variant is 'auto', choose among these lie types (default: all four). */
  variants: LieVariant[];
  mistakenWitness: boolean | 'auto';
  /** Exact number of hedged honest mistakes; null keeps the standard seeded 0-or-1. */
  mistakenWitnesses: number | null;
  suspects: number;
  slots: number;
  doorLogGap: boolean;
}

export function readConfig(raw: Record<string, unknown>): LtConfig {
  const int = (v: unknown, min: number, max: number): number | null => {
    const n = Number(v);
    return v !== undefined && v !== null && Number.isInteger(n) && n >= min && n <= max ? n : null;
  };
  const variant = raw.variant;
  const mistaken = raw.mistakenWitness;
  return {
    questionBudget: int(raw.questionBudget, 3, 30) ?? 12,
    variant: typeof variant === 'string' && (LIE_VARIANTS as readonly string[]).includes(variant) ? (variant as LieVariant) : 'auto',
    variants: Array.isArray(raw.variants) ? LIE_VARIANTS.filter((v) => (raw.variants as unknown[]).includes(v)) : [...LIE_VARIANTS],
    mistakenWitness: mistaken === true || mistaken === false ? mistaken : 'auto',
    mistakenWitnesses: int(raw.mistakenWitnesses, 0, 3),
    suspects: int(raw.suspects, 4, 8) ?? 5,
    slots: int(raw.slots, 3, 4) ?? 3,
    doorLogGap: raw.doorLogGap === true,
  };
}

export function buildWorld(ctx: Pick<ProgramContext, 'rng'>, cfg: LtConfig): LtWorld {
  return generateWorld(ctx.rng.fork('liars-table'), {
    variant: cfg.variant === 'auto' ? undefined : cfg.variant,
    variantPool: cfg.variants,
    mistakenWitness: cfg.mistakenWitness,
    mistakenCount: cfg.mistakenWitnesses ?? undefined,
    suspects: cfg.suspects,
    slots: cfg.slots,
    doorLogGap: cfg.doorLogGap,
  });
}

const COUNT_WORDS = ['zero', 'one', 'two', 'three', 'four', 'five', 'six'];

function andList(items: string[]): string {
  return items.length <= 1 ? items.join('') : `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`;
}

/** The case file and rules — the first part of every (self-contained) turn prompt. */
export function buildBriefing(w: LtWorld, budget: number): string {
  const roomNotes = w.rooms
    .map((r) => {
      const notes: string[] = [];
      if (r === w.roles.adjacent) notes.push(`next door to the ${w.objectRoom}`);
      if (r === w.roles.bar) notes.push('has a bar');
      return notes.length ? `the ${r} (${notes.join('; ')})` : `the ${r}`;
    })
    .join(', ');
  const suspects = w.suspects.map((s) => `- ${s.name}, ${s.blurb}`).join('\n');
  return [
    `You are the detective in THE LIAR'S TABLE, an interrogation game. Every answer is fixed in advance, so asking the same question twice gives the same answer. Each message gives you the full case file and a log of everything asked so far.`,
    ``,
    `THE CASE`,
    `${w.host} held a dinner party at ${w.venue}. At 8:00 pm ${w.hostShort} showed every guest ${w.object} in the ${w.objectRoom}, then locked the room. At ${endTime(w)} pm ${w.object} was gone.`,
    `The theft window is split into ${COUNT_WORDS[w.slotCount]} half-hour slots: ${andList(slotsOf(w))} pm. During each slot every guest stayed in one room.`,
    `Rooms: the ${w.objectRoom} (where ${w.object} was kept), ${roomNotes}.`,
    ``,
    `SUSPECTS`,
    suspects,
    ``,
    `RULES`,
    `- Exactly one suspect is the thief. The thief was alone in the ${w.objectRoom} during one slot and lies about where they were then.`,
    `- Everyone else tells the truth. An honest guest may misremember a detail, but always says so when unsure.`,
    `- Evidence files are accurate but may be incomplete.`,
    `- You have ${budget} questions. Every ASK or CHECK uses one, and so does an invalid command.`,
    `- ACCUSE ends the game. Naming the thief matters most. Fewer questions, and a reason that cites the exact contradiction (who, where, at what time, disproved by whom or what), earn the rest. A wrong accusation scores zero.`,
    ``,
    `COMMANDS (exactly one per reply; if you write more than one ACCUSE, only the first counts)`,
    `ASK <suspect> ABOUT <topic>, where <topic> is one of:`,
    `  ALIBI: where they were at ${andList(slotsOf(w))}`,
    `  ${slotsOf(w).join(' | ')}: where they were at that time and who was with them`,
    `  <another suspect>: when and where they saw that person`,
    `  THE ${w.objectKey}: what they know about ${w.object}`,
    `CHECK <evidence>: DOOR LOG (${w.objectRoom} door keypad log) | WITNESS (statement from ${w.staff.title}) | RECEIPT (bar receipts) | CCTV (camera stills)`,
    `ACCUSE <suspect> BECAUSE <reason>`,
  ].join('\n');
}

export interface LogEntry {
  n: number;
  command: string;
  answer: string;
  valid: boolean;
}

/** One self-contained turn: case file, full Q&A log, budget, menu, then the exact output format. */
export function buildTurnPrompt(w: LtWorld, budget: number, log: readonly LogEntry[], asked: ReadonlySet<string>, notice = ''): string {
  const used = log.length;
  const left = budget - used;
  const forced = left <= 0;
  const logText = log.length === 0 ? '(nothing asked yet)' : log.map((e) => `Q${e.n} · ${e.command}\n${e.answer}`).join('\n\n');
  return [
    buildBriefing(w, budget),
    '',
    'CASE LOG (every question so far, with its answer)',
    logText,
    '',
    `Questions left: ${left} of ${budget}.${forced ? ' You have no questions left: you must ACCUSE now.' : ''}${notice ? ` ${notice}` : ''}`,
    '',
    buildMenu(w, asked, forced),
    '',
    'Reply with a few short sentences of reasoning at most, then end with exactly one line:',
    forced ? 'ACTION: ACCUSE <suspect> BECAUSE <reason>' : 'ACTION: <command>',
    'Exactly one command per reply. If you write more than one ACCUSE, only the first counts.',
  ].join('\n');
}

export function buildMenu(w: LtWorld, asked: ReadonlySet<string>, accuseOnly: boolean): string {
  const lines: string[] = ['Commands you can send now:'];
  const names = w.suspects.map((s) => s.name);
  if (!accuseOnly) {
    for (const s of names) {
      const topics = ['ALIBI', ...slotsOf(w), ...names.filter((n) => n !== s), `THE ${w.objectKey}`];
      const cmds = topics.map((t) => `ASK ${s} ABOUT ${t}`).filter((c) => !asked.has(c));
      if (cmds.length) lines.push(cmds.map((c) => `\`${c}\``).join(' '));
    }
    const checks = EVIDENCE_IDS.map((e) => `CHECK ${e}`).filter((c) => !asked.has(c));
    if (checks.length) lines.push(checks.map((c) => `\`${c}\``).join(' '));
  }
  lines.push(names.map((n) => `\`ACCUSE ${n} BECAUSE <reason>\``).join(' '));
  return lines.join('\n');
}

export function answerFor(cmd: LtCommand, w: LtWorld): string {
  if (cmd.kind === 'check') return evidenceText(w, cmd.evidence);
  if (cmd.kind !== 'ask') return '';
  const t = cmd.topic;
  const who = cmd.suspect;
  const body =
    t.kind === 'alibi'
      ? answerAlibi(w, who)
      : t.kind === 'time'
        ? answerTime(w, who, t.slot)
        : t.kind === 'suspect'
          ? answerAbout(w, who, t.name)
          : answerObject(w, who);
  return `${who}: ${body}`;
}

function verdictLine(w: LtWorld): string {
  const t = SLOTS[w.theftSlot]!;
  return `${w.culprit} was ${roomPhrase(w.objectRoom)} at ${t}, not ${roomPhrase(w.claimedRoom)}.`;
}

export const program: ProgramDefinition = {
  id: 'liars-table',
  name: "The Liar's Table",
  description:
    'A seeded dinner-party theft with five suspects, a hidden ground-truth timeline and four evidence files. The model interrogates suspects with enumerated questions under a strict budget, must find the one contradicted story, and accuse the thief with a reason.',
  scoring:
    'Naming the thief is worth 60%; a wrong or missing accusation scores 0. A correct accusation earns up to 25% more for efficiency — (budget − questions used) ÷ (budget − 3) — but only if the thief\'s claim and a source contradicting it were actually uncovered (no efficiency credit for lucky guesses). The final 15% checks the stated reason deterministically: a third each for citing the theft time, the room the thief claimed, and the suspect or evidence that disproves it. Only the first ACCUSE in a reply counts, and invalid commands still use up a question.',
  defaults: { questionBudget: 12, variant: 'auto', mistakenWitness: 'auto' },

  async run(ctx: ProgramContext): Promise<ProgramResult> {
    const cfg = readConfig(ctx.config);
    const w = buildWorld(ctx, cfg);
    const budget = cfg.questionBudget;

    const asked = new Set<string>();
    const log: LogEntry[] = [];
    const frames: ReplayFrame[] = [
      {
        step: 0,
        label: 'The case',
        observation: `${w.object} vanished from the ${w.objectRoom} at ${w.venue}. Suspects: ${w.suspects.map((s) => s.name).join(', ')}.`,
        stats: { budget: 100, 'Questions left': budget },
        tone: 'neutral',
      },
    ];
    let invalid = 0;
    let accusation: { suspect: string; reason: string } | null = null;
    let forcedAttempts = 0;
    let notice = '';

    while (accusation === null) {
      const forced = log.length >= budget;
      if (forced && forcedAttempts >= 2) break;
      const reply = await ctx.model.complete({
        messages: [{ role: 'user', content: buildTurnPrompt(w, budget, log, asked, notice) }],
        maxOutputTokens: ctx.maxOutputTokens,
        label: forced ? `accuse ${forcedAttempts + 1}` : `question ${log.length + 1}`,
      });
      const text = reply.stopReason === 'refusal' ? '' : reply.text;
      const cmd: LtCommand = text.trim() ? parseCommand(text, w) : { kind: 'invalid', raw: '', why: 'empty or refused reply' };
      notice = '';

      if (cmd.kind === 'accuse') {
        accusation = { suspect: cmd.suspect, reason: cmd.reason };
        break;
      }
      if (forced) {
        forcedAttempts++;
        notice = `Your last reply was not a valid accusation (${cmd.kind === 'invalid' ? cmd.why : 'no questions left'}).`;
        continue;
      }

      const n = log.length + 1;
      const left = budget - n;
      let answer: string;
      if (cmd.kind === 'invalid') {
        invalid++;
        answer = `Invalid command (${cmd.why}). This used a question.`;
      } else {
        answer = answerFor(cmd, w);
        asked.add(canonical(cmd, w));
      }
      const label = cmd.kind === 'invalid' ? `(invalid) ${canonical(cmd, w)}` : canonical(cmd, w);
      log.push({ n, command: label, answer, valid: cmd.kind !== 'invalid' });
      frames.push({
        step: n,
        label: `Q${n} · ${label}`,
        action: label,
        outcome: answer,
        stats: { budget: Math.round((left / budget) * 100), 'Questions left': left },
        tone: cmd.kind === 'invalid' ? 'bad' : 'neutral',
      });
    }

    return score(w, budget, log.length, invalid, asked, accusation, frames, log);
  },
};

function score(
  w: LtWorld,
  budget: number,
  used: number,
  invalid: number,
  asked: ReadonlySet<string>,
  accusation: { suspect: string; reason: string } | null,
  frames: ReplayFrame[],
  log: readonly LogEntry[],
): ProgramResult {
  const correct = accusation?.suspect === w.culprit;
  const informed = wasInformed(asked, w);
  const efficiency = Math.max(0, Math.min(1, (budget - used) / (budget - PAR)));
  const reason: ReasonCheck = accusation ? checkReason(accusation.reason, w) : { time: false, place: false, source: false, score: 0 };
  const parts = {
    accusation: correct ? W_ACCUSE : 0,
    efficiency: correct && informed ? W_EFFICIENCY * efficiency : 0,
    reason: correct ? W_REASON * reason.score : 0,
  };
  const total = Math.round((parts.accusation + parts.efficiency + parts.reason) * 1000) / 1000;

  const q = (n: number) => `${n} question${n === 1 ? '' : 's'}`;
  const summary = correct
    ? `Caught ${w.culprit} after ${q(used)}`
    : accusation
      ? `Accused the wrong person (${accusation.suspect})`
      : `Never accused anyone (${q(used)} used)`;

  frames.push({
    step: used + 1,
    label: 'Verdict',
    action: accusation ? `ACCUSE ${accusation.suspect} BECAUSE ${accusation.reason}`.slice(0, 400) : '(no accusation)',
    outcome: correct
      ? `Correct! ${verdictLine(w)}`
      : `Wrong — the thief was ${w.culprit}. ${verdictLine(w)}`,
    stats: { budget: Math.round(((budget - used) / budget) * 100), 'Questions used': used, Reason: `${Math.round(reason.score * 3)}/3` },
    tone: correct ? 'good' : 'bad',
  });

  return {
    score: total,
    passed: correct,
    summary,
    detail: {
      culprit: w.culprit,
      accused: accusation?.suspect ?? null,
      correct,
      informed,
      questionsUsed: used,
      budget,
      invalidActions: invalid,
      efficiency: Math.round(efficiency * 1000) / 1000,
      reason: accusation?.reason ?? null,
      reasonCheck: reason,
      components: parts,
      case: {
        object: w.object,
        objectRoom: w.objectRoom,
        theftTime: SLOTS[w.theftSlot],
        claimedRoom: w.claimedRoom,
        lieVariant: w.variant,
        contradictionSources: w.contradiction.sources,
        hardCase: w.mistaken !== null,
        mistaken: w.mistaken,
        mistakes: w.mistakes,
        suspects: w.suspects.length,
        slots: w.slotCount,
        doorLogGap: w.doorGap,
      },
      questions: log.map((e) => ({ n: e.n, command: e.command, valid: e.valid })),
    },
    replay: {
      title: `The Liar's Table — ${w.object} at ${w.venue}`,
      gauges: ['budget'],
      frames,
    },
  };
}
