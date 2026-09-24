"""Builds reasoning.truth-tellers-extreme cases (generation + prompt rendering + metadata)."""
import sys, os, json, time
import numpy as np
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
from common import ONE
from gen import Gen
from lib import stmt_text, NAMES, TYPES, NUMW, KN, KV, SP, AL, Ev

def cnt(t): return lambda W: (W == t).sum(axis=1)

W1 = {'simple': 6, 'parity': 1.2, 'would': 1.6, 'meta': 0.8, 'metacount': 0.8}

PLAN = [
 # id, n, types, glob, glob text, seed, min_st, max_st
 ('y01', 8, [KN, KV, SP, AL], lambda W: (cnt(SP)(W) == 1) & (cnt(AL)(W) == 1), 'Exactly one of them is a spy, and exactly one of them is an alternator.', 101, 2, 3),
 ('y02', 9, [KN, KV, SP, AL], lambda W: (cnt(SP)(W) == 1) & (cnt(AL)(W) <= 1), 'Exactly one of them is a spy, and at most one of them is an alternator.', 102, 2, 3),
 ('y03', 9, [KN, KV, SP], lambda W: cnt(SP)(W) == 2, 'Exactly two of them are spies.', 103, 1, 3),
 ('y04', 9, [KN, KV, AL], lambda W: cnt(AL)(W) >= 1, 'At least one of them is an alternator.', 104, 2, 3),
 ('y05', 9, [KN, KV, SP, AL], lambda W: (cnt(SP)(W) <= 1) & (cnt(AL)(W) == 1), 'At most one of them is a spy, and exactly one of them is an alternator.', 105, 2, 3),
 ('y06', 10, [KN, KV, SP], lambda W: (cnt(SP)(W) % 2 == 0) & (cnt(SP)(W) <= 2), 'The number of spies among them is even and at most two (so it is zero or two).', 106, 2, 3),
 ('y07', 10, [KN, KV, SP, AL], lambda W: (cnt(SP)(W) == 1) & (cnt(AL)(W) <= 2), 'Exactly one of them is a spy, and at most two of them are alternators.', 107, 2, 3),
 ('y08', 10, [KN, KV, SP, AL], lambda W: (cnt(SP)(W) == 2) & (cnt(AL)(W) == 2), 'Exactly two of them are spies, and exactly two of them are alternators.', 108, 2, 3),
 ('y09', 10, [KN, KV, AL], lambda W: (cnt(AL)(W) >= 2) & (cnt(AL)(W) <= 3), 'Two or three of them are alternators.', 109, 2, 3),
 ('y10', 10, [KN, KV, SP, AL], lambda W: (cnt(SP)(W) == 1) & (cnt(AL)(W) == 1), 'Exactly one of them is a spy, and exactly one of them is an alternator.', 110, 2, 3),
]

TYPE_DEF = {
    KN: '- A knight: every statement a knight makes is true.',
    KV: '- A knave: every statement a knave makes is false.',
    SP: '- A spy: each statement a spy makes may be true or false, in any combination.',
    AL: '- An alternator: an alternator\'s statements alternate between true and false in the order they are made. The first may be either true or false, but no two consecutive statements by an alternator have the same truth value.',
}


def kinds_of(stmts):
    out = set()
    for l in stmts:
        for st in l:
            out.add(st[0])
            if st[0] in ('or', 'and', 'if', 'iff'):
                out.add(st[1][0]); out.add(st[2][0])
    return out


def build(entry):
    cid, n, types, glob, gtext, seed, mn, mx = entry
    t0 = time.time()
    g = Gen(n, types, glob, seed, mn, mx, W1, 0.35)
    r = None
    for _ in range(60):
        r = g.generate(tries=400, min_each=2 if AL in types else 1)
        if r is None:
            break
        k = kinds_of(r[1])
        if {'would', 'parity'} <= k and k & {'meta', 'metacount'}:
            break
    else:
        r = None
    if r is None:
        return None
    w, stmts, _ = r
    names = NAMES[:n]
    nst = [len(l) for l in stmts]
    sols = g.solutions(stmts)
    assert len(sols) == 1 and tuple(g.W[sols[0]]) == w
    lines = []
    for s, l in enumerate(stmts):
        if len(l) == 1:
            lines.append(f'{names[s]} says: "{stmt_text(l[0], s, names, nst)}"')
        else:
            parts = ' '.join(f'({i + 1}) "{stmt_text(st, s, names, nst)}"' for i, st in enumerate(l))
            lines.append(f'{names[s]} makes {NUMW[len(l)]} statements, in this order: {parts}')
    k = kinds_of(stmts)
    conv = [
        f'- "Us" always means all {NUMW[n]} of these inhabitants, including the speaker. "Me" and "I" always mean the speaker.',
        '- "Or" is inclusive: "P, or Q" is true when at least one of P and Q is true. "P, and Q" is true only when both are true.',
        '- "If P, then Q" is false only when P is true and Q is false; otherwise it is true.',
        '- "P if and only if Q" is true exactly when P and Q are both true or both false.',
        '- "X and Y are the same type" / "different types" compare the two people\'s types.',
        '- "Among X, Y and me, exactly two are knights" counts only the people named.',
    ]
    if AL in types:
        conv.append('- All alternators count as the same type, whichever truth value their first statement has. So "X and Y are the same type" is true when both are alternators, and counts of alternators include every alternator.')
    if 'parity' in k:
        conv.append('- "The number of spies among X, Y and Z is even" counts only the people named; zero counts as even.')
    if 'would' in k:
        others = 'a spy or an alternator (they have' if AL in types else 'a spy (spies have'
        conv.append(f'- "X would say that P" is shorthand for: X is a knight and P is true, or X is a knave and P is false. It is therefore false whenever X is {others} no fixed answer). P itself never mentions the speaker or X.')
    if 'meta' in k:
        conv.append('- "Ada\'s second statement is true" refers to Ada\'s statement with that number in the list below ("Ada\'s statement" if she made only one), and is true exactly when that statement is true.')
    if 'metacount' in k:
        conv.append('- "Exactly one of Ada\'s statements is true", "All of Ada\'s statements are true" and "All of Ada\'s statements are false" count the truth values of all of Ada\'s statements listed below.')
    who = ', '.join(names[:-1]) + ' and ' + names[-1]
    tw = ', '.join(TYPES[t] for t in types)
    prompt = ('On a certain island, every inhabitant is exactly one of these types:\n' + '\n'.join(TYPE_DEF[t] for t in types) + '\n\n'
              f'You meet {NUMW[n]} inhabitants: {who}. Each of them knows the type of everyone present. {gtext}\n\n'
              'Conventions:\n' + '\n'.join(conv) + '\n\nStatements:\n' + '\n'.join(lines) + '\n\n'
              'Determine the type of every inhabitant. There is exactly one assignment of types consistent with all of the information above.\n\n'
              f'Give your answer as a single line in exactly this form, listing everyone in the order they were introduced and using only the words {tw}:\n'
              + ', '.join(f'{nm}: <type>' for nm in names) + f'\n{ONE}')
    labeled = ', '.join(f'{nm}: {TYPES[t]}' for nm, t in zip(names, w))
    plain = ', '.join(TYPES[t] for t in w)
    kinds_count = {}
    for l in stmts:
        for st in l:
            kinds_count[st[0]] = kinds_count.get(st[0], 0) + 1
    notes = (f'[extreme] {n} islanders, {sum(nst)} statements (seed {seed}). Uniqueness by exhaustive vectorised enumeration of all {g.W.shape[0]} '
             f'type assignments allowed by the global rule (numpy, truth_extreme/gen.py): exactly one satisfies every speaker constraint; statements were '
             f'minimised (no removable statement except where another statement refers to it). Independently re-verified by truth_extreme/verify.mjs, '
             f'which parses the English back into logic and brute-forces every assignment of the {len(types)} types. Statement kinds: {kinds_count}. '
             f'Built in {time.time() - t0:.0f}s.')
    return dict(id=cid, prompt=prompt, expected=[labeled, plain], notes=notes, n=n, nst=sum(nst))


if __name__ == '__main__':
    out = []
    for e in PLAN:
        c = None
        for bump in range(6):  # if a seed yields no qualifying puzzle, try the next seed (recorded in the notes)
            c = build(e[:5] + (e[5] + 1000 * bump,) + e[6:])
            if c is not None:
                break
        if c is None:
            print(e[0], 'FAILED', flush=True); continue
        out.append(c)
        print(c['id'], c['n'], 'islanders', c['nst'], 'statements ->', c['expected'][0], flush=True)
    json.dump(out, open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'cases.json'), 'w'), indent=1, ensure_ascii=False)
