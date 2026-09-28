import { emptyUsage, addUsage } from '../core/cost.ts';
import { imageCallCost } from '../core/image-output.ts';
import type { CompletionRequest, ImageGenResult, ImageReply } from '../core/types.ts';
import { ProviderError } from '../providers/index.ts';
import type { CallPolicy, CallTarget, CaseRecorder } from './recorder.ts';
import { SpendLimitError } from './spend-guard.ts';

/**
 * ModelHandle.generateImage for one case: the harness's uniform retry policy (429 / 5xx / network back off,
 * honouring Retry-After), then the call is recorded in the transcript with its per-image cost. The picture bytes
 * are not stored in the transcript; the program saves them as an artifact.
 */

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

export async function imageWithRetry(
  target: CallTarget,
  req: { prompt: string; aspectRatio: string; callContext?: CompletionRequest['callContext'] },
  policy: CallPolicy,
  signal: AbortSignal,
  onRetry?: (attempt: number, waitMs: number, err: Error) => void,
): Promise<ImageGenResult & { retries: number }> {
  const gen = target.adapter.generateImage;
  if (!gen) throw new Error(`${target.contestant.label} has no image-generation API in Gauntlet (supported: OpenAI-compatible images, Gemini, Manual, Random Baseline)`);
  let attempt = 0;
  for (;;) {
    const release = await target.semaphore.acquire(signal);
    try {
      const r = await gen.call(target.adapter, { ...req, signal });
      return { ...r, retries: attempt };
    } catch (err) {
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
      release();
    }
  }
}

const mb = (bytes: number) => `${(bytes / 1e6).toFixed(1)} MB`;

export function recordedImageCall(
  rec: CaseRecorder,
  opts: {
    target: CallTarget;
    policy: CallPolicy;
    signal: AbortSignal;
    callContext?: Omit<NonNullable<CompletionRequest['callContext']>, 'label'>;
    onCall?: (label: string) => void;
    onRetry?: (attempt: number, waitMs: number, err: Error) => void;
  },
  nextLabel: () => string,
): (req: { prompt: string; aspectRatio?: string; label?: string }) => Promise<ImageReply> {
  return async (req) => {
    const label = req.label ?? nextLabel();
    opts.onCall?.(label);
    const started = Date.now();
    const messages = [{ role: 'user' as const, content: req.prompt }];
    try {
      // A spend limit: pictures are billed per image, so a picture is only started while money is left.
      if (opts.policy.spend && !opts.policy.spend.hasRoom()) {
        opts.policy.spend.hit = true;
        throw new SpendLimitError(`Spend limit reached: $${opts.policy.spend.spentUsd.toFixed(2)} of $${opts.policy.spend.capUsd.toFixed(2)} spent. Resume with a higher limit to finish.`);
      }
      const r = await imageWithRetry(
        opts.target,
        { prompt: req.prompt, aspectRatio: req.aspectRatio ?? '3:2', callContext: opts.callContext ? { ...opts.callContext, label } : undefined },
        opts.policy,
        opts.signal,
        (attempt, wait, err) => {
          rec.retries++;
          opts.onRetry?.(attempt, wait, err);
        },
      );
      const cost = imageCallCost(opts.target.contestant, r);
      opts.policy.spend?.addSpent(cost);
      const img = r.images[0] ?? null;
      rec.usage = addUsage(rec.usage, r.usage);
      rec.costUsd += cost;
      rec.apiCalls++;
      rec.generationMs += Math.max(1, r.totalMs);
      rec.lastStopReason = r.stopReason;
      const said = img ? `[picture · ${img.width}×${img.height} ${img.mediaType === 'image/png' ? 'PNG' : 'JPEG'} · ${mb(img.bytes)}${r.size ? ` · requested ${r.size}` : ''}${r.quality ? ` ${r.quality}` : ''}]` : '[no picture returned]';
      rec.transcript.push({
        label,
        messages,
        response: r.text ? `${said}\n${r.text}` : said,
        usage: r.usage,
        ttftMs: null,
        totalMs: r.totalMs,
        stopReason: r.stopReason,
        rawStopReason: r.rawStopReason,
        costUsd: cost,
        retries: r.retries,
      });
      return { image: img, text: r.text, stopReason: r.stopReason, totalMs: r.totalMs, costUsd: cost };
    } catch (err) {
      rec.transcript.push({
        label,
        messages,
        response: '',
        usage: emptyUsage(),
        ttftMs: null,
        totalMs: Date.now() - started,
        stopReason: 'other',
        rawStopReason: 'error',
        costUsd: 0,
        retries: 0,
        error: (err as Error).message,
      });
      throw err;
    }
  };
}
