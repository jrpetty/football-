import sys, json, datetime as dt
from decimal import Decimal as D, ROUND_HALF_UP
sys.path.insert(0, '..')
from common import write_test, tok

JSON_RULES = ("Output rules: respond with ONLY the JSON object (you may wrap it in a ```json code block), with exactly the keys listed, in any key order. "
              "Use JSON numbers (not strings) for numeric fields, with no currency symbols, units or thousands separators. Use null where the schema allows it and the value is not given. "
              "Use ISO dates YYYY-MM-DD. Where the document states a correction or a change, use the corrected / final value. Do not add explanations.")

cases = []
def add(cid, d, instructions, doc, schema, expected, notes):
    prompt = f"{instructions}\n\nDocument:\n<<<\n{doc.strip()}\n>>>\n\nSchema:\n{schema.strip()}\n\n{JSON_RULES}"
    cases.append(dict(id=cid, prompt=prompt, expected=expected, notes=f"[{d}] " + notes))

# ---------------------------------------------------------------- e01 invoice
items = [('A12', 24, D('18.50')), ('B07', 24, D('4.25')), ('C33', 8, D('45.00')), ('D02', 1, D('35.00'))]
lt = [q * p for _, q, p in items]
sub = sum(lt); vat = (sub * D('0.20')).quantize(D('0.01'), ROUND_HALF_UP); tot = sub + vat
assert (sub, vat, tot) == (D('941.00'), D('188.20'), D('1129.20'))
inv_date = dt.date(2026, 8, 3); due = inv_date + dt.timedelta(days=30)
add('e01', 'medium', "Extract the invoice below into JSON that follows the schema exactly.", """
NORTHWIND FABRICATION LTD.
Unit 4, Harbour Trading Estate, Cardiff CF10 4XX
VAT Reg: GB 294 1177 03

TAX INVOICE                          Invoice No.: NF-2026-00417
Invoice date: 03/08/2026             (all dates on this invoice are DD/MM/YYYY)
Printed: 05/08/2026 14:22
Customer: Bellweather Theatre Company
Attn: Accounts Payable   -   Your PO ref: BTC-7781
Payment terms: 30 days from invoice date

Item  Description                      Qty   Unit price   Amount
----  -------------------------------  ----  -----------  ---------
A12   Steel stage brackets              24    £18.50       £444.00
B07   Powder coating (per bracket)      24    £4.25        £102.00
C33   Site installation (hours)          9    £45.00       £405.00 *
D02   Delivery                           1    £35.00       £35.00

* Correction: 1 hour of installation was waived, so only 8 hours are billed at the agreed rate. Corrected amount £360.00.

Subtotal (after correction)                             £941.00
VAT @ 20%                                               £188.20
TOTAL DUE                                               £1,129.20

Please quote the invoice number with your remittance. Late payments incur interest of 2% per month.
""", """
{
  "invoice_number": string,
  "invoice_date": "YYYY-MM-DD",
  "due_date": "YYYY-MM-DD",              // invoice date plus the payment-terms period
  "vendor_name": string,                 // copy it exactly as printed in the header, including punctuation (letter case is not checked)
  "customer_name": string,               // copy it exactly as printed
  "purchase_order": string or null,
  "currency": string,                    // ISO 4217 code, e.g. "EUR"
  "line_items": [                        // in the order they appear
    { "item_code": string, "quantity": number, "unit_price": number, "line_total": number }
  ],
  "subtotal": number,
  "tax_rate_percent": number,
  "tax_amount": number,
  "total": number
}
""", {
    'invoice_number': 'NF-2026-00417', 'invoice_date': '2026-08-03', 'due_date': due.isoformat(),
    'vendor_name': 'NORTHWIND FABRICATION LTD.', 'customer_name': 'Bellweather Theatre Company', 'purchase_order': 'BTC-7781', 'currency': 'GBP',
    'line_items': [{'item_code': c, 'quantity': q, 'unit_price': float(p), 'line_total': float(q * p)} for c, q, p in items],
    'subtotal': float(sub), 'tax_rate_percent': 20, 'tax_amount': float(vat), 'total': float(tot)},
    f"Traps: DD/MM/YYYY dates, printed date vs invoice date, line C33 printed as 9 h = £405 but corrected to 8 h = £360 (use corrected quantity and total). Due date {inv_date}+30 days = {due}. Subtotal/VAT/total recomputed in code (941.00 / 188.20 / 1129.20).")

# ---------------------------------------------------------------- e02 email thread
add('e02', 'medium', "Read the email thread below and extract the FINAL agreed meeting details into JSON that follows the schema exactly.", """
From: Priya Raman <priya.raman@kestrel-labs.io>
To: Tomasz Zielinski <t.zielinski@kestrel-labs.io>; Amara Okafor <amara@okafor-design.com>
Sent: Mon 14 Sep 2026 09:12 (UTC+2)
Subject: Q4 roadmap review

Hi both - can we do the Q4 roadmap review on Tuesday 22 September, 15:00-16:30 my time (UTC+2)? Room: Atlas (3rd floor).

----------
From: Amara Okafor <amara@okafor-design.com>
Sent: Mon 14 Sep 2026 10:40 (UTC+1)
Subject: RE: Q4 roadmap review

Tuesday doesn't work for me, sorry. Thursday the 24th at the same time Priya proposed would be fine.

----------
From: Priya Raman <priya.raman@kestrel-labs.io>
Sent: Mon 14 Sep 2026 11:05 (UTC+2)
Subject: RE: Q4 roadmap review

OK - not Tuesday but Thursday 24 September then, at the start time I originally proposed: 15:00 my time (UTC+2). Atlas is booked that day, so we'll use the Juniper room (2nd floor) instead. Let's keep it to 60 minutes rather than 90. I'm also adding Wen Li (wen.li@kestrel-labs.io) as an optional attendee.

----------
From: Tomasz Zielinski <t.zielinski@kestrel-labs.io>
Sent: Mon 14 Sep 2026 11:30 (UTC+2)
Subject: RE: Q4 roadmap review

Works for me. I'll dial in remotely from Warsaw.
""", """
{
  "title": string,                     // the subject line without any "RE:" prefix
  "date": "YYYY-MM-DD",
  "start_time_utc": "HH:MM",           // 24-hour clock, converted to UTC
  "end_time_utc": "HH:MM",
  "duration_minutes": number,
  "room": string,                      // room name only, e.g. "Atlas"
  "organizer_email": string,
  "required_attendees": [string],      // email addresses of everyone expected (not optional), INCLUDING the organizer, sorted alphabetically
  "optional_attendees": [string],      // email addresses, sorted alphabetically
  "remote_attendees": [string]         // email addresses of people who said they will join remotely, sorted alphabetically
}
""", {
    'title': 'Q4 roadmap review', 'date': '2026-09-24', 'start_time_utc': '13:00', 'end_time_utc': '14:00', 'duration_minutes': 60,
    'room': 'Juniper', 'organizer_email': 'priya.raman@kestrel-labs.io',
    'required_attendees': sorted(['priya.raman@kestrel-labs.io', 't.zielinski@kestrel-labs.io', 'amara@okafor-design.com']),
    'optional_attendees': ['wen.li@kestrel-labs.io'], 'remote_attendees': ['t.zielinski@kestrel-labs.io']},
    "Traps: 'not Tuesday but Thursday'; 'the time I originally proposed' = 15:00 UTC+2 = 13:00 UTC (Amara writes from UTC+1 but refers to Priya's time); 90 -> 60 minutes so end is 14:00 UTC; room changed Atlas -> Juniper; organizer included in required list; 2026-09-24 is a Thursday (checked).")

# ---------------------------------------------------------------- e03 clinical note (fictional)
tc = (D('101.3') - 32) * 5 / 9
assert tc == D('38.5')
add('e03', 'medium', "Extract the (fictional) triage note below into JSON that follows the schema exactly.", """
ED triage note - 2026-07-19 23:48
Pt: 67 y/o male. Wt 176 lb (pt-reported); scale reading 81.2 kg - use scale value. Temp 101.3 F oral. HR 104, BP 142/88, SpO2 94% on room air.
Allergies: penicillin (rash). NKFA. Pt initially said "sulfa" but his daughter clarified that is HER allergy, not his - removed.
Current meds: metformin 500 mg twice daily; lisinopril 10 mg once daily; atorvastatin 40 mg at night.
Aspirin 81 mg daily was stopped two weeks ago on cardiology advice.
PMH: T2DM, HTN.
Plan: CXR, CBC, blood cultures x2. Admit to obs.
""", """
{
  "age_years": number,
  "sex": "male" or "female",
  "weight_kg": number,                 // one decimal place
  "temperature_c": number,             // degrees Celsius, one decimal place (convert if needed)
  "heart_rate_bpm": number,
  "systolic_bp": number,
  "diastolic_bp": number,
  "spo2_percent": number,
  "drug_allergies": [string],          // lowercase generic names, in order of appearance
  "active_medications": [              // medications the patient currently takes, in order of appearance
    { "name": string, "dose_mg": number, "times_per_day": number }   // name in lowercase
  ],
  "discontinued_medications": [string] // lowercase names
}
""", {
    'age_years': 67, 'sex': 'male', 'weight_kg': 81.2, 'temperature_c': 38.5, 'heart_rate_bpm': 104, 'systolic_bp': 142, 'diastolic_bp': 88, 'spo2_percent': 94,
    'drug_allergies': ['penicillin'],
    'active_medications': [{'name': 'metformin', 'dose_mg': 500, 'times_per_day': 2}, {'name': 'lisinopril', 'dose_mg': 10, 'times_per_day': 1}, {'name': 'atorvastatin', 'dose_mg': 40, 'times_per_day': 1}],
    'discontinued_medications': ['aspirin']},
    "Traps: two weights (use scale 81.2 kg, not 176 lb = 79.8 kg); 101.3 F = 38.5 C exactly ((101.3-32)*5/9, checked in code); sulfa allergy retracted; NKFA is not a drug allergy; aspirin discontinued; 'at night' = once daily.")

# ---------------------------------------------------------------- e04 shipping manifest
LB = D('0.45359237')
w2 = (D(23200) * LB).quantize(D('1'), ROUND_HALF_UP); w5 = (D(26000) * LB).quantize(D('1'), ROUND_HALF_UP)
assert (w2, w5) == (D(10523), D(11793))
total_w = 18450 + int(w2) + 9870 + int(w5)
add('e04', 'hard', "Extract the cargo manifest below into JSON that follows the schema exactly.", """
MANIFEST - MV CORAL DAWN - Voyage 26W11
Port of loading: Valencia (ESVLC)      Port of discharge: Haifa (ILHFA)
ETD 2026-10-02     ETA 2026-10-07

Cntr No.        Type   Gross wt     Contents                   Haz
MSKU 204113-7   40HC   18,450 kg    Ceramic tiles              N
TGHU 551920-3   20GP   22,300 lb    Olive oil (drums)          N
CAIU 778015-0   20GP   9,870 kg     Lithium batteries          Y (UN3480, class 9)
MSKU 204114-2   40HC   -            EMPTY - repositioning      N
TEMU 330671-9   40RF   26,000 lb    Frozen fish                N

Note: the gross weight for TGHU 551920-3 was mis-keyed; the correct gross weight is 23,200 lb.
""", """
{
  "vessel_name": string,                // copied as printed but without the "MV" prefix (letter case is not checked)
  "voyage": string,
  "port_of_loading": string,            // UN/LOCODE, e.g. "NLRTM"
  "port_of_discharge": string,          // UN/LOCODE
  "departure_date": "YYYY-MM-DD",
  "arrival_date": "YYYY-MM-DD",
  "containers": [                       // in the order listed
    {
      "container_number": string,       // letters and digits only, no spaces or hyphens, e.g. "ABCU1234567"
      "type": string,                   // e.g. "20GP"
      "empty": boolean,
      "gross_weight_kg": number or null,  // convert pounds with 1 lb = 0.45359237 kg and round to the nearest whole kg; null if no weight is given
      "hazardous": boolean,
      "un_number": string or null       // e.g. "UN1234", null if not hazardous
    }
  ],
  "total_gross_weight_kg": number,      // sum of the rounded gross_weight_kg values of all containers that have a weight
  "hazardous_container_count": number
}
""", {
    'vessel_name': 'CORAL DAWN', 'voyage': '26W11', 'port_of_loading': 'ESVLC', 'port_of_discharge': 'ILHFA', 'departure_date': '2026-10-02', 'arrival_date': '2026-10-07',
    'containers': [
        {'container_number': 'MSKU2041137', 'type': '40HC', 'empty': False, 'gross_weight_kg': 18450, 'hazardous': False, 'un_number': None},
        {'container_number': 'TGHU5519203', 'type': '20GP', 'empty': False, 'gross_weight_kg': int(w2), 'hazardous': False, 'un_number': None},
        {'container_number': 'CAIU7780150', 'type': '20GP', 'empty': False, 'gross_weight_kg': 9870, 'hazardous': True, 'un_number': 'UN3480'},
        {'container_number': 'MSKU2041142', 'type': '40HC', 'empty': True, 'gross_weight_kg': None, 'hazardous': False, 'un_number': None},
        {'container_number': 'TEMU3306719', 'type': '40RF', 'empty': False, 'gross_weight_kg': int(w5), 'hazardous': False, 'un_number': None}],
    'total_gross_weight_kg': total_w, 'hazardous_container_count': 1},
    f"Traps: corrected TGHU weight 23,200 lb -> {w2} kg (22,300 lb would give 10115); 26,000 lb -> {w5} kg; empty container has no weight (null) and is excluded from the total; total = 18450+{w2}+9870+{w5} = {total_w} (computed with Decimal). Container numbers normalised by removing spaces/hyphens.")

# ---------------------------------------------------------------- e05 meeting notes relative dates
md = dt.date(2026, 3, 11)
assert md.weekday() == 2  # Wednesday
def next_wd(d, wd):
    x = d + dt.timedelta(days=1)
    while x.weekday() != wd:
        x += dt.timedelta(days=1)
    return x
fri = next_wd(md, 4); mon = next_wd(md, 0)
add('e05', 'hard', "Extract the meeting notes below into JSON that follows the schema exactly.\n\nDate rules: resolve every relative date against the meeting date. \"Next <weekday>\" and a bare weekday name (e.g. \"Monday\") both mean the first such weekday strictly after the meeting date. \"End of this month\" means the last calendar day of the meeting's month. \"N weeks from today\" means the meeting date plus 7 x N days.", """
Ops sync - Wednesday 2026-03-11 (notes: Dana Whitfield)
Present: Dana Whitfield, Marco Bellini, Ines Carvalho, Raj Patel (joined late)
Apologies: Tom Hughes

1. Warehouse move - Marco to get three quotes by next Friday. Ines offered to help, but Marco owns this.
2. Q1 audit - Raj will send the audit pack to Finance by end of this month.
3. Onboarding doc - Ines to update it. Originally due tomorrow; pushed back to Monday.
4. Offsite venue - Dana booked it during the meeting. DONE.
5. Supplier contract renewal - owner TBD, due 2026-04-15.

Next meeting: two weeks from today, same time.
""", """
{
  "meeting_date": "YYYY-MM-DD",
  "note_taker": string,                 // full name
  "attendees": [string],                // full names of everyone listed as present (including anyone who joined late; not people who sent apologies), in the order listed
  "action_items": [                     // one per numbered item, in order
    {
      "item": number,                   // the item's number in the notes
      "owner": string or null,          // full name of the ONE person the notes make responsible for the item (someone who only offers to help is not the owner); null if no owner has been assigned
      "due_date": "YYYY-MM-DD" or null, // null if no due date is given
      "status": "open" or "done"
    }
  ],
  "next_meeting_date": "YYYY-MM-DD"
}
""", {
    'meeting_date': md.isoformat(), 'note_taker': 'Dana Whitfield', 'attendees': ['Dana Whitfield', 'Marco Bellini', 'Ines Carvalho', 'Raj Patel'],
    'action_items': [
        {'item': 1, 'owner': 'Marco Bellini', 'due_date': fri.isoformat(), 'status': 'open'},
        {'item': 2, 'owner': 'Raj Patel', 'due_date': '2026-03-31', 'status': 'open'},
        {'item': 3, 'owner': 'Ines Carvalho', 'due_date': mon.isoformat(), 'status': 'open'},
        {'item': 4, 'owner': 'Dana Whitfield', 'due_date': None, 'status': 'done'},
        {'item': 5, 'owner': None, 'due_date': '2026-04-15', 'status': 'open'}],
    'next_meeting_date': (md + dt.timedelta(days=14)).isoformat()},
    f"Traps: Tom Hughes sent apologies (not present); Ines helps but Marco owns item 1; item 3 moved from tomorrow to Monday = {mon}; next Friday = {fri} per the stated rule; end of month = 2026-03-31; next meeting {md + dt.timedelta(days=14)}. Weekdays checked with Python datetime.")

# ---------------------------------------------------------------- e06 job posting
add('e06', 'medium', "Extract the job posting below into JSON that follows the schema exactly.", """
**Senior Data Engineer (Contract-to-Hire)** - Lumora Health
Location: Remote (US only) - occasional travel to our Denver HQ (at most twice a quarter)
Pay: $68-$82/hr during the 6-month contract; converts to salaried employment at $150k-$175k base.
Must have: 5+ yrs Python; 3+ yrs Spark; SQL; Airflow.
Nice to have: dbt, Terraform.
Posted Aug 30, 2026 - Applications close Sept 21, 2026
Please note we are unable to sponsor visas for this role.
""", """
{
  "job_title": string,                  // the title only, without the employment-type text in parentheses
  "company": string,
  "employment_type": string,            // one of: "full-time", "part-time", "contract", "contract-to-hire", "internship"
  "remote_policy": string,              // one of: "remote" (work can be done from anywhere allowed; occasional travel does not change this), "hybrid" (regular scheduled days at an office are required), "onsite" (work is done at the employer's premises)
  "contract_hourly_rate_min_usd": number,
  "contract_hourly_rate_max_usd": number,
  "salary_min_usd": number,             // annual base salary after conversion, as a full number (e.g. 120000)
  "salary_max_usd": number,
  "required_skills": [string],          // skill names only, in the order listed
  "nice_to_have_skills": [string],      // in the order listed
  "min_years_experience": number,       // the largest minimum number of years stated for any single required skill
  "visa_sponsorship": boolean,
  "posted_date": "YYYY-MM-DD",
  "closing_date": "YYYY-MM-DD"
}
""", {
    'job_title': 'Senior Data Engineer', 'company': 'Lumora Health', 'employment_type': 'contract-to-hire', 'remote_policy': 'remote',
    'contract_hourly_rate_min_usd': 68, 'contract_hourly_rate_max_usd': 82, 'salary_min_usd': 150000, 'salary_max_usd': 175000,
    'required_skills': ['Python', 'Spark', 'SQL', 'Airflow'], 'nice_to_have_skills': ['dbt', 'Terraform'], 'min_years_experience': 5,
    'visa_sponsorship': False, 'posted_date': '2026-08-30', 'closing_date': '2026-09-21'},
    "Traps: 'k' salaries -> 150000/175000; remote despite occasional travel; contract-to-hire; max of per-skill minimums = 5; visa sponsorship false.")

# ---------------------------------------------------------------- e07 receipt
lines = [D('2.98'), D('4.30'), D('4.50'), D('11.99'), D('-2.00'), D('3.49'), D('-4.50'), D('5.10'), D('7.80')]
subtotal = sum(lines)
taxable = D('3.49') + D('7.80')
tax = (taxable * D('0.08')).quantize(D('0.01'), ROUND_HALF_UP)
total7 = subtotal + tax
assert D('1.24') * D('2.40') == D('2.976')
add('e07', 'hard', "Extract the supermarket receipt below into JSON that follows the schema exactly. Some of the receipt's own totals may be missing; compute them from the lines where the schema asks for them.", """
GREENLEAF MARKET  store #0217            02/05/2026 18:41
(dates on this receipt are DD/MM/YYYY)
------------------------------------------------------
Organic bananas 1.24 kg @ 2.40/kg                 2.98 F
Oat milk 1L  2 @ 2.15                             4.30 F
Sourdough loaf                                    4.50 F
Olive oil 750ml                                  11.99 F
   MEMBER DISCOUNT (olive oil)                   -2.00 F
Dish soap 500ml                                   3.49 T
VOID  Sourdough loaf                             -4.50 F
Sourdough loaf, seeded                            5.10 F
Paper towels 6pk                                  7.80 T
------------------------------------------------------
F = food, tax-exempt     T = taxable at 8%
PAID  VISA ****4417
Thank you for shopping with us!
""", """
{
  "store_number": string,               // digits exactly as printed, keep leading zeros
  "date": "YYYY-MM-DD",
  "purchased_item_count": number,       // number of PRODUCT LINES actually bought: a line with a quantity such as "2 @ 2.15" counts as one line; exclude voided product lines, the VOID line itself and discount lines
  "voided_items": [string],             // names of voided products exactly as printed on the VOID line
  "total_discounts": number,            // positive number: sum of the lines labelled DISCOUNT (a VOID is not a discount)
  "subtotal": number,                   // sum of all line amounts after voids and discounts, before tax
  "taxable_amount": number,             // sum of lines marked T
  "tax": number,                        // 8% of taxable_amount, rounded to the nearest cent (half up)
  "total": number,                      // subtotal + tax
  "payment_method": string,             // one of: "cash", "card"
  "card_last4": string or null
}
""", {
    'store_number': '0217', 'date': '2026-05-02', 'purchased_item_count': 6, 'voided_items': ['Sourdough loaf'], 'total_discounts': 2.0,
    'subtotal': float(subtotal), 'taxable_amount': float(taxable), 'tax': float(tax), 'total': float(total7), 'payment_method': 'card', 'card_last4': '4417'},
    f"Purchased lines: bananas, oat milk, olive oil, dish soap, seeded sourdough, paper towels = 6 (the plain sourdough was voided). Subtotal {subtotal}, taxable {taxable}, tax {tax} (0.9032 rounded), total {total7} (Decimal arithmetic). DD/MM date 02/05/2026 = 2026-05-02.")

# ---------------------------------------------------------------- e08 lease with addendum
start = dt.date(2026, 10, 1)
end = dt.date(2027, 10, 31)  # 13 months: Oct 2026 .. Oct 2027 inclusive
m = (end.year - start.year) * 12 + end.month - start.month + 1
assert m == 13 and (end + dt.timedelta(days=1)).day == 1
add('e08', 'medium', "Extract the lease summary below into JSON that follows the schema exactly. Apply the addendum to the original terms.", """
RESIDENTIAL LEASE - SUMMARY OF TERMS
Landlord: Halvorsen Property Group LLC
Tenant(s): Joaquín Ruiz and Mei-Ling Chow
Premises: 1428 Alder Street, Apt 3B, Portland, OR 97209
Term: 12 months commencing 1 October 2026
Monthly rent: $2,150.00, due on the 1st of each month; late fee of $75 if unpaid by the 5th
Security deposit: $4,300.00
Pets: one cat permitted, with a $300 non-refundable pet fee. No dogs.
Utilities included in rent: water, sewer, trash. Tenant pays electricity and internet.
Parking: one assigned space (#14) at $85/month, billed together with the rent but not included in it.

ADDENDUM A (signed 10 September 2026): Monthly rent is reduced to $2,095.00 in exchange for the tenants handling lawn care. The term is extended to 13 months. All other terms are unchanged.
""", """
{
  "landlord": string,
  "tenants": [string],                  // full names exactly as written, in the order listed
  "unit": string,                       // apartment identifier only, e.g. "12A"
  "postal_code": string,
  "lease_start": "YYYY-MM-DD",
  "lease_end": "YYYY-MM-DD",            // the last day of the term
  "term_months": number,
  "monthly_rent": number,
  "security_deposit": number,
  "late_fee": number,
  "pet_fee": number,
  "pets_allowed": [string],             // lowercase singular animal names
  "utilities_included": [string],       // lowercase, in the order listed
  "parking_fee_monthly": number,
  "total_monthly_payment": number       // monthly rent plus the monthly parking fee
}
""", {
    'landlord': 'Halvorsen Property Group LLC', 'tenants': ['Joaquín Ruiz', 'Mei-Ling Chow'], 'unit': '3B', 'postal_code': '97209',
    'lease_start': start.isoformat(), 'lease_end': end.isoformat(), 'term_months': 13, 'monthly_rent': 2095, 'security_deposit': 4300,
    'late_fee': 75, 'pet_fee': 300, 'pets_allowed': ['cat'], 'utilities_included': ['water', 'sewer', 'trash'], 'parking_fee_monthly': 85,
    'total_monthly_payment': 2095 + 85},
    "Traps: addendum changes rent (2150 -> 2095) and term (12 -> 13 months), so the lease runs 2026-10-01 .. 2027-10-31; deposit stated as a fixed amount (unchanged); parking not included in rent; dogs not allowed.")

meta = dict(
    id='extraction.structured-json', category='extraction', name='Messy Text to Exact JSON',
    description='Invoices, email threads, triage notes, cargo manifests, meeting notes, job ads, receipts and leases with the traps real documents contain: corrections, reschedules, retracted facts, DD/MM dates, unit conversions and relative dates. Each leaf field is scored, so careless readers lose points on exactly the details that matter in production.',
    version='1.1.0', difficulty='medium', tags=['extraction', 'json', 'information-extraction', 'dates', 'units'],
    hook='Eight messy documents, one exact JSON schema. Every field is checked.',
    maxOutputTokens=8000,
    estimate={'inputTokens': int(sum(tok(c['prompt']) for c in cases) / len(cases)) + 20, 'outputTokens': 2500},
    scorer={'type': 'json', 'numberTolerance': 0.005},
)
for c in cases:
    print(c['id'], json.dumps(c['expected'])[:160])
print(write_test(meta, cases))
