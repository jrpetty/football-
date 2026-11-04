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
/** Extra lines for the long invoices of the hard cases (standard 20%, reduced 5% and zero-rated goods). */
const MORE = [
  ['Black bin liners, heavy duty (roll of 50)', 640, 'S'], ['Kitchen roll 2-ply (pack of 12)', 1099, 'S'], ['Floor cleaner concentrate 5L', 1375, 'S'],
  ['Washing-up liquid 5L', 1150, 'S'], ['Toilet roll, 2-ply (case of 36)', 2295, 'S'], ['Disposable aprons, white (roll of 200)', 845, 'S'],
  ['Antibacterial wipes (tub of 200)', 525, 'S'], ['Laminating pouches A4 (box of 100)', 1240, 'S'], ['Whiteboard markers, assorted (pack of 10)', 690, 'S'],
  ['Lever arch files, A4 (box of 10)', 1880, 'S'], ['Stapler, heavy duty', 2450, 'S'], ['Shrink film 300mm x 500m', 3125, 'S'],
  ['Mop bucket with wringer 20L', 3299, 'S'], ['LED tube 1500mm (pack of 10)', 4890, 'S'], ['Extension lead 4-way 5m', 1325, 'S'],
  ['Cable ties 300mm (bag of 100)', 375, 'S'], ['Dishwasher tablets (tub of 150)', 2760, 'S'], ['Paper napkins 33cm (pack of 500)', 935, 'S'],
  ['Children’s car seat, group 2/3', 5995, 'R'], ['Mobility walking frame, folding', 4250, 'R'], ['Nicotine patches 21mg (pack of 7)', 1149, 'R'],
  ['Energy-saving loft insulation roll', 2780, 'R'], ['Bread, wholemeal 800g (tray of 12)', 1020, 'Z'], ['Fresh fruit box (mixed, 5kg)', 1650, 'Z'],
  ['Large-print reading books (set of 6)', 3600, 'Z'], ['Baby wipes (case of 12 packs)', 1490, 'S'],
];
const RATE_PCT = { S: 20, R: 5, Z: 0 };
const RATE_LABEL = { S: '20%', R: '5%', Z: '0%' };
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
  // Hard third: long invoices, three VAT rates, several amendments (one cancelling another), traps at once.
  { id: 'c07', lines: 12, terms: 'days30', ocr: true, custVat: true, refs: true, zero: true, reduced: true, amend: ['qty', 'credit'] },
  { id: 'c08', lines: 16, terms: 'eomf', notChanged: true, refs: true, zero: true, reduced: true, discount: true, amend: ['delivery', 'price', 'qty'] },
  { id: 'c09', lines: 22, terms: 'eomf', zero: true, reduced: true, balance: true, custVat: true, changed: true, amend: ['qty', 'credit', 'price', 'cancel'] },
  { id: 'c10', lines: 28, terms: 'net30us', usDate: true, ocr: true, zero: true, reduced: true, changed: true, custVat: true, refs: true, discount: true, amend: ['qty', 'credit', 'price', 'delivery', 'cancel'] },
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
  const pool = [...CATALOGUE, ...(plan.lines > CATALOGUE.length - 4 ? MORE : [])].filter((c) => c[2] === 'S' || (plan.zero && c[2] === 'Z') || (plan.reduced && c[2] === 'R'));
  const toItem = ([desc, unit, rate]) => ({ desc, unit, rate, qty: rng.int(2, 18) });
  const items = rng.sample(pool, plan.lines).map(toItem);
  if (plan.zero && !items.some((i) => i.rate === 'Z')) items[items.length - 1] = toItem(CATALOGUE[11 + rng.int(0, 3)]);
  if (plan.reduced && !items.some((i) => i.rate === 'R')) items[items.length - 2] = toItem(MORE.find((m) => m[2] === 'R'));
  if (plan.zero) assert(items.some((i) => i.rate === 'S') && items.some((i) => i.rate === 'Z'), 'mixed VAT rates');
  const delivery = plan.correction === 'delivery' || (plan.amend ?? []).includes('delivery') ? 4500 : rng.pick([0, 0, 995, 1495]);
  const correctedDelivery = 2500;
  let corrLine = -1;
  let corrQty = 0;
  if (plan.correction === 'qty') {
    corrLine = 1;
    corrQty = items[1].qty - rng.int(2, 4);
    assert(corrQty >= 1, 'corrected qty');
  }

  // Amendments (hard cases): applied in order; a "cancel" amendment withdraws the price amendment before it.
  const amendments = [];
  const usedLines = new Set();
  const pickLine = (pred = () => true) => {
    for (;;) {
      const k = rng.int(0, items.length - 1);
      if (!usedLines.has(k) && pred(items[k])) {
        usedLines.add(k);
        return k;
      }
    }
  };
  for (const kind of plan.amend ?? []) {
    if (kind === 'qty') {
      const k = pickLine((it) => it.qty >= 5);
      amendments.push({ kind, line: k, to: items[k].qty - rng.int(1, 3) });
    } else if (kind === 'credit') {
      const k = pickLine((it) => it.qty >= 4);
      amendments.push({ kind, line: k, n: rng.int(1, 3) });
    } else if (kind === 'price') {
      const k = pickLine((it) => it.rate !== 'Z');
      amendments.push({ kind, line: k, to: items[k].unit + rng.pick([-1, 1]) * rng.int(20, 150) });
    } else if (kind === 'delivery') amendments.push({ kind, to: correctedDelivery });
    else if (kind === 'cancel') {
      const target = amendments.findIndex((a) => a.kind === 'price');
      assert(target >= 0, 'cancel needs a price amendment');
      amendments.push({ kind, target });
    }
  }
  const cancelled = new Set(amendments.filter((a) => a.kind === 'cancel').map((a) => a.target));

  /** Per-band totals (VAT per rate on the band total, rounded half up to the penny). */
  const totals = (useCorrection) => {
    const qty = items.map((it) => it.qty);
    const unit = items.map((it) => it.unit);
    let del = delivery;
    if (useCorrection) {
      if (corrLine >= 0) qty[corrLine] = corrQty;
      if (plan.correction === 'delivery') del = correctedDelivery;
      amendments.forEach((a, i) => {
        if (cancelled.has(i)) return;
        if (a.kind === 'qty') qty[a.line] = a.to;
        if (a.kind === 'credit') qty[a.line] -= a.n;
        if (a.kind === 'price') unit[a.line] = a.to;
        if (a.kind === 'delivery') del = a.to;
      });
    }
    const band = { S: del, R: 0, Z: 0 }; // delivery is standard-rated
    items.forEach((it, i) => (band[it.rate] += qty[i] * unit[i]));
    const vatS = vat20(band.S);
    const vatR = Math.floor((band.R * 5 + 50) / 100);
    const net = band.S + band.R + band.Z;
    return { net, vat: vatS + vatR, gross: net + vatS + vatR, del, band, vatS, vatR };
  };
  const printed = totals(false);
  const truth = plan.correction || amendments.length ? totals(true) : printed;
  if (plan.correction || amendments.length) assert(truth.gross !== printed.gross, 'correction must change the total');

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
  lines.push(amendments.length ? 'Line. Qty | Description | Unit price | VAT | Net' : 'Qty | Description | Unit price | VAT | Net');
  items.forEach((it, k) => lines.push(`${amendments.length ? `${k + 1}. ` : ''}${it.qty} | ${it.desc} | ${money(it.unit)} | ${RATE_LABEL[it.rate]} | ${money(it.qty * it.unit)}`));
  if (printed.del) lines.push(`1 | Delivery charge | ${money(printed.del)} | 20% | ${money(printed.del)}`);
  lines.push('');
  lines.push(`Net total: ${money(printed.net)}`);
  if (plan.reduced) lines.push(`VAT @ 20% on ${money(printed.band.S)}: ${money(printed.vatS)}   VAT @ 5% on ${money(printed.band.R)}: ${money(printed.vatR)}   Zero-rated: ${money(printed.band.Z)}`);
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
  amendments.forEach((a, i) => {
    const it = a.line !== undefined ? items[a.line] : null;
    const head = `AMENDMENT ${i + 1}:`;
    if (a.kind === 'qty') lines.push(`${head} line ${a.line + 1} ("${it.desc}") should be ${a.to}, not ${it.qty}: only ${a.to} were delivered.`);
    if (a.kind === 'credit') lines.push(`${head} you returned ${a.n} of line ${a.line + 1} ("${it.desc}") as damaged; please deduct them from this invoice at the line's unit price.`);
    if (a.kind === 'price') lines.push(`${head} the unit price on line ${a.line + 1} ("${it.desc}") should be ${money(a.to)}, not ${money(it.unit)} (price list update).`);
    if (a.kind === 'delivery') lines.push(`${head} the delivery charge should be ${money(a.to)}, not ${money(delivery)}.`);
    if (a.kind === 'cancel') lines.push(`${head} please ignore Amendment ${a.target + 1}: the original unit price on that line was correct after all.`);
  });
  if (amendments.length) lines.push('The table and totals above have NOT been reissued: please pay the amended amount.');
  if (plan.discount) lines.push(`Early settlement: 2.5% discount if paid within 7 days (not included in the figures above).`);
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
    vat_breakdown: {
      standard_net: Number(plain(truth.band.S)),
      standard_vat: Number(plain(truth.vatS)),
      reduced_net: Number(plain(truth.band.R)),
      reduced_vat: Number(plain(truth.vatR)),
      zero_rated_net: Number(plain(truth.band.Z)),
    },
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
    '  "vat": number,                    // VAT, in pounds (all rates together)',
    '  "gross": number,                  // total of THIS invoice including VAT, in pounds',
    '  "supplier_vat_number": string,    // e.g. "GB123456789"',
    '  "po_reference": string or null,   // OUR purchase order number quoted on the invoice; null if none is quoted',
    '  "bank_details_changed": true or false,  // does the invoice say the supplier’s bank details have changed?',
    '  "sort_code": "NN-NN-NN",          // the account the invoice tells us to pay into now',
    '  "account_number": string,',
    '  "vat_breakdown": {"standard_net": number, "standard_vat": number, "reduced_net": number, "reduced_vat": number, "zero_rated_net": number}  // net and VAT per rate after amendments; 0 where a rate is not used',
    '}',
    '',
    'Rules:',
    '- Dates on UK documents are day/month/year unless the document says otherwise.',
    '- Due dates: "N days from the invoice date" means the invoice date plus N calendar days. "End of the month following" means the last day of the next calendar month.',
    '- If the invoice states amendments, apply them in order (a later amendment can withdraw an earlier one) and recalculate net, VAT and gross yourself. VAT is worked out per rate on the total net at that rate (20% standard, 5% reduced, 0% zero-rated; delivery is standard-rated), each rounded to the nearest penny (half a penny rounds up).',
    '- Ignore any early-settlement discount that is only offered (not already deducted).',
    '- gross is the total of this invoice only: do not add any earlier balance.',
    ...(plan.ocr ? ['- OCR note: the scanner sometimes reads the digit 0 as the letter O (and 1 as l). Invoice numbers are "INV-" followed by digits only, and VAT numbers are "GB" followed by 9 digits, so correct any such slips.'] : []),
    `- Reply with ONE JSON object (a \`\`\`json block is fine) and nothing else after it. Numbers as plain JSON numbers. ${HEDGE_RULE} Every field is checked separately.`,
  ].join('\n');

  const notes = [
    `features: ${Object.keys(plan).filter((k) => k !== 'id' && plan[k]).map((k) => `${k}=${plan[k]}`).join(', ')}`,
    `printed totals net ${plain(printed.net)} vat ${plain(printed.vat)} gross ${plain(printed.gross)}`,
    plan.correction || amendments.length ? `amended totals net ${plain(truth.net)} vat ${plain(truth.vat)} gross ${plain(truth.gross)}` : null,
    amendments.length ? `amendments: ${JSON.stringify(amendments)}${cancelled.size ? ` (cancelled: ${[...cancelled].map((x) => x + 1).join(', ')})` : ''}` : null,
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
    vat_breakdown: { standard_net: Number(plain(printed.band.S)), standard_vat: Number(plain(printed.vatS)), reduced_net: Number(plain(printed.band.R)), reduced_vat: Number(plain(printed.vatR)), zero_rated_net: Number(plain(printed.band.Z)) },
  };
  // A strong but human-error-prone reader: gets everything right except that it applies the withdrawn amendment
  // (when there is one) or, otherwise, misses the last amendment.
  let slip = expected;
  if (amendments.length) {
    const keep = cancelled.size ? amendments.map((_, i) => i) : amendments.map((_, i) => i).slice(0, -1);
    const saved = new Set(cancelled);
    cancelled.clear();
    const all = amendments.slice();
    amendments.length = 0;
    keep.forEach((i) => amendments.push(all[i]));
    const t = totals(true);
    amendments.length = 0;
    all.forEach((a) => amendments.push(a));
    saved.forEach((x) => cancelled.add(x));
    slip = { ...expected, net: Number(plain(t.net)), vat: Number(plain(t.vat)), gross: Number(plain(t.gross)), vat_breakdown: { standard_net: Number(plain(t.band.S)), standard_vat: Number(plain(t.vatS)), reduced_net: Number(plain(t.band.R)), reduced_vat: Number(plain(t.vatR)), zero_rated_net: Number(plain(t.band.Z)) } };
  }
  return { case: { id: plan.id, prompt, expected, notes }, careless, slip, decoys: { printedGross: printed.gross / 100, balanceGross: (printed.gross + balance) / 100, custVat, oldSort, oldAcct, ukDate: plan.usDate ? `${invDate.getUTCFullYear()}-${String(invDate.getUTCDate()).padStart(2, '0')}-${String(invDate.getUTCMonth() + 1).padStart(2, '0')}` : null, ocrInv: shownInv } };
}

export const META = {
  id: 'work.invoice-read',
  name: 'Read an Invoice',
  description:
    'Business Ladder, Finance & Accounts, Level 1 (Assist). One supplier invoice per case, copied from a PDF: fill in the accounts-system form (supplier, invoice number, dates, net/VAT/gross, VAT number, PO reference, whether the bank details have changed, and the account to pay). Later cases add the traps real invoices have: our own VAT number next to the supplier’s, a delivery-note number that looks like the invoice number, an earlier balance added to the amount due, a US-format date flagged as such, an OCR scan that reads 0 as O, and, in the hard third, long invoices at three VAT rates with several amendments (a quantity, a credit for returns, a price change later withdrawn, the delivery charge) that must be applied in order and recalculated per rate.',
  difficulty: 'easy',
  tags: ['business-ladder', 'finance', 'level-1', 'extraction', 'json', 'invoices', 'uk'],
  hook: 'One invoice, seventeen boxes to fill. Can it read the paperwork without slipping?',
  outputTokens: 2500,
  scorer: { type: 'json', numberTolerance: 1e-8, lenient: true },
};

export function generate() {
  return PLAN.map((p, i) => build(p, i));
}
