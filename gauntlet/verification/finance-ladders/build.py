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


def expenses_test():
    rows, scripted = [], {}
    for c in EX.make_cases():
        exp = jsonable(c['expected'])
        sc = {'type': 'json', 'lenient': True, 'numberTolerance': 1e-9, 'sets': {'non_compliant': 2}}
        sc['weights'] = trap_weights(exp, jsonable(c['careless']), EX.TRAP_WEIGHT, ['non_compliant'])
        notes = (f"[level {c['level']}] {c['n']} claim lines from {c['emps']} employees in {c['claims']} claims. Rules in play: {len(c['fams'])} families ({', '.join(c['fams'])}); "
                 f"{len(exp['non_compliant'])} lines paid less than claimed. Key = audit() of verification/realworld-work/expenses.py over the structured lines with exact decimals (half-up to the penny); "
                 "every rule family the level does not print changes nothing when switched off, every exact copy is a planted duplicate. "
                 f"Fields where a trap sits are worth {EX.TRAP_WEIGHT} points, routine fields 1. {LADDER_NOTE}")
        rows.append(dict(id=c['id'], prompt=c['prompt'], expected=exp, scorer=sc, notes=notes))
        scripted[c['id']] = {'careless': jsonable(c['careless']), 'slip': jsonable(c['slip']), 'slip_kind': c['slip_kind']}
    meta = dict(
        id='work.expenses-ladder', name='Expense Policy Check: Ladder',
        description=('Ten frozen levels of the same expenses audit: claim lines checked against a written policy, with every line paid less than claimed (the first rule it breaks and the amount to pay), '
                     'the total per employee, the batch total and the reclaimable VAT, to the penny. Each level is strictly bigger and stacks more rules of the same policy: level 1 has 8 lines and two rules '
                     '(receipts from £25.00, hotel limits per night); level 2 adds meal limits shared across a day per city and first-class rail; level 3 alcohol, the 60-day limit with late-submission approvals and duplicates; '
                     'level 4 client entertainment and its approvals, mileage at 45p then 25p with passengers, and euro and dollar conversion; level 5 is the full policy (with the hotel-night meal uplift) at the size of the hardest '
                     'Expense Policy Check case; levels 6 to 10 grow to over 900 lines from 27 employees with more claims per person, late lines claimed again and foreign taxis either side of £25.00 after conversion. '
                     'A level is passed at 90% of its points; the headline is the highest level passed reliably.'),
        tags=['work', 'finance', 'expenses', 'policy', 'arithmetic', 'real-world', 'ladder'],
        hook='Ten rungs of expense claims, from 8 lines to over 900. How far up can it pay every person exactly by the policy?',
        scorer={'type': 'json', 'lenient': True, 'numberTolerance': 1e-9, 'sets': {'non_compliant': 2}},
    )
    return meta, rows, scripted


def reconciliation_test():
    rows, scripted = [], {}
    for c in BR.make_cases():
        exp = jsonable(c['expected'])
        sc = {'type': 'json', 'lenient': True, 'numberTolerance': 1e-9, 'unorderedArrays': True, 'sets': {'differences': 2}}
        sc['weights'] = trap_weights(exp, jsonable(c['careless']), BR.TRAP_WEIGHT, ['differences'])
        notes = (f"[level {c['level']}] {c['n_bank']} statement lines and {c['n_ledger']} cash-book entries. Rules in play: the basics (one-to-one matches, timing items, fees, interest) + {len(c['fams'])} families ({', '.join(c['fams'])}); "
                 f"{len(exp['differences'])} explained differences. Key = the generator's own transaction list (verification/realworld-work/bankrec.py); check_unique() re-derives every 1:1 match from the printed fields; "
                 f"balance() proves adjusted cash book = adjusted bank = {exp['adjusted_balance']}; every reason in the key belongs to a printed rule. "
                 f"Fields where a trap sits are worth {BR.TRAP_WEIGHT} points, routine fields 1. {LADDER_NOTE}")
        rows.append(dict(id=c['id'], prompt=c['prompt'], expected=exp, scorer=sc, notes=notes))
        scripted[c['id']] = {'careless': jsonable(c['careless']), 'slip': jsonable(c['slip']), 'slip_kind': c['slip_kind']}
    meta = dict(
        id='work.reconciliation-ladder', name='Reconcile the Account: Ladder',
        description=('Ten frozen levels of the same month-end bank reconciliation: every statement line and cash-book entry matched or given a reason from a fixed list, every explained difference with its amount, '
                     'and the adjusted balance. Each level is strictly bigger and stacks more rules of the same list: level 1 has 14 lines a side (one-to-one matches, timing items, fees and interest, a payment keyed '
                     'with two digits swapped); level 2 adds direct debits nobody entered and payments made or entered twice; level 3 instalments, part-payments and a reversed charge; level 4 BACS runs, a returned cheque '
                     "and dollar payments at a different rate; level 5 is the full rules (with last month's outstanding items) at the size of the hardest Reconcile the Account case; levels 6 to 10 grow to about 700 lines a "
                     'side with more of every trap, look-alike payments and an instalment the bank paid twice. A level is passed at 90% of its points; the headline is the highest level passed reliably.'),
        tags=['work', 'finance', 'bookkeeping', 'reconciliation', 'matching', 'real-world', 'ladder'],
        hook='Ten rungs of bank reconciliation, from 28 lines to 1,400. How far up can it match every line and land the balance to the penny?',
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
