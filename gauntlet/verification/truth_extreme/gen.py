"""Generator for reasoning.truth-tellers-extreme (vectorised exhaustive uniqueness check, see lib.py)."""
import random
import numpy as np
from lib import Ev, worlds, refs, KN, KV, SP, AL


class Gen:
    def __init__(self, n, types, glob, seed, min_st, max_st, weights, compound_p):
        self.n, self.types, self.glob = n, types, glob
        self.rng = random.Random(seed)
        self.W = worlds(n, types, glob)
        self.min_st, self.max_st = min_st, max_st
        self.weights = weights
        self.compound_p = compound_p

    def pick(self, d):
        ks = list(d); return self.rng.choices(ks, [d[k] for k in ks])[0]

    def rand_simple(self, sp, avoid=()):
        R, n = self.rng, self.n
        people = [i for i in range(n) if i not in avoid]
        k = self.pick({'is': 0.45, 'same': 0.3, 'count': 0.25})
        if k == 'is':
            return (R.choice(['is', 'is', 'isnot']), R.choice(people), R.choice(self.types))
        if k == 'same':
            a, b = R.sample(people, 2)
            return (R.choice(['same', 'diff']), min(a, b), max(a, b))
        t = R.choice(self.types)
        if R.random() < 0.4:
            return ('count', t, R.choice(['eq', 'eq', 'ge', 'le']), R.randint(1, min(4, n - 1)), None)
        sub = tuple(sorted(R.sample(people, R.randint(3, min(5, len(people))))))
        return ('count', t, R.choice(['eq', 'eq', 'ge', 'le']), R.randint(1, len(sub) - 1), sub)

    def rand_stmt(self, sp, stmts):
        R, n = self.rng, self.n
        k = self.pick(self.weights)
        if k == 'simple':
            if R.random() < self.compound_p:
                return (R.choice(['or', 'and', 'if', 'if', 'iff']), self.rand_simple(sp), self.rand_simple(sp))
            return self.rand_simple(sp)
        if k == 'parity':
            t = R.choice([x for x in self.types if x != KN] or self.types)
            sub = tuple(sorted(R.sample(range(n), R.randint(3, 6))))
            return ('parity', t, sub, R.choice(['odd', 'even']))
        if k == 'would':
            x = R.choice([i for i in range(n) if i != sp])
            inner = self.rand_simple(sp, avoid=(sp, x))
            if inner[0] == 'count':
                return None
            return ('would', x, inner)
        if k in ('meta', 'metacount'):
            # refer only to speakers whose statements contain no references themselves (no chains)
            cands = [s for s in range(n) if s != sp and stmts[s] and not any(refs(x) for x in stmts[s])]
            if not cands:
                return None
            s = R.choice(cands)
            if k == 'meta':
                return ('meta', s, R.randrange(len(stmts[s])), R.random() < 0.5)
            if len(stmts[s]) < 2:
                return None
            return ('metacount', s, R.randint(0, len(stmts[s])))
        raise ValueError(k)

    def trivial(self, ev, st, own):
        v = ev.v(st)
        if v.all() or not v.any():
            return True
        if st[0] in ('or', 'and', 'if', 'iff'):
            def people(at):
                if at[0] in ('is', 'isnot'): return {at[1]}
                if at[0] in ('same', 'diff'): return {at[1], at[2]}
                return {'*'}
            if people(st[1]) == people(st[2]) and len(people(st[1])) == 1:
                return True
            a, b = ev.v(st[1]), ev.v(st[2])
            if a.all() or not a.any() or b.all() or not b.any() or (a == b).all() or (a != b).all():
                return True
        for o in own:
            ov = ev.v(o)
            if (ov == v).all() or (ov != v).all():
                return True
        return False

    def attempt(self, sol_filter=None):
        R, n, W = self.rng, self.n, self.W
        for _ in range(200):
            w = tuple(W[R.randrange(W.shape[0])])
            if sol_filter is None or sol_filter(w):
                break
        stmts = [[] for _ in range(n)]
        order = list(range(n)); R.shuffle(order)
        for sp in order:
            m = R.randint(self.min_st, self.max_st)
            t = w[sp]
            if t == KN: pat = [True] * m
            elif t == KV: pat = [False] * m
            elif t == AL:
                f = R.random() < 0.5; pat = [f if i % 2 == 0 else not f for i in range(m)]
            else: pat = [R.random() < 0.5 for _ in range(m)]
            for want in pat:
                for _ in range(400):
                    st = self.rand_stmt(sp, stmts)
                    if st is None or st in stmts[sp]:
                        continue
                    ev = Ev(W, stmts)
                    cand = stmts[sp] + [st]
                    stmts[sp] = cand
                    idx = self.index_of_world(w)
                    ok = bool(ev.v(st)[idx]) == want and not self.trivial(ev, st, cand[:-1])
                    if ok:
                        break
                    stmts[sp] = cand[:-1]
                else:
                    return None
        return w, stmts

    def index_of_world(self, w):
        if not hasattr(self, '_idx'):
            self._idx = {}
        if w not in self._idx:
            self._idx[w] = int(np.nonzero((self.W == np.array(w, dtype=np.int8)).all(axis=1))[0][0])
        return self._idx[w]

    def solutions(self, stmts):
        ev = Ev(self.W, stmts)
        return np.nonzero(ev.consistent())[0]

    def generate(self, tries=3000, sol_filter=None, min_each=1, log=None):
        for a in range(tries):
            r = self.attempt(sol_filter)
            if r is None:
                continue
            w, stmts = r
            sols = self.solutions(stmts)
            if len(sols) != 1:
                continue
            assert tuple(self.W[sols[0]]) == w
            stmts = self.minimise(w, stmts, min_each)
            return w, stmts, a
        return None

    def minimise(self, w, stmts, min_each):
        R = self.rng
        changed = True
        while changed:
            changed = False
            cands = [(s, i) for s in range(self.n) for i in range(len(stmts[s]))]
            R.shuffle(cands)
            for s, i in cands:
                if len(stmts[s]) <= min_each:
                    continue
                if any(s in refs(x) for l in stmts for x in l):
                    continue  # keep numbering / counts of referenced speakers stable
                trial = [list(l) for l in stmts]
                trial[s].pop(i)
                if len(self.solutions(trial)) == 1:
                    stmts = trial
                    changed = True
                    break
        return stmts
