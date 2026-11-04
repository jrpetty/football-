"""Generator for reasoning.truth-ladder ("Knights & Knaves: Ladder"): ten frozen levels of one truth-teller task.

Reuses the Knights, Knaves, Spies & Alternators: Extreme statement language and English renderer unchanged
(truth_extreme/lib.py). What is new is the scale: from 4 islanders to 24, so the 4^n possible line-ups (up to
2.8 x 10^14) can no longer be enumerated. Uniqueness is therefore decided with OR-tools CP-SAT (an exact solver: the
intended line-up is forbidden and the solver must prove no other line-up satisfies every speaker), and re-proved
independently by verify.mjs (an exhaustive three-valued backtracking search in JavaScript that parses the English
back and counts every consistent line-up).

What grows, level by level (same rules and the same conventions on every level):
  islanders            4, 5, 6, 9, 12, 14, 16, 19, 22, 24
  spies / alternators  0/0, 0/0, 1/0, 1/1, 2/1, 2/2, 2/2, 3/2, 3/3, 4/3   (stated exactly in the prompt)
  statement depth      atoms -> compounds and counts -> "would say" -> statements about other people's statements
                       -> parity and "exactly k of X's statements" -> nested "X would say that Y would say that ..."
                       and chains of statements about statements (depth 2, then 3)

Generation: draw the intended line-up, give every islander statements whose truth value fits their type, then
repeatedly ask CP-SAT for a different consistent line-up and add a statement (to someone who still may speak) that
rules it out, until none exists. Finally the statements are minimised (every removable statement is removed while
the line-up stays unique; statements other statements refer to are kept so numbering and counts stay stable).

    python3 gen_ladder.py           # builds ladder_cases.json
"""
import json
import os
import random
import sys

import numpy as np
from ortools.sat.python import cp_model

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'truth_extreme'))
sys.path.insert(1, os.path.join(HERE, '..'))
import lib  # noqa: E402  (truth_extreme/lib.py)
from lib import Ev, refs, stmt_text, TYPES, KN, KV, SP, AL  # noqa: E402
from common import ONE  # noqa: E402

NAMES = ['Ada', 'Bruno', 'Cyra', 'Dov', 'Esme', 'Fitz', 'Gia', 'Hugo', 'Iris', 'Jonah', 'Kiri', 'Lars', 'Mei', 'Nnamdi',
         'Omar', 'Priya', 'Quinn', 'Rosa', 'Sven', 'Tavi', 'Uma', 'Vikram', 'Wren', 'Yusuf']
lib.NUMW.update({11: 'eleven', 12: 'twelve', 13: 'thirteen', 14: 'fourteen', 15: 'fifteen', 16: 'sixteen', 17: 'seventeen',
                 18: 'eighteen', 19: 'nineteen', 20: 'twenty', 21: 'twenty-one', 22: 'twenty-two', 23: 'twenty-three', 24: 'twenty-four'})
NUMW = lib.NUMW

# level, islanders, spies, alternators, seed, max statements per islander, kinds (weights), compound share, would depth, meta depth
PLAN = [
    (1, 4, 0, 0, 7101, 1, {'simple': 1}, 0.0, 0, 0),
    (2, 5, 0, 0, 7102, 2, {'simple': 1}, 0.3, 0, 0),
    (3, 6, 1, 0, 7103, 2, {'simple': 5, 'would': 1.5}, 0.3, 1, 0),
    (4, 9, 1, 1, 7104, 3, {'simple': 5, 'would': 1.5, 'meta': 1, 'parity': 0.8}, 0.35, 1, 1),
    (5, 12, 2, 1, 7105, 3, {'simple': 5, 'would': 1.6, 'meta': 1, 'metacount': 0.8, 'parity': 1}, 0.35, 2, 1),
    (6, 14, 2, 2, 7106, 3, {'simple': 5, 'would': 1.8, 'meta': 1, 'metacount': 0.8, 'parity': 1}, 0.35, 2, 2),
    (7, 16, 2, 2, 7107, 3, {'simple': 5, 'would': 2, 'meta': 1.2, 'metacount': 0.9, 'parity': 1}, 0.4, 2, 2),
    (8, 19, 3, 2, 7108, 3, {'simple': 5, 'would': 2.2, 'meta': 1.3, 'metacount': 1, 'parity': 1}, 0.4, 3, 3),
    (9, 22, 3, 3, 7109, 3, {'simple': 5, 'would': 2.4, 'meta': 1.4, 'metacount': 1, 'parity': 1}, 0.4, 3, 3),
    (10, 24, 4, 3, 7110, 3, {'simple': 5, 'would': 2.6, 'meta': 1.5, 'metacount': 1.1, 'parity': 1.1}, 0.45, 3, 3),
]

TYPE_DEF = {
    KN: '- A knight: every statement a knight makes is true.',
    KV: '- A knave: every statement a knave makes is false.',
    SP: '- A spy: each statement a spy makes may be true or false, in any combination.',
    AL: '- An alternator: an alternator\'s statements alternate between true and false in the order they are made. The first may be either true or false, but no two consecutive statements by an alternator have the same truth value.',
}


def rule_text(s, a):
    if s == 0 and a == 0:
        return 'None of them is a spy or an alternator.'
    sp = 'none of them is a spy' if s == 0 else ('exactly one of them is a spy' if s == 1 else f'exactly {NUMW[s]} of them are spies')
    al = 'none of them is an alternator' if a == 0 else ('exactly one of them is an alternator' if a == 1 else f'exactly {NUMW[a]} of them are alternators')
    return f'{sp[0].upper() + sp[1:]}, and {al}.'


# ---------------------------------------------------------------------------------------------- CP-SAT model
def deterministic_solver():
    """One search worker and a fixed seed: the rival line-up CP-SAT returns (which steers the next statement) is the
    same on every run, so the whole ladder regenerates identically from its seeds."""
    s = cp_model.CpSolver()
    s.parameters.num_workers = 1
    s.parameters.random_seed = 0
    s.parameters.max_time_in_seconds = 1800
    return s


class Model:
    """Exact uniqueness check: every speaker constraint over boolean type variables (one type per islander)."""

    def __init__(self, n, ns, na, stmts):
        self.n, self.stmts = n, stmts
        m = self.m = cp_model.CpModel()
        self.T = [[m.NewBoolVar(f't{i}_{t}') for t in range(4)] for i in range(n)]
        for i in range(n):
            m.AddExactlyOne(self.T[i])
        m.Add(sum(self.T[i][SP] for i in range(n)) == ns)
        m.Add(sum(self.T[i][AL] for i in range(n)) == na)
        self.V = [[m.NewBoolVar(f'v{s}_{j}') for j in range(len(l))] for s, l in enumerate(stmts)]
        self.cache = {}
        for s, l in enumerate(stmts):
            for j, st in enumerate(l):
                b = self.reify(st)
                m.Add(self.V[s][j] == b)
            vs = self.V[s]
            for v in vs:
                m.AddImplication(self.T[s][KN], v)
                m.AddImplication(self.T[s][KV], v.Not())
            for j in range(len(vs) - 1):
                m.Add(vs[j] != vs[j + 1]).OnlyEnforceIf(self.T[s][AL])

    def AND(self, a, b):
        c = self.m.NewBoolVar('')
        self.m.AddBoolAnd([a, b]).OnlyEnforceIf(c)
        self.m.AddBoolOr([a.Not(), b.Not()]).OnlyEnforceIf(c.Not())
        return c

    def cmp(self, expr, op, k):
        b = self.m.NewBoolVar('')
        m = self.m
        if op == 'eq':
            m.Add(expr == k).OnlyEnforceIf(b); m.Add(expr != k).OnlyEnforceIf(b.Not())
        elif op == 'ge':
            m.Add(expr >= k).OnlyEnforceIf(b); m.Add(expr < k).OnlyEnforceIf(b.Not())
        else:
            m.Add(expr <= k).OnlyEnforceIf(b); m.Add(expr > k).OnlyEnforceIf(b.Not())
        return b

    def reify(self, st):
        if st in self.cache:
            return self.cache[st]
        m, T, k = self.m, self.T, st[0]
        if k == 'is': r = T[st[1]][st[2]]
        elif k == 'isnot': r = T[st[1]][st[2]].Not()
        elif k in ('same', 'diff'):
            same = m.NewBoolVar('')
            m.Add(sum(self.AND(T[st[1]][t], T[st[2]][t]) for t in range(4)) == 1).OnlyEnforceIf(same)
            m.Add(sum(self.AND(T[st[1]][t], T[st[2]][t]) for t in range(4)) == 0).OnlyEnforceIf(same.Not())
            r = same if k == 'same' else same.Not()
        elif k == 'count':
            _, t, op, kk, sub = st
            cols = range(self.n) if sub is None else sub
            r = self.cmp(sum(T[i][t] for i in cols), op, kk)
        elif k == 'parity':
            c = sum(T[i][st[1]] for i in st[2])
            q = m.NewIntVar(0, len(st[2]), '')
            odd = m.NewBoolVar('')
            m.Add(c == 2 * q + odd)
            r = odd if st[3] == 'odd' else odd.Not()
        elif k == 'would':
            inner = self.reify(st[2])
            a = self.AND(T[st[1]][KN], inner)
            b = self.AND(T[st[1]][KV], inner.Not())
            r = m.NewBoolVar('')
            m.AddBoolOr([a, b]).OnlyEnforceIf(r)
            m.AddBoolAnd([a.Not(), b.Not()]).OnlyEnforceIf(r.Not())
        elif k == 'meta':
            v = self.V[st[1]][st[2]]
            r = v if st[3] else v.Not()
        elif k == 'metacount':
            r = self.cmp(sum(self.V[st[1]]), 'eq', st[2])
        elif k in ('or', 'and', 'if', 'iff'):
            a, b = self.reify(st[1]), self.reify(st[2])
            r = m.NewBoolVar('')
            if k == 'or':
                m.AddBoolOr([a, b]).OnlyEnforceIf(r); m.AddBoolAnd([a.Not(), b.Not()]).OnlyEnforceIf(r.Not())
            elif k == 'and':
                m.AddBoolAnd([a, b]).OnlyEnforceIf(r); m.AddBoolOr([a.Not(), b.Not()]).OnlyEnforceIf(r.Not())
            elif k == 'if':
                m.AddBoolOr([a.Not(), b]).OnlyEnforceIf(r); m.AddBoolAnd([a, b.Not()]).OnlyEnforceIf(r.Not())
            else:
                m.Add(a == b).OnlyEnforceIf(r); m.Add(a != b).OnlyEnforceIf(r.Not())
        else:
            raise ValueError(st)
        self.cache[st] = r
        return r

    def other(self, w):
        """A consistent line-up different from w, or None when w is the only one (proved by CP-SAT)."""
        self.m.AddBoolOr([self.T[i][w[i]].Not() for i in range(self.n)])
        s = deterministic_solver()
        st = s.Solve(self.m)
        if st == cp_model.INFEASIBLE:
            return None
        if st in (cp_model.FEASIBLE, cp_model.OPTIMAL):
            return tuple(next(t for t in range(4) if s.Value(self.T[i][t])) for i in range(self.n))
        raise RuntimeError('CP-SAT timeout')

    def any_solution(self):
        s = deterministic_solver()
        st = s.Solve(self.m)
        if st in (cp_model.FEASIBLE, cp_model.OPTIMAL):
            return tuple(next(t for t in range(4) if s.Value(self.T[i][t])) for i in range(self.n))
        return None


# ---------------------------------------------------------------------------------------------- generation
def depth_meta(st, stmts, memo=None):
    """Depth of statements-about-statements chains (0 = none)."""
    k = st[0]
    if k == 'meta':
        return 1 + depth_meta(stmts[st[1]][st[2]], stmts)
    if k == 'metacount':
        return 1 + max(depth_meta(x, stmts) for x in stmts[st[1]])
    if k in ('or', 'and', 'if', 'iff'):
        return max(depth_meta(st[1], stmts), depth_meta(st[2], stmts))
    if k == 'would':
        return depth_meta(st[2], stmts)
    return 0


def depth_would(st):
    if st[0] == 'would':
        return 1 + depth_would(st[2])
    if st[0] in ('or', 'and', 'if', 'iff'):
        return max(depth_would(st[1]), depth_would(st[2]))
    return 0


class Gen:
    def __init__(self, n, ns, na, seed, max_st, weights, compound_p, dwould, dmeta):
        self.n, self.ns, self.na = n, ns, na
        self.R = random.Random(seed)
        self.max_st, self.weights, self.compound_p = max_st, weights, compound_p
        self.dwould, self.dmeta = dwould, dmeta
        self.types = [KN, KV] + ([SP] if ns else []) + ([AL] if na else [])

    def rand_world(self):
        R, n = self.R, self.n
        idx = list(range(n)); R.shuffle(idx)
        w = [KN if R.random() < 0.5 else KV for _ in range(n)]
        for i in idx[:self.ns]: w[i] = SP
        for i in idx[self.ns:self.ns + self.na]: w[i] = AL
        return tuple(w)

    def intended_world(self):
        """The answer: a random line-up with the stated counts and a real mix of knights and knaves."""
        m = self.n - self.ns - self.na
        while True:
            w = self.rand_world()
            if min(w.count(KN), w.count(KV)) >= max(1, round(0.3 * m)):
                return w

    def sample(self, w, extra, size=3000):
        W = [w] + list(extra)
        while len(W) < size:
            W.append(self.rand_world())
        return np.array(W, dtype=np.int8)

    def pick(self, d):
        ks = list(d)
        return self.R.choices(ks, [d[k] for k in ks])[0]

    def rand_simple(self, sp, avoid=()):
        R, n = self.R, self.n
        people = [i for i in range(n) if i not in avoid]
        k = self.pick({'is': 0.45, 'same': 0.3, 'count': 0.25})
        if k == 'is':
            return (R.choice(['is', 'is', 'isnot']), R.choice(people), R.choice(self.types))
        if k == 'same':
            a, b = R.sample(people, 2)
            return (R.choice(['same', 'diff']), min(a, b), max(a, b))
        t = R.choice(self.types)
        op = R.choice(['eq', 'eq', 'ge', 'le'])
        if R.random() < 0.35:
            if t in (SP, AL):
                kk = R.randint(0, (self.ns if t == SP else self.na) + 1)
            else:
                kk = R.randint(max(1, n // 2 - 3), min(n - 1, n // 2 + 3))
            if kk == 0:
                op = 'eq'
            return ('count', t, op, kk, None)
        sub = tuple(sorted(R.sample(people, R.randint(3, min(6, len(people))))))
        return ('count', t, op, R.randint(1, len(sub) - 1), sub)

    def rand_would(self, sp, depth):
        R = self.R
        chain, avoid = [], {sp}
        for _ in range(depth):
            x = R.choice([i for i in range(self.n) if i not in avoid])
            chain.append(x); avoid.add(x)
        inner = self.rand_simple(sp, avoid=tuple(avoid))
        if inner[0] == 'count':
            return None
        for x in reversed(chain):
            inner = ('would', x, inner)
        return inner

    def rand_stmt(self, sp, stmts, frozen, force=None, depth=None):
        R, n = self.R, self.n
        k = force or self.pick(self.weights)
        if k == 'simple':
            if R.random() < self.compound_p:
                return (R.choice(['or', 'and', 'if', 'if', 'iff']), self.rand_simple(sp), self.rand_simple(sp))
            return self.rand_simple(sp)
        if k == 'parity':
            t = R.choice([x for x in self.types if x != KN])
            sub = tuple(sorted(R.sample(range(n), R.randint(3, min(6, n)))))
            return ('parity', t, sub, R.choice(['odd', 'even']))
        if k == 'would':
            return self.rand_would(sp, depth or R.randint(1, self.dwould))
        if k in ('meta', 'metacount'):
            # refer only to speakers whose statements are final (frozen), never to oneself; chains up to dmeta deep
            # (depth = the exact chain depth wanted, used when a level's deepest chain is built on purpose)
            cands = [s for s in frozen if s != sp and stmts[s]
                     and (max(depth_meta(x, stmts) for x in stmts[s]) < self.dmeta if depth is None
                          else max(depth_meta(x, stmts) for x in stmts[s]) == depth - 1)]
            if not cands:
                return None
            s = R.choice(cands)
            if k == 'meta':
                return ('meta', s, R.randrange(len(stmts[s])), R.random() < 0.5)
            if len(stmts[s]) < 2:
                return None
            return ('metacount', s, R.randint(0, len(stmts[s])))
        raise ValueError(k)

    @staticmethod
    def trivial(ev, st, own):
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

    def want(self, w, sp, stmts, ev0):
        """Truth value the next statement of sp must have in the intended line-up w (None for a spy: either)."""
        t = w[sp]
        if t == KN: return True
        if t == KV: return False
        if t == AL:
            if not stmts[sp]:
                return None
            return not bool(ev0.v(stmts[sp][-1])[0])
        return None

    def add_statement(self, w, sp, stmts, frozen, W, rival=None, tries=500, force=None, depth=None):
        """A new statement for sp, true/false in w as sp's type requires and not trivial on the sample W (a
        statement constant over thousands of random line-ups says nothing); prefers one that makes the rival
        line-up (row `rival` of W) inconsistent. Returns (statement, kills_rival) or None."""
        need = self.want(w, sp, stmts, Ev(W, stmts))
        small = W[[0] + ([rival] if rival is not None else [])]
        best = None
        for _ in range(tries):
            st = self.rand_stmt(sp, stmts, frozen, force, depth)
            if st is None or st in stmts[sp]:
                continue
            trial = [list(l) for l in stmts]
            trial[sp].append(st)
            ev = Ev(W, trial)
            if need is not None and bool(ev.v(st)[0]) != need:
                continue
            if self.trivial(ev, st, stmts[sp]):
                continue
            ok = Ev(small, trial).consistent()
            if not ok[0]:
                continue
            if rival is not None and not ok[1]:
                return st, True
            if best is None:
                best = st
                if rival is None:
                    break
        return (best, False) if best is not None else None

    def consistent_w(self, w, stmts):
        return bool(Ev(np.array([w], dtype=np.int8), stmts).consistent()[0])

    def generate(self, log=print):
        n = self.n
        w = self.intended_world()
        stmts = [[] for _ in range(n)]
        frozen = []
        order = list(range(n)); self.R.shuffle(order)
        rivals = []
        W = self.sample(w, rivals)
        # everyone speaks once (alternators twice, so the alternation shows)
        for sp in order:
            for _ in range(2 if w[sp] == AL and self.max_st >= 2 else 1):
                r = self.add_statement(w, sp, stmts, frozen, W)
                if r is None:
                    return None
                stmts[sp].append(r[0])
            if self.R.random() < 0.25:
                frozen.append(sp)
        # make sure the level's statement kinds and its deepest nesting are present from the start
        wanted = [(k, None) for k in self.weights if k not in ('simple', 'meta', 'metacount')]
        if self.dwould >= 2:
            wanted.append(('would', self.dwould))
        for d in range(1, self.dmeta + 1):
            wanted.append(('meta', d))
        if 'metacount' in self.weights:
            wanted.append(('metacount', None))
        for kind, d in wanted:
            for _ in range(60):
                open_sp = [x for x in range(n) if x not in frozen and len(stmts[x]) < self.max_st]
                if not open_sp:
                    return None
                sp = self.R.choice(open_sp)
                r = self.add_statement(w, sp, stmts, frozen, W, tries=60, force=kind, depth=d)
                if r is not None:
                    stmts[sp].append(r[0])
                    if kind in ('meta', 'metacount') and sp not in frozen:
                        frozen.append(sp)  # so the next, deeper statement can refer to this one
                    break
        assert self.consistent_w(w, stmts)
        rounds = 0
        while True:
            rounds += 1
            other = Model(n, self.ns, self.na, stmts).other(w)
            if other is None:
                break
            rivals.append(other)
            W = self.sample(w, rivals[-200:])
            row = len(rivals[-200:])  # W = [w] + recent rivals + random line-ups: the newest rival's row
            open_sp = [s for s in range(n) if s not in frozen and len(stmts[s]) < self.max_st]
            if not open_sp:
                return None
            self.R.shuffle(open_sp)
            # prefer a statement that kills the rival; otherwise any valid statement
            pick = None
            for sp in open_sp:
                r = self.add_statement(w, sp, stmts, frozen, W, row, tries=200)
                if r and r[1]:
                    pick = (sp, r[0]); break
                if r and pick is None:
                    pick = (sp, r[0])
            if pick is None:
                return None
            sp, st = pick
            stmts[sp].append(st)
            if len(stmts[sp]) >= self.max_st or self.R.random() < 0.2:
                if sp not in frozen:
                    frozen.append(sp)
            assert self.consistent_w(w, stmts)
            if rounds > 40 * n:
                return None
        log(f'  unique after {rounds} rounds with {sum(map(len, stmts))} statements; minimising')
        stmts = self.minimise(w, stmts)
        return w, stmts

    def minimise(self, w, stmts):
        changed = True
        while changed:
            changed = False
            cands = [(s, i) for s in range(self.n) for i in range(len(stmts[s]))]
            self.R.shuffle(cands)
            for s, i in cands:
                if len(stmts[s]) <= 1:
                    continue
                if any(s in refs(x) for l in stmts for x in l):
                    continue  # keep numbering and counts of referenced speakers stable
                trial = [list(l) for l in stmts]
                trial[s].pop(i)
                if not self.consistent_w(w, trial):
                    continue
                if Model(self.n, self.ns, self.na, trial).other(w) is None:
                    stmts = trial
                    changed = True
                    break
        return stmts


# ---------------------------------------------------------------------------------------------- prompt
CONVENTIONS = [
    '- "Us" always means all {N} of these inhabitants, including the speaker. "Me" and "I" always mean the speaker.',
    '- "Or" is inclusive: "P, or Q" is true when at least one of P and Q is true. "P, and Q" is true only when both are true.',
    '- "If P, then Q" is false only when P is true and Q is false; otherwise it is true.',
    '- "P if and only if Q" is true exactly when P and Q are both true or both false.',
    '- "X and Y are the same type" / "different types" compare the two people\'s types.',
    '- "Among X, Y and me, exactly two are knights" counts only the people named.',
    '- All alternators count as the same type, whichever truth value their first statement has. So "X and Y are the same type" is true when both are alternators, and counts of alternators include every alternator.',
    '- "The number of spies among X, Y and Z is even" counts only the people named; zero counts as even.',
    '- "X would say that P" is shorthand for: X is a knight and P is true, or X is a knave and P is false. It is therefore false whenever X is a spy or an alternator (they have no fixed answer). P itself never mentions the speaker or X. P may itself be of the form "Y would say that Q", which is read in the same way.',
    '- "Ada\'s second statement is true" refers to Ada\'s statement with that number in the list below ("Ada\'s statement" if she made only one), and is true exactly when that statement is true.',
    '- "Exactly one of Ada\'s statements is true", "All of Ada\'s statements are true" and "All of Ada\'s statements are false" count the truth values of all of Ada\'s statements listed below.',
]


def render(n, ns, na, stmts):
    names = NAMES[:n]
    nst = [len(l) for l in stmts]
    lines = []
    for s, l in enumerate(stmts):
        if len(l) == 1:
            lines.append(f'{names[s]} says: "{stmt_text(l[0], s, names, nst)}"')
        else:
            parts = ' '.join(f'({i + 1}) "{stmt_text(st, s, names, nst)}"' for i, st in enumerate(l))
            lines.append(f'{names[s]} makes {NUMW[len(l)]} statements, in this order: {parts}')
    who = ', '.join(names[:-1]) + ' and ' + names[-1]
    conv = [c.replace('{N}', NUMW[n]) for c in CONVENTIONS]
    return ('On a certain island, every inhabitant is exactly one of these types:\n' + '\n'.join(TYPE_DEF[t] for t in (KN, KV, SP, AL)) + '\n\n'
            f'You meet {NUMW[n]} inhabitants: {who}. Each of them knows the type of everyone present. {rule_text(ns, na)}\n\n'
            'Conventions:\n' + '\n'.join(conv) + '\n\nStatements:\n' + '\n'.join(lines) + '\n\n'
            'Determine the type of every inhabitant. There is exactly one assignment of types consistent with all of the information above.\n\n'
            'Give your answer as a single line in exactly this form, listing everyone in the order they were introduced and using only the words knight, knave, spy, alternator:\n'
            + ', '.join(f'{nm}: <type>' for nm in names) + f'\n{ONE}')


def build(entry, log=print):
    level, n, ns, na, seed, mx, weights, cp, dw, dm = entry
    for bump in range(60):
        g = Gen(n, ns, na, seed + 1000 * bump, mx, weights, cp, dw, dm)
        r = g.generate(log=log)
        if r is None:
            log('  attempt failed, next seed')
            continue
        w, stmts = r
        # every statement kind the level asks for must actually appear
        kinds = {st[0] for l in stmts for st in l}
        need = set(k for k in weights if k != 'simple')
        # ... and the level's full nesting depth must be reached
        need |= {f'would{dw}', f'meta{dm}'}
        have = set(kinds)
        have.add(f'would{max(depth_would(st) for l in stmts for st in l)}')
        have.add(f'meta{max(depth_meta(st, stmts) for l in stmts for st in l)}')
        if not need <= have:
            log(f'  missing kinds {need - have}, next seed')
            continue
        break
    else:
        return None
    assert Model(n, ns, na, stmts).other(w) is None
    assert Model(n, ns, na, stmts).any_solution() == w
    names = NAMES[:n]
    prompt = render(n, ns, na, stmts)
    labeled = ', '.join(f'{nm}: {TYPES[t]}' for nm, t in zip(names, w))
    plain = ', '.join(TYPES[t] for t in w)
    kc = {}
    for l in stmts:
        for st in l:
            kc[st[0]] = kc.get(st[0], 0) + 1
    dwo = max(depth_would(st) for l in stmts for st in l)
    dme = max(depth_meta(st, stmts) for l in stmts for st in l)
    nst = sum(map(len, stmts))
    import math
    space = 4 ** n
    allowed = math.comb(n, ns) * math.comb(n - ns, na) * 2 ** (n - ns - na)
    plural = lambda k, one, many: f'{k} {one if k == 1 else many}'
    notes = (f'[level {level}] {n} islanders ({plural(ns, "spy", "spies")}, {plural(na, "alternator", "alternators")}), {nst} statements, nesting depth: "would say" {dwo}, '
             f'statements about statements {dme} (seed {seed + 1000 * bump}). {allowed:,} type assignments obey the stated counts '
             f'(4^{n} = {space:,} without them). Uniqueness: OR-tools CP-SAT finds the intended assignment and proves that no other '
             f'satisfies every speaker; statements were minimised (no removable statement except where another statement refers to it). '
             f'Independently re-verified by truth_ladder/verify.mjs, which parses the English back into logic and counts every consistent '
             f'assignment by exhaustive three-valued backtracking search (exactly one). Statement kinds: {kc}.')
    return dict(id=f'L{level:02d}', prompt=prompt, expected=[labeled, plain], notes=notes, n=n, spies=ns, alternators=na,
                statements=nst, would_depth=dwo, meta_depth=dme, assignments=allowed)


if __name__ == '__main__':
    args = sys.argv[1:]
    out_path = os.path.join(HERE, 'ladder_cases.json')
    if '--out' in args:
        out_path = args[args.index('--out') + 1]
        args = [a for a in args if a not in ('--out', out_path)]
    only = set(args)
    done = json.load(open(out_path)) if os.path.exists(out_path) else []
    have = {c['id'] for c in done}
    for e in PLAN:
        cid = f'L{e[0]:02d}'
        if (only and cid not in only) or (not only and cid in have):
            continue
        print('building', cid, e[1], 'islanders', flush=True)
        c = build(e, log=lambda s: print(s, flush=True))
        if c is None:
            print('  FAILED', flush=True)
            continue
        done = sorted([d for d in done if d['id'] != c['id']] + [c], key=lambda d: d['id'])
        with open(out_path, 'w') as fh:
            json.dump(done, fh, indent=1, ensure_ascii=False)
            fh.write('\n')
        print(f"  {cid}: {c['statements']} statements, depth {c['would_depth']}/{c['meta_depth']} -> {c['expected'][0]}", flush=True)
