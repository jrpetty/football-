"""Knights / knaves / spies / alternators: generator + exhaustive verifier.

World = tuple of types, one per islander.  A statement is a nested tuple AST
evaluated against the world (and, for meta statements, against other
statements).  Speaker constraints:
  knight      -> every statement true
  knave       -> every statement false
  spy         -> no constraint
  alternator  -> consecutive statements differ in truth value
Global constraints (e.g. exactly one spy) are python predicates on the world.
"""
import itertools, random

ART = {'knight': 'a knight', 'knave': 'a knave', 'spy': 'a spy', 'alternator': 'an alternator'}
PL = {'knight': 'knights', 'knave': 'knaves', 'spy': 'spies', 'alternator': 'alternators'}
META_SINGLE = {}
NUMW = {0: 'zero', 1: 'one', 2: 'two', 3: 'three', 4: 'four', 5: 'five', 6: 'six', 7: 'seven'}


def ev(st, w, stmts):
    k = st[0]
    if k == 'is':
        return w[st[1]] == st[2]
    if k == 'isnot':
        return w[st[1]] != st[2]
    if k == 'same':
        return w[st[1]] == w[st[2]]
    if k == 'diff':
        return w[st[1]] != w[st[2]]
    if k == 'count':  # ('count', type, op, k, subset or None)
        _, t, op, kk, sub = st
        idx = range(len(w)) if sub is None else sub
        c = sum(1 for i in idx if w[i] == t)
        return {'eq': c == kk, 'ge': c >= kk, 'le': c <= kk}[op]
    if k == 'parity':  # ('parity', type, 'odd'|'even')
        c = sum(1 for x in w if x == st[1])
        return (c % 2 == 1) == (st[2] == 'odd')
    if k == 'or':
        return ev(st[1], w, stmts) or ev(st[2], w, stmts)
    if k == 'and':
        return ev(st[1], w, stmts) and ev(st[2], w, stmts)
    if k == 'if':
        return (not ev(st[1], w, stmts)) or ev(st[2], w, stmts)
    if k == 'iff':
        return ev(st[1], w, stmts) == ev(st[2], w, stmts)
    if k == 'meta':  # ('meta', speaker, index, True/False) "speaker's statement #index is true/false"
        _, s, i, val = st
        return ev(stmts[s][i], w, stmts) == val
    raise ValueError(k)


def consistent(w, stmts):
    for s, lst in enumerate(stmts):
        vals = [ev(st, w, stmts) for st in lst]
        t = w[s]
        if t == 'knight' and not all(vals):
            return False
        if t == 'knave' and any(vals):
            return False
        if t == 'alternator' and any(vals[i] == vals[i + 1] for i in range(len(vals) - 1)):
            return False
    return True


def solutions(n, types, stmts, glob):
    out = []
    for w in itertools.product(types, repeat=n):
        if glob(w) and consistent(w, stmts):
            out.append(w)
    return out


# ---------------------------------------------------------------- rendering
def atom_text(st, speaker, names):
    k = st[0]
    def N(i):
        return 'I' if i == speaker else names[i]
    if k in ('is', 'isnot'):
        i, t = st[1], st[2]
        if i == speaker:
            return f"I am {'not ' if k == 'isnot' else ''}{ART[t]}"
        return f"{names[i]} is {'not ' if k == 'isnot' else ''}{ART[t]}"
    if k in ('same', 'diff'):
        a, b = st[1], st[2]
        if b == speaker:
            a, b = b, a
        word = 'the same type' if k == 'same' else 'different types'
        if a == speaker:
            return f"{names[b]} and I are {word}"
        return f"{names[a]} and {names[b]} are {word}"
    if k == 'count':
        _, t, op, kk, sub = st
        opw = {'eq': 'exactly', 'ge': 'at least', 'le': 'at most'}[op]
        if sub is None:
            if kk == 0 and op == 'eq':
                return f"none of us is {ART[t]}"
            if kk == 1:
                return f"{opw} one of us is {ART[t]}"
            return f"{opw} {NUMW[kk]} of us are {PL[t]}"
        members = [N(i) for i in sub]
        if 'I' in members:
            members.remove('I')
            members.append('me')
        lst = ', '.join(members[:-1]) + ' and ' + members[-1] if len(members) > 2 else ' and '.join(members)
        if kk == 0 and op == 'eq':
            return f"among {lst}, none is {ART[t]}"
        if kk == 1:
            return f"among {lst}, {opw} one is {ART[t]}"
        return f"among {lst}, {opw} {NUMW[kk]} are {PL[t]}"
    if k == 'parity':
        return f"the number of {PL[st[1]]} among us is {st[2]}"
    if k == 'meta':
        _, s, i, val = st
        who = 'my' if s == speaker else f"{names[s]}'s"
        if META_SINGLE.get(s):
            return f"{who} statement is {'true' if val else 'false'}"
        ordw = ['first', 'second', 'third'][i]
        return f"{who} {ordw} statement is {'true' if val else 'false'}"
    raise ValueError(k)


def cap(s):
    return s[0].upper() + s[1:]


def stmt_text(st, speaker, names):
    k = st[0]
    if k == 'or':
        return f"{cap(atom_text(st[1], speaker, names))}, or {atom_text(st[2], speaker, names)}."
    if k == 'and':
        return f"{cap(atom_text(st[1], speaker, names))}, and {atom_text(st[2], speaker, names)}."
    if k == 'if':
        return f"If {atom_text(st[1], speaker, names)}, then {atom_text(st[2], speaker, names)}."
    if k == 'iff':
        return f"{cap(atom_text(st[1], speaker, names))} if and only if {atom_text(st[2], speaker, names)}."
    return cap(atom_text(st, speaker, names)) + '.'


# ---------------------------------------------------------------- random statements
def rand_atom(rng, n, speaker, types, allow_self=True, count_ok=True):
    r = rng.random()
    others = [i for i in range(n) if i != speaker]
    pool_i = list(range(n)) if allow_self else others
    if r < 0.40:
        i = rng.choice(pool_i)
        t = rng.choice(types)
        return ('is' if rng.random() < 0.75 else 'isnot', i, t)
    if r < 0.70:
        a, b = rng.sample(range(n), 2)
        if not allow_self and speaker in (a, b):
            a, b = rng.sample(others, 2)
        return ('same' if rng.random() < 0.5 else 'diff', a, b)
    if count_ok:
        t = rng.choice(types)
        if rng.random() < 0.5:
            sub = None
            kk = rng.randint(0, min(n, 4))
        else:
            sz = rng.randint(2, min(4, n))
            sub = tuple(sorted(rng.sample(range(n), sz)))
            kk = rng.randint(0, sz - 1) if sz > 1 else 1
            kk = max(kk, 1)
        op = rng.choice(['eq', 'eq', 'ge', 'le'])
        if op == 'le' and kk == 0:
            op = 'eq'
        if op == 'ge' and kk == 0:
            kk = 1
        return ('count', t, op, kk, sub)
    return ('is', rng.choice(pool_i), rng.choice(types))


def rand_stmt(rng, n, speaker, types, compound_p=0.35, count_ok=True):
    if rng.random() < compound_p:
        op = rng.choice(['or', 'and', 'if', 'if', 'iff'])
        a = rand_atom(rng, n, speaker, types, count_ok=False)
        b = rand_atom(rng, n, speaker, types, count_ok=count_ok)
        return (op, a, b)
    return rand_atom(rng, n, speaker, types, count_ok=count_ok)
