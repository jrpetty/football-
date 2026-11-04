/**
 * The work-world turn loop, shared by every tool-world scenario (the personal assistant, and later the accounts
 * clerk, the helpdesk, …).
 *
 * One action per model call. The conversation keeps the last `keepFull` exchanges verbatim and shortens older
 * ones (their results become one line), so the cost per turn stays bounded; the model can keep a MEMO line of
 * its own. Every action counts against the budget, valid or not. The loop only records what happened: the
 * scenario scores the final world and its effect log afterwards. The replay frames and the display-only
 * `visual` data for the dashboard's work-world stage are built from the same log.
 */
import type { ChatMessage, ProgramContext, ReplayFrame } from '../../../core/types.ts';
import { parseAction, type ParsedAction } from './protocol.ts';
import { toolNames, type Tool, type ToolOutcome } from './tools.ts';
import { fmtDay } from './time.ts';
import type { WorkWorld } from './world.ts';

export interface ScenarioSpec<W extends WorkWorld> {
  world: W;
  tools: Tool<W>[];
  system: string;
  intro: string;
  maxActions: number;
  keepFull: number;
  /** Backticked suggestions added to every turn (read-only actions only: the Random Baseline picks from them). */
  options(world: W): string[];
  /** Extra text for the status line (counts of what has been done). */
  status?(world: W): string;
  /** Extra stats for replay frames. */
  frameStats?(world: W): Record<string, number | string>;
}

export interface ActionLogEntry {
  n: number;
  /** The tool used, or null for an invalid action. */
  tool: string | null;
  line: string;
  ok: boolean;
  note?: string;
}

export interface RunRecord {
  actionsUsed: number;
  finishedBy: 'model' | 'actions';
  invalidActions: number;
  log: ActionLogEntry[];
  frames: ReplayFrame[];
  memo: string;
}

interface Exchange {
  reply: string;
  line: string;
  result: string;
  brief: string;
}

/** The "ACTIONS" block of a system prompt: every tool's usage lines. */
export function actionsHelp(tools: readonly Tool[]): string {
  return tools.flatMap((t) => t.usage).join('\n');
}

export function buildMessages(intro: string, history: readonly Exchange[], keepFull: number): ChatMessage[] {
  const msgs: ChatMessage[] = [{ role: 'user', content: intro }];
  history.forEach((h, i) => {
    const old = i < history.length - keepFull;
    msgs.push({ role: 'assistant', content: old ? `(earlier reply, shortened)\nACTION: ${h.line}` : h.reply });
    msgs.push({ role: 'user', content: old ? h.brief : h.result });
  });
  return msgs;
}

function shorten(text: string, max: number): string {
  const t = text.replace(/\s+/g, ' ').trim();
  return t.length > max ? `${t.slice(0, max - 1)}…` : t;
}

export function optionsLine<W extends WorkWorld>(spec: ScenarioSpec<W>): string {
  return `Options include: ${spec.options(spec.world).map((o) => `\`${o}\``).join(', ')}.`;
}

export async function runWorkWorld<W extends WorkWorld>(ctx: ProgramContext, spec: ScenarioSpec<W>): Promise<RunRecord> {
  const { world, tools } = spec;
  const names = toolNames(tools);
  const byName = new Map(tools.map((t) => [t.name, t]));
  const history: Exchange[] = [];
  const log: ActionLogEntry[] = [];
  const frames: ReplayFrame[] = [];
  let actions = 0;
  let invalid = 0;
  let memo = '';
  let finishedBy: RunRecord['finishedBy'] = 'actions';

  const stats = () => ({ 'actions left': Math.max(0, spec.maxActions - actions), ...(spec.frameStats?.(world) ?? {}) });
  frames.push({ step: 0, label: 'Start', observation: shorten(spec.intro, 300), outcome: `${fmtDay(world.week, world.now.day)}: the week begins.`, tone: 'neutral', stats: stats() });

  while (actions < spec.maxActions) {
    if (ctx.signal.aborted) throw Object.assign(new Error('Aborted'), { name: 'AbortError' });
    const reply = await ctx.model.complete({ system: spec.system, messages: buildMessages(spec.intro, history, spec.keepFull), maxOutputTokens: ctx.maxOutputTokens, label: `Action ${actions + 1}` });
    actions++;
    const n = actions;
    const text = typeof reply.text === 'string' ? reply.text : '';
    const parsed: ParsedAction =
      reply.stopReason === 'refusal'
        ? { name: null, argText: '', args: [], body: '', line: '', memo: null, ignored: 0, error: 'The model refused to answer.' }
        : !text.trim()
        ? { name: null, argText: '', args: [], body: '', line: '', memo: null, ignored: 0, error: 'The reply was empty.' }
        : parseAction(text, names);
    if (parsed.memo !== null) memo = parsed.memo;
    const tool = parsed.name ? byName.get(parsed.name) : undefined;
    let out: ToolOutcome;
    if (!tool) {
      invalid++;
      out = { ok: false, text: `Invalid action: ${parsed.error ?? 'unknown action'}`, outcome: parsed.error ?? 'Invalid action', tone: 'bad' };
    } else if (reply.stopReason === 'max_tokens' && tool.takesBody) {
      invalid++;
      out = { ok: false, text: `Your reply was cut off at the output limit, so the ${tool.name} was not carried out. Write less before the ACTION line.`, outcome: 'Reply cut off at the output limit; not carried out.', tone: 'bad' };
    } else {
      out = await tool.run(world, parsed, n);
      if (!out.ok) invalid++;
    }
    const line = parsed.line || '(no action)';
    log.push({ n, tool: tool?.name ?? null, line: shorten(line, 200), ok: out.ok, ...(out.ok ? {} : { note: shorten(out.text, 160) }) });
    frames.push({ step: n, label: `Action ${n}`, action: shorten(line, 110), outcome: shorten(out.outcome ?? out.text, 220), tone: out.ok ? (out.tone ?? 'neutral') : 'bad', stats: stats() });

    if (out.finish) {
      finishedBy = 'model';
      history.push({ reply: text, line, result: out.text, brief: out.text });
      break;
    }
    const left = spec.maxActions - actions;
    const ignored = parsed.ignored > 0 ? `\n(Note: one action per reply: only your last ACTION line was used; ${parsed.ignored} earlier one${parsed.ignored === 1 ? ' was' : 's were'} ignored.)` : '';
    const status = `Status: ${left} action${left === 1 ? '' : 's'} left${spec.status ? ` · ${spec.status(world)}` : ''}`;
    const memoLine = memo ? `Your memo: "${memo}"` : 'Your memo: (empty; a MEMO: line before your ACTION line keeps notes for yourself)';
    const next = left > 0 ? `Reply with your next action. ${optionsLine(spec)}` : 'No actions left: the week ends here.';
    history.push({
      reply: text,
      line,
      result: `Result of action ${n} (${shorten(line, 80)}):\n\n${out.text}${ignored}\n\n${status}\n${memoLine}\n${next}`,
      brief: `Result of action ${n} (${shorten(line, 60)}): ${out.brief ?? shorten(out.text.split('\n')[0] ?? '', 160)}`,
    });
  }
  return { actionsUsed: actions, finishedBy, invalidActions: invalid, log, frames, memo };
}
