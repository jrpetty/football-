/**
 * The Liar's Table — a seeded dinner-party theft. The model interrogates five
 * suspects with enumerated questions, checks evidence, and must name the one
 * suspect whose story is contradicted, using as few questions as possible.
 */
import type { ChatMessage, ProgramContext, ProgramDefinition, ProgramResult, ReplayFrame } from '../core/types.ts';
import {
  answerAbout,
  answerAlibi,
  answerObject,
  answerTime,
  EVIDENCE_IDS,
  evidenceText,
  generateWorld,
  LIE_VARIANTS,
  roomPhrase,
  SLOTS,
} from './lib/liars-table-world.ts';
import type { LieVariant, LtWorld } from './lib/liars-table-world.ts';
import { canonical, checkReason, parseCommand, wasInformed } from './lib/liars-table-parse.ts';
import type { LtCommand, ReasonCheck } from './lib/liars-table-parse.ts';

/** Questions at or below this count earn full efficiency credit. */
const PAR = 3;
const W_ACCUSE = 0.6;
const W_EFFICIENCY = 0.25;
const W_REASON = 0.15;
/** Assistant turns are capped in the running history to keep cost bounded. */
const MAX_HISTORY_REPLY_CHARS = 2000;

export interface LtConfig {
  questionBudget: number;
  variant: LieVariant | 'auto';
  mistakenWitness: boolean | 'auto';
}

export function readConfig(raw: Record<string, unknown>): LtConfig {
  const budget = Number(raw.questionBudget);
  const variant = raw.variant;
  const mistaken = raw.mistakenWitness;
  return {
    questionBudget: Number.isInteger(budget) && budget >= 3 && budget <= 30 ? budget : 12,
    variant: typeof variant === 'string' && (LIE_VARIANTS as readonly string[]).includes(variant) ? (variant as LieVariant) : 'auto',
    mistakenWitness: mistaken === true || mistaken === false ? mistaken : 'auto',
  };
}

export function buildWorld(ctx: Pick<ProgramContext, 'rng'>, cfg: LtConfig): LtWorld {
  return generateWorld(ctx.rng.fork('liars-table'), {
    variant: cfg.variant === 'auto' ? undefined : cfg.variant,
    mistakenWitness: cfg.mistakenWitness,
  });
}

export function buildSystemPrompt(w: LtWorld, budget: number): string {
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
    `You are the detective in THE LIAR'S TABLE, an interrogation game. Every answer is fixed in advance, so asking the same question twice gives the same answer.`,
    ``,
    `THE CASE`,
    `${w.host} held a dinner party at ${w.venue}. At 8:00 pm ${w.hostShort} showed every guest ${w.object} in the ${w.objectRoom}, then locked the room. At 10:00 pm ${w.object} was gone.`,
    `The theft window is split into three half-hour slots: 8:30, 9:00 and 9:30 pm. During each slot every guest stayed in one room.`,
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
    `COMMANDS (exactly one per reply)`,
    `ASK <suspect> ABOUT <topic>, where <topic> is one of:`,
    `  ALIBI: where they were at 8:30, 9:00 and 9:30`,
    `  8:30 | 9:00 | 9:30: where they were at that time and who was with them`,
    `  <another suspect>: when and where they saw that person`,
    `  THE ${w.objectKey}: what they know about ${w.object}`,
    `CHECK <evidence>: DOOR LOG (${w.objectRoom} door keypad log) | WITNESS (statement from ${w.staff.title}) | RECEIPT (bar receipts) | CCTV (camera stills)`,
    `ACCUSE <suspect> BECAUSE <reason>`,
    ``,
    `Think briefly (a few short sentences at most), then end every reply with exactly one line:`,
    `ACTION: <command>`,
  ].join('\n');
}

export function buildMenu(w: LtWorld, asked: ReadonlySet<string>, accuseOnly: boolean): string {
  const lines: string[] = ['Commands you can send now (reply with ACTION: <command>):'];
  const names = w.suspects.map((s) => s.name);
  if (!accuseOnly) {
    for (const s of names) {
      const topics = ['ALIBI', ...SLOTS, ...names.filter((n) => n !== s), `THE ${w.objectKey}`];
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

function trimReply(text: string): string {
  if (text.length <= MAX_HISTORY_REPLY_CHARS) return text;
  return `[…earlier reasoning trimmed…]\n${text.slice(-MAX_HISTORY_REPLY_CHARS)}`;
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
    'Naming the thief is worth 60%; a wrong or missing accusation scores 0. A correct accusation earns up to 25% more for efficiency — (budget − questions used) ÷ (budget − 3) — but only if the thief\'s claim and a source contradicting it were actually uncovered (no efficiency credit for lucky guesses). The final 15% checks the stated reason deterministically: a third each for citing the theft time, the room the thief claimed, and the suspect or evidence that disproves it.',
  defaults: { questionBudget: 12, variant: 'auto', mistakenWitness: 'auto' },

  async run(ctx: ProgramContext): Promise<ProgramResult> {
    const cfg = readConfig(ctx.config);
    const w = buildWorld(ctx, cfg);
    const budget = cfg.questionBudget;
    const system = buildSystemPrompt(w, budget);

    const turns: ChatMessage[] = [];
    const asked = new Set<string>();
    const log: Array<{ n: number; command: string; valid: boolean }> = [];
    const frames: ReplayFrame[] = [
      {
        step: 0,
        label: 'The case',
        observation: `${w.object} vanished from the ${w.objectRoom} at ${w.venue}. Suspects: ${w.suspects.map((s) => s.name).join(', ')}.`,
        stats: { budget: 100, 'Questions left': budget },
        tone: 'neutral',
      },
    ];
    let used = 0;
    let invalid = 0;
    let accusation: { suspect: string; reason: string } | null = null;
    let forcedAttempts = 0;
    let pending = `The interrogation begins. Questions left: ${budget} of ${budget}.`;

    while (accusation === null) {
      const forced = used >= budget;
      if (forced && forcedAttempts >= 2) break;
      const menu = buildMenu(w, asked, forced);
      const messages: ChatMessage[] = [...turns, { role: 'user', content: `${pending}\n\n${menu}` }];
      const reply = await ctx.model.complete({
        system,
        messages,
        maxOutputTokens: ctx.maxOutputTokens,
        label: forced ? `accuse ${forcedAttempts + 1}` : `question ${used + 1}`,
      });
      const text = reply.stopReason === 'refusal' ? '' : reply.text;
      const cmd: LtCommand = text.trim() ? parseCommand(text, w) : { kind: 'invalid', raw: '', why: 'empty or refused reply' };
      turns.push({ role: 'user', content: pending }, { role: 'assistant', content: text.trim() ? trimReply(text) : '(no reply)' });

      if (cmd.kind === 'accuse') {
        accusation = { suspect: cmd.suspect, reason: cmd.reason };
        break;
      }
      if (forced) {
        forcedAttempts++;
        pending = `That was not a valid accusation (${cmd.kind === 'invalid' ? cmd.why : 'no questions left'}). You must ACCUSE a suspect now.`;
        continue;
      }

      used++;
      const left = budget - used;
      let answer: string;
      if (cmd.kind === 'invalid') {
        invalid++;
        answer = `Invalid command (${cmd.why}). This used a question.`;
      } else {
        answer = answerFor(cmd, w);
        asked.add(canonical(cmd, w));
      }
      const label = canonical(cmd, w);
      log.push({ n: used, command: label, valid: cmd.kind !== 'invalid' });
      frames.push({
        step: used,
        label: `Q${used} · ${label}`,
        action: label,
        outcome: answer,
        stats: { budget: Math.round((left / budget) * 100), 'Questions left': left },
        tone: cmd.kind === 'invalid' ? 'bad' : 'neutral',
      });
      pending = `Q${used} · ${label}\n${answer}\n\nQuestions left: ${left} of ${budget}.`;
      if (left === 0) pending += ' You have no questions left: you must ACCUSE now.';
    }

    return score(w, budget, used, invalid, asked, accusation, frames, log);
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
  log: Array<{ n: number; command: string; valid: boolean }>,
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
      },
      questions: log,
    },
    replay: {
      title: `The Liar's Table — ${w.object} at ${w.venue}`,
      gauges: ['budget'],
      frames,
    },
  };
}
