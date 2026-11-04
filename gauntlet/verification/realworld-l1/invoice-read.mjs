// work.invoice-read: "Read an Invoice" (Business Ladder, Finance & Accounts, Level 1).
// One supplier invoice per case; the model fills in the accounts-system form. Every key is computed here from
// the same data that renders the invoice text.
import { rngFor, money, plain, vat20, D, iso, addDays, ukNum, usNum, ukLong, lastDayOfMonth, companyName, address, vatNumber, spacedVat, person, HEDGE_RULE, assert } from './lib.mjs';

const CATALOGUE = [
  ['Corrugated boxes 450x350x300 (bundle of 25)', 1850, 'S'],
  ['Brown parcel tape 48mm x 66m (pack of 6)', 795, 'S'],
  ['Bubble wrap roll 750mm x 100m', 2399, 'S'],
  ['A4 copier paper 80gsm (box of 5 reams)', 2150, 'S'],
  ['Nitrile gloves, blue, medium (box of 100)', 699, 'S'],
  ['Blue centrefeed roll 2-ply (pack of 6)', 1425, 'S'],
  ['Hi-vis vest, yellow, XL', 385, 'S'],
  ['Hand sanitiser 5L', 1890, 'S'],
  ['Thermal till rolls 80x80 (box of 20)', 2475, 'S'],
  ['Pallet wrap 400mm x 300m', 1160, 'S'],
  ['Compostable coffee cups 12oz (case of 1000)', 6450, 'S'],
  ['Bottled still water 500ml (case of 24)', 540, 'Z'],
  ['Milk, semi-skimmed 2 pint (crate of 12)', 1188, 'Z'],
  ['Printed training manuals (books)', 1250, 'Z'],
  ['Children’s hi-vis tabard, age 7-10', 299, 'Z'],
];
const CUSTOMERS = ['Larkspur Events Ltd', 'Brightwell Dental Group Ltd', 'Moorside Community Trust', 'Fairhaven Hotels Ltd', 'Quayside Brewing Co Ltd'];

function sortCode(rng) {
  return `${rng.int(10, 99)}-${rng.int(10, 99)}-${rng.int(10, 99)}`;
}

/** Feature plan for each case, easiest first. */
const PLAN = [
  { id: 'c01', lines: 2, terms: 'date' },
  { id: 'c02', lines: 3, terms: 'days30', custVat: true },
  { id: 'c03', lines: 3, terms: 'days14', custVat: true, refs: true, notChanged: true },
  { id: 'c04', lines: 3, terms: 'date', changed: true, refs: true },
  { id: 'c05', lines: 4, terms: 'days30', zero: true, balance: true, custVat: true },
  { id: 'c06', lines: 3, terms: 'net30us', usDate: true, refs: true, noPo: true },
  { id: 'c07', lines: 4, terms: 'days30', ocr: true, custVat: true, refs: true },
  { id: 'c08', lines: 4, terms: 'eomf', correction: 'delivery', notChanged: true, refs: true },
  { id: 'c09', lines: 5, terms: 'eomf', zero: true, correction: 'qty', balance: true, custVat: true, changed: true },
  { id: 'c10', lines: 5, terms: 'net30us', usDate: true, ocr: true, zero: true, correction: 'qty', changed: true, custVat: true, refs: true },
];

function build(plan, idx) {
  const rng = rngFor(`invoice-read/${plan.id}`);
  const supplier = companyName(rng);
  const customer = CUSTOMERS[idx % CUSTOMERS.length];
  let supVat = vatNumber(rng);
  while (plan.ocr && !supVat.slice(2).includes('0')) supVat = vatNumber(rng);
  let custVat = vatNumber(rng);
  while (custVat === supVat) custVat = vatNumber(rng);
  const invDigits = String(rng.int(20000, 49999));
  let invNo = `INV-${invDigits}`;
  // OCR cases need a zero in the invoice number and the VAT number to be misread as the letter O.
  if (plan.ocr && !invDigits.includes('0')) invNo = `INV-${invDigits.slice(0, 2)}0${invDigits.slice(3)}`;
  const dnNo = `DN-${Number(invNo.slice(4)) - rng.int(1, 3)}`;
  const soNo = `SO-${rng.int(60000, 89999)}`;
  const poNo = plan.noPo ? null : `PO-${rng.int(10000, 99999)}`;
  const year = 2026;
  const month = 1 + ((idx * 5 + rng.int(0, 3)) % 11);
  let invDate = D(year, month, rng.int(1, 12));
  if (plan.usDate) invDate = D(year, rng.int(1, 9), rng.int(2, 12)); // day ≤ 12 so the US date is a real trap
  if (plan.usDate) assert(invDate.getUTCDate() !== invDate.getUTCMonth() + 1, 'US date must differ from its UK reading');

  // Lines
  const pool = CATALOGUE.filter((c) => (plan.zero ? true : c[2] === 'S'));
  const toItem = ([desc, unit, rate]) => ({ desc, unit, rate, qty: rng.int(2, 18) });
  const items = rng.sample(pool, plan.lines).map(toItem);
  if (plan.zero && !items.some((i) => i.rate === 'Z')) items[items.length - 1] = toItem(CATALOGUE[11 + rng.int(0, 3)]);
  if (plan.zero) assert(items.some((i) => i.rate === 'S') && items.some((i) => i.rate === 'Z'), 'mixed VAT rates');
  const delivery = plan.correction === 'delivery' ? 4500 : rng.pick([0, 0, 995, 1495]);
  const correctedDelivery = 2500;
  let corrLine = -1;
  let corrQty = 0;
  if (plan.correction === 'qty') {
    corrLine = 1;
    corrQty = items[1].qty - rng.int(2, 4);
    assert(corrQty >= 1, 'corrected qty');
  }

  const totals = (useCorrection) => {
    let std = 0;
    let zero = 0;
    items.forEach((it, i) => {
      const q = useCorrection && i === corrLine ? corrQty : it.qty;
      const n = q * it.unit;
      if (it.rate === 'S') std += n;
      else zero += n;
    });
    const del = useCorrection && plan.correction === 'delivery' ? correctedDelivery : delivery;
    std += del; // delivery is standard-rated
    const net = std + zero;
    const v = vat20(std);
    return { net, vat: v, gross: net + v, del };
  };
  const printed = totals(false);
  const truth = plan.correction ? totals(true) : printed;
  if (plan.correction) assert(truth.gross !== printed.gross, 'correction must change the total');

  // Dates
  let due;
  let termsText;
  switch (plan.terms) {
    case 'date':
      due = addDays(invDate, rng.pick([14, 21, 28]));
      termsText = `Payment due by ${ukLong(due)}.`;
      break;
    case 'days30':
      due = addDays(invDate, 30);
      termsText = 'Payment terms: 30 days from date of invoice.';
      break;
    case 'days14':
      due = addDays(invDate, 14);
      termsText = 'Terms: strictly 14 days from invoice date.';
      break;
    case 'net30us':
      due = addDays(invDate, 30);
      termsText = 'Terms: Net 30 (payment due 30 days after the invoice date).';
      break;
    case 'eomf': {
      due = lastDayOfMonth(invDate.getUTCFullYear(), invDate.getUTCMonth() + 2);
      termsText = 'Payment terms: end of the month following the month of invoice.';
      break;
    }
  }
  const fmtDate = plan.usDate ? usNum : ukNum;

  // Bank
  const oldSort = sortCode(rng);
  const oldAcct = rng.digits(8);
  const newSort = sortCode(rng);
  const newAcct = rng.digits(8);
  const paySort = plan.changed ? newSort : oldSort;
  const payAcct = plan.changed ? newAcct : oldAcct;

  // OCR rendering: zeros in identifiers read as the letter O.
  const ocr = (s) => (plan.ocr ? s.replace(/0/g, 'O') : s);
  const shownInv = plan.ocr ? `INV-${ocr(invNo.slice(4))}` : invNo;
  const shownSupVat = plan.ocr ? `GB ${ocr(spacedVat(supVat).slice(3))}` : spacedVat(supVat);
  if (plan.ocr) assert(shownInv !== invNo && shownSupVat.replace(/ /g, '') !== supVat, 'OCR slip must touch the invoice and VAT numbers');

  const contact = person(rng);
  const lines = [];
  lines.push(supplier.toUpperCase());
  lines.push(address(rng));
  lines.push(`VAT Reg No: ${shownSupVat}    Company No: ${rng.digits(8)}`);
  lines.push('');
  lines.push(plan.ocr ? 'TAX lNVOICE' : 'TAX INVOICE');
  if (plan.usDate) lines.push('(Dates on this document are shown MM/DD/YYYY)');
  lines.push('');
  lines.push(`Invoice No: ${shownInv}        Invoice Date: ${fmtDate(invDate)}`);
  if (plan.refs) lines.push(`Delivery Note: ${dnNo}       Our Ref: ${soNo}`);
  lines.push(`Your Order Ref: ${poNo ?? '(none quoted)'}`);
  lines.push('');
  lines.push(`Invoice to: ${customer}, ${address(rng)}`);
  if (plan.custVat) lines.push(`Customer VAT No: ${spacedVat(custVat)}`);
  lines.push(`FAO: ${contact}, Accounts Payable`);
  lines.push('');
  lines.push('Qty | Description | Unit price | VAT | Net');
  items.forEach((it) => lines.push(`${it.qty} | ${it.desc} | ${money(it.unit)} | ${it.rate === 'S' ? '20%' : '0%'} | ${money(it.qty * it.unit)}`));
  if (printed.del) lines.push(`1 | Delivery charge | ${money(printed.del)} | 20% | ${money(printed.del)}`);
  lines.push('');
  lines.push(`Net total: ${money(printed.net)}`);
  lines.push(`VAT: ${money(printed.vat)}`);
  lines.push(`Invoice total: ${money(printed.gross)}`);
  let balance = 0;
  if (plan.balance) {
    balance = rng.int(80, 600) * 100 + rng.int(0, 99);
    lines.push(`Previous balance outstanding (invoice ${`INV-${Number(invNo.slice(4)) - rng.int(40, 90)}`}): ${money(balance)}`);
    lines.push(`TOTAL NOW DUE ON ACCOUNT: ${money(printed.gross + balance)}`);
  }
  lines.push('');
  if (plan.correction === 'delivery')
    lines.push(`AMENDMENT (credit control, ${plan.usDate ? usNum(addDays(invDate, 1)) : ukNum(addDays(invDate, 1))}): the delivery charge on this invoice should be ${money(correctedDelivery)}, not ${money(4500)}. The table above has not been reissued; please pay the amended amount.`);
  if (plan.correction === 'qty') {
    const it = items[corrLine];
    lines.push(`AMENDMENT: only ${corrQty} of the ${it.qty} x "${it.desc}" were delivered, so this line should be ${corrQty} x ${money(it.unit)}. The table above has not been reissued; please pay the amended amount.`);
  }
  lines.push(termsText);
  if (plan.changed) {
    lines.push(`IMPORTANT: our bank details have changed. From now on please pay ${supplier} at sort code ${newSort}, account ${newAcct}. Our old account (sort code ${oldSort}, account ${oldAcct}) is closed.`);
  } else {
    lines.push(`Please pay by BACS to ${supplier}, sort code ${oldSort}, account ${oldAcct}, quoting the invoice number.`);
    if (plan.notChanged) lines.push('Fraud warning: our bank details have NOT changed. If you receive an email saying they have, call us before paying.');
  }
  lines.push(`Queries: ${contact.split(' ')[0].toLowerCase()}@${supplier.split(' ')[0].toLowerCase()}.example.co.uk`);
  const doc = lines.join('\n');

  const expected = {
    supplier,
    invoice_number: invNo,
    invoice_date: iso(invDate),
    due_date: iso(due),
    net: Number(plain(truth.net)),
    vat: Number(plain(truth.vat)),
    gross: Number(plain(truth.gross)),
    supplier_vat_number: supVat,
    po_reference: poNo,
    bank_details_changed: !!plan.changed,
    sort_code: paySort,
    account_number: payAcct,
  };
  // One truth: no decoy equals a key.
  assert(!doc.includes(`${invNo}`) || !plan.ocr, 'OCR case must not print the clean invoice number');
  assert(custVat !== supVat && dnNo !== invNo && soNo !== poNo, 'decoys differ from keys');
  if (plan.balance) assert(printed.gross + balance !== truth.gross, 'balance decoy differs');

  const prompt = [
    `I work in accounts payable at ${customer}. Here is a supplier invoice that has just arrived (text copied from the PDF${plan.ocr ? ', which was a scan read by OCR software' : ''}). Please read it and fill in the form for our accounts system.`,
    '',
    'Invoice:',
    '<<<',
    doc,
    '>>>',
    '',
    'Form (reply with this JSON object, filled in):',
    '{',
    '  "supplier": string,               // the supplier company name exactly as it is registered (e.g. "Acme Widgets Ltd")',
    '  "invoice_number": string,         // the supplier’s invoice number, e.g. "INV-12345"',
    '  "invoice_date": "YYYY-MM-DD",',
    '  "due_date": "YYYY-MM-DD",         // as stated, or worked out from the payment terms',
    '  "net": number,                    // amount before VAT, in pounds',
    '  "vat": number,                    // VAT, in pounds',
    '  "gross": number,                  // total of THIS invoice including VAT, in pounds',
    '  "supplier_vat_number": string,    // e.g. "GB123456789"',
    '  "po_reference": string or null,   // OUR purchase order number quoted on the invoice; null if none is quoted',
    '  "bank_details_changed": true or false,  // does the invoice say the supplier’s bank details have changed?',
    '  "sort_code": "NN-NN-NN",          // the account the invoice tells us to pay into now',
    '  "account_number": string',
    '}',
    '',
    'Rules:',
    '- Dates on UK documents are day/month/year unless the document says otherwise.',
    '- Due dates: "N days from the invoice date" means the invoice date plus N calendar days. "End of the month following" means the last day of the next calendar month.',
    '- If the invoice states an amendment, use the amended figures: recalculate net, VAT (20% on standard-rated lines, 0% on zero-rated lines; delivery is standard-rated) and gross yourself, rounding VAT to the nearest penny.',
    '- gross is the total of this invoice only: do not add any earlier balance.',
    ...(plan.ocr ? ['- OCR note: the scanner sometimes reads the digit 0 as the letter O (and 1 as l). Invoice numbers are "INV-" followed by digits only, and VAT numbers are "GB" followed by 9 digits, so correct any such slips.'] : []),
    `- Reply with ONE JSON object (a \`\`\`json block is fine) and nothing else after it. Numbers as plain JSON numbers. ${HEDGE_RULE} Every field is checked separately.`,
  ].join('\n');

  const notes = [
    `features: ${Object.keys(plan).filter((k) => k !== 'id' && plan[k]).map((k) => `${k}=${plan[k]}`).join(', ')}`,
    `printed totals net ${plain(printed.net)} vat ${plain(printed.vat)} gross ${plain(printed.gross)}`,
    plan.correction ? `amended totals net ${plain(truth.net)} vat ${plain(truth.vat)} gross ${plain(truth.gross)}` : null,
    plan.balance ? `decoy: total now due on account ${plain(printed.gross + balance)} includes an earlier balance` : null,
    plan.custVat ? `decoy: customer VAT ${custVat}` : null,
    plan.refs ? `decoys: delivery note ${dnNo}, supplier order ref ${soNo}` : null,
    plan.changed ? `decoy: old bank ${oldSort} ${oldAcct}` : null,
    plan.usDate ? `US date ${usNum(invDate)} = ${iso(invDate)} (UK reading would be wrong)` : null,
    plan.ocr ? `OCR: printed ${shownInv} / ${shownSupVat}` : null,
  ]
    .filter(Boolean)
    .join('; ');
  // A careless reader: printed (pre-amendment) totals, the amount due on account, the OCR text as printed,
  // UK reading of a US date, our own VAT number when it is printed, and the old bank details.
  const careless = {
    ...expected,
    net: Number(plain(printed.net)),
    vat: Number(plain(printed.vat)),
    gross: Number(plain(printed.gross + balance)),
    invoice_number: shownInv,
    invoice_date: plan.usDate ? `${invDate.getUTCFullYear()}-${String(invDate.getUTCDate()).padStart(2, '0')}-${String(invDate.getUTCMonth() + 1).padStart(2, '0')}` : expected.invoice_date,
    supplier_vat_number: plan.custVat ? custVat : shownSupVat.replace(/ /g, ''),
    sort_code: oldSort,
    account_number: oldAcct,
    bank_details_changed: !!plan.changed || !!plan.notChanged,
  };
  return { case: { id: plan.id, prompt, expected, notes }, careless, decoys: { printedGross: printed.gross / 100, balanceGross: (printed.gross + balance) / 100, custVat, oldSort, oldAcct, ukDate: plan.usDate ? `${invDate.getUTCFullYear()}-${String(invDate.getUTCDate()).padStart(2, '0')}-${String(invDate.getUTCMonth() + 1).padStart(2, '0')}` : null, ocrInv: shownInv } };
}

export const META = {
  id: 'work.invoice-read',
  name: 'Read an Invoice',
  description:
    'Business Ladder, Finance & Accounts, Level 1 (Assist). One supplier invoice per case, copied from a PDF: fill in the accounts-system form (supplier, invoice number, dates, net/VAT/gross, VAT number, PO reference, whether the bank details have changed, and the account to pay). Later cases add the traps real invoices have: our own VAT number next to the supplier’s, a delivery-note number that looks like the invoice number, an earlier balance added to the amount due, a US-format date flagged as such, an OCR scan that reads 0 as O, and an amendment that changes a line so the totals must be recalculated.',
  difficulty: 'easy',
  tags: ['business-ladder', 'finance', 'level-1', 'extraction', 'json', 'invoices', 'uk'],
  hook: 'One invoice, twelve boxes to fill. Can it read the paperwork without slipping?',
  outputTokens: 900,
  scorer: { type: 'json', numberTolerance: 1e-8, lenient: true },
};

export function generate() {
  return PLAN.map((p, i) => build(p, i));
}
