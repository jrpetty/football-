"""work.reconciliation-ladder — "Reconcile the Account: Ladder".

Ten frozen levels of month-end bank reconciliation for one company, built on the transaction generator of
work.bank-reconciliation (verification/realworld-work/bankrec.py, class `Rec`) and its matching rules and wording.
From level 3 on a level covers SEVERAL consecutive months of one account, and the months depend on each other:

  * a cheque, a paying-in or the second half of a keyed-in payment can clear in a later month, so a cash-book entry
    is matched with a bank line of another month, and every month's balance must know which of the earlier entries
    are still outstanding at that month's end;
  * the cash book is never corrected: bank charges, interest, direct debits, duplicates, reversed charges, returned
    cheques and keying or FX differences found in one month still have to be allowed for in every later month's
    adjusted balance;
  * an instalment plan pays one cash-book entry across several months, outstanding by the part not yet through;
  * a charge reversed, or a cheque returned unpaid, in the month after the original line.

One early mistake (a missed charge, a wrong difference, an item called outstanding that cleared) therefore carries
into every later month's balance. The balances are worth a quarter of a level's points.

Key: the generator's transaction list. Second derivation, independent of it: every month's adjusted balance is
computed twice, from the cash-book side (printed cash book + every correction so far) and from the bank side
(statement + every entry not yet through at that month end), and they must agree; and every one-to-one match is
re-derived from the printed fields alone and must be the only candidate (check_unique, across months).
"""
import json
import os
import sys
from datetime import date, timedelta
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'realworld-work'))
from bankrec import Rec, SUPPLIERS, CUSTOMERS, transpose, linked, fmt, TRAP_WEIGHT, r2  # noqa: E402
from wk import uk  # noqa: E402

D = Decimal
MONTH_NAMES = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December']

FAMILIES = ['keying', 'dd', 'duplicates', 'carry', 'splits', 'reversal', 'returned', 'batch', 'fx', 'instalments']
# level: (months, families printed, sizes per month)
LEVELS = {
    1: (1, 1), 2: (1, 3), 3: (2, 5), 4: (2, 8), 5: (3, 10), 6: (3, 10), 7: (4, 10), 8: (4, 10), 9: (5, 10), 10: (6, 10),
}
# ordinary payments, receipts, and cross-month items per month (cheques, paying-in, keying, reversal, returned, instalments)
SIZES = {
    1: dict(pay=6, rec=4, cross=0), 2: dict(pay=8, rec=5, cross=0), 3: dict(pay=8, rec=5, cross=1), 4: dict(pay=9, rec=5, cross=1),
    5: dict(pay=10, rec=6, cross=2), 6: dict(pay=12, rec=6, cross=3), 7: dict(pay=12, rec=6, cross=3), 8: dict(pay=14, rec=7, cross=4),
    9: dict(pay=14, rec=7, cross=4), 10: dict(pay=15, rec=7, cross=5),
}
SEEDS = {l: 6100 + 37 * l for l in range(1, 11)}
BANK_ONLY = ('bank_charge', 'interest', 'unrecorded_direct_debit', 'duplicate_payment', 'reversed_charge', 'returned_cheque')


class MRec(Rec):
    """A Rec over several consecutive months: `window(m)` points Rec's own same-month methods at month m."""

    def __init__(self, seed, months):
        super().__init__(seed, 'ladder')
        self.n_months = months
        start = self.rng.choice([1, 3, 5, 6] if months <= 4 else [1, 3, 5])
        self.windows = []
        for k in range(months):
            m = start + k
            first = date(self.year, m, 1)
            nxt = date(self.year + (m == 12), m % 12 + 1, 1)
            self.windows.append((m, first, nxt - timedelta(days=1)))
        self.opening = r2(self.opening + D(30000 * months))
        self.bank_opening = self.opening
        self.cur = 0

    def window(self, k):
        self.cur = k
        self.month, self.first, self.last = self.windows[k]
        lw = self.last
        while lw.weekday() >= 5:
            lw -= timedelta(days=1)
        self.lastwd = lw

    def wd(self, d, back=False):
        while d.weekday() >= 5:
            d += timedelta(days=-1 if back else 1)
        return d

    def late_day(self, k):
        """A working day in the last few days of month k."""
        m, first, last = self.windows[k]
        return self.wd(last - timedelta(days=self.rng.randint(1, 4)), back=True)

    def early_day(self, k, lo=1, hi=8):
        m, first, last = self.windows[k]
        return self.wd(first + timedelta(days=self.rng.randint(lo, hi) - 1))

    # cross-month transactions ---------------------------------------------------------------------
    def cheque_cross(self, k, clears):
        """A cheque written late in month k that clears `clears` months later (None: not yet through)."""
        sup = self.rng.choice(SUPPLIERS)
        amt = self.amount(150, 2900)
        no = f"{self.rng.randint(100200, 100899):06d}"
        L = self.add_ledger(self.late_day(k), f"Cheque {no} to {sup[0]}", f"CHQ {no}", -amt, dict(kind='chq'))
        if clears is None or k + clears >= self.n_months:
            self.truth_ledger[L] = 'unpresented_cheque'
            return
        B = self.add_bank(self.early_day(k + clears, 1, 20), f"CHQ {no}", -amt, dict(kind='chq'))
        self.link(B, L)

    def paid_in_cross(self, k):
        """Takings paid in at the branch on the last working day of month k, on the statement next month."""
        cus = self.rng.choice(CUSTOMERS)
        amt = self.amount(300, 6400)
        slip = self.rng.randint(4400, 4999)
        _, first, last = self.windows[k]
        L = self.add_ledger(self.wd(last, back=True), f"Cash and cheques paid in at branch (paying-in slip {slip}) — {cus[0]}", f"PIS {slip}", amt, dict(kind='dit'))
        if k + 1 >= self.n_months:
            self.truth_ledger[L] = 'deposit_in_transit'
            return
        B = self.add_bank(self.early_day(k + 1, 1, 3), f"PAID IN AT BRANCH PIS {slip}", amt, dict(kind='dit'))
        self.link(B, L)

    def keying_cross(self, k):
        """A payment keyed wrongly in the cash book late in month k that the bank pays early in month k + 1."""
        sup = self.rng.choice(SUPPLIERS)
        real = self.amount(1100, 8800)
        wrong = transpose(real, self.rng)
        inv = self.ref('INV-')
        _, first, last = self.windows[k]
        d = self.wd(last - timedelta(days=1), back=True)
        L = self.add_ledger(d, f"{sup[0]} — invoice {inv}", inv, -wrong, dict(kind='key'))
        c = self.wd(d + timedelta(days=self.rng.randint(2, 4)))
        if c <= last:
            c = self.early_day(k + 1, 1, 2)
        B = self.add_bank(c, f"FP OUT {sup[1]} REF {inv.replace('-', '')}", -real, dict(kind='key'))
        self.link(B, L)
        self.diffs[B] = ('keying_error', -real - (-wrong))

    def reversal_cross(self, k):
        amt = self.amount(15, 35)
        B1 = self.add_bank(self.late_day(k), 'UNPAID ITEM CHARGE', -amt, dict(kind='rev'))
        B2 = self.add_bank(self.early_day(k + 1, 1, 6), 'REVERSAL OF UNPAID ITEM CHARGE', amt, dict(kind='rev'))
        self.truth_bank[B1] = 'reversed_charge'
        self.truth_bank[B2] = 'reversed_charge'

    def returned_cross(self, k):
        cus = self.rng.choice(CUSTOMERS)
        amt = self.amount(200, 2600)
        no = f"{self.rng.randint(300100, 300899):06d}"
        d = self.late_day(k)
        L = self.add_ledger(d, f"Cheque {no} from {cus[0]} paid in", f"CHQ {no}", amt, dict(kind='rtn'))
        c = self.wd(d + timedelta(days=1))
        if c > self.windows[k][2]:
            c = d
        B = self.add_bank(c, f"CHQ PAID IN {no}", amt, dict(kind='rtn'))
        self.link(B, L)
        B2 = self.add_bank(self.early_day(k + 1, 2, 8), f"RETURNED CHQ {no} UNPAID REFER TO DRAWER", -amt, dict(kind='rtn2'))
        self.truth_bank[B2] = 'returned_cheque'

    def instalments(self, k, parts):
        """One cash-book payment in month k paid by the bank in `parts` monthly instalments (k, k + 1, ...)."""
        sup = self.rng.choice(SUPPLIERS)
        each = self.amount(700, 2600)
        total = each * parts
        inv = self.ref('INV-')
        d = self.early_day(k, 2, 12)
        L = self.add_ledger(d, f"{sup[0]} — invoice {inv} (paid in {parts} monthly instalments)", inv, -total, dict(kind='inst'))
        Bs = []
        for i in range(parts):
            bd = d if i == 0 else self.wd(self.windows[k + i][1] + timedelta(days=d.day - 1 if d.day <= 25 else 24))
            Bs.append(self.add_bank(self.wd(bd), f"FP OUT {sup[1]} REF {inv.replace('-', '')} {i + 1}OF{parts}", -each, dict(kind='inst')))
        for B in Bs:
            self.truth_bank[B] = L
        self.truth_ledger[L] = Bs

    # numbering and balances ------------------------------------------------------------------------
    def month_of(self, d):
        for k, (m, first, last) in enumerate(self.windows):
            if first <= d <= last:
                return k
        raise AssertionError(f'date {d} outside the months')

    def finish(self):
        for x in self.bank + self.ledger:
            x['k'] = self.month_of(x['date'])
        bo = sorted(range(len(self.bank)), key=lambda i: (self.bank[i]['date'], i))
        lo = sorted(range(len(self.ledger)), key=lambda i: (self.ledger[i]['date'], i))
        width = 3 if max(sum(1 for x in self.bank if x['k'] == k) for k in range(self.n_months)) >= 100 or max(sum(1 for x in self.ledger if x['k'] == k) for k in range(self.n_months)) >= 100 else 2
        bid, lid = {}, {}
        cnt = {}
        for i in bo:
            k = self.bank[i]['k']
            cnt[('B', k)] = cnt.get(('B', k), 0) + 1
            bid[i] = f"B{k + 1}{cnt[('B', k)]:0{width}d}"
        for i in lo:
            k = self.ledger[i]['k']
            cnt[('L', k)] = cnt.get(('L', k), 0) + 1
            lid[i] = f"L{k + 1}{cnt[('L', k)]:0{width}d}"

        def m(v, ids):
            if isinstance(v, list):
                return sorted(ids[x] for x in v)
            if isinstance(v, int):
                return ids[v]
            return v
        self.key_bank = {bid[i]: m(self.truth_bank[i], lid) for i in bo}
        self.key_ledger = {lid[i]: m(self.truth_ledger[i], bid) for i in lo}
        self.key_diffs = {bid[i]: {'reason': r, 'amount': float(a)} for i, (r, a) in sorted(self.diffs.items(), key=lambda kv: bid[kv[0]])}
        self.B = [dict(self.bank[i], id=bid[i]) for i in bo]
        self.L = [dict(self.ledger[i], id=lid[i]) for i in lo]
        self.bank_month = {b['id']: b['k'] for b in self.B}
        bal = self.opening
        self.bank_close = []
        for k in range(self.n_months):
            for b in self.B:
                if b['k'] == k:
                    bal += b['amount']
                    b['balance'] = bal
                    assert bal > 500, 'overdrawn'
            self.bank_close.append(bal)
        cb = self.opening
        self.ledger_close = []
        for k in range(self.n_months):
            cb += sum((l['amount'] for l in self.L if l['k'] == k), D('0'))
            self.ledger_close.append(cb)

    def month_label(self, k):
        m = self.windows[k][0]
        return f"{MONTH_NAMES[m - 1]} {self.year}"


def balances(rec):
    """Every month's adjusted balance, from both sides; they must agree (raises otherwise)."""
    out = []
    for k in range(rec.n_months):
        # cash-book side: printed cash book to the end of month k + every correction found in months 1..k
        cb = rec.ledger_close[k]
        for b in rec.B:
            if b['k'] <= k and rec.key_bank[b['id']] in BANK_ONLY:
                cb += b['amount']
        for bid_, v in rec.key_diffs.items():
            if rec.bank_month[bid_] <= k:
                cb += D(str(v['amount']))
        for l in rec.L:
            if l['k'] <= k and rec.key_ledger[l['id']] == 'duplicate_entry':
                cb -= l['amount']
        # bank side: statement at the end of month k + every cash-book entry (to month k) not yet through by then
        bk = rec.bank_close[k]
        amounts = {b['id']: b['amount'] for b in rec.B}
        for l in rec.L:
            if l['k'] > k:
                continue
            v = rec.key_ledger[l['id']]
            if v == 'duplicate_entry':
                continue
            if isinstance(v, list):
                through = sum((amounts[x] for x in v if rec.bank_month[x] <= k), D('0'))
                bk += l['amount'] - through
            elif isinstance(v, str) and v.startswith('B'):
                if rec.bank_month[v] > k:
                    bk += l['amount']
            else:
                bk += l['amount']  # unpresented cheque / deposit in transit
        assert cb == bk, ('balance', k, cb, bk)
        out.append(cb)
    return out


def check_unique(rec):
    """Every 1:1 match is the only cash-book entry the printed fields allow: equal (or explained) amount, the bank
    date on or after the cash-book date (within 7 days, cheques any time later), a shared reference or counterparty."""
    for b in rec.B:
        v = rec.key_bank[b['id']]
        if not (isinstance(v, str) and v.startswith('L')) or isinstance(rec.key_ledger[v], list):
            continue
        cands = []
        for l in rec.L:
            late = (b['date'] - l['date']).days
            if late < 0 or (late > 7 and not l['ref'].startswith('CHQ')):
                continue
            shared = linked(b, l)
            if shared and (l['amount'] == b['amount'] or b['id'] in rec.key_diffs):
                cands.append(l['id'])
        cands = [c for c in cands if rec.key_ledger[c] != 'duplicate_entry']
        assert cands == [v], (b['id'], v, cands)


# ───────────────────────────── the rules, family by family ─────────────────────────────


def join_or(xs):
    return xs[0] if len(xs) == 1 else (f"{xs[0]} or {xs[1]}" if len(xs) == 2 else ', '.join(xs[:-1]) + ', or ' + xs[-1])


def join_and(xs):
    return xs[0] if len(xs) == 1 else ', '.join(xs[:-1]) + ' and ' + xs[-1]


def rules_text(fams, months):
    on = lambda f: f in fams  # noqa: E731
    multi = months > 1
    r1 = ('1. Match a bank line with cash-book entries for the same transaction: same counterparty and reference, the bank date on or up to 7 days after the cash-book date'
          + (' (a cheque can take longer: any time after its cash-book date, in a later month too)' if on('carry') else '') + ', and the same amount.')
    if on('splits'):
        r1 += (' A payment or receipt can be split: one cash-book entry may match several bank lines (instalments sharing its reference'
               + (', which can run over several months' if on('instalments') else '') + '), or several cash-book entries one bank line (two part-payments sharing a reference'
               + (", or the payments of one BACS run, which the cash book marks with the run's date" if on('batch') else '') + '), when they add up exactly.')
    if on('carry'):
        r1 += (' Statement lines and cash-book entries are numbered through the months (B1.. and L1.. in the first month, B2.. and L2.. in the second, ...). '
               'An entry is matched with the line that clears it in whichever month that is.')
    expl = (['"keying_error" (the cash book was keyed wrongly, e.g. two digits swapped)'] if on('keying') else []) + (['"fx_difference" (a foreign-currency payment cost a different amount in pounds than the cash book\'s booked rate)'] if on('fx') else [])
    r2_ = (f'2. A match whose amounts differ is still a match when it is the same transaction and the difference is explained: {" or ".join(expl)}. The bank figure is right{" in both cases" if len(expl) == 2 else ""}. '
           'List each in "differences" with amount = bank amount minus cash-book amount (money in positive, money out negative).')
    bank = ['"bank_charge" (fees the bank charged)', '"interest"']
    if on('dd'):
        bank.append('"unrecorded_direct_debit" (a direct debit nobody entered in the cash book)')
    if on('duplicates'):
        bank.append('"duplicate_payment" (the bank paid the same payment twice: the later line is the duplicate)')
    if on('reversal'):
        bank.append('"reversed_charge" (a charge and its reversal on the statement' + (', in the same month or the next' if on('carry') else '') + ': give both lines this reason)')
    if on('returned'):
        bank.append('"returned_cheque" (a cheque paid in that the bank has returned unpaid' + (', in the same month or later' if on('carry') else '') + '; the paying-in itself is matched normally)')
    led = ['"unpresented_cheque" (a cheque not yet through the bank)', '"deposit_in_transit" (money paid in but not yet on the statement)']
    if on('duplicates'):
        led.append('"duplicate_entry" (the same payment entered twice in the cash book: the later entry is the duplicate; the earlier one is matched)')
    r4 = f'4. Cash-book entries with no bank line get a reason: {join_or(led)}.'
    if on('carry'):
        r4 += f' Use these reasons only for entries still not through the bank at the end of {"the last month" if multi else "the month"}.'
    adj = ['every bank-only item'] + (['every difference'] if expl else []) + (['every duplicate entry'] if on('duplicates') else [])
    if on('carry'):
        r5 = (f'5. The cash book has never been corrected. Each month\'s adjusted balance is that month\'s cash-book closing balance corrected for {join_and(adj)} found in that month AND in every earlier month. '
              'It must equal that month\'s statement closing balance plus the money paid in and minus the payments in the cash book (that month or before) not yet through the bank at that month\'s end'
              + (', counting a payment made in instalments by the part not yet through' if on('instalments') else '') + '.')
    else:
        r5 = (f'5. The adjusted balance is the cash-book closing balance corrected for {join_and(adj)}. (It must equal the statement closing balance plus deposits in transit minus unpresented cheques, including any from last month still outstanding.)')
    return '\n'.join(['How to reconcile (apply exactly):', r1, r2_, f'3. Bank lines with no cash-book entry get a reason: {join_or(bank)}.', r4, r5])


def care_list(fams):
    on = lambda f: f in fams  # noqa: E731
    xs = []
    if on('carry'):
        xs.append('an entry cleared in another month')
    if on('splits'):
        xs.append('a split')
    if on('duplicates'):
        xs.append('a duplicate')
    if on('reversal'):
        xs.append('a reversal')
    xs.append('a keying or FX difference' if on('fx') else 'a keying difference')
    xs.append('a timing item')
    return ', '.join(xs)


def prompt(rec, fams, bal_weight):
    on = lambda f: f in fams  # noqa: E731
    multi = rec.n_months > 1
    names = [rec.month_label(k) for k in range(rec.n_months)]
    period = names[0] if not multi else f"{names[0]} to {names[-1]}"
    out = [f"Can you do the bank reconciliation{'s' if multi else ''} for {rec.company}, {period}? The account was fully reconciled at the end of the month before, so the statement and the cash book both opened at £{fmt(rec.opening)}."
           + (" Our bookkeeper has been off, so nothing has been reconciled or corrected since: every month is below." if multi else ''), '', rules_text(fams, rec.n_months), '']
    for k in range(rec.n_months):
        tb = ["Date        | Description                                   | Paid out  | Paid in   | Balance"]
        for b in rec.B:
            if b['k'] == k:
                o = fmt(b['amount']) if b['amount'] < 0 else ''
                i = fmt(b['amount']) if b['amount'] > 0 else ''
                tb.append(f"{b['id']} {uk(b['date'])} | {b['desc']} | {o} | {i} | {fmt(b['balance'])}")
        tl = ["Date | Ref | Details | Payments | Receipts"]
        for l in rec.L:
            if l['k'] == k:
                p = fmt(l['amount']) if l['amount'] < 0 else ''
                rc = fmt(l['amount']) if l['amount'] > 0 else ''
                tl.append(f"{l['id']} {uk(l['date'])} | {l['ref']} | {l['desc']} | {p} | {rc}")
        out.append(f"Bank statement, {names[k]} (Business Current Account):\n" + '\n'.join(tb) + f"\nClosing balance £{fmt(rec.bank_close[k])}")
        out.append('')
        out.append(f"Cash book, {names[k]}:\n" + '\n'.join(tl) + f"\nCash-book closing balance £{fmt(rec.ledger_close[k])}")
        out.append('')
    sch_bal = ', '.join(f'"{n}": 12345.67' for n in names[:2]) + (', ...' if multi else '')
    schema = ('{"bank": {"B101": "L102", "B102": "bank_charge", ' + ('"B105": ["L106", "L107"], ' if on('splits') else '') + '...}, '
              '"ledger": {"L101": "B103", "L102": "B101", "L109": "unpresented_cheque", ...}, '
              '"differences": {"B107": {"reason": "keying_error", "amount": -270.00}}, '
              f'"adjusted_balance": {{{sch_bal}}}}}')
    split_note = (' (a list for a split or a BACS run)' if on('batch') else ' (a list for a split)') if on('splits') else ''
    out.append("Answer with one JSON object in exactly this shape:\n" + schema + "\n"
               f"- bank: every statement line, with the cash-book entry it matches{split_note} or its reason from rule 3.\n"
               f"- ledger: every cash-book entry, with the bank line it matches{' (a list for a split)' if on('splits') else ''} or its reason from rule 4.\n"
               "- differences: one entry per matched bank line whose amount differs from the cash book (rule 2); leave it empty ({}) if there are none.\n"
               f"- adjusted_balance: the adjusted balance at the end of {'each month, keyed by the month as written above' if multi else 'the month, keyed by the month as written above'} (rule 5), in pounds to the penny.\n"
               f"Marking: each bank line, each cash-book entry, each difference's reason and amount and each adjusted balance is a field. Routine fields (a straightforward match) are worth 1 point; "
               f"fields that need extra care ({care_list(fams)}) are worth {TRAP_WEIGHT}, and each adjusted balance is worth {bal_weight} (the balances together are about a quarter of the points). "
               f"Every difference you list that is not one costs two fields at {TRAP_WEIGHT} points each. "
               "Reply with the JSON object only (a ```json code block is fine); key order, spacing and letter case do not matter, and amounts may have a £ sign. "
               "Give one value per field: a field with two answers is marked wrong, and if you give more than one JSON answer only the last one counts.")
    return '\n'.join(out)


# ───────────────────────────── the levels ─────────────────────────────


def build_rec(seed, level):
    months, nf = LEVELS[level]
    fams = set(FAMILIES[:nf])
    sz = SIZES[level]
    rec = MRec(seed, months)
    rng = rec.rng
    for k in range(months):
        rec.window(k)
        for _ in range(sz['pay']):
            rec.payment()
        for _ in range(sz['rec']):
            rec.receipt()
        rec.cheque(True)
        rec.bank_only('bank_charge')
        if k == months - 1 or rng.random() < 0.5:
            rec.bank_only('interest')
        rec.keying_error()
        if months == 1:
            rec.cheque(False)
            rec.deposit_in_transit()
        if 'dd' in fams:
            rec.bank_only('unrecorded_direct_debit')
        if 'duplicates' in fams:
            (rec.ledger_duplicate if k % 2 == 0 else rec.bank_duplicate)()
            if level >= 6:
                (rec.bank_duplicate if k % 2 == 0 else rec.ledger_duplicate)()
        if 'splits' in fams:
            rec.split(bank_split=rng.random() < 0.5)
        if 'reversal' in fams and k == months - 1:
            rec.reversed_charge()
        if 'returned' in fams and k == months - 1:
            rec.returned_cheque()
        if 'batch' in fams:
            rec.batch(3 + k % 3)
        if 'fx' in fams:
            rec.fx()
        # cross-month items: they clear in a later month, or are still outstanding at the end
        if 'carry' in fams:
            rec.paid_in_cross(k)
            for j in range(sz['cross']):
                rec.cheque_cross(k, rng.choice([1, 1, 2, None]) if j else 1)
            if k < months - 1:
                rec.keying_cross(k)
                if 'reversal' in fams and rng.random() < 0.7:
                    rec.reversal_cross(k)
                if 'returned' in fams and rng.random() < 0.7:
                    rec.returned_cross(k)
                if 'instalments' in fams and k + 2 <= months:
                    rec.instalments(k, min(months - k, rng.choice([2, 3])))
            elif months == 1:
                pass
        if 'carry' not in fams and k == months - 1:
            pass
    if 'carry' in fams:
        rec.window(months - 1)
        rec.cheque(False)
    rec.window(0)
    rec.finish()
    return rec, fams


def careless(rec):
    """A hurried bookkeeper: each month on its own, exact amounts only, within that month's statement; leftovers are
    timing items or bank charges; each month's balance corrected for that month's bank-only lines only."""
    kb, kl = {}, {}
    for k in range(rec.n_months):
        used = set()
        for b in [x for x in rec.B if x['k'] == k]:
            best = next((l for l in rec.L if l['k'] == k and l['id'] not in used and l['amount'] == b['amount'] and 0 <= (b['date'] - l['date']).days <= 7), None)
            if best:
                used.add(best['id'])
                kb[b['id']] = best['id']
                kl[best['id']] = b['id']
            else:
                kb[b['id']] = 'bank_charge' if b['amount'] < 0 else 'interest'
    for l in rec.L:
        kl.setdefault(l['id'], 'unpresented_cheque' if l['amount'] < 0 else 'deposit_in_transit')
    bals = {}
    for k in range(rec.n_months):
        bals[rec.month_label(k)] = float(rec.ledger_close[k] + sum((b['amount'] for b in rec.B if b['k'] == k and not str(kb[b['id']]).startswith('L')), D('0')))
    return {'bank': kb, 'ledger': kl, 'differences': {}, 'adjusted_balance': bals}


def slip(rec, exp):
    """A strong bookkeeper's one slip: the first keying difference's sign flipped, and with it every balance from that
    month on (the slip carries, as it would in real books)."""
    out = json.loads(json.dumps(exp))
    first = min(out['differences'])
    v = out['differences'][first]
    v['amount'] = -v['amount']
    k0 = rec.bank_month[first]
    for k in range(k0, rec.n_months):
        out['adjusted_balance'][rec.month_label(k)] = float(D(str(out['adjusted_balance'][rec.month_label(k)])) - 2 * D(str(exp['differences'][first]['amount'])))
    return out


def chain_error(rec, exp):
    """The reasoning error that spreads: every line right, but each month's balance corrected only for that month's
    own items (the corrections of earlier months forgotten, as if the cash book had been put right)."""
    out = json.loads(json.dumps(exp))
    for k in range(rec.n_months):
        cb = rec.ledger_close[k]
        for b in rec.B:
            if b['k'] == k and rec.key_bank[b['id']] in BANK_ONLY:
                cb += b['amount']
        for bid_, v in rec.key_diffs.items():
            if rec.bank_month[bid_] == k:
                cb += D(str(v['amount']))
        for l in rec.L:
            if l['k'] == k and rec.key_ledger[l['id']] == 'duplicate_entry':
                cb -= l['amount']
        out['adjusted_balance'][rec.month_label(k)] = float(cb)
    return out


def check(rec, key, fams):
    bad = []
    vals = list(key['bank'].values()) + list(key['ledger'].values())
    if any(isinstance(v, list) for v in vals) and 'splits' not in fams:
        bad.append('a split without its rule')
    reason_family = {'unrecorded_direct_debit': 'dd', 'duplicate_payment': 'duplicates', 'duplicate_entry': 'duplicates', 'reversed_charge': 'reversal', 'returned_cheque': 'returned'}
    for v in vals:
        if isinstance(v, str) and reason_family.get(v) and reason_family[v] not in fams:
            bad.append(f'{v} without its rule')
    if any(v['reason'] == 'fx_difference' for v in key['differences'].values()) and 'fx' not in fams:
        bad.append('fx without its rule')
    cross = sum(1 for l in rec.L for v in [key['ledger'][l['id']]] if isinstance(v, str) and v.startswith('B') and rec.bank_month[v] != l['k'])
    if rec.n_months > 1 and cross < rec.n_months:
        bad.append('too few cross-month clearings')
    chq = [l['ref'] for l in rec.L if l['ref'].startswith('CHQ')]
    if len(chq) != len(set(chq)):
        bad.append('cheque numbers repeat')
    if not key['differences']:
        bad.append('no difference')
    return bad


def bal_weight_for(exp, months, sc_weights):
    """Each balance's weight: the balances together are about a quarter of the level's points."""
    other = sum(sc_weights.get(f'bank.{k}', 1) for k in exp['bank']) + sum(sc_weights.get(f'ledger.{k}', 1) for k in exp['ledger']) + 2 * TRAP_WEIGHT * len(exp['differences'])
    return max(TRAP_WEIGHT, round(other / 3 / months))


def build_level(level):
    for k in range(80):
        try:
            rec, fams = build_rec(SEEDS[level] * 100 + k, level)
            check_unique(rec)
            bals = balances(rec)
        except AssertionError:
            continue
        exp = {'bank': rec.key_bank, 'ledger': rec.key_ledger, 'differences': rec.key_diffs,
               'adjusted_balance': {rec.month_label(i): float(b) for i, b in enumerate(bals)}}
        if not check(rec, exp, fams):
            break
    else:
        raise AssertionError(f'L{level:02d}: no valid set of months')
    months, nf = LEVELS[level]
    return dict(id=f'L{level:02d}', level=level, rec=rec, fams=FAMILIES[:nf], months=months, expected=exp, careless=careless(rec), slip=slip(rec, exp),
                slip_kind='the first keying difference\'s sign flipped (and every balance from that month on)', chain=chain_error(rec, exp) if months > 1 else None, n_bank=len(rec.B), n_ledger=len(rec.L), attempt=k,
                cross=sum(1 for l in rec.L for v in [exp['ledger'][l['id']]] if isinstance(v, str) and v.startswith('B') and rec.bank_month[v] != l['k']))


def make_cases():
    return [build_level(l) for l in range(1, 11)]


if __name__ == '__main__':
    sys.path.insert(0, os.path.join(HERE, '..'))
    from common import tok
    for c in make_cases():
        p = prompt(c['rec'], set(c['fams']), 9)
        print(c['id'], c['months'], 'months', c['n_bank'], 'bank', c['n_ledger'], 'ledger', c['cross'], 'cross-month', tok(p), 'tokens', len(c['expected']['differences']), 'diffs', 'attempt', c['attempt'])
