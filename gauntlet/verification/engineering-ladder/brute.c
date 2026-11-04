/* Oracle 3 for Engineering: Ladder: the literal model again, in C with 64-bit integers, fast enough to replay the
 * largest hidden tests (every operation loops over its range: about 10^10 steps for the biggest).
 *
 *   cc -O2 -o brute brute.c && ./brute < input.txt
 *
 * Input (written by oracles.mjs from a runEngine input):
 *   G seed n lo hi q m            then m lines: name weight [xlo xhi]      (a generator description)
 *   E n v0 v1 ... q               then q lines: name args...               (explicit values and operations)
 * Output: "count checksum", or "OVERFLOW" if a value or an answer leaves the range the prompt promises (+-2^50).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef long long ll;
#define MOD 1000000007LL
static ll *a, *hi, *lo;
static ll n, cnt = 0, h = 0;
static int bad = 0;
static const ll SAFE_VAL = 1LL << 50, SAFE_SUM = 1LL << 50;

static ll fdiv(ll v, ll x) { ll q = v / x; if ((v % x != 0) && ((v < 0) != (x < 0))) q--; return q; }
static void note(ll i) {
  if (a[i] > SAFE_VAL || a[i] < -SAFE_VAL) bad = 1;
  if (a[i] > hi[i]) hi[i] = a[i];
  if (a[i] < lo[i]) lo[i] = a[i];
}
static void answer(ll v) { cnt++; ll r = ((v % MOD) + MOD) % MOD; h = (h * 1000003LL + r) % MOD; }

static void op(const char *name, ll l, ll r, ll x) {
  ll i;
  if (!strcmp(name, "sum")) { ll s = 0; for (i = l; i <= r; i++) s += a[i]; if (s > SAFE_SUM || s < -SAFE_SUM) bad = 1; answer(s); }
  else if (!strcmp(name, "min")) { ll s = a[l]; for (i = l; i <= r; i++) if (a[i] < s) s = a[i]; answer(s); }
  else if (!strcmp(name, "max")) { ll s = a[l]; for (i = l; i <= r; i++) if (a[i] > s) s = a[i]; answer(s); }
  else if (!strcmp(name, "hmax")) { ll s = hi[l]; for (i = l; i <= r; i++) if (hi[i] > s) s = hi[i]; answer(s); }
  else if (!strcmp(name, "hmin")) { ll s = lo[l]; for (i = l; i <= r; i++) if (lo[i] < s) s = lo[i]; answer(s); }
  else if (!strcmp(name, "set") || !strcmp(name, "assign")) { for (i = l; i <= r; i++) { a[i] = x; note(i); } }
  else if (!strcmp(name, "add")) { for (i = l; i <= r; i++) { a[i] += x; note(i); } }
  else if (!strcmp(name, "chmin")) { for (i = l; i <= r; i++) { if (a[i] > x) a[i] = x; note(i); } }
  else if (!strcmp(name, "chmax")) { for (i = l; i <= r; i++) { if (a[i] < x) a[i] = x; note(i); } }
  else if (!strcmp(name, "div")) { for (i = l; i <= r; i++) { a[i] = fdiv(a[i], x); note(i); } }
  else if (!strcmp(name, "hreset")) { for (i = l; i <= r; i++) hi[i] = lo[i] = a[i]; }
  else { fprintf(stderr, "unknown op %s\n", name); exit(2); }
}

static int takesValue(const char *s) { return !strcmp(s, "add") || !strcmp(s, "assign") || !strcmp(s, "chmin") || !strcmp(s, "chmax") || !strcmp(s, "div"); }
static ll state;
static ll rnd(ll k) { state = state * 48271LL % 2147483647LL; return state % k; }

int main(void) {
  char kind[4];
  if (scanf("%3s", kind) != 1) return 2;
  if (kind[0] == 'G') {
    ll seed, lov, hiv, q, m, i, j;
    if (scanf("%lld %lld %lld %lld %lld %lld", &seed, &n, &lov, &hiv, &q, &m) != 6) return 2;
    char names[32][16]; ll w[32], xl[32], xh[32], W = 0;
    for (i = 0; i < m; i++) {
      if (scanf("%15s %lld %lld %lld", names[i], &w[i], &xl[i], &xh[i]) != 4) return 2;
      W += w[i];
    }
    state = seed;
    a = malloc(sizeof(ll) * n); hi = malloc(sizeof(ll) * n); lo = malloc(sizeof(ll) * n);
    for (i = 0; i < n; i++) { a[i] = lov + rnd(hiv - lov + 1); hi[i] = lo[i] = a[i]; }
    for (j = 0; j < q; j++) {
      ll x = rnd(W), e = 0;
      for (e = 0; e < m; e++) { if (x < w[e]) break; x -= w[e]; }
      if (!strcmp(names[e], "set")) { ll ii = rnd(n); op("set", ii, ii, xl[e] + rnd(xh[e] - xl[e] + 1)); continue; }
      ll u = rnd(n), t = rnd(n); ll l = u < t ? u : t, r = u < t ? t : u;
      ll val = takesValue(names[e]) ? xl[e] + rnd(xh[e] - xl[e] + 1) : 0;
      op(names[e], l, r, val);
    }
  } else {
    ll q, i;
    if (scanf("%lld", &n) != 1) return 2;
    a = malloc(sizeof(ll) * n); hi = malloc(sizeof(ll) * n); lo = malloc(sizeof(ll) * n);
    for (i = 0; i < n; i++) { if (scanf("%lld", &a[i]) != 1) return 2; hi[i] = lo[i] = a[i]; }
    if (scanf("%lld", &q) != 1) return 2;
    for (i = 0; i < q; i++) {
      char name[16]; ll x1, x2, x3;
      if (scanf("%15s %lld %lld %lld", name, &x1, &x2, &x3) != 4) return 2;
      if (!strcmp(name, "set")) op("set", x1, x1, x2); else op(name, x1, x2, x3);
    }
  }
  if (bad) printf("OVERFLOW\n"); else printf("%lld %lld\n", cnt, h);
  return 0;
}
