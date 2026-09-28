import { computeCost, emptyUsage, addUsage } from '../core/cost.ts';
import type { ChatMessage, ChatSession, CompletionRequest, CompletionResult, Contestant, ModelHandle, ModelReply, TokenUsage, TranscriptEntry } from '../core/types.ts';
import { ProviderError, type ProviderAdapter } from '../providers/index.ts';
import type { Semaphore } from './semaphore.ts';
import { stripImageData } from '../core/vision.ts';
import { recordedImageCall } from './image-recorder.ts';
import { estimateInputTokens, perAnswerOutputTokens, type SpendGuard } from './spend-guard.ts';

export interface CallPolicy {
  maxRetries: number;
  temperature: number;
  defaultMaxOutputTokens: number;
  /** Whole-run spend limit: every call reserves its worst case first and may get a lower output limit (spend-guard.ts). */
  spend?: SpendGuard;
  /** Per-answer spend limit in USD (contestant calls only, never judges): lowers each call's output limit to what it buys. */
  perAnswerUsd?: number;
}

/** Why a call's output limit was lowered below what the test asked for (shown when a reply is cut off). */
export type OutputLimitBy = 'spend-limit' | 'per-answer';

export interface CallTarget {
  contestant: Contestant;
  adapter: ProviderAdapter;
  semaphore: Semaphore;
}

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) return reject(Object.assign(new Error('Aborted'), { name: 'AbortError' }));
    const t = setTimeout(() => {
      signal.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    const onAbort = () => {
      clearTimeout(t);
      reject(Object.assign(new Error('Aborted'), { name: 'AbortError' }));
    };
    signal.addEventListener('abort', onAbort, { once: true });
  });
}

/** Output limit used for "model-max" tests when a model does not declare its own maximum (a common API ceiling). */
export const MODEL_MAX_FALLBACK = 65_536;

/**
 * A test's output budget for one contestant: its number, the settings default, or, for "model-max", the model's
 * own maximum. The result still goes through effectiveMaxOutputTokens (a configured cap can only lower it).
 */
export function resolveOutputBudget(
  requested: number | 'model-max' | undefined,
  c: Pick<Contestant, 'maxOutputTokens'>,
  fallbackDefault: number,
  /** The run's "same token limit for every model" choice: replaces "model-max" (a model's own lower maximum still applies). */
  sameOutputTokens?: number,
): number {
  if (requested === 'model-max' && sameOutputTokens && sameOutputTokens > 0) return sameOutputTokens;
  if (requested === 'model-max') return c.maxOutputTokens && c.maxOutputTokens > 0 ? c.maxOutputTokens : MODEL_MAX_FALLBACK;
  return requested ?? fallbackDefault;
}

/**
 * Output-token limit actually sent for one call: the requested value, lowered to the contestant's configured cap
 * (options.maxOutputTokensCap) and to the model's own API limit (maxOutputTokens), whichever is smallest.
 */
export function effectiveMaxOutputTokens(c: Pick<Contestant, 'options' | 'maxOutputTokens'>, requested: number): number {
  let n = requested;
  const cap = c.options?.maxOutputTokensCap;
  if (cap) n = Math.min(n, cap);
  if (c.maxOutputTokens && c.maxOutputTokens > 0) n = Math.min(n, c.maxOutputTokens);
  return n;
}

/** Thrown when a model call exceeds its answer-time limit (time-pressure tests). Never retried. */
export class OutOfTimeError extends Error {
  readonly limitMs: number;
  readonly elapsedMs: number;
  constructor(limitMs: number, elapsedMs: number) {
    super(`Out of time: no answer within ${Math.round(limitMs / 1000)} s`);
    this.name = 'OutOfTimeError';
    this.limitMs = limitMs;
    this.elapsedMs = elapsedMs;
  }
}

/**
 * One API call with the harness's uniform retry policy: retryable errors
 * (429, 5xx, network) back off exponentially (honouring Retry-After) up to
 * `maxRetries`. Partial streamed text from failed attempts is discarded.
 */
export async function callWithRetry(
  target: CallTarget,
  req: { system?: string; messages: ChatMessage[]; maxOutputTokens: number; temperature: number; onDelta?: (t: string) => void; callContext?: CompletionRequest['callContext'] },
  policy: CallPolicy,
  signal: AbortSignal,
  onRetry?: (attempt: number, waitMs: number, err: Error) => void,
  /**
   * Answer-time limit per attempt (ms). The clock starts when the request is sent, so queueing for a
   * provider slot and retry back-off never count against the model. A slower attempt is aborted and
   * throws OutOfTimeError (not retried).
   */
  attemptLimitMs?: number,
): Promise<CompletionResult & { outputLimitBy?: OutputLimitBy; maxOutputTokensSent?: number }> {
  let maxOutputTokens = effectiveMaxOutputTokens(target.contestant, req.maxOutputTokens);
  let limitBy: OutputLimitBy | undefined;
  const pricing = target.contestant.pricing;
  const inputTokens = policy.spend || policy.perAnswerUsd ? estimateInputTokens(req.system, req.messages) : 0;
  if (policy.perAnswerUsd && pricing) {
    // Never below a small floor: a limit that cannot even pay for the prompt still sends a (short) request.
    const n = Math.max(256, perAnswerOutputTokens(policy.perAnswerUsd, inputTokens, pricing));
    if (n < maxOutputTokens) {
      maxOutputTokens = n;
      limitBy = 'per-answer';
    }
  }
  let attempt = 0;
  for (;;) {
    const release = await target.semaphore.acquire(signal);
    // Whole-run spend limit: claim this attempt's worst case (waiting for running calls, or lowering the limit).
    let reservation: Awaited<ReturnType<SpendGuard['reserve']>> | null = null;
    if (policy.spend && pricing) {
      try {
        reservation = await policy.spend.reserve({ inputTokens, maxOutputTokens, pricing, signal, what: `a call to ${target.contestant.label}` });
      } catch (err) {
        release();
        throw err;
      }
    }
    const sent = reservation ? reservation.maxOutputTokens : maxOutputTokens;
    const sentBy: OutputLimitBy | undefined = reservation?.clamped ? 'spend-limit' : limitBy;
    let billed = 0;
    const limiter = attemptLimitMs ? new AbortController() : null;
    const onOuter = () => limiter?.abort();
    let late = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const sentAt = Date.now();
    if (limiter) {
      signal.addEventListener('abort', onOuter, { once: true });
      timer = setTimeout(() => {
        late = true;
        limiter.abort();
      }, attemptLimitMs);
    }
    try {
      const r = await target.adapter.complete({ ...req, maxOutputTokens: sent, signal: limiter?.signal ?? signal });
      billed = r.costUsd ?? (pricing ? computeCost(r.usage, pricing) : 0);
      // Adapters that cannot be interrupted still get judged on their measured time.
      if (attemptLimitMs && (late || r.totalMs > attemptLimitMs)) throw new OutOfTimeError(attemptLimitMs, Math.max(r.totalMs, Date.now() - sentAt));
      return { ...r, retries: attempt, ...(sentBy ? { outputLimitBy: sentBy } : {}), maxOutputTokensSent: sent };
    } catch (err) {
      if (err instanceof OutOfTimeError) throw err;
      if (late && !signal.aborted) throw new OutOfTimeError(attemptLimitMs!, Date.now() - sentAt);
      if (signal.aborted) throw err;
      const retryable = err instanceof ProviderError ? err.retryable : false;
      if (!retryable || attempt >= policy.maxRetries) throw err;
      const base = err instanceof ProviderError && err.retryAfterMs !== undefined ? err.retryAfterMs : Math.min(60_000, 1500 * 2 ** attempt);
      const wait = Math.round(base + Math.random() * 500);
      onRetry?.(attempt + 1, wait, err as Error);
      reservation?.settle(0);
      release();
      await sleep(wait, signal);
      attempt++;
      continue;
    } finally {
      if (timer) clearTimeout(timer);
      signal.removeEventListener('abort', onOuter);
      // A failed attempt is not billed by the providers we support; a finished one settles at its real cost.
      reservation?.settle(billed);
      release();
    }
  }
}

export interface CaseRecorder {
  handle: ModelHandle;
  transcript: TranscriptEntry[];
  usage: TokenUsage;
  costUsd: number;
  judgeCostUsd: number;
  apiCalls: number;
  retries: number;
  firstTtftMs: number | null;
  /** Sum of end-to-end call durations (used for output tokens/sec). */
  generationMs: number;
  lastStopReason: ModelReply['stopReason'] | null;
  responseChars: number;
  recordJudge(entry: TranscriptEntry): void;
}

/**
 * Wraps a contestant as a ModelHandle for one case, recording every call
 * (prompt, response, timing, tokens, cost) into the case transcript.
 */
export function createRecorder(opts: {
  target: CallTarget;
  policy: CallPolicy;
  signal: AbortSignal;
  maxOutputTokens: number;
  onDelta?: (text: string, label?: string) => void;
  /**
   * Live view only: the text streamed so far for the current call was thrown away (a failed attempt is
   * being retried), so a watcher should drop it. Never affects what is recorded.
   */
  onDeltaReset?: (label: string) => void;
  onCall?: (label: string) => void;
  onRetry?: (attempt: number, waitMs: number, err: Error) => void;
  /** Identifies the job (for manual copy & paste requests). */
  callContext?: Omit<NonNullable<CompletionRequest['callContext']>, 'label'>;
  /** Answer-time limit per model call in ms (time-pressure tests); see callWithRetry. */
  attemptLimitMs?: number;
}): CaseRecorder {
  const rec: CaseRecorder = {
    handle: null as unknown as ModelHandle,
    transcript: [],
    usage: emptyUsage(),
    costUsd: 0,
    judgeCostUsd: 0,
    apiCalls: 0,
    retries: 0,
    firstTtftMs: null,
    generationMs: 0,
    lastStopReason: null,
    responseChars: 0,
    recordJudge(entry) {
      rec.transcript.push(entry);
      rec.judgeCostUsd += entry.costUsd;
    },
  };

  let callNo = 0;
  const complete = async (req: { system?: string; messages: ChatMessage[]; maxOutputTokens?: number; label?: string }): Promise<ModelReply> => {
    callNo++;
    const label = req.label ?? `call ${callNo}`;
    opts.onCall?.(label);
    const started = Date.now();
    // Characters streamed to the live view during the current attempt (display only).
    let streamed = 0;
    try {
      const r = await callWithRetry(
        opts.target,
        {
          system: req.system,
          messages: req.messages,
          maxOutputTokens: req.maxOutputTokens ?? opts.maxOutputTokens,
          temperature: opts.policy.temperature,
          onDelta: opts.onDelta
            ? (t) => {
                streamed += t.length;
                opts.onDelta!(t, label);
              }
            : undefined,
          callContext: opts.callContext ? { ...opts.callContext, label } : undefined,
        },
        opts.policy,
        opts.signal,
        (attempt, wait, err) => {
          rec.retries++;
          if (streamed > 0) {
            streamed = 0;
            opts.onDeltaReset?.(label);
          }
          opts.onRetry?.(attempt, wait, err);
        },
        opts.attemptLimitMs,
      );
      // A provider that could not stream still shows its answer live: all at once, when it arrives.
      if (opts.onDelta && streamed === 0 && r.text) opts.onDelta(r.text, label);
      const cost = r.costUsd ?? computeCost(r.usage, opts.target.contestant.pricing);
      rec.usage = addUsage(rec.usage, r.usage);
      rec.costUsd += cost;
      rec.apiCalls++;
      if (rec.firstTtftMs === null) rec.firstTtftMs = r.ttftMs;
      rec.generationMs += Math.max(1, r.totalMs);
      rec.lastStopReason = r.stopReason;
      rec.responseChars += r.text.length;
      rec.transcript.push({
        label,
        system: req.system,
        messages: stripImageData(req.messages),
        response: r.text,
        usage: r.usage,
        ttftMs: r.ttftMs,
        totalMs: r.totalMs,
        stopReason: r.stopReason,
        rawStopReason: r.rawStopReason,
        costUsd: cost,
        retries: r.retries,
        ...(r.outputLimitBy ? { outputLimitBy: r.outputLimitBy, maxOutputTokens: r.maxOutputTokensSent } : {}),
      });
      return { text: r.text, stopReason: r.stopReason, totalMs: r.totalMs, ttftMs: r.ttftMs, outputTokens: r.usage.outputTokens, ...(r.outputLimitBy ? { outputLimitBy: r.outputLimitBy } : {}) };
    } catch (err) {
      rec.transcript.push({
        label,
        system: req.system,
        messages: stripImageData(req.messages),
        response: '',
        usage: emptyUsage(),
        ttftMs: null,
        totalMs: err instanceof OutOfTimeError ? err.elapsedMs : Date.now() - started,
        stopReason: 'other',
        rawStopReason: err instanceof OutOfTimeError ? 'out_of_time' : 'error',
        costUsd: 0,
        retries: 0,
        error: (err as Error).message,
      });
      throw err;
    }
  };

  const chat = (system?: string): ChatSession => {
    const history: ChatMessage[] = [];
    return {
      get history() {
        return history as readonly ChatMessage[];
      },
      async send(userText, sendOpts) {
        history.push(sendOpts?.images?.length ? { role: 'user', content: userText, images: sendOpts.images } : { role: 'user', content: userText });
        const reply = await complete({ system, messages: history.slice(), maxOutputTokens: sendOpts?.maxOutputTokens, label: sendOpts?.label });
        history.push({ role: 'assistant', content: reply.text });
        return reply;
      },
    };
  };

  rec.handle = { complete, chat };
  // Image generation (The Gallery Masterpiece) for providers that have an image API.
  if (opts.target.adapter.generateImage) rec.handle.generateImage = recordedImageCall(rec, opts, () => `call ${++callNo}`);
  return rec;
}
