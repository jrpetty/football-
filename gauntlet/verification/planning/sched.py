"""Exact minimum makespan for non-preemptive precedence-constrained scheduling
with identical workers, where task i needs w[i] workers for its whole duration.

Method 1: event-driven exhaustive search (starts only at time 0 or at a completion time;
          active schedules contain an optimum).
Method 2: serial schedule generation over ALL precedence-feasible task orders
          (each order -> earliest feasible start per task respecting resources); the
          minimum over all orders is the optimum (SGS generates all active schedules).
"""
import itertools, functools


def method1(dur, pred, need, W):
    n = len(dur)
    best = [sum(dur) + 1]
    seen = {}
    def rec(t, done, running):  # running: tuple of (finish_time, task)
        # lower bound
        key = (done, running)
        if key in seen and seen[key] <= t:
            return
        seen[key] = t
        started = {x[1] for x in running}
        avail = [i for i in range(n) if i not in done and i not in started and all(p in done for p in pred[i])]
        used = sum(need[x[1]] for x in running)
        free = W - used
        # enumerate subsets of avail to start now
        subsets = []
        for k in range(len(avail) + 1):
            for sub in itertools.combinations(avail, k):
                if sum(need[i] for i in sub) <= free:
                    subsets.append(sub)
        for sub in subsets:
            nr = tuple(sorted(running + tuple((t + dur[i], i) for i in sub)))
            if not nr:
                continue
            nt = nr[0][0]
            fin = tuple(x for x in nr if x[0] == nt)
            rest = tuple(x for x in nr if x[0] != nt)
            nd = done | frozenset(x[1] for x in fin)
            if len(nd) == n:
                best[0] = min(best[0], nt)
                continue
            # simple bound
            if nt >= best[0]:
                continue
            rec(nt, nd, rest)
    rec(0, frozenset(), ())
    return best[0]


def method2(dur, pred, need, W):
    n = len(dur)
    best = None
    horizon = sum(dur) + 1
    def orders(prefix, remaining):
        if not remaining:
            yield prefix
            return
        for i in sorted(remaining):
            if all(p in prefix_set[0] for p in pred[i]):
                prefix_set[0].add(i)
                yield from orders(prefix + [i], remaining - {i})
                prefix_set[0].discard(i)
    prefix_set = [set()]
    for order in orders([], frozenset(range(n))):
        usage = [0] * (horizon + max(dur) + 1)
        fin = {}
        for i in order:
            t = max([fin[p] for p in pred[i]] + [0])
            while any(usage[x] + need[i] > W for x in range(t, t + dur[i])):
                t += 1
            for x in range(t, t + dur[i]):
                usage[x] += need[i]
            fin[i] = t + dur[i]
        ms = max(fin.values())
        if best is None or ms < best:
            best = ms
    return best


def best_schedule(dur, pred, need, W):
    """SGS over all orders; returns (makespan, {task: start})."""
    n = len(dur)
    best = (None, None)
    horizon = sum(dur) + max(dur) + 2
    def orders(prefix, placed):
        if len(prefix) == n:
            yield prefix
            return
        for i in range(n):
            if i not in placed and all(p in placed for p in pred[i]):
                placed.add(i)
                yield from orders(prefix + [i], placed)
                placed.discard(i)
    for order in orders([], set()):
        usage = [0] * horizon
        st = {}
        for i in order:
            t = max([st[p] + dur[p] for p in pred[i]] + [0])
            while any(usage[x] + need[i] > W for x in range(t, t + dur[i])):
                t += 1
            for x in range(t, t + dur[i]):
                usage[x] += need[i]
            st[i] = t
        ms = max(st[i] + dur[i] for i in range(n))
        if best[0] is None or ms < best[0]:
            best = (ms, dict(st))
    return best
