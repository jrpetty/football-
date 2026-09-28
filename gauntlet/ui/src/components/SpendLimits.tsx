/**
 * Spend limits in the owner's currency (pounds by default): the whole-run limit presets (No limit, £5, £10, £30,
 * £50, Custom), the optional per-answer limit, the fair "same token limit for every model" choice, and the
 * exchange-rate line ("£1 = $1.33 · edit"). Amounts are typed in the display currency and sent to the API in USD.
 */
import { useState } from 'react';
import { api } from '../api.ts';
import { useMeta, useToast } from '../context.tsx';
import { CURRENCIES, type CurrencyCode } from '../../../src/core/currency.ts';
import { currencySymbol, currentRateLabel, displayCurrency, displayToUsd, money, usdToDisplay } from '../money.ts';
import { cx } from './ui.tsx';
import { fmtCost } from '../format.ts';
import type { RunLimits } from '../types.ts';
import { Icon } from './icons.tsx';
import './spend-limits.css';

/** "£1 = $1.33 · edit": the editable exchange rate. Nothing is fetched online; the owner types the rate. */
export function CurrencyRate({ compact }: { compact?: boolean }) {
  const { reload } = useMeta();
  const toast = useToast();
  const cur = displayCurrency();
  const [open, setOpen] = useState(false);
  const [code, setCode] = useState<CurrencyCode>(cur.code);
  const [rate, setRate] = useState(String(cur.usdPerUnit));
  const [saving, setSaving] = useState(false);
  const rateNum = Number(rate);
  const valid = code === 'USD' || (Number.isFinite(rateNum) && rateNum > 0.01 && rateNum < 100);
  const save = async () => {
    setSaving(true);
    try {
      await api.setCurrency({ code, usdPerUnit: code === 'USD' ? 1 : rateNum });
      reload();
      setOpen(false);
      toast.success(code === 'USD' ? 'Showing costs in US dollars' : `Showing costs in ${CURRENCIES[code].name} at ${CURRENCIES[code].symbol}1 = $${rateNum.toFixed(2)}`);
    } catch (e) {
      toast.error(e as Error, 'Could not save the currency');
    } finally {
      setSaving(false);
    }
  };
  if (!open)
    return (
      <span className={cx('sl-rate', compact && 'compact')}>
        {cur.code === 'USD' ? 'Costs in US dollars' : currentRateLabel()}
        {cur.rateDate && cur.code !== 'USD' && <span className="muted"> (rate of {cur.rateDate})</span>}
        {' · '}
        <button type="button" className="sl-link" onClick={() => setOpen(true)}>
          edit
        </button>
      </span>
    );
  return (
    <span className="sl-rate-edit" role="group" aria-label="Display currency">
      <select className="input sm" value={code} onChange={(e) => setCode(e.target.value as CurrencyCode)} aria-label="Currency">
        {(Object.keys(CURRENCIES) as CurrencyCode[]).map((k) => (
          <option key={k} value={k}>
            {CURRENCIES[k].symbol} {CURRENCIES[k].name}
          </option>
        ))}
      </select>
      {code !== 'USD' && (
        <label className="row" style={{ gap: 6 }}>
          <span className="muted">{CURRENCIES[code].symbol}1 = $</span>
          <input className={cx('input sm tnum', !valid && 'invalid')} style={{ width: 80 }} value={rate} onChange={(e) => setRate(e.target.value)} inputMode="decimal" aria-label="US dollars per unit" />
        </label>
      )}
      <button type="button" className="btn sm primary" disabled={!valid || saving} onClick={save}>
        Save
      </button>
      <button type="button" className="btn sm ghost" onClick={() => setOpen(false)}>
        Cancel
      </button>
      <span className="muted sl-rate-hint">Type today’s rate from your bank or a currency site; Gauntlet never looks it up by itself.</span>
    </span>
  );
}

/**
 * A money limit picker: preset buttons plus a custom amount, in the display currency. `valueUsd` null = no limit.
 * Presets are in display units (e.g. [5, 10, 30, 50] for pounds).
 */
export function MoneyLimitPicker({
  valueUsd,
  onChange,
  presets,
  noneLabel = 'No limit',
  label,
  idBase,
}: {
  valueUsd: number | null;
  onChange: (usd: number | null) => void;
  presets: number[];
  noneLabel?: string;
  label: string;
  idBase: string;
}) {
  const sym = currencySymbol();
  const shown = valueUsd === null ? null : Math.round(usdToDisplay(valueUsd) * 100) / 100;
  const isPreset = shown !== null && presets.some((p) => Math.abs(p - shown) < 0.005);
  const [custom, setCustom] = useState(shown !== null && !isPreset);
  const [text, setText] = useState(shown !== null && !isPreset ? String(shown) : '');
  const num = Number(text);
  const bad = custom && text.trim() !== '' && !(Number.isFinite(num) && num > 0);
  return (
    <div className="sl-picker">
      <div className="seg" role="group" aria-label={label}>
        <button type="button" aria-pressed={valueUsd === null && !custom} onClick={() => (setCustom(false), onChange(null))}>
          {noneLabel}
        </button>
        {presets.map((p) => (
          <button key={p} type="button" className="tnum" aria-pressed={!custom && shown !== null && Math.abs(p - shown) < 0.005} onClick={() => (setCustom(false), onChange(displayToUsd(p)))}>
            {sym}
            {p}
          </button>
        ))}
        <button type="button" aria-pressed={custom} onClick={() => (setCustom(true), onChange(Number.isFinite(num) && num > 0 ? displayToUsd(num) : null))}>
          Custom
        </button>
      </div>
      {custom && (
        <label className="sl-custom" htmlFor={`${idBase}-custom`}>
          <span className="sl-sym">{sym}</span>
          <input
            id={`${idBase}-custom`}
            className={cx('input tnum', bad && 'invalid')}
            inputMode="decimal"
            placeholder="amount"
            value={text}
            onChange={(e) => {
              setText(e.target.value);
              const v = Number(e.target.value);
              onChange(Number.isFinite(v) && v > 0 ? displayToUsd(v) : null);
            }}
          />
          {valueUsd !== null && displayCurrency().code !== 'USD' && <span className="muted tnum">= ${valueUsd.toFixed(2)}</span>}
        </label>
      )}
    </div>
  );
}

export type OutputChoice = 'model-max' | 'same';

/** The New Run "Spending & output limits" block. */
export function SpendLimitsSection({
  capUsd,
  onCap,
  perAnswerUsd,
  onPerAnswer,
  outputChoice,
  onOutputChoice,
  sameTokens,
  onSameTokens,
  hasModelMaxTest,
}: {
  capUsd: number | null;
  onCap: (usd: number | null) => void;
  perAnswerUsd: number | null;
  onPerAnswer: (usd: number | null) => void;
  outputChoice: OutputChoice;
  onOutputChoice: (c: OutputChoice) => void;
  sameTokens: string;
  onSameTokens: (t: string) => void;
  hasModelMaxTest: boolean;
}) {
  const tokensNum = Number(sameTokens.replace(/[,\s]/g, ''));
  const tokensBad = outputChoice === 'same' && !(Number.isInteger(tokensNum) && tokensNum >= 256 && tokensNum <= 1_000_000);
  return (
    <div className="sl-section stack">
      <div className="sl-block">
        <div className="sl-head">
          <span className="label">
            <Icon.Dollar style={{ width: 13, height: 13 }} /> Spending limit for the whole run
          </span>
          <CurrencyRate compact />
        </div>
        <MoneyLimitPicker idBase="nr-cap" label="Spending limit for the whole run" valueUsd={capUsd} onChange={onCap} presets={[5, 10, 30, 50]} />
        <div className="hint">
          {capUsd === null
            ? 'No limit: the run finishes whatever it costs. Check the estimate on the right first.'
            : `Models and judges together never spend more than ${money(capUsd)}. Before every call Gauntlet reserves that call’s worst case; if the money left cannot pay for a reply, the run stops cleanly (“stopped: spend limit”), keeps every finished result, and can be resumed with a higher limit.`}
        </div>
      </div>
      <div className="sl-block">
        <div className="sl-head">
          <span className="label">Per-answer limit (optional)</span>
        </div>
        <MoneyLimitPicker idBase="nr-per" label="Per-answer spending limit" valueUsd={perAnswerUsd} onChange={onPerAnswer} presets={[0.5, 1, 2, 5]} noneLabel="Off" />
        <div className="hint">
          {perAnswerUsd === null
            ? 'Off: one reply may cost whatever the model’s maximum output costs.'
            : `No single reply may cost more than ${money(perAnswerUsd)}. Each model’s output limit becomes what that buys at its own price, shown per model in the estimate. A reply cut off by it is marked “stopped by your per-answer spend limit”.`}
        </div>
      </div>
      <div className="sl-block">
        <div className="sl-head">
          <span className="label">Output limit{hasModelMaxTest ? ' (The Game Jam and other “no limit” tests)' : ''}</span>
        </div>
        <div className="seg" role="group" aria-label="Output limit">
          <button type="button" aria-pressed={outputChoice === 'model-max'} onClick={() => onOutputChoice('model-max')}>
            Each model’s own maximum
          </button>
          <button type="button" aria-pressed={outputChoice === 'same'} onClick={() => onOutputChoice('same')}>
            Same token limit for every model
          </button>
        </div>
        {outputChoice === 'same' && (
          <label className="sl-custom" htmlFor="nr-same">
            <input id="nr-same" className={cx('input tnum', tokensBad && 'invalid')} inputMode="numeric" value={sameTokens} onChange={(e) => onSameTokens(e.target.value)} style={{ width: 120 }} />
            <span className="muted">output tokens per reply (a model whose own maximum is lower keeps its maximum)</span>
          </label>
        )}
        {(perAnswerUsd !== null || outputChoice === 'same') && (
          <div className={cx('sl-fair', perAnswerUsd !== null ? 'warn' : 'good')}>
            <Icon.Info style={{ width: 14, height: 14 }} />
            {perAnswerUsd !== null
              ? 'Fairness: a money limit gives models different token allowances (a cheap model gets more room than an expensive one). For a like-for-like comparison, turn the per-answer limit off and choose “Same token limit for every model”. The limits are recorded with the run and shown in the Presenter.'
              : 'Fair: every model gets the same output allowance. The limit is recorded with the run and shown in the Presenter.'}
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * The limits a run used, as chips (run page) or one line (Presenter methods line), so a video can disclose them.
 * Runs from before limits existed record none: they used each test's own output setting and at most a run cap.
 */
export function limitsSummary(limits: RunLimits | undefined, maxCostUsd: number | undefined): Array<{ text: string; tone: '' | 'warn' | 'good' }> {
  const out: Array<{ text: string; tone: '' | 'warn' | 'good' }> = [];
  if (!limits) out.push({ text: 'Output: each test’s own setting', tone: '' });
  else if (limits.sameOutputTokens) out.push({ text: `Same output limit for every model: ${limits.sameOutputTokens.toLocaleString('en-US')} tokens`, tone: 'good' });
  else out.push({ text: 'Output: each model’s own maximum', tone: '' });
  if (limits?.perAnswerUsd) out.push({ text: `Per-answer limit ${fmtCost(limits.perAnswerUsd)} (token room differs by model price)`, tone: 'warn' });
  const cap = maxCostUsd ?? limits?.maxCostUsd;
  out.push({ text: cap ? `Run spending limit ${fmtCost(cap)} (judges included)` : 'No run spending limit', tone: '' });
  return out;
}

export function RunLimitsChips({ limits, maxCostUsd }: { limits: RunLimits | undefined; maxCostUsd: number | undefined }) {
  return (
    <span className="run-limits">
      {limitsSummary(limits, maxCostUsd).map((c) => (
        <span key={c.text} className={cx('chip', c.tone)}>
          {c.text}
        </span>
      ))}
    </span>
  );
}

/** Parse the same-token text box (null when invalid). */
export function parseSameTokens(t: string): number | null {
  const n = Number(t.replace(/[,\s]/g, ''));
  return Number.isInteger(n) && n >= 256 && n <= 1_000_000 ? n : null;
}
