/**
 * Chain of Whispers — a seeded ~470-word story with 12 checkable facts is
 * summarised (≤100 words) and re-expanded (~450 words) for several cycles,
 * each call in a fresh context that sees only the previous output. The score
 * is the fraction of facts that survive into the final story.
 */
import type { ProgramContext, ProgramDefinition, ProgramResult, ReplayFrame } from '../core/types.ts';
import { extractCodeBlock } from '../core/extract.ts';
import { generateStory, STORY_IDS, survivingFacts } from './lib/chain-of-whispers-story.ts';
import type { FactSpec, SourceStory, StoryId } from './lib/chain-of-whispers-story.ts';
import { traceFacts } from './lib/chain-of-whispers-trace.ts';
import { countWords, truncateWords } from './lib/needle-haystack-normalize.ts';

export { truncateWords };

export interface CwConfig {
  cycles: number;
  summaryWords: number;
  storyWords: number;
  storyMaxWords: number;
  storyMinWords: number;
  story: StoryId | 'auto';
  /** 12 (standard) or 20 facts (hard tier: longer source story). */
  facts: 12 | 20;
}

export function readConfig(raw: Record<string, unknown>): CwConfig {
  const int = (v: unknown, def: number, min: number, max: number) => {
    const n = Number(v);
    return Number.isInteger(n) && n >= min && n <= max ? n : def;
  };
  const story = typeof raw.story === 'string' && (STORY_IDS as readonly string[]).includes(raw.story) ? (raw.story as StoryId) : 'auto';
  const storyWords = int(raw.storyWords, 450, 150, 1500);
  return {
    cycles: int(raw.cycles, 3, 1, 8),
    summaryWords: int(raw.summaryWords, 100, 20, 400),
    storyWords,
    storyMaxWords: Math.max(storyWords, int(raw.storyMaxWords, 500, 150, 2000)),
    storyMinWords: Math.min(storyWords, int(raw.storyMinWords, 300, 50, 1500)),
    story,
    facts: Number(raw.facts) === 20 ? 20 : 12,
  };
}

const PENALTY_OVER = 0.02;
const PENALTY_SHORT = 0.04;
const PENALTY_CAP = 0.15;

/** How words are counted — stated in every prompt, and exactly what countWords() does. */
export const WORD_RULE =
  'Words are counted by splitting on spaces, so a hyphenated word (like "well-known") or a number (like "4,457") counts as one word; stand-alone punctuation does not count.';

export function summaryPrompt(text: string, limit: number): string {
  return [
    `Summarise the story below in at most ${limit} words. Keep as many of its specific details as you can: who each person is, names, numbers, dates, places and anything unusual. Anything beyond ${limit} words is cut off before the next step. ${WORD_RULE}`,
    '',
    'STORY:',
    '"""',
    text,
    '"""',
    '',
    `Reply with only the summary: plain prose sentences (no list, no title, no preamble), at most ${limit} words.`,
  ].join('\n');
}

export function expandPrompt(text: string, target: number, max: number): string {
  return [
    `Below is a short summary of a story. Write the full story it describes, about ${target} words long (never more than ${max}: anything beyond ${max} words is cut off before the next step). ${WORD_RULE} Keep every specific detail in the summary exactly as given — names, who each person is, numbers, dates, places and unusual details — and do not change or contradict any of them. You may add atmosphere, dialogue and connecting action.`,
    '',
    'SUMMARY:',
    '"""',
    text,
    '"""',
    '',
    `Reply with only the story: plain prose paragraphs (no title, no preamble), about ${target} words and never more than ${max}.`,
  ].join('\n');
}

/** Removes wrappers models sometimes add (code fences, echoed delimiters, a "Summary:" label). */
export function cleanOutput(text: string): string {
  let t = text.trim();
  if (/^```/.test(t)) t = extractCodeBlock(t)?.code.trim() ?? t;
  t = t.replace(/^"""\s*|\s*"""$/g, '').trim();
  t = t.replace(/^(?:\*\*|#+\s*)?(?:summary|story)\s*(?:\*\*)?\s*:\s*(?:\*\*)?\s*/i, '');
  return t.trim();
}

export interface RoundRecord {
  round: number;
  cycle: number;
  kind: 'summary' | 'story';
  /** Words the model wrote (before truncation). */
  words: number;
  limit: number;
  truncated: boolean;
  tooShort: boolean;
  failed: boolean;
  facts: string[];
  lost: string[];
  text: string;
}

function excerpt(text: string, max = 320): string {
  const t = text.replace(/\s+/g, ' ').trim();
  return t.length <= max ? t : `${t.slice(0, max - 1)}…`;
}

function factLabels(ids: string[], facts: readonly FactSpec[]): string {
  return ids.map((id) => facts.find((f) => f.id === id)?.label ?? id).join(', ');
}

export function buildStory(ctx: Pick<ProgramContext, 'rng'>, cfg: CwConfig): SourceStory {
  return generateStory(ctx.rng.fork('chain-of-whispers'), cfg.story, cfg.facts);
}

export const program: ProgramDefinition = {
  id: 'chain-of-whispers',
  name: 'Chain of Whispers',
  description:
    'A seeded ~470-word story containing 12 checkable facts (names with roles, numbers with units, a date, places, unusual details) is summarised to at most 100 words, then expanded back into a ~450-word story — three times over, each step in a fresh context that sees only the previous output. The model rewrites its own text on purpose, so the test measures self-consistent information retention: how much meaning survives repeated compression and reconstruction by the same model. The hard tier uses 20 facts, a 60-word summary limit and five cycles.',
  scoring:
    'After every step the text (cut to the word limit first; words are counted by splitting on spaces) is checked for the facts. A fact only counts when its keywords appear together in one sentence — a name next to its role, a number next to its unit — with digits and number words treated alike. Score = facts surviving in the final story ÷ number of facts, minus 0.02 for each output over its word limit and 0.04 for each story under 300 words (penalties capped at 0.15). Because the model only ever rewrites its own previous output, the score measures self-consistent information retention.',
  defaults: { cycles: 3, summaryWords: 100, storyWords: 450, storyMaxWords: 500, storyMinWords: 300, story: 'auto', facts: 12 },

  async run(ctx: ProgramContext): Promise<ProgramResult> {
    const cfg = readConfig(ctx.config);
    const story = buildStory(ctx, cfg);
    const facts = story.facts;
    const total = facts.length;
    const sourceFacts = survivingFacts(story.text, facts);

    const rounds: RoundRecord[] = [];
    let text = story.text;
    let prevFacts = sourceFacts;
    let broken: { cycle: number; kind: string } | null = null;

    outer: for (let cycle = 1; cycle <= cfg.cycles; cycle++) {
      for (const kind of ['summary', 'story'] as const) {
        const limit = kind === 'summary' ? cfg.summaryWords : cfg.storyMaxWords;
        const prompt = kind === 'summary' ? summaryPrompt(text, limit) : expandPrompt(text, cfg.storyWords, limit);
        const reply = await ctx.model.complete({
          messages: [{ role: 'user', content: prompt }],
          maxOutputTokens: ctx.maxOutputTokens,
          label: `cycle ${cycle} · ${kind}`,
        });
        const out = reply.stopReason === 'refusal' ? '' : cleanOutput(reply.text);
        const round = rounds.length + 1;
        if (!out) {
          broken = { cycle, kind };
          rounds.push({ round, cycle, kind, words: 0, limit, truncated: false, tooShort: false, failed: true, facts: [], lost: prevFacts, text: '' });
          break outer;
        }
        const words = countWords(out);
        const kept = words > limit ? truncateWords(out, limit) : out;
        const present = survivingFacts(kept, facts);
        rounds.push({
          round,
          cycle,
          kind,
          words,
          limit,
          truncated: words > limit,
          tooShort: kind === 'story' && Math.min(words, limit) < cfg.storyMinWords,
          failed: false,
          facts: present,
          lost: prevFacts.filter((id) => !present.includes(id)),
          text: kept,
        });
        text = kept;
        prevFacts = present;
      }
    }

    const expectedRounds = cfg.cycles * 2;
    const last = rounds[rounds.length - 1];
    const finalFacts = broken || !last ? [] : last.facts;
    const over = rounds.filter((r) => r.truncated).length;
    const short = rounds.filter((r) => r.tooShort).length;
    const penalty = Math.min(PENALTY_CAP, over * PENALTY_OVER + short * PENALTY_SHORT);
    const fraction = finalFacts.length / total;
    const score = Math.round(Math.max(0, fraction - penalty) * 1000) / 1000;

    ctx.artifact(
      'whispers.txt',
      'text',
      [`SOURCE — ${story.title}\n\n${story.text}`, ...rounds.map((r) => `ROUND ${r.round} — cycle ${r.cycle} ${r.kind} (${r.words} words${r.truncated ? `, cut to ${r.limit}` : ''})\n\n${r.text || '(no output)'}`)].join('\n\n────────\n\n'),
    );

    const points = [{ x: 0, y: sourceFacts.length }, ...rounds.map((r) => ({ x: r.round, y: r.facts.length }))];
    for (let x = rounds.length + 1; x <= expectedRounds; x++) points.push({ x, y: 0 });

    const frames: ReplayFrame[] = [
      {
        step: 0,
        label: `Source · ${story.title}`,
        observation: excerpt(story.text),
        outcome: `${sourceFacts.length}/${total} facts`,
        stats: { survival: Math.round((sourceFacts.length / total) * 100), facts: sourceFacts.length, words: countWords(story.text) },
        tone: 'neutral',
      },
      ...rounds.map((r): ReplayFrame => {
        const notes: string[] = [];
        if (r.failed) notes.push('empty or refused reply — the chain broke');
        if (r.truncated) notes.push(`cut from ${r.words} to ${r.limit} words`);
        if (r.tooShort) notes.push(`only ${r.words} words`);
        const lostText = r.lost.length ? `Lost: ${factLabels(r.lost, facts)}` : 'No facts lost';
        return {
          step: r.round,
          label: `Cycle ${r.cycle} · ${r.kind === 'summary' ? 'Summary' : 'Story'} (${r.words} words)`,
          observation: r.failed ? '(no output)' : excerpt(r.text),
          outcome: [lostText, ...notes].join(' · '),
          stats: { survival: Math.round((r.facts.length / total) * 100), facts: r.facts.length, words: r.words },
          tone: r.failed || r.lost.length > 0 ? 'bad' : 'good',
        };
      }),
    ];

    const cyclesText = `${cfg.cycles} cycle${cfg.cycles === 1 ? '' : 's'}`;
    const summary = broken
      ? `Chain broke in cycle ${broken.cycle} (${broken.kind}) · 0/${total} facts`
      : `${finalFacts.length}/${total} facts survived ${cyclesText}`;

    return {
      score,
      passed: score >= 0.6,
      summary,
      detail: {
        story: { id: story.id, title: story.title, words: countWords(story.text) },
        survived: finalFacts.length,
        total,
        fraction: Math.round(fraction * 1000) / 1000,
        penalty: { total: penalty, overLimit: over, tooShort: short },
        broken,
        facts: facts.map((f) => {
          const diedAt = rounds.find((r) => !r.facts.includes(f.id));
          return {
            id: f.id,
            label: f.label,
            canonical: f.canonical,
            survived: finalFacts.includes(f.id),
            diedAtRound: diedAt ? diedAt.round : null,
          };
        }),
        rounds: rounds.map((r) => ({
          round: r.round,
          cycle: r.cycle,
          kind: r.kind,
          words: r.words,
          limit: r.limit,
          truncated: r.truncated,
          tooShort: r.tooShort,
          failed: r.failed,
          facts: r.facts.length,
          lost: r.lost,
        })),
      },
      replay: {
        title: `Chain of Whispers — ${story.title}`,
        gauges: ['survival'],
        frames,
        series: [{ name: 'Facts surviving', points }],
        visual: {
          kind: 'chain-of-whispers',
          data: {
            facts: facts.map((f) => ({ id: f.id, label: f.label, canonical: f.canonical })),
            source: { text: story.text, trace: traceFacts(story.text, facts) },
            rounds: rounds.map((r) => ({ round: r.round, kind: r.kind, text: r.text, trace: r.failed ? {} : traceFacts(r.text, facts) })),
          },
        },
      },
    };
  },
};
