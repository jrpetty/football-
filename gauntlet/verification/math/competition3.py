from fractions import Fraction as F
from itertools import product, combinations, permutations
from functools import lru_cache
import math, random
R={}
# X5: bag 5 red 7 blue, draw without replacement until 3 of one colour drawn. Expected draws.
@lru_cache(None)
def E(r,b,dr,db):
    # r,b remaining; dr,db drawn so far
    if dr==3 or db==3: return F(0)
    tot=r+b
    res=F(1)
    if r: res+=F(r,tot)*E(r-1,b,dr+1,db)
    if b: res+=F(b,tot)*E(r,b-1,dr,db+1)
    return res
e=E(5,7,0,0)
R['X5']=e.numerator+e.denominator; R['X5_frac']=e
# brute force over all C(12,5) arrangements
tot=0; cnt=0
for reds in combinations(range(12),5):
    s=set(reds); dr=db=0
    for i in range(12):
        if i in s: dr+=1
        else: db+=1
        if dr==3 or db==3:
            tot+=i+1; break
    cnt+=1
assert F(tot,cnt)==e
# X6: 4x6 0/1 matrices, each row sum 3, each column sum 2
rows=[r for r in product((0,1),repeat=6) if sum(r)==3]
c=0
for a in rows:
    for b in rows:
        for cc in rows:
            s=[a[i]+b[i]+cc[i] for i in range(6)]
            if max(s)>2: continue
            need=tuple(2-x for x in s)
            if sum(need)==3 and all(x in (0,1) for x in need): c+=1
R['X6']=c
# X4: ordered sums of 1,2,3 totalling 20 with no two consecutive 3s
@lru_cache(None)
def f(n,last3):
    if n==0: return 1
    t=0
    for p in (1,2,3):
        if p>n: continue
        if p==3 and last3: continue
        t+=f(n-p,p==3)
    return t
R['X4']=f(20,False)
# brute check by enumeration of compositions for n=20 (via recursion listing) - count directly
def comps(n):
    if n==0: yield (); return
    for p in (1,2,3):
        if p<=n:
            for rest in comps(n-p): yield (p,)+rest
R['X4_bf']=sum(1 for c in comps(20) if all(not (c[i]==3 and c[i+1]==3) for i in range(len(c)-1)))
print(R)
