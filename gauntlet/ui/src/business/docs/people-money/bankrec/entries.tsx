import type { DocEntry } from '../types.ts';
import { recQuestion } from '../model/bankrec.ts';
import { RecBeats, RecInline } from './BankRec.tsx';

const caption = 'The bank statement and the cash book side by side, with the model’s matches drawn between them (green right, red wrong, dashed: missed) and its reason for every unmatched line, then the adjusted balance against the correct one.';

export const BANK_REC: DocEntry = {
  family: 'extraction',
  caption,
  build: (input) => (recQuestion(input.turns[0] ?? '') ? input : null),
  render: (_m, mode, input) => <RecInline input={input} mode={mode} />,
  Beats: RecBeats,
};

export const REC_LADDER: DocEntry = { ...BANK_REC, family: 'business' };
