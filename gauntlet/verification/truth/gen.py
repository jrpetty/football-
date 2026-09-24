import random, sys, itertools, json
from truthlib import *


GLOB_WORLDS = []

def canon(st):
    if st[0] in ('same', 'diff'):
        return (st[0], min(st[1], st[2]), max(st[1], st[2]))
    if st[0] in ('or', 'and', 'if', 'iff'):
        return (st[0], canon(st[1]), canon(st[2]))
    return st

def trivial(st, n, types, stmts):
    ws = GLOB_WORLDS
    vals = {ev(st, w, stmts) for w in ws}
    if len(vals) < 2:
        return True
    if st[0] in ('or', 'and', 'if', 'iff'):
        a = [ev(st[1], w, stmts) for w in ws]
        b = [ev(st[2], w, stmts) for w in ws]
        if len(set(a)) < 2 or len(set(b)) < 2:
            return True
        if a == b or all(x != y for x, y in zip(a, b)):
            return True
        # both atoms about the very same single person -> awkward
        def people(at):
            if at[0] in ('is', 'isnot'):
                return {at[1]}
            if at[0] in ('same', 'diff'):
                return {at[1], at[2]}
            return {'*'}
        if people(st[1]) == people(st[2]) and len(people(st[1])) == 1:
            return True
    return False


def gen(n, types, glob, seed, min_st=1, max_st=1, compound_p=0.35, meta_p=0.0, count_ok=True,
        tries=4000, sol_filter=None, minimise=True, min_each=1):
    rng = random.Random(seed)
    worlds = [w for w in itertools.product(types, repeat=n) if glob(w)]
    GLOB_WORLDS[:] = worlds
    for attempt in range(tries):
        w = rng.choice(worlds)
        if sol_filter and not sol_filter(w):
            continue
        stmts = [[] for _ in range(n)]
        # truth pattern
        pats = []
        for s in range(n):
            k = rng.randint(min_st, max_st)
            t = w[s]
            if t == 'knight':
                pat = [True] * k
            elif t == 'knave':
                pat = [False] * k
            elif t == 'alternator':
                st0 = rng.random() < 0.5
                pat = [st0 if i % 2 == 0 else (not st0) for i in range(k)]
            else:
                pat = [rng.random() < 0.5 for _ in range(k)]
            pats.append(pat)
        ok = True
        for s in range(n):
            for i, want in enumerate(pats[s]):
                for _ in range(500):
                    if meta_p and rng.random() < meta_p:
                        # meta: refer to a non-meta statement of an earlier speaker
                        cands = [(s2, j) for s2 in range(n) if s2 != s for j in range(len(stmts[s2])) if stmts[s2][j][0] != 'meta']
                        if not cands:
                            continue
                        s2, j = rng.choice(cands)
                        st = ('meta', s2, j, rng.random() < 0.5)
                    else:
                        st = canon(rand_stmt(rng, n, s, types, compound_p=compound_p, count_ok=count_ok))
                    if st in stmts[s]:
                        continue
                    stmts[s].append(st)
                    vec = tuple(ev(st, x, stmts) for x in worlds)
                    dup = False
                    for other in stmts[s][:-1]:
                        ov = tuple(ev(other, x, stmts) for x in worlds)
                        if ov == vec or all(a != b for a, b in zip(ov, vec)):
                            dup = True
                    good = (not dup) and ev(st, w, stmts) == want and not trivial(st, n, types, stmts)
                    if good:
                        break
                    stmts[s].pop()
                else:
                    ok = False
                    break
            if not ok:
                break
        if not ok:
            continue
        sols = [x for x in worlds if consistent(x, stmts)]
        if len(sols) != 1:
            continue
        assert sols[0] == w
        if minimise:
            changed = True
            while changed:
                changed = False
                for s in range(n):
                    if len(stmts[s]) <= min_each:
                        continue
                    for i in range(len(stmts[s])):
                        trial = [list(l) for l in stmts]
                        removed = trial[s].pop(i)
                        # don't break meta references
                        if any(m[0] == 'meta' and m[1] == s for l in trial for m in l):
                            continue
                        if any(ev_is_meta_to(trial) for _ in [0]):
                            pass
                        sols2 = [x for x in worlds if consistent(x, trial)]
                        if len(sols2) == 1:
                            stmts = trial
                            changed = True
                            break
                    if changed:
                        break
        return w, stmts
    return None


def ev_is_meta_to(stmts):
    return False


def hardness(n, types, glob, stmts):
    """Number of worlds consistent with global constraint alone and number surviving with each statement set dropped."""
    worlds = [w for w in itertools.product(types, repeat=n) if glob(w)]
    return len(worlds)


def render(names, stmts):
    lines = []
    for s, lst in enumerate(stmts):
        if len(lst) == 1:
            lines.append(f"{names[s]} says: \"{stmt_text(lst[0], s, names)}\"")
        else:
            parts = ' '.join(f"({i+1}) \"{stmt_text(st, s, names)}\"" for i, st in enumerate(lst))
            lines.append(f"{names[s]} makes {NUMW[len(lst)]} statements, in this order: {parts}")
    return lines
