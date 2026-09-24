from sched import *
import random, time
def greedy(dur,pred,need,W):
    # list scheduling by longest remaining path (critical path priority), start whenever possible at event times
    n=len(dur)
    succ=[[j for j in range(n) if i in pred[j]] for i in range(n)]
    import functools
    @functools.lru_cache(None)
    def tail(i): return dur[i]+max([tail(j) for j in succ[i]]+[0])
    t=0; done=set(); running=[]; startd=set()
    while len(done)<n:
        avail=sorted([i for i in range(n) if i not in startd and all(p in done for p in pred[i])], key=lambda i:-tail(i))
        free=W-sum(need[i] for _,i in running)
        for i in avail:
            if need[i]<=free:
                running.append((t+dur[i],i)); startd.add(i); free-=need[i]
        running.sort(); nt=running[0][0]
        for f,i in [x for x in running if x[0]==nt]: done.add(i)
        running=[x for x in running if x[0]!=nt]; t=nt
    return t
def solve(name,dur,pred,need,W,check2=True):
    t=time.time(); m1=method1(dur,pred,need,W); t1=time.time()-t
    m2=method2(dur,pred,need,W) if check2 else None
    print(name,'opt',m1,'sgs',m2,'greedyCP',greedy(dur,pred,need,W),'LB work',sum(d*w for d,w in zip(dur,need))/W, f'{t1:.1f}s')
