import type { DocEntry } from '../types.ts';
import { apQuestion } from '../model/ap.ts';
import { ApBeats, ApInline } from './ThreeWay.tsx';

const caption = 'Every document in the accounts-payable inbox with the model’s stamp (PAY, HOLD or REJECT) against the policy’s; one document drawn as a three-way match: purchase order, goods received and invoice, line by line, with the mismatching lines marked.';

export const INVOICE_MATCHING: DocEntry = {
  family: 'extraction',
  caption,
  build: (input) => (apQuestion(input.turns[0] ?? '') ? input : null),
  render: (_m, mode, input) => <ApInline input={input} mode={mode} />,
  Beats: ApBeats,
};

export const INVOICES_LADDER: DocEntry = { ...INVOICE_MATCHING, family: 'business' };
