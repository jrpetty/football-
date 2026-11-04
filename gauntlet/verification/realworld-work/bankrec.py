"""work.bank-reconciliation — "Reconcile the Account".

A month of bank statement lines and the company's cash book, generated from a seed as a set of
transactions: ordinary payments and receipts (which clear 0-4 days later), unpresented cheques, a deposit in
transit, bank fees and interest, a direct debit nobody entered, a payment keyed with two digits swapped, a USD
payment that cost more than the cash book's booked rate, a payment split into two, a payment duplicated in the
cash book or made twice by the bank, and a charge the bank later reversed.

The key comes from the generator's own transaction list. `check_unique()` re-derives every match from the
printed fields alone (amount, dates, counterparty and reference) and asserts that each match is the only one
the rules allow, and `balance()` proves the adjusted cash-book balance equals the adjusted bank balance.
`careless()` is a hurried bookkeeper's answer: matches only exact amounts, so the keyed-in error and the FX
payment are left as unexplained items, the split payment and the reversed charge are missed, duplicates are
not spotted, and every unmatched cash-book entry is called a timing difference.
"""
import json
import random

# Points per field for fields where a trap sits (routine fields are worth 1). Stated in the prompt.
TRAP_WEIGHT = 3
from datetime import date, timedelta
from decimal import Decimal, ROUND_HALF_UP
from wk import uk, uky, people

D = Decimal


def r2(x):
    return D(x).quantize(D('0.01'), rounding=ROUND_HALF_UP)


SUPPLIERS = [('Corran Office Supplies', 'CORRAN OFFICE SUP'), ('Oakhurst Timber', 'OAKHURST TIMBER'), ('Ashby Fleet Hire', 'ASHBY FLEET HIRE'),
             ('Tidewater Print Ltd', 'TIDEWATER PRINT'), ('Kestrel IT Services', 'KESTREL IT SERV'), ('Millbrook Catering', 'MILLBROOK CATERI'),
             ('Pennant Electrical', 'PENNANT ELEC'), ('Greystone Builders Merchants', 'GREYSTONE BM'), ('Harlow & Finch Accountants', 'HARLOW FINCH')]
CUSTOMERS = [('Northgate Housing Trust', 'NORTHGATE HT'), ('Saltmarsh Hotels', 'SALTMARSH HOTELS'), ('Larkspur Academy Trust', 'LARKSPUR ACAD'),
             ('Redcliffe Leisure', 'REDCLIFFE LEIS'), ('Ashgrove Veterinary', 'ASHGROVE VET'), ('Kinloch Distillers', 'KINLOCH DIST'),
             ('Pennine Dental Group', 'PENNINE DENTAL'), ('Gorse & Vale Solicitors', 'GORSE VALE')]
COMPANIES = ['Hollins & Webb Joinery Ltd', 'Calder Valley Signs Ltd', 'Brindle Street Bakery Ltd', 'Marsh Lane Motors Ltd', 'Fairhaven Events Ltd']

BANK_REASONS = ['bank_charge', 'interest', 'unrecorded_direct_debit', 'duplicate_payment', 'reversed_charge', 'returned_cheque']
LEDGER_REASONS = ['unpresented_cheque', 'deposit_in_transit', 'duplicate_entry']
DIFF_REASONS = ['keying_error', 'fx_difference']


def transpose(amount, rng):
    s = f"{amount:.2f}".replace('.', '')
    digits = list(s)
    cands = [i for i in range(len(digits) - 3) if digits[i] != digits[i + 1]]
    assert cands, 'no digits to swap'
    i = rng.choice(cands)
    digits[i], digits[i + 1] = digits[i + 1], digits[i]
    t = ''.join(digits)
    return D(t[:-2] + '.' + t[-2:])


class Rec:
    def __init__(self, seed, tier):
        self.rng = rng = random.Random(seed)
        self.tier = tier
        self.company = rng.choice(COMPANIES)
        self.month = rng.choice([3, 5, 6, 9, 10])
        self.year = 2026
        self.first = date(self.year, self.month, 1)
        nxt = date(self.year + (self.month == 12), self.month % 12 + 1, 1)
        self.last = nxt - timedelta(days=1)
        self.opening = r2(D(rng.randint(1500000, 6000000)) / 100)
        lw = self.last
        while lw.weekday() >= 5:
            lw -= timedelta(days=1)
        self.lastwd = lw
        self.bank = []    # dict(date, desc, amount, key, tx)
        self.ledger = []  # dict(date, desc, ref, amount, key, tx)
        self.truth_bank = {}
        self.truth_ledger = {}
        self.diffs = {}
        self.used_refs = set()
        self.names = people(rng, 4)
        self.prior = []  # items outstanding at the end of last month: dict(id, desc, ref, amount, date, bank)
        self.bank_opening = self.opening

    def day(self, lo=1, hi=None):
        hi = hi or self.last.day - 4
        d = date(self.year, self.month, self.rng.randint(lo, hi))
        while d.weekday() >= 5:
            d -= timedelta(days=1)
            if d < self.first:
                d = self.first + timedelta(days=(7 - self.first.weekday()) % 7)
        return d

    def clear(self, d, lo=0, hi=3):
        x = d + timedelta(days=self.rng.randint(lo, hi))
        while x.weekday() >= 5:
            x += timedelta(days=1)
        return min(x, self.last) if x <= self.last else None

    def ref(self, prefix):
        while True:
            r = f"{prefix}{self.rng.randint(1000, 9899)}"
            if r not in self.used_refs:
                self.used_refs.add(r)
                return r

    def amount(self, lo, hi):
        return r2(D(self.rng.randint(lo * 100, hi * 100)) / 100)

    # transaction kinds ---------------------------------------------------
    def payment(self, amt=None, sup=None, kind='FP', d=None):
        sup = sup or self.rng.choice(SUPPLIERS)
        amt = amt if amt is not None else self.amount(60, 4800)
        d = d or self.day()
        inv = self.ref('INV-')
        c = self.clear(d, 0, 2)
        tx = dict(kind='pay')
        L = self.add_ledger(d, f"{sup[0]} — invoice {inv}", inv, -amt, tx)
        B = self.add_bank(c, f"FP OUT {sup[1]} REF {inv.replace('-', '')}", -amt, tx)
        self.link(B, L)
        return L, B

    def receipt(self, amt=None, cus=None):
        cus = cus or self.rng.choice(CUSTOMERS)
        amt = amt if amt is not None else self.amount(250, 9500)
        d = self.day()
        inv = self.ref('SI-')
        c = self.clear(d, 0, 3)
        tx = dict(kind='rec')
        L = self.add_ledger(d, f"Receipt from {cus[0]} — {inv}", inv, amt, tx)
        B = self.add_bank(c, f"FP IN {cus[1]} {inv.replace('-', '')}", amt, tx)
        self.link(B, L)

    def cheque(self, cleared=True):
        sup = self.rng.choice(SUPPLIERS)
        amt = self.amount(120, 2600)
        no = f"{self.rng.randint(100200, 100899):06d}"
        if cleared:
            d = self.day(1, self.last.day - 8)
            c = self.clear(d, 3, 6)
        else:
            d = self.day(self.last.day - 4, self.last.day)
            c = None
        tx = dict(kind='chq')
        L = self.add_ledger(d, f"Cheque {no} to {sup[0]}", f"CHQ {no}", -amt, tx)
        if c:
            B = self.add_bank(c, f"CHQ {no}", -amt, tx)
            self.link(B, L)
        else:
            self.truth_ledger[L] = 'unpresented_cheque'

    def deposit_in_transit(self):
        cus = self.rng.choice(CUSTOMERS)
        amt = self.amount(300, 6400)
        d = self.last if self.last.weekday() < 5 else self.last - timedelta(days=self.last.weekday() - 4)
        L = self.add_ledger(d, f"Cash and cheques paid in at branch (paying-in slip {self.rng.randint(4400, 4999)}) — {cus[0]}", 'PIS', amt, dict(kind='dit'))
        self.truth_ledger[L] = 'deposit_in_transit'

    def bank_only(self, reason):
        if reason == 'bank_charge':
            B = self.add_bank(self.lastwd, 'ACCOUNT FEE MONTHLY', -self.amount(8, 35), dict(kind='fee'))
        elif reason == 'interest':
            B = self.add_bank(self.lastwd, 'CREDIT INTEREST', self.amount(1, 19), dict(kind='int'))
        else:
            B = self.add_bank(self.day(), self.rng.choice(['DD HARBOURSIDE INSURANCE', 'DD LINEWAVE BROADBAND', 'DD COUNCIL BUSINESS RATES']), -self.amount(40, 640), dict(kind='dd'))
        self.truth_bank[B] = reason

    def keying_error(self):
        sup = self.rng.choice(SUPPLIERS)
        real = self.amount(1100, 8800)
        wrong = transpose(real, self.rng)
        d = self.day()
        inv = self.ref('INV-')
        c = self.clear(d, 0, 2)
        tx = dict(kind='key')
        L = self.add_ledger(d, f"{sup[0]} — invoice {inv}", inv, -wrong, tx)
        B = self.add_bank(c, f"FP OUT {sup[1]} REF {inv.replace('-', '')}", -real, tx)
        self.link(B, L)
        self.diffs[B] = ('keying_error', -real - (-wrong))

    def fx(self):
        sup = ('Lakeshore Tooling Inc (USA)', 'LAKESHORE TOOLING')
        usd = D(self.rng.choice([1850, 2400, 3150, 4200]))
        booked = D(self.rng.choice(['0.78', '0.79', '0.80']))
        actual = booked + D(self.rng.choice(['0.012', '0.017', '-0.009', '0.021']))
        lamt = r2(usd * booked)
        bamt = r2(usd * actual)
        d = self.day()
        inv = self.ref('US-')
        c = self.clear(d, 1, 3)
        tx = dict(kind='fx')
        L = self.add_ledger(d, f"{sup[0]} — invoice {inv}, USD {usd:,.2f} booked at £{booked} per $", inv, -lamt, tx)
        B = self.add_bank(c, f"INTL PAYMENT {sup[1]} REF {inv.replace('-', '')} USD {usd:,.2f} RATE {actual}", -bamt, tx)
        self.link(B, L)
        self.diffs[B] = ('fx_difference', -bamt - (-lamt))

    def split(self, bank_split=True, parts=2):
        sup = self.rng.choice(SUPPLIERS)
        half = self.amount(900, 3600)
        total = half * 2
        d = self.day(1, self.last.day - 8)
        inv = self.ref('INV-')
        tx = dict(kind='split')
        if bank_split:
            word = {2: 'two', 3: 'three'}[parts]
            sizes = [half, half] if parts == 2 else [r2(total * D('0.4')), r2(total * D('0.35'))]
            if parts == 3:
                sizes.append(total - sizes[0] - sizes[1])
            L = self.add_ledger(d, f"{sup[0]} — invoice {inv} (paid in {word} instalments)", inv, -total, tx)
            Bs = []
            for i, amt in enumerate(sizes):
                ci = self.clear(d, [0, 5, 7][i] if parts == 3 else [0, 5][i], [1, 6, 7][i] if parts == 3 else [1, 7][i])
                Bs.append(self.add_bank(ci, f"FP OUT {sup[1]} REF {inv.replace('-', '')} {i + 1}OF{parts}", -amt, tx))
            for B in Bs:
                self.truth_bank[B] = L
            self.truth_ledger[L] = Bs
        else:
            cus = self.rng.choice(CUSTOMERS)
            L1 = self.add_ledger(d, f"Receipt from {cus[0]} — {inv} part payment 1", inv, half, tx)
            L2 = self.add_ledger(d + timedelta(days=1) if (d + timedelta(days=1)).weekday() < 5 else d, f"Receipt from {cus[0]} — {inv} part payment 2", inv, half, tx)
            B = self.add_bank(self.clear(d, 1, 3), f"FP IN {cus[1]} {inv.replace('-', '')}", total, tx)
            self.truth_bank[B] = [L1, L2]
            self.truth_ledger[L1] = B
            self.truth_ledger[L2] = B

    def batch(self, k):
        """One BACS run on the statement pays several cash-book payments."""
        sups = self.rng.sample(SUPPLIERS, k)
        d = self.day(1, self.last.day - 6)
        c = self.clear(d, 1, 2)
        tx = dict(kind='batch')
        Ls = []
        for sup in sups:
            inv = self.ref('INV-')
            amt = self.amount(80, 2400)
            Ls.append(self.add_ledger(d, f"{sup[0]} — invoice {inv} (BACS run {d.day} {d.strftime('%b')})", inv, -amt, tx))
        total = sum((self.ledger[i]['amount'] for i in Ls), D('0'))
        B = self.add_bank(c, f"BACS BATCH RUN {d.day:02d}{d.strftime('%b').upper()} {k} ITEMS", total, tx)
        self.truth_bank[B] = Ls
        for L in Ls:
            self.truth_ledger[L] = B

    def returned_cheque(self):
        cus = self.rng.choice(CUSTOMERS)
        amt = self.amount(200, 2600)
        d = self.day(1, self.last.day - 9)
        no = f"{self.rng.randint(300100, 300899):06d}"
        tx = dict(kind='rtn')
        L = self.add_ledger(d, f"Cheque {no} from {cus[0]} paid in", f"CHQ {no}", amt, tx)
        c = self.clear(d, 1, 2)
        B = self.add_bank(c, f"CHQ PAID IN {no}", amt, tx)
        self.link(B, L)
        r = self.clear(c, 3, 5)
        B2 = self.add_bank(r, f"RETURNED CHQ {no} UNPAID REFER TO DRAWER", -amt, dict(kind='rtn2'))
        self.truth_bank[B2] = 'returned_cheque'

    def prior_items(self):
        """Last month's unpresented cheques and deposit: two clear this month, one is still outstanding."""
        prev = self.first - timedelta(days=1)
        while prev.weekday() >= 5:
            prev -= timedelta(days=1)
        items = []
        for i, kind in enumerate(['chq', 'chq', 'dep']):
            sup = self.rng.choice(SUPPLIERS)
            cus = self.rng.choice(CUSTOMERS)
            if kind == 'chq':
                no = f"{self.rng.randint(100100, 100199):06d}"
                items.append(dict(id=f"P{i + 1}", desc=f"Cheque {no} to {sup[0]}", ref=f"CHQ {no}", amount=-self.amount(150, 1900), date=prev, kind=kind, no=no))
            else:
                items.append(dict(id=f"P{i + 1}", desc=f"Cheques paid in at branch — {cus[0]}", ref='PIS', amount=self.amount(400, 3800), date=prev, kind=kind, no=None))
        outstanding = self.rng.choice([0, 1])
        for i, it in enumerate(items):
            if i == outstanding:
                it['bank'] = None
                continue
            d = self.day(1, 6)
            desc = f"CHQ {it['no']}" if it['kind'] == 'chq' else 'CASH/CHQS PAID IN AT BRANCH'
            it['bank'] = self.add_bank(d, desc, it['amount'], dict(kind='prior'))
            self.truth_bank[it['bank']] = it['id']
        self.prior = items
        self.bank_opening = self.opening - sum((it['amount'] for it in items), D('0'))

    def ledger_duplicate(self):
        L, B = self.payment()
        l = self.ledger[L]
        L2 = self.add_ledger(l['date'], l['desc'], l['ref'], l['amount'], dict(kind='dup'))
        self.truth_ledger[L2] = 'duplicate_entry'

    def bank_duplicate(self):
        L, B = self.payment()
        b = self.bank[B]
        d2 = b['date'] + timedelta(days=1)
        while d2.weekday() >= 5:
            d2 += timedelta(days=1)
        if d2 > self.last:
            d2 = b['date']
        B2 = self.add_bank(d2, b['desc'], b['amount'], dict(kind='bdup'))
        self.truth_bank[B2] = 'duplicate_payment'

    def reversed_charge(self):
        d = self.day(1, self.last.day - 6)
        amt = self.amount(15, 35)
        B1 = self.add_bank(d, 'UNPAID ITEM CHARGE', -amt, dict(kind='rev'))
        d2 = self.clear(d, 2, 4)
        B2 = self.add_bank(d2, 'REVERSAL OF UNPAID ITEM CHARGE', amt, dict(kind='rev'))
        self.truth_bank[B1] = 'reversed_charge'
        self.truth_bank[B2] = 'reversed_charge'

    # plumbing ------------------------------------------------------------------------
    def add_bank(self, d, desc, amt, tx):
        assert d is not None
        self.bank.append(dict(date=d, desc=desc, amount=amt, tx=tx))
        return len(self.bank) - 1

    def add_ledger(self, d, desc, ref, amt, tx):
        self.ledger.append(dict(date=d, desc=desc, ref=ref, amount=amt, tx=tx))
        return len(self.ledger) - 1

    def link(self, B, L):
        self.truth_bank[B] = L
        self.truth_ledger[L] = B

    def finish(self):
        # sort and number; remap truth
        bo = sorted(range(len(self.bank)), key=lambda i: (self.bank[i]['date'], i))
        lo = sorted(range(len(self.ledger)), key=lambda i: (self.ledger[i]['date'], i))
        bid = {old: f"B{n + 1:02d}" for n, old in enumerate(bo)}
        lid = {old: f"L{n + 1:02d}" for n, old in enumerate(lo)}

        def m(v, ids):
            if isinstance(v, list):
                return sorted(ids[x] for x in v)
            if isinstance(v, int):
                return ids[v]
            return v
        self.key_bank = {bid[i]: m(self.truth_bank[i], lid) for i in bo}
        self.key_ledger = {lid[i]: m(self.truth_ledger[i], bid) for i in lo}
        self.key_diffs = {bid[i]: {'reason': r, 'amount': float(a)} for i, (r, a) in sorted(self.diffs.items(), key=lambda kv: bid[kv[0]])}
        self.key_prior = {it['id']: (bid[it['bank']] if it['bank'] is not None else ('unpresented_cheque' if it['amount'] < 0 else 'deposit_in_transit')) for it in self.prior}
        self.B = [dict(self.bank[i], id=bid[i]) for i in bo]
        self.L = [dict(self.ledger[i], id=lid[i]) for i in lo]
        # running balances
        bal = self.bank_opening
        for b in self.B:
            bal += b['amount']
            b['balance'] = bal
            assert bal > 500, 'overdrawn'
        self.bank_close = bal
        self.ledger_close = self.opening + sum(l['amount'] for l in self.L)


def balance(rec):
    """Adjusted cash book balance == adjusted bank balance (raises otherwise)."""
    adj_cb = rec.ledger_close
    for b in rec.B:
        r = rec.key_bank[b['id']]
        if r in ('bank_charge', 'interest', 'unrecorded_direct_debit', 'duplicate_payment', 'reversed_charge', 'returned_cheque'):
            adj_cb += b['amount']
    for k, v in rec.key_diffs.items():
        adj_cb += D(str(v['amount']))
    for l in rec.L:
        if rec.key_ledger[l['id']] == 'duplicate_entry':
            adj_cb -= l['amount']
    adj_bank = rec.bank_close
    for l in rec.L:
        r = rec.key_ledger[l['id']]
        if r in ('unpresented_cheque', 'deposit_in_transit'):
            adj_bank += l['amount']
    for it in rec.prior:
        if it['bank'] is None:
            adj_bank += it['amount']
    assert adj_cb == adj_bank, (adj_cb, adj_bank)
    return adj_cb


def tokens(desc):
    return set(w for w in desc.upper().replace('—', ' ').replace(',', ' ').replace('-', '').split() if len(w) >= 4)


def refkey(l):
    return l['ref'].replace('-', '').split()[-1].upper()


def has_ref(desc):
    import re
    return bool(re.search(r'\b(?:INV|SI|US)\d{4}\b|\bCHQ \d{6}\b', desc))


def linked(b, l):
    """Same transaction by the printed fields: the cash-book reference appears on the bank line, or (when the bank
    line carries no reference) they share a counterparty word."""
    if refkey(l) in b['desc'].upper().split():
        return True
    if not has_ref(b['desc']):
        return bool(tokens(b['desc']) & tokens(l['desc']))
    return False


def check_unique(rec):
    """Every 1:1 match is the only ledger entry the matching rule allows (equal or explained amount, date window,
    shared reference or counterparty word), and no ordinary bank line has a second candidate."""
    for b in rec.B:
        v = rec.key_bank[b['id']]
        if not (isinstance(v, str) and v.startswith('L')) or isinstance(rec.key_ledger[v], list):
            continue
        cands = []
        for l in rec.L:
            if not (l['date'] <= b['date'] <= l['date'] + timedelta(days=7)):
                continue
            same = l['amount'] == b['amount']
            shared = linked(b, l)
            if same and shared:
                cands.append(l['id'])
            elif b['id'] in rec.key_diffs and shared:
                cands.append(l['id'])
        # the duplicate cash-book entry is the same payment; the rule picks the earlier one
        cands = [c for c in cands if rec.key_ledger[c] != 'duplicate_entry']
        assert cands == [v], (b['id'], v, cands)


def careless(rec):
    """Exact amounts only, nearest date first; leftovers called timing differences / bank charges."""
    used = set()
    kb, kl = {}, {}
    for b in rec.B:
        best = None
        for l in rec.L:
            if l['id'] in used or l['amount'] != b['amount']:
                continue
            if abs((b['date'] - l['date']).days) <= 7:
                best = l
                break
        if best:
            used.add(best['id'])
            kb[b['id']] = best['id']
            kl[best['id']] = b['id']
        else:
            kb[b['id']] = 'bank_charge' if b['amount'] < 0 else 'interest'
    for l in rec.L:
        if l['id'] not in kl:
            kl[l['id']] = 'unpresented_cheque' if l['amount'] < 0 else 'deposit_in_transit'
    timing = sum((l['amount'] for l in rec.L if not str(kl[l['id']]).startswith('B')), D('0'))
    out = {'bank': kb, 'ledger': kl, 'differences': {}, 'adjusted_balance': float(rec.bank_close + timing + sum((it['amount'] for it in rec.prior), D('0')))}
    if rec.prior:
        out['prior'] = {it['id']: ('unpresented_cheque' if it['amount'] < 0 else 'deposit_in_transit') for it in rec.prior}
    return out


def slip(rec, exp):
    """A strong bookkeeper's two slips: the returned cheque booked as a bank charge, and the FX difference's sign flipped."""
    out = json.loads(json.dumps(exp))
    for k, v in out['bank'].items():
        if v == 'returned_cheque':
            out['bank'][k] = 'bank_charge'
    for k, v in out['differences'].items():
        if v['reason'] == 'fx_difference':
            v['amount'] = -v['amount']
    return out


def build(seed, tier):
    rec = Rec(seed, tier)
    rng = rec.rng
    n_pay = {'easy': 6, 'medium': 22, 'hard': 60}[tier]
    n_rec = {'easy': 4, 'medium': 10, 'hard': 28}[tier]
    rec.opening += {'easy': 0, 'medium': 25000, 'hard': 140000}[tier]
    rec.bank_opening = rec.opening
    if tier == 'hard':
        rec.prior_items()
    for _ in range(n_pay):
        rec.payment()
    for _ in range(n_rec):
        rec.receipt()
    rec.cheque(True)
    rec.cheque(False)
    rec.deposit_in_transit()
    rec.bank_only('bank_charge')
    if tier == 'easy':
        rec.bank_only('interest')
        rec.keying_error()
    if tier != 'easy':
        rec.bank_only('unrecorded_direct_debit')
        rec.keying_error()
        rec.ledger_duplicate()
        rec.split(bank_split=rng.random() < 0.5)
        rec.reversed_charge()
    if tier == 'medium':
        rec.batch(3)
        rec.returned_cheque()
        rec.fx()
        rec.cheque(True)
        rec.bank_only('bank_charge')
    if tier == 'hard':
        rec.fx()
        rec.fx()
        rec.bank_duplicate()
        rec.bank_duplicate()
        rec.ledger_duplicate()
        rec.keying_error()
        rec.keying_error()
        rec.cheque(False)
        rec.cheque(True)
        rec.cheque(True)
        rec.bank_only('interest')
        rec.bank_only('unrecorded_direct_debit')
        rec.bank_only('bank_charge')
        rec.split(bank_split=True, parts=3)
        rec.split(bank_split=True, parts=2)
        rec.split(bank_split=False)
        rec.split(bank_split=False)
        rec.batch(4)
        rec.batch(5)
        rec.batch(3)
        rec.batch(4)
        rec.fx()
        rec.keying_error()
        rec.keying_error()
        rec.bank_duplicate()
        rec.ledger_duplicate()
        rec.returned_cheque()
        rec.returned_cheque()
        rec.reversed_charge()
        rec.deposit_in_transit()
        # a look-alike: same supplier and same amount as an earlier payment, different invoice, later in the month
        L0 = rec.ledger[0]
        sup = next(s for s in SUPPLIERS if s[0] in L0['desc'])
        rec.payment(amt=-L0['amount'], sup=sup, d=min(L0['date'] + timedelta(days=9), rec.last - timedelta(days=4)))
    rec.finish()
    check_unique(rec)
    bal = balance(rec)
    return rec, bal


def fmt(x):
    return f"{abs(x):,.2f}"


def prompt(rec):
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
    rules = f"""How to reconcile (apply exactly):
1. Match a bank line with cash-book entries for the same transaction: same counterparty and reference, the bank date on or up to 7 days after the cash-book date, and the same amount. A payment or receipt can be split: one cash-book entry may match several bank lines (instalments sharing its reference), or several cash-book entries one bank line (two part-payments sharing a reference, or the payments of one BACS run, which the cash book marks with the run's date), when they add up exactly.
2. A match whose amounts differ is still a match when it is the same transaction and the difference is explained: "keying_error" (the cash book was keyed wrongly, e.g. two digits swapped) or "fx_difference" (a foreign-currency payment cost a different amount in pounds than the cash book's booked rate). The bank figure is right in both cases. List each in "differences" with amount = bank amount minus cash-book amount (money in positive, money out negative).
3. Bank lines with no cash-book entry get a reason: "bank_charge" (fees the bank charged), "interest", "unrecorded_direct_debit" (a direct debit nobody entered in the cash book), "duplicate_payment" (the bank paid the same payment twice: the later line is the duplicate), "reversed_charge" (a charge and its reversal on the statement: give both lines this reason), or "returned_cheque" (a cheque paid in that the bank has returned unpaid; the paying-in itself is matched normally).
4. Cash-book entries with no bank line get a reason: "unpresented_cheque" (a cheque not yet through the bank), "deposit_in_transit" (money paid in but not yet on the statement), or "duplicate_entry" (the same payment entered twice in the cash book: the later entry is the duplicate; the earlier one is matched).
5. The adjusted balance is the cash-book closing balance corrected for every bank-only item, every difference and every duplicate entry. (It must equal the statement closing balance plus deposits in transit minus unpresented cheques, including any from last month still outstanding.)"""
    if rec.prior:
        rules += """
6. Items outstanding at the end of last month (P1, P2, …) are not in this month's cash book. A bank line that clears one gets that item's id (e.g. "P1") as its value; in "prior", give each item the bank line that cleared it, or "unpresented_cheque" / "deposit_in_transit" if it is still outstanding."""
    schema = ('{"bank": {"B01": "L02", "B02": "bank_charge", "B05": ["L06", "L07"], ...}, '
              '"ledger": {"L01": "B03", "L02": "B01", "L06": "B05", "L09": "unpresented_cheque", ...}, '
              '"differences": {"B07": {"reason": "keying_error", "amount": -270.00}}, '
              + ('"prior": {"P1": "B04", "P2": "unpresented_cheque", ...}, ' if rec.prior else '') +
              '"adjusted_balance": 12345.67}')
    if rec.prior:
        pl = '\n'.join(f"  {it['id']} {uk(it['date'])} | {it['ref']} | {it['desc']} | " + (f"payment {fmt(it['amount'])}" if it['amount'] < 0 else f"receipt {fmt(it['amount'])}") for it in rec.prior)
        opening = (f"Last month's reconciliation left these cash-book items not yet through the bank:\n{pl}\n"
                   f"So this month the cash book opened at £{fmt(rec.opening)} and the statement at £{fmt(rec.bank_opening)}.")
    else:
        opening = f"The account was fully reconciled at the end of last month, so the statement and the cash book both opened the month at £{fmt(rec.opening)}."
    return (f"Can you do the {month} bank reconciliation for {rec.company}? {opening}\n\n{rules}\n\n"
            f"Bank statement, {month} (Business Current Account):\n" + '\n'.join(tb) + f"\nClosing balance £{fmt(rec.bank_close)}\n\n"
            f"Cash book, {month}:\n" + '\n'.join(tl) + f"\nCash-book closing balance £{fmt(rec.ledger_close)}\n\n"
            "Answer with one JSON object in exactly this shape:\n" + schema + "\n"
            "- bank: every statement line, with the cash-book entry it matches (a list for a split or a BACS run) or its reason from rule 3" + (" or last month's item id" if rec.prior else '') + ".\n"
            "- ledger: every cash-book entry, with the bank line it matches (a list for a split) or its reason from rule 4.\n"
            "- differences: one entry per matched bank line whose amount differs from the cash book (rule 2); leave it empty ({}) if there are none.\n"
            "- adjusted_balance: in pounds, to the penny.\n"
            f"Marking: each bank line, each cash-book entry, each difference's reason and amount and the adjusted balance is a field. Routine fields (a straightforward match) are worth 1 point; "
            f"fields that need extra care (a split, a duplicate, a reversal, a keying or FX difference, a timing item, the balance) are worth {TRAP_WEIGHT}. Every difference you list that is not one costs two fields at {TRAP_WEIGHT} points each. "
            "Reply with the JSON object only (a ```json code block is fine); key order, spacing and letter case do not matter, and amounts may have a £ sign. "
            "Give one value per field: a field with two answers is marked wrong, and if you give more than one JSON answer only the last one counts.")


CASES = [('r01', 801, 'easy'), ('r02', 802, 'easy'), ('r03', 803, 'easy'),
         ('r04', 904, 'medium'), ('r05', 905, 'medium'), ('r06', 906, 'medium'),
         ('r07', 1007, 'hard'), ('r08', 1008, 'hard'), ('r09', 1009, 'hard')]


def make_cases():
    out = []
    for cid, seed, tier in CASES:
        for k in range(50):
            try:
                rec, bal = build(seed * 100 + k, tier)
                break
            except AssertionError:
                continue
        else:
            raise AssertionError(cid)
        exp = {'bank': rec.key_bank, 'ledger': rec.key_ledger, 'differences': rec.key_diffs, 'adjusted_balance': float(bal)}
        if rec.prior:
            exp['prior'] = rec.key_prior
        out.append(dict(id=cid, tier=tier, prompt=prompt(rec), expected=exp, careless=careless(rec), slip=slip(rec, exp), rec=rec, attempt=k))
    return out


if __name__ == '__main__':
    import json
    cs = make_cases()
    for c in cs:
        print(c['id'], c['tier'], 'attempt', c['attempt'], len(c['rec'].B), 'bank', len(c['rec'].L), 'ledger', len(c['prompt']), 'chars')
    print(cs[6]['prompt'])
    print(json.dumps(cs[6]['expected']))
