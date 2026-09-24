import sys, collections, time
from gridlib import generate, Linear, Circle, Grid, count_solutions
from render import render
import themes

CONFIGS = {
    'beach': dict(theme=themes.t_beach, geo=Linear(4), w={'pos': 2, 'notpos': 1, 'same': 2, 'notsame': 2, 'adj': 2, 'left': 1, 'immleft': 2, 'end': 1}, kinds={'xor': 0, 'ofxy': 0, 'ifthen': 0}),
    'boats': dict(theme=themes.t_boats, geo=Linear(4), w={'pos': 1, 'notpos': 1, 'same': 2, 'notsame': 2, 'adj': 2, 'notadj': 1, 'left': 2, 'immleft': 2, 'end': 1}, kinds={'xor': 0, 'ofxy': 0, 'ifthen': 0}),
    'trucks': dict(theme=themes.t_trucks, geo=Linear(5), w={'pos': 1, 'notpos': 1, 'same': 2, 'notsame': 2, 'adj': 3, 'notadj': 2, 'left': 3, 'immleft': 3, 'end': 1, 'dist': 1}, kinds={'xor': 0, 'ofxy': 0, 'ifthen': 0}),
    'floors': dict(theme=themes.t_floors, geo=Linear(5), w={'pos': 1, 'notpos': 1, 'same': 2, 'notsame': 2, 'adj': 3, 'notadj': 2, 'left': 3, 'immleft': 3, 'end': 1, 'dist': 2, 'xor': 1}, kinds={'ofxy': 0, 'ifthen': 0}),
    'talks': dict(theme=themes.t_talks, geo=Linear(5), w={'pos': 1, 'notpos': 1, 'same': 2, 'notsame': 2, 'adj': 3, 'notadj': 2, 'left': 3, 'immleft': 3, 'end': 1, 'dist': 2, 'xor': 2, 'ofxy': 1}, kinds={'ifthen': 0}),
    'gardeners': dict(theme=themes.t_gardeners, geo=Linear(5), w={'pos': 1, 'notpos': 1, 'same': 2, 'notsame': 2, 'adj': 2, 'notadj': 2, 'left': 2, 'immleft': 2, 'numgt': 3, 'numdiff': 3, 'xor': 1}, kinds={'ofxy': 0, 'ifthen': 0}, numeric=True),
    'table': dict(theme=themes.t_table, geo=Circle(6), w={'pos': 1, 'notpos': 1, 'same': 2, 'notsame': 2, 'adj': 3, 'notadj': 2, 'immcw': 3, 'opposite': 3, 'xor': 1}, kinds={'ofxy': 0, 'ifthen': 0}),
    'lockers': dict(theme=themes.t_lockers, geo=Grid(2, 3), w={'pos': 1, 'notpos': 1, 'same': 2, 'notsame': 2, 'adj': 2, 'notadj': 2, 'above': 3, 'gimmleft': 3, 'samerow': 2, 'samecol': 2, 'diffrow': 2, 'xor': 1}, kinds={'ofxy': 0, 'ifthen': 0}),
    'lanes': dict(theme=themes.t_lanes, geo=Linear(6), w={'pos': 1, 'notpos': 1, 'same': 2, 'notsame': 2, 'adj': 3, 'notadj': 2, 'left': 3, 'immleft': 3, 'end': 1, 'dist': 2, 'xor': 2, 'ofxy': 1}, kinds={'ifthen': 0}),
    'lab': dict(theme=themes.t_lab, geo=Linear(6), w={'pos': 0.5, 'notpos': 1, 'same': 1, 'notsame': 2, 'adj': 2, 'notadj': 2, 'left': 2, 'immleft': 2, 'dist': 2, 'numgt': 3, 'numdiff': 3, 'xor': 2, 'ofxy': 2, 'ifthen': 2}, numeric=True),
    'ferries': dict(theme=themes.t_ferries, geo=Linear(6), w={'pos': 0.3, 'notpos': 1, 'same': 0.7, 'notsame': 2, 'adj': 2, 'notadj': 2, 'left': 3, 'immleft': 2, 'dist': 2, 'xor': 3, 'ofxy': 3, 'ifthen': 3}),
    'chess': dict(theme=themes.t_chess, geo=Circle(6), w={'pos': 0.3, 'notpos': 1, 'same': 0.7, 'notsame': 2, 'adj': 2, 'notadj': 2, 'immcw': 3, 'opposite': 3, 'xor': 3, 'ofxy': 3, 'ifthen': 3}),
    'cabins': dict(theme=themes.t_cabins, geo=Grid(3, 2), w={'pos': 0.3, 'notpos': 1, 'same': 0.7, 'notsame': 2, 'adj': 2, 'notadj': 2, 'above': 3, 'gimmleft': 3, 'samerow': 2, 'samecol': 2, 'diffrow': 2, 'xor': 3, 'ofxy': 3, 'ifthen': 3}),
    'train': dict(theme=themes.t_train, geo=Linear(5), w={'pos': 1, 'notpos': 1, 'same': 1.5, 'notsame': 2, 'adj': 3, 'notadj': 2, 'left': 3, 'immleft': 3, 'end': 1, 'dist': 2, 'xor': 2}, kinds={'ofxy': 0, 'ifthen': 0}),
    'gallery': dict(theme=themes.t_gallery, geo=Linear(6), w={'pos': 1, 'notpos': 1, 'same': 2, 'notsame': 2, 'adj': 3, 'notadj': 2, 'left': 3, 'immleft': 3, 'end': 1, 'dist': 2, 'xor': 1}, kinds={'ofxy': 0, 'ifthen': 0}),
}


def build(name, seed):
    cfg = CONFIGS[name]
    th = cfg['theme']()
    numeric = None
    if isinstance(th, tuple):
        th, numeric = th
    cats = list(th.cats.keys())
    n = cfg['geo'].n
    res = generate(n, cats, cfg['geo'], cfg['w'], numeric=numeric, seed=seed, kinds=cfg.get('kinds'))
    return th, cats, res


if __name__ == '__main__':
    name = sys.argv[1]
    seeds = range(int(sys.argv[2]), int(sys.argv[3]))
    for seed in seeds:
        t = time.time()
        th, cats, res = build(name, seed)
        if res is None:
            print(seed, 'FAIL')
            continue
        sol, clues = res
        hist = collections.Counter(c.kind for c in clues)
        print(seed, len(clues), dict(hist), f"{time.time()-t:.1f}s")
