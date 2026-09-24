"""Independent checker for reasoning.deduction-grid-extreme.

Reads ONLY the prompt text of each case: the category legend (values + clue wording), the definitions and the clues.
Every clue is parsed back from English, encoded to CNF from scratch (pysat, no code shared with the CP-SAT
generator) and ALL models are enumerated (blocking clauses). A case passes when there is exactly one model and it
matches the answer key. Usage: python3 verify_sat.py [cases.json | ../../tests/reasoning/deduction-grid-extreme.json]
"""
import json, re, sys, itertools
from pysat.formula import IDPool
from pysat.card import CardEnc, EncType
from pysat.solvers import Cadical153


def parse_prompt(text):
    n = int(re.search(r'numbered 1 to (\d+) from left to right', text).group(1))
    place = re.search(r'\n- (\w+)s are numbered 1 to', text).group(1).lower()
    legend = text.split('Categories (with how the clues refer to them):\n')[1].split('\n\n')[0].split('\n')
    cats, S, P, N = [], {}, {}, {}
    for line in legend:
        m = re.match(r'^- (.+?): (.+?)\. Clue wording, e\.g\. for (.+?): "(.+?)" \(the person with this value\); "\.\.\. (.+?)"; "\.\.\. (.+?)"\.$', line)
        assert m, line
        cat, vals, ex, s, p, ng = m.groups()
        vals = vals.split(', ')
        assert len(vals) == n and ex in vals
        cats.append(cat)
        ts, tp, tn = (x.replace(ex, '{v}') for x in (s, p, ng))
        for i, v in enumerate(vals):
            item = (cat, i)
            for tbl, t in ((S, ts), (P, tp), (N, tn)):
                key = t.replace('{v}', v)
                assert key not in tbl, ('ambiguous phrase', key)
                tbl[key] = item
    vals = {}
    for line in legend:
        m = re.match(r'^- (.+?): (.+?)\. Clue wording', line)
        vals[m.group(1)] = m.group(2).split(', ')
    clues = [re.sub(r'^\d+\. ', '', l) for l in text.split('Clues:\n')[1].split('\n\n')[0].split('\n')]
    q = re.search(r'Question: What are the (.+?) and the (.+?) of the people in', text)
    ask = []
    for name in q.groups():
        ask.append([c for c in cats if c.lower() == name][0])
    return dict(n=n, place=place, cats=cats, S=S, P=P, N=N, vals=vals, clues=clues, ask=ask)


def subj(pz, s):
    for cand in (s, s[0].lower() + s[1:], s[0].upper() + s[1:]):
        if cand in pz['S']:
            return pz['S'][cand]
    raise ValueError('unknown subject: ' + s)


def parse_clause(pz, t):
    place = pz['place']
    m = re.match(rf'^[Tt]he {place} numbers of (.+) and (.+) differ by exactly (\d+)$', t)
    if m: return ('dist', subj(pz, m.group(1)), subj(pz, m.group(2)), int(m.group(3)))
    m = re.match(rf'^(.+) is in {place} (\d+)$', t)
    if m: return ('at', subj(pz, m.group(1)), int(m.group(2)) - 1)
    m = re.match(r'^(.+) is next to (.+)$', t)
    if m: return ('next', subj(pz, m.group(1)), subj(pz, m.group(2)))
    m = re.match(r'^(.+) is somewhere to the left of (.+)$', t)
    if m: return ('left', subj(pz, m.group(1)), subj(pz, m.group(2)))
    m = re.match(r'^(.+) is immediately to the left of (.+)$', t)
    if m: return ('imm', subj(pz, m.group(1)), subj(pz, m.group(2)))
    hits = []
    for pred, item in pz['P'].items():
        if t.endswith(' ' + pred):
            head = t[: -len(pred) - 1]
            try:
                hits.append(('same', subj(pz, head), item))
            except ValueError:
                pass
    assert len(hits) == 1, ('clause parse', t, hits)
    return hits[0]


def parse_clue(pz, c):
    place = pz['place']
    assert c.endswith('.'), c
    body = c[:-1]
    m = re.match(r'^From left to right, (.+), (.+) and (.+) are in that order$', body)
    if m: return ('chain',) + tuple(subj(pz, g) for g in m.groups())
    m = re.match(r'^Exactly one of these two statements is true: \(a\) (.+); \(b\) (.+)$', body)
    if m: return ('xor', parse_clause(pz, m.group(1)), parse_clause(pz, m.group(2)))
    m = re.match(r'^Exactly (one|two) of these three statements (?:is|are) true: \(a\) (.+); \(b\) (.+); \(c\) (.+)$', body)
    if m: return ('exactly', {'one': 1, 'two': 2}[m.group(1)], tuple(parse_clause(pz, g) for g in m.groups()[1:]))
    m = re.match(r'^If (.+), then (.+)$', body)
    if m: return ('if', parse_clause(pz, m.group(1)), parse_clause(pz, m.group(2)))
    m = re.match(r'^Of (.+) and (.+) \(two different \w+\), one (.+) and the other (.+)$', body)
    if m: return ('ofxy', subj(pz, m.group(1)), subj(pz, m.group(2)), pz['P'][m.group(3)], pz['P'][m.group(4)])
    m = re.match(r'^(.+) is somewhere between (.+) and (.+)$', body)
    if m: return ('between', subj(pz, m.group(2)), subj(pz, m.group(1)), subj(pz, m.group(3)))
    m = re.match(rf'^(.+) is not in {place} ([\d, or]+)$', body)
    if m: return ('notat', subj(pz, m.group(1)), tuple(int(x) - 1 for x in re.findall(r'\d+', m.group(2))))
    m = re.match(rf'^(.+) is in one of the two end {place}s$', body)
    if m: return ('end', subj(pz, m.group(1)))
    m = re.match(r'^(.+) is not next to (.+)$', body)
    if m: return ('notnext', subj(pz, m.group(1)), subj(pz, m.group(2)))
    m = re.match(r'^(.+) is exactly (\d+) years older than (.+)$', body)
    if m: return ('numdiff', subj(pz, m.group(1)), subj(pz, m.group(3)), int(m.group(2)))
    m = re.match(r'^(.+) is older than (.+)$', body)
    if m: return ('numgt', subj(pz, m.group(1)), subj(pz, m.group(2)))
    for neg, item in pz['N'].items():
        if body.endswith(' ' + neg):
            try:
                return ('notsame', subj(pz, body[: -len(neg) - 1]), item)
            except ValueError:
                pass
    return ('atom', parse_clause(pz, body))


def solve(pz, limit=3):
    n, cats = pz['n'], pz['cats']
    pool = IDPool()
    X = lambda it, p: pool.id(('x', it, p))
    cnf = []
    items = [(c, v) for c in cats for v in range(n)]
    for it in items:
        cnf += CardEnc.equals([X(it, p) for p in range(n)], 1, vpool=pool, encoding=EncType.pairwise).clauses
    for c in cats:
        for p in range(n):
            cnf += CardEnc.equals([X((c, v), p) for v in range(n)], 1, vpool=pool, encoding=EncType.pairwise).clauses

    def or_of_ands(terms):
        t = pool.id(('aux', len(cnf), id(terms), pool.top))
        ands = []
        for lits in terms:
            a = pool.id(('and', t, tuple(lits)))
            for l in lits:
                cnf.append([-a, l])
            cnf.append([a] + [-l for l in lits])
            ands.append(a)
        cnf.append([-t] + ands)
        for a in ands:
            cnf.append([-a, t])
        return t

    pairs = [(p, q) for p in range(n) for q in range(n)]

    def atom(a):
        k = a[0]
        if k == 'at': return X(a[1], a[2])
        if k == 'same': return or_of_ands([(X(a[1], p), X(a[2], p)) for p in range(n)])
        if k == 'next': return or_of_ands([(X(a[1], p), X(a[2], q)) for p, q in pairs if abs(p - q) == 1])
        if k == 'left': return or_of_ands([(X(a[1], p), X(a[2], q)) for p, q in pairs if p < q])
        if k == 'imm': return or_of_ands([(X(a[1], p), X(a[2], q)) for p, q in pairs if q == p + 1])
        if k == 'dist': return or_of_ands([(X(a[1], p), X(a[2], q)) for p, q in pairs if abs(p - q) == a[3]])
        raise ValueError(a)

    ages = [int(v) for v in pz['vals'].get('Ages', [])]
    for c in pz['parsed']:
        k = c[0]
        if k == 'atom': cnf.append([atom(c[1])])
        elif k == 'notsame': cnf.append([-atom(('same', c[1], c[2]))])
        elif k == 'notat': cnf += [[-X(c[1], p)] for p in c[2]]
        elif k == 'notnext':
            for p, q in pairs:
                if abs(p - q) <= 1: cnf.append([-X(c[1], p), -X(c[2], q)])
        elif k == 'end': cnf.append([X(c[1], 0), X(c[1], n - 1)])
        elif k == 'between':
            cnf.append([or_of_ands([(X(c[1], p), X(c[2], q), X(c[3], r)) for p, q, r in itertools.product(range(n), repeat=3) if min(p, r) < q < max(p, r)])])
        elif k == 'chain':
            cnf.append([or_of_ands([(X(c[1], p), X(c[2], q), X(c[3], r)) for p, q, r in itertools.product(range(n), repeat=3) if p < q < r])])
        elif k == 'xor':
            a, b = atom(c[1]), atom(c[2]); cnf += [[a, b], [-a, -b]]
        elif k == 'exactly':
            cnf += CardEnc.equals([atom(a) for a in c[2]], c[1], vpool=pool, encoding=EncType.seqcounter).clauses
        elif k == 'if': cnf.append([-atom(c[1]), atom(c[2])])
        elif k == 'ofxy':
            x, y, a, b = c[1:]
            cnf.append([or_of_ands([(X(x, p), X(y, q), X(a, p), X(b, q)) for p, q in pairs if p != q] +
                                   [(X(x, p), X(y, q), X(b, p), X(a, q)) for p, q in pairs if p != q])])
        elif k in ('numgt', 'numdiff'):
            terms = []
            for p, q in pairs:
                if p == q: continue
                for va, vb in itertools.permutations(range(n), 2):
                    ok = ages[va] > ages[vb] if k == 'numgt' else ages[va] - ages[vb] == c[3]
                    if ok: terms.append((X(c[1], p), X(c[2], q), X(('Ages', va), p), X(('Ages', vb), q)))
            cnf.append([or_of_ands(terms)])
        else:
            raise ValueError(c)
    sols = []
    with Cadical153(bootstrap_with=cnf) as s:
        while len(sols) < limit and s.solve():
            model = set(l for l in s.get_model() if l > 0)
            sol = {it: [p for p in range(n) if X(it, p) in model][0] for it in items}
            sols.append(sol)
            s.add_clause([-X(it, sol[it]) for it in items])
    return sols


def check_case(case):
    pz = parse_prompt(case['prompt'])
    pz['parsed'] = [parse_clue(pz, c) for c in pz['clues']]
    sols = solve(pz)
    if len(sols) != 1:
        return False, f'{len(sols)} solutions'
    sol = sols[0]
    rows = []
    for cat in pz['ask']:
        rows.append(', '.join(pz['vals'][cat][[v for v in range(pz['n']) if sol[(cat, v)] == p][0]] for p in range(pz['n'])))
    ans = '; '.join(rows)
    exp = case['expected'][0] if isinstance(case['expected'], list) else case['expected']
    if ans != exp:
        return False, ans
    if MINIMAL:
        for i in range(len(pz['parsed'])):
            sub = dict(pz); sub['parsed'] = pz['parsed'][:i] + pz['parsed'][i + 1:]
            if len(solve(sub, limit=2)) < 2:
                return False, f'clue {i + 1} is redundant'
        ans += f'  [minimal: each of the {len(pz["parsed"])} clues is needed]'
    return True, ans


MINIMAL = '--minimal' in sys.argv
if __name__ == '__main__':
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    path = args[0] if args else 'cases.json'
    data = json.load(open(path))
    cases = data['cases'] if isinstance(data, dict) else data
    bad = 0
    for c in cases:
        ok, info = check_case(c)
        bad += not ok
        print(c['id'], 'OK' if ok else 'MISMATCH', info)
    sys.exit(1 if bad else 0)
