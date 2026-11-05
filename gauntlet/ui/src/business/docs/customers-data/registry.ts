/**
 * The customers, comms and data Business tests drawn as their real paperwork (inbox, helpdesk, contract pages,
 * customer cards, spreadsheet, SQL, van map, rota). Test id → entry; caseVisuals.tsx gives these entries
 * precedence over the generic extraction/business pictures, and the close-ups use their beats.
 */
import type { DocVisual } from './parts.tsx';
import { EMAIL_ACTIONS, INBOX_TRIAGE } from './Inbox.tsx';
import { CONTRACT_LADDER, CONTRACT_REDLINE } from './Contract.tsx';
import { CRM_DEDUPE, CRM_LADDER } from './Crm.tsx';
import { MEETING_SCHEDULER } from './Meeting.tsx';
import { DELIVERY_ROUTES, SHIFT_ROTA } from './Plans.tsx';
import { SHEET_LOOKUP } from './Sheet.tsx';
import { SQL_HARD, SQL_LADDER, SQL_QUESTIONS } from './Sql.tsx';
import { SUPPORT_LADDER, SUPPORT_REFUNDS, SUPPORT_REPLIES, TICKET_TAG } from './Support.tsx';

export const CUSTOMERS_DATA_VISUALS: Record<string, DocVisual> = {
  'work.inbox-triage': INBOX_TRIAGE,
  'work.email-actions': EMAIL_ACTIONS,
  'work.meeting-scheduler': MEETING_SCHEDULER,
  'work.ticket-tag': TICKET_TAG,
  'work.support-refunds': SUPPORT_REFUNDS,
  'work.support-ladder': SUPPORT_LADDER,
  'work.support-replies': SUPPORT_REPLIES,
  'work.contract-redline': CONTRACT_REDLINE,
  'work.contract-ladder': CONTRACT_LADDER,
  'work.crm-dedupe': CRM_DEDUPE,
  'work.crm-ladder': CRM_LADDER,
  'work.sheet-lookup': SHEET_LOOKUP,
  'work.sql-questions': SQL_QUESTIONS,
  'work.sql-hard': SQL_HARD,
  'work.sql-ladder': SQL_LADDER,
  'work.delivery-routes': DELIVERY_ROUTES,
  'work.shift-rota': SHIFT_ROTA,
};
