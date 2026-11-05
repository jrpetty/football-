import type { DocEntry } from '../types.ts';
import { invoiceDoc } from '../model/invoiceRead.ts';
import { InvoiceReadBeats, InvoiceReadInline } from './InvoiceRead.tsx';

export const INVOICE_READ: DocEntry = {
  family: 'extraction',
  caption: 'The accounts-system form as the model filled it in, beside the invoice. Numbered marks show where each box comes from; the traps on the invoice (our own VAT number, an earlier balance, the old bank account, amendments) are tagged.',
  build: (input) => (invoiceDoc(input.turns[0] ?? '') ? input : null),
  render: (_m, mode, input) => <InvoiceReadInline input={input} mode={mode} />,
  Beats: InvoiceReadBeats,
};
