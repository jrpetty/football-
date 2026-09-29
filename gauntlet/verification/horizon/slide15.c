/*
 * Fast optimal solver for sliding puzzles up to 4x4 (IDA*, Manhattan distance + linear conflicts).
 * Used only to FIND candidate start positions for the deepest Horizon sliding levels quickly; every
 * published minimum is then re-proved by sliding.py (Python) and verify.mjs (JavaScript).
 *
 *   gcc -O2 -o slide15 slide15.c
 *   ./slide15 ROWS COLS t0 t1 ... t(n-1)   (0 = the gap)  → prints "<length> <plan...>"
 */
#include <stdio.h>
#include <stdlib.h>

static int R, C, N, b[16], path[200], best_found;
static long long nodes;

static int lc_line(int *tiles, int k) {
  /* tiles: goal positions along the line of tiles that belong to this line, in order; returns k - LIS */
  int lis[16], len = 0;
  for (int i = 0; i < k; i++) {
    int lo = 0, hi = len;
    while (lo < hi) { int m = (lo + hi) / 2; if (lis[m] < tiles[i]) lo = m + 1; else hi = m; }
    lis[lo] = tiles[i];
    if (lo == len) len++;
  }
  return k - len;
}

static int heur(void) {
  int h = 0;
  for (int i = 0; i < N; i++) if (b[i]) {
    int g = b[i] - 1;
    h += abs(g / C - i / C) + abs(g % C - i % C);
  }
  for (int r = 0; r < R; r++) {
    int t[16], k = 0;
    for (int c = 0; c < C; c++) { int v = b[r * C + c]; if (v && (v - 1) / C == r) t[k++] = (v - 1) % C; }
    h += 2 * lc_line(t, k);
  }
  for (int c = 0; c < C; c++) {
    int t[16], k = 0;
    for (int r = 0; r < R; r++) { int v = b[r * C + c]; if (v && (v - 1) % C == c) t[k++] = (v - 1) / C; }
    h += 2 * lc_line(t, k);
  }
  return h;
}

static int search(int g, int bound, int z, int prev) {
  nodes++;
  int h = heur();
  if (g + h > bound) return g + h;
  if (h == 0) { best_found = g; return -1; }
  int min = 1 << 30;
  int nb[4], n = 0;
  if (z >= C) nb[n++] = z - C;
  if (z < N - C) nb[n++] = z + C;
  if (z % C) nb[n++] = z - 1;
  if (z % C != C - 1) nb[n++] = z + 1;
  for (int i = 0; i < n; i++) {
    int t = nb[i];
    if (t == prev) continue;
    int tile = b[t];
    b[z] = tile; b[t] = 0; path[g] = tile;
    int res = search(g + 1, bound, t, z);
    b[t] = tile; b[z] = 0;
    if (res == -1) return -1;
    if (res < min) min = res;
  }
  return min;
}

int main(int argc, char **argv) {
  R = atoi(argv[1]); C = atoi(argv[2]); N = R * C;
  int z = 0;
  for (int i = 0; i < N; i++) { b[i] = atoi(argv[3 + i]); if (!b[i]) z = i; }
  int limit = argc > 3 + N ? atoi(argv[3 + N]) : 200;
  int bound = heur();
  while (bound <= limit) {
    int res = search(0, bound, z, -1);
    if (res == -1) {
      printf("%d", best_found);
      for (int i = 0; i < best_found; i++) printf(" %d", path[i]);
      printf("\n");
      return 0;
    }
    bound = res;
  }
  printf("-1\n");
  return 0;
}
