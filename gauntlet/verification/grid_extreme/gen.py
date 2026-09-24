"""Generator for reasoning.deduction-grid-extreme.

A puzzle = n positions (7 or 8) x k categories (5 or 6). Items are (category, value-index); pos[item] in 0..n-1,
all-different per category. Uniqueness is decided with OR-tools CP-SAT: given the intended solution S, the clue set
is unique iff the model plus "at least one position differs from S" is infeasible. Clue sets are then minimised so
that removing ANY single clue admits a second solution (checked explicitly at the end).
"""
import random, itertools, json, sys
from ortools.sat.python import cp_model

NUM_CAT = 'Ages'


class Puzzle:
    def __init__(self, theme, n, k, seed):
        self.theme, self.n, self.seed = theme, n, seed
        self.cats = [c for c in theme['cats']][:k] if k >= len(theme['cats']) else self._choose(theme['cats'], k)
        self.names = [c[0] for c in self.cats]
        self.vals = {c[0]: c[1][:n] for c in self.cats}
        self.tpl = {c[0]: (c[2], c[3], c[4]) for c in self.cats}
        rng = random.Random(seed)
        self.sol = {}
        for c in self.names:
            perm = list(range(n)); rng.shuffle(perm)
            for v in range(n):
                self.sol[(c, v)] = perm[v]
        self.rng = rng
        self.items = [(c, v) for c in self.names for v in range(n)]

    @staticmethod
    def _choose(cats, k):
        keep = [cats[0]] + [c for c in cats[1:] if c[0] == NUM_CAT]
        rest = [c for c in cats[1:] if c[0] != NUM_CAT]
        return (keep + rest)[:k] if len(keep) < k else keep[:k]

    # ---------------------------------------------------------------- truth in the intended solution
    def num(self, item, sol=None):
        sol = sol or self.sol
        p = sol[item]
        for v in range(self.n):
            if sol[(NUM_CAT, v)] == p:
                return int(self.vals[NUM_CAT][v])

    def atom_true(self, a, sol=None):
        sol = sol or self.sol
        k = a[0]
        if k == 'same': return sol[a[1]] == sol[a[2]]
        if k == 'at': return sol[a[1]] == a[2]
        if k == 'next': return abs(sol[a[1]] - sol[a[2]]) == 1
        if k == 'left': return sol[a[1]] < sol[a[2]]
        if k == 'imm': return sol[a[1]] + 1 == sol[a[2]]
        if k == 'dist': return abs(sol[a[1]] - sol[a[2]]) == a[3]
        raise ValueError(a)

    def clue_true(self, c, sol=None):
        sol = sol or self.sol
        k = c[0]
        if k == 'atom': return self.atom_true(c[1], sol)
        if k == 'notsame': return sol[c[1]] != sol[c[2]]
        if k == 'notat': return sol[c[1]] not in c[2]
        if k == 'notnext': return abs(sol[c[1]] - sol[c[2]]) >= 2
        if k == 'end': return sol[c[1]] in (0, self.n - 1)
        if k == 'between': return min(sol[c[1]], sol[c[3]]) < sol[c[2]] < max(sol[c[1]], sol[c[3]])
        if k == 'chain': return sol[c[1]] < sol[c[2]] < sol[c[3]]
        if k == 'xor': return self.atom_true(c[1], sol) != self.atom_true(c[2], sol)
        if k == 'exactly': return sum(self.atom_true(a, sol) for a in c[2]) == c[1]
        if k == 'if': return (not self.atom_true(c[1], sol)) or self.atom_true(c[2], sol)
        if k == 'ofxy':
            x, y, a, b = c[1:]
            return sol[x] != sol[y] and {sol[x], sol[y]} == {sol[a], sol[b]}
        if k == 'numgt': return self.num(c[1], sol) > self.num(c[2], sol)
        if k == 'numdiff': return self.num(c[1], sol) - self.num(c[2], sol) == c[3]
        raise ValueError(c)

    # ---------------------------------------------------------------- random candidate clues
    def rand_item(self, exclude_cat=None, allow_num=True):
        while True:
            it = self.rng.choice(self.items)
            if it[0] != exclude_cat and (allow_num or it[0] != NUM_CAT):
                return it

    def rand_atom(self):
        r = self.rng.random()
        x = self.rand_item()
        if r < 0.40:
            y = self.rand_item(exclude_cat=x[0]); return ('same', x, y)
        if r < 0.55:
            return ('at', x, self.rng.randrange(self.n))
        y = self.rand_item()
        while y == x or self.sol[y] == self.sol[x] and y[0] == x[0]:
            y = self.rand_item()
        if r < 0.72: return ('next', x, y)
        if r < 0.90: return ('left', x, y)
        if r < 0.96: return ('imm', x, y)
        return ('dist', x, y, self.rng.randint(2, self.n - 2))

    def pool(self, size, weights):
        out, seen = [], set()
        kinds = list(weights)
        wts = [weights[k] for k in kinds]
        tries = 0
        while len(out) < size and tries < size * 400:
            tries += 1
            k = self.rng.choices(kinds, wts)[0]
            c = self.make(k)
            if c is None or c in seen or not self.clue_true(c):
                continue
            seen.add(c)
            out.append(c)
        return out

    def make(self, k):
        R = self.rng
        x = self.rand_item()
        if k in ('same', 'notsame'):
            y = self.rand_item(exclude_cat=x[0])
            if (self.sol[x] == self.sol[y]) != (k == 'same'): return None
            return ('atom', ('same', x, y)) if k == 'same' else ('notsame', x, y)
        if k == 'at':
            return ('atom', ('at', x, self.sol[x]))
        if k == 'notat':
            ps = sorted(R.sample([p for p in range(self.n) if p != self.sol[x]], R.randint(2, 3)))
            return ('notat', x, tuple(ps))
        y = self.rand_item()
        if self.sol[y] == self.sol[x]:
            return None
        if k == 'next': return ('atom', ('next', x, y))
        if k == 'notnext': return ('notnext', x, y)
        if k == 'left': return ('atom', ('left', x, y))
        if k == 'imm': return ('atom', ('imm', x, y))
        if k == 'dist': return ('atom', ('dist', x, y, abs(self.sol[x] - self.sol[y])))
        if k == 'end': return ('end', x)
        if k in ('between', 'chain'):
            z = self.rand_item()
            if len({self.sol[x], self.sol[y], self.sol[z]}) < 3: return None
            return (k, x, y, z)
        if k == 'xor':
            a, b = self.rand_atom(), self.rand_atom()
            if a == b: return None
            return ('xor', a, b)
        if k == 'exactly':
            atoms = [self.rand_atom() for _ in range(3)]
            if len(set(atoms)) < 3: return None
            t = sum(self.atom_true(a) for a in atoms)
            if t not in (1, 2): return None
            return ('exactly', t, tuple(atoms))
        if k == 'if':
            a, b = self.rand_atom(), self.rand_atom()
            if a == b: return None
            return ('if', a, b)
        if k == 'ofxy':
            a, b = self.rand_item(exclude_cat=x[0]), self.rand_item(exclude_cat=y[0])
            if a[0] == x[0] or a[0] == y[0] or b[0] == x[0] or b[0] == y[0] or a == b: return None
            return ('ofxy', x, y, a, b)
        if k in ('numgt', 'numdiff'):
            if NUM_CAT not in self.names or x[0] == NUM_CAT or y[0] == NUM_CAT: return None
            d = self.num(x) - self.num(y)
            if d <= 0: return None
            return ('numgt', x, y) if k == 'numgt' else ('numdiff', x, y, d)
        raise ValueError(k)

    # ---------------------------------------------------------------- CP-SAT
    def model(self, clues, forbid_solution=True):
        m = cp_model.CpModel()
        n = self.n
        P = {it: m.NewIntVar(0, n - 1, f'p{it}') for it in self.items}
        for c in self.names:
            m.AddAllDifferent([P[(c, v)] for v in range(n)])
        cache = {}

        def absdiff(x, y):
            key = ('abs', x, y)
            if key not in cache:
                d = m.NewIntVar(-n, n, ''); a = m.NewIntVar(0, n, '')
                m.Add(d == P[x] - P[y]); m.AddAbsEquality(a, d)
                cache[key] = a
            return cache[key]

        def numv(x):
            key = ('num', x)
            if key not in cache:
                vals = [int(v) for v in self.vals[NUM_CAT]]
                bs = []
                for v in range(n):
                    b = m.NewBoolVar('')
                    m.Add(P[(NUM_CAT, v)] == P[x]).OnlyEnforceIf(b)
                    m.Add(P[(NUM_CAT, v)] != P[x]).OnlyEnforceIf(b.Not())
                    bs.append(b)
                nv = m.NewIntVar(min(vals), max(vals), '')
                m.Add(nv == sum(vals[v] * bs[v] for v in range(n)))
                cache[key] = nv
            return cache[key]

        def reify(a):
            key = ('atom', a)
            if key in cache:
                return cache[key]
            b = m.NewBoolVar('')
            k = a[0]
            if k == 'same':
                m.Add(P[a[1]] == P[a[2]]).OnlyEnforceIf(b); m.Add(P[a[1]] != P[a[2]]).OnlyEnforceIf(b.Not())
            elif k == 'at':
                m.Add(P[a[1]] == a[2]).OnlyEnforceIf(b); m.Add(P[a[1]] != a[2]).OnlyEnforceIf(b.Not())
            elif k == 'next':
                ad = absdiff(a[1], a[2]); m.Add(ad == 1).OnlyEnforceIf(b); m.Add(ad != 1).OnlyEnforceIf(b.Not())
            elif k == 'left':
                m.Add(P[a[1]] < P[a[2]]).OnlyEnforceIf(b); m.Add(P[a[1]] >= P[a[2]]).OnlyEnforceIf(b.Not())
            elif k == 'imm':
                m.Add(P[a[1]] + 1 == P[a[2]]).OnlyEnforceIf(b); m.Add(P[a[1]] + 1 != P[a[2]]).OnlyEnforceIf(b.Not())
            elif k == 'dist':
                ad = absdiff(a[1], a[2]); m.Add(ad == a[3]).OnlyEnforceIf(b); m.Add(ad != a[3]).OnlyEnforceIf(b.Not())
            cache[key] = b
            return b

        for c in clues:
            k = c[0]
            if k == 'atom': m.Add(reify(c[1]) == 1)
            elif k == 'notsame': m.Add(P[c[1]] != P[c[2]])
            elif k == 'notat':
                for p in c[2]: m.Add(P[c[1]] != p)
            elif k == 'notnext': m.Add(absdiff(c[1], c[2]) >= 2)
            elif k == 'end': m.AddLinearExpressionInDomain(P[c[1]], cp_model.Domain.FromValues([0, n - 1]))
            elif k == 'between':
                b1, b2 = m.NewBoolVar(''), m.NewBoolVar('')
                m.Add(P[c[1]] < P[c[2]]).OnlyEnforceIf(b1); m.Add(P[c[2]] < P[c[3]]).OnlyEnforceIf(b1)
                m.Add(P[c[3]] < P[c[2]]).OnlyEnforceIf(b2); m.Add(P[c[2]] < P[c[1]]).OnlyEnforceIf(b2)
                m.AddBoolOr([b1, b2])
            elif k == 'chain': m.Add(P[c[1]] < P[c[2]]); m.Add(P[c[2]] < P[c[3]])
            elif k == 'xor': m.Add(reify(c[1]) + reify(c[2]) == 1)
            elif k == 'exactly': m.Add(sum(reify(a) for a in c[2]) == c[1])
            elif k == 'if': m.AddImplication(reify(c[1]), reify(c[2]))
            elif k == 'ofxy':
                x, y, a, b = c[1:]
                m.Add(P[x] != P[y])
                o1 = m.NewBoolVar(''); o2 = m.NewBoolVar('')
                m.Add(P[x] == P[a]).OnlyEnforceIf(o1); m.Add(P[y] == P[b]).OnlyEnforceIf(o1)
                m.Add(P[x] == P[b]).OnlyEnforceIf(o2); m.Add(P[y] == P[a]).OnlyEnforceIf(o2)
                m.AddBoolOr([o1, o2])
            elif k == 'numgt': m.Add(numv(c[1]) > numv(c[2]))
            elif k == 'numdiff': m.Add(numv(c[1]) - numv(c[2]) == c[3])
        if forbid_solution:
            diffs = []
            for it in self.items:
                b = m.NewBoolVar('')
                m.Add(P[it] != self.sol[it]).OnlyEnforceIf(b); m.Add(P[it] == self.sol[it]).OnlyEnforceIf(b.Not())
                diffs.append(b)
            m.AddBoolOr(diffs)
        return m, P

    def unique(self, clues):
        """True iff the intended solution is the ONLY solution of clues."""
        m, P = self.model(clues)
        s = cp_model.CpSolver()
        s.parameters.num_workers = 8
        s.parameters.max_time_in_seconds = 300
        st = s.Solve(m)
        if st == cp_model.INFEASIBLE:
            return True
        if st in (cp_model.FEASIBLE, cp_model.OPTIMAL):
            return False
        raise RuntimeError('solver timeout')

    def consistent(self, clues):
        m, P = self.model(clues, forbid_solution=False)
        s = cp_model.CpSolver(); s.parameters.num_workers = 8
        return s.Solve(m) in (cp_model.FEASIBLE, cp_model.OPTIMAL)


def generate(theme, n, k, seed, weights, pool_size=900, check_every=4, log=print):
    pz = Puzzle(theme, n, k, seed)
    pool = pz.pool(pool_size, weights)
    for c in pool:
        assert pz.clue_true(c)
    chosen = []
    it = iter(pool)
    step = 0
    for c in it:
        chosen.append(c)
        step += 1
        if step % check_every == 0 and pz.unique(chosen):
            break
    else:
        if not pz.unique(chosen):
            return None
    log(f'  unique with {len(chosen)} clues; minimising')
    # minimise: try removing clues, direct kinds first, then others in random order
    order = list(range(len(chosen)))
    pz.rng.shuffle(order)
    prio = {'atom': 0, 'notsame': 1}
    order.sort(key=lambda i: 0 if (chosen[i][0] == 'atom' and chosen[i][1][0] in ('same', 'at')) else 1)
    keep = list(chosen)
    for i in order:
        c = chosen[i]
        trial = [x for x in keep if x is not c]
        if pz.unique(trial):
            keep = trial
    # explicit minimality proof: every single removal admits another solution
    for c in keep:
        trial = [x for x in keep if x is not c]
        assert not pz.unique(trial), 'not minimal'
    assert pz.unique(keep)
    return pz, keep
