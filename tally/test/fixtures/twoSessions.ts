// ---------------------------------------------------------------------------
// One day, two Z reads — the Gardeners Arms, 18 September 2026.
//
// Transcribed off the photographs she sent. The pub cashed up twice: the day
// session at 16:57 and the evening at 22:46, so there are two separate Z reads
// carrying the same date, and the night is the two of them added together:
//
//   Z1 1712   16:57:14   #4631   346 units   £1,174.75
//   Z1 1713   22:46:49   #4743   244 units   £  836.30
//                                ---------   ----------
//                                590 units   £2,011.05
//
// Both foot exactly: each PLU list adds to its own receipt's DEPT TL, and each
// clerk split adds to its own PAID TL. That is why they are worth having as a
// fixture — every figure below can be checked against the paper.
//
// The trap this fixture exists to hold down is in the *third* printout. The
// clerk report of the 16:57 read is receipt #4633, not #4631: the receipt
// number counts printouts, not sessions. Anything that split receipts on the
// receipt number would tear that session in two and then add it to itself,
// turning £1,174.75 into £2,349.50. The timestamp and the Z counter are the
// things that identify a session; the receipt number is not.
// ---------------------------------------------------------------------------

import type { PluLine, ZRead } from '../../src/core/zread.ts'

function plu(code: string, name: string, qty: number, pence: number): PluLine {
  return { code, name, qtyMilli: qty * 1000, pence }
}

/** The day session: rung off at 16:57, £1,174.75 over 346 items. */
export const DAY_SESSION: ZRead = {
  header: {
    receiptNo: '4631',
    zNumber: 1712,
    clerk: '0004 CLERK0004',
    printedAt: '18/09/2026 16:57:14',
    gt1Pence: 142424966,
    gt2Pence: 144574038,
    gt3Pence: -2149072,
  },
  departments: [
    { code: 'D01', name: 'DRAUGHT BEERS', qtyMilli: 273000, pence: 95800, percentBp: 8155, group: 'GROUP01' },
    { code: 'D02', name: 'SPIRITS', qtyMilli: 1000, pence: 375, percentBp: 32, group: 'GROUP01' },
    { code: 'D03', name: 'WINE', qtyMilli: 22000, pence: 10500, percentBp: 894, group: 'GROUP01' },
    { code: 'D05', name: 'MIXERS', qtyMilli: 43000, pence: 8980, percentBp: 764, group: 'GROUP01' },
    { code: 'D07', name: 'SUNDRIES', qtyMilli: 4000, pence: 680, percentBp: 58, group: 'GROUP01' },
    { code: 'D08', name: 'OPEN FOOD', qtyMilli: 3000, pence: 1140, percentBp: 97, group: 'GROUP02' },
  ],
  groups: [
    { code: 'GROUP01', qtyMilli: 343000, pence: 116335, percentBp: 9903 },
    { code: 'GROUP02', qtyMilli: 3000, pence: 1140, percentBp: 97 },
  ],
  deptTotal: { qtyMilli: 346000, pence: 117475, percentBp: 10000 },
  transaction: {
    net1Pence: 117475,
    net2Pence: 117475,
    voidCount: 8,
    voidPence: 2540,
    noSaleCount: 1,
    guestCount: 157,
    orderTotalPence: 117475,
    paidTotalPence: 117475,
    avePence: 748,
    cashCount: 54,
    cashPence: 39700,
    cardCount: 103,
    cardPence: 77775,
    cidPence: 39700,
    caChkIdPence: 39700,
  },
  clerks: [
    {
      code: 'CLK#0001',
      name: 'CLERK0001',
      orderTotalPence: 74270,
      nonComPence: 74270,
      paidTotalPence: 74270,
      avePence: 774,
      voidCount: 6,
      voidPence: 1820,
      guestCount: 96,
      cashCount: 48,
      cashPence: 34530,
      cardCount: 48,
      cardPence: 39740,
      cidPence: 34530,
    },
    {
      code: 'CLK#0004',
      name: 'CLERK0004',
      orderTotalPence: 43205,
      nonComPence: 43205,
      paidTotalPence: 43205,
      avePence: 708,
      voidCount: 2,
      voidPence: 720,
      guestCount: 61,
      cashCount: 6,
      cashPence: 5170,
      cardCount: 55,
      cardPence: 38035,
      cidPence: 5170,
    },
  ],
  plus: [
    plu('P00001', 'PINT DARK MILD', 6, 1680),
    plu('P00002', 'PINT CIDER', 13, 6890),
    plu('P00006', 'HALF DARK MILD', 1, 140),
    plu('P00007', 'HALF CIDER', 3, 795),
    plu('P00011', 'PINT OBB', 43, 15480),
    plu('P00013', 'PINT ALPINE', 50, 15000),
    plu('P00014', 'PINT TADDY LAGER', 88, 35200),
    plu('P00016', 'PINT PURE BREW', 14, 7420),
    plu('P00017', 'PINT STOUT', 15, 6000),
    plu('P00020', 'HALF ALPINE', 20, 3000),
    plu('P00021', 'HALF TADDY LAGER', 11, 2200),
    plu('P00023', 'HALF PURE BREW', 3, 795),
    plu('P00024', 'HALF STOUT', 6, 1200),
    plu('P00026', '175ML RED WINE', 1, 525),
    plu('P00029', '125ML HOUSE WINE', 10, 3750),
    plu('P00030', '175ML HOUSE WINE', 9, 4725),
    plu('P00031', '250ML HOUSE WINE', 1, 750),
    plu('P00038', '250ML ROSE', 1, 750),
    plu('P00052', 'TEQUILA', 1, 375),
    plu('P00054', 'ELDERFLOWER', 4, 1520),
    plu('P00060', 'ORANGE JUICE', 3, 1140),
    plu('P00069', 'HALF POST MIX', 32, 6080),
    plu('P00070', 'DASH', 4, 240),
    plu('P00074', 'CRISPS', 4, 680),
    plu('P00080', 'OPEN FOOD', 3, 1140),
  ],
  pluTotal: { qtyMilli: 346000, pence: 117475 },
}

/** The evening session: rung off at 22:46, £836.30 over 244 items. */
export const EVENING_SESSION: ZRead = {
  header: {
    receiptNo: '4743',
    zNumber: 1713,
    clerk: '0004 CLERK0004',
    printedAt: '18/09/2026 22:46:49',
    gt1Pence: 142508596,
    gt2Pence: 144657668,
    gt3Pence: -2149072,
  },
  departments: [
    { code: 'D01', name: 'DRAUGHT BEERS', qtyMilli: 172000, pence: 60375, percentBp: 7219, group: 'GROUP01' },
    { code: 'D02', name: 'SPIRITS', qtyMilli: 17000, pence: 5355, percentBp: 640, group: 'GROUP01' },
    { code: 'D03', name: 'WINE', qtyMilli: 16000, pence: 8550, percentBp: 1022, group: 'GROUP01' },
    { code: 'D04', name: 'BOTTLED BEERS', qtyMilli: 8000, pence: 4400, percentBp: 526, group: 'GROUP01' },
    { code: 'D05', name: 'MIXERS', qtyMilli: 23000, pence: 3590, percentBp: 429, group: 'GROUP01' },
    { code: 'D07', name: 'SUNDRIES', qtyMilli: 8000, pence: 1360, percentBp: 163, group: 'GROUP01' },
  ],
  groups: [{ code: 'GROUP01', qtyMilli: 244000, pence: 83630, percentBp: 10000 }],
  deptTotal: { qtyMilli: 244000, pence: 83630, percentBp: 10000 },
  transaction: {
    net1Pence: 83630,
    net2Pence: 83630,
    noSaleCount: 2,
    guestCount: 107,
    orderTotalPence: 83630,
    paidTotalPence: 83630,
    avePence: 782,
    cashCount: 47,
    cashPence: 32000,
    cardCount: 60,
    cardPence: 51630,
    cidPence: 32000,
    caChkIdPence: 32000,
  },
  clerks: [
    {
      code: 'CLK#0001',
      name: 'CLERK0001',
      orderTotalPence: 79570,
      nonComPence: 79570,
      paidTotalPence: 79570,
      avePence: 788,
      guestCount: 101,
      cashCount: 43,
      cashPence: 29850,
      cardCount: 58,
      cardPence: 49720,
      cidPence: 29850,
    },
    {
      code: 'CLK#0004',
      name: 'CLERK0004',
      orderTotalPence: 4060,
      nonComPence: 4060,
      paidTotalPence: 4060,
      avePence: 677,
      guestCount: 6,
      cashCount: 4,
      cashPence: 2150,
      cardCount: 2,
      cardPence: 1910,
      cidPence: 2150,
    },
  ],
  plus: [
    plu('P00001', 'PINT DARK MILD', 8, 2240),
    plu('P00011', 'PINT OBB', 42, 15120),
    plu('P00013', 'PINT ALPINE', 30, 9000),
    plu('P00014', 'PINT TADDY LAGER', 35, 14000),
    plu('P00016', 'PINT PURE BREW', 27, 14310),
    plu('P00017', 'PINT STOUT', 2, 800),
    plu('P00018', 'HALF OBB', 3, 540),
    plu('P00020', 'HALF ALPINE', 14, 2100),
    plu('P00021', 'HALF TADDY LAGER', 9, 1800),
    plu('P00023', 'HALF PURE BREW', 1, 265),
    plu('P00024', 'HALF STOUT', 1, 200),
    plu('P00029', '125ML HOUSE WINE', 8, 3000),
    plu('P00030', '175ML HOUSE WINE', 2, 1050),
    plu('P00038', '250ML ROSE', 6, 4500),
    plu('P00039', 'WHISKY', 1, 315),
    plu('P00041', 'VODKA', 12, 3780),
    plu('P00042', 'DARK RUM', 4, 1260),
    plu('P00059', '550ml alc free', 4, 1920),
    plu('P00064', 'PEAR CIDER BTL', 4, 2480),
    plu('P00069', 'HALF POST MIX', 17, 3230),
    plu('P00070', 'DASH', 6, 360),
    plu('P00074', 'CRISPS', 7, 1190),
    plu('P00076', 'DRY ROAST', 1, 170),
  ],
  pluTotal: { qtyMilli: 244000, pence: 83630 },
}

/** The two of them, as the night actually was. */
export const BOTH = {
  pence: 201105,
  qtyMilli: 590000,
  cashPence: 71700,
  cardPence: 129405,
  guestCount: 264,
}

/**
 * One session cut into the photographs she takes of it.
 *
 * The top carries the header, the departments and the transaction block; the
 * PLU list and the clerk split are further down the roll and carry no header of
 * their own — which is the whole difficulty, because a headerless photograph
 * gives nothing away about which session it came off.
 *
 * `stampClerks` puts a header back on the clerk report, as the till does when
 * it prints one separately: same timestamp, *next* receipt number.
 */
export function asPhotos(
  z: ZRead,
  opts: { stampClerks?: boolean } = {},
): { top: ZRead; items: ZRead; clerks: ZRead } {
  const top: ZRead = { ...z, plus: [], clerks: [] }
  delete (top as { pluTotal?: unknown }).pluTotal

  const items: ZRead = {
    header: {},
    departments: [],
    groups: [],
    transaction: {},
    clerks: [],
    plus: z.plus,
    ...(z.pluTotal ? { pluTotal: z.pluTotal } : {}),
  }

  const clerks: ZRead = {
    header: opts.stampClerks
      ? {
          receiptNo: String(Number(z.header.receiptNo) + 2),
          ...(z.header.printedAt ? { printedAt: z.header.printedAt } : {}),
        }
      : {},
    departments: [],
    groups: [],
    transaction: {
      ...(z.transaction.paidTotalPence !== undefined
        ? { paidTotalPence: z.transaction.paidTotalPence }
        : {}),
      ...(z.transaction.guestCount !== undefined ? { guestCount: z.transaction.guestCount } : {}),
    },
    clerks: z.clerks,
    plus: [],
  }

  return { top, items, clerks }
}
