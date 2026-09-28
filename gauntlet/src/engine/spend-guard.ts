/**
 * Spend limits. The owner can say "this run may not spend more than £10" (a whole-run cap, which judges count
 * towards) and "any single answer may cost at most £2" (a per-answer cap). Both are off by default.
 *
 * The whole-run cap used to be checked only between cases, so one hour-long Game Jam reply could blow straight past
 * it. The SpendGuard closes that gap: before every paid call it works out the call's worst case (its prompt plus
 * its full output allowance, priced at the model's rates) and
 *  - reserves that amount, so calls running at the same time can never jointly exceed the cap;
 *  - when the worst case does not fit, first waits for calls already running to finish (they usually cost far less
 *    than their worst case, which frees room), and only then lowers the call's output allowance to what the money
 *    left can buy;
 *  - refuses to start a call when not even MIN_AFFORDABLE_OUTPUT tokens are affordable, throwing SpendLimitError.
 * The run then stops cleanly ("stopped: spend limit"), keeps every finished result, and can be resumed with a
 * higher cap.
 *
 * All amounts are US dollars (the unit providers bill in); the UI converts to pounds for display.
 */
import type { ChatMessage, Pricing, RunLimits } from '../core/types.ts';

/** A call is only started when the budget left buys its prompt plus at least this many output tokens (or its full request, if smaller). */
export const MIN_AFFORDABLE_OUTPUT = 2000;
/** Worst-case input tokens assumed per attached picture (a full-HD screenshot costs 1-2k tokens on every provider). */
export const IMAGE_TOKENS_WORST = 2000;

export class SpendLimitError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SpendLimitError';
  }
}

export function isSpendLimitError(err: unknown): boolean {
  return err instanceof SpendLimitError || (err as Error | null)?.name === 'SpendLimitError';
}

/** The price of one input token at worst (cache writes can cost more than plain input). */
function inputRate(p: Pricing): number {
  return Math.max(p.inputPerM, p.cacheWritePerM ?? 0);
}

/** Worst-case USD of a call: every prompt token at the highest input rate, every allowed output token at the output rate. */
export function worstCaseUsd(inputTokens: number, maxOutputTokens: number, p: Pricing): number {
  return (inputTokens * inputRate(p) + maxOutputTokens * p.outputPerM) / 1e6;
}

/** How many output tokens `usd` buys after paying for the prompt (0 when it cannot even pay for the prompt). */
export function affordableOutputTokens(usd: number, inputTokens: number, p: Pricing): number {
  const left = usd * 1e6 - inputTokens * inputRate(p);
  if (left < 0 || (left === 0 && inputTokens > 0)) return 0;
  if (!(p.outputPerM > 0)) return Number.MAX_SAFE_INTEGER;
  return Math.floor(left / p.outputPerM);
}

/**
 * Per-answer cap → an output-token limit for one model: what `perAnswerUsd` buys after a prompt of `inputTokens`.
 * A cheaper model gets more tokens for the same money (see METHODOLOGY: money caps are not token-fair).
 */
export function perAnswerOutputTokens(perAnswerUsd: number, inputTokens: number, p: Pricing): number {
  return affordableOutputTokens(perAnswerUsd, inputTokens, p);
}

/** A conservative (high) token count for a prompt: ~3 characters per token plus a flat amount per picture. */
export function estimateInputTokens(system: string | undefined, messages: ChatMessage[]): number {
  let chars = system?.length ?? 0;
  let images = 0;
  for (const m of messages) {
    chars += m.content.length;
    images += m.images?.length ?? 0;
  }
  return Math.ceil(chars / 3) + images * IMAGE_TOKENS_WORST + 50;
}

export interface Reservation {
  /** The output limit to send (may be lower than requested). */
  maxOutputTokens: number;
  /** True when the money left lowered it. */
  clamped: boolean;
  /** Turn the reservation into the call's real cost (0 for a failed call that was not billed). */
  settle(actualUsd: number): void;
}

export class SpendGuard {
  readonly capUsd: number;
  private spent: number;
  private readonly reserved = new Map<number, number>();
  private nextId = 1;
  private waiters: Array<() => void> = [];
  /** Set once a call was refused: the run should stop starting new work. */
  hit = false;

  constructor(capUsd: number, alreadySpentUsd = 0) {
    this.capUsd = capUsd;
    this.spent = alreadySpentUsd;
  }

  /** Money actually spent (settled calls, including what was spent before a resume). */
  get spentUsd(): number {
    return this.spent;
  }

  /** Worst-case cost of the calls running right now. */
  get reservedUsd(): number {
    let s = 0;
    for (const v of this.reserved.values()) s += v;
    return s;
  }

  get inFlight(): number {
    return this.reserved.size;
  }

  /** Money that no running call has claimed yet. */
  get remainingUsd(): number {
    return Math.max(0, this.capUsd - this.spent - this.reservedUsd);
  }

  /** Record spend that did not go through reserve() (e.g. a picture billed per image). */
  addSpent(usd: number): void {
    if (usd > 0) this.spent += usd;
    this.wake();
  }

  /** True when there is still money for at least a small call. */
  hasRoom(minUsd = 0): boolean {
    return this.remainingUsd > minUsd;
  }

  private wake(): void {
    const w = this.waiters;
    this.waiters = [];
    for (const f of w) f();
  }

  private waitForChange(signal?: AbortSignal): Promise<void> {
    return new Promise((resolve, reject) => {
      const onAbort = () => reject(Object.assign(new Error('Aborted'), { name: 'AbortError' }));
      if (signal?.aborted) return onAbort();
      signal?.addEventListener('abort', onAbort, { once: true });
      this.waiters.push(() => {
        signal?.removeEventListener('abort', onAbort);
        resolve();
      });
    });
  }

  /**
   * Claim the worst case of one call before it is sent. Waits while other calls are running if the full request does
   * not fit; with nothing else running, lowers the output limit to what is left, or throws SpendLimitError.
   */
  async reserve(o: { inputTokens: number; maxOutputTokens: number; pricing: Pricing; signal?: AbortSignal; what?: string }): Promise<Reservation> {
    const minOut = Math.min(o.maxOutputTokens, MIN_AFFORDABLE_OUTPUT);
    for (;;) {
      const affordable = affordableOutputTokens(this.remainingUsd, o.inputTokens, o.pricing);
      if (affordable >= o.maxOutputTokens) return this.claim(o.inputTokens, o.maxOutputTokens, o.pricing, false);
      // Others are running: their unused worst case comes back when they finish, so wait rather than start short.
      if (this.inFlight > 0) {
        await this.waitForChange(o.signal);
        continue;
      }
      if (affordable >= minOut) return this.claim(o.inputTokens, affordable, o.pricing, true);
      this.hit = true;
      throw new SpendLimitError(
        `Spend limit reached: $${this.spent.toFixed(2)} of $${this.capUsd.toFixed(2)} spent, and the rest cannot pay for ${o.what ?? 'the next call'} (its prompt plus at least ${minOut.toLocaleString('en-US')} output tokens). Resume with a higher limit to finish.`,
      );
    }
  }

  private claim(inputTokens: number, maxOutputTokens: number, p: Pricing, clamped: boolean): Reservation {
    const id = this.nextId++;
    this.reserved.set(id, worstCaseUsd(inputTokens, maxOutputTokens, p));
    let done = false;
    return {
      maxOutputTokens,
      clamped,
      settle: (actualUsd: number) => {
        if (done) return;
        done = true;
        this.reserved.delete(id);
        if (actualUsd > 0) this.spent += actualUsd;
        this.wake();
      },
    };
  }
}

export type { RunLimits };

/** Validate the optional per-run limits of a run request (throws a plain-English error). */
export function checkedLimits(l: { perAnswerUsd?: number | null; sameOutputTokens?: number | null } | undefined): { perAnswerUsd?: number; sameOutputTokens?: number } {
  const out: { perAnswerUsd?: number; sameOutputTokens?: number } = {};
  if (l?.perAnswerUsd !== undefined && l.perAnswerUsd !== null) {
    if (!(typeof l.perAnswerUsd === 'number' && l.perAnswerUsd > 0)) throw new Error('perAnswerUsd must be a positive number');
    out.perAnswerUsd = l.perAnswerUsd;
  }
  if (l?.sameOutputTokens !== undefined && l.sameOutputTokens !== null) {
    if (!(Number.isInteger(l.sameOutputTokens) && l.sameOutputTokens >= 256 && l.sameOutputTokens <= 1_000_000)) throw new Error('sameOutputTokens must be a whole number between 256 and 1,000,000');
    out.sameOutputTokens = l.sameOutputTokens;
  }
  return out;
}

/**
 * The output limit one model gets for one test under a run's limits: the test's request (its number, or the model's
 * own maximum, or the run's "same for every model" number), lowered to the model's own maximum and, with a
 * per-answer cap, to what that money buys after a prompt of `inputTokens`. `by` says which limit decided it.
 */
export function outputAllowance(o: {
  requested: number | 'model-max' | undefined;
  contestant: { maxOutputTokens?: number; options?: { maxOutputTokensCap?: number }; pricing: Pricing };
  defaultMaxOutputTokens: number;
  limits?: { perAnswerUsd?: number; sameOutputTokens?: number };
  inputTokens: number;
}): { tokens: number; by: 'test' | 'model-max' | 'same-tokens' | 'per-answer' } {
  const c = o.contestant;
  let by: 'test' | 'model-max' | 'same-tokens' | 'per-answer' = o.requested === 'model-max' ? (o.limits?.sameOutputTokens ? 'same-tokens' : 'model-max') : 'test';
  let n: number;
  if (o.requested === 'model-max') n = o.limits?.sameOutputTokens ?? (c.maxOutputTokens && c.maxOutputTokens > 0 ? c.maxOutputTokens : 65_536);
  else n = o.requested ?? o.defaultMaxOutputTokens;
  const cap = c.options?.maxOutputTokensCap;
  if (cap && cap < n) n = cap;
  if (c.maxOutputTokens && c.maxOutputTokens > 0 && c.maxOutputTokens < n) {
    n = c.maxOutputTokens;
    if (by === 'same-tokens') by = 'model-max';
  }
  if (o.limits?.perAnswerUsd) {
    const m = Math.max(256, perAnswerOutputTokens(o.limits.perAnswerUsd, o.inputTokens, c.pricing));
    if (m < n) {
      n = m;
      by = 'per-answer';
    }
  }
  return { tokens: n, by };
}

/** One plain-English line describing a run's limits, for the run page and the Presenter's methods line. */
export function describeLimits(l: RunLimits | undefined, money: (usd: number) => string = (u) => `$${u.toFixed(2)}`): string {
  const parts: string[] = [];
  if (l?.sameOutputTokens) parts.push(`same output limit for every model: ${l.sameOutputTokens.toLocaleString('en-US')} tokens`);
  else parts.push('output: each model’s own maximum');
  if (l?.perAnswerUsd) parts.push(`per-answer spend limit ${money(l.perAnswerUsd)} (token allowance differs by model price)`);
  parts.push(l?.maxCostUsd ? `run spend limit ${money(l.maxCostUsd)}` : 'no run spend limit');
  return parts.join(' · ');
}
