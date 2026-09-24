import sys
from explore import build, CONFIGS
from render import render
import random

def show(name, seed, shuffle_seed=None):
    th, cats, res = build(name, seed)
    sol, clues = res
    geo = CONFIGS[name]['geo']
    order = list(clues)
    random.Random(shuffle_seed if shuffle_seed is not None else seed).shuffle(order)
    lines = [f"{i+1}. {render(c, th, geo)}" for i, c in enumerate(order)]
    n = geo.n
    table = []
    for p in range(n):
        row = []
        for c in cats:
            v = [v for v in range(n) if sol[c][v] == p][0]
            row.append(th.cats[c][v])
        table.append(row)
    return th, cats, sol, order, lines, table

if __name__ == '__main__':
    th, cats, sol, order, lines, table = show(sys.argv[1], int(sys.argv[2]))
    print('\n'.join(lines))
    for p, row in enumerate(table):
        print(p + 1, row)
