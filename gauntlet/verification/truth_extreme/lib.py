"""Truth-tellers EXTREME: statement ASTs, vectorised evaluation over every world, rendering.

Types: 0 knight (all statements true), 1 knave (all false), 2 spy (anything), 3 alternator (consecutive statements
alternate truth values). A world is one type per islander. Every statement is evaluated as a boolean vector over ALL
worlds allowed by the global rule (numpy), so uniqueness = exactly one world survives the speaker constraints.
"""
import itertools
import numpy as np

TYPES = ['knight', 'knave', 'spy', 'alternator']
KN, KV, SP, AL = 0, 1, 2, 3
ART = {0: 'a knight', 1: 'a knave', 2: 'a spy', 3: 'an alternator'}
PL = {0: 'knights', 1: 'knaves', 2: 'spies', 3: 'alternators'}
NUMW = {0: 'zero', 1: 'one', 2: 'two', 3: 'three', 4: 'four', 5: 'five', 6: 'six', 7: 'seven', 8: 'eight', 9: 'nine', 10: 'ten'}
ORD = ['first', 'second', 'third']
NAMES = ['Ada', 'Bruno', 'Cyra', 'Dov', 'Esme', 'Fitz', 'Gia', 'Hugo', 'Iris', 'Jonah']


def worlds(n, types, glob):
    W = np.array(list(itertools.product(types, repeat=n)), dtype=np.int8)
    return W[glob(W)]


class Ev:
    """Evaluates statement ASTs to boolean vectors over the world matrix W."""
    def __init__(self, W, stmts):
        self.W = W
        self.stmts = stmts  # list (per speaker) of list of ASTs
        self.cache = {}

    def v(self, st):
        key = st
        if key in self.cache:
            return self.cache[key]
        W = self.W
        k = st[0]
        if k == 'is': r = W[:, st[1]] == st[2]
        elif k == 'isnot': r = W[:, st[1]] != st[2]
        elif k == 'same': r = W[:, st[1]] == W[:, st[2]]
        elif k == 'diff': r = W[:, st[1]] != W[:, st[2]]
        elif k == 'count':  # ('count', t, op, k, subset|None)
            _, t, op, kk, sub = st
            cols = list(range(W.shape[1])) if sub is None else list(sub)
            c = (W[:, cols] == t).sum(axis=1)
            r = {'eq': c == kk, 'ge': c >= kk, 'le': c <= kk}[op]
        elif k == 'parity':  # ('parity', t, subset, 'odd'|'even')
            c = (W[:, list(st[2])] == st[1]).sum(axis=1)
            r = (c % 2 == 1) if st[3] == 'odd' else (c % 2 == 0)
        elif k == 'would':  # ('would', x, inner): x is a knight and inner true, or x is a knave and inner false
            inner = self.v(st[2])
            r = ((W[:, st[1]] == KN) & inner) | ((W[:, st[1]] == KV) & ~inner)
        elif k == 'meta':  # ('meta', s, i, val)
            r = self.v(self.stmts[st[1]][st[2]]) == st[3]
        elif k == 'metacount':  # ('metacount', s, k): exactly k of s's statements are true
            vs = [self.v(x) for x in self.stmts[st[1]]]
            r = np.sum(vs, axis=0) == st[2]
        elif k == 'or': r = self.v(st[1]) | self.v(st[2])
        elif k == 'and': r = self.v(st[1]) & self.v(st[2])
        elif k == 'if': r = (~self.v(st[1])) | self.v(st[2])
        elif k == 'iff': r = self.v(st[1]) == self.v(st[2])
        else:
            raise ValueError(st)
        self.cache[key] = r
        return r

    def consistent(self):
        W = self.W
        ok = np.ones(W.shape[0], dtype=bool)
        for s, lst in enumerate(self.stmts):
            if not lst:
                continue
            vals = [self.v(st) for st in lst]
            allt = np.logical_and.reduce(vals)
            nonet = ~np.logical_or.reduce(vals)
            alt = np.ones(W.shape[0], dtype=bool)
            for i in range(len(vals) - 1):
                alt &= vals[i] != vals[i + 1]
            t = W[:, s]
            ok &= np.where(t == KN, allt, np.where(t == KV, nonet, np.where(t == AL, alt, True)))
        return ok


def refs(st):
    """Speakers whose statements st refers to (meta / metacount)."""
    k = st[0]
    if k in ('meta', 'metacount'): return {st[1]}
    if k in ('or', 'and', 'if', 'iff'): return refs(st[1]) | refs(st[2])
    if k == 'would': return refs(st[2])
    return set()


# ------------------------------------------------------------------------ rendering
def join_names(items):
    return items[0] if len(items) == 1 else ', '.join(items[:-1]) + ' and ' + items[-1]


def atom_text(st, sp, names, nst):
    k = st[0]
    me = lambda i: 'I' if i == sp else names[i]
    if k in ('is', 'isnot'):
        i, t = st[1], st[2]
        neg = 'not ' if k == 'isnot' else ''
        return f"I am {neg}{ART[t]}" if i == sp else f"{names[i]} is {neg}{ART[t]}"
    if k in ('same', 'diff'):
        a, b = st[1], st[2]
        if b == sp: a, b = b, a
        w = 'the same type' if k == 'same' else 'different types'
        return f"{names[b]} and I are {w}" if a == sp else f"{names[a]} and {names[b]} are {w}"
    if k == 'count':
        _, t, op, kk, sub = st
        opw = {'eq': 'exactly', 'ge': 'at least', 'le': 'at most'}[op]
        if sub is None:
            if kk == 0 and op == 'eq': return f"none of us is {ART[t]}"
            if kk == 1: return f"{opw} one of us is {ART[t]}"
            return f"{opw} {NUMW[kk]} of us are {PL[t]}"
        mem = [names[i] for i in sub if i != sp] + (['me'] if sp in sub else [])
        if kk == 0 and op == 'eq': return f"among {join_names(mem)}, none is {ART[t]}"
        if kk == 1: return f"among {join_names(mem)}, {opw} one is {ART[t]}"
        return f"among {join_names(mem)}, {opw} {NUMW[kk]} are {PL[t]}"
    if k == 'parity':
        mem = [names[i] for i in st[2] if i != sp] + (['me'] if sp in st[2] else [])
        return f"the number of {PL[st[1]]} among {join_names(mem)} is {st[3]}"
    if k == 'would':
        return f"{names[st[1]]} would say that {atom_text(st[2], sp, names, nst)}"
    if k == 'meta':
        who = 'my' if st[1] == sp else f"{names[st[1]]}'s"
        if nst[st[1]] == 1:
            return f"{who} statement is {'true' if st[3] else 'false'}"
        return f"{who} {ORD[st[2]]} statement is {'true' if st[3] else 'false'}"
    if k == 'metacount':
        who = names[st[1]]
        m = nst[st[1]]
        if st[2] == 0: return f"all of {who}'s statements are false"
        if st[2] == m: return f"all of {who}'s statements are true"
        return f"exactly {NUMW[st[2]]} of {who}'s statements {'is' if st[2] == 1 else 'are'} true"
    raise ValueError(st)


def cap(s):
    return s[0].upper() + s[1:]


def stmt_text(st, sp, names, nst):
    k = st[0]
    if k == 'or': return f"{cap(atom_text(st[1], sp, names, nst))}, or {atom_text(st[2], sp, names, nst)}."
    if k == 'and': return f"{cap(atom_text(st[1], sp, names, nst))}, and {atom_text(st[2], sp, names, nst)}."
    if k == 'if': return f"If {atom_text(st[1], sp, names, nst)}, then {atom_text(st[2], sp, names, nst)}."
    if k == 'iff': return f"{cap(atom_text(st[1], sp, names, nst))} if and only if {atom_text(st[2], sp, names, nst)}."
    return cap(atom_text(st, sp, names, nst)) + '.'
