"""work.reconciliation-ladder — "Reconcile the Account: Ladder".

Ten frozen levels of the same month-end bank reconciliation as work.bank-reconciliation, built with the SAME
transaction generator (the `Rec` class), the SAME uniqueness proof (`check_unique()`: every 1:1 match re-derived from
the printed amounts, dates and references alone) and the SAME balance proof (`balance()`: adjusted cash book =
adjusted bank), and the SAME rules wording (verification/realworld-work/bankrec.py). A level prints only the rules
it uses, word for word as in the full list, and each level adds rules and lines:

  L01  ~14 lines a side: one-to-one matches, unpresented cheques, deposits in transit, bank fees and interest, a
       payment keyed with two digits swapped
  L02  + direct debits nobody entered, payments made twice by the bank or entered twice in the cash book
  L03  + instalments and part-payments, a charge and its reversal
  L04  + BACS runs, a customer cheque returned unpaid, dollar payments at a different rate (the medium case's size)
  L05  + last month's outstanding items: the full rules at the size of the hardest Reconcile the Account case
  L06-L10  the full rules, up to ~600 lines a side, with more of every trap and two more interactions: a
           look-alike payment (same supplier and amount, different invoice) and an instalment the bank paid twice.

The key is the generator's own transaction list; every level passes check_unique() and balance(). Ladder checks:
every reason, difference, split and prior item in the key belongs to a printed rule; the full-rules prompt is
byte-identical to bankrec.prompt(); cheque numbers are unique.
"""
import json
import os
import sys
from datetime import timedelta
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'realworld-work'))
import bankrec as R  # noqa: E402
from bankrec import Rec, SUPPLIERS, check_unique, balance, careless, slip, fmt, TRAP_WEIGHT  # noqa: E402
from wk import uk  # noqa: E402

D = Decimal

FAMILIES = ['keying', 'dd', 'duplicates', 'splits', 'reversal', 'batch', 'returned', 'fx', 'prior']
FAMS = {1: FAMILIES[:1], 2: FAMILIES[:3], 3: FAMILIES[:5], 4: FAMILIES[:8]}
for _l in range(5, 11):
    FAMS[_l] = FAMILIES[:]
REASON_FAMILY = {'bank_charge': None, 'interest': None, 'unpresented_cheque': None, 'deposit_in_transit': None, 'unrecorded_direct_debit': 'dd',
                 'duplicate_payment': 'duplicates', 'duplicate_entry': 'duplicates', 'reversed_charge': 'reversal', 'returned_cheque': 'returned',
                 'keying_error': 'keying', 'fx_difference': 'fx'}
SCALE = {6: 1.6, 7: 2.2, 8: 2.9, 9: 3.8, 10: 5.0}
SEEDS = {l: 5200 + 31 * l for l in range(1, 11)}

# ───────────────────────────── the rules, family by family ─────────────────────────────


def join_or(xs):
    return xs[0] if len(xs) == 1 else (f"{xs[0]} or {xs[1]}" if len(xs) == 2 else ', '.join(xs[:-1]) + ', or ' + xs[-1])


def join_and(xs):
    return xs[0] if len(xs) == 1 else ', '.join(xs[:-1]) + ' and ' + xs[-1]


def rules_text(fams, prior):
    on = lambda f: f in fams  # noqa: E731
    r1 = '1. Match a bank line with cash-book entries for the same transaction: same counterparty and reference, the bank date on or up to 7 days after the cash-book date, and the same amount.'
    if on('splits'):
        r1 += (' A payment or receipt can be split: one cash-book entry may match several bank lines (instalments sharing its reference), or several cash-book entries one bank line (two part-payments sharing a reference'
               + (", or the payments of one BACS run, which the cash book marks with the run's date" if on('batch') else '') + '), when they add up exactly.')
    expl = (['"keying_error" (the cash book was keyed wrongly, e.g. two digits swapped)'] if on('keying') else []) + (['"fx_difference" (a foreign-currency payment cost a different amount in pounds than the cash book\'s booked rate)'] if on('fx') else [])
    r2 = (f'2. A match whose amounts differ is still a match when it is the same transaction and the difference is explained: {" or ".join(expl)}. The bank figure is right{" in both cases" if len(expl) == 2 else ""}. '
          'List each in "differences" with amount = bank amount minus cash-book amount (money in positive, money out negative).')
    bank = ['"bank_charge" (fees the bank charged)', '"interest"']
    if on('dd'):
        bank.append('"unrecorded_direct_debit" (a direct debit nobody entered in the cash book)')
    if on('duplicates'):
        bank.append('"duplicate_payment" (the bank paid the same payment twice: the later line is the duplicate)')
    if on('reversal'):
        bank.append('"reversed_charge" (a charge and its reversal on the statement: give both lines this reason)')
    if on('returned'):
        bank.append('"returned_cheque" (a cheque paid in that the bank has returned unpaid; the paying-in itself is matched normally)')
    led = ['"unpresented_cheque" (a cheque not yet through the bank)', '"deposit_in_transit" (money paid in but not yet on the statement)']
    if on('duplicates'):
        led.append('"duplicate_entry" (the same payment entered twice in the cash book: the later entry is the duplicate; the earlier one is matched)')
    adj = ['every bank-only item'] + (['every difference'] if expl else []) + (['every duplicate entry'] if on('duplicates') else [])
    lines = ['How to reconcile (apply exactly):', r1, r2,
             f'3. Bank lines with no cash-book entry get a reason: {join_or(bank)}.',
             f'4. Cash-book entries with no bank line get a reason: {join_or(led)}.',
             f'5. The adjusted balance is the cash-book closing balance corrected for {join_and(adj)}. (It must equal the statement closing balance plus deposits in transit minus unpresented cheques, including any from last month still outstanding.)']
    text = '\n'.join(lines)
    if prior:
        text += """
6. Items outstanding at the end of last month (P1, P2, …) are not in this month's cash book. A bank line that clears one gets that item's id (e.g. "P1") as its value; in "prior", give each item the bank line that cleared it, or "unpresented_cheque" / "deposit_in_transit" if it is still outstanding."""
    return text


def care_list(fams):
    on = lambda f: f in fams  # noqa: E731
    xs = []
    if on('splits'):
        xs.append('a split')
    if on('duplicates'):
        xs.append('a duplicate')
    if on('reversal'):
        xs.append('a reversal')
    xs.append('a keying or FX difference' if on('fx') else 'a keying difference')
    xs += ['a timing item', 'the balance']
    return ', '.join(xs)


def prompt(rec, fams):
    """bankrec.prompt() with the rules of the level's families (identical to it with every family)."""
    on = lambda f: f in fams  # noqa: E731
    tb = ["Date        | Description                                   | Paid out  | Paid in   | Balance"]
    for b in rec.B:
        out = fmt(b['amount']) if b['amount'] < 0 else ''
        inn = fmt(b['amount']) if b['amount'] > 0 else ''
        tb.append(f"{b['id']} {uk(b['date'])} | {b['desc']} | {out} | {inn} | {fmt(b['balance'])}")
    tl = ["Date | Ref | Details | Payments | Receipts"]
    for l in rec.L:
        pay = fmt(l['amount']) if l['amount'] < 0 else ''
        rc = fmt(l['amount']) if l['amount'] > 0 else ''
        tl.append(f"{l['id']} {uk(l['date'])} | {l['ref']} | {l['desc']} | {pay} | {rc}")
    month = rec.last.strftime('%B %Y')
    rules = rules_text(fams, bool(rec.prior))
    schema = ('{"bank": {"B01": "L02", "B02": "bank_charge", "B05": ["L06", "L07"], ...}, '
              '"ledger": {"L01": "B03", "L02": "B01", "L06": "B05", "L09": "unpresented_cheque", ...}, '
              if on('splits') else
              '{"bank": {"B01": "L02", "B02": "bank_charge", ...}, '
              '"ledger": {"L01": "B03", "L02": "B01", "L09": "unpresented_cheque", ...}, ')
    schema += ('"differences": {"B07": {"reason": "keying_error", "amount": -270.00}}, '
               + ('"prior": {"P1": "B04", "P2": "unpresented_cheque", ...}, ' if rec.prior else '') +
               '"adjusted_balance": 12345.67}')
    if rec.prior:
        pl = '\n'.join(f"  {it['id']} {uk(it['date'])} | {it['ref']} | {it['desc']} | " + (f"payment {fmt(it['amount'])}" if it['amount'] < 0 else f"receipt {fmt(it['amount'])}") for it in rec.prior)
        opening = (f"Last month's reconciliation left these cash-book items not yet through the bank:\n{pl}\n"
                   f"So this month the cash book opened at £{fmt(rec.opening)} and the statement at £{fmt(rec.bank_opening)}.")
    else:
        opening = f"The account was fully reconciled at the end of last month, so the statement and the cash book both opened the month at £{fmt(rec.opening)}."
    split_note = (' (a list for a split or a BACS run)' if on('batch') else ' (a list for a split)') if on('splits') else ''
    return (f"Can you do the {month} bank reconciliation for {rec.company}? {opening}\n\n{rules}\n\n"
            f"Bank statement, {month} (Business Current Account):\n" + '\n'.join(tb) + f"\nClosing balance £{fmt(rec.bank_close)}\n\n"
            f"Cash book, {month}:\n" + '\n'.join(tl) + f"\nCash-book closing balance £{fmt(rec.ledger_close)}\n\n"
            "Answer with one JSON object in exactly this shape:\n" + schema + "\n"
            f"- bank: every statement line, with the cash-book entry it matches{split_note} or its reason from rule 3" + (" or last month's item id" if rec.prior else '') + ".\n"
            f"- ledger: every cash-book entry, with the bank line it matches{' (a list for a split)' if on('splits') else ''} or its reason from rule 4.\n"
            "- differences: one entry per matched bank line whose amount differs from the cash book (rule 2); leave it empty ({}) if there are none.\n"
            "- adjusted_balance: in pounds, to the penny.\n"
            f"Marking: each bank line, each cash-book entry, each difference's reason and amount and the adjusted balance is a field. Routine fields (a straightforward match) are worth 1 point; "
            f"fields that need extra care ({care_list(fams)}) are worth {TRAP_WEIGHT}. Every difference you list that is not one costs two fields at {TRAP_WEIGHT} points each. "
            "Reply with the JSON object only (a ```json code block is fine); key order, spacing and letter case do not matter, and amounts may have a £ sign. "
            "Give one value per field: a field with two answers is marked wrong, and if you give more than one JSON answer only the last one counts.")


# ───────────────────────────── the levels ─────────────────────────────


def dup_instalment(rec):
    """An instalment the bank paid twice: a split payment, then a second copy of its first instalment the next day."""
    before = len(rec.bank)
    rec.split(bank_split=True)
    b = rec.bank[before]
    d2 = b['date'] + timedelta(days=1)
    while d2.weekday() >= 5:
        d2 += timedelta(days=1)
    if d2 > rec.last:
        d2 = b['date']
    B2 = rec.add_bank(d2, b['desc'], b['amount'], dict(kind='bdup'))
    rec.truth_bank[B2] = 'duplicate_payment'


def lookalike(rec):
    """Same supplier and same amount as an earlier ordinary payment, a different invoice, later in the month."""
    pays = [i for i, l in enumerate(rec.ledger) if l['tx']['kind'] == 'pay']
    L0 = rec.ledger[rec.rng.choice(pays)]
    sup = next(s for s in SUPPLIERS if s[0] in L0['desc'])
    rec.payment(amt=-L0['amount'], sup=sup, d=min(L0['date'] + timedelta(days=rec.rng.randint(5, 9)), rec.last - timedelta(days=4)))


def build_small(seed, level):
    rec = Rec(seed, 'ladder')
    rng = rec.rng
    n_pay, n_rec = {1: (6, 4), 2: (10, 6), 3: (15, 8), 4: (22, 10)}[level]
    rec.opening += {1: 0, 2: 8000, 3: 15000, 4: 25000}[level]
    rec.bank_opening = rec.opening
    for _ in range(n_pay):
        rec.payment()
    for _ in range(n_rec):
        rec.receipt()
    rec.cheque(True)
    rec.cheque(False)
    rec.deposit_in_transit()
    rec.bank_only('bank_charge')
    if level < 4:
        rec.bank_only('interest')
    rec.keying_error()
    if level >= 2:
        rec.bank_only('unrecorded_direct_debit')
        rec.ledger_duplicate()
        if level < 4:
            rec.bank_duplicate()
            rec.keying_error()
    if level >= 3:
        rec.cheque(True)
        rec.split(bank_split=True)
        rec.split(bank_split=level == 4 and rng.random() < 0.5)
        rec.reversed_charge()
    if level == 4:
        rec.batch(3)
        rec.returned_cheque()
        rec.fx()
        rec.bank_only('bank_charge')
    rec.finish()
    return rec


def build_big(seed, level):
    """bankrec.build(seed, 'hard') with every count scaled by SCALE[level] (level 5 is exactly the hard build)."""
    if level == 5:
        return R.build(seed, 'hard')[0]
    s = SCALE[level]
    n = lambda k: max(1, round(k * s))  # noqa: E731
    rec = Rec(seed, 'hard')
    rec.opening += round(140000 * s)
    rec.bank_opening = rec.opening
    rec.prior_items()
    for _ in range(n(60)):
        rec.payment()
    for _ in range(n(28)):
        rec.receipt()
    for _ in range(n(4)):
        rec.cheque(True)
    for _ in range(n(2)):
        rec.cheque(False)
    for _ in range(n(2)):
        rec.deposit_in_transit()
    for _ in range(n(2)):
        rec.bank_only('bank_charge')
    for _ in range(n(1)):
        rec.bank_only('interest')
    for _ in range(n(2)):
        rec.bank_only('unrecorded_direct_debit')
    for _ in range(n(6)):
        rec.keying_error()
    for _ in range(n(3)):
        rec.fx()
    for _ in range(n(3)):
        rec.bank_duplicate()
    for _ in range(n(3)):
        rec.ledger_duplicate()
    for k in range(n(4)):
        rec.split(bank_split=k % 4 < 2, parts=3 if k % 4 == 0 else 2)
    for k in range(n(4)):
        rec.batch(3 + k % 3)
    for _ in range(n(3)):
        rec.returned_cheque()
    for _ in range(n(2)):
        rec.reversed_charge()
    for _ in range(n(2)):
        lookalike(rec)
    for _ in range(n(1)):
        dup_instalment(rec)
    rec.finish()
    return rec


def check(rec, key, fams):
    bad = []
    vals = list(key['bank'].values()) + list(key['ledger'].values()) + [v['reason'] for v in key['differences'].values()]
    for v in vals:
        if isinstance(v, list):
            if 'splits' not in fams:
                bad.append('a split without the split rule')
        elif v in REASON_FAMILY and REASON_FAMILY[v] and REASON_FAMILY[v] not in fams:
            bad.append(f'{v} without its rule')
    if any(l['tx']['kind'] == 'batch' for l in rec.L) and 'batch' not in fams:
        bad.append('a BACS run without its rule')
    if rec.prior and 'prior' not in fams:
        bad.append('prior items without their rule')
    chq = [l['ref'] for l in rec.L if l['ref'].startswith('CHQ')] + [it['ref'] for it in rec.prior if it['ref'].startswith('CHQ')]
    if len(chq) != len(set(chq)):
        bad.append('cheque numbers repeat')
    return bad


def build_level(level):
    fams = set(FAMS[level])
    for k in range(60):
        try:
            rec = (build_small if level < 5 else build_big)(SEEDS[level] * 100 + k, level)
            check_unique(rec)
            bal = balance(rec)
        except AssertionError:
            continue
        exp = {'bank': rec.key_bank, 'ledger': rec.key_ledger, 'differences': rec.key_diffs, 'adjusted_balance': float(bal)}
        if rec.prior:
            exp['prior'] = rec.key_prior
        if not check(rec, exp, fams):
            break
    else:
        raise AssertionError(f'L{level:02d}: no valid month')
    s, kind = slip(rec, exp), 'returned cheque booked as a bank charge, FX difference sign flipped'
    if s == exp:
        # no returned cheque or FX payment yet: the strong bookkeeper's slip is the keying difference's sign
        s = json.loads(json.dumps(exp))
        for v in s['differences'].values():
            v['amount'] = -v['amount']
        kind = 'keying difference sign flipped'
    return dict(id=f'L{level:02d}', level=level, prompt=prompt(rec, fams), expected=exp, careless=careless(rec), slip=s, slip_kind=kind,
                n_bank=len(rec.B), n_ledger=len(rec.L), attempt=k, fams=FAMS[level], rec=rec)


def self_test():
    """With every family, the prompt is bankrec.prompt() byte for byte (with and without last month's items)."""
    for tier in ('hard', 'medium'):
        rec, _ = R.build(100700 if tier == 'hard' else 90500, tier)
        assert prompt(rec, set(FAMILIES)) == R.prompt(rec), f'full prompt differs from bankrec.prompt() ({tier})'


def make_cases():
    self_test()
    return [build_level(l) for l in range(1, 11)]


if __name__ == '__main__':
    sys.path.insert(0, os.path.join(HERE, '..'))
    from common import tok
    for c in make_cases():
        print(c['id'], c['n_bank'], 'bank', c['n_ledger'], 'ledger', tok(c['prompt']), 'tokens', len(c['expected']['differences']), 'differences', 'attempt', c['attempt'])
