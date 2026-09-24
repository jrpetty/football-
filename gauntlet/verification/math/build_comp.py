import sys, json
sys.path.insert(0, '..')
from common import write_test, tok, ONE
import runpy

R1 = runpy.run_path('competition.py')['R']
R2 = runpy.run_path('competition2.py')['R']
R3 = runpy.run_path('competition3.py')['R']
INT = "Give the final answer as a single integer (digits only, no words, units or expressions). " + ONE
MN = "Give only the value of m + n, as a single integer. " + ONE

cases = [
 ('c01', 'easy', R1['C01'], "How many positive integers n with n <= 3000 are divisible by 4 or by 6 (or by both), but are not divisible by 9?\n\n" + INT,
  "Brute force over 1..3000 and inclusion-exclusion (1000 - 166) agree."),
 ('c02', 'easy', R1['C02'], "Let r, s and t be the three roots (real or complex, counted with multiplicity) of the polynomial x^3 - 4x^2 + 6x - 9. Compute the value of (r^2 + 4)(s^2 + 4)(t^2 + 4). The result is an integer.\n\n" + INT,
  "Product equals P(2i)P(-2i) = |7+4i|^2 = 65; confirmed numerically with numpy roots."),
 ('c03', 'easy', R1['C03'], "Find the smallest positive integer n such that n! (n factorial) is divisible by 2^50 * 3^30.\n\n" + INT,
  "Legendre's formula search; confirmed by computing n! and (n-1)! exactly."),
 ('c04', 'easy', R1['C07'], "How many ordered pairs (a, b) of positive integers satisfy gcd(a, b) = 6 and lcm(a, b) = 113400? (Note: 113400 = 2^3 * 3^4 * 5^2 * 7.)\n\n" + INT,
  "Brute force over all divisor pairs of 113400; equals 2^(number of primes where the exponents of gcd and lcm differ) = 2^4."),
 ('c05', 'medium', R1['C04'], "A lattice path goes from (0, 0) to (9, 6) using 15 unit steps, each step either one unit in the +x direction or one unit in the +y direction. How many such paths do not pass through any of the three points (3, 2), (5, 4) and (6, 1)?\n\n" + INT,
  "Dynamic programming over the grid and full enumeration of all C(15,6) step sequences agree."),
 ('c06', 'medium', R1['C05'], "Three fair six-sided dice (faces 1 to 6) and one fair eight-sided die (faces 1 to 8) are rolled together. The probability that the sum of the three six-sided dice equals exactly twice the number shown on the eight-sided die can be written as m/n, where m and n are relatively prime positive integers. Find m + n.\n\n" + MN,
  f"Exact enumeration of all 1728 outcomes: probability {R1['C05_frac']}."),
 ('c07', 'medium', R1['C06'], "Let N = 2026^2026. Find the remainder when 13^N is divided by 1000.\n\n" + INT,
  "Python pow(13, 2026**2026, 1000) with the full exponent, cross-checked with exponent reduced mod lambda(1000) = 100."),
 ('c08', 'medium', R1['C08'], "How many ordered pairs of integers (x, y), where x and y may be positive, negative or zero, satisfy x^2 + xy + y^2 = 2401?\n\n" + INT,
  "Brute force over |x|,|y| <= 60; matches the Eisenstein-integer count 6*(number of divisors of 7^4) = 30."),
 ('c09', 'medium', R1['C09'], "How many five-digit positive integers have digits that never decrease from left to right (each digit is greater than or equal to the digit before it) and have a digit sum of exactly 20?\n\n" + INT,
  "Brute force over 10000..99999."),
 ('c10', 'medium', R2['C17'], "Triangle ABC has vertices A = (0, 0), B = (21, 0) and C = (5, 12). Let O be the center of its circumscribed circle and I the center of its inscribed circle. The square of the distance OI can be written as m/n, where m and n are relatively prime positive integers. Find m + n.\n\n" + MN,
  f"Exact rational coordinates for O and I computed with fractions: OI^2 = {R2['C17_frac']} (agrees with Euler: R(R-2r) with R=65/6, r=14/3)."),
 ('c11', 'hard', R2['C07b'], "How many five-digit positive integers are divisible by 11 and have a digit sum of exactly 27?\n\n" + INT,
  "Brute force over 10000..99999; matches 9 x #(a+c+e=19, a>=1) via the alternating-sum argument."),
 ('c12', 'hard', R1['C10'], "Find the sum of all positive integers n with n < 1000 such that n^2 - 1 is divisible by 840.\n\n" + INT,
  f"Brute force over 1..999 ({R1['C10_count']} solutions)."),
 ('c13', 'hard', R1['C11'], "For a positive integer k, let S(k) denote the sum of the decimal digits of k. How many integers n with 1 <= n <= 9999 satisfy S(n) = S(2n)?\n\n" + INT,
  "Brute force; cross-checked with the carry characterisation (digit sum = 9 x number of digits >= 5)."),
 ('c14', 'hard', R1['C13'], "A token starts at position 0 on the number line. Every second it moves one unit: to the right with probability 2/3 or to the left with probability 1/3, independently of all previous moves. It stops as soon as it reaches position -2 or position 4. The expected number of moves it makes is m/n, where m and n are relatively prime positive integers. Find m + n.\n\n" + MN,
  f"Exact linear system solved with sympy: E = {R1['C13_frac']}; Monte-Carlo (200k runs) gives {R1['C13_mc']:.3f}."),
 ('c15', 'hard', R1['C14'], "How many permutations (a1, a2, a3, a4, a5, a6) of the numbers 1, 2, 3, 4, 5, 6 have the property that i + a_i is NOT a prime number for every i from 1 to 6?\n\n" + INT,
  "Brute force over all 720 permutations."),
 ('c16', 'hard', R3['X5'], "A bag contains 5 red marbles and 7 blue marbles. Marbles are drawn one at a time, uniformly at random and without replacement, until 3 marbles of the same colour have been drawn (3 red or 3 blue); then drawing stops. The expected number of marbles drawn is m/n, where m and n are relatively prime positive integers. Find m + n.\n\n" + MN,
  f"Exact recursion with fractions ({R3['X5_frac']}) and exhaustive enumeration of all C(12,5) colour orders agree."),
 ('c17', 'extreme', R1['C12'], "How many subsets of {1, 2, 3, ..., 20} (including the empty set) contain no two elements whose difference is 1 or 3?\n\n" + INT,
  "Bitmask brute force over all 2^20 subsets and a transfer DP agree."),
 ('c18', 'extreme', R2['C15_k5'], "Each of the six faces of a cube is painted with one of 5 available colours (not every colour has to be used). Two faces that share an edge must receive different colours. Two colourings are considered the same if one can be turned into the other by rotating the cube (reflections are not allowed). How many different colourings are there?\n\n" + INT,
  "Brute force: all 5^6 colourings filtered for proper adjacency (780, matching the octahedron chromatic polynomial), then orbits under the 24-element rotation group generated explicitly."),
 ('c19', 'extreme', R2['C16'], "Four distinct vertices of a regular 12-gon are chosen uniformly at random (every set of 4 vertices is equally likely). The probability that the centre of the 12-gon lies strictly inside the quadrilateral formed by the four chosen vertices is m/n, where m and n are relatively prime positive integers. Find m + n.\n\n" + MN,
  f"Geometric test (strict cross products) on all 495 subsets and the gap criterion (all arcs < 180 degrees) agree: {R2['C16_fav']} favourable, probability {R2['C16_frac']}."),
 ('c20', 'extreme', R3['X6'], "How many 4 x 6 matrices (4 rows, 6 columns) with every entry equal to 0 or 1 have exactly three 1s in each row and exactly two 1s in each column?\n\n" + INT,
  "Exhaustive enumeration over choices of the first three rows (the fourth row is forced)."),
]

out = []
for cid, d, ans, prompt, note in cases:
    out.append({'id': cid, 'prompt': prompt, 'expected': int(ans), 'notes': f"[{d}] {note} Scripts: math/competition.py, competition2.py, competition3.py."})
    print(cid, d, ans)
meta = dict(
    id='math.competition', category='math', name='Competition Maths',
    description='Twenty original competition-style problems (number theory, combinatorics, probability, algebra and geometry) with single integer answers, from warm-ups to problems that need a clever count or an exact expectation. Every answer is verified by brute force or two independent methods, and the hard tail (transfer matrices, Burnside, geometric probability) separates top models that reason exactly from those that approximate.',
    difficulty='hard', tags=['math', 'competition', 'number-theory', 'combinatorics', 'probability', 'brute-force-verified'],
    hook='Twenty contest problems. Integer answers. No partial credit.',
    maxOutputTokens=32000,
    estimate={'inputTokens': int(sum(tok(c['prompt']) for c in out) / len(out)) + 40, 'outputTokens': 9000},
    scorer={'type': 'number', 'tolerance': 0},
)
print(write_test(meta, out))
