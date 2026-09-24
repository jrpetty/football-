# E3 - SaaS agreement with two amendments (one partly reversing the other), an unsigned side letter, CPI indexation,
# an SLA month with incidents and a late correction, a liability cap and a termination-for-convenience fee.
import datetime as dt, calendar
from decimal import Decimal as D, ROUND_HALF_UP, ROUND_FLOOR
def r2(x): return x.quantize(D('0.01'), ROUND_HALF_UP)
def r0(x): return x.quantize(D('1'), ROUND_HALF_UP)

INSTRUCTIONS = ("Below is the contract file for a software subscription, compiled by the provider's legal team on 16 August 2027. "
                "Work out the contractual position that results from ALL of the documents in the file and extract it into JSON that follows the schema exactly.")

DOC = r"""
CONTRACT FILE - ORBIS ANALYTICS LTD / MARROW & FINCH RETAIL PLC
Compiled by Orbis Legal on 16 August 2027. Documents in date order.

======================================================================
DOCUMENT 1 - MASTER SUBSCRIPTION AGREEMENT dated 3 March 2025 (extract; clauses not reproduced are not relevant)
between ORBIS ANALYTICS LTD ("Orbis") and MARROW & FINCH RETAIL PLC (the "Customer")

1. DEFINITIONS
1.1 "Effective Date" means 1 April 2025.
1.2 "Month" means a calendar month. "Contract Year" means each period of 12 Months starting on the Effective Date or on an anniversary of it.
1.3 "Named User" means an individual who holds login credentials for the Production Service that are active at any time during the relevant Month, excluding credentials held by Orbis personnel for support purposes.
1.4 "Business Day" means a day other than a Saturday, a Sunday or a public holiday in England.
1.5 "Downtime" means the number of minutes in a Month during which the Production Service is unavailable, excluding (a) Scheduled Maintenance and (b) unavailability caused by the Customer's own systems or configuration. "Scheduled Maintenance" means maintenance of which Orbis has notified the Customer by email at least 5 Business Days in advance.
1.6 "Availability" for a Month means (total minutes in the Month minus Downtime) divided by total minutes in the Month, multiplied by 100 and rounded DOWN to two decimal places.

3. TERM
3.1 This Agreement starts on the Effective Date and continues for an initial term of 36 Months (the "Initial Term").
3.2 After the Initial Term this Agreement renews automatically for successive periods of 12 Months unless either party gives the other at least 90 days' written notice of non-renewal before the end of the then-current term.

4. FEES
4.1 The Customer shall pay a platform fee of GBP 18,500 per Month (the "Platform Fee"), which includes up to 250 Named Users. For each Named User above that number in a Month the Customer shall pay GBP 62.00 for that Month (the "User Fee").
4.2 With effect from each anniversary of the Effective Date, the Platform Fee shall be increased by the CPI Rate, provided that the increase shall be no less than 2% and no more than 5%. The increased Platform Fee is rounded to the nearest whole pound. The User Fee is not indexed.
4.3 "CPI Rate" means the 12-month rate of the Consumer Prices Index published by the Office for National Statistics for the month of January immediately preceding the relevant anniversary.
4.4 Fees are invoiced monthly in arrears. VAT at the applicable rate (currently 20%) is added to every invoice.

5. SERVICE LEVELS
5.1 Orbis shall use reasonable endeavours to achieve Availability of at least 99.9% in every Month.
5.2 If Availability in a Month is below 99.9%, the Customer is entitled to a service credit of 5% of the Platform Fee for that Month; if it is below 99.5%, 10%; and if it is below 99.0%, 25%. Service credits are deducted from the invoice for the following Month.

9. LIABILITY
9.1 Each party's total liability arising out of or in connection with this Agreement in any Contract Year is limited to the total fees paid or payable by the Customer in the 12 Months preceding the event giving rise to the claim.

11. TERMINATION FOR CONVENIENCE
11.1 The Customer may terminate this Agreement for convenience with effect from any date after the end of the first Contract Year by giving not less than 6 months' written notice.
11.2 On termination under clause 11.1 the Customer shall pay an early termination fee equal to 50% of the Platform Fees that would have been payable for the remainder of the Initial Term after the termination date.

19. NOTICES
19.1 A notice under this Agreement must be in writing and is deemed given on the date on which it is received by the addressee.

======================================================================
DOCUMENT 2 - AMENDMENT No. 1, dated 18 September 2025, effective 1 October 2025

The parties agree that with effect from 1 October 2025 the Agreement is amended as follows:
1. Clause 4.1 is deleted and replaced with: "The Customer shall pay a platform fee of GBP 21,000 per Month (the "Platform Fee"), which includes up to 300 Named Users. For each Named User above that number in a Month the Customer shall pay GBP 58.00 for that Month (the "User Fee")."
2. A new clause 4.5 is inserted: "4.5 The Customer shall pay GBP 2,400 per Month for the Forecasting Module. Clause 4.2 applies to the Forecasting Module fee in the same way as it applies to the Platform Fee (the two fees being indexed and rounded separately), but the Forecasting Module fee is not part of the Platform Fee for any other purpose."
3. In clause 1.5 the words "at least 5 Business Days in advance" are replaced with "at least 48 hours in advance".
4. Clause 5.2 is deleted and replaced with: "If Availability in a Month is below 99.95%, the Customer is entitled to a service credit of 5% of the Platform Fee for that Month; if it is below 99.9%, 10%; if it is below 99.5%, 15%; and if it is below 99.0%, 25%. Service credits are deducted from the invoice for the following Month."
5. In clause 11.1 the words "6 months'" are replaced with "4 months'".
All other terms of the Agreement remain unchanged.

======================================================================
DOCUMENT 3 - AMENDMENT No. 2, dated 24 February 2026, effective 1 March 2026

The parties agree that with effect from 1 March 2026 the Agreement (as amended by Amendment No. 1) is amended as follows:
1. Clause 3.1 is deleted and replaced with: "This Agreement starts on the Effective Date and continues until 31 March 2029 (the "Initial Term")."
2. Clause 4.2 is deleted and replaced with: "With effect from each anniversary of the Effective Date, the Platform Fee shall be increased by the CPI Rate, provided that the increase shall not exceed 4%. If the CPI Rate is zero or negative the Platform Fee does not change. The increased Platform Fee is rounded to the nearest whole pound. The User Fee is not indexed."
3. Clause 4.5 (Forecasting Module) is deleted with effect from 1 July 2026. No Forecasting Module fee is payable for any Month starting on or after that date.
4. Clause 5.2 as amended by Amendment No. 1 is deleted and replaced with the text of clause 5.2 as it stood in the Agreement when the Agreement was signed on 3 March 2025.
5. Clause 9.1 is deleted and replaced with: "Each party's total liability arising out of or in connection with this Agreement in any Contract Year is limited to the greater of (a) GBP 400,000 and (b) 150% of twelve times the monthly Platform Fee in force on the date of the event giving rise to the claim."
6. Clause 11 is deleted and replaced with:
"11.1 The Customer may terminate this Agreement for convenience with effect from the last day of any Month ending after 31 March 2027 by giving not less than 3 months' written notice.
11.2 On termination under clause 11.1 the Customer shall pay an early termination fee equal to 40% of the Platform Fees that would have been payable for the Months of the Initial Term falling after the termination date, calculated at the monthly Platform Fee in force on the termination date. User Fees and any Forecasting Module fee are disregarded for this purpose."
All other terms of the Agreement as amended by Amendment No. 1 remain unchanged.

======================================================================
DOCUMENT 4 - DRAFT SIDE LETTER from Orbis to the Customer, dated 10 March 2026

"Because publication of the January 2026 CPI figure was delayed, we propose that for the anniversary falling on 1 April 2026 only, the CPI Rate shall be the 12-month rate for February 2026 instead of January 2026. This letter becomes binding only when countersigned by the Customer."

Email from Priya Raman (Head of Procurement, Marrow & Finch Retail plc) to Orbis, 16 March 2026:
"Thank you for the side letter of 10 March. The January 2026 figure has now been published, so we see no reason to depart from the Agreement and we will not be countersigning the letter."

======================================================================
DOCUMENT 5 - CPI RATES (ONS, Consumer Prices Index, 12-month rate)
January 2026: 1.6%    February 2026: 2.9%    January 2027: 4.6%    February 2027: 3.8%

======================================================================
DOCUMENT 6 - ORBIS SERVICE REPORT, JUNE 2027 (issued 5 July 2027; all times UK local time)

Named User credentials active during June 2027: 321, of which 3 are Orbis support accounts.
(For comparison: May 2027: 309 active credentials, of which 3 Orbis support accounts; May 2027 had no Downtime of any kind.)

Incidents affecting the Production Service in June 2027:
#1  Wed 2 June, 01:00-02:30 (90 min). Planned database upgrade. Notified to the Customer by email on Thu 20 May at 16:00.
#2  Wed 9 June, 10:05-11:40 (95 min). Unplanned outage: storage cluster failure.
#3  Thu 17 June, 00:30-02:30 (120 min). Maintenance: security patching of the application servers. Notified to the Customer by email on Mon 14 June at 09:00.
#4  Wed 23 June, 13:00-13:25 (25 min). Users unable to log in because the Customer's single sign-on certificate had expired on the Customer's side.
#5  Mon 28 June, 14:10-14:52 (42 min). Unplanned outage: faulty configuration release.

Post-incident review addendum (issued 12 July 2027): for incident #5, the service was fully restored at 14:37; the monitoring alert cleared late, which is why the report showed 14:52. The duration of incident #5 is corrected to 27 minutes. No other incident is affected.

======================================================================
DOCUMENT 7 - LETTER from the Customer to Orbis, dated 21 July 2027, received 22 July 2027

"We notify you of a claim arising from the faulty configuration release of 28 June 2027 (incident #5), which corrupted our store replenishment forecasts. We will provide details of our losses in due course."

======================================================================
DOCUMENT 8 - LETTER from the Customer to Orbis, dated Tuesday 10 August 2027, received by Orbis on Thursday 12 August 2027

"We hereby give notice under clause 11.1 of the Agreement that we terminate the Agreement for convenience with effect from the earliest date permitted under the Agreement."
"""

SCHEMA = r"""
{
  "provider": string,                               // company name exactly as written in the agreement's parties line (letter case is not checked)
  "customer": string,                               // same
  "effective_date": "YYYY-MM-DD",
  "initial_term_end": "YYYY-MM-DD",                 // last day of the Initial Term as it now stands
  "platform_fee_history": [                         // one entry for the Effective Date and one for every later date on which the monthly Platform Fee changed, up to 16 August 2027, in chronological order
    { "from": "YYYY-MM-DD", "monthly_fee_gbp": number }
  ],
  "increase_2026_percent": number,                  // percentage increase actually applied to the Platform Fee on 1 April 2026 (e.g. 2.5)
  "increase_2027_percent": number,                  // percentage increase actually applied to the Platform Fee on 1 April 2027
  "side_letter_binding": boolean,
  "forecasting_fee_june_2026_gbp": number,          // Forecasting Module fee for the Month of June 2026
  "forecasting_last_month": "YYYY-MM",              // last Month for which a Forecasting Module fee is payable
  "included_named_users": number,                   // Named Users included in the Platform Fee in June 2027
  "june_2027_named_users": number,
  "june_2027_user_fees_gbp": number,
  "june_2027_invoice_net_gbp": number,              // invoice for the Month of June 2027, before VAT
  "june_2027_invoice_vat_gbp": number,
  "june_2027_downtime_minutes": number,
  "june_2027_availability_percent": number,         // as defined in the agreement
  "june_2027_service_credit_percent": number,       // 0 if none
  "june_2027_service_credit_gbp": number,           // 0 if none
  "liability_cap_gbp": number,                      // the cap applicable to the claim notified on 22 July 2027
  "termination_notice_date": "YYYY-MM-DD",          // the date on which the termination notice is deemed given
  "termination_date": "YYYY-MM-DD",                 // the date with effect from which the Agreement terminates
  "etf_months": number,                             // number of Months on which the early termination fee is calculated
  "early_termination_fee_gbp": number
}
"""

EXTRA_RULES = ("Money amounts are JSON numbers in GBP, rounded to 2 decimals (half up) unless the documents give a different rounding rule. "
               "\"N months' notice\" given on a date D means that the termination date may not be earlier than the day with the same day number N calendar months after D.")

def expected():
    cpi = {(2026, 1): D('1.6'), (2026, 2): D('2.9'), (2027, 1): D('4.6'), (2027, 2): D('3.8')}
    side_letter_binding = False                                  # never countersigned
    # Clause 4.2 in force on each anniversary: 1 Apr 2026 and 1 Apr 2027 are both after Amendment 2 (effective 1 Mar 2026): cap 4%, no floor.
    def inc(year):
        rate = cpi[(year, 1)]
        return max(D(0), min(rate, D(4)))
    fee = {dt.date(2025, 4, 1): D(18500), dt.date(2025, 10, 1): D(21000)}
    i26, i27 = inc(2026), inc(2027)
    f26 = r0(D(21000) * (1 + i26 / 100)); fee[dt.date(2026, 4, 1)] = f26
    f27 = r0(f26 * (1 + i27 / 100)); fee[dt.date(2027, 4, 1)] = f27
    fc26 = r0(D(2400) * (1 + i26 / 100))                         # indexed separately, deleted from 1 July 2026
    # June 2027 usage
    named = 321 - 3; included = 300; user_fee = D('58.00')
    user_fees = max(0, named - included) * user_fee
    net = f27 + user_fees; vat = r2(net * D('0.20'))
    # Downtime: #1 scheduled (13 days notice), #2 95, #3 notice 63.5 h >= 48 h (Amendment 1 changed clause 1.5; Amendment 2 did not revert it) -> excluded,
    # #4 customer-caused -> excluded, #5 corrected to 27.
    notice3 = dt.datetime(2027, 6, 17, 0, 30) - dt.datetime(2027, 6, 14, 9, 0)
    assert notice3 >= dt.timedelta(hours=48)
    downtime = 95 + 27
    minutes = 30 * 24 * 60
    avail = (D(minutes - downtime) / D(minutes) * 100).quantize(D('0.01'), ROUND_FLOOR)
    # Original clause 5.2 reinstated by Amendment 2.
    pct = 25 if avail < D('99.0') else 10 if avail < D('99.5') else 5 if avail < D('99.9') else 0
    credit = r2(f27 * pct / 100)
    # Liability: greater of 400,000 and 150% x 12 x Platform Fee in force on 28 June 2027.
    cap = max(D(400000), r2(D('1.5') * 12 * f27))
    # Termination: notice received 12 Aug 2027; earliest month-end on or after 12 Nov 2027.
    notice = dt.date(2027, 8, 12); earliest = dt.date(2027, 11, 12)
    term = dt.date(2027, 11, calendar.monthrange(2027, 11)[1]); assert term >= earliest
    months = (2029 - 2027) * 12 + (3 - 11)                         # Dec 2027 .. Mar 2029
    assert months == 16
    etf = r2(D('0.40') * months * f27)
    f = float
    hist = [{'from': d.isoformat(), 'monthly_fee_gbp': f(v)} for d, v in sorted(fee.items())]
    return {
        'provider': 'Orbis Analytics Ltd', 'customer': 'Marrow & Finch Retail plc', 'effective_date': '2025-04-01', 'initial_term_end': '2029-03-31',
        'platform_fee_history': hist, 'increase_2026_percent': f(i26), 'increase_2027_percent': f(i27), 'side_letter_binding': side_letter_binding,
        'forecasting_fee_june_2026_gbp': f(fc26), 'forecasting_last_month': '2026-06', 'included_named_users': included,
        'june_2027_named_users': named, 'june_2027_user_fees_gbp': f(user_fees), 'june_2027_invoice_net_gbp': f(net), 'june_2027_invoice_vat_gbp': f(vat),
        'june_2027_downtime_minutes': downtime, 'june_2027_availability_percent': f(avail), 'june_2027_service_credit_percent': pct,
        'june_2027_service_credit_gbp': f(credit), 'liability_cap_gbp': f(cap), 'termination_notice_date': notice.isoformat(),
        'termination_date': term.isoformat(), 'etf_months': months, 'early_termination_fee_gbp': f(etf),
    }

NOTES = ("Traps: Amendment 2 replaces clause 4.2 before the first anniversary, so the 2026 increase is the January 2026 CPI of 1.6% with no 2% floor "
         "(21,000 -> 21,336); the side letter (February CPI 2.9%) was never countersigned; 2027: January CPI 4.6% capped at 4% (21,336 x 1.04 = 22,189.44 -> 22,189). "
         "Forecasting fee indexed separately (2,400 -> 2,438) and deleted from 1 July 2026. Named Users exclude 3 Orbis support accounts (318; 18 x GBP 58). "
         "Downtime: #1 scheduled; #3 notified 63.5 h ahead, which satisfies the 48-hour rule that Amendment 1 put into clause 1.5 (Amendment 2 only reverted 5.2); "
         "#4 customer-caused; #5 corrected 42 -> 27 min; 95 + 27 = 122 min of 43,200 -> 99.71% (rounded down) -> original clause 5.2 reinstated -> 5%. "
         "Liability: max(400,000; 1.5 x 12 x 22,189 = 399,402) = 400,000. Termination: notice deemed given on receipt 12 Aug 2027, 3 months -> 12 Nov, "
         "must be a month end -> 30 Nov 2027; ETF = 40% x 16 months (Dec 2027 - Mar 2029) x 22,189. Computed with Decimal in verification/frontier-extraction/e3.py.")

if __name__ == '__main__':
    import json; e = expected(); print(json.dumps(e, indent=1)); print(len(DOC.split()), 'words')
