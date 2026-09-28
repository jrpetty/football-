import { loadSettings } from '../core/config.ts';
import { formatMoney, normalizeCurrency, toUsd, CURRENCIES } from '../core/currency.ts';
import { checkBudgetInput } from './budget.ts';
import { budgetStatus, loadBudget } from './spend.ts';
import { saveBudget } from './routes.ts';

/**
 * `node src/cli.ts budget`                               this month's spend, what is left, next reset, recent spending
 * `node src/cli.ts budget set monthly 50`                 monthly budget in your display currency (pounds by default)
 * `node src/cli.ts budget set run 10`                     default whole-run limit (pre-selected in New Run and Arena)
 * `node src/cli.ts budget set per-answer 2`               default per-answer limit
 * `node src/cli.ts budget set hard-stop on|off`           block runs and tournaments once the month's budget is used up
 * `node src/cli.ts budget set monthly off`                clear a setting ("off" or "none")
 */

const tty = process.stdout.isTTY && !process.env.NO_COLOR;
const green = (s: string) => (tty ? `\x1b[32m${s}\x1b[39m` : s);
const yellow = (s: string) => (tty ? `\x1b[33m${s}\x1b[39m` : s);
const red = (s: string) => (tty ? `\x1b[31m${s}\x1b[39m` : s);
const dim = (s: string) => (tty ? `\x1b[2m${s}\x1b[22m` : s);
const bold = (s: string) => (tty ? `\x1b[1m${s}\x1b[22m` : s);

const FIELDS: Record<string, 'monthlyUsd' | 'defaultRunUsd' | 'defaultPerAnswerUsd'> = {
  monthly: 'monthlyUsd',
  month: 'monthlyUsd',
  run: 'defaultRunUsd',
  'run-limit': 'defaultRunUsd',
  'per-answer': 'defaultPerAnswerUsd',
  answer: 'defaultPerAnswerUsd',
};

function show(): void {
  const s = budgetStatus();
  const cur = s.currency;
  const m = (usd: number) => formatMoney(usd, cur);
  const set = s.settings;
  console.log(bold('My budget') + dim(`  (${s.month.label}; resets on ${s.month.nextResetLabel})`));
  if (set.monthlyUsd !== undefined) {
    const pct = Math.round((s.fraction ?? 0) * 100);
    const paint = s.tone === 'over' ? red : s.tone === 'warn' ? yellow : green;
    const width = 30;
    const filled = Math.min(width, Math.round((s.fraction ?? 0) * width));
    console.log(`  ${paint('█'.repeat(filled))}${dim('░'.repeat(width - filled))}  ${paint(`${m(s.spentUsd)} of ${m(set.monthlyUsd)}`)} spent (${pct}%)`);
    console.log(`  Left this month: ${bold(m(Math.max(0, s.remainingUsd ?? 0)))}${s.committedUsd > 0 ? dim(` (${m(s.committedUsd)} of it reserved by running jobs)`) : ''}`);
    console.log(`  Hard stop: ${set.hardStop ? 'on (nothing starts once the budget is used up; a run’s limit is lowered to what is left)' : 'off (warnings only)'}`);
    if (s.blocked) console.log(red(`  The budget is used up: runs and tournaments cannot start until ${s.month.nextResetLabel}.`));
  } else {
    console.log(`  Spent this month: ${bold(m(s.spentUsd))}  ${dim('(no monthly budget set)')}`);
  }
  console.log(`  Default run limit: ${set.defaultRunUsd !== undefined ? m(set.defaultRunUsd) : dim('none')}   Default per-answer limit: ${set.defaultPerAnswerUsd !== undefined ? m(set.defaultPerAnswerUsd) : dim('off')}`);
  if (s.items.length) {
    console.log(bold('\nRecent spending'));
    for (const i of s.items.slice(0, 12)) {
      const kind = i.kind === 'run' ? 'Run' : i.kind === 'arena' ? 'Arena' : i.kind === 'grade' ? 'Judges' : i.kind === 'polish' ? 'Script' : 'Other';
      console.log(`  ${i.at.slice(0, 10)}  ${kind.padEnd(7)} ${m(i.spentUsd).padStart(9)}  ${i.name}${i.active ? dim(' (running)') : ''}`);
    }
  }
  console.log(dim('\nChange it: node src/cli.ts budget set monthly 50   (or open the dashboard → Budget)'));
}

export async function budgetCommand(positional: string[]): Promise<void> {
  const [sub, what, value] = positional;
  if (!sub || sub === 'show') return show();

  if (sub === 'set' || sub === 'clear') {
    const cur = normalizeCurrency(loadSettings().currency);
    const sym = CURRENCIES[cur.code].symbol;
    let patch: Record<string, unknown>;
    if (what === 'hard-stop' || what === 'hardstop') {
      const on = sub === 'set' && /^(on|yes|true|1)$/i.test(value ?? '');
      if (sub === 'set' && !/^(on|off|yes|no|true|false|1|0)$/i.test(value ?? '')) {
        console.error('Usage: budget set hard-stop on|off');
        process.exit(1);
      }
      patch = { hardStop: on };
    } else {
      const field = FIELDS[what ?? ''];
      if (!field) {
        console.error(`Unknown setting "${what ?? ''}". One of: monthly, run, per-answer, hard-stop`);
        process.exit(1);
      }
      if (sub === 'clear' || /^(off|none|no)$/i.test(value ?? '')) {
        patch = { [field]: null, ...(field === 'monthlyUsd' ? { hardStop: false } : {}) };
      } else {
        const amount = Number(String(value ?? '').replace(/[£$€,\s]/g, ''));
        if (!(Number.isFinite(amount) && amount > 0)) {
          console.error(`Give an amount in ${CURRENCIES[cur.code].name}, e.g. budget set ${what} 50  (or "off" to clear it)`);
          process.exit(1);
        }
        patch = { [field]: Math.round(toUsd(amount, cur) * 1e6) / 1e6 };
        console.log(dim(`${sym}${amount} = $${toUsd(amount, cur).toFixed(2)} at ${sym}1 = $${cur.usdPerUnit.toFixed(2)}`));
      }
    }
    const next = checkBudgetInput(patch, loadBudget());
    if (typeof next === 'string') {
      console.error(red(next));
      process.exit(1);
    }
    saveBudget(next);
    console.log(green('Saved.'));
    show();
    return;
  }

  console.error('Usage: budget [show] | budget set monthly|run|per-answer <amount|off> | budget set hard-stop on|off');
  process.exit(1);
}
