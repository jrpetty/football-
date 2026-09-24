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
import { createRng } from '../../src/core/rng.ts';

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
