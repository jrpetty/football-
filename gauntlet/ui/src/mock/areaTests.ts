/**
 * Demo mode (?mock=1): every Business (`work.*`) and Everyday Life (`life.*`) test from the real test library,
 * loaded as-is so the demo Business pages, Everyday Life, the Test Library and the Cost Planner show the real tests.
 * The scores the demo shows for them are invented (see areaDemo.ts); the tests themselves are the shipped ones.
 */
import type { TestDefinition } from '../types.ts';
import wAccountsClerk from '../../../tests/work/accounts-clerk.json';
import wAnalystAgent from '../../../tests/work/analyst-agent.json';
import wAssistant from '../../../tests/work/assistant.json';
import wAssistantHard from '../../../tests/work/assistant-hard.json';
import wBankReconciliation from '../../../tests/work/bank-reconciliation.json';
import wBoardReport from '../../../tests/work/board-report.json';
import wContractRedline from '../../../tests/work/contract-redline.json';
import wCrmDedupe from '../../../tests/work/crm-dedupe.json';
import wCvRead from '../../../tests/work/cv-read.json';
import wCvShortlist from '../../../tests/work/cv-shortlist.json';
import wDeliveryNote from '../../../tests/work/delivery-note.json';
import wDeliveryRoutes from '../../../tests/work/delivery-routes.json';
import wEmailActions from '../../../tests/work/email-actions.json';
import wExpenseAudit from '../../../tests/work/expense-audit.json';
import wHelpdeskAgent from '../../../tests/work/helpdesk-agent.json';
import wInboxTriage from '../../../tests/work/inbox-triage.json';
import wInvoiceMatching from '../../../tests/work/invoice-matching.json';
import wInvoiceRead from '../../../tests/work/invoice-read.json';
import wMeetingScheduler from '../../../tests/work/meeting-scheduler.json';
import wMonthEnd from '../../../tests/work/month-end.json';
import wOfficeWeek from '../../../tests/work/office-week.json';
import wPayrollCheck from '../../../tests/work/payroll-check.json';
import wRecruiter from '../../../tests/work/recruiter.json';
import wSheetLookup from '../../../tests/work/sheet-lookup.json';
import wShiftRota from '../../../tests/work/shift-rota.json';
import wSqlHard from '../../../tests/work/sql-hard.json';
import wSqlQuestions from '../../../tests/work/sql-questions.json';
import wStockReorder from '../../../tests/work/stock-reorder.json';
import wSupportRefunds from '../../../tests/work/support-refunds.json';
import wSupportReplies from '../../../tests/work/support-replies.json';
import wSupportWeek from '../../../tests/work/support-week.json';
import wTicketTag from '../../../tests/work/ticket-tag.json';
import wWarehouseWeek from '../../../tests/work/warehouse-week.json';
import lBillSplit from '../../../tests/life/bill-split.json';
import lLoanOverpayment from '../../../tests/life/loan-overpayment.json';
import lPackingLimits from '../../../tests/life/packing-limits.json';
import lRecipeShopping from '../../../tests/life/recipe-shopping.json';
import lTariffChooser from '../../../tests/life/tariff-chooser.json';
import lTripPlanner from '../../../tests/life/trip-planner.json';

/** Every built Business Ladder test (tests/work/). */
export const WORK_TESTS: TestDefinition[] = [wAccountsClerk, wAnalystAgent, wAssistant, wAssistantHard, wBankReconciliation, wBoardReport, wContractRedline, wCrmDedupe, wCvRead, wCvShortlist, wDeliveryNote, wDeliveryRoutes, wEmailActions, wExpenseAudit, wHelpdeskAgent, wInboxTriage, wInvoiceMatching, wInvoiceRead, wMeetingScheduler, wMonthEnd, wOfficeWeek, wPayrollCheck, wRecruiter, wSheetLookup, wShiftRota, wSqlHard, wSqlQuestions, wStockReorder, wSupportRefunds, wSupportReplies, wSupportWeek, wTicketTag, wWarehouseWeek] as unknown as TestDefinition[];

/** Every Everyday Life test (tests/life/). */
export const LIFE_TESTS: TestDefinition[] = [lBillSplit, lLoanOverpayment, lPackingLimits, lRecipeShopping, lTariffChooser, lTripPlanner] as unknown as TestDefinition[];

export const AREA_TESTS: TestDefinition[] = [...WORK_TESTS, ...LIFE_TESTS];
