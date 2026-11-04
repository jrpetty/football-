"""Builds the two Python finance ladders: tests/work/{expenses-ladder,reconciliation-ladder}.json and
verification/finance-ladders/scripted-work.json (scripted careless and slip answers per level).

Run from this folder:  python3 build.py            (write the files)
                       python3 build.py --check    (exit 1 unless every file is byte-identical to a fresh build)
                       python3 build.py --out DIR  (write DIR/work/*.json and DIR/scripted-work.json instead)
Fixed seeds, no clock, no randomness outside random.Random(seed). (The invoice and payroll ladders: node build.mjs.)
"""
import json
import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..'))
import expenses_ladder as EX  # noqa: E402
import bankrec_ladder as BR  # noqa: E402
from common import tok  # noqa: E402

TESTS = os.path.join(HERE, '..', '..', 'tests')
LADDER_NOTE = ('Ladder: ten frozen levels (L01 easiest, L10 hardest), each strictly bigger than the one below and stacking more rule families of the same policy. '
               'A level is passed at 90% of its points; the headline is the highest level passed reliably.')


def jsonable(x):
    return json.loads(json.dumps(x))


# The trap weights of the Real-World Work pack (verification/realworld-work/build.py), unchanged: a field the
# careless reading gets wrong is worth W, a routine field 1, and an invented set item costs its fields at W.
MISSING = object()


def leaves(x, keys=()):
    if isinstance(x, dict) and x:
        for k, v in x.items():
            yield from leaves(v, keys + (k,))
    else:
        yield keys, x


def at(x, keys):
    for k in keys:
        if not isinstance(x, dict) or k not in x:
            return MISSING
        x = x[k]
    return x


def norm(v):
    return sorted(json.dumps(i) for i in v) if isinstance(v, list) else v


def trap_weights(expected, careless, W, sets):
    w = {}
    for keys, v in leaves(expected):
        path = '.'.join(keys)
        if norm(at(careless, keys)) != norm(v):
            w[path] = W
        elif keys[0] in sets:
            w[path] = 1
    for sp in sets:
        w[sp] = W
    return w


def out_estimate(expected):
    return min(56000, 4000 + 8 * tok(json.dumps(expected)))


def write(meta, cases, out_dir):
    obj = {
        'kind': 'prompt', 'id': meta['id'], 'version': '1.0.0', 'name': meta['name'], 'category': 'work', 'description': meta['description'],
        'difficulty': 'extreme', 'tags': meta['tags'], 'hook': meta['hook'], 'maxOutputTokens': 64000, 'timeLimitSec': 1800, 'maxRetries': 1,
        'estimate': {'inputTokens': int(sum(tok(c['prompt']) for c in cases) / len(cases)), 'outputTokens': int(round(sum(out_estimate(c['expected']) for c in cases) / len(cases), -2))},
        'author': 'Gauntlet Core', 'createdAt': '2026-11-04', 'scorer': meta['scorer'], 'cases': cases,
    }
    placeholders = {}
    for i, c in enumerate(obj['cases']):
        compact = json.dumps(c['expected'], ensure_ascii=False, separators=(',', ':'))
        if len(compact) > 4000:
            key = f'@@COMPACT_{i}@@'
            placeholders[key] = compact
            c['expected'] = key
    text = json.dumps(obj, indent=2, ensure_ascii=False)
    for key, compact in placeholders.items():
        text = text.replace(json.dumps(key), compact, 1)
    json.loads(text)
    return os.path.join(out_dir, 'work', meta['id'].split('.', 1)[1] + '.json'), text + '\n'


CHAIN = re.compile('^(paid_out|advances_left|miles_to_date|adjusted_balance)[.]')


def chain_weights(exp, careless, W, sets, share):
    """Trap weights, plus the chained figures (payouts, advances, miles, month balances) weighted so that together they
    are `share` of the points: a broken chain costs well over the 10% a rung can lose and still pass."""
    w = trap_weights(exp, careless, W, sets)
    paths = ['.'.join(k) for k, _ in leaves(exp)]
    chain = [p for p in paths if CHAIN.match(p)]
    rest = sum(w.get(p, 1) for p in paths if not CHAIN.match(p))
    cw = max(W, round(share / (1 - share) * rest / max(1, len(chain))))
    for p in chain:
        w[p] = cw
    return w, cw


def expenses_test():
    rows, scripted = [], {}
    for c in EX.make_cases():
        exp = jsonable(c['expected'])
        sc = {'type': 'json', 'lenient': True, 'numberTolerance': 1e-9, 'sets': {'non_compliant': 2}}
        sc['weights'], cw = chain_weights(exp, jsonable(c['careless']), EX.TRAP_WEIGHT, ['non_compliant'], 0.3)
        notes = (f"[level {c['level']}] {c['months']} month{'s' if c['months'] > 1 else ''}, {c['n']} claim lines from {c['emps']} employees in {c['claims']} claims. Rules in play: {len(c['fams'])} families ({', '.join(c['fams'])}); "
                 f"{len(exp['non_compliant'])} lines paid less than claimed. Key = audit() of verification/realworld-work/expenses.py over every month's lines in order, exact decimals; "
                 "second derivations: month-by-month audits over the months so far give every line the same result, the advance set-off step by step equals its closed form, miles summed directly. "
                 f"Trap fields {EX.TRAP_WEIGHT} points, routine 1, each payout / advance / miles figure {cw}. {LADDER_NOTE}")
        rows.append(dict(id=c['id'], prompt=c['make_prompt'](cw), expected=exp, scorer=sc, notes=notes))
        scripted[c['id']] = {'careless': jsonable(c['careless']), 'slip': jsonable(c['slip']), 'slip_kind': c['slip_kind'], 'chain': jsonable(c['chain'])}
    meta = dict(
        id='work.expenses-ladder', name='Expense Policy Check: Ladder',
        description=('Ten frozen levels of expense auditing for one team, against a written policy with exceptions to the exceptions: every line paid less than claimed (rule and amount), what to pay each person, and the reclaimable VAT. '
                     'Levels 1-2 are one monthly batch with two and four rules; from level 3 a level is two to six consecutive monthly batches for the same people, and the months depend on each other: mileage at 45p '
                     'then 25p after 10,000 miles is counted through the months and restarts on 6 April, expenses come back as duplicates in later months, late lines need an approval for their own claim, meal limits are '
                     "shared with earlier lines of the same day, and (from level 5) cash advances are set off month by month and carried on. The answer carries the state: each month's payout per person, the advances left "
                     'and the miles to date, so one early slip moves every later month. A level is passed at 90% of its points; the headline is the highest level passed reliably.'),
        tags=['work', 'finance', 'expenses', 'policy', 'arithmetic', 'real-world', 'ladder'],
        hook='Six months of expense claims for one team, every month depending on the last. How far up can it pay everyone right?',
        scorer={'type': 'json', 'lenient': True, 'numberTolerance': 1e-9, 'sets': {'non_compliant': 2}},
    )
    return meta, rows, scripted


def reconciliation_test():
    rows, scripted = [], {}
    for c in BR.make_cases():
        exp = jsonable(c['expected'])
        sc = {'type': 'json', 'lenient': True, 'numberTolerance': 1e-9, 'unorderedArrays': True, 'sets': {'differences': 2}}
        sc['weights'], bw = chain_weights(exp, jsonable(c['careless']), BR.TRAP_WEIGHT, ['differences'], 0.25)
        notes = (f"[level {c['level']}] {c['months']} month{'s' if c['months'] > 1 else ''}, {c['n_bank']} statement lines and {c['n_ledger']} cash-book entries, {c['cross']} entries cleared in a later month. "
                 f"Rules in play: the basics (one-to-one matches, timing items, fees, interest) + {len(c['fams'])} families ({', '.join(c['fams'])}); {len(exp['differences'])} explained differences. "
                 "Key = the generator's own transaction list (Rec of verification/realworld-work/bankrec.py over several months); second derivations: every month's adjusted balance computed from the cash-book side and "
                 "from the bank side agrees, and every one-to-one match is re-derived from the printed fields alone and is the only candidate. "
                 f"Trap fields {BR.TRAP_WEIGHT} points, routine 1, each month's balance {bw}. {LADDER_NOTE}")
        rows.append(dict(id=c['id'], prompt=BR.prompt(c['rec'], set(c['fams']), bw), expected=exp, scorer=sc, notes=notes))
        scripted[c['id']] = {'careless': jsonable(c['careless']), 'slip': jsonable(c['slip']), 'slip_kind': c['slip_kind'], 'chain': jsonable(c['chain'])}
    meta = dict(
        id='work.reconciliation-ladder', name='Reconcile the Account: Ladder',
        description=('Ten frozen levels of bank reconciliation for one account: every statement line and cash-book entry matched or given a reason, every explained difference, and the adjusted balance. '
                     'Levels 1-2 are one month; from level 3 a level is two to six consecutive months nobody has reconciled, and the months depend on each other: cheques, paying-ins and keyed-in payments clear '
                     'in a later month, instalment plans run across months, charges are reversed and cheques returned the month after, and because the cash book was never corrected every month\'s balance must '
                     "allow for every correction found so far and every item still outstanding at that month's end. The month balances are a quarter of the points, so one early slip carries into every later month. "
                     'A level is passed at 90% of its points; the headline is the highest level passed reliably.'),
        tags=['work', 'finance', 'bookkeeping', 'reconciliation', 'matching', 'real-world', 'ladder'],
        hook='Six months of bank statements nobody reconciled, each month resting on the last. How far up can it land every balance?',
        scorer={'type': 'json', 'lenient': True, 'numberTolerance': 1e-9, 'unorderedArrays': True, 'sets': {'differences': 2}},
    )
    return meta, rows, scripted


def build_all(out_dir):
    files, scripted = [], {}
    for fn in (expenses_test, reconciliation_test):
        meta, rows, sc = fn()
        files.append(write(meta, rows, out_dir))
        scripted[meta['id']] = sc
    files.append((os.path.join(out_dir if out_dir != TESTS else HERE, 'scripted-work.json'), json.dumps(scripted, indent=1, ensure_ascii=False, sort_keys=True) + '\n'))
    return files


if __name__ == '__main__':
    check = '--check' in sys.argv
    out = sys.argv[sys.argv.index('--out') + 1] if '--out' in sys.argv else TESTS
    ok = True
    for path, text in build_all(out):
        if check:
            same = os.path.exists(path) and open(path, encoding='utf-8').read() == text
            ok &= same
            print(('same    ' if same else 'DIFFERS ') + os.path.relpath(path, os.path.join(HERE, '..', '..')))
        else:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, 'w', encoding='utf-8') as f:
                f.write(text)
            print(f'wrote   {os.path.relpath(path, os.path.join(HERE, "..", ".."))} ({len(text) // 1024} KB)')
    if not ok:
        sys.exit(1)
