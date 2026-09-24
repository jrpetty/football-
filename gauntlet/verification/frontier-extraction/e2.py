# E2 - multi-currency freight invoice, disputes and a credit note that the model has to recalculate itself.
import datetime as dt
from decimal import Decimal as D, ROUND_HALF_UP
def r2(x): return x.quantize(D('0.01'), ROUND_HALF_UP)

INSTRUCTIONS = ("Read the freight invoice and the email correspondence below. The credit note mentioned at the end has not been sent yet, "
                "so you must work out its effect yourself. Extract the result into JSON that follows the schema exactly.")

DOC = r"""
BRUGMANS FORWARDING B.V.
Waalhaven Z.z. 42, 3089 JH Rotterdam, The Netherlands
VAT NL8612.44.907.B01 - Chamber of Commerce 24418830

INVOICE                                   Invoice no.: BF-26-08812
Invoice date: 14/09/2026                  (all dates in this document are DD/MM/YYYY)
Customer: Tessel Wonen B.V., Kanaalweg 17, 3526 KL Utrecht
Customer VAT: NL8577.20.113.B01           Your reference: TW-IMP-2026-044
Shipment: FCL Ningbo (CNNGB) to Rotterdam (NLRTM), vessel MSC AURELIA voyage 634W
Departure Ningbo: 06/08/2026              Discharge Rotterdam: 07/09/2026
Containers: MSKU 7734120 (40' HC), TGHU 5528907 (40' HC)
Payment terms: 30 days from invoice date, net.

Exchange rates used on this invoice (Brugmans daily rates of 14/09/2026): USD 1 = EUR 0.9150; CNY 1 = EUR 0.1284.
Foreign-currency amounts are converted to EUR line by line, after any line discount, and each converted line is rounded to the cent.
VAT codes: Z = 0% (international transport), S = 21%, N = outside the scope of VAT (disbursement).

Code  Description                              Qty  Unit price  Cur  Disc  Amount (cur)  Amount EUR  VAT
----  ---------------------------------------  ---  ----------  ---  ----  ------------  ----------  ---
O1    Export customs clearance Ningbo            1      650.00  CNY              650.00       83.46  Z
O2    Terminal handling Ningbo (per container)   2    1,180.00  CNY            2,360.00      303.02  Z
O3    Documentation / B/L fee                    1      400.00  CNY              400.00       51.36  Z
F1    Ocean freight 40' HC (per container)       2    2,340.00  USD   5%       4,446.00    4,068.09  Z
F2    Bunker adjustment factor (per container)   2      285.00  USD              570.00      521.55  Z
D1    Terminal handling Rotterdam (per cntr)     2      265.00  EUR              530.00      530.00  S
D2    Import customs declaration                 1       95.00  EUR               95.00       95.00  S
D3    Trucking Rotterdam - Utrecht (per cntr)    2      410.00  EUR              820.00      820.00  S
D4    Demurrage TGHU 5528907 (per day)           4       85.00  EUR              340.00      340.00  S
X1    Import duty paid on your behalf            1    3,412.80  EUR            3,412.80    3,412.80  N
X2    Disbursement fee (2.5% of X1, min. 45.00)  1       85.32  EUR               85.32       85.32  S

Volume rebate 3% (see note 1)
    on Z lines O1-F2:   5,027.48 x 3%                                          -150.82
    on S lines D1-D4:   1,785.00 x 3%                                           -53.55

SUMMARY                                                                     EUR
Net Z lines after rebate                                                 4,876.66
Net S lines after rebate (D1-D4 after rebate, plus X2)                   1,816.77
VAT 21% on net S                                                           381.52
Disbursements (N)                                                        3,412.80
TOTAL                                                                   10,487.75

Note 1: Under the 2026 volume agreement a 3% rebate applies to all freight and service charges (lines O, F and D). It is calculated separately on the EUR total of the Z lines and on the EUR total of the S lines D1-D4, each rebate rounded to the cent, and it is deducted before VAT. No rebate applies to disbursements (X1) or to the disbursement fee (X2).
Note 2: Under our general conditions, USD ocean charges (lines F) are converted at the Brugmans daily rate of the vessel's departure date from the port of loading. All other foreign-currency charges are converted at the Brugmans daily rate of the invoice date.
Note 3: Import VAT is accounted for by you under your Article 23 licence and is not invoiced.

======================================================================
From: Femke de Wit <f.dewit@tesselwonen.nl>
To: Ruud Verbeek <billing@brugmans-forwarding.nl>
Date: 17/09/2026 10:02
Subject: Invoice BF-26-08812 - queries

Dear Ruud,

We have checked invoice BF-26-08812 and have the following points:

(a) Note 2 of your own invoice says that the ocean charges are converted at the rate of the departure date, but the invoice uses the rate of 14/09. Our bank's rate for 06/08/2026 was USD 1 = EUR 0.9050; please use that rate.
(b) Line D1: our quotation says terminal handling in Rotterdam is charged per bill of lading, not per container, so it should be charged once.
(c) Line O3: given the delays on this shipment we would appreciate it if you could waive the documentation fee as a goodwill gesture.
(d) Line D4: the carrier has told us that the free time for TGHU 5528907 was extended because of the terminal system outage in Rotterdam. Can you check?
(e) Our customs broker has filed an amended import declaration with a corrected tariff code, so the import duty will change. The revised assessment should follow shortly.

Kind regards,
Femke de Wit
Accounts Payable, Tessel Wonen B.V.

----------------------------------------------------------------------
From: Ruud Verbeek <billing@brugmans-forwarding.nl>
To: Femke de Wit <f.dewit@tesselwonen.nl>
Date: 18/09/2026 15:40
Subject: RE: Invoice BF-26-08812 - queries

Dear Femke,

Thank you for your careful check.

(a) You are right that the departure-date rate applies to the ocean charges. However, our conditions refer to our own daily rate, not to your bank's. For your information, the Brugmans daily rates of 06/08/2026 were USD 1 = EUR 0.9082 and CNY 1 = EUR 0.1262.
(b) Agreed - quotation Q-2026-311 indeed states THC Rotterdam per B/L. We will correct D1 to one unit.
(c) I am sorry, but we cannot waive the documentation fee; it is a standard charge.
(d) I have asked the carrier and will come back to you.
(e) Noted. Once customs has issued the revised assessment we will adjust X1, and X2 with it.

Best regards,
Ruud Verbeek
Billing, Brugmans Forwarding B.V.

----------------------------------------------------------------------
From: Ruud Verbeek <billing@brugmans-forwarding.nl>
To: Femke de Wit <f.dewit@tesselwonen.nl>
Date: 21/09/2026 08:55
Subject: RE: Invoice BF-26-08812 - queries

Femke, a correction to point (b) of my email of 18/09: I looked at the wrong quotation. Q-2026-311 was our LCL quotation; your FCL quotation Q-2026-298 states THC Rotterdam per container. Line D1 therefore stands as invoiced. My apologies for the confusion.

Ruud

----------------------------------------------------------------------
From: Ruud Verbeek <billing@brugmans-forwarding.nl>
To: Femke de Wit <f.dewit@tesselwonen.nl>
Date: 22/09/2026 16:12
Subject: RE: Invoice BF-26-08812 - queries

Dear Femke,

Two updates:

(d) The carrier has confirmed that the free time for TGHU 5528907 was extended by 2 days, so 2 of the 4 demurrage days on line D4 are cancelled.
(e) Customs has issued the revised assessment: the import duty is now EUR 1,748.40 instead of EUR 3,412.80. Customs will refund the difference to us and we pass it on to you in full.

Best regards,
Ruud

----------------------------------------------------------------------
From: Femke de Wit <f.dewit@tesselwonen.nl>
To: Ruud Verbeek <billing@brugmans-forwarding.nl>
Date: 23/09/2026 09:30
Subject: RE: Invoice BF-26-08812 - queries

Dear Ruud,

Thank you. Two more things:

1. On 02/09/2026 we made an advance payment of EUR 2,000.00 for this shipment (our payment reference TW-ADV-0931). Please confirm that it is allocated to invoice BF-26-08812.
2. With the lower duty, the disbursement fee becomes 2.5% of EUR 1,748.40, i.e. EUR 43.71.

Kind regards,
Femke

----------------------------------------------------------------------
From: Ruud Verbeek <billing@brugmans-forwarding.nl>
To: Femke de Wit <f.dewit@tesselwonen.nl>
Date: 24/09/2026 11:05
Subject: RE: Invoice BF-26-08812 - queries

Dear Femke,

1. Confirmed: the advance payment of EUR 2,000.00 received on 02/09/2026 is allocated in full to invoice BF-26-08812.
2. Please note that the disbursement fee is subject to the minimum of EUR 45.00 shown on line X2, so it cannot be lower than that.

We will issue credit note BF-CN-26-0417 for all corrections we have agreed in this correspondence. The credit note recalculates the whole invoice with those corrections, using exactly the same method as the original invoice (conversion line by line, the rebate as in note 1, VAT on the net S amount), and credits the difference between the TOTAL of the invoice as issued and the TOTAL of the recalculated invoice. The payment term of the original invoice is unchanged. Please pay the invoice total less the credit note and less your advance payment by the original due date.

Best regards,
Ruud Verbeek
Billing, Brugmans Forwarding B.V.
"""

SCHEMA = r"""
{
  "invoice_number": string,
  "credit_note_number": string,
  "invoice_date": "YYYY-MM-DD",
  "due_date": "YYYY-MM-DD",                  // the date by which the remaining balance must be paid
  "containers": [string],                    // container numbers exactly as written, in the order listed on the invoice
  "usd_rate_final": number,                  // EUR per 1 USD used in the recalculated invoice
  "cny_rate_final": number,                  // EUR per 1 CNY used in the recalculated invoice
  "ocean_freight_usd": number,               // line F1 in USD after its line discount
  "lines": [                                 // all 11 invoice lines, in invoice order, as they stand in the RECALCULATED invoice
    { "code": string,                        // e.g. "O1"
      "quantity": number,
      "amount_eur": number }                 // the line's EUR amount before the volume rebate
  ],
  "rebate_z_eur": number,                    // recalculated rebate on the Z lines (a positive number)
  "rebate_s_eur": number,                    // recalculated rebate on lines D1-D4 (a positive number)
  "net_z_eur": number,                       // recalculated "Net Z lines after rebate"
  "net_s_eur": number,                       // recalculated "Net S lines after rebate" (including X2)
  "vat_eur": number,                         // recalculated VAT
  "disbursements_eur": number,               // recalculated disbursements (N)
  "original_total_eur": number,              // TOTAL of the invoice as issued
  "recalculated_total_eur": number,          // TOTAL of the recalculated invoice
  "credit_note_total_eur": number,           // amount of the credit note (a positive number)
  "advance_payment_eur": number,
  "amount_payable_eur": number,              // what Tessel Wonen still has to pay
  "disputed_lines_unchanged": [string]       // codes of the lines Tessel Wonen asked to change in this correspondence whose recalculated amount_eur equals the amount on the invoice as issued, in invoice order
}
"""

EXTRA_RULES = ("All money amounts are in the unit named by the field, as JSON numbers rounded to 2 decimals (half up); exchange rates exactly as quoted. "
               "\"30 days from\" a date means that date plus 30 calendar days.")

def expected():
    usd, cny = D('0.9082'), D('0.1284')
    # (code, qty, unit, currency, discount, vat)
    lines = [('O1', 1, D('650.00'), 'CNY', 0, 'Z'), ('O2', 2, D('1180.00'), 'CNY', 0, 'Z'), ('O3', 1, D('400.00'), 'CNY', 0, 'Z'),
             ('F1', 2, D('2340.00'), 'USD', D('0.05'), 'Z'), ('F2', 2, D('285.00'), 'USD', 0, 'Z'),
             ('D1', 2, D('265.00'), 'EUR', 0, 'S'), ('D2', 1, D('95.00'), 'EUR', 0, 'S'), ('D3', 2, D('410.00'), 'EUR', 0, 'S'),
             ('D4', 4, D('85.00'), 'EUR', 0, 'S'), ('X1', 1, D('3412.80'), 'EUR', 0, 'N')]
    def build(usd_rate, cny_rate, demurrage_days, duty):
        rate = {'USD': usd_rate, 'CNY': cny_rate, 'EUR': D(1)}
        out = []
        for code, q, u, cur, disc, vat in lines:
            if code == 'D4': q = demurrage_days
            if code == 'X1': u = duty
            cur_amt = r2(u * q * (1 - D(disc)))
            out.append((code, q, cur_amt, r2(cur_amt * rate[cur]), vat))
        fee = max(r2(duty * D('0.025')), D('45.00'))
        out.append(('X2', 1, fee, fee, 'S'))
        z = sum(a for c, q, ca, a, v in out if v == 'Z'); s_d = sum(a for c, q, ca, a, v in out if c.startswith('D'))
        rz, rs = r2(z * D('0.03')), r2(s_d * D('0.03'))
        net_z = z - rz; net_s = s_d - rs + fee; vat = r2(net_s * D('0.21')); disb = duty
        total = net_z + net_s + vat + disb
        return dict(lines=out, rz=rz, rs=rs, net_z=net_z, net_s=net_s, vat=vat, disb=disb, total=total, z=z, s_d=s_d)
    orig = build(D('0.9150'), D('0.1284'), 4, D('3412.80'))
    # the printed invoice must be internally consistent with the method
    assert [a for *_, a, v in orig['lines']] == [D(x) for x in '83.46 303.02 51.36 4068.09 521.55 530.00 95.00 820.00 340.00 3412.80 85.32'.split()]
    assert (orig['z'], orig['s_d'], orig['rz'], orig['rs']) == (D('5027.48'), D('1785.00'), D('150.82'), D('53.55'))
    assert (orig['net_z'], orig['net_s'], orig['vat'], orig['total']) == (D('4876.66'), D('1816.77'), D('381.52'), D('10487.75'))
    new = build(usd, cny, 2, D('1748.40'))   # agreed: departure-date USD rate (Brugmans, not bank), 2 demurrage days, revised duty; D1 and O3 unchanged
    credit = orig['total'] - new['total']; advance = D('2000.00')
    unchanged = [c for (c, q, ca, a, v), (c2, q2, ca2, a2, v2) in zip(new['lines'], orig['lines']) if c in ('O3', 'D1', 'F1', 'F2', 'D4', 'X1', 'X2') and a == a2]
    f = float
    return {
        'invoice_number': 'BF-26-08812', 'credit_note_number': 'BF-CN-26-0417', 'invoice_date': '2026-09-14',
        'due_date': (dt.date(2026, 9, 14) + dt.timedelta(days=30)).isoformat(),
        'containers': ['MSKU 7734120', 'TGHU 5528907'], 'usd_rate_final': f(usd), 'cny_rate_final': f(cny),
        'ocean_freight_usd': f(new['lines'][3][2]),
        'lines': [{'code': c, 'quantity': q, 'amount_eur': f(a)} for c, q, ca, a, v in new['lines']],
        'rebate_z_eur': f(new['rz']), 'rebate_s_eur': f(new['rs']), 'net_z_eur': f(new['net_z']), 'net_s_eur': f(new['net_s']),
        'vat_eur': f(new['vat']), 'disbursements_eur': f(new['disb']), 'original_total_eur': f(orig['total']),
        'recalculated_total_eur': f(new['total']), 'credit_note_total_eur': f(credit), 'advance_payment_eur': f(advance),
        'amount_payable_eur': f(new['total'] - advance), 'disputed_lines_unchanged': unchanged,
    }

NOTES = ("Traps: USD lines re-converted at the Brugmans 06/08 rate 0.9082 (not the bank's 0.9050, not the invoice-date 0.9150); CNY lines stay at the invoice-date "
         "rate 0.1284 (note 2) even though a 06/08 CNY rate is quoted; D1 correction agreed on 18/09 but retracted on 21/09; O3 waiver refused; D4 cut to 2 days; "
         "duty 3,412.80 -> 1,748.40 so 2.5% = 43.71 falls below the EUR 45.00 minimum; rebates re-derived per note 1; credit = difference of TOTALS; "
         "advance EUR 2,000 deducted; due date unchanged (14/09 + 30 = 14/10/2026). Disputed lines (O3, D1, F1, F2, D4, X1, X2 - F/X lines via (a),(e) and the 23/09 email) whose amount "
         "is unchanged: O3 and D1. All derived values computed with Decimal in verification/frontier-extraction/e2.py.")

if __name__ == '__main__':
    import json; e = expected(); print(json.dumps(e, indent=1)); print(len(DOC.split()), 'words')
