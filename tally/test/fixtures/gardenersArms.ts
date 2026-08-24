// ---------------------------------------------------------------------------
// A real Z read: the Gardeners Arms, 23 August 2026, Z counter 1685.
//
// Transcribed from photographs of the actual roll. The summary and clerk
// sections are transcribed with confidence *because* they cross-foot — every
// equation the receipt states about itself holds on these figures, which is a
// far stronger guarantee than careful reading.
//
// The PLU list from the same roll is deliberately NOT included. It could not be
// transcribed reliably from the photograph: the item values ran a column out of
// step with their quantities, and the cross-foot caught it (the spirits lines
// came to £60.60 against a printed department total of £35.25). Inventing the
// missing figures to make a fuller fixture would be exactly the failure this
// whole app exists to prevent, so the PLU parser is tested against a small
// constructed case instead, and the real list waits for a legible photograph.
// ---------------------------------------------------------------------------

import type { ZRead } from '../../src/core/zread.ts'

/** The roll as printed, in the layout the till uses. */
export const GARDENERS_ARMS_TEXT = `Samuel Smith
#1233   23/08/2026 21:39:16      000000
0004 CLERK0004
        *Z1*
GT1     *0000140111.26     Z1  1685
GT2     *0000142296.83
GT3     -00000021185.57

DEPT./GROUP
D01 DRAUGHT BEERS      406.000 Q     *1492.25    68.05%
D02 SPIRITS             11.000 Q       *35.25     1.61%
D03 WINE                43.000 Q      *234.80    10.71%
D04 BOTTLED BEERS        4.000 Q       *27.00     1.23%
D05 MIXERS             137.000 Q      *252.90    11.53%
D07 SUNDRIES            86.000 Q      *146.20     6.67%
GROUP01                687.000 Q     *2188.40    99.80%

D08 OPEN FOOD            2.000 Q        *4.40     0.20%
GROUP02                  2.000 Q        *4.40     0.20%

*DEPT TL               689.000 Q     *2192.80   100.00%

TRANSACTION
NET1                                 *2192.80
NET2                                 *2192.80
VOID                         3 Q       *12.50
NO SALE                      5 Q
GUEST                      267 Q
ORDER TL                             *2192.80
PAID TL                              *2192.80
AVE.                                    *8.21
CASH                        57 Q      *351.80
CREDIT CARD                210 Q     *1841.00
****CID                               *351.80
CA/CHK ID                             *351.80

ALL CLERK   *Z1*
CLK#0001 CLERK0001
PAID TL                                 *0.00
CLK#0002 CLERK0002
ORDER TL                                *4.00
NON COM.                                *4.00
PAID TL                                 *4.00
AVE.                                    *4.00
GUEST                        1 Q
CREDIT CARD                  1 Q        *4.00
CLK#0004 CLERK0004
ORDER TL                             *2188.80
NON COM.                             *2188.80
PAID TL                              *2188.80
AVE.                                    *8.23
VOID                         3 Q       *12.50
GUEST                      266 Q
CASH                        57 Q      *351.80
CREDIT CARD                209 Q     *1837.00
****CID                               *351.80
CA/CHK ID                             *351.80
***TOTAL
ORDER TL                             *2192.80
NON COM.                             *2192.80
PAID TL                              *2192.80
AVE.                                    *8.21
VOID                         3 Q       *12.50
GUEST                      267 Q
CASH                        57 Q      *351.80
CREDIT CARD                210 Q     *1841.00
****CID                               *351.80
CA/CHK ID                             *351.80
GARDENERS ARMS
`

/** The same roll, as the app should end up holding it. */
export const GARDENERS_ARMS: ZRead = {
  header: {
    receiptNo: '1233',
    zNumber: 1685,
    clerk: '0004 CLERK0004',
    printedAt: '23/08/2026 21:39:16',
    gt1Pence: 14011126,
    gt2Pence: 14229683,
    gt3Pence: -2118557,
  },
  departments: [
    { code: 'D01', name: 'DRAUGHT BEERS', qtyMilli: 406000, pence: 149225, percentBp: 6805, group: 'GROUP01' },
    { code: 'D02', name: 'SPIRITS', qtyMilli: 11000, pence: 3525, percentBp: 161, group: 'GROUP01' },
    { code: 'D03', name: 'WINE', qtyMilli: 43000, pence: 23480, percentBp: 1071, group: 'GROUP01' },
    { code: 'D04', name: 'BOTTLED BEERS', qtyMilli: 4000, pence: 2700, percentBp: 123, group: 'GROUP01' },
    { code: 'D05', name: 'MIXERS', qtyMilli: 137000, pence: 25290, percentBp: 1153, group: 'GROUP01' },
    { code: 'D07', name: 'SUNDRIES', qtyMilli: 86000, pence: 14620, percentBp: 667, group: 'GROUP01' },
    { code: 'D08', name: 'OPEN FOOD', qtyMilli: 2000, pence: 440, percentBp: 20, group: 'GROUP02' },
  ],
  groups: [
    { code: 'GROUP01', qtyMilli: 687000, pence: 218840, percentBp: 9980 },
    { code: 'GROUP02', qtyMilli: 2000, pence: 440, percentBp: 20 },
  ],
  deptTotal: { qtyMilli: 689000, pence: 219280, percentBp: 10000 },
  transaction: {
    net1Pence: 219280,
    net2Pence: 219280,
    voidCount: 3,
    voidPence: 1250,
    noSaleCount: 5,
    guestCount: 267,
    orderTotalPence: 219280,
    paidTotalPence: 219280,
    avePence: 821,
    cashCount: 57,
    cashPence: 35180,
    cardCount: 210,
    cardPence: 184100,
    cidPence: 35180,
    caChkIdPence: 35180,
  },
  clerks: [
    { code: 'CLK#0001', name: 'CLERK0001', paidTotalPence: 0 },
    {
      code: 'CLK#0002',
      name: 'CLERK0002',
      orderTotalPence: 400,
      nonComPence: 400,
      paidTotalPence: 400,
      avePence: 400,
      guestCount: 1,
      cardCount: 1,
      cardPence: 400,
    },
    {
      code: 'CLK#0004',
      name: 'CLERK0004',
      orderTotalPence: 218880,
      nonComPence: 218880,
      paidTotalPence: 218880,
      avePence: 823,
      voidCount: 3,
      voidPence: 1250,
      guestCount: 266,
      cashCount: 57,
      cashPence: 35180,
      cardCount: 209,
      cardPence: 183700,
      cidPence: 35180,
    },
  ],
  plus: [],
}
