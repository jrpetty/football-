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

# Points per field for fields where a trap sits (routine fields are worth 1). Stated in the prompt.
TRAP_WEIGHT = 3
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

POLICY = """Expenses policy (Finance, version 8) — apply every rule exactly as written.

A. Conversion. Amounts in euros or US dollars are converted to pounds at the rate in the rate table, rounded to the nearest penny, BEFORE any limit is applied. All limits are in pounds.
B. Rules, in this order. A line breaks a rule when that rule reduces what the company pays for it.
 1. LATE — a line whose expense date is more than 60 days before its claim's submission date pays £0, unless the register holds a late-submission approval (type LATE) for that claim number.
 2. DUPLICATE — the same expense claimed again (same employee, date, merchant and amount, in the same or another claim): the later line (higher line number) pays £0.
 3. NO_RECEIPT — any line of £25.00 or more (after conversion) without a receipt pays £0. Mileage needs no receipt.
 4. ALCOHOL — alcohol is never paid (deduct the alcohol amount shown on the line, converted if needed), except on client entertainment approved under rule 7, where alcohol is paid up to £15 per head (heads counted as in rule 7) and only the excess is deducted.
 5. MEAL_CAP — meals (breakfast, lunch, dinner) are paid up to a limit for each employee, each city and each day: London £40, other UK cities £30, Paris £48, Dublin £44, New York £60. Meals in different cities on the same day are capped separately, each against its own city's limit. A city's limit is £20 higher on a day when the same employee has a hotel stay in that city in this batch covering the night of that day (a hotel line dated D for N nights covers the nights of D to D+N-1, not the morning of departure). When one employee has several meals in the same city on the same day, take them in line order: each is paid up to what is left of that limit (after any alcohol deduction).
 6. HOTEL_CAP — hotels are paid up to a limit per night: London £180, other UK cities £120, Paris £190, Dublin £170, New York £240.
 7. ENTERTAINMENT_CAP — client entertainment is paid up to £60 per head (count everyone at the table, staff included), after any alcohol deduction. It is approved only if the line's approval reference is in the register as type ENT for the same employee; an approval mentioned on a claim but missing from the register, or registered for someone else, does not count. Approved entertainment is paid in full (alcohol as in rule 4); if the register gives a limit, the amount left after any alcohol deduction is capped at that limit.
 8. RAIL_CLASS — rail travel is paid at standard class: a first-class ticket is paid at the standard fare shown on the line.
 9. MILEAGE_RATE — business mileage in a private car is paid at 45p a mile for the first 10,000 business miles in the tax year and 25p a mile after that, plus 5p a mile for each colleague carried as a passenger (the passenger supplement does not count towards the 10,000). The tax year runs 6 April to 5 April. Count each employee's miles in date order across all their claims in this batch, starting from the miles paid before this batch in the tax year named on their claims (in any other tax year, start from 0); a journey that crosses 10,000 is split. Mileage is paid at the correct rate whatever amount was claimed.
C. Report a line as non-compliant when it is paid less than its claimed amount (in pounds). Give the FIRST rule in the list above that reduced it, and the amount to pay. Lines paid in full (or more) are compliant and must not be listed; a mileage line claimed below the correct amount is paid the correct amount and is compliant.
D. VAT. The company reclaims the VAT shown on a line only if the line is paid in full, is in pounds (a UK expense), has a receipt and is not client entertainment. Mileage carries no VAT."""

# The careless reviewer's mistakes, one flag each (audit(..., ALL) is the scripted careless answer); SLIP is a strong
# reviewer who forgets the hotel-night uplift of the meal limit.
ALL = frozenset({'late', 'dup', 'receipt', 'approval', 'alc_cap', 'approval_limit', 'uplift', 'percap', 'passengers', 'mileage', 'vat'})
SLIP = frozenset({'uplift'})


class Line:
    def __init__(self, **k):
        self.__dict__.update(k)


def gbp(line, rates):
    return r2(line.amount * rates[line.cur]) if line.cur != 'GBP' else line.amount


def audit(claims, lines, rates, register, F=frozenset()):
    if F is True:
        F = ALL
    pay = {}
    rule = {}
    seen = {}
    day_used = {}
    miles_done = {}  # (employee, tax year) -> miles
    ent_ok = {(r['ref'], r['emp']): r for r in register if r['type'] == 'ENT'}
    late_ok = {r['claim'] for r in register if r['type'] == 'LATE'}
    # nights covered by hotel stays: (employee, city, night date)
    nights = set()
    for ln in lines:
        if ln.kind == 'hotel':
            for i in range(ln.nights):
                nights.add((claims[ln.claim]['emp'], ln.city, ln.date + timedelta(days=i)))
    # mileage: each employee's journeys in date order across all their claims
    mile_pay = {}
    for ln in sorted((l for l in lines if l.kind == 'mileage'), key=lambda l: (l.date, l.n)):
        c = claims[ln.claim]
        ty = ln.date.year if ln.date >= date(ln.date.year, 4, 6) else ln.date.year - 1
        k = (c['emp'], ty)
        if k not in miles_done:
            if 'mileage' in F:
                miles_done[k] = c['miles_before']
            else:
                miles_done[k] = c['miles_before'] if ty == c['miles_year'] else 0
        before = miles_done[k]
        hi = max(0, min(ln.miles, 10000 - before))
        lo = ln.miles - hi
        correct = r2(D(hi) * D('0.45') + D(lo) * D('0.25')) if 'mileage' not in F else r2(D(ln.miles) * D('0.45'))
        if 'passengers' not in F and getattr(ln, 'passengers', 0):
            correct += r2(D(ln.miles) * D('0.05') * ln.passengers)
        miles_done[k] = before + ln.miles
        mile_pay[ln.n] = correct
    for ln in sorted(lines, key=lambda l: l.n):
        c = claims[ln.claim]
        claimed = gbp(ln, rates)
        reasons = []
        amt = claimed
        # 1 late
        if 'late' not in F and (c['submitted'] - ln.date).days > 60 and c['id'] not in late_ok:
            reasons.append('LATE')
            amt = D('0')
        # 2 duplicate
        key = (c['emp'], ln.date, ln.merchant, ln.amount)
        if key in seen and 'dup' not in F:
            if not reasons:
                reasons.append('DUPLICATE')
            amt = D('0')
        seen.setdefault(key, ln.n)
        # 3 receipt
        need = (claimed > D('25')) if 'receipt' in F else (claimed >= D('25'))
        if ln.kind != 'mileage' and need and not ln.receipt:
            if not reasons:
                reasons.append('NO_RECEIPT')
            amt = D('0')
        if amt == 0 and reasons:
            pay[ln.n] = D('0')
            rule[ln.n] = reasons[0]
            continue
        reg = None
        if ln.kind == 'entertainment' and ln.approval:
            reg = ent_ok.get((ln.approval, c['emp']))
            if reg is None and 'approval' in F:
                reg = {'limit': None}
        approved = reg is not None
        # 4 alcohol
        if ln.alcohol:
            alc = r2(ln.alcohol * rates[ln.cur]) if ln.cur != 'GBP' else ln.alcohol
            if approved:
                excess = max(D('0'), alc - D('15') * ln.heads) if 'alc_cap' not in F else D('0')
            else:
                excess = alc
            if excess > 0:
                amt -= excess
                reasons.append('ALCOHOL')
        if ln.kind == 'meal':
            cap = MEAL_CAP[ln.city]
            if 'uplift' not in F and (c['emp'], ln.city, ln.date) in nights:
                cap += D('20')
            if 'percap' in F:
                left = cap
            else:
                left = cap - day_used.get((c['emp'], ln.date, ln.city), D('0'))
            paid = min(amt, max(left, D('0')))
            if paid < amt:
                reasons.append('MEAL_CAP')
            day_used[(c['emp'], ln.date, ln.city)] = day_used.get((c['emp'], ln.date, ln.city), D('0')) + paid
            amt = paid
        if ln.kind == 'hotel':
            cap = HOTEL_CAP[ln.city] * ln.nights
            if amt > cap:
                amt = cap
                reasons.append('HOTEL_CAP')
        if ln.kind == 'entertainment':
            if not approved:
                cap = D('60') * ln.heads
            else:
                cap = reg['limit'] if (reg['limit'] is not None and 'approval_limit' not in F) else None
            if cap is not None and amt > cap:
                amt = cap
                reasons.append('ENTERTAINMENT_CAP')
        if ln.kind == 'rail' and ln.first_class:
            std = ln.standard
            if amt > std:
                amt = std
                reasons.append('RAIL_CLASS')
        if ln.kind == 'mileage':
            correct = mile_pay[ln.n]
            if correct < amt:
                reasons.append('MILEAGE_RATE')
            amt = correct
        pay[ln.n] = amt
        if amt < claimed:
            rule[ln.n] = reasons[0]
    # VAT
    vat = D('0')
    for ln in lines:
        claimed = gbp(ln, rates)
        if 'vat' in F:
            if ln.vat:
                vat += ln.vat if ln.cur == 'GBP' else r2(ln.vat * rates[ln.cur])
            continue
        if ln.vat and pay[ln.n] == claimed and ln.cur == 'GBP' and ln.receipt and ln.kind != 'entertainment':
            vat += ln.vat
    by_emp = {}
    for ln in lines:
        e = claims[ln.claim]['emp']
        by_emp[e] = by_emp.get(e, D('0')) + pay[ln.n]
    nc = {line_id(n, len(lines)): {'rule': rule[n], 'pay': float(pay[n])} for n in sorted(rule)}
    return {'non_compliant': nc,
            'pay_by_employee': {e: float(v) for e, v in by_emp.items()},
            'total_pay': float(sum(pay.values())),
            'vat_reclaimable': float(vat)}, pay, rule


def line_id(n, total):
    return f"L{n:03d}" if total >= 100 else f"L{n:02d}"


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
                register.append(dict(ref=ref, type='ENT', director=rng.choice(directors), emp=c['emp'], limit=None))
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


def gen_big(seed, tier, p=None):
    """Medium and hard batches: many employees, trips with hotels and the meals around them, mileage near the 10,000
    mark with passengers, entertainment with every approval state, receipts at the £25.00 edge, late lines with and
    without a late-submission approval, and duplicates.

    `p` (the Expense Policy Check ladder, verification/finance-ladders/) sets the sizes and the rule families in play;
    without it this is exactly the medium or hard batch of work.expense-audit."""
    rng = random.Random(seed)
    P = dict(n_emp={'medium': 5, 'hard': 8}.get(tier), multi=3 if tier == 'hard' else 0, month={'medium': 9, 'hard': 4}.get(tier),
             extra=['small', 'local_meals'] if tier == 'hard' else [], ents=5 + (2 if tier == 'hard' else 0),
             n_late=2 if tier == 'medium' else 3, n_dup={'medium': 2, 'hard': 4}.get(tier), late_dups=0, edge_fx=0, fams=None)
    P.update(p or {})

    def fam(f):
        return P['fams'] is None or f in P['fams']
    n_emp = P['n_emp']
    names = people(rng, n_emp + 3)
    emps = names[:n_emp]
    directors = names[n_emp:n_emp + 3]
    rates = {'GBP': D('1'), 'EUR': D(str(rng.choice(['0.84', '0.85', '0.86', '0.87']))), 'USD': D(str(rng.choice(['0.74', '0.76', '0.78', '0.79'])))}
    month = P['month']
    base = date(2026, month, 1)
    claims = {}
    lines = []
    register = []
    used_ids = set()
    for i, e in enumerate(emps):
        for k in range(2 if i < P['multi'] else 1):
            while True:
                cid = f"C-{rng.randint(3100, 3999)}"
                if cid not in used_ids:
                    used_ids.add(cid)
                    break
            sub = base + timedelta(days=rng.randint(18, 26) - (9 if k == 0 and i < P['multi'] else 0))
            while sub.weekday() >= 5:
                sub += timedelta(days=1)
            miles_year = 2026 if month >= 5 else 2025
            claims[cid] = dict(id=cid, emp=e, dept=DEPTS[(seed + i) % len(DEPTS)], submitted=sub,
                               miles_before=rng.choice([1200, 4300, 7650, 9480, 9720, 9890, 9935]), miles_year=miles_year)
    C = list(claims.values())
    first_of = {}
    for c in C:
        if c['emp'] in first_of:
            c['miles_before'] = first_of[c['emp']]['miles_before']
        else:
            first_of[c['emp']] = c

    def add(**k):
        k.setdefault('alcohol', None)
        k.setdefault('approval', None)
        k.setdefault('vat', None)
        k.setdefault('first_class', False)
        k.setdefault('note', '')
        k.setdefault('passengers', 0)
        lines.append(Line(**k))
        return lines[-1]

    def wday(d, back=True):
        while d.weekday() >= 5:
            d += timedelta(days=-1 if back else 1)
        return d

    def edate(c, lo=3, hi=30):
        return wday(c['submitted'] - timedelta(days=rng.randint(lo, hi)))

    def vat_of(gross):
        return r2(gross / D(6))

    def money(lo, hi):
        return r2(D(rng.randint(int(lo * 100), int(hi * 100))) / 100)

    def to_local(gbp_amt, cur):
        return r2(gbp_amt / rates[cur]) if cur != 'GBP' else gbp_amt

    def meal_line(c, d, city, kind, amt_gbp, alcohol_gbp=None, receipt=True):
        cur = CUR[city]
        amt = to_local(amt_gbp, cur)
        alc = to_local(alcohol_gbp, cur) if alcohol_gbp else None
        return add(claim=c['id'], date=d, kind='meal', city=city, merchant=rng.choice(MERCH['meal'][city]), desc=kind, amount=amt, cur=cur,
                   receipt=receipt, alcohol=alc, vat=vat_of(amt) if cur == 'GBP' and receipt else None)

    def trip(c):
        city = rng.choice(list(HOTEL_CAP) if fam('conversion') else UK_CITIES)
        n = rng.choice([1, 2, 2, 3])
        d0 = edate(c, 8, 40)
        cap = HOTEL_CAP[city]
        per = cap + money(4, 45) if rng.random() < 0.5 else cap - money(3, 40)
        amt = to_local(r2(per * n), CUR[city])
        add(claim=c['id'], date=d0, kind='hotel', city=city, merchant=rng.choice(MERCH['hotel'][city]), desc=f"Hotel, {n} night{'s' if n > 1 else ''}",
            amount=amt, cur=CUR[city], receipt=True, nights=n, vat=vat_of(amt) if CUR[city] == 'GBP' else None)
        base_cap = MEAL_CAP[city]
        for i in range(n + 1):  # the departure day is not covered by a night
            if not fam('meal') or (not fam('meal-uplift') and i < n):
                continue  # (ladder) no meals yet, or only on the departure day before the hotel-night uplift is a rule
            d = d0 + timedelta(days=i)
            if d.weekday() >= 5 and rng.random() < 0.5:
                continue
            kinds = rng.sample(['Breakfast', 'Lunch', 'Dinner'], rng.choice([1, 2, 2, 3]))
            kinds.sort(key=['Breakfast', 'Lunch', 'Dinner'].index)
            # day totals land between the plain limit and the uplifted limit more often than not
            target = base_cap + money(-8, 26)
            parts = [rng.random() + 0.3 for _ in kinds]
            for kd, w in zip(kinds, parts):
                a = r2(target * D(str(round(w / sum(parts), 3))))
                alc = r2(a * D('0.2')) if kd == 'Dinner' and rng.random() < 0.35 else None
                meal_line(c, d, city, kd, max(a, D('4.50')), alc if fam('alcohol') else None)
        if city in UK_CITIES and city != 'London':
            a, b, std, fc = rng.choice([r for r in RAIL if city.split()[0] in r[1] or city.split()[0] in r[0]] or RAIL)
            first = rng.random() < 0.4 and fam('rail')
            add(claim=c['id'], date=d0, kind='rail', city='London', merchant='National Rail (LNER/Avanti/GWR)', desc=f"Rail {a} – {b} return, {'first' if first else 'standard'} class",
                amount=fc if first else std, cur='GBP', receipt=True, first_class=first, standard=std)

    def local_meals(c):
        d = edate(c)
        for kd in sorted(rng.sample(['Breakfast', 'Lunch', 'Dinner'], rng.choice([1, 2])), key=['Breakfast', 'Lunch', 'Dinner'].index):
            amt = money(6, 32)
            alc = money(3, 9) if kd == 'Dinner' and rng.random() < 0.5 else None
            meal_line(c, d, 'London', kd, amt, alc if fam('alcohol') else None, receipt=rng.random() < 0.85)

    def mileage_run(c):
        k = rng.choice([1, 2, 3])
        dates = sorted(edate(c, 2, 40) for _ in range(k))
        for d in dates:
            miles = rng.choice([46, 84, 126, 157, 212, 264, 318])
            pas = rng.choice([0, 0, 1, 2])
            claimed = r2(D(miles) * D('0.45') + D(miles) * D('0.05') * pas) if rng.random() < 0.7 else r2(D(miles) * D('0.45'))
            add(claim=c['id'], date=d, kind='mileage', city='', merchant='Own car',
                desc=f"Mileage, {miles} miles (client visits)" + (f", {pas} colleague{'s' if pas > 1 else ''} as passenger{'s' if pas > 1 else ''}" if pas else ''),
                amount=claimed, cur='GBP', receipt=False, miles=miles, passengers=pas)

    def ent(c, state):
        heads = rng.randint(3, 7)
        per = money(48, 98)
        amt = r2(per * heads)
        alc = r2(amt * D(rng.choice(['0.15', '0.22', '0.28', '0.34'])))
        ref = f"DA-{rng.randint(100, 899)}" if state != 'none' else None
        if state == 'approved':
            register.append(dict(ref=ref, type='ENT', director=rng.choice(directors), emp=c['emp'], limit=None))
        elif state == 'limit':
            register.append(dict(ref=ref, type='ENT', director=rng.choice(directors), emp=c['emp'], limit=r2(amt * D(rng.choice(['0.7', '0.8', '0.9'])))))
        elif state == 'other':  # registered, but for another employee
            register.append(dict(ref=ref, type='ENT', director=rng.choice(directors), emp=rng.choice([e for e in emps if e != c['emp']]), limit=None))
        add(claim=c['id'], date=edate(c), kind='entertainment', city='London', merchant=rng.choice(['Guildhall Grill', 'Brasserie Aubert, Covent Garden', 'The Fenwick Dining Room', 'Quayside Oyster Bar']),
            desc=f"Client dinner, {heads} people (incl. {rng.randint(1, 2)} staff)", amount=amt, cur='GBP', receipt=True, alcohol=alc, heads=heads, approval=ref,
            vat=vat_of(amt), note=(f"approved by director, ref {ref}" if ref else ''))

    def small(c, edge=False):
        what = rng.choice([('Stationery World', 'Printer paper and folders'), ('City Car Parks', 'Parking, client visit'), ('Royal Mail', 'Special Delivery postage'),
                           ('High Street Pharmacy', 'First-aid kit restock'), ('Citylink Cars', 'Taxi to client site'), ('Metro Cabs', 'Taxi to the station')])
        amt = rng.choice([D('25.00'), D('24.99'), D('25.01')]) if edge else money(6, 48)
        receipt = (not edge) and rng.random() < 0.8
        taxi = 'Taxi' in what[1]
        add(claim=c['id'], date=edate(c), kind='taxi' if taxi else 'misc', city='London', merchant=what[0], desc=what[1], amount=amt, cur='GBP', receipt=receipt,
            vat=vat_of(amt) if (not taxi and what[0] != 'Royal Mail' and receipt) else None)

    states = ['approved', 'limit', 'other', 'claimed', 'none']
    rng.shuffle(states)
    named = {'trip': trip, 'mileage_run': mileage_run, 'local_meals': local_meals, 'small': small}
    for i, c in enumerate(C):
        if p is None:
            blocks = [trip, mileage_run, local_meals, small, lambda c: small(c, True)]
            if tier == 'hard':
                blocks += [small, local_meals]
        else:
            blocks = [trip] + ([mileage_run] if fam('mileage') else []) + ([local_meals] if fam('meal') else []) + [small, lambda c: small(c, True)]
            blocks += [named[x] for x in P['extra'] if (x != 'local_meals' or fam('meal')) and (x != 'mileage_run' or fam('mileage'))]
        rng.shuffle(blocks)
        for b in blocks:
            b(c)
        ent(c, states[i % len(states)]) if fam('entertainment') and i < P['ents'] else None
    # late lines: one claim with a LATE approval, one where the approval names a different claim
    late_claims = rng.sample(C, P['n_late']) if P['n_late'] else []
    for j, c in enumerate(late_claims):
        ln = [l for l in lines if l.claim == c['id'] and l.kind in ('misc', 'taxi', 'meal')][0]
        ln.date = wday(c['submitted'] - timedelta(days=rng.choice([61, 63, 70, 85])))
        ln.late_planted = True
        if j % 3 == 0:
            register.append(dict(ref=f"LS-{rng.randint(10, 99)}", type='LATE', director=rng.choice(directors), claim=c['id']))
        elif j % 3 == 1:
            others = [x for x in C if x['id'] != c['id']]
            register.append(dict(ref=f"LS-{rng.randint(10, 99)}", type='LATE', director=rng.choice(directors), claim=rng.choice(others)['id']))
    # (ladder) a late line claimed again: the copy is late too, unless an approval covers ITS claim
    for _ in range(P['late_dups']):
        src = rng.choice([l for l in lines if getattr(l, 'late_planted', False)])
        cand = [x for x in C if x['emp'] == claims[src.claim]['emp']]
        dup = Line(**dict(src.__dict__))
        dup.claim = rng.choice(cand)['id']
        dup.receipt = True
        lines.append(dup)
    # (ladder) foreign taxis without a receipt either side of £25.00 after conversion
    for _ in range(P['edge_fx']):
        c = rng.choice(C)
        city = rng.choice(['Paris', 'Dublin', 'New York'])
        cur = CUR[city]
        target = D('25') + D(rng.choice([-35, -12, 9, 21, 48])) / 100
        amt = r2(target / rates[cur])
        merchant = {'Paris': 'G7 Taxi, Paris', 'Dublin': 'Liffey Cabs, Dublin', 'New York': 'Yellow Cab, New York'}[city]
        add(claim=c['id'], date=edate(c), kind='taxi', city=city, merchant=merchant, desc='Taxi to client site', amount=amt, cur=cur, receipt=False)
    # duplicates (the same employee; in the hard tier sometimes in their other claim)
    for _ in range(P['n_dup']):
        src = rng.choice([l for l in lines if l.kind in ('meal', 'taxi', 'misc', 'hotel')])
        cand = [x for x in C if x['emp'] == claims[src.claim]['emp'] and x['submitted'] > src.date + timedelta(days=1 if src.kind != 'hotel' else src.nights)]
        dup = Line(**dict(src.__dict__))
        dup.claim = rng.choice(cand)['id']
        dup.receipt = True
        lines.append(dup)
    # mileage across the tax year: the hard batch sits in April
    order = {cid: i for i, cid in enumerate(claims)}
    lines.sort(key=lambda l: (order[l.claim], l.date if l.kind == 'mileage' else date(2000, 1, 1)))
    # keep each claim's lines in a stable, readable order: by date
    lines.sort(key=lambda l: (order[l.claim], l.date))
    for i, ln in enumerate(lines, 1):
        ln.n = i
    # a duplicate must come after its original: renumbering by date keeps exact copies adjacent, so put the copy last
    return claims, lines, rates, register, directors


def render(claims, lines, rates, register):
    out = []
    out.append(f"Rate table: €1 = £{rates['EUR']}, $1 = £{rates['USD']}.")
    out.append("")
    out.append("Approvals register (written approvals; type ENT = client entertainment, type LATE = late submission of a claim):")
    if register:
        for r in register:
            if r['type'] == 'ENT':
                lim = f", up to £{r['limit']:,.2f}" if r.get('limit') is not None else ''
                out.append(f"  - {r['ref']} (ENT): approved by {r['director']} for {r['emp']}{lim}")
            else:
                out.append(f"  - {r['ref']} (LATE): {r['director']} approves late submission of claim {r['claim']}")
    else:
        out.append("  - (no approvals this period)")
    out.append("")
    for cid, c in claims.items():
        out.append(f"Claim {cid} — {c['emp']} ({c['dept']}), submitted {uky(c['submitted'])}. Business miles paid to this employee in tax year {c['miles_year']}/{str(c['miles_year'] + 1)[2:]} before this batch: {c['miles_before']:,}.")
        for ln in lines:
            if ln.claim != cid:
                continue
            bits = [line_id(ln.n, len(lines)), uky(ln.date), ln.merchant + (f", {ln.city}" if ln.city and ln.kind in ('hotel', 'meal') else ''), ln.desc, money_cur(ln.amount, ln.cur)]
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
            "All amounts in pounds to the penny (a £ sign and thousands separators are fine). Marking: each listed line has two fields (rule, amount) and each total is a field. "
            f"Routine fields are worth 1 point; fields that need extra care (shared or uplifted meal limits, approvals and their limits, mileage thresholds and passengers, late or duplicate lines, the £25.00 edge, the totals) are worth {TRAP_WEIGHT}. "
            f"Every line you list that should not be listed costs two fields at {TRAP_WEIGHT} points each. Reply with the JSON object only (a ```json code block is fine); key order, spacing and letter case do not matter. "
            "Give one value per field: a field with two values is marked wrong, and if you give more than one JSON answer only the last one counts.")


CASES = [('x01', 501, 'easy'), ('x02', 502, 'easy'), ('x03', 503, 'easy'),
         ('x04', 604, 'medium'), ('x05', 605, 'medium'), ('x06', 606, 'medium'),
         ('x07', 707, 'hard'), ('x08', 708, 'hard'), ('x09', 709, 'hard')]


def make_cases():
    out = []
    for cid, seed, tier in CASES:
        claims, lines, rates, register, directors = (gen if tier == 'easy' else gen_big)(seed, tier)
        key, pay, rule = audit(claims, lines, rates, register)
        careless, _, _ = audit(claims, lines, rates, register, ALL)
        slip, _, _ = audit(claims, lines, rates, register, SLIP)
        assert key['non_compliant'], cid
        # every exact copy is a deliberate duplicate of the same employee's line, and it comes after the original
        seen = {}
        for ln in lines:
            k = (claims[ln.claim]['emp'], ln.date, ln.merchant, ln.amount)
            if k in seen:
                assert rule.get(ln.n) == 'DUPLICATE' or rule.get(ln.n) in ('LATE',) or pay[ln.n] == 0, (cid, ln.n)
            seen.setdefault(k, ln.n)
        out.append(dict(id=cid, tier=tier, prompt=prompt(claims, lines, rates, register), expected=key, careless=careless, slip=slip, n=len(lines)))
    return out


if __name__ == '__main__':
    import json
    cs = make_cases()
    for c in cs:
        print(c['id'], c['tier'], c['n'], 'lines', len(c['prompt']), 'chars', len(c['expected']['non_compliant']), 'non-compliant', 'careless nc', len(c['careless']['non_compliant']))
    print(cs[6]['prompt'])
    print(json.dumps(cs[6]['expected'], indent=1))
    print(json.dumps(cs[6]['careless'], indent=1))
