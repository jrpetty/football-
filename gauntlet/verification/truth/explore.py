import sys, collections
from gen import *
import truthlib

NAMES7 = ['Ada', 'Bruno', 'Cyra', 'Dov', 'Esme', 'Fitz', 'Gia']
KK = ['knight', 'knave']
KKS = ['knight', 'knave', 'spy']
KKA = ['knight', 'knave', 'alternator']
ALL = ['knight', 'knave', 'spy', 'alternator']

def cnt(t, k):
    return lambda w: sum(1 for x in w if x == t) == k

CFG = {
  'e3': dict(n=3, types=KK, glob=lambda w: True, min_st=1, max_st=1, compound_p=0.2),
  'e4': dict(n=4, types=KK, glob=lambda w: True, min_st=1, max_st=1, compound_p=0.2),
  'm5': dict(n=5, types=KK, glob=lambda w: True, min_st=1, max_st=1, compound_p=0.4),
  'm5s': dict(n=5, types=KKS, glob=cnt('spy', 1), min_st=1, max_st=2, compound_p=0.3),
  'm4a': dict(n=4, types=KKA, glob=lambda w: True, min_st=2, max_st=2, compound_p=0.2, min_each=2),
  'h5a': dict(n=5, types=KKA, glob=lambda w: True, min_st=2, max_st=2, compound_p=0.35, min_each=2),
  'h6': dict(n=6, types=KK, glob=lambda w: True, min_st=1, max_st=1, compound_p=0.5),
  'h6s': dict(n=6, types=KKS, glob=cnt('spy', 1), min_st=1, max_st=2, compound_p=0.4, meta_p=0.15),
  'x6all': dict(n=6, types=ALL, glob=lambda w: cnt('spy', 1)(w) and cnt('alternator', 1)(w), min_st=2, max_st=2, compound_p=0.4, min_each=2),
  'x7a': dict(n=7, types=KKA, glob=lambda w: True, min_st=2, max_st=2, compound_p=0.4, meta_p=0.1, min_each=2),
  'x6s2': dict(n=6, types=KKS, glob=cnt('spy', 2), min_st=1, max_st=2, compound_p=0.45, meta_p=0.1),
  'x7s': dict(n=7, types=KKS, glob=lambda w: sum(1 for x in w if x == 'spy') <= 1, min_st=1, max_st=2, compound_p=0.45, meta_p=0.1),
}

if __name__ == '__main__':
    name = sys.argv[1]
    for seed in range(int(sys.argv[2]), int(sys.argv[3])):
        c = dict(CFG[name])
        n = c.pop('n'); types = c.pop('types'); glob = c.pop('glob')
        r = gen(n, types, glob, seed, **c)
        if r is None:
            print(seed, 'FAIL'); continue
        w, stmts = r
        names = NAMES7[:n]
        truthlib.META_SINGLE.clear()
        for i, l in enumerate(stmts):
            truthlib.META_SINGLE[i] = len(l) == 1
        print('== seed', seed, 'n_stmts', sum(len(l) for l in stmts), w)
        for line in render(names, stmts):
            print('  ', line)
