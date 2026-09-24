"""Builds reasoning.deduction-grid-extreme cases: generate (gen.py), render to English, store with metadata."""
import sys, json, time, os, re
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
from common import ONE
from themes import T
from gen import generate, NUM_CAT

NUMW = {2: 'two', 3: 'three', 4: 'four', 5: 'five', 6: 'six', 7: 'seven', 8: 'eight'}


def cap(s):
    return s[0].upper() + s[1:]


class R:
    def __init__(self, pz):
        self.pz = pz
        self.place = pz.theme['place']

    def S(self, it):
        c, v = it
        return self.pz.tpl[c][0].replace('{v}', self.pz.vals[c][v])

    def P(self, it):
        c, v = it
        return self.pz.tpl[c][1].replace('{v}', self.pz.vals[c][v])

    def N(self, it):
        c, v = it
        return self.pz.tpl[c][2].replace('{v}', self.pz.vals[c][v])

    def clause(self, a):
        k = a[0]
        if k == 'same': return f"{self.S(a[1])} {self.P(a[2])}"
        if k == 'at': return f"{self.S(a[1])} is in {self.place} {a[2] + 1}"
        if k == 'next': return f"{self.S(a[1])} is next to {self.S(a[2])}"
        if k == 'left': return f"{self.S(a[1])} is somewhere to the left of {self.S(a[2])}"
        if k == 'imm': return f"{self.S(a[1])} is immediately to the left of {self.S(a[2])}"
        if k == 'dist': return f"the {self.place} numbers of {self.S(a[1])} and {self.S(a[2])} differ by exactly {a[3]}"
        raise ValueError(a)

    def clue(self, c):
        k = c[0]
        if k == 'atom': return cap(self.clause(c[1])) + '.'
        if k == 'notsame': return f"{cap(self.S(c[1]))} {self.N(c[2])}."
        if k == 'notat':
            ps = [str(p + 1) for p in c[2]]
            lst = ', '.join(ps[:-1]) + ' or ' + ps[-1]
            return f"{cap(self.S(c[1]))} is not in {self.place} {lst}."
        if k == 'notnext': return f"{cap(self.S(c[1]))} is not next to {self.S(c[2])}."
        if k == 'end': return f"{cap(self.S(c[1]))} is in one of the two end {self.place}s."
        if k == 'between': return f"{cap(self.S(c[2]))} is somewhere between {self.S(c[1])} and {self.S(c[3])}."
        if k == 'chain': return f"From left to right, {self.S(c[1])}, {self.S(c[2])} and {self.S(c[3])} are in that order."
        if k == 'xor': return f"Exactly one of these two statements is true: (a) {self.clause(c[1])}; (b) {self.clause(c[2])}."
        if k == 'exactly':
            w = 'one' if c[1] == 1 else 'two'
            verb = 'is' if c[1] == 1 else 'are'
            a, b, d = c[2]
            return f"Exactly {w} of these three statements {verb} true: (a) {self.clause(a)}; (b) {self.clause(b)}; (c) {self.clause(d)}."
        if k == 'if': return f"If {self.clause(c[1])}, then {self.clause(c[2])}."
        if k == 'ofxy':
            return f"Of {self.S(c[1])} and {self.S(c[2])} (two different {self.pz.theme['people']}), one {self.P(c[3])} and the other {self.P(c[4])}."
        if k == 'numgt': return f"{cap(self.S(c[1]))} is older than {self.S(c[2])}."
        if k == 'numdiff': return f"{cap(self.S(c[1]))} is exactly {c[3]} years older than {self.S(c[2])}."
        raise ValueError(c)


def prompt_for(pz, clues, ask, order_seed):
    r = R(pz)
    import random
    order = list(clues)
    random.Random(order_seed).shuffle(order)
    n = pz.n
    place = r.place
    people = pz.theme['people']
    intro = pz.theme['intro'].replace('Eight', cap(NUMW[n])).replace('eight', NUMW[n]).replace('1 to 8', f'1 to {n}')
    legend = []
    for c in pz.names:
        vals = sorted(pz.vals[c], key=lambda s: (int(s) if c == NUM_CAT else 0, s.lower()))
        s, p, ng = pz.tpl[c]
        ex = vals[0]
        legend.append(f"- {c}: {', '.join(vals)}. Clue wording, e.g. for {ex}: \"{s.replace('{v}', ex)}\" (the person with this value); \"... {p.replace('{v}', ex)}\"; \"... {ng.replace('{v}', ex)}\".")
    kinds = {c[0] for c in clues} | {a[0] for c in clues if c[0] in ('xor', 'if') for a in c[1:3]} | {a[0] for c in clues if c[0] == 'exactly' for a in c[2]}
    defs = [
        f"{cap(place)}s are numbered 1 to {n} from left to right, and each {place} has exactly one person.",
        f"\"X is somewhere to the left of Y\" means X's {place} number is lower than Y's (not necessarily adjacent). \"X is immediately to the left of Y\" means X's {place} number is exactly one lower than Y's.",
        f"\"X is next to Y\" means their {place} numbers differ by exactly 1. \"X is not next to Y\" means X and Y are two different people whose {place} numbers differ by at least 2.",
        f"\"The {place} numbers of X and Y differ by exactly d\" means the absolute difference of the two {place} numbers is d.",
        f"\"X is in one of the two end {place}s\" means X is in {place} 1 or {place} {n}. \"X is not in {place} 2, 5 or 7\" means X is in none of those {place}s.",
        f"\"Y is somewhere between X and Z\" means Y's {place} number is strictly between those of X and Z (in either order). \"From left to right, X, Y and Z are in that order\" means X's {place} number < Y's < Z's (not necessarily adjacent).",
        "\"Exactly one of these two statements is true: (a) ...; (b) ...\" means one statement is true and the other is false; the two statements may or may not be able to be true together. \"Exactly one/two of these three statements is/are true\" means precisely that many of the three are true.",
        "\"If P, then Q\" is false only when P is true and Q is false; otherwise it is true (in particular it is true whenever P is false).",
        f"\"Of X and Y (two different {people}), one ... and the other ...\" means X and Y are two different people, one of whom has the first property while the other has the second.",
        "Two different descriptions in the same clue may refer to the same person unless the clue says or implies otherwise (\"next to\", \"left of\", \"between\", \"not next to\", \"older than\", \"in that order\" and \"two different\" all imply different people).",
    ]
    if NUM_CAT in pz.names:
        defs.append("\"X is older than Y\" compares their ages; \"X is exactly d years older than Y\" means X's age minus Y's age is d.")
    A, B = ask
    fmt = f"<{A.lower()} in {place}s 1 to {n}, separated by commas>; <{B.lower()} in {place}s 1 to {n}, separated by commas>"
    lines = [f"{i + 1}. {r.clue(c)}" for i, c in enumerate(order)]
    text = (f"{intro} Each person has exactly one value from each category below, and no two people share a value in any category, so every listed value is used exactly once.\n\n"
            f"Categories (with how the clues refer to them):\n" + '\n'.join(legend) + "\n\n"
            "Definitions:\n" + '\n'.join('- ' + d for d in defs) + "\n\n"
            f"Clues:\n" + '\n'.join(lines) + "\n\n"
            f"Exactly one arrangement satisfies all {len(clues)} clues, and every clue is needed to pin it down.\n\n"
            f"Question: What are the {A.lower()} and the {B.lower()} of the people in {place}s 1 to {n}?\n\n"
            f"Answer format: a single line of the form\n{fmt}\n"
            f"i.e. first the {n} {A.lower()} in {place} order, then a semicolon, then the {n} {B.lower()} in {place} order, each written exactly as in the category list. "
            f"Both lists must be complete and correct to score. {ONE}")
    ans = []
    for c in (A, B):
        row = []
        for p in range(n):
            v = [v for v in range(n) if pz.sol[(c, v)] == p][0]
            row.append(pz.vals[c][v])
        ans.append(row)
    return text, ans, order


PLAN = [
    # id, theme, n, k, seed, ask
    ('x01', 'hotel', 7, 5, 11, ('Countries', 'Books')),
    ('x02', 'camp', 7, 5, 12, ('Instruments', 'Dinners')),
    ('x03', 'theatre', 8, 5, 13, ('Friends', 'Jobs')),
    ('x04', 'lane', 8, 5, 14, ('Pets', 'Car colours')),
    ('x05', 'market', 8, 5, 15, ('Vendors', 'Home towns')),
    ('x06', 'office', 8, 6, 16, ('Colleagues', 'Languages')),
    ('x07', 'studio', 8, 6, 17, ('Subjects', 'Music')),
    ('x08', 'bowling', 8, 6, 18, ('Bowlers', 'Snacks')),
    ('x09', 'canal', 8, 6, 19, ('Boat names', 'Cargoes')),
    ('x10', 'gallery', 8, 6, 20, ('Sculptors', 'Materials')),
]

WEIGHTS = {'same': 0.35, 'at': 0.15, 'notsame': 1.0, 'notat': 1.0, 'next': 1.2, 'notnext': 1.4, 'left': 1.8, 'imm': 0.8,
           'dist': 1.2, 'end': 0.4, 'between': 1.8, 'chain': 1.8, 'xor': 2.4, 'exactly': 2.4, 'if': 2.4, 'ofxy': 1.6,
           'numgt': 1.6, 'numdiff': 1.4}


def build(entry):
    cid, theme, n, k, seed, ask = entry
    t0 = time.time()
    res = generate(T[theme], n, k, seed, WEIGHTS)
    if res is None:
        return None
    pz, clues = res
    assert all(a in pz.names for a in ask), (cid, ask, pz.names)
    text, ans, order = prompt_for(pz, clues, ask, seed * 31 + 7)
    kinds = {}
    for c in clues:
        kinds[c[0]] = kinds.get(c[0], 0) + 1
    full = []
    for p in range(n):
        full.append(f"{p + 1}: " + ', '.join(pz.vals[c][[v for v in range(n) if pz.sol[(c, v)] == p][0]] for c in pz.names))
    sep = '; '
    expected = [', '.join(ans[0]) + sep + ', '.join(ans[1]),
                f"{ask[0]}: " + ', '.join(ans[0]) + sep + f"{ask[1]}: " + ', '.join(ans[1])]
    # Values like "a volcano" are also accepted without their article ("volcano"): leaving it out is not a reasoning error.
    expected += [e2 for e2 in (re.sub(r'(^|(?<=[,;:] ))(a|an|the) ', '', e) for e in expected) if e2 not in expected]
    notes = (f"[extreme] {n} positions x {len(pz.names)} categories, {len(clues)} clues (config {theme}, seed {seed}). "
             f"Uniqueness: OR-tools CP-SAT proves that no arrangement other than the intended one satisfies the clues, and minimality is proved clue by clue "
             f"(removing any single clue admits a second arrangement). Independently re-verified by grid_extreme/verify_sat.py, which parses the English "
             f"legend and clues back from this prompt, encodes them as CNF from scratch and enumerates all models with a SAT solver (exactly one). "
             f"Clue kinds: {kinds}. Full solution: " + ' | '.join(full) + f". Built in {time.time() - t0:.0f}s.")
    return dict(id=cid, prompt=text, expected=expected, notes=notes, n=n, cats=pz.names, nclues=len(clues))


if __name__ == '__main__':
    only = set(sys.argv[1:])
    out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'cases.json')
    done = json.load(open(out_path)) if os.path.exists(out_path) else []
    have = {c['id'] for c in done}
    for e in PLAN:
        if (only and e[0] not in only) or (not only and e[0] in have):
            continue
        print('building', e[0], e[1], e[2], 'x', e[3], flush=True)
        c = build(e)
        if c is None:
            print('  FAILED'); continue
        done = [d for d in done if d['id'] != c['id']] + [c]
        done.sort(key=lambda d: d['id'])
        json.dump(done, open(out_path, 'w'), indent=1, ensure_ascii=False)
        print(f"  {c['id']}: {c['nclues']} clues -> {c['expected'][0]}", flush=True)
