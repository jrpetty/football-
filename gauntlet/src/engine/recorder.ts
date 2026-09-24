import { computeCost, emptyUsage, addUsage } from '../core/cost.ts';
import type { ChatMessage, ChatSession, CompletionRequest, CompletionResult, Contestant, ModelHandle, ModelReply, TokenUsage, TranscriptEntry } from '../core/types.ts';
import { ProviderError, type ProviderAdapter } from '../providers/index.ts';
import type { Semaphore } from './semaphore.ts';
import { stripImageData } from '../core/vision.ts';

export interface CallPolicy {
  maxRetries: number;
  temperature: number;
  defaultMaxOutputTokens: number;
}

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
): Promise<CompletionResult> {
  const cap = target.contestant.options?.maxOutputTokensCap;
  const maxOutputTokens = cap ? Math.min(cap, req.maxOutputTokens) : req.maxOutputTokens;
  let attempt = 0;
  for (;;) {
    const release = await target.semaphore.acquire(signal);
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
      const r = await target.adapter.complete({ ...req, maxOutputTokens, signal: limiter?.signal ?? signal });
      // Adapters that cannot be interrupted still get judged on their measured time.
      if (attemptLimitMs && (late || r.totalMs > attemptLimitMs)) throw new OutOfTimeError(attemptLimitMs, Math.max(r.totalMs, Date.now() - sentAt));
      return { ...r, retries: attempt };
    } catch (err) {
      if (err instanceof OutOfTimeError) throw err;
      if (late && !signal.aborted) throw new OutOfTimeError(attemptLimitMs!, Date.now() - sentAt);
      if (signal.aborted) throw err;
      const retryable = err instanceof ProviderError ? err.retryable : false;
      if (!retryable || attempt >= policy.maxRetries) throw err;
      const base = err instanceof ProviderError && err.retryAfterMs !== undefined ? err.retryAfterMs : Math.min(60_000, 1500 * 2 ** attempt);
      const wait = Math.round(base + Math.random() * 500);
      onRetry?.(attempt + 1, wait, err as Error);
      release();
      await sleep(wait, signal);
      attempt++;
      continue;
    } finally {
      if (timer) clearTimeout(timer);
      signal.removeEventListener('abort', onOuter);
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
    try {
      const r = await callWithRetry(
        opts.target,
        {
          system: req.system,
          messages: req.messages,
          maxOutputTokens: req.maxOutputTokens ?? opts.maxOutputTokens,
          temperature: opts.policy.temperature,
          onDelta: opts.onDelta ? (t) => opts.onDelta!(t, label) : undefined,
          callContext: opts.callContext ? { ...opts.callContext, label } : undefined,
        },
        opts.policy,
        opts.signal,
        (attempt, wait, err) => {
          rec.retries++;
          opts.onRetry?.(attempt, wait, err);
        },
        opts.attemptLimitMs,
      );
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
      });
      return { text: r.text, stopReason: r.stopReason, totalMs: r.totalMs, ttftMs: r.ttftMs, outputTokens: r.usage.outputTokens };
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
  return rec;
}
