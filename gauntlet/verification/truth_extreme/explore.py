import sys, time
import numpy as np
from gen import Gen
from lib import stmt_text, NAMES, TYPES, NUMW, KN, KV, SP, AL

def cnt(t): return lambda W: (W == t).sum(axis=1)
CFG = {
 't8a':  dict(n=8,  types=[KN, KV, SP, AL], glob=lambda W: (cnt(SP)(W) == 1) & (cnt(AL)(W) == 1), min_st=2, max_st=3, text='Exactly one of them is a spy and exactly one of them is an alternator.'),
 't9s2': dict(n=9,  types=[KN, KV, SP], glob=lambda W: cnt(SP)(W) == 2, min_st=1, max_st=3, text='Exactly two of them are spies.'),
 't9a':  dict(n=9,  types=[KN, KV, AL], glob=lambda W: cnt(AL)(W) >= 1, min_st=2, max_st=3, text='At least one of them is an alternator.'),
 't10e': dict(n=10, types=[KN, KV, SP], glob=lambda W: cnt(SP)(W) % 2 == 0, min_st=2, max_st=3, text='The number of spies among them is even (it may be zero).'),
 't10all': dict(n=10, types=[KN, KV, SP, AL], glob=lambda W: (cnt(SP)(W) == 2) & (cnt(AL)(W) == 2), min_st=2, max_st=3, text='Exactly two of them are spies and exactly two of them are alternators.'),
 't9le': dict(n=9, types=[KN, KV, SP, AL], glob=lambda W: (cnt(SP)(W) <= 1) & (cnt(AL)(W) == 1), min_st=2, max_st=3, text='At most one of them is a spy, and exactly one of them is an alternator.'),
 't10s1': dict(n=10, types=[KN, KV, SP, AL], glob=lambda W: (cnt(SP)(W) == 1) & (cnt(AL)(W) <= 2), min_st=2, max_st=3, text='Exactly one of them is a spy, and at most two of them are alternators.'),
 't8odd': dict(n=8, types=[KN, KV, SP, AL], glob=lambda W: (cnt(SP)(W) % 2 == 1) & (cnt(AL)(W) == 1), min_st=2, max_st=3, text='The number of spies among them is odd, and exactly one of them is an alternator.'),
}
W1 = {'simple': 6, 'parity': 1, 'would': 1.5, 'meta': 0.8, 'metacount': 0.8}

def run(name, seed, compound_p=0.35, verbose=True):
    c = CFG[name]
    t0 = time.time()
    g = Gen(c['n'], c['types'], c['glob'], seed, c['min_st'], c['max_st'], W1, compound_p)
    r = None
    for _ in range(40):
        r = g.generate(tries=400, min_each=2 if AL in c['types'] else 1)
        if r is None:
            break
        kinds = {x[0] for l in r[1] for st in l for x in [st] + ([st[1], st[2]] if st[0] in ('or', 'and', 'if', 'iff') else [])}
        if {'would', 'parity'} <= kinds and kinds & {'meta', 'metacount'}:
            break
    else:
        r = None
    if r is None:
        print(name, seed, 'FAIL', f'{time.time()-t0:.0f}s'); return None
    w, stmts, a = r
    nst = [len(l) for l in stmts]
    if verbose:
        print(f'== {name} seed {seed} worlds={g.W.shape[0]} attempts={a} statements={sum(nst)} time={time.time()-t0:.0f}s', [TYPES[t] for t in w])
        for s, l in enumerate(stmts):
            print('  ', NAMES[s], ' | '.join(stmt_text(st, s, NAMES, nst) for st in l))
    return g, w, stmts

if __name__ == '__main__':
    for seed in range(int(sys.argv[2]), int(sys.argv[3])):
        run(sys.argv[1], seed)
