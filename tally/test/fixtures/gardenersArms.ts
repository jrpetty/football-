// ---------------------------------------------------------------------------
// A real Z read: the Gardeners Arms, 23 August 2026, Z counter 1685.
//
// Transcribed from photographs of the actual roll, IN THE LAYOUT THE TILL
// ACTUALLY PRINTS. That qualifier is the whole point of this file. An earlier
// version of it invented a tidy one-line-per-department format, the parser was
// built to match, and every test passed against a receipt that does not exist.
//
// The real thing splits a department across three lines:
//
//     D01                    406.000 Q     <- code and quantity
//     DRAUGHT BEERS           *1492.25     <- name and value
//                               68.05%     <- percentage, on its own
//
// and does the same to CASH, CREDIT CARD, VOID, the group subtotals and the
// department total. Any parser that assumes one line per record reads nothing
// at all from this receipt.
//
// The summary and clerk sections are transcribed with confidence *because* they
// cross-foot — every equation the receipt states about itself holds on these
// figures, which is a far stronger guarantee than careful reading.
//
// One digit in here was wrong until the receipt itself disproved it. GT3 was
// transcribed as -21185.57 from "-00000021185.57", where the leading zeros hide
// where the number starts. GT1 + |GT3| = GT2 holds to the penny only at
// -2185.57, so that is what it is. There is now a check for it.
//
// The item list IS now included, and it is included because it reconciles: all
// 38 lines come to 689 items and £2,192.80, matching the roll's own ***TOTAL on
// both counts, and the first twelve come to exactly D01's 406 items and
// £1,492.25. An earlier attempt was abandoned when the spirits appeared to come
// to £60.60 against a printed £35.25 — but that was a bad guess at which
// department each item belongs to, which the receipt never states. The item
// figures themselves were right.
// ---------------------------------------------------------------------------

import type { ZRead } from '../../src/core/zread.ts'

/** The roll exactly as printed, line breaks included. */
export const GARDENERS_ARMS_TEXT = `Samuel Smith
#1233    23/08/2026 21:39:16            000000
0004 CLERK0004
                    *Z1*
GT1                         *0000140111.26     Z1 1685
GT2                         *0000142296.83
GT3                         -0000002185.57

DEPT./GROUP
D01                             406.000 Q
DRAUGHT BEERS                    *1492.25
                                   68.05%
D02                              11.000 Q
SPIRITS                            *35.25
                                    1.61%
D03                              43.000 Q
WINE                              *234.80
                                   10.71%
D04                               4.000 Q
BOTTLED BEERS                      *27.00
                                    1.23%
D05                             137.000 Q
MIXERS                            *252.90
                                   11.53%
D07                              86.000 Q
SUNDRIES                          *146.20
                                    6.67%
GROUP01                         687.000 Q
                                 *2188.40
                                   99.80%

D08                               2.000 Q
OPEN FOOD                           *4.40
                                    0.20%
GROUP02                           2.000 Q
                                    *4.40
                                    0.20%

*DEPT TL                        689.000 Q
                                 *2192.80
                                  100.00%

TRANSACTION
NET1                             *2192.80
NET2                             *2192.80
VOID                                  3 Q
                                   *12.50
NO SALE                               5 Q
GUEST                               267 Q
ORDER TL                         *2192.80
PAID TL                          *2192.80
AVE.                                *8.21
CASH                                 57 Q
                                  *351.80
CREDIT CARD                         210 Q
                                 *1841.00
****CID                           *351.80
CA/CHK ID                         *351.80

ALL CLERK    *Z1*
CLK#0001 CLERK0001
PAID TL                             *0.00
CLK#0002 CLERK0002
ORDER TL                            *4.00
NON COM.                            *4.00
PAID TL                             *4.00
AVE.                                *4.00
GUEST                                 1 Q
CREDIT CARD                           1 Q
                                    *4.00
CLK#0004 CLERK0004
ORDER TL                         *2188.80
NON COM.                         *2188.80
PAID TL                          *2188.80
AVE.                                *8.23
VOID                                  3 Q
                                   *12.50
GUEST                               266 Q
CASH                                 57 Q
                                  *351.80
CREDIT CARD                         209 Q
                                 *1837.00
****CID                           *351.80
CA/CHK ID                         *351.80
***TOTAL
ORDER TL                         *2192.80
NON COM.                         *2192.80
PAID TL                          *2192.80
AVE.                                *8.21
VOID                                  3 Q
                                   *12.50
GUEST                               267 Q
CASH                                 57 Q
                                  *351.80
CREDIT CARD                         210 Q
                                 *1841.00
****CID                           *351.80
CA/CHK ID                         *351.80
GARDENERS ARMS

PLU/EAN
PLU                       00001-9999999999
P00001                  5.000 Q
PINT DARK MILD          *14.00
P00002                  28.000 Q
PINT CIDER              *148.40
P00007                  6.000 Q
HALF CIDER              *15.90
P00011                  66.000 Q
PINT OBB                *237.60
P00013                  66.000 Q
PINT ALPINE             *198.00
P00014                  120.000 Q
PINT TADDY LAGER        *480.00
P00016                  40.000 Q
PINT PURE BREW          *212.00
P00017                  24.000 Q
PINT STOUT              *96.00
P00018                  3.000 Q
HALF OBB                *5.40
P00020                  26.000 Q
HALF ALPINE             *39.00
P00021                  19.000 Q
HALF TADDY LAGER        *38.00
P00023                  3.000 Q
HALF PURE BREW          *7.95
P00029                  8.000 Q
125ML HOUSE WINE        *30.00
P00030                  7.000 Q
175ML HOUSE WINE        *36.75
P00031                  11.000 Q
250ML HOUSE WINE        *82.50
P00032                  2.000 Q
BOURBON                 *8.10
P00034                  4.000 Q
Spiced rum              *13.80
P00035                  1.000 Q
PEACH SCHNAPPS          *3.45
P00037                  6.000 Q
175ML ROSE              *31.50
P00038                  3.000 Q
250ML ROSE              *22.50
P00040                  2.000 Q
GIN                     *6.30
P00041                  8.000 Q
VODKA                   *25.20
P00047                  1.000 Q
Raspgin                 *3.75
P00050                  1.000 Q
Bot pure brew           *6.20
P00053                  6.000 Q
GINGER BEER             *22.80
P00054                  5.000 Q
ELDERFLOWER             *19.00
P00058                  3.000 Q
FRUIT BEER              *22.20
P00059                  1.000 Q
550ml alc free          *4.80
P00060                  1.000 Q
ORANGE JUICE            *3.80
P00067                  3.000 Q
TONIC                   *8.70
P00068                  3.000 Q
O A P                   *9.90
P00069                  73.000 Q
HALF POST MIX           *138.70
P00070                  39.000 Q
DASH                    *23.40
P00073                  7.000 Q
APPLE JUICE             *26.60
P00074                  79.000 Q
CRISPS                  *134.30
P00075                  3.000 Q
SALTED NUTS             *5.10
P00076                  4.000 Q
DRY ROAST               *6.80
P00080                  2.000 Q
OPEN FOOD               *4.40
***TOTAL                        689.000 Q
                                 *2192.80
*SET PLU*
EAN
*SET EAN*
`

/**
 * The same roll flattened onto one line per record.
 *
 * Kept because a vision model asked to transcribe may tidy the columns up
 * despite being told not to, and reading the receipt must not depend on which
 * way it chose. Both layouts have to produce the identical result.
 */
export const GARDENERS_ARMS_FLAT = `Samuel Smith
#1233    23/08/2026 21:39:16      000000
0004 CLERK0004
        *Z1*
GT1     *0000140111.26     Z1  1685
GT2     *0000142296.83
GT3      -0000002185.57

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

PLU/EAN
PLU                       00001-9999999999
P00001 PINT DARK MILD           5.000 Q   *   14.00
P00002 PINT CIDER              28.000 Q   *  148.40
P00007 HALF CIDER               6.000 Q   *   15.90
P00011 PINT OBB                66.000 Q   *  237.60
P00013 PINT ALPINE             66.000 Q   *  198.00
P00014 PINT TADDY LAGER       120.000 Q   *  480.00
P00016 PINT PURE BREW          40.000 Q   *  212.00
P00017 PINT STOUT              24.000 Q   *   96.00
P00018 HALF OBB                 3.000 Q   *    5.40
P00020 HALF ALPINE             26.000 Q   *   39.00
P00021 HALF TADDY LAGER        19.000 Q   *   38.00
P00023 HALF PURE BREW           3.000 Q   *    7.95
P00029 125ML HOUSE WINE         8.000 Q   *   30.00
P00030 175ML HOUSE WINE         7.000 Q   *   36.75
P00031 250ML HOUSE WINE        11.000 Q   *   82.50
P00032 BOURBON                  2.000 Q   *    8.10
P00034 Spiced rum               4.000 Q   *   13.80
P00035 PEACH SCHNAPPS           1.000 Q   *    3.45
P00037 175ML ROSE               6.000 Q   *   31.50
P00038 250ML ROSE               3.000 Q   *   22.50
P00040 GIN                      2.000 Q   *    6.30
P00041 VODKA                    8.000 Q   *   25.20
P00047 Raspgin                  1.000 Q   *    3.75
P00050 Bot pure brew            1.000 Q   *    6.20
P00053 GINGER BEER              6.000 Q   *   22.80
P00054 ELDERFLOWER              5.000 Q   *   19.00
P00058 FRUIT BEER               3.000 Q   *   22.20
P00059 550ml alc free           1.000 Q   *    4.80
P00060 ORANGE JUICE             1.000 Q   *    3.80
P00067 TONIC                    3.000 Q   *    8.70
P00068 O A P                    3.000 Q   *    9.90
P00069 HALF POST MIX           73.000 Q   *  138.70
P00070 DASH                    39.000 Q   *   23.40
P00073 APPLE JUICE              7.000 Q   *   26.60
P00074 CRISPS                  79.000 Q   *  134.30
P00075 SALTED NUTS              3.000 Q   *    5.10
P00076 DRY ROAST                4.000 Q   *    6.80
P00080 OPEN FOOD                2.000 Q   *    4.40
***TOTAL                     689.000 Q   *2192.80
*SET PLU*
EAN
*SET EAN*
`

/** The roll as the app should end up holding it, from either layout. */
export const GARDENERS_ARMS: ZRead = {
  header: {
    receiptNo: '1233',
    zNumber: 1685,
    clerk: '0004 CLERK0004',
    printedAt: '23/08/2026 21:39:16',
    gt1Pence: 14011126,
    gt2Pence: 14229683,
    gt3Pence: -218557,
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
  // Verified by the roll's own arithmetic, not by careful reading: these sum to
  // the printed ***TOTAL on both quantity and value.
  plus: [
    { code: 'P00001', name: 'PINT DARK MILD', qtyMilli: 5000, pence: 1400 },
    { code: 'P00002', name: 'PINT CIDER', qtyMilli: 28000, pence: 14840 },
    { code: 'P00007', name: 'HALF CIDER', qtyMilli: 6000, pence: 1590 },
    { code: 'P00011', name: 'PINT OBB', qtyMilli: 66000, pence: 23760 },
    { code: 'P00013', name: 'PINT ALPINE', qtyMilli: 66000, pence: 19800 },
    { code: 'P00014', name: 'PINT TADDY LAGER', qtyMilli: 120000, pence: 48000 },
    { code: 'P00016', name: 'PINT PURE BREW', qtyMilli: 40000, pence: 21200 },
    { code: 'P00017', name: 'PINT STOUT', qtyMilli: 24000, pence: 9600 },
    { code: 'P00018', name: 'HALF OBB', qtyMilli: 3000, pence: 540 },
    { code: 'P00020', name: 'HALF ALPINE', qtyMilli: 26000, pence: 3900 },
    { code: 'P00021', name: 'HALF TADDY LAGER', qtyMilli: 19000, pence: 3800 },
    { code: 'P00023', name: 'HALF PURE BREW', qtyMilli: 3000, pence: 795 },
    { code: 'P00029', name: '125ML HOUSE WINE', qtyMilli: 8000, pence: 3000 },
    { code: 'P00030', name: '175ML HOUSE WINE', qtyMilli: 7000, pence: 3675 },
    { code: 'P00031', name: '250ML HOUSE WINE', qtyMilli: 11000, pence: 8250 },
    { code: 'P00032', name: 'BOURBON', qtyMilli: 2000, pence: 810 },
    { code: 'P00034', name: 'Spiced rum', qtyMilli: 4000, pence: 1380 },
    { code: 'P00035', name: 'PEACH SCHNAPPS', qtyMilli: 1000, pence: 345 },
    { code: 'P00037', name: '175ML ROSE', qtyMilli: 6000, pence: 3150 },
    { code: 'P00038', name: '250ML ROSE', qtyMilli: 3000, pence: 2250 },
    { code: 'P00040', name: 'GIN', qtyMilli: 2000, pence: 630 },
    { code: 'P00041', name: 'VODKA', qtyMilli: 8000, pence: 2520 },
    { code: 'P00047', name: 'Raspgin', qtyMilli: 1000, pence: 375 },
    { code: 'P00050', name: 'Bot pure brew', qtyMilli: 1000, pence: 620 },
    { code: 'P00053', name: 'GINGER BEER', qtyMilli: 6000, pence: 2280 },
    { code: 'P00054', name: 'ELDERFLOWER', qtyMilli: 5000, pence: 1900 },
    { code: 'P00058', name: 'FRUIT BEER', qtyMilli: 3000, pence: 2220 },
    { code: 'P00059', name: '550ml alc free', qtyMilli: 1000, pence: 480 },
    { code: 'P00060', name: 'ORANGE JUICE', qtyMilli: 1000, pence: 380 },
    { code: 'P00067', name: 'TONIC', qtyMilli: 3000, pence: 870 },
    { code: 'P00068', name: 'O A P', qtyMilli: 3000, pence: 990 },
    { code: 'P00069', name: 'HALF POST MIX', qtyMilli: 73000, pence: 13870 },
    { code: 'P00070', name: 'DASH', qtyMilli: 39000, pence: 2340 },
    { code: 'P00073', name: 'APPLE JUICE', qtyMilli: 7000, pence: 2660 },
    { code: 'P00074', name: 'CRISPS', qtyMilli: 79000, pence: 13430 },
    { code: 'P00075', name: 'SALTED NUTS', qtyMilli: 3000, pence: 510 },
    { code: 'P00076', name: 'DRY ROAST', qtyMilli: 4000, pence: 680 },
    { code: 'P00080', name: 'OPEN FOOD', qtyMilli: 2000, pence: 440 },
  ],
  pluTotal: { qtyMilli: 689000, pence: 219280 },
}
