/**
 * Every Business test drawn as its own paperwork, by test id: people and money (CVs, invoices, payslips,
 * expenses, bank statements, board KPIs, stock) and customers, comms and data (inbox, helpdesk, contracts,
 * customer list, spreadsheet, SQL, van routes, rota). The ONE lookup the app consults: the case-visual registry
 * (Result Inspector, Presenter, jobs page) and the close-up's beats.
 */
import type { DocEntry } from './people-money/types.ts';
import { PEOPLE_MONEY_VISUALS } from './people-money/registry.ts';
import { CUSTOMERS_DATA_VISUALS } from './customers-data/registry.ts';

export type { DocEntry };

export const BUSINESS_DOC_VISUALS: Record<string, DocEntry> = { ...PEOPLE_MONEY_VISUALS, ...(CUSTOMERS_DATA_VISUALS as Record<string, DocEntry>) };

export function docVisualFor(testId: string): DocEntry | null {
  return Object.prototype.hasOwnProperty.call(BUSINESS_DOC_VISUALS, testId) ? BUSINESS_DOC_VISUALS[testId]! : null;
}
