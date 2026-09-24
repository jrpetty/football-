"""Builds tests/math/olympiad.json. Answers come from olympiad.py (which asserts that every
independent method agrees). Usage (from verification/frontier-reasoning): python3 build_olympiad.py [gauntlet-root, default ../..]"""
import json
import sys

import olympiad as V

INT = "Give the final answer as a single integer (digits only, no words, units or expressions). Give exactly one answer. If you give more than one answer, it will be marked wrong."
MN = "Give only the value of m + n, as a single integer. Give exactly one answer. If you give more than one answer, it will be marked wrong."
SCRIPT = "Scripts: frontier-reasoning/olympiad.py (all methods, asserts agreement) and olympiad_crosscheck.mjs (independent JS brute force where feasible)."

CASES = [
    ("o01", V.o01, """Let N0 = {0, 1, 2, 3, ...} be the set of non-negative integers. A function f: N0 -> N0 is strictly increasing (f(a) < f(b) whenever a < b) and satisfies

f(f(n)) = 3n + 2 for every n in N0.

There is exactly one such function. Find f(2026).""", INT,
     "[medium] Substituting h(m) = f(m - 1) + 1 (m >= 1) gives a strictly increasing h: Z+ -> Z+ with h(h(m)) = 3m, whose unique solution is h(m) = m + 3^k for 3^k <= m <= 2*3^k and h(m) = 3m - 3^(k+1) for 2*3^k <= m <= 3^(k+1) (classical argument: h(1) = 2, h(3m) = 3h(m), and the 3^k - 1 integers strictly between 3^k and 2*3^k are squeezed onto the 3^k - 1 integers strictly between 2*3^k and 3^(k+1)). f(2026) = h(2027) - 1 = (3*2027 - 2187) - 1 = 3893. Verified by the closed form (f(f(n)) = 3n + 2 and monotonicity checked for n < 100000) and by an independent step-by-step construction of f in Python and JS."),
    ("o02", V.o02, """A 6-element subset of {1, 2, 3, ..., 24} is chosen uniformly at random (each of the C(24, 6) = 134596 subsets is equally likely). The probability that the chosen subset contains no two consecutive integers and no two elements whose sum is 25 can be written as m/n, where m and n are relatively prime positive integers. Find m + n.""", MN,
     "[medium] Put i and 25 - i in column i (i = 1..12). Forbidden pairs are exactly the edges of the 2 x 12 ladder graph (consecutive integers are neighbours along the rails; the rung 12-13 is both consecutive and sums to 25). Independent 6-sets of the ladder, by a transfer matrix with size polynomials: 12642. Probability 12642/134596 = 903/9614, so m + n = 10517. Verified by brute force over all subsets (Python and JS) and by the transfer matrix."),
    ("o03", V.o03, """How many ordered pairs (m, n) of positive integers with m <= 10000 and n <= 10000 have the property that the product m*n divides m^2 + n^2 + 35? (The pairs (m, n) and (n, m) are counted separately when m and n are different.)""", INT,
     "[hard] Vieta jumping: for k = (m^2 + n^2 + 35)/(mn), (m, n) -> (m, km - n) = (m, (m^2 + 35)/n) preserves solutions, and descent ends at base solutions with m <= n, n^2 <= m^2 + 35: (1,1) k=37, (1,2) k=20, (1,3) k=15, (1,4) k=13, (1,6) k=12 (its n-jump returns (1,6) itself, so only one chain leaves it), (2,3) k=8, (3,4) k=5. Unordered solutions with max <= 10000 per k: k=5: 11, k=8: 8, k=12: 3, k=13: 7, k=15: 7, k=20: 6, k=37: 3 (45 in total, one of them (1,1)), so 2*44 + 1 = 89 ordered pairs. Verified by divisor enumeration, a full 10^8-pair grid brute force (Python and JS) and chain generation from the base solutions."),
    ("o04", V.o04, """Tetrahedron ABCD has edge lengths AB = 3, AC = 4, AD = 5, BC = 5, BD = 6 and CD = 7. There is exactly one sphere that is tangent to all six edges of the tetrahedron, touching each edge at a point strictly between its two endpoints. The square of the radius of this sphere can be written as m/n, where m and n are relatively prime positive integers. Find m + n.""", MN,
     "[hard] Tangent segments from a vertex are equal, so AB = t_A + t_B etc., giving (t_A, t_B, t_C, t_D) = (1, 2, 3, 4) (consistent because AB + CD = AC + BD = AD + BC = 10). The centre O satisfies |OX|^2 = r^2 + t_X^2 for every vertex X (radical centre of the four mutually tangent vertex spheres). Solving with the Gram matrix of AB, AC, AD gives r^2 = 576/215; equivalently 4/r^2 = (sum 1/t)^2 - 2 sum 1/t^2 = 215/144. m + n = 791. Verified by exact Gram-matrix algebra (sympy), numeric 3D coordinates with the tangency distance checked on all six edges (tangency points strictly inside), and the identity."),
    ("o05", V.o05, """Six married couples (12 different people) are to be seated at a round table with 12 equally spaced chairs, numbered 1 to 12 around the table. Chairs k and k + 1 are next to each other (and so are chairs 12 and 1); chairs k and k + 6 are directly opposite each other. Nobody may sit next to their spouse, and nobody may sit directly opposite their spouse. Two seatings are considered the same if one can be obtained from the other by rotating everyone around the table by the same number of chairs (mirror-image seatings are considered different). How many different seatings are there?""", INT,
     "[hard] The forbidden chair pairs form the Moebius ladder (12-cycle plus 6 diameters, 18 edges) with matching numbers m_k = 1, 18, 117, 336, 420, 192, 18 (k = 0..6). Inclusion-exclusion over couples forced onto forbidden pairs: sum (-1)^k C(6,k) m_k k! 2^k (12 - 2k)! = 79,073,280 seatings on numbered chairs; no seating is fixed by a non-trivial rotation, so there are 79,073,280 / 12 = 6,589,440 up to rotation. Verified by inclusion-exclusion, exhaustive backtracking in Python, and an independent JS backtracking with person 1 fixed in chair 1."),
    ("o06", V.o06, """Each of the 12 vertices of a regular icosahedron is coloured with one of 5 available colours (not every colour has to be used) so that the two endpoints of every edge receive different colours. Two colourings are considered the same if one can be turned into the other by a rotation of the icosahedron in space. The 5 colours are distinguishable and are never exchanged for one another, and reflections are not allowed. How many different colourings are there?""", INT,
     "[hard] No non-identity rotation fixes a proper colouring (5-fold rotations cycle adjacent pentagon vertices, 3-fold rotations cycle the vertices of a face, 2-fold rotations swap the endpoints of an edge), so by Burnside the answer is P(icosahedron, 5)/60. Counting via top vertex, two pentagons (antiprism band) and bottom vertex gives P(5) = 80400, so 80400/60 = 1340. Verified by Burnside over the explicit 60-element rotation group, by direct orbit enumeration with canonical representatives, and by an independent antiprism-band count."),
    ("o07", V.o07, """For a positive integer n, the digits after the decimal point in the decimal expansion of 1/n are eventually periodic. Let L(n) be the length of the shortest block of digits that eventually repeats forever. For example, L(3) = 1 because 1/3 = 0.333..., L(7) = 6 because 1/7 = 0.142857142857..., L(12) = 1 because 1/12 = 0.08333..., and L(8) = 1 because 1/8 = 0.125000... (the repeating block is 0).

Find the smallest positive integer n such that L(n) = 2026.""", INT,
     "[hard] L(n) is the multiplicative order of 10 modulo n' (n with all factors 2 and 5 removed), so we need ord = 2026 = 2*1013. Some prime factor p of n' needs 1013 | ord_p(10), so p = 1 (mod 1013) (p = 1013 fails since its order divides 1012). In order: 2027 prime but 10 is a quadratic residue ((2/2027) = (5/2027) = -1) so ord = 1013; 4053 = 3*7*193; 6079 prime, 10 a residue and (p-1)/2 = 3039 is odd, so ord | 3039 (it is 1013); 8105 = 5*1621; 10131 = 3*11*307; 12157 prime with p - 1 = 4*3*1013 and 10^1013 = -1 (mod 12157), so ord = 2026. Any composite n' with order 2026 needs a factor 2027 or 6079 times a factor of even order, at least 11*2027 = 22297 > 12157. Answer 12157. Trap: 10 is a quadratic residue mod 12157, which does not force an odd order because 4 | p - 1. Verified by a linear search with sympy's n_order, by the candidate argument with plain pow(), and by a JS long-division search over every n <= 12157."),
    ("o08", V.o08, """A triangulation of a convex polygon divides it into triangles by drawing diagonals that do not cross each other inside the polygon. In a triangulation, an ear is a triangle that has two of its three sides on the boundary of the polygon.

The 13 vertices of a convex 13-gon are labelled 1 to 13 and stay fixed in place, so two triangulations are different whenever they use different sets of diagonals (rotated or reflected versions of a triangulation count as different triangulations). How many triangulations of this 13-gon have exactly 4 ears?""", INT,
     "[hard] The dual tree of a triangulation has 11 nodes of degree <= 3 and its leaves are the ears; exactly 4 leaves means two degree-3 nodes joined by a path, with four paths hanging off. Closed form (Hurtado-Noy): (n/k) 2^(n-2k) C(n-4, 2k-4) Catalan(k-2) = (13/4)(32)(126)(2) = 26208. Verified by enumerating all Catalan(11) = 58786 triangulations and counting ears, by an ear-counting DP over sub-polygons (Python and an independent JS interval DP), and by the closed form."),
    ("o09", V.o09, """A knight starts on the bottom-left corner square of a 5 x 5 chessboard. At every step it makes one legal knight move (two squares in one direction and then one square in a perpendicular direction, staying on the board), chosen uniformly at random from all legal moves available from its current square, independently of all earlier steps. The expected number of moves until the knight first lands on the top-right corner square can be written as m/n, where m and n are relatively prime positive integers. Find m + n.""", MN,
     "[hard] The 5 x 5 knight graph has 48 edges and the 180-degree rotation swaps the two corners, so the hitting time equals half the commute time: H = |E| * R_eff = 48 * 264/215 = 12672/215 (about 58.94). Using both reflections (potential 1/2 on the anti-diagonal) only 5 unknown potentials remain. m + n = 12887. Verified by the exact hitting-time linear system, by Tetali's formula with the Laplacian pseudo-inverse, by the commute-time identity, and by simulation (20,000 walks)."),
    ("o10", V.o10, """The number 199 is prime. How many ordered triples (x, y, z) of integers with 0 <= x <= 198, 0 <= y <= 198 and 0 <= z <= 198 satisfy

x^3 + y^3 + z^3 = 1 (mod 199),

that is, x^3 + y^3 + z^3 - 1 is divisible by 199?""", INT,
     "[extreme] Cubic character sums. For p = 1 (mod 3) write 4p = L^2 + 27M^2 with L = 1 (mod 3); here 796 = 121 + 675, so L = -11, M = 5. With Jacobi sums, N(x^3 + y^3 + z^3 = 1) = p^2 + 6p - L: the six character triples mixing chi and chi^2 contribute p each, and the two pure triples contribute -J(chi,chi) - conj = -L (2 Re J(chi,chi) = L, the same quantity as in Gauss's count p - 2 + L of x^3 + y^3 = 1). 39601 + 1194 + 11 = 40806. Verified by complete brute force over all 199^3 triples (two Python implementations and JS) and by the formula (also checked against brute force for 5 other primes)."),
    ("o11", V.o11, """For a real number x, let floor(x) denote the greatest integer that is less than or equal to x. Compute

floor(1*sqrt(2)) + floor(2*sqrt(2)) + floor(3*sqrt(2)) + ... + floor(2026*sqrt(2)),

that is, the sum of floor(k*sqrt(2)) over all integers k from 1 to 2026.""", INT,
     "[extreme] Beatty: floor(k*sqrt2) (k >= 1) and floor(j*(2 + sqrt2)) (j >= 1) partition the positive integers, so with N = floor(n*sqrt2) and m = #{j : floor(j(2 + sqrt2)) <= N}: S(n) = N(N+1)/2 - m(m+1) - S(m). Chain (n, N, m): (2026, 2865, 839), (839, 1186, 347), (347, 490, 143), (143, 202, 59), (59, 83, 24), (24, 33, 9), (9, 12, 3), (3, 4, 1), (1, 1, 0). Answer 2902864. Verified by exact integer-square-root summation (Python and JS BigInt), by the Beatty recursion, and by 60-digit decimal arithmetic."),
    ("o12", V.o12, """An ant starts at a vertex of a cube. Every minute it walks along one of the 3 edges at its current vertex, chosen uniformly at random and independently of all earlier choices, and arrives at the neighbouring vertex. The starting vertex counts as visited. The expected number of minutes until the ant has visited all 8 vertices of the cube can be written as m/n, where m and n are relatively prime positive integers. Find m + n.""", MN,
     "[extreme] Exact cover time of the 3-cube from a vertex: 1996/95 (about 21.0105). Computed by exact rational elimination over (current vertex, visited set) states, processing visited sets from largest to smallest; cross-checked by one global floating-point linear system over all 1016 states (21.01052632) and by 100,000 simulated walks. m + n = 2091."),
]


def main(root):
    cases = []
    for cid, fn, body, tail, note in CASES:
        fn()
        ans = V.RESULTS[cid]
        cases.append({"id": cid, "prompt": body.strip() + "\n\n" + tail, "expected": ans, "notes": note + " " + SCRIPT})
    test = {
        "kind": "prompt",
        "id": "math.olympiad",
        "version": "1.0.0",
        "name": "Olympiad Maths",
        "category": "math",
        "description": "Twelve original olympiad-level problems (upper AIME to USAMO short-answer) with single integer answers: cubic character sums, Vieta jumping, multiplicative orders, a Moebius-ladder menage count, Burnside on the icosahedron, triangulation ears, random-walk hitting and cover times, an edge-tangent sphere, a Beatty-sequence sum and a conjugated functional equation. Hand search is hopeless on most of them, so a model has to find the structure and then execute a long exact computation without a single slip. Every answer is verified by at least two independent methods (brute force or simulation plus exact derivation).",
        "difficulty": "extreme",
        "tags": ["math", "olympiad", "frontier", "number-theory", "combinatorics", "probability", "geometry", "brute-force-verified"],
        "hook": "Twelve olympiad problems. One integer each. No calculator.",
        "maxOutputTokens": 32000,
        "estimate": {"inputTokens": 260, "outputTokens": 22000},
        "author": "Gauntlet Core",
        "createdAt": "2026-09-24",
        "scorer": {"type": "number", "tolerance": 0},
        "cases": cases,
    }
    path = f"{root}/tests/math/olympiad.json"
    with open(path, "w") as fh:
        json.dump(test, fh, indent=2, ensure_ascii=False)
        fh.write("\n")
    print("wrote", path, len(cases), "cases")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "../..")
