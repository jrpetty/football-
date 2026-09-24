# Third oracle for c3 (medium inputs): expand around every center, record first/last start of each palindrome string.
import sys, json
def solve(parts):
    s = ''.join(t * k for t, k in parts); n = len(s)
    first = {}; last = {}; cnt = {}
    for c in range(2 * n - 1):
        l = c // 2; r = l + (c % 2)
        while l >= 0 and r < n and s[l] == s[r]:
            t = s[l:r + 1]
            if t not in first or l < first[t]: first[t] = l
            if t not in last or l > last[t]: last[t] = l
            cnt[t] = cnt.get(t, 0) + 1
            l -= 1; r += 1
    good = [t for t in first if last[t] - first[t] >= len(t)]
    return [len(good), max((len(t) for t in good), default=0), sum(cnt[t] for t in good)]
if __name__ == '__main__':
    print(json.dumps([solve(*c) for c in json.load(sys.stdin)]))
