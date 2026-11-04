"""work.expenses-ladder — "Expense Policy Check: Ladder".

Ten frozen levels of expense auditing for one team, built with the generator (`gen_big`), the rules engine
(`audit()`, exact decimals) and the policy wording of work.expense-audit (verification/realworld-work/expenses.py).
Levels 1-2 are one month; from level 3 a level is several consecutive monthly batches for the SAME employees, and
the months depend on each other:

  * mileage is counted in date order through the months: 45p becomes 25p at 10,000 miles and the count restarts on
    6 April, so a month's mileage lines depend on every earlier mileage line of that person;
  * duplicates are claimed again in a later month, late lines come back with or without an approval for THEIR claim,
    and meal limits are shared with lines of any earlier month on the same day;
  * cash advances (from level 5) are set off against what a person is owed month by month, the rest carried on.

The answer carries that state: each month's payout per person, the advances still outstanding and every person's
miles to date, as well as every non-compliant line. A slip early in the chain (a mileage threshold, a duplicate, an
advance) moves every later month of that person.

Key: audit() over every month's lines in order. Second derivations: (1) month by month, audit() over the lines of
that month and the months before only, must give every line the same result; (2) advances by the step-by-step
set-off and by a closed form (outstanding = the largest sum of advances minus amounts owed over any run of recent
months, floored at 0) must agree; (3) miles to date summed directly from the lines, with the tax-year restart.
"""
import json
import os
import random
import sys
from datetime import date, timedelta
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'realworld-work'))
from expenses import audit, gen_big, line_id, money_cur, TRAP_WEIGHT, Line  # noqa: E402
from wk import uky, people  # noqa: E402

D = Decimal
MONTH_NAMES = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December']

FAMILIES = ['receipt', 'hotel', 'meal', 'rail', 'alcohol', 'late', 'duplicate', 'months', 'entertainment', 'mileage', 'conversion', 'meal-uplift', 'advances']
RULE_FAMILY = {'LATE': 'late', 'DUPLICATE': 'duplicate', 'NO_RECEIPT': 'receipt', 'ALCOHOL': 'alcohol', 'MEAL_CAP': 'meal', 'HOTEL_CAP': 'hotel',
               'ENTERTAINMENT_CAP': 'entertainment', 'RAIL_CLASS': 'rail', 'MILEAGE_RATE': 'mileage'}
# level: months, families printed, employees, first month, per-month gen_big sizes, cross-month duplicates per month, advances
LEVELS = {
    1: dict(months=1, nf=2, emp=2, start=9, size=dict(multi=0, extra=[], ents=0, n_late=0, n_dup=0), xdup=0, adv=0),
    2: dict(months=1, nf=4, emp=3, start=9, size=dict(multi=0, extra=[], ents=0, n_late=0, n_dup=0), xdup=0, adv=0),
    3: dict(months=2, nf=8, emp=3, start=8, size=dict(multi=1, extra=[], ents=0, n_late=1, n_dup=1), xdup=1, adv=0),
    4: dict(months=2, nf=11, emp=4, start=3, size=dict(multi=1, extra=[], ents=4, n_late=1, n_dup=1), xdup=1, adv=0),
    5: dict(months=3, nf=13, emp=4, start=2, size=dict(multi=1, extra=[], ents=4, n_late=1, n_dup=1), xdup=1, adv=2),
    6: dict(months=3, nf=13, emp=5, start=2, size=dict(multi=2, extra=[], ents=5, n_late=2, n_dup=1, edge_fx=1), xdup=2, adv=3),
    7: dict(months=4, nf=13, emp=5, start=1, size=dict(multi=2, extra=[], ents=5, n_late=2, n_dup=1, edge_fx=1, late_dups=1), xdup=2, adv=4),
    8: dict(months=4, nf=13, emp=6, start=1, size=dict(multi=3, extra=['small'], ents=6, n_late=2, n_dup=2, edge_fx=1, late_dups=1), xdup=2, adv=5),
    9: dict(months=5, nf=13, emp=6, start=12, size=dict(multi=3, extra=['small'], ents=6, n_late=2, n_dup=2, edge_fx=1, late_dups=1), xdup=3, adv=6),
    10: dict(months=6, nf=13, emp=7, start=12, size=dict(multi=3, extra=['small'], ents=7, n_late=3, n_dup=2, edge_fx=2, late_dups=1), xdup=3, adv=7),
}
SEEDS = {l: 47000 + 89 * l for l in range(1, 11)}

# ───────────────────────────── the policy, rule family by rule family ─────────────────────────────
UK_LIMITS = {'meal': 'London £40, other UK cities £30', 'hotel': 'London £180, other UK cities £120'}
ABROAD_LIMITS = {'meal': ', Paris £48, Dublin £44, New York £60', 'hotel': ', Paris £190, Dublin £170, New York £240'}


def policy(fams):
    """The work.expense-audit policy for the given families (all of them: exactly expenses.POLICY), with the month
    and advance clauses of the ladder when those families are in play."""
    on = lambda f: f in fams  # noqa: E731
    mo = on('months')
    rules = []
    if on('late'):
        rules.append(('LATE', lambda n: "a line whose expense date is more than 60 days before its claim's submission date pays £0, unless the register holds a late-submission approval (type LATE) for that claim number."))
    if on('duplicate'):
        rules.append(('DUPLICATE', lambda n: f"the same expense claimed again (same employee, date, merchant and amount, in the same or another claim{', in any month' if mo else ''}): the later line (higher line number) pays £0."))
    if on('receipt'):
        rules.append(('NO_RECEIPT', lambda n: f"any line of £25.00 or more{' (after conversion)' if on('conversion') else ''} without a receipt pays £0.{' Mileage needs no receipt.' if on('mileage') else ''}"))
    if on('alcohol'):
        rules.append(('ALCOHOL', lambda n: f"alcohol is never paid (deduct the alcohol amount shown on the line{', converted if needed' if on('conversion') else ''})"
                      + (f", except on client entertainment approved under rule {n['ENTERTAINMENT_CAP']}, where alcohol is paid up to £15 per head (heads counted as in rule {n['ENTERTAINMENT_CAP']}) and only the excess is deducted." if on('entertainment') else '.')))
    if on('meal'):
        rules.append(('MEAL_CAP', lambda n: f"meals (breakfast, lunch, dinner) are paid up to a limit for each employee, each city and each day: {UK_LIMITS['meal']}{ABROAD_LIMITS['meal'] if on('conversion') else ''}. Meals in different cities on the same day are capped separately, each against its own city's limit."
                      + (f" A city's limit is £20 higher on a day when the same employee has a hotel stay in that city in {'these batches' if mo else 'this batch'} covering the night of that day (a hotel line dated D for N nights covers the nights of D to D+N-1, not the morning of departure)." if on('meal-uplift') else '')
                      + f" When one employee has several meals in the same city on the same day, take them in line order: each is paid up to what is left of that limit{' (after any alcohol deduction)' if on('alcohol') else ''}."))
    if on('hotel'):
        rules.append(('HOTEL_CAP', lambda n: f"hotels are paid up to a limit per night: {UK_LIMITS['hotel']}{ABROAD_LIMITS['hotel'] if on('conversion') else ''}."))
    if on('entertainment'):
        rules.append(('ENTERTAINMENT_CAP', lambda n: f"client entertainment is paid up to £60 per head (count everyone at the table, staff included), after any alcohol deduction. It is approved only if the line's approval reference is in the register as type ENT for the same employee; an approval mentioned on a claim but missing from the register, or registered for someone else, does not count. Approved entertainment is paid in full (alcohol as in rule {n['ALCOHOL']}); if the register gives a limit, the amount left after any alcohol deduction is capped at that limit."))
    if on('rail'):
        rules.append(('RAIL_CLASS', lambda n: 'rail travel is paid at standard class: a first-class ticket is paid at the standard fare shown on the line.'))
    if on('mileage'):
        across = ("across all their claims in all the months below, starting from the miles paid before the first month in the tax year named on their first claim" if mo
                  else "across all their claims in this batch, starting from the miles paid before this batch in the tax year named on their claims")
        rules.append(('MILEAGE_RATE', lambda n: f"business mileage in a private car is paid at 45p a mile for the first 10,000 business miles in the tax year and 25p a mile after that, plus 5p a mile for each colleague carried as a passenger (the passenger supplement does not count towards the 10,000). The tax year runs 6 April to 5 April. Count each employee's miles in date order {across} (in any other tax year, start from 0); a journey that crosses 10,000 is split. Mileage is paid at the correct rate whatever amount was claimed."))
    num = {name: i + 1 for i, (name, _) in enumerate(rules)}
    letters = iter('ABCDEFG')
    out = ['Expenses policy (Finance, version 8) — apply every rule exactly as written.', '']
    if on('conversion'):
        out.append(f"{next(letters)}. Conversion. Amounts in euros or US dollars are converted to pounds at the rate in the rate table, rounded to the nearest penny, BEFORE any limit is applied. All limits are in pounds.")
    if mo:
        out.append(f"{next(letters)}. Months. The batches below are processed month by month, in order, and the line numbers run on through the months. Every rule that looks at other lines (duplicates, meal limits, mileage) also looks at the lines of earlier months.")
    out.append(f"{next(letters)}. Rules, in this order. A line breaks a rule when that rule reduces what the company pays for it.")
    for name, text in rules:
        out.append(f" {num[name]}. {name} — {text(num)}")
    out.append(f"{next(letters)}. Report a line as non-compliant when it is paid less than its claimed amount (in pounds). Give the FIRST rule in the list above that reduced it, and the amount to pay. Lines paid in full (or more) are compliant and must not be listed"
               + ('; a mileage line claimed below the correct amount is paid the correct amount and is compliant.' if on('mileage') else '.'))
    conds = ['is paid in full'] + (['is in pounds (a UK expense)'] if on('conversion') else []) + ['has a receipt']
    if on('entertainment'):
        conds.append('is not client entertainment')
    out.append(f"{next(letters)}. VAT. The company reclaims the VAT shown on a line only if the line {', '.join(conds[:-1])} and {conds[-1]}." + (' Mileage carries no VAT.' if on('mileage') else ''))
    if on('advances'):
        out.append(f"{next(letters)}. Cash advances. An advance still outstanding is set off against what the employee is owed, month by month: the payout for a month is what that month's lines pay the employee minus the advance outstanding (never below zero), and whatever the set-off does not use stays outstanding for the next month. An advance paid during a month is outstanding only from the next month on.")
    return '\n'.join(out), [name for name, _ in rules]


# ───────────────────────────── the months ─────────────────────────────


class Batch:
    """Several monthly batches for one team: claims, lines (numbered through the months), one rate table, the
    register (ENT / LATE approvals per month) and the cash advances."""


def month_of(start, k):
    m = (start - 1 + k) % 12 + 1
    y = 2026 + (start - 1 + k) // 12
    return y, m


def build_batch(seed, level):
    spec = LEVELS[level]
    fams = set(FAMILIES[:spec['nf']])
    rng = random.Random(seed)
    roster = people(rng, spec['emp'] + 3)
    emps, directors = roster[:spec['emp']], roster[spec['emp']:]
    rates = {'GBP': D('1'), 'EUR': D(rng.choice(['0.84', '0.85', '0.86', '0.87'])), 'USD': D(rng.choice(['0.74', '0.76', '0.78', '0.79']))}
    b = Batch()
    b.months, b.claims, b.lines, b.register, b.emps, b.rates, b.fams = [], {}, [], [], emps, rates, fams
    used_cids, used_refs = set(), set()
    first_claim = {}
    for k in range(spec['months']):
        y, m = month_of(spec['start'], k)
        b.months.append((y, m))
        mseed = seed * 31 + k * 1009
        names = people(random.Random(mseed), spec['emp'] + 3)
        emap = dict(zip(names[:spec['emp']], emps))
        emap.update(zip(names[spec['emp']:spec['emp'] + 3], directors))
        p = dict(spec['size'], n_emp=spec['emp'], month=m, year=y, fams=fams, rates=rates, floor=date(y, m, 1))
        claims, lines, _, register, _ = gen_big(mseed, 'ladder', p)
        cmap = {}
        for cid, c in claims.items():
            new = cid
            while new in used_cids:
                new = f"C-{rng.randint(3100, 3999)}"
            used_cids.add(new)
            cmap[cid] = new
            c['id'], c['emp'], c['k'] = new, emap[c['emp']], k
            if c['emp'] in first_claim:
                c['miles_before'], c['miles_year'] = first_claim[c['emp']]['miles_before'], first_claim[c['emp']]['miles_year']
            else:
                first_claim[c['emp']] = c
            b.claims[new] = c
        rmap = {}
        for r in register:
            r = dict(r, director=emap[r['director']], k=k)
            if r['type'] == 'ENT':
                r['emp'] = emap[r['emp']]
                ref = r['ref']
                while ref in used_refs:
                    ref = f"DA-{rng.randint(100, 899)}"
                rmap[r['ref']] = ref
                r['ref'] = ref
                used_refs.add(ref)
            else:
                r['claim'] = cmap[r['claim']]
            b.register.append(r)
        for ln in lines:
            ln.claim = cmap[ln.claim]
            ln.k = k
            if ln.approval:
                ln.approval = rmap.get(ln.approval, ln.approval)
                if ln.note:
                    ln.note = f"approved by director, ref {ln.approval}"
            b.lines.append(ln)
        # the same expense claimed again in a later month (in a claim of the same employee)
        if k and 'duplicate' in fams:
            for _ in range(spec['xdup']):
                src = rng.choice([l for l in b.lines if l.k == k - 1 and l.kind in ('meal', 'taxi', 'misc') and not getattr(l, 'late_planted', False)])
                emp = b.claims[src.claim]['emp']
                dup = Line(**dict(src.__dict__))
                dup.claim = rng.choice([c['id'] for c in b.claims.values() if c['k'] == k and c['emp'] == emp])
                dup.k = k
                dup.receipt = True
                dup.cross = True
                b.lines.append(dup)
    order = {cid: i for i, cid in enumerate(b.claims)}
    b.lines.sort(key=lambda l: (order[l.claim], l.date))
    for i, ln in enumerate(b.lines, 1):
        ln.n = i
    # advances: some outstanding at the start, more paid during the months
    b.advances = []  # (employee, month index it becomes outstanding from, amount, month paid or None for "before")
    if 'advances' in fams:
        for j in range(spec['adv']):
            e = emps[j % len(emps)]
            k = -1 if j < max(1, spec['adv'] // 3) else rng.randint(0, spec['months'] - 2)
            b.advances.append(dict(emp=e, paid_k=k, amount=D(rng.randint(80, 300) * 10), ref=f"ADV-{rng.randint(10, 99)}{j}"))
    b.spec = spec
    return b


def month_label(b, k):
    y, m = b.months[k]
    return f"{MONTH_NAMES[m - 1]} {y}"


def tax_year(d):
    return d.year if d >= date(d.year, 4, 6) else d.year - 1


def solve(b):
    """The key: audit() over every month's lines, then the month-by-month payouts, the advance set-off and miles."""
    key, pay, rule = audit(b.claims, b.lines, b.rates, b.register)
    owed = {(e, k): D('0') for e in b.emps for k in range(len(b.months))}
    for ln in b.lines:
        owed[(b.claims[ln.claim]['emp'], ln.k)] += pay[ln.n]
    paid_out, left = advance_steps(b, owed)
    out = {'non_compliant': key['non_compliant'],
           'paid_out': {month_label(b, k): {e: float(paid_out[(e, k)]) for e in b.emps} for k in range(len(b.months))},
           'vat_reclaimable': key['vat_reclaimable']}
    if 'advances' in b.fams:
        out['advances_left'] = {e: float(left[e]) for e in sorted({a['emp'] for a in b.advances})}
    if 'mileage' in b.fams:
        out['miles_to_date'] = miles_to_date(b)
    return out, pay, owed


def advance_steps(b, owed):
    """Step by step: each month, set the outstanding advance off against what is owed, then add the month's new advances."""
    left = {e: sum((a['amount'] for a in b.advances if a['emp'] == e and a['paid_k'] == -1), D('0')) for e in b.emps}
    paid = {}
    for k in range(len(b.months)):
        for e in b.emps:
            use = min(left[e], owed[(e, k)])
            paid[(e, k)] = owed[(e, k)] - use
            left[e] -= use
        for a in b.advances:
            if a['paid_k'] == k:
                left[a['emp']] += a['amount']
    return paid, left


def advance_closed_form(b, owed):
    """Independent: outstanding after month k = max(0, max over j <= k of (advances available from month j on minus
    everything owed from month j to k)); a month's set-off is the fall in (outstanding + new advances)."""
    n = len(b.months)
    left, paid = {}, {}
    for e in b.emps:
        a = [sum((x['amount'] for x in b.advances if x['emp'] == e and x['paid_k'] == k - 1), D('0')) for k in range(n + 1)]  # a[k]: becomes outstanding at the start of month k
        o = [owed[(e, k)] for k in range(n)]

        def out_after(k):
            best = D('0')
            for j in range(k + 1):
                best = max(best, sum(a[j:k + 1], D('0')) - sum(o[j:k + 1], D('0')))
            return best
        prev = D('0')
        for k in range(n):
            now = out_after(k)
            paid[(e, k)] = o[k] - (prev + a[k] - now)
            prev = now
        left[e] = prev + a[n]
    return paid, left


def miles_to_date(b):
    """Each employee's business miles in the tax year of the last month, at the end of the last month."""
    y, m = b.months[-1]
    ty = tax_year(date(y, m, 28))
    out = {}
    for e in b.emps:
        ls = [l for l in b.lines if l.kind == 'mileage' and b.claims[l.claim]['emp'] == e]
        if not ls:
            continue
        c0 = min((c for c in b.claims.values() if c['emp'] == e), key=lambda c: c['k'])
        start = c0['miles_before'] if c0['miles_year'] == ty else 0
        out[e] = start + sum(l.miles for l in ls if tax_year(l.date) == ty)
    return out


def careless(b):
    """audit()'s careless reviewer, each month taken at face value: no advance set-off, miles counted from the figure
    on the first claim without the tax-year restart."""
    key, pay, _ = audit(b.claims, b.lines, b.rates, b.register, True)
    out = {'non_compliant': key['non_compliant'], 'paid_out': {}, 'vat_reclaimable': key['vat_reclaimable']}
    for k in range(len(b.months)):
        out['paid_out'][month_label(b, k)] = {e: float(sum((pay[l.n] for l in b.lines if l.k == k and b.claims[l.claim]['emp'] == e), D('0'))) for e in b.emps}
    if 'advances' in b.fams:
        out['advances_left'] = {e: float(sum((a['amount'] for a in b.advances if a['emp'] == e), D('0'))) for e in sorted({a['emp'] for a in b.advances})}
    if 'mileage' in b.fams:
        out['miles_to_date'] = {e: min((c for c in b.claims.values() if c['emp'] == e), key=lambda c: c['k'])['miles_before'] + sum(l.miles for l in b.lines if l.kind == 'mileage' and b.claims[l.claim]['emp'] == e)
                                for e in miles_to_date(b)}
    return out


def chain_error(b, key):
    """The reasoning error that spreads: every rule right, but each month audited on its own, as if the earlier months
    did not exist (mileage restarts from the opening figure, earlier months' duplicates and meals are not seen)."""
    pay, rule = {}, {}
    for k in range(len(b.months)):
        sub = [l for l in b.lines if l.k == k]
        cl = {cid: c for cid, c in b.claims.items() if c['k'] == k}
        _, pk, rk = audit(cl, sub, b.rates, b.register)
        pay.update(pk)
        rule.update(rk)
    owed = {(e, k): D('0') for e in b.emps for k in range(len(b.months))}
    for ln in b.lines:
        owed[(b.claims[ln.claim]['emp'], ln.k)] += pay[ln.n]
    paid, left = advance_steps(b, owed)
    out = json.loads(json.dumps(key))
    out['non_compliant'] = {line_id(n, len(b.lines)): {'rule': rule[n], 'pay': float(pay[n])} for n in sorted(rule)}
    out['paid_out'] = {month_label(b, k): {e: float(paid[(e, k)]) for e in b.emps} for k in range(len(b.months))}
    if 'advances_left' in out:
        out['advances_left'] = {e: float(left[e]) for e in out['advances_left']}
    if 'miles_to_date' in out:
        y, m = b.months[-1]
        ty = tax_year(date(y, m, 28))
        for e in out['miles_to_date']:
            c0 = min((c for c in b.claims.values() if c['emp'] == e), key=lambda c: c['k'])
            out['miles_to_date'][e] = (c0['miles_before'] if c0['miles_year'] == ty else 0) + sum(l.miles for l in b.lines if l.kind == 'mileage' and l.k == len(b.months) - 1 and b.claims[l.claim]['emp'] == e and tax_year(l.date) == ty)
    return out


SLIPS = [('ignores the limit on an approval', {'approval_limit'}), ('forgets the passenger supplement', {'passengers'}),
         ('reads the receipt rule as "over £25"', {'receipt'}), ('forgets the hotel-night meal uplift', {'uplift'})]


def slip(b, key):
    for name, flags in SLIPS:
        k2, pay, _ = audit(b.claims, b.lines, b.rates, b.register, frozenset(flags))
        if k2['non_compliant'] != key['non_compliant']:
            owed = {(e, k): D('0') for e in b.emps for k in range(len(b.months))}
            for ln in b.lines:
                owed[(b.claims[ln.claim]['emp'], ln.k)] += pay[ln.n]
            paid, left = advance_steps(b, owed)
            out = json.loads(json.dumps(key))
            out['non_compliant'] = k2['non_compliant']
            out['vat_reclaimable'] = k2['vat_reclaimable']
            out['paid_out'] = {month_label(b, k): {e: float(paid[(e, k)]) for e in b.emps} for k in range(len(b.months))}
            if 'advances_left' in out:
                out['advances_left'] = {e: float(left[e]) for e in out['advances_left']}
            return out, name
    return key, 'none'


OFF = {'meal-uplift': {'uplift'}, 'late': {'late'}, 'duplicate': {'dup'}, 'entertainment': {'approval', 'alc_cap', 'approval_limit'}, 'mileage': {'mileage', 'passengers'}}


def check(b, key, pay, owed):
    bad = []
    fams = b.fams
    base, _, _ = audit(b.claims, b.lines, b.rates, b.register)
    for f, flags in OFF.items():
        if f not in fams and audit(b.claims, b.lines, b.rates, b.register, frozenset(flags))[0] != base:
            bad.append(f'{f} is not printed but changes the key')
    feats = {'alcohol': any(l.alcohol for l in b.lines), 'entertainment': any(l.kind == 'entertainment' for l in b.lines), 'mileage': any(l.kind == 'mileage' for l in b.lines),
             'conversion': any(l.cur != 'GBP' for l in b.lines), 'rail': any(l.kind == 'rail' and l.first_class for l in b.lines), 'meal': any(l.kind == 'meal' for l in b.lines),
             'late': any((b.claims[l.claim]['submitted'] - l.date).days > 60 for l in b.lines)}
    for f, present in feats.items():
        if present and f not in fams:
            bad.append(f'{f} occurs but is not printed')
    for v in key['non_compliant'].values():
        if RULE_FAMILY[v['rule']] not in fams:
            bad.append(f'{v["rule"]} not printed')
    # (1) month by month: the lines of month k audited with only the months before it give the same results
    for k in range(len(b.months)):
        sub = [l for l in b.lines if l.k <= k]
        cl = {cid: c for cid, c in b.claims.items() if c['k'] <= k}
        _, pay_k, rule_k = audit(cl, sub, b.rates, b.register)
        for l in sub:
            if l.k == k and pay_k[l.n] != pay[l.n]:
                bad.append(f'L{l.n}: month-by-month audit differs')
    # (2) advances two ways
    p1, l1 = advance_steps(b, owed)
    p2, l2 = advance_closed_form(b, owed)
    if p1 != p2 or l1 != l2:
        bad.append('advance set-off: the two derivations differ')
    if 'advances' in fams and not any(p1[(e, k)] != owed[(e, k)] for e in b.emps for k in range(1, len(b.months))):
        bad.append('no advance set-off after the first month')
    # every non-late line dated within its own month, never after its claim
    for l in b.lines:
        c = b.claims[l.claim]
        late = (c['submitted'] - l.date).days > 60 or getattr(l, 'cross', False) or getattr(l, 'late_planted', False)
        if not late and not (date(*b.months[l.k], 1) <= l.date <= c['submitted']):
            bad.append(f'L{l.n} dated outside its month')
    # every exact copy is a planted duplicate
    seen, copies = set(), 0
    for l in b.lines:
        kk = (b.claims[l.claim]['emp'], l.date, l.merchant, l.amount)
        copies += kk in seen
        seen.add(kk)
    sz = b.spec['size']
    planted = len(b.months) * (sz.get('n_dup', 0) + sz.get('late_dups', 0)) + (len(b.months) - 1) * b.spec['xdup']
    if copies != planted:
        bad.append(f'{copies} exact copies, planted {planted}')
    if 'mileage' in fams:
        ys = {tax_year(l.date) for l in b.lines if l.kind == 'mileage'}
        if len(b.months) > 2 and len(ys) < 2:
            bad.append('mileage never crosses 6 April')
    return bad


# ───────────────────────────── rendering ─────────────────────────────


def render(b):
    fams = b.fams
    on = lambda f: f in fams  # noqa: E731
    n_lines = len(b.lines)
    out = []
    if on('conversion'):
        out.append(f"Rate table{' (all the months below)' if len(b.months) > 1 else ''}: €1 = £{b.rates['EUR']}, $1 = £{b.rates['USD']}.")
        out.append('')
    if on('advances'):
        start = [a for a in b.advances if a['paid_k'] == -1]
        out.append(f"Cash advances outstanding at the start of {month_label(b, 0)}: " + ('; '.join(f"{a['emp']} £{a['amount']:,.2f} ({a['ref']})" for a in start) or 'none') + '.')
        out.append('')
    for k in range(len(b.months)):
        if len(b.months) > 1:
            out.append(f"══ {month_label(b, k)} batch ══")
        if on('late') or on('entertainment'):
            kinds = (['type ENT = client entertainment'] if on('entertainment') else []) + (['type LATE = late submission of a claim'] if on('late') else [])
            regs = [r for r in b.register if r['k'] == k]
            out.append(f"Approvals register{' entries this month' if len(b.months) > 1 else ''} (written approvals; {', '.join(kinds)}):")
            for r in regs:
                if r['type'] == 'ENT':
                    lim = f", up to £{r['limit']:,.2f}" if r.get('limit') is not None else ''
                    out.append(f"  - {r['ref']} (ENT): approved by {r['director']} for {r['emp']}{lim}")
                else:
                    out.append(f"  - {r['ref']} (LATE): {r['director']} approves late submission of claim {r['claim']}")
            if not regs:
                out.append("  - (no approvals this period)")
            out.append('')
        if on('advances'):
            new = [a for a in b.advances if a['paid_k'] == k]
            if new:
                out.append('Cash advances paid this month: ' + '; '.join(f"{a['emp']} £{a['amount']:,.2f} ({a['ref']})" for a in new) + '.')
                out.append('')
        for cid, c in b.claims.items():
            if c['k'] != k:
                continue
            miles = ''
            if on('mileage') and cid == next(x['id'] for x in b.claims.values() if x['emp'] == c['emp']):
                miles = f" Business miles paid to this employee in tax year {c['miles_year']}/{str(c['miles_year'] + 1)[2:]} before {'the first month' if len(b.months) > 1 else 'this batch'}: {c['miles_before']:,}."
            out.append(f"Claim {cid} — {c['emp']} ({c['dept']}), submitted {uky(c['submitted'])}.{miles}")
            for ln in b.lines:
                if ln.claim != cid:
                    continue
                bits = [line_id(ln.n, n_lines), uky(ln.date), ln.merchant + (f", {ln.city}" if ln.city and ln.kind in ('hotel', 'meal') else ''), ln.desc, money_cur(ln.amount, ln.cur)]
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
            out.append('')
    return '\n'.join(out).strip()


def care_list(fams):
    on = lambda f: f in fams  # noqa: E731
    xs = []
    if on('meal'):
        xs.append('shared or uplifted meal limits' if on('meal-uplift') else 'shared meal limits')
    if on('entertainment'):
        xs.append('approvals and their limits')
    if on('mileage'):
        xs.append('mileage thresholds and passengers')
    if on('late') or on('duplicate'):
        xs.append('late or duplicate lines')
    xs.append('the £25.00 edge')
    return ', '.join(xs)


def prompt(b, chain_w=TRAP_WEIGHT):
    fams = b.fams
    multi = len(b.months) > 1
    text, rules = policy(fams)
    names = [month_label(b, k) for k in range(len(b.months))]
    pay_ex = ', '.join(f'"{n}": {{"Full Name": 512.40, ...}}' for n in names[:2]) + (', ...' if multi else '')
    schema = ('{"non_compliant": {"L03": {"rule": "HOTEL_CAP", "pay": 360.00}, "L07": {"rule": "NO_RECEIPT", "pay": 0}, ...}, '
              f'"paid_out": {{{pay_ex}}}, '
              + ('"advances_left": {"Full Name": 120.00, ...}, ' if 'advances' in fams else '')
              + ('"miles_to_date": {"Full Name": 10312, ...}, ' if 'mileage' in fams else '')
              + '"vat_reclaimable": 41.27}')
    intro = ("I run the monthly expenses check. Please audit this batch of claim lines against our policy and tell me what to pay." if not multi else
             f"I've been off, so the expenses for {names[0]} to {names[-1]} were never checked or paid. Please audit every monthly batch against our policy, in order, and tell me what to pay each person each month.")
    lines = [intro, '', text, '', render(b), '', 'Answer with one JSON object in exactly this shape:', schema,
             f"- non_compliant: one entry per line that is paid less than claimed, keyed by line number, with the rule (one of {', '.join(rules)}) and the amount to pay in pounds. Do not list lines that are paid in full.",
             f"- paid_out: for each month (keyed by the month as written above), the amount to pay each employee (every employee) for that month's batch{', after setting off any advance' if 'advances' in fams else ''}."]
    if 'advances' in fams:
        lines.append(f"- advances_left: for every employee who has had an advance, the advance still outstanding after the {names[-1]} batch.")
    if 'mileage' in fams:
        lines.append(f"- miles_to_date: for every employee with a mileage line, their business miles in the tax year of {names[-1]} once the {names[-1]} batch is counted (the miles before the first month included when that is the same tax year).")
    lines.append("- vat_reclaimable: the total VAT the company can reclaim" + (' over all the months.' if multi else '.'))
    lines.append("All amounts in pounds to the penny (a £ sign and thousands separators are fine). Marking: each listed line has two fields (rule, amount); every payout, advance, miles figure and the VAT is a field. "
                 f"Routine fields are worth 1 point; fields that need extra care ({care_list(fams)}, the VAT) are worth {TRAP_WEIGHT}, and every payout{', advance' if 'advances' in fams else ''}{' and miles figure' if 'mileage' in fams else ''} is worth {chain_w} (together about a third of the points). "
                 f"Every line you list that should not be listed costs two fields at {TRAP_WEIGHT} points each. Reply with the JSON object only (a ```json code block is fine); key order, spacing and letter case do not matter. "
                 "Give one value per field: a field with two values is marked wrong, and if you give more than one JSON answer only the last one counts.")
    return '\n'.join(lines)


def build_level(level):
    for k in range(80):
        try:
            b = build_batch(SEEDS[level] * 100 + k, level)
        except (IndexError, ValueError):
            continue
        key, pay, owed = solve(b)
        if not check(b, key, pay, owed) and key['non_compliant']:
            break
    else:
        raise AssertionError(f'L{level:02d}: no valid set of months')
    s, kind = slip(b, key)
    return dict(id=f'L{level:02d}', level=level, make_prompt=lambda w: prompt(b, w), expected=key, careless=careless(b), slip=s, slip_kind=kind,
                chain=chain_error(b, key) if len(b.months) > 1 else None, n=len(b.lines), emps=len(b.emps),
                claims=len(b.claims), months=len(b.months), attempt=k, fams=FAMILIES[:LEVELS[level]['nf']])


def make_cases():
    return [build_level(l) for l in range(1, 11)]


if __name__ == '__main__':
    sys.path.insert(0, os.path.join(HERE, '..'))
    from common import tok
    for c in make_cases():
        print(c['id'], c['months'], 'months', c['n'], 'lines', c['emps'], 'employees', c['claims'], 'claims', tok(c['make_prompt'](9)), 'tokens', len(c['expected']['non_compliant']), 'non-compliant', 'attempt', c['attempt'], 'slip:', c['slip_kind'])
