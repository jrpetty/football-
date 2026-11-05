import type { DocEntry } from '../types.ts';
import { expensesQuestion } from '../model/expenses.ts';
import { ExpensesBeats, ExpensesInline } from './Expenses.tsx';

const caption = 'Each expense claim as a claim form with its receipt slips; the model’s flag on every line (rule broken, amount to pay) against the correct one, and what each person should be paid.';

export const EXPENSE_AUDIT: DocEntry = {
  family: 'extraction',
  caption,
  build: (input) => (expensesQuestion(input.turns[0] ?? '') ? input : null),
  render: (_m, mode, input) => <ExpensesInline input={input} mode={mode} />,
  Beats: ExpensesBeats,
};

export const EXPENSES_LADDER: DocEntry = { ...EXPENSE_AUDIT, family: 'business' };
