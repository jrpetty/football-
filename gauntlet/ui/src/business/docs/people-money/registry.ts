/**
 * People and money: the Business tests drawn as the paperwork a business owner knows (CVs, invoices with their
 * purchase orders and delivery notes, payslips, expense receipts, bank statements, board KPIs, a stock list),
 * with the model's decisions stamped on them. Keyed by test id; these entries win over the generic
 * `extraction` / `business` pictures of components/viz/caseVisuals.tsx and draw the Level close-up's beats.
 */
import type { DocEntry } from './types.ts';
import { CV_READ, CV_SHORTLIST } from './cv/entries.tsx';
import { INVOICES_LADDER, INVOICE_MATCHING } from './ap/entries.tsx';
import { INVOICE_READ } from './invoice/entries.tsx';
import { DELIVERY_NOTE } from './delivery/entries.tsx';
import { PAYROLL_CHECK, PAYROLL_LADDER } from './payroll/entries.tsx';
import { EXPENSES_LADDER, EXPENSE_AUDIT } from './expenses/entries.tsx';
import { BANK_REC, REC_LADDER } from './bankrec/entries.tsx';
import { BOARD_REPORT, STOCK_REORDER } from './ops/entries.tsx';

export type { DocEntry } from './types.ts';

export const PEOPLE_MONEY_VISUALS: Record<string, DocEntry> = {
  'work.cv-read': CV_READ,
  'work.cv-shortlist': CV_SHORTLIST,
  'work.invoice-read': INVOICE_READ,
  'work.invoice-matching': INVOICE_MATCHING,
  'work.invoices-ladder': INVOICES_LADDER,
  'work.delivery-note': DELIVERY_NOTE,
  'work.payroll-check': PAYROLL_CHECK,
  'work.payroll-ladder': PAYROLL_LADDER,
  'work.expense-audit': EXPENSE_AUDIT,
  'work.expenses-ladder': EXPENSES_LADDER,
  'work.bank-reconciliation': BANK_REC,
  'work.reconciliation-ladder': REC_LADDER,
  'work.board-report': BOARD_REPORT,
  'work.stock-reorder': STOCK_REORDER,
};
