"""work.expense-audit — "Expense Policy Check".

A fixed company expenses policy and a batch of seeded claim lines. `audit()` applies the policy to the
structured lines (never to the text) and produces the key: every line that is not paid in full, the first
rule it breaks and the amount to pay, plus totals per employee, the grand total and the reclaimable VAT.
`audit(careless=True)` applies the same policy the way a hurried reviewer would (each meal capped on its own
instead of per day, an approval claimed on the form taken at its word, mileage at 45p throughout, the
£25.00 receipt threshold read as "over £25", duplicates and the 60-day limit missed, VAT taken from every
line), which gives the scripted careless answer.
"""
import random
from datetime import date, timedelta
from decimal import Decimal, ROUND_HALF_UP
from wk import uk, uky, people, money, DOW, MON

D = Decimal
P = D('0.01')


def r2(x):
    return D(x).quantize(P, rounding=ROUND_HALF_UP)


RULES = ['LATE', 'DUPLICATE', 'NO_RECEIPT', 'ALCOHOL', 'MEAL_CAP', 'HOTEL_CAP', 'ENTERTAINMENT_CAP', 'RAIL_CLASS', 'MILEAGE_RATE']
MEAL_CAP = {'London': D('40'), 'Manchester': D('30'), 'Leeds': D('30'), 'Bristol': D('30'), 'Glasgow': D('30'), 'Paris': D('48'), 'Dublin': D('44'), 'New York': D('60')}
HOTEL_CAP = {'London': D('180'), 'Manchester': D('120'), 'Leeds': D('120'), 'Bristol': D('120'), 'Glasgow': D('120'), 'Paris': D('190'), 'Dublin': D('170'), 'New York': D('240')}
CUR = {'London': 'GBP', 'Manchester': 'GBP', 'Leeds': 'GBP', 'Bristol': 'GBP', 'Glasgow': 'GBP', 'Paris': 'EUR', 'Dublin': 'EUR', 'New York': 'USD'}
SYM = {'GBP': '£', 'EUR': '€', 'USD': '$'}
UK_CITIES = ['London', 'Manchester', 'Leeds', 'Bristol', 'Glasgow']

POLICY = """Expenses policy (Finance, version 7) — apply every rule exactly as written.

A. Conversion. Amounts in euros or US dollars are converted to pounds at the rate in the rate table, rounded to the nearest penny, BEFORE any limit is applied. All limits are in pounds.
B. Rules, in this order. A line breaks a rule when that rule reduces what the company pays for it.
 1. LATE — a line whose expense date is more than 60 days before its claim's submission date pays £0.
 2. DUPLICATE — the same expense claimed again (same employee, date, merchant and amount, in the same or another claim): the later line (higher line number) pays £0.
 3. NO_RECEIPT — any line of £25.00 or more (after conversion) without a receipt pays £0. Mileage needs no receipt.
 4. ALCOHOL — alcohol is never paid (deduct the alcohol amount shown on the line, converted if needed), except on client entertainment approved in writing (rule 7).
 5. MEAL_CAP — meals (breakfast, lunch, dinner) are paid up to a daily limit per employee per day, by city: London £40, other UK cities £30, Paris £48, Dublin £44, New York £60. When one employee has several meals on the same day, take them in line order: each is paid up to what is left of that day's limit (after any alcohol deduction).
 6. HOTEL_CAP — hotels are paid up to a limit per night: London £180, other UK cities £120, Paris £190, Dublin £170, New York £240.
 7. ENTERTAINMENT_CAP — client entertainment is paid up to £60 per head (count everyone at the table, staff included). If, and only if, the line's approval reference appears in the Director approvals register below, the whole amount is paid, alcohol included. An approval mentioned on a claim but missing from the register does not count.
 8. RAIL_CLASS — rail travel is paid at standard class: a first-class ticket is paid at the standard fare shown on the line.
 9. MILEAGE_RATE — business mileage in a private car is paid at 45p a mile for the first 10,000 business miles in the tax year and 25p a mile after that. The tax year runs 6 April to 5 April. Count each employee's miles in date order, starting from the miles already paid in the tax year named on their claim (in any other tax year, start from 0); a journey that crosses 10,000 is split. Mileage is paid at the correct rate whatever amount was claimed.
C. Report a line as non-compliant when it is paid less than its claimed amount (in pounds). Give the FIRST rule in the list above that reduced it, and the amount to pay. Lines paid in full are compliant and must not be listed.
D. VAT. The company reclaims the VAT shown on a line only if the line is paid in full, is in pounds (a UK expense), has a receipt and is not client entertainment. Mileage carries no VAT."""


class Line:
    def __init__(self, **k):
        self.__dict__.update(k)


def gbp(line, rates):
    return r2(line.amount * rates[line.cur]) if line.cur != 'GBP' else line.amount


def audit(claims, lines, rates, register, careless=False):
    pay = {}
    rule = {}
    seen = {}
    day_used = {}
    miles_done = {}  # (employee, tax year) -> miles
    for ln in sorted(lines, key=lambda l: l.n):
        c = claims[ln.claim]
        claimed = gbp(ln, rates)
        reasons = []
        amt = claimed
        # 1 late
        if not careless and (c['submitted'] - ln.date).days > 60:
            reasons.append('LATE')
            amt = D('0')
        # 2 duplicate
        key = (c['emp'], ln.date, ln.merchant, ln.amount)
        if key in seen and not careless:
            if not reasons:
                reasons.append('DUPLICATE')
            amt = D('0')
        seen.setdefault(key, ln.n)
        # 3 receipt
        need = (claimed > D('25')) if careless else (claimed >= D('25'))
        if ln.kind != 'mileage' and need and not ln.receipt:
            if not reasons:
                reasons.append('NO_RECEIPT')
            amt = D('0')
        if amt == 0 and reasons:
            pay[ln.n] = D('0')
            rule[ln.n] = reasons[0]
            continue
        approved = bool(ln.kind == 'entertainment' and ln.approval and (ln.approval in {r[0] for r in register} or careless))
        # 4 alcohol
        if ln.alcohol and not approved:
            amt -= (r2(ln.alcohol * rates[ln.cur]) if ln.cur != 'GBP' else ln.alcohol)
            reasons.append('ALCOHOL')
        if ln.kind == 'meal':
            cap = MEAL_CAP[ln.city]
            if careless:
                left = cap
            else:
                left = cap - day_used.get((c['emp'], ln.date), D('0'))
            paid = min(amt, max(left, D('0')))
            if paid < amt:
                reasons.append('MEAL_CAP')
            day_used[(c['emp'], ln.date)] = day_used.get((c['emp'], ln.date), D('0')) + paid
            amt = paid
        if ln.kind == 'hotel':
            cap = HOTEL_CAP[ln.city] * ln.nights
            if amt > cap:
                amt = cap
                reasons.append('HOTEL_CAP')
        if ln.kind == 'entertainment' and not approved:
            cap = D('60') * ln.heads
            if amt > cap:
                amt = cap
                reasons.append('ENTERTAINMENT_CAP')
        if ln.kind == 'rail' and ln.first_class:
            std = ln.standard
            if amt > std:
                amt = std
                reasons.append('RAIL_CLASS')
        if ln.kind == 'mileage':
            ty = ln.date.year if ln.date >= date(ln.date.year, 4, 6) else ln.date.year - 1
            k = (c['emp'], ty)
            if k not in miles_done:
                if careless:
                    miles_done[k] = c['miles_before']
                else:
                    miles_done[k] = c['miles_before'] if ty == c['miles_year'] else 0
            before = miles_done[k]
            hi = max(0, min(ln.miles, 10000 - before))
            lo = ln.miles - hi
            correct = r2(D(hi) * D('0.45') + D(lo) * D('0.25')) if not careless else r2(D(ln.miles) * D('0.45'))
            miles_done[k] = before + ln.miles
            if correct != amt:
                reasons.append('MILEAGE_RATE')
            amt = correct
        pay[ln.n] = amt
        if amt < claimed:
            rule[ln.n] = reasons[0]
    # VAT
    vat = D('0')
    for ln in lines:
        claimed = gbp(ln, rates)
        if careless:
            if ln.vat:
                vat += ln.vat if ln.cur == 'GBP' else r2(ln.vat * rates[ln.cur])
            continue
        if ln.vat and pay[ln.n] == claimed and ln.cur == 'GBP' and ln.receipt and ln.kind != 'entertainment':
            vat += ln.vat
    by_emp = {}
    for ln in lines:
        e = claims[ln.claim]['emp']
        by_emp[e] = by_emp.get(e, D('0')) + pay[ln.n]
    nc = {f"L{n:02d}": {'rule': rule[n], 'pay': float(pay[n])} for n in sorted(rule)}
    return {'non_compliant': nc,
            'pay_by_employee': {e: float(v) for e, v in by_emp.items()},
            'total_pay': float(sum(pay.values())),
            'vat_reclaimable': float(vat)}, pay, rule


MERCH = {
    'hotel': {'London': ['Riverside Lodge London City', 'Rivergate Rooms Covent Garden', 'The Fenwick Hotel Bloomsbury'], 'Manchester': ['Central Stay Manchester', 'Northern Quarter Rooms'],
              'Leeds': ['Wharfside Hotel Leeds'], 'Bristol': ['Harbourside Inn Bristol'], 'Glasgow': ['Merchant City Lodge'], 'Paris': ['Hôtel des Arts Montmartre'],
              'Dublin': ['Liffey Quay Hotel'], 'New York': ['Hudson Yard Suites']},
    'meal': {'London': ['Daily Bread Café', 'Copper Kettle Café Borough', 'The Crown & Anchor'], 'Manchester': ['Ancoats Kitchen', 'Northern Canteen'], 'Leeds': ['Kirkgate Market Diner'],
             'Bristol': ['Harbour Café'], 'Glasgow': ['Ashton Lane Bistro'], 'Paris': ['Brasserie du Canal'], 'Dublin': ['Temple Bar Grill'], 'New York': ['Midtown Deli']},
}
RAIL = [('London Euston', 'Manchester Piccadilly', D('92.40'), D('164.00')), ('London King\'s Cross', 'Leeds', D('86.70'), D('151.20')),
        ('London Paddington', 'Bristol Temple Meads', D('64.50'), D('118.00')), ('Manchester Piccadilly', 'Glasgow Central', D('58.90'), D('97.30')),
        ('London Euston', 'Glasgow Central', D('120.10'), D('210.50'))]
DEPTS = ['Sales', 'Customer Success', 'Engineering', 'Marketing', 'Operations', 'Finance']


def money_cur(x, cur):
    return f"{SYM[cur]}{x:,.2f}"


def gen(seed, tier):
    rng = random.Random(seed)
    n_emp = {'easy': 2, 'medium': 3, 'hard': 4}[tier]
    names = people(rng, n_emp + 3)
    emps = names[:n_emp]
    directors = names[n_emp:n_emp + 2]
    rates = {'GBP': D('1'), 'EUR': D(str(rng.choice(['0.84', '0.85', '0.86', '0.87']))), 'USD': D(str(rng.choice(['0.74', '0.76', '0.78', '0.79'])))}
    month = {'easy': 6, 'medium': 9, 'hard': 4}[tier]
    base = date(2026, month, 1)
    claims = {}
    lines = []
    register = []
    for i, e in enumerate(emps):
        cid = f"C-{rng.randint(3100, 3999)}"
        sub = base + timedelta(days=rng.randint(18, 26))
        while sub.weekday() >= 5:
            sub += timedelta(days=1)
        miles_year = 2026 if month >= 5 else 2025
        claims[cid] = dict(id=cid, emp=e, dept=DEPTS[(seed + i) % len(DEPTS)], submitted=sub,
                           miles_before=rng.choice([1200, 4300, 7650, 9480, 9720, 9890]) if tier != 'easy' else rng.choice([800, 2400, 5100]), miles_year=miles_year)
    cids = list(claims)
    target = {'easy': 9, 'medium': 15, 'hard': 22}[tier]
    cities_uk = UK_CITIES
    cities_all = UK_CITIES + (['Paris', 'Dublin', 'New York'] if tier != 'easy' else [])

    def add(**k):
        k.setdefault('alcohol', None)
        k.setdefault('approval', None)
        k.setdefault('vat', None)
        k.setdefault('first_class', False)
        k.setdefault('note', '')
        lines.append(Line(**k))

    def edate(c, lo=3, hi=20):
        d = c['submitted'] - timedelta(days=rng.randint(lo, hi))
        while d.weekday() >= 5:
            d -= timedelta(days=1)
        return d

    def vat_of(gross, rate=True):
        return r2(gross / D(6)) if rate else None

    # building blocks
    def hotel(c, over=False):
        city = rng.choice(cities_all)
        nights = rng.choice([1, 1, 2, 3])
        cap = HOTEL_CAP[city]
        per = cap + D(rng.randint(5, 40)) if over else cap - D(rng.randint(5, 50))
        per_local = r2(per / rates[CUR[city]]) if CUR[city] != 'GBP' else per
        amt = r2(per_local * nights - D(rng.choice([0, 0.5, 0.2])))
        add(claim=c['id'], date=edate(c), kind='hotel', city=city, merchant=rng.choice(MERCH['hotel'][city]), desc=f"Hotel, {nights} night{'s' if nights > 1 else ''}",
            amount=amt, cur=CUR[city], receipt=True, nights=nights, vat=vat_of(amt) if CUR[city] == 'GBP' else None)

    def meal(c, d=None, city=None, kind=None, amt=None, alcohol=None, receipt=True):
        city = city or rng.choice(cities_all)
        d = d or edate(c)
        kind = kind or rng.choice(['Breakfast', 'Lunch', 'Dinner'])
        if amt is None:
            amt = D(rng.randint(800, 3800)) / 100
        add(claim=c['id'], date=d, kind='meal', city=city, merchant=rng.choice(MERCH['meal'][city]), desc=kind, amount=r2(amt), cur=CUR[city],
            receipt=receipt, alcohol=alcohol, vat=vat_of(r2(amt)) if CUR[city] == 'GBP' and receipt else None)

    def rail(c, first=False):
        a, b, std, fc = rng.choice(RAIL)
        amt = fc if first else std
        add(claim=c['id'], date=edate(c), kind='rail', city='London', merchant='National Rail (LNER/Avanti/GWR)', desc=f"Rail {a} – {b} return, {'first' if first else 'standard'} class",
            amount=amt, cur='GBP', receipt=True, first_class=first, standard=std, vat=None)

    def taxi(c, receipt=True, amt=None):
        amt = amt if amt is not None else D(rng.randint(900, 4800)) / 100
        add(claim=c['id'], date=edate(c), kind='taxi', city='London', merchant=rng.choice(['Citylink Cars', 'Black cab', 'Metro Cabs']), desc='Taxi to client site',
            amount=r2(amt), cur='GBP', receipt=receipt, vat=None)

    def misc(c, receipt=True, amt=None):
        amt = amt if amt is not None else D(rng.randint(600, 2400)) / 100
        what = rng.choice([('Stationery World', 'Printer paper and folders'), ('City Car Parks', 'Parking, client visit'), ('Royal Mail', 'Special Delivery postage'), ('High Street Pharmacy', 'First-aid kit restock')])
        add(claim=c['id'], date=edate(c), kind='misc', city='London', merchant=what[0], desc=what[1], amount=r2(amt), cur='GBP', receipt=receipt,
            vat=vat_of(r2(amt)) if what[0] != 'Royal Mail' and receipt else None)

    def ent(c, approved=None, over=True):
        heads = rng.randint(3, 6)
        per = D(rng.randint(62, 95)) if over else D(rng.randint(35, 58))
        amt = r2(per * heads + D(rng.randint(0, 99)) / 100)
        alc = r2(amt * D(rng.choice(['0.18', '0.22', '0.27'])))
        ref = None
        if approved is not None:
            ref = f"DA-{rng.randint(100, 499)}"
            if approved:
                register.append((ref, rng.choice(directors), c['emp']))
        add(claim=c['id'], date=edate(c), kind='entertainment', city='London', merchant=rng.choice(['Guildhall Grill', 'Brasserie Aubert, Covent Garden', 'The Fenwick Dining Room']),
            desc=f"Client dinner, {heads} people (incl. {rng.randint(1, 2)} staff)", amount=amt, cur='GBP', receipt=True, alcohol=alc, heads=heads, approval=ref,
            vat=vat_of(amt), note=(f"approved by director, ref {ref}" if ref else ''))

    def mileage(c, miles=None, d=None, claim_rate=D('0.45')):
        miles = miles or rng.choice([84, 126, 212, 318, 46, 157])
        d = d or edate(c)
        add(claim=c['id'], date=d, kind='mileage', city='', merchant='Own car', desc=f"Mileage, {miles} miles (client visits)", amount=r2(D(miles) * claim_rate), cur='GBP',
            receipt=False, miles=miles)

    # tiered content
    C = [claims[k] for k in cids]
    if tier == 'easy':
        hotel(C[0], over=True)
        meal(C[0])
        rail(C[0])
        taxi(C[0], receipt=False, amt=D('31.50'))
        meal(C[1], alcohol=D('9.50'), amt=D('34.80'), city='Leeds')
        hotel(C[1])
        misc(C[1])
        mileage(C[1])
        misc(C[0], receipt=False, amt=D('14.20'))
        # a late line
        lines[-2].date = C[1]['submitted'] - timedelta(days=74)
    elif tier == 'medium':
        hotel(C[0], over=True)
        d = edate(C[0])
        meal(C[0], d=d, city='London', kind='Lunch', amt=D('16.40'))
        meal(C[0], d=d, city='London', kind='Dinner', amt=D('31.20'), alcohol=D('6.80'))
        rail(C[0], first=True)
        hotel(C[1], over=False)
        meal(C[1], city='Paris', kind='Dinner', amt=D('62.00'), alcohol=D('14.00'))
        taxi(C[1], receipt=False, amt=D('25.00'))
        ent(C[1], approved=True)
        misc(C[1])
        mileage(C[2])
        meal(C[2], city='Manchester', kind='Breakfast', amt=D('12.90'))
        hotel(C[2], over=True)
        ent(C[2], approved=None)
        misc(C[2], receipt=False, amt=D('24.99'))
        # duplicate of an earlier meal in another claim
        src = lines[5]
        lines.append(Line(**dict(src.__dict__)))
    else:
        hotel(C[0], over=True)
        d = edate(C[0])
        meal(C[0], d=d, city='New York', kind='Breakfast', amt=D('21.50'))
        meal(C[0], d=d, city='New York', kind='Lunch', amt=D('27.00'))
        meal(C[0], d=d, city='New York', kind='Dinner', amt=D('44.00'), alcohol=D('12.00'))
        rail(C[0], first=True)
        ent(C[0], approved=False)  # approval claimed on the form, not in the register
        # mileage across the 10,000 mark and across the tax year (claims in April)
        C[1]['miles_before'] = 9720
        C[1]['miles_year'] = 2025
        mileage(C[1], miles=212, d=date(2026, 3, 31))
        mileage(C[1], miles=126, d=date(2026, 4, 2))
        mileage(C[1], miles=318, d=date(2026, 4, 8))
        meal(C[1], city='Dublin', kind='Dinner', amt=D('58.00'), alcohol=D('9.00'))
        hotel(C[1], over=False)
        taxi(C[1], receipt=True)
        ent(C[2], approved=True)
        d2 = edate(C[2])
        meal(C[2], d=d2, city='Glasgow', kind='Lunch', amt=D('18.60'))
        meal(C[2], d=d2, city='Glasgow', kind='Dinner', amt=D('19.40'))
        misc(C[2], receipt=False, amt=D('25.00'))
        hotel(C[2], over=True)
        C[3]['miles_before'] = 9890
        C[3]['miles_year'] = 2025
        mileage(C[3], miles=157, d=date(2026, 3, 27))
        meal(C[3], city='London', kind='Lunch', amt=D('24.10'), receipt=False)
        rail(C[3], first=False)
        ent(C[3], approved=None, over=False)
        src = lines[10]
        lines.append(Line(**dict(src.__dict__)))
        lines[-1].receipt = True
        misc(C[3])
        lines[-1].date = C[3]['submitted'] - timedelta(days=61)
    # a few ordinary extra lines so cases of one tier differ in shape
    for _ in range(rng.randint(1, 3)):
        c = rng.choice(C)
        rng.choice([lambda: meal(c, city=rng.choice(UK_CITIES), amt=D(rng.randint(600, 2200)) / 100), lambda: misc(c), lambda: taxi(c), lambda: rail(c)])()
    # number the lines claim by claim (a duplicate sits at the end of its own claim, after the original)
    order = {cid: i for i, cid in enumerate(cids)}
    lines.sort(key=lambda l: order[l.claim])
    for i, ln in enumerate(lines, 1):
        ln.n = i
    return claims, lines, rates, register, directors


def render(claims, lines, rates, register):
    out = []
    out.append(f"Rate table: €1 = £{rates['EUR']}, $1 = £{rates['USD']}.")
    out.append("")
    out.append("Director approvals register (written approvals for client entertainment):")
    if register:
        for ref, d, e in register:
            out.append(f"  - {ref}: approved by {d} for {e}")
    else:
        out.append("  - (no approvals this period)")
    out.append("")
    for cid, c in claims.items():
        out.append(f"Claim {cid} — {c['emp']} ({c['dept']}), submitted {uky(c['submitted'])}. Business miles already paid in tax year {c['miles_year']}/{str(c['miles_year'] + 1)[2:]} before this claim: {c['miles_before']:,}.")
        for ln in lines:
            if ln.claim != cid:
                continue
            bits = [f"L{ln.n:02d}", uky(ln.date), ln.merchant + (f", {ln.city}" if ln.city and ln.kind in ('hotel', 'meal') else ''), ln.desc, money_cur(ln.amount, ln.cur)]
            if ln.alcohol:
                bits.append(f"includes alcohol {money_cur(ln.alcohol, ln.cur)}")
            if ln.kind == 'rail' and ln.first_class:
                bits.append(f"standard fare for this journey £{ln.standard}")
            if ln.vat:
                bits.append(f"VAT £{ln.vat}")
            bits.append('receipt attached' if ln.receipt else ('no receipt needed' if ln.kind == 'mileage' else 'no receipt'))
            if ln.note:
                bits.append(ln.note)
            out.append('  ' + ' | '.join(bits))
        out.append("")
    return '\n'.join(out).strip()


def prompt(claims, lines, rates, register):
    schema = ('{"non_compliant": {"L03": {"rule": "HOTEL_CAP", "pay": 360.00}, "L07": {"rule": "NO_RECEIPT", "pay": 0}, ...}, '
              '"pay_by_employee": {"Full Name": 512.40, "Other Name": 98.10, ...}, "total_pay": 610.50, "vat_reclaimable": 41.27}')
    return ("I run the monthly expenses check. Please audit this batch of claim lines against our policy and tell me what to pay.\n\n"
            f"{POLICY}\n\n{render(claims, lines, rates, register)}\n\n"
            "Answer with one JSON object in exactly this shape:\n" + schema + "\n"
            "- non_compliant: one entry per line that is paid less than claimed, keyed by line number, with the rule (one of LATE, DUPLICATE, NO_RECEIPT, ALCOHOL, MEAL_CAP, HOTEL_CAP, "
            "ENTERTAINMENT_CAP, RAIL_CLASS, MILEAGE_RATE) and the amount to pay in pounds. Do not list lines that are paid in full.\n"
            "- pay_by_employee: the total to pay each employee (every employee in the batch); total_pay: the total for the batch; vat_reclaimable: the total VAT the company can reclaim.\n"
            "All amounts in pounds to the penny (a £ sign and thousands separators are fine). Marking: each listed line earns a point for the rule and a point for the amount; "
            "every line you list that should not be listed costs two points; each total is a point. Reply with the JSON object only (a ```json code block is fine); key order, spacing and letter case do not matter. "
            "Give one value per field: a field with two values is marked wrong, and if you give more than one JSON answer only the last one counts.")


CASES = [('x01', 501, 'easy'), ('x02', 502, 'easy'), ('x03', 503, 'easy'),
         ('x04', 604, 'medium'), ('x05', 605, 'medium'), ('x06', 606, 'medium'),
         ('x07', 707, 'hard'), ('x08', 708, 'hard'), ('x09', 709, 'hard')]


def make_cases():
    out = []
    for cid, seed, tier in CASES:
        claims, lines, rates, register, directors = gen(seed, tier)
        key, pay, rule = audit(claims, lines, rates, register)
        careless, _, _ = audit(claims, lines, rates, register, careless=True)
        # the same expense twice must be the same employee's
        assert key['non_compliant'], cid
        out.append(dict(id=cid, tier=tier, prompt=prompt(claims, lines, rates, register), expected=key, careless=careless, n=len(lines)))
    return out


if __name__ == '__main__':
    import json
    cs = make_cases()
    for c in cs:
        print(c['id'], c['tier'], c['n'], 'lines', len(c['prompt']), 'chars', len(c['expected']['non_compliant']), 'non-compliant', 'careless nc', len(c['careless']['non_compliant']))
    print(cs[6]['prompt'])
    print(json.dumps(cs[6]['expected'], indent=1))
    print(json.dumps(cs[6]['careless'], indent=1))
