# Second independent oracle for c3 (any size).
#  * which palindromes count: first occurrences from an eertree of s, LAST occurrences from an eertree of reversed(s)
#    (a palindrome's first occurrence in reversed(s) mirrors its last occurrence in s); the two trees are matched node by
#    node by walking identical (parent, character) edges. No suffix-link propagation is used.
#  * total occurrences: Manacher radii + polynomial hashing. The counted palindromes centred at one centre form a prefix of
#    radii (removing the outer letters of a counted palindrome keeps it counted), so the number of counted occurrences at a
#    centre is found by binary search with hash-set lookups; summing over all centres counts every occurrence exactly once.
import sys, json, random
MOD = (1 << 61) - 1
def eertree(s):
    L = [-1, 0]; link = [0, 0]; created = [-1, -1]; nxt = [{}, {}]
    cur = 1
    for i, c in enumerate(s):
        v = cur
        while not (i - L[v] - 1 >= 0 and s[i - L[v] - 1] == c): v = link[v]
        if c in nxt[v]:
            cur = nxt[v][c]; continue
        node = len(L); L.append(L[v] + 2); created.append(i); nxt.append({})
        if L[node] == 1: link.append(1)
        else:
            w = link[v]
            while not (i - L[w] - 1 >= 0 and s[i - L[w] - 1] == c): w = link[w]
            link.append(nxt[w][c])
        nxt[v][c] = node; cur = node
    return L, created, nxt
def solve(parts):
    s = ''.join(t * k for t, k in parts); n = len(s)
    B = random.Random(12345).randrange(10**6, MOD - 1)
    pw = [1] * (n + 2)
    for i in range(1, n + 2): pw[i] = pw[i - 1] * B % MOD
    pre = [0] * (n + 1)
    for i, c in enumerate(s): pre[i + 1] = (pre[i] * B + ord(c)) % MOD
    def sub(l, r):  # hash of s[l:r]
        return (pre[r] - pre[l] * pw[r - l]) % MOD
    L1, c1, x1 = eertree(s); L2, c2, x2 = eertree(s[::-1])
    assert len(L1) == len(L2)
    counted = set(); count = 0; longest = 0
    stack = [(0, 0), (1, 1)]
    while stack:
        a, b = stack.pop()
        if a >= 2:
            first_start = c1[a] - L1[a] + 1
            last_start = n - 1 - c2[b]
            if last_start - first_start >= L1[a]:
                count += 1; longest = max(longest, L1[a])
                counted.add((L1[a], sub(first_start, first_start + L1[a])))
        for ch, a2 in x1[a].items():
            stack.append((a2, x2[b][ch]))
    # Manacher (odd radii d1: palindrome s[i-k+1 .. i+k-1] for k<=d1[i]; even d2: s[i-k .. i+k-1] for k<=d2[i])
    d1 = [0] * n; l, r = 0, -1
    for i in range(n):
        k = 1 if i > r else min(d1[l + r - i], r - i + 1)
        while i - k >= 0 and i + k < n and s[i - k] == s[i + k]: k += 1
        d1[i] = k
        if i + k - 1 > r: l, r = i - k + 1, i + k - 1
    d2 = [0] * n; l, r = 0, -1
    for i in range(n):
        k = 0 if i > r else min(d2[l + r - i + 1], r - i + 1)
        while i - k - 1 >= 0 and i + k < n and s[i - k - 1] == s[i + k]: k += 1
        d2[i] = k
        if i + k - 1 > r: l, r = i - k, i + k - 1
    total = 0
    for i in range(n):
        lo, hi = 0, d1[i]           # largest k with odd palindrome of half-size k counted
        while lo < hi:
            mid = (lo + hi + 1) // 2
            if (2 * mid - 1, sub(i - mid + 1, i + mid)) in counted: lo = mid
            else: hi = mid - 1
        total += lo
        lo, hi = 0, d2[i]
        while lo < hi:
            mid = (lo + hi + 1) // 2
            if (2 * mid, sub(i - mid, i + mid)) in counted: lo = mid
            else: hi = mid - 1
        total += lo
    return [count, longest, total]
if __name__ == '__main__':
    print(json.dumps([solve(*c) for c in json.load(sys.stdin)]))
