import sys
sys.path.insert(0, '..')
from common import write_test, tok, ONE
from plan import *
from sched import best_schedule, method1, method2

INT = "Give the answer as a single integer (digits only, no units). " + ONE
VER = "Verified by exhaustive breadth-first search in planning/plan.py and re-verified by an independent JS BFS in planning/crosscheck.mjs."

cases = []

# p01 -----------------------------------------------------------------------
d, path = jugs((4, 9), 6)
cases.append(dict(id='p01', d='easy', ans=d, notes=f"{VER} State (4 L jug, 9 L jug) path: {path}.", prompt=(
"You have two unmarked jugs: one holds exactly 4 litres and the other exactly 9 litres. Both start empty. You also have an unlimited water tap and a drain.\n\n"
"Each step is exactly one of these actions:\n"
"1. Fill one jug completely from the tap.\n"
"2. Empty one jug completely into the drain.\n"
"3. Pour water from one jug into the other until either the first jug is empty or the second jug is full, whichever happens first.\n"
"You cannot measure or estimate partial amounts in any other way.\n\n"
"What is the minimum number of steps needed so that one of the jugs contains exactly 6 litres of water? (It does not matter what the other jug contains at that moment.)\n\n" + INT)))

# p02 -----------------------------------------------------------------------
n = 10
start = 'HTTHHTTTHT'
masks = [0b111 << i for i in range(n - 2)]
s = int(''.join('1' if ch == 'T' else '0' for ch in start), 2)
d, _ = toggles(n, masks, s, 0)
cases.append(dict(id='p02', d='easy', ans=d, notes=f"{VER} Flips commute and each window is used at most once in an optimal solution; the left-to-right greedy solution is forced and uses {d} flips.", prompt=(
"Ten coins lie in a row. From left to right they show:\n"
"H T T H H T T T H T\n"
"(H = heads, T = tails.)\n\n"
"In one move you choose any three coins that are next to each other in the row (positions i, i+1, i+2) and turn all three of them over, so each head becomes a tail and each tail becomes a head. You may not move coins around or turn over any other number of coins.\n\n"
"What is the minimum number of moves needed to make all ten coins show heads?\n\n" + INT)))

# p03 -----------------------------------------------------------------------
def bridge_plan(times, path):
    steps = []
    for a, b in zip(path, path[1:]):
        moved = (a[0] - b[0]) if a[1] == 0 else (b[0] - a[0])
        steps.append(('over ' if a[1] == 0 else 'back ') + '+'.join(str(times[i]) for i in sorted(moved)))
    return '; '.join(steps)
d, path = bridge((2, 3, 7, 9, 12, 20), 2)
cases.append(dict(id='p03', d='medium', ans=d, notes=f"{VER} Dijkstra over (who is on the start side, lantern side). One optimal plan: {bridge_plan((2, 3, 7, 9, 12, 20), path)} = {d} minutes.", prompt=(
"Six hikers must cross a narrow rope bridge at night: Oona takes 2 minutes to cross, Pavel 3 minutes, Quinn 7 minutes, Rosa 9 minutes, Sami 12 minutes and Tove 20 minutes.\n\n"
"Rules:\n"
"- At most two people can be on the bridge at the same time.\n"
"- The group has exactly one lantern, and every crossing (in either direction) must carry it. The lantern cannot be thrown across or left on the bridge; it can only get back to the start side by someone carrying it.\n"
"- When two people cross together they move at the pace of the slower one.\n"
"- Time spent on the banks is zero; only crossing time counts.\n\n"
"What is the minimum total time, in minutes, for all six hikers to end up on the far side?\n\n" + INT)))

# p04 -----------------------------------------------------------------------
W = (92, 81, 68, 57, 44, 29)
import itertools
def gond():
    n = 6; allp = frozenset(range(n))
    def nb(st):
        left, side = st
        here = left if side == 0 else allp - left
        out = []
        for k in range(1, 4):
            for g in itertools.combinations(sorted(here), k):
                if sum(W[i] for i in g) > 130 or g == (5,):
                    continue
                g = frozenset(g)
                out.append((left - g if side == 0 else left | g, 1 - side))
        return out
    return bfs((allp, 0), lambda st: len(st[0]) == 0, nb)
d, path = gond()
names = ['Ulla', 'Birk', 'Carmen', 'Deniz', 'Eamon', 'Fifi']
plan_txt = []
for a, b in zip(path, path[1:]):
    moved = (a[0] - b[0]) if a[1] == 0 else (b[0] - a[0])
    plan_txt.append(('up ' if a[1] == 0 else 'down ') + '+'.join(names[i] for i in sorted(moved)))
cases.append(dict(id='p04', d='medium', ans=d, notes=f"{VER} Optimal plan ({d} rides): {'; '.join(plan_txt)}.", prompt=(
"A small gondola runs between a valley station and a summit station. Six people are at the valley station: Ulla (92 kg), Birk (81 kg), Carmen (68 kg), Deniz (57 kg), Eamon (44 kg) and Fifi (29 kg, a child).\n\n"
"Rules:\n"
"- Each ride goes from one station to the other (up or down).\n"
"- A ride can carry at most 3 people and at most 130 kg in total.\n"
"- The gondola cannot move empty: every ride must have at least one person in it.\n"
"- Fifi may never ride alone (she may ride with any other person or people).\n"
"- Anyone may ride any number of times, in either direction.\n\n"
"What is the minimum number of rides (counting every one-way ride, up or down) needed to get all six people to the summit station?\n\n" + INT)))

# p05 -----------------------------------------------------------------------
s5 = [None] * 5
for dd in (5, 3): s5[dd - 1] = 0
for dd in (4, 2, 1): s5[dd - 1] = 1
d, _ = hanoi(5, s5, [2] * 5)
cases.append(dict(id='p05', d='medium', ans=d, notes=f"{VER} BFS over all 3^5 disk placements.", prompt=(
"There are three pegs, A, B and C, and five disks of different sizes numbered 1 (smallest) to 5 (largest).\n\n"
"Current position:\n"
"- Peg A: disk 5 at the bottom with disk 3 on top of it.\n"
"- Peg B: disk 4 at the bottom, then disk 2, then disk 1 on top.\n"
"- Peg C: empty.\n\n"
"A move consists of taking the top disk from any peg and placing it on top of another peg. A disk may never be placed on top of a smaller disk.\n\n"
"What is the minimum number of moves needed to get all five disks onto peg C?\n\n" + INT)))

# p06 -----------------------------------------------------------------------
d, _ = sliding((5, 2, 0, 4, 3, 1), (1, 2, 3, 4, 5, 0), 2, 3)
cases.append(dict(id='p06', d='medium', ans=d, notes=f"{VER} BFS over all 360 reachable 2x3 states.", prompt=(
"A sliding puzzle has 2 rows and 3 columns: five numbered tiles and one empty square (shown as _).\n\n"
"Current position:\n"
"5 2 _\n"
"4 3 1\n\n"
"Goal position:\n"
"1 2 3\n"
"4 5 _\n\n"
"One move slides a single tile that is horizontally or vertically adjacent to the empty square into the empty square. Tiles cannot be lifted out or moved diagonally.\n\n"
"What is the minimum number of moves needed to reach the goal position?\n\n" + INT)))

# p07 -----------------------------------------------------------------------
cases.append(dict(id='p07', d='hard', ans=7, notes=f"{VER} The 12x12 toggle matrix over GF(2) has full rank (all 4096 states reachable), so the set of presses is unique; it has 7 presses. JS check enumerates all 4096 press subsets.", prompt=(
"A panel has 12 lamps arranged in 3 rows and 4 columns. Pressing a lamp toggles that lamp and every lamp directly above, below, left or right of it (lamps on the edges have fewer neighbours; there is no wrap-around). Toggling turns an on lamp off and an off lamp on.\n\n"
"Current state (1 = on, 0 = off), rows from top to bottom:\n"
"0 1 1 0\n"
"0 1 1 0\n"
"1 0 0 0\n\n"
"What is the minimum number of presses needed to turn every lamp off?\n\n" + INT)))

# p08 -----------------------------------------------------------------------
def pour_only(caps, start, goal_fn):
    def nb(st):
        out = []
        for i in range(3):
            for j in range(3):
                if i != j and st[i] > 0 and st[j] < caps[j]:
                    a = min(st[i], caps[j] - st[j]); t = list(st); t[i] -= a; t[j] += a; out.append(tuple(t))
        return out
    return bfs(start, goal_fn, nb)
d, path = pour_only((7, 11, 18), (0, 0, 18), lambda st: sorted(st)[1:] == [9, 9])
cases.append(dict(id='p08', d='hard', ans=d, notes=f"{VER} Optimal path (7L,11L,18L): {path}.", prompt=(
"You have three unmarked jugs with capacities 7 litres, 11 litres and 18 litres. The 18-litre jug is completely full of water and the other two are empty. There is no tap and no drain, and no water may be spilled or added.\n\n"
"The only allowed step is a pour: pour water from one jug into another until either the jug you are pouring from is empty or the jug you are pouring into is full, whichever happens first.\n\n"
"What is the minimum number of pours needed so that two of the jugs each contain exactly 9 litres?\n\n" + INT)))

# p09 -----------------------------------------------------------------------
d, path = bridge((2, 5, 7, 11, 13, 16, 20, 24), 3)
cases.append(dict(id='p09', d='hard', ans=d, notes=f"{VER} Dijkstra over (who is on the start side, lantern side) with groups of 1-3. One optimal plan: {bridge_plan((2, 5, 7, 11, 13, 16, 20, 24), path)} = {d} minutes.", prompt=(
"Eight explorers must cross a fragile bridge in the dark. Their individual crossing times are: Aiko 2 minutes, Bram 5, Cato 7, Dara 11, Emil 13, Fern 16, Gus 20 and Hedda 24 minutes.\n\n"
"Rules:\n"
"- At most three people can be on the bridge at the same time.\n"
"- There is exactly one torch, and every crossing (in either direction) must carry it. It cannot be thrown or left on the bridge.\n"
"- A group crossing together moves at the pace of its slowest member.\n"
"- Only crossing time counts; time on the banks is zero.\n\n"
"What is the minimum total time, in minutes, for all eight explorers to end up on the far side?\n\n" + INT)))

# p10 -----------------------------------------------------------------------
d, path = pancakes((4, 1, 6, 3, 7, 2, 5))
cases.append(dict(id='p10', d='hard', ans=d, notes=f"{VER} BFS over all 7! = 5040 stacks. One optimal sequence of stacks: {path}.", prompt=(
"A stack of seven pancakes has sizes 1 (smallest) to 7 (largest). Reading from the top of the stack to the bottom, the sizes are currently:\n"
"4, 1, 6, 3, 7, 2, 5\n\n"
"One flip means: slide a spatula under any pancake in the stack and turn over everything above the spatula in one go, so the order of those top pancakes is reversed (the pancakes below the spatula do not move).\n\n"
"What is the minimum number of flips needed to arrange the stack as 1, 2, 3, 4, 5, 6, 7 from top to bottom?\n\n" + INT)))

# p11 -----------------------------------------------------------------------
dur = [1, 4, 1, 6, 3, 5, 1, 4, 1]; pred = [[], [0], [1], [1], [2], [1], [0, 3], [0, 4, 5], [1]]; need = [1] * 9
m1 = method1(dur, pred, need, 2); m2 = method2(dur, pred, need, 2); ms, st = best_schedule(dur, pred, need, 2)
assert m1 == m2 == ms
L = 'ABCDEFGHI'
sched_txt = ', '.join(f"{L[i]}@{st[i]}-{st[i]+dur[i]}" for i in sorted(st, key=lambda i: (st[i], i)))
SCHED_RULES = (
"Rules:\n"
"- Workers are identical and interchangeable. A worker can work on only one task at a time.\n"
"- Each task needs the stated number of workers for its whole duration; a task cannot be split, paused or sped up, and once started it runs to completion.\n"
"- A task can start only when all of its prerequisites have finished; it may start at the exact moment its last prerequisite finishes.\n"
"- Work starts at time 0. There is no set-up or travel time between tasks, and workers may stand idle.\n")
cases.append(dict(id='p11', d='hard', ans=m1, notes=f"Exact optimum by two independent methods in planning/sched.py: event-driven exhaustive search over active schedules ({m1}) and serial schedule generation over every precedence-feasible task order ({m2}). Lower bounds are only 14 (critical path A-B-F-H) and 13 (26 worker-hours / 2), and critical-path-priority list scheduling gives 17. Optimal schedule: {sched_txt}.", prompt=(
"A two-person crew must complete nine tasks, A to I. There are exactly 2 workers, and every task needs exactly 1 worker.\n\n"
"Task | Duration (hours) | Prerequisites (must be finished first)\n"
"A | 1 | none\n"
"B | 4 | A\n"
"C | 1 | B\n"
"D | 6 | B\n"
"E | 3 | C\n"
"F | 5 | B\n"
"G | 1 | A, D\n"
"H | 4 | A, E, F\n"
"I | 1 | B\n\n" + SCHED_RULES +
"\nWhat is the minimum possible time, in hours from the start, at which all nine tasks are finished?\n\n" + INT)))

# p12 -----------------------------------------------------------------------
G = ["###############",
     "#S....#.....#E#",
     "#.###.#.###.#B#",
     "#.#a#...#b#...#",
     "#.#.#####.#####",
     "#...#...A.....#",
     "###.#.#######.#",
     "#.....#.......#",
     "###############"]
d, path = maze(G)
cases.append(dict(id='p12', d='hard', ans=d, notes=f"{VER} BFS over (row, col, keys held). Both keys are required (removing either key makes E unreachable). Shortest route length {d}.", prompt=(
"A robot moves on this grid (each row is shown on one line; every row has 15 characters):\n\n"
+ '\n'.join(G) + "\n\n"
"Legend:\n"
"- # is a wall. The robot can never enter a wall square.\n"
"- . is an open floor square.\n"
"- S is the robot's starting square and E is the exit (both are open floor).\n"
"- a and b are keys (open floor). The robot picks up a key automatically when it enters that square and keeps it forever; keys are never used up.\n"
"- A and B are locked doors. The robot may enter door A only if it has already picked up key a, and door B only if it has already picked up key b. Once it has the matching key, a door square behaves like open floor.\n\n"
"In one step the robot moves exactly one square up, down, left or right (never diagonally). Revisiting squares is allowed.\n\n"
"What is the minimum number of steps needed for the robot to go from S to E?\n\n" + INT)))

# p13 -----------------------------------------------------------------------
d, _ = sliding((1, 4, 0, 7, 2, 6, 5, 8, 3), (1, 2, 3, 4, 5, 6, 7, 8, 0), 3, 3)
cases.append(dict(id='p13', d='extreme', ans=d, notes=f"{VER} BFS over all 181,440 reachable 3x3 states (distance from goal).", prompt=(
"A 3x3 sliding puzzle has eight numbered tiles and one empty square (shown as _).\n\n"
"Current position:\n"
"1 4 _\n"
"7 2 6\n"
"5 8 3\n\n"
"Goal position:\n"
"1 2 3\n"
"4 5 6\n"
"7 8 _\n\n"
"One move slides a single tile that is horizontally or vertically adjacent to the empty square into the empty square.\n\n"
"What is the minimum number of moves needed to reach the goal position?\n\n" + INT)))

# p14 -----------------------------------------------------------------------
s14 = [None] * 4
for dd in (4, 1): s14[dd - 1] = 0
for dd in (3, 2): s14[dd - 1] = 2
allowed = {(0, 1), (1, 0), (1, 2), (2, 1)}
d, _ = hanoi(4, s14, [2] * 4, allowed)
d_std, _ = hanoi(3, [0] * 3, [2] * 3, allowed)
assert d_std == 26
cases.append(dict(id='p14', d='extreme', ans=d, notes=f"{VER} BFS over 3^4 placements with only A<->B and B<->C moves (sanity check: the standard 3-disk A->C version gives 26 = 3^3 - 1).", prompt=(
"Three pegs stand in a row: A on the left, B in the middle, C on the right. There are four disks numbered 1 (smallest) to 4 (largest).\n\n"
"Current position:\n"
"- Peg A: disk 4 at the bottom with disk 1 on top of it.\n"
"- Peg B: empty.\n"
"- Peg C: disk 3 at the bottom with disk 2 on top of it.\n\n"
"A move takes the top disk of one peg and places it on top of a neighbouring peg. Only these moves exist: A to B, B to A, B to C and C to B. A disk can never be moved directly between A and C. A disk may never be placed on a smaller disk.\n\n"
"What is the minimum number of moves needed to get all four disks onto peg C?\n\n" + INT)))

# p15 -----------------------------------------------------------------------
dur = [3, 2, 4, 4, 5, 4, 3, 5, 4, 3]; need = [1, 1, 1, 2, 1, 3, 3, 1, 2, 1]; pred = [[], [], [1], [0, 2], [2, 3], [], [2, 4, 5], [2], [5], [0]]
m1 = method1(dur, pred, need, 3); m2 = method2(dur, pred, need, 3); ms, st = best_schedule(dur, pred, need, 3)
assert m1 == m2 == ms
L = 'ABCDEFGHIJ'
sched_txt = ', '.join(f"{L[i]}@{st[i]}-{st[i]+dur[i]}x{need[i]}" for i in sorted(st, key=lambda i: (st[i], i)))
rows = []
for i in range(10):
    rows.append(f"{L[i]} | {dur[i]} | {need[i]} | {', '.join(L[p] for p in pred[i]) if pred[i] else 'none'}")
cases.append(dict(id='p15', d='extreme', ans=m1, notes=f"Exact optimum by two independent methods in planning/sched.py (event-driven search over active schedules = {m1}; serial schedule generation over all precedence-feasible orders = {m2}). Lower bounds: work 59/3 -> 20, critical path 18; critical-path-priority list scheduling gives 26. Optimal schedule (task@start-end x workers): {sched_txt}.", prompt=(
"A workshop has exactly 3 workers and ten tasks, A to J. Some tasks need more than one worker at the same time.\n\n"
"Task | Duration (hours) | Workers needed | Prerequisites (must be finished first)\n"
+ '\n'.join(rows) + "\n\n" + SCHED_RULES +
"- At no moment may the tasks in progress need more than 3 workers in total.\n"
"\nWhat is the minimum possible time, in hours from the start, at which all ten tasks are finished?\n\n" + INT)))

# ---------------------------------------------------------------------------
out = []
EST = {'easy': 4000, 'medium': 8000, 'hard': 14000, 'extreme': 20000}
for c in cases:
    out.append({'id': c['id'], 'prompt': c['prompt'], 'expected': c['ans'], 'notes': f"[{c['d']}] " + c['notes']})
    print(c['id'], c['d'], c['ans'])
meta = dict(
    id='reasoning.planning', category='reasoning', name='Shortest Plans',
    description='Custom shortest-plan puzzles (jugs, crossings, disks, sliding tiles, lamp panels, pancakes, a key-and-door maze and resource-constrained scheduling) where the answer is the proven minimum. Models must both find a plan and be sure no shorter plan exists, which separates genuine search from plausible-looking but suboptimal plans.',
    difficulty='hard', tags=['planning', 'search', 'optimisation', 'bfs-verified'],
    hook='Find the shortest plan — one move too many and it scores zero.',
    maxOutputTokens=32000,
    estimate={'inputTokens': max(tok(c['prompt']) for c in out), 'outputTokens': 12000},
    scorer={'type': 'number', 'tolerance': 0},
)
print(write_test(meta, out))
