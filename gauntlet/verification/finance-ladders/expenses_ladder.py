"""work.expenses-ladder — "Expense Policy Check: Ladder".

Ten frozen levels of the same expenses audit as work.expense-audit, built with the SAME generator (`gen_big` with
ladder sizes), the SAME rules engine (`audit()`, exact decimals) and the SAME policy wording
(verification/realworld-work/expenses.py). A level prints only the rules it uses, word for word as in the full
policy (renumbered in the same order), and each level adds rules and claim lines:

  L01  ~10 lines, 2 employees: receipts from £25.00 and hotel limits per night
  L02  + meal limits shared across a day per city, first-class rail
  L03  + alcohol, the 60-day limit with late-submission approvals, duplicates across claims
  L04  + client entertainment and its approvals, mileage 45p/25p with passengers, euro and dollar conversion
  L05  + the hotel-night meal uplift: the full policy at the size of the hardest Expense Policy Check case
  L06-L10  the full policy, up to ~700 lines from 27 employees, more claims per person (mileage, duplicates and
           late lines across claims), late lines claimed again, foreign taxis either side of £25.00 after conversion.

The key is audit() over the structured lines. Cross-checks (as work.expense-audit, plus the ladder's own): every
exact copy is a planted duplicate and nothing else is; every rule a level does not print changes nothing when the
engine switches it off, and the features it governs do not occur; the full-policy wording is byte-identical to
expenses.POLICY and the full prompt to expenses.prompt().
"""
import os
import sys
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'realworld-work'))
import expenses as X  # noqa: E402
from expenses import audit, gen_big, line_id, money_cur, MEAL_CAP, HOTEL_CAP, TRAP_WEIGHT  # noqa: E402
from wk import uky  # noqa: E402

D = Decimal

FAMILIES = ['receipt', 'hotel', 'meal', 'rail', 'alcohol', 'late', 'duplicate', 'entertainment', 'mileage', 'conversion', 'meal-uplift']
FAMS = {1: FAMILIES[:2], 2: FAMILIES[:4], 3: FAMILIES[:7], 4: FAMILIES[:10]}
for _l in range(5, 11):
    FAMS[_l] = FAMILIES[:]
RULE_FAMILY = {'LATE': 'late', 'DUPLICATE': 'duplicate', 'NO_RECEIPT': 'receipt', 'ALCOHOL': 'alcohol', 'MEAL_CAP': 'meal', 'HOTEL_CAP': 'hotel',
               'ENTERTAINMENT_CAP': 'entertainment', 'RAIL_CLASS': 'rail', 'MILEAGE_RATE': 'mileage'}

# Sizes per level (gen_big's ladder parameters). month 4 = April: mileage crosses the 6 April tax-year reset.
SIZES = {
    1: dict(n_emp=2, multi=0, month=9, extra=[], ents=0, n_late=0, n_dup=0),
    2: dict(n_emp=3, multi=0, month=9, extra=[], ents=0, n_late=0, n_dup=0),
    3: dict(n_emp=3, multi=1, month=9, extra=[], ents=0, n_late=2, n_dup=2),
    4: dict(n_emp=5, multi=1, month=9, extra=[], ents=5, n_late=3, n_dup=2),
    5: dict(n_emp=8, multi=3, month=4, extra=['small', 'local_meals'], ents=7, n_late=3, n_dup=4),
    6: dict(n_emp=11, multi=5, month=4, extra=['small', 'local_meals'], ents=10, n_late=5, n_dup=6, late_dups=2, edge_fx=3),
    7: dict(n_emp=14, multi=7, month=4, extra=['small', 'local_meals', 'trip'], ents=13, n_late=7, n_dup=8, late_dups=3, edge_fx=4),
    8: dict(n_emp=18, multi=9, month=4, extra=['small', 'local_meals', 'trip'], ents=16, n_late=9, n_dup=10, late_dups=4, edge_fx=5),
    9: dict(n_emp=22, multi=12, month=4, extra=['small', 'local_meals', 'trip'], ents=20, n_late=11, n_dup=12, late_dups=5, edge_fx=6),
    10: dict(n_emp=27, multi=15, month=4, extra=['small', 'local_meals', 'trip'], ents=24, n_late=13, n_dup=14, late_dups=6, edge_fx=7),
}
SEEDS = {l: 41000 + 97 * l for l in range(1, 11)}

# ───────────────────────────── the policy, rule family by rule family ─────────────────────────────
UK_LIMITS = {'meal': 'London £40, other UK cities £30', 'hotel': 'London £180, other UK cities £120'}
ABROAD_LIMITS = {'meal': ', Paris £48, Dublin £44, New York £60', 'hotel': ', Paris £190, Dublin £170, New York £240'}


def policy(fams):
    on = lambda f: f in fams  # noqa: E731
    rules = []  # (name, text builder taking the rule numbers)
    if on('late'):
        rules.append(('LATE', lambda n: "a line whose expense date is more than 60 days before its claim's submission date pays £0, unless the register holds a late-submission approval (type LATE) for that claim number."))
    if on('duplicate'):
        rules.append(('DUPLICATE', lambda n: 'the same expense claimed again (same employee, date, merchant and amount, in the same or another claim): the later line (higher line number) pays £0.'))
    if on('receipt'):
        rules.append(('NO_RECEIPT', lambda n: f"any line of £25.00 or more{' (after conversion)' if on('conversion') else ''} without a receipt pays £0.{' Mileage needs no receipt.' if on('mileage') else ''}"))
    if on('alcohol'):
        rules.append(('ALCOHOL', lambda n: f"alcohol is never paid (deduct the alcohol amount shown on the line{', converted if needed' if on('conversion') else ''})"
                      + (f", except on client entertainment approved under rule {n['ENTERTAINMENT_CAP']}, where alcohol is paid up to £15 per head (heads counted as in rule {n['ENTERTAINMENT_CAP']}) and only the excess is deducted." if on('entertainment') else '.')))
    if on('meal'):
        rules.append(('MEAL_CAP', lambda n: f"meals (breakfast, lunch, dinner) are paid up to a limit for each employee, each city and each day: {UK_LIMITS['meal']}{ABROAD_LIMITS['meal'] if on('conversion') else ''}. Meals in different cities on the same day are capped separately, each against its own city's limit."
                      + (" A city's limit is £20 higher on a day when the same employee has a hotel stay in that city in this batch covering the night of that day (a hotel line dated D for N nights covers the nights of D to D+N-1, not the morning of departure)." if on('meal-uplift') else '')
                      + f" When one employee has several meals in the same city on the same day, take them in line order: each is paid up to what is left of that limit{' (after any alcohol deduction)' if on('alcohol') else ''}."))
    if on('hotel'):
        rules.append(('HOTEL_CAP', lambda n: f"hotels are paid up to a limit per night: {UK_LIMITS['hotel']}{ABROAD_LIMITS['hotel'] if on('conversion') else ''}."))
    if on('entertainment'):
        rules.append(('ENTERTAINMENT_CAP', lambda n: f"client entertainment is paid up to £60 per head (count everyone at the table, staff included), after any alcohol deduction. It is approved only if the line's approval reference is in the register as type ENT for the same employee; an approval mentioned on a claim but missing from the register, or registered for someone else, does not count. Approved entertainment is paid in full (alcohol as in rule {n['ALCOHOL']}); if the register gives a limit, the amount left after any alcohol deduction is capped at that limit."))
    if on('rail'):
        rules.append(('RAIL_CLASS', lambda n: 'rail travel is paid at standard class: a first-class ticket is paid at the standard fare shown on the line.'))
    if on('mileage'):
        rules.append(('MILEAGE_RATE', lambda n: "business mileage in a private car is paid at 45p a mile for the first 10,000 business miles in the tax year and 25p a mile after that, plus 5p a mile for each colleague carried as a passenger (the passenger supplement does not count towards the 10,000). The tax year runs 6 April to 5 April. Count each employee's miles in date order across all their claims in this batch, starting from the miles paid before this batch in the tax year named on their claims (in any other tax year, start from 0); a journey that crosses 10,000 is split. Mileage is paid at the correct rate whatever amount was claimed."))
    num = {name: i + 1 for i, (name, _) in enumerate(rules)}
    letters = iter('ABCDE')
    out = ['Expenses policy (Finance, version 8) — apply every rule exactly as written.', '']
    if on('conversion'):
        out.append(f"{next(letters)}. Conversion. Amounts in euros or US dollars are converted to pounds at the rate in the rate table, rounded to the nearest penny, BEFORE any limit is applied. All limits are in pounds.")
    out.append(f"{next(letters)}. Rules, in this order. A line breaks a rule when that rule reduces what the company pays for it.")
    for name, text in rules:
        out.append(f" {num[name]}. {name} — {text(num)}")
    out.append(f"{next(letters)}. Report a line as non-compliant when it is paid less than its claimed amount (in pounds). Give the FIRST rule in the list above that reduced it, and the amount to pay. Lines paid in full (or more) are compliant and must not be listed"
               + ('; a mileage line claimed below the correct amount is paid the correct amount and is compliant.' if on('mileage') else '.'))
    conds = ['is paid in full'] + (['is in pounds (a UK expense)'] if on('conversion') else []) + ['has a receipt']
    if on('entertainment'):
        conds.append('is not client entertainment')
    vat = f"{next(letters)}. VAT. The company reclaims the VAT shown on a line only if the line {', '.join(conds[:-1])} and {conds[-1]}." + (' Mileage carries no VAT.' if on('mileage') else '')
    out.append(vat)
    return '\n'.join(out), [name for name, _ in rules]


def render(claims, lines, rates, register, fams):
    """expenses.render(), showing only what the level's rules use (no rate table before conversion, no register
    before approvals, no miles-to-date before mileage)."""
    out = []
    if 'conversion' in fams:
        out.append(f"Rate table: €1 = £{rates['EUR']}, $1 = £{rates['USD']}.")
        out.append("")
    if 'late' in fams or 'entertainment' in fams:
        kinds = (['type ENT = client entertainment'] if 'entertainment' in fams else []) + (['type LATE = late submission of a claim'] if 'late' in fams else [])
        out.append(f"Approvals register (written approvals; {', '.join(kinds)}):")
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
        miles = f" Business miles paid to this employee in tax year {c['miles_year']}/{str(c['miles_year'] + 1)[2:]} before this batch: {c['miles_before']:,}." if 'mileage' in fams else ''
        out.append(f"Claim {cid} — {c['emp']} ({c['dept']}), submitted {uky(c['submitted'])}.{miles}")
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
    xs.append('the totals')
    return ', '.join(xs)


def prompt(claims, lines, rates, register, fams):
    text, rules = policy(fams)
    schema = ('{"non_compliant": {"L03": {"rule": "HOTEL_CAP", "pay": 360.00}, "L07": {"rule": "NO_RECEIPT", "pay": 0}, ...}, '
              '"pay_by_employee": {"Full Name": 512.40, "Other Name": 98.10, ...}, "total_pay": 610.50, "vat_reclaimable": 41.27}')
    rule_list = ', '.join(rules)
    return ("I run the monthly expenses check. Please audit this batch of claim lines against our policy and tell me what to pay.\n\n"
            f"{text}\n\n{render(claims, lines, rates, register, fams)}\n\n"
            "Answer with one JSON object in exactly this shape:\n" + schema + "\n"
            f"- non_compliant: one entry per line that is paid less than claimed, keyed by line number, with the rule (one of {rule_list}) and the amount to pay in pounds. Do not list lines that are paid in full.\n"
            "- pay_by_employee: the total to pay each employee (every employee in the batch); total_pay: the total for the batch; vat_reclaimable: the total VAT the company can reclaim.\n"
            "All amounts in pounds to the penny (a £ sign and thousands separators are fine). Marking: each listed line has two fields (rule, amount) and each total is a field. "
            f"Routine fields are worth 1 point; fields that need extra care ({care_list(fams)}) are worth {TRAP_WEIGHT}. "
            f"Every line you list that should not be listed costs two fields at {TRAP_WEIGHT} points each. Reply with the JSON object only (a ```json code block is fine); key order, spacing and letter case do not matter. "
            "Give one value per field: a field with two values is marked wrong, and if you give more than one JSON answer only the last one counts.")


# ───────────────────────────── checks ─────────────────────────────
OFF = {'meal-uplift': {'uplift'}, 'late': {'late'}, 'duplicate': {'dup'}, 'entertainment': {'approval', 'alc_cap', 'approval_limit'}, 'mileage': {'mileage', 'passengers'}}


def check(level, claims, lines, rates, register, key):
    fams = set(FAMS[level])
    p = SIZES[level]
    bad = []
    for f, flags in OFF.items():
        if f not in fams and audit(claims, lines, rates, register, frozenset(flags))[0] != key:
            bad.append(f'{f} is not printed but changes the key')
    feats = {
        'alcohol': any(l.alcohol for l in lines), 'entertainment': any(l.kind == 'entertainment' for l in lines), 'mileage': any(l.kind == 'mileage' for l in lines),
        'conversion': any(l.cur != 'GBP' for l in lines), 'rail': any(l.kind == 'rail' and l.first_class for l in lines), 'meal': any(l.kind == 'meal' for l in lines),
        'late': any((claims[l.claim]['submitted'] - l.date).days > 60 for l in lines) or any(r['type'] == 'LATE' for r in register),
    }
    for f, present in feats.items():
        if present and f not in fams:
            bad.append(f'{f} occurs but is not printed')
    for ln_id, v in key['non_compliant'].items():
        if RULE_FAMILY[v['rule']] not in fams:
            bad.append(f'{ln_id}: {v["rule"]} not printed')
    # every exact copy is a planted duplicate
    seen, copies = set(), 0
    for ln in lines:
        k = (claims[ln.claim]['emp'], ln.date, ln.merchant, ln.amount)
        copies += k in seen
        seen.add(k)
    if copies != p.get('n_dup', 0) + p.get('late_dups', 0):
        bad.append(f'{copies} exact copies, planted {p.get("n_dup", 0) + p.get("late_dups", 0)}')
    if not key['non_compliant']:
        bad.append('nothing to flag')
    # a late copy must be late itself (so the first rule it breaks is LATE unless an approval covers its claim)
    return bad


SLIPS = [('ignores the limit on an approval', {'approval_limit'}), ('forgets the passenger supplement', {'passengers'}),
         ('reads the receipt rule as "over £25"', {'receipt'}), ('forgets the hotel-night meal uplift', {'uplift'}),
         ('caps each meal on its own', {'percap'})]


def build_level(level):
    fams = set(FAMS[level])
    for k in range(60):
        claims, lines, rates, register, directors = gen_big(SEEDS[level] * 100 + k, 'ladder', dict(SIZES[level], fams=fams))
        key, pay, rule = audit(claims, lines, rates, register)
        if not check(level, claims, lines, rates, register, key):
            break
    else:
        raise AssertionError(f'L{level:02d}: no valid batch')
    careless = audit(claims, lines, rates, register, True)[0]
    slip, slip_kind = key, 'none'
    for name, flags in SLIPS:
        a = audit(claims, lines, rates, register, frozenset(flags))[0]
        if a != key:
            slip, slip_kind = a, name
            break
    return dict(id=f'L{level:02d}', level=level, prompt=prompt(claims, lines, rates, register, fams), expected=key, careless=careless, slip=slip, slip_kind=slip_kind,
                n=len(lines), emps=len({c['emp'] for c in claims.values()}), claims=len(claims), attempt=k, fams=FAMS[level])


def self_test():
    """The full-policy wording is the current test's, byte for byte."""
    text, _ = policy(set(FAMILIES))
    assert text == X.POLICY, 'full policy differs from expenses.POLICY'
    claims, lines, rates, register, _ = gen_big(605, 'medium')
    assert prompt(claims, lines, rates, register, set(FAMILIES)) == X.prompt(claims, lines, rates, register), 'full prompt differs from expenses.prompt()'


def make_cases():
    self_test()
    return [build_level(l) for l in range(1, 11)]


if __name__ == '__main__':
    from common import tok
    for c in make_cases():
        print(c['id'], c['n'], 'lines', c['emps'], 'employees', c['claims'], 'claims', tok(c['prompt']), 'tokens', len(c['expected']['non_compliant']), 'non-compliant', 'attempt', c['attempt'], 'slip:', c['slip_kind'])
