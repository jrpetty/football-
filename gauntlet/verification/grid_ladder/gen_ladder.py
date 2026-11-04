"""Generator for reasoning.grid-ladder ("Deduction Grid: Ladder"): ten frozen levels of one logic-grid task.

Reuses the Deduction Grid: Extreme machinery unchanged (grid_extreme/gen.py: random intended solution, random true
clues, OR-tools CP-SAT uniqueness check, greedy minimisation, then an explicit per-clue minimality proof; and
grid_extreme/build.py: the English renderer and prompt layout). Only the scale grows, level by level:

  people (positions) x categories:  4x3, 5x3, 6x4, 7x4, 8x5, 9x5, 9x6, 10x6, 11x7, 12x8
  clue depth: levels 1-2 use only direct clues (same person, position, next to, left of, not ...); levels 3-4 add
  either/or, if-then, betweenness, ordering chains and "of X and Y, one ... the other ..."; from level 5 every
  kind is in play including "exactly k of 3", distances and age arithmetic; levels 8-9 weight the compound kinds most
  heavily and level 10 the weakest kinds (negations, betweenness, either/or, exactly k of 3): each such clue says
  less, so many more are needed (172 at level 10) and they must be combined.

Independent check: grid_extreme/verify_sat.py parses every prompt back from English and enumerates all models of a
from-scratch CNF encoding with a SAT solver (exactly one model, equal to the key); --minimal re-proves every clue
is needed.

    python3 gen_ladder.py            # builds missing levels into ladder_cases.json (resumable)
    python3 gen_ladder.py L03 L07    # rebuilds just those levels
"""
import importlib.util
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
GX = os.path.join(HERE, '..', 'grid_extreme')
sys.path.insert(0, GX)
sys.path.insert(1, os.path.join(HERE, '..'))
from gen import generate, NUM_CAT  # noqa: E402  (grid_extreme/gen.py)

_spec = importlib.util.spec_from_file_location('gx_build', os.path.join(GX, 'build.py'))
gx = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gx)
gx.NUMW.update({9: 'nine', 10: 'ten', 11: 'eleven', 12: 'twelve'})

from ladder_themes import T  # noqa: E402

NUMW = {4: 'four', 5: 'five', 6: 'six', 7: 'seven', 8: 'eight', 9: 'nine', 10: 'ten', 11: 'eleven', 12: 'twelve'}

DIRECT = {'same': 0.35, 'at': 0.15, 'notsame': 1.0, 'notat': 1.0, 'next': 1.2, 'notnext': 1.4, 'left': 1.8, 'imm': 0.8, 'end': 0.4}
LINKED = dict(DIRECT, between=1.4, chain=1.4, xor=1.6, **{'if': 1.6}, ofxy=1.2, dist=0.8)
ALL = dict(gx.WEIGHTS)  # every kind, exactly as Deduction Grid: Extreme
DEEP = dict(ALL, notsame=1.4, notat=1.4, notnext=1.6, xor=3.2, exactly=3.2, **{'if': 3.2}, ofxy=2.0, between=2.0, chain=2.0)
# Level 10: the weakest kinds weighted most (negations, "between", either/or, exactly-k), so each clue says little
# and far more of them are needed and must be combined.
WEAK = dict(ALL, same=0.1, at=0.05, notsame=2.0, notat=1.6, next=0.6, notnext=2.0, left=1.6, imm=0.3, dist=0.6, end=0.2,
            between=2.0, chain=1.2, xor=3.0, exactly=3.0, **{'if': 3.0}, ofxy=1.2, numgt=1.6, numdiff=0.6)
DEPTH_NAME = {id(DIRECT): 'direct clues only', id(LINKED): 'direct + either/or, if-then, between, order, one-and-the-other',
              id(ALL): 'every clue kind', id(DEEP): 'every clue kind, compound clues weighted most',
              id(WEAK): 'every clue kind, the weakest (negations, between, either/or, exactly k) weighted most'}

# level, theme, n, categories (first = people, asked first), second asked category, seed, weights, pool size, check step
PLAN = [
    (1, 'terrace', 4, ['Neighbours', 'Pets', 'Drinks'], 'Pets', 501, DIRECT, 300, 1),
    (2, 'marina', 5, ['Sailors', 'Boat colours', 'Home ports'], 'Home ports', 502, DIRECT, 300, 1),
    (3, 'allotments', 6, ['Gardeners', 'Vegetables', 'Flowers', 'Shed colours'], 'Vegetables', 503, LINKED, 600, 2),
    (4, 'office', 7, ['Colleagues', 'Departments', 'Mug colours', 'Languages'], 'Languages', 504, LINKED, 600, 2),
    (5, 'market', 8, ['Vendors', 'Dishes', 'Lantern colours', 'Home towns', 'Ages'], 'Dishes', 505, ALL, 1200, 4),
    (6, 'terrace', 9, ['Neighbours', 'Pets', 'Car colours', 'Hobbies', 'Ages'], 'Hobbies', 506, ALL, 1500, 4),
    (7, 'marina', 9, ['Sailors', 'Boat colours', 'Home ports', 'Catches', 'Dogs', 'Ages'], 'Catches', 507, ALL, 2000, 4),
    (8, 'allotments', 10, ['Gardeners', 'Vegetables', 'Flowers', 'Tools', 'Lunches', 'Ages'], 'Flowers', 508, DEEP, 3000, 6),
    (9, 'office', 11, ['Colleagues', 'Departments', 'Desk plants', 'Languages', 'Lunches', 'Commutes', 'Ages'], 'Departments', 509, DEEP, 4000, 8),
    (10, 'market', 12, ['Vendors', 'Dishes', 'Lantern colours', 'Home towns', 'Spices', 'Aprons', 'Music', 'Ages'], 'Spices', 510, WEAK, 6000, 8),
]


def theme_for(name, n, cats):
    t = T[name]
    by = {c[0]: c for c in t['cats']}
    intro = t['intro'].format(C=NUMW[n].capitalize(), n=NUMW[n], N=n)
    return dict(place=t['place'], people=t['people'], intro=intro, cats=[by[c] for c in cats])


def build(entry, log=print):
    level, theme, n, cats, second, seed, weights, pool, step = entry
    th = theme_for(theme, n, cats)
    t0 = time.time()
    res = generate(th, n, len(cats), seed, weights, pool_size=pool, check_every=step, log=log)
    if res is None:
        return None
    pz, clues = res
    assert pz.names == cats
    ask = (cats[0], second)
    text, ans, order = gx.prompt_for(pz, clues, ask, seed * 31 + 7)
    kinds = {}
    for c in clues:
        kinds[c[0]] = kinds.get(c[0], 0) + 1
    full = []
    for p in range(n):
        full.append(f"{p + 1}: " + ', '.join(pz.vals[c][[v for v in range(n) if pz.sol[(c, v)] == p][0]] for c in pz.names))
    expected = [', '.join(ans[0]) + '; ' + ', '.join(ans[1]),
                f"{ask[0]}: " + ', '.join(ans[0]) + '; ' + f"{ask[1]}: " + ', '.join(ans[1])]
    import re
    expected += [e2 for e2 in (re.sub(r'(^|(?<=[,;:] ))(a|an|the) ', '', e) for e in expected) if e2 not in expected]
    compound = sum(v for k, v in kinds.items() if k in ('xor', 'exactly', 'if', 'ofxy', 'between', 'chain'))
    notes = (f"[level {level}] {n} positions x {len(cats)} categories = {n * len(cats)} attribute values, {len(clues)} clues "
             f"({compound} compound), clue depth: {DEPTH_NAME[id(weights)]} (theme {theme}, seed {seed}). Uniqueness: OR-tools CP-SAT proves "
             f"that no arrangement other than the intended one satisfies the clues, and minimality is proved clue by clue (removing any "
             f"single clue admits a second arrangement). Independently re-verified by grid_extreme/verify_sat.py, which parses the English "
             f"legend and clues back from this prompt, encodes them as CNF from scratch and enumerates all models with a SAT solver "
             f"(exactly one). Clue kinds: {kinds}. Full solution: " + ' | '.join(full) + '.')
    log(f'  L{level:02d}: {len(clues)} clues in {time.time() - t0:.0f}s')
    return dict(id=f'L{level:02d}', prompt=text, expected=expected, notes=notes, n=n, cats=pz.names, nclues=len(clues),
                compound=compound, kinds=kinds)


if __name__ == '__main__':
    args = sys.argv[1:]
    out_path = os.path.join(HERE, 'ladder_cases.json')
    if '--out' in args:  # e.g. to prove a rebuild is identical
        out_path = args[args.index('--out') + 1]
        args = [a for a in args if a not in ('--out', out_path)]
    only = set(args)
    done = json.load(open(out_path)) if os.path.exists(out_path) else []
    have = {c['id'] for c in done}
    for e in PLAN:
        cid = f'L{e[0]:02d}'
        if (only and cid not in only) or (not only and cid in have):
            continue
        print('building', cid, e[1], e[2], 'x', len(e[3]), flush=True)
        c = build(e, log=lambda s: print(s, flush=True))
        if c is None:
            print('  FAILED', flush=True)
            continue
        done = sorted([d for d in done if d['id'] != c['id']] + [c], key=lambda d: d['id'])
        with open(out_path, 'w') as fh:
            json.dump(done, fh, indent=1, ensure_ascii=False)
            fh.write('\n')
        print(f"  {c['id']}: {c['nclues']} clues -> {c['expected'][0]}", flush=True)
