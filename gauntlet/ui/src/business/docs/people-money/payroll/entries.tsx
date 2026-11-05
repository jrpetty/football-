import type { DocEntry } from '../types.ts';
import { payrollQuestion } from '../model/payroll.ts';
import { PayrollBeats, PayrollInline } from './Payslips.tsx';

const caption = 'The bureau’s draft payslips as payslips: the wrong figures circled with the correct amount beside them, and the model’s verdict stamped on each (caught, missed, false alarm) with its corrections ticked or crossed.';

export const PAYROLL_CHECK: DocEntry = {
  family: 'extraction',
  caption,
  build: (input) => (payrollQuestion(input.turns[0] ?? '') ? input : null),
  render: (_m, mode, input) => <PayrollInline input={input} mode={mode} />,
  Beats: PayrollBeats,
};

export const PAYROLL_LADDER: DocEntry = { ...PAYROLL_CHECK, family: 'business', caption: `Every payslip of every month as a calendar (staff × months), coloured by the model’s verdict. ${caption}` };
