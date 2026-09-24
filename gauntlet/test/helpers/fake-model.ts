/**
 * Scripted fake ModelHandle + ProgramContext for program tests.
 *
 * A responder is a plain function `(system, userText, history, info) => reply`
 * that plays the model. Every call is recorded (system prompt, full message
 * list, reply) so tests can assert on the exact bytes a program sends.
 */
import type {
  ArtifactKind,
  ChatMessage,
  ChatSession,
  ModelHandle,
  ModelReply,
  ProgramContext,
  StopReason,
} from '../../src/core/types.ts';
import { createRng, hashString } from '../../src/core/rng.ts';

export type FakeResponse = string | { text: string; stopReason?: StopReason };

export interface ResponderInfo {
  /** 0-based index of this call across the whole fake model. */
  index: number;
  label?: string;
  kind: 'complete' | 'chat';
}

/**
 * Plays the model. `userText` is the latest user message, `history` the
 * messages before it (empty for single-shot calls).
 */
export type Responder = (
  system: string | undefined,
  userText: string,
  history: readonly ChatMessage[],
  info: ResponderInfo,
) => FakeResponse | Promise<FakeResponse>;

export interface FakeCall {
  index: number;
  kind: 'complete' | 'chat';
  label?: string;
  system?: string;
  /** Every message sent with this call, ending with the latest user message. */
  messages: ChatMessage[];
  maxOutputTokens?: number;
  reply: ModelReply;
}

export interface FakeModel extends ModelHandle {
  readonly calls: FakeCall[];
}

function toReply(res: FakeResponse): ModelReply {
  const text = typeof res === 'string' ? res : res.text;
  const stopReason: StopReason = typeof res === 'string' ? 'end' : (res.stopReason ?? 'end');
  return { text, stopReason, totalMs: 1, ttftMs: 1, outputTokens: Math.ceil(text.length / 4) };
}

export function createFakeModel(responder: Responder): FakeModel {
  const calls: FakeCall[] = [];

  async function invoke(
    kind: 'complete' | 'chat',
    system: string | undefined,
    messages: ChatMessage[],
    maxOutputTokens: number | undefined,
    label: string | undefined,
  ): Promise<ModelReply> {
    const last = messages[messages.length - 1];
    const userText = last && last.role === 'user' ? last.content : '';
    const history = messages.slice(0, -1);
    const index = calls.length;
    const reply = toReply(await responder(system, userText, history, { index, label, kind }));
    calls.push({ index, kind, label, system, messages: messages.map((m) => ({ ...m })), maxOutputTokens, reply });
    return reply;
  }

  return {
    calls,
    complete(req) {
      return invoke('complete', req.system, req.messages, req.maxOutputTokens, req.label);
    },
    chat(system?: string): ChatSession {
      const history: ChatMessage[] = [];
      return {
        get history() {
          return history;
        },
        async send(userText, opts) {
          const messages = [...history, { role: 'user' as const, content: userText }];
          const reply = await invoke('chat', system, messages, opts?.maxOutputTokens, opts?.label);
          history.push({ role: 'user', content: userText }, { role: 'assistant', content: reply.text });
          return reply;
        },
      };
    },
  };
}

/**
 * Mimics the harness's "Random Baseline" mock: picks a random single-backticked
 * option from the latest user message, or answers with filler when none exist.
 */
export function randomBacktickResponder(seed: number): Responder {
  const rng = createRng(seed);
  return (_system, userText) => {
    const options = [...userText.matchAll(/(?<!`)`([^`\n]+)`(?!`)/g)].map((m) => m[1]!);
    if (options.length === 0) return 'FINAL ANSWER: 42';
    return rng.pick(options);
  };
}

/**
 * Mirrors the real Random Baseline (src/providers/mock.ts `respond`) without
 * its artificial latency: backticked options → "ACTION: <random option>"
 * (one pick per tag for "TAG: value" options), SVG prompts → a random gray
 * circle, "A1:" prompts → "unknown" answers, FINAL ANSWER prompts → a random
 * number, otherwise filler prose. Seeded from the full prompt, like the mock.
 */
export function mockBaselineResponder(contestantId = 'random-baseline'): Responder {
  return (system, userText, history) => {
    const all = (system ?? '') + '\n' + [...history.map((m) => m.content), userText].join('\n');
    const rng = createRng(hashString(`${contestantId}\n${all}`));
    const options = [...userText.matchAll(/`([^`\n]{1,120})`/g)].map((m) => m[1]!.trim()).filter(Boolean);
    if (options.length > 0) {
      const tagged = options.filter((o) => /^[A-Z][A-Z_ ]{1,24}:\s*\S/.test(o));
      if (tagged.length > 0) {
        const byTag = new Map<string, string[]>();
        for (const o of tagged) {
          const tag = o.slice(0, o.indexOf(':')).trim();
          byTag.set(tag, [...(byTag.get(tag) ?? []), o]);
        }
        return [...byTag.values()].map((opts) => rng.pick(opts)).join('\n');
      }
      return `I will try this.\nACTION: ${rng.pick(options)}`;
    }
    if (/svg/i.test(userText) && /draw|illustrat|svg/i.test(userText)) {
      const x = rng.int(20, 300);
      const y = rng.int(20, 300);
      return `\`\`\`svg\n<svg xmlns="http://www.w3.org/2000/svg" width="400" height="400"><circle cx="${x}" cy="${y}" r="40" fill="gray"/></svg>\n\`\`\``;
    }
    if (/\bA\d+\s*:/.test(userText)) {
      const n = Math.max(1, (userText.match(/\bQ\d+\b/g) ?? []).length);
      return Array.from({ length: n }, (_, i) => `A${i + 1}: unknown`).join('\n');
    }
    if (/FINAL ANSWER/.test(userText)) return `FINAL ANSWER: ${rng.int(0, 100)}`;
    const words = ['the', 'model', 'result', 'answer', 'benchmark', 'quickly', 'random', 'value', 'system', 'data', 'story', 'river', 'light'];
    return Array.from({ length: 40 + rng.int(0, 40) }, () => rng.pick(words)).join(' ') + '.';
  };
}

/** A responder that always returns the same text (e.g. garbage or empty). */
export function constantResponder(text: string, stopReason: StopReason = 'end'): Responder {
  return () => ({ text, stopReason });
}

export interface SavedArtifact {
  name: string;
  kind: ArtifactKind;
  content: string;
}

export interface TestContext {
  ctx: ProgramContext;
  artifacts: SavedArtifact[];
}

/** Builds a ProgramContext exactly like the engine would (seeded rng, merged config). */
export function createTestContext(opts: {
  seed: number;
  model: ModelHandle;
  config?: Record<string, unknown>;
  defaults?: Record<string, unknown>;
  maxOutputTokens?: number;
}): TestContext {
  const artifacts: SavedArtifact[] = [];
  const ctx: ProgramContext = {
    seed: opts.seed,
    rng: createRng(opts.seed),
    config: { ...(opts.defaults ?? {}), ...(opts.config ?? {}) },
    model: opts.model,
    maxOutputTokens: opts.maxOutputTokens ?? 8000,
    signal: new AbortController().signal,
    artifact(name, kind, content) {
      artifacts.push({ name, kind, content });
    },
  };
  return { ctx, artifacts };
}
