import { test } from 'node:test';
import assert from 'node:assert/strict';
import { CURRENCIES, DEFAULT_CURRENCY, formatMoney, fromUsd, normalizeCurrency, rateLabel, toUsd } from '../src/core/currency.ts';
import { MIN_AFFORDABLE_OUTPUT, SpendGuard, SpendLimitError, affordableOutputTokens, checkedLimits, describeLimits, estimateInputTokens, outputAllowance, perAnswerOutputTokens, worstCaseUsd } from '../src/engine/spend-guard.ts';
import { callWithRetry, effectiveMaxOutputTokens, resolveOutputBudget, type CallTarget } from '../src/engine/recorder.ts';
import { Semaphore } from '../src/engine/semaphore.ts';
import { checkCurrencyInput } from '../src/server/money-routes.ts';
import { loadContestants, loadSettings } from '../src/core/config.ts';
import type { CompletionRequest, Contestant } from '../src/core/types.ts';

const PRICE = { inputPerM: 5, outputPerM: 25 };

test('currency: pounds by default, editable rate, conversion both ways', () => {
  assert.equal(DEFAULT_CURRENCY.code, 'GBP');
  assert.equal(loadSettings().currency?.code, 'GBP', 'the owner sees pounds out of the box');
  const gbp = { code: 'GBP' as const, usdPerUnit: 1.25 };
  assert.equal(toUsd(10, gbp), 12.5, '£10 is $12.50 at £1 = $1.25');
  assert.equal(fromUsd(12.5, gbp), 10);
  assert.equal(formatMoney(12.5, gbp), '£10.00');
  assert.equal(formatMoney(0.05, gbp), '£0.040');
  assert.equal(formatMoney(2500, gbp), '£2,000');
  assert.equal(formatMoney(3, { code: 'USD', usdPerUnit: 1 }), '$3.00');
  assert.equal(rateLabel(gbp), '£1 = $1.25');
  assert.equal(rateLabel({ code: 'USD', usdPerUnit: 1 }), '');
  // Bad stored values fall back to a working default instead of breaking every cost on screen.
  assert.equal(normalizeCurrency({ code: 'GBP', usdPerUnit: -3 }).usdPerUnit, CURRENCIES.GBP.defaultUsdPerUnit);
  assert.equal(normalizeCurrency({ code: 'XYZ' as 'GBP' }).code, 'GBP');
  assert.equal(normalizeCurrency({ code: 'USD', usdPerUnit: 9 }).usdPerUnit, 1);
  assert.deepEqual(checkCurrencyInput({ code: 'GBP', usdPerUnit: 1.2712345 }, '2026-09-28'), { code: 'GBP', usdPerUnit: 1.2712, rateDate: '2026-09-28' });
  assert.match(checkCurrencyInput({ code: 'GBP', usdPerUnit: 'lots' }) as string, /usdPerUnit/);
  assert.match(checkCurrencyInput({ code: 'JPY', usdPerUnit: 1 }) as string, /GBP, USD or EUR/);
});

test('budget clamp math: what the money left buys', () => {
  // $1 at $5/M in and $25/M out, after a 10k-token prompt ($0.05): $0.95 buys 38,000 output tokens.
  assert.equal(affordableOutputTokens(1, 10_000, PRICE), 38_000);
  assert.equal(affordableOutputTokens(0.01, 10_000, PRICE), 0, 'cannot even pay for the prompt');
  assert.ok(Math.abs(worstCaseUsd(10_000, 38_000, PRICE) - 1) < 1e-9);
  // Cache writes can cost more than plain input: the worst case uses the higher rate.
  assert.equal(affordableOutputTokens(1, 10_000, { ...PRICE, cacheWritePerM: 10 }), 36_000);
  // Per-answer cap: £2 at £1 = $1.25 is $2.50; a cheaper model gets more tokens for the same money.
  assert.equal(perAnswerOutputTokens(2.5, 4000, PRICE), 99_200);
  assert.equal(perAnswerOutputTokens(2.5, 4000, { inputPerM: 1, outputPerM: 5 }), 499_200);
  assert.ok(estimateInputTokens('abc', [{ role: 'user', content: 'x'.repeat(300), images: [{ name: 'a', mediaType: 'image/png', data: '', bytes: 0 }] as never }]) >= 101 + 2000, 'prompt estimate is conservative and counts pictures');
});

test('max-output clamp: "model-max", the fair same-for-all choice and the per-answer cap', () => {
  const opus = { maxOutputTokens: 128_000, pricing: PRICE };
  const small = { maxOutputTokens: 16_384, pricing: PRICE };
  assert.equal(resolveOutputBudget('model-max', opus, 16000), 128_000);
  assert.equal(resolveOutputBudget('model-max', opus, 16000, 64_000), 64_000, 'same token limit for every model');
  assert.equal(effectiveMaxOutputTokens(small, resolveOutputBudget('model-max', small, 16000, 64_000)), 16_384, 'a model’s own lower maximum still applies');
  assert.equal(resolveOutputBudget(4000, opus, 16000, 64_000), 4000, 'fixed-budget tests are untouched');
  const base = { requested: 'model-max' as const, defaultMaxOutputTokens: 16000, inputTokens: 4000 };
  assert.deepEqual(outputAllowance({ ...base, contestant: opus }), { tokens: 128_000, by: 'model-max' });
  assert.deepEqual(outputAllowance({ ...base, contestant: opus, limits: { sameOutputTokens: 64_000 } }), { tokens: 64_000, by: 'same-tokens' });
  assert.deepEqual(outputAllowance({ ...base, contestant: small, limits: { sameOutputTokens: 64_000 } }), { tokens: 16_384, by: 'model-max' });
  assert.deepEqual(outputAllowance({ ...base, contestant: opus, limits: { perAnswerUsd: 1 } }), { tokens: 39_200, by: 'per-answer' });
  assert.deepEqual(outputAllowance({ ...base, contestant: opus, limits: { perAnswerUsd: 100 } }), { tokens: 128_000, by: 'model-max' }, 'a generous cap changes nothing');
  assert.deepEqual(checkedLimits({ perAnswerUsd: 2.5, sameOutputTokens: 64000 }), { perAnswerUsd: 2.5, sameOutputTokens: 64000 });
  assert.deepEqual(checkedLimits({ perAnswerUsd: null }), {});
  assert.throws(() => checkedLimits({ perAnswerUsd: -1 }), /positive/);
  assert.throws(() => checkedLimits({ sameOutputTokens: 12.5 }), /whole number/);
});

test('every configured model declares its own maximum output, with where the number came from', () => {
  for (const c of loadContestants().filter((x) => x.enabled && !['baseline', 'manual', 'mock'].includes(x.provider) && !x.imageOnly)) {
    if (!c.maxOutputTokens) continue;
    assert.ok(c.maxOutputTokens >= 4096, `${c.id}: ${c.maxOutputTokens}`);
    assert.ok(c.maxOutputTokensSource && c.maxOutputTokensSource.length > 10, `${c.id} says where its maximum comes from`);
  }
});

test('spend guard: calls running at the same time can never jointly exceed the cap', async () => {
  const g = new SpendGuard(10);
  const a = await g.reserve({ inputTokens: 0, maxOutputTokens: 200_000, pricing: PRICE }); // worst case $5
  const b = await g.reserve({ inputTokens: 0, maxOutputTokens: 200_000, pricing: PRICE }); // another $5
  assert.equal(a.clamped || b.clamped, false);
  assert.ok(g.remainingUsd < 1e-9, 'both worst cases are reserved');
  // A third call does not start short while others run: it waits for one to finish.
  let started = false;
  const c = g.reserve({ inputTokens: 0, maxOutputTokens: 200_000, pricing: PRICE }).then((r) => ((started = true), r));
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(started, false, 'waits instead of overspending');
  a.settle(1); // the first call only cost $1: $4 of its worst case comes back
  await new Promise((r) => setTimeout(r, 0));
  assert.equal(started, false, 'still not enough for the full request while another call runs');
  b.settle(1);
  const rc = await c;
  // Nothing else runs now: $8 left buys 320,000 tokens, more than requested.
  assert.equal(rc.maxOutputTokens, 200_000);
  assert.equal(rc.clamped, false);
  rc.settle(7.5);
  // $0.50 left: a big request is lowered to what it buys (20,000 tokens) instead of blowing past the cap.
  const d = await g.reserve({ inputTokens: 0, maxOutputTokens: 128_000, pricing: PRICE });
  assert.equal(d.maxOutputTokens, 20_000);
  assert.equal(d.clamped, true);
  d.settle(0.5);
  assert.ok(Math.abs(g.spentUsd - 10) < 1e-9);
  await assert.rejects(g.reserve({ inputTokens: 100, maxOutputTokens: 128_000, pricing: PRICE }), SpendLimitError);
  assert.equal(g.hit, true, 'the run knows to stop starting work');
});

test('spend guard: a call is refused when not even the minimum output is affordable', async () => {
  const g = new SpendGuard(1, 0.99);
  const min = (MIN_AFFORDABLE_OUTPUT * PRICE.outputPerM) / 1e6; // $0.05
  assert.ok(g.remainingUsd < min);
  await assert.rejects(g.reserve({ inputTokens: 0, maxOutputTokens: 64_000, pricing: PRICE, what: 'the next game' }), /Spend limit reached.*the next game/);
  // A small request that fits entirely is still allowed.
  const ok = await new SpendGuard(1, 0.99).reserve({ inputTokens: 0, maxOutputTokens: 300, pricing: PRICE });
  assert.equal(ok.maxOutputTokens, 300);
});

function fakeTarget(price = PRICE, text = 'hello'): { target: CallTarget; sent: number[] } {
  const sent: number[] = [];
  const contestant = { id: 'x', label: 'X', vendor: 'V', provider: 'p', model: 'm', color: '#000', enabled: true, maxOutputTokens: 128_000, pricing: price } as Contestant;
  return {
    sent,
    target: {
      contestant,
      semaphore: new Semaphore(4),
      adapter: {
        async complete(req: CompletionRequest) {
          sent.push(req.maxOutputTokens);
          const out = Math.min(req.maxOutputTokens, 1000);
          return { text, stopReason: out < 1000 ? 'max_tokens' : 'end', rawStopReason: 'x', usage: { inputTokens: 100, outputTokens: out, reasoningTokens: 0, cachedInputTokens: 0, cacheWriteTokens: 0 }, ttftMs: 1, totalMs: 2 };
        },
      },
    } as unknown as CallTarget,
  };
}

test('calls: the run limit lowers a call’s output limit and settles at the real cost; the per-answer cap labels its cut-offs', async () => {
  const policy = { maxRetries: 0, temperature: 0, defaultMaxOutputTokens: 16000 };
  const { target, sent } = fakeTarget();
  const guard = new SpendGuard(0.5);
  const r = await callWithRetry(target, { messages: [{ role: 'user', content: 'hi' }], maxOutputTokens: 128_000, temperature: 0 }, { ...policy, spend: guard }, new AbortController().signal);
  assert.ok(sent[0]! < 128_000 && sent[0]! >= 19_000, `sent ${sent[0]}`);
  assert.equal(r.outputLimitBy, 'spend-limit');
  assert.ok(Math.abs(guard.spentUsd - (100 * 5 + 1000 * 25) / 1e6) < 1e-12, 'settled at the billed cost, not the worst case');
  assert.equal(guard.inFlight, 0);
  const small = fakeTarget();
  const p = await callWithRetry(small.target, { messages: [{ role: 'user', content: 'hi' }], maxOutputTokens: 128_000, temperature: 0 }, { ...policy, perAnswerUsd: 0.01 }, new AbortController().signal);
  assert.equal(small.sent[0], 389, '$0.01 buys 389 output tokens after a (conservatively counted) 51-token prompt');
  assert.equal(p.stopReason, 'max_tokens');
  assert.equal(p.outputLimitBy, 'per-answer');
  const free = fakeTarget();
  const q = await callWithRetry(free.target, { messages: [{ role: 'user', content: 'hi' }], maxOutputTokens: 128_000, temperature: 0 }, policy, new AbortController().signal);
  assert.equal(free.sent[0], 128_000, 'no limits: the model’s full maximum');
  assert.equal(q.outputLimitBy, undefined);
});

test('the recorded limits read as one plain line for the run page and the Presenter', () => {
  assert.equal(describeLimits(undefined), 'output: each model’s own maximum · no run spend limit');
  const gbp = (u: number) => formatMoney(u, { code: 'GBP', usdPerUnit: 1.25 });
  assert.equal(describeLimits({ maxCostUsd: 12.5, perAnswerUsd: 2.5 }, gbp), 'output: each model’s own maximum · per-answer spend limit £2.00 (token allowance differs by model price) · run spend limit £10.00');
  assert.match(describeLimits({ sameOutputTokens: 64000 }), /same output limit for every model: 64,000 tokens/);
});
