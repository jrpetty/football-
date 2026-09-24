"""Builds tests/reasoning/planning-extreme.json from plan_extreme.py results.
Usage (from verification/frontier-reasoning): python3 build_planning.py [gauntlet-root, default ../..]"""
import json
import sys

import plan_extreme as V

TAIL = "Give the answer as a single integer (digits only, no units). Give exactly one answer. If you give more than one answer, it will be marked wrong."
VER = "Verified by exhaustive search in frontier-reasoning/plan_extreme.py and re-verified by independent JS code in plan_extreme_crosscheck.mjs."

CASES = [
    ("p01", V.p01, "hard", """A bakery must plan flour deliveries for the next 8 weeks. The number of sacks of flour it uses each week is fixed:

Week:       1   2   3   4   5   6   7   8
Sacks used: 30  50  20  70  40  10  60  30

Rules:
- The storeroom is empty before week 1. A delivery arrives at the very start of a week, and delivered flour can be used in that same week.
- The bakery may never run short: in every week, the flour in the storeroom (including that week's delivery) must cover that week's use.
- There is at most one delivery per week, and a delivery can be any whole number of sacks.
- Each week with a delivery costs a fixed fee of $120, plus $40 for every truck used. One truck carries at most 80 sacks, so a delivery of q sacks needs the smallest number of trucks that can carry q sacks (for example, 80 sacks need 1 truck and 90 sacks need 2 trucks).
- Storage costs $1 for every sack left in the storeroom at the end of a week (after that week's use). Sacks may be left over at the end of week 8, but they are charged storage like any other week.
- At the end of every week, at most 100 sacks may be left in the storeroom.
- The price of the flour itself is the same in every plan and is ignored.

What is the minimum possible total cost (delivery fees plus truck costs plus storage costs), in dollars, over the 8 weeks?""",
     "Dynamic programming over (week, end-of-week stock 0..100) with every whole delivery size. The optimum breaks the zero-inventory-ordering rule: carrying 10 extra sacks out of week 4 lets week 7 use one truck instead of two, so the best plan that only orders when the store is empty costs $780 (the classic Wagner-Whitin answer) while the true optimum is $770"),
    ("p02", V.p02, "hard", """A rover must drive from its base to a research station 9 km due east along a straight track. There is a marker at every whole kilometre: marker 0 is the base and marker 9 is the station.

Rules:
- The rover can hold at most 5 fuel cells at a time and carries no other cargo.
- Driving 1 km (from one marker to the next, in either direction) uses exactly 1 fuel cell from the rover's load. The rover cannot move with 0 cells, and it can only stop or turn around at markers.
- Fuel cells are available only at the base, in unlimited supply. At the base the rover can load cells, up to its limit of 5.
- At any marker the rover may leave any number of the cells it is carrying in a depot at that marker, and on any later visit it may collect any number of the cells stored there (up to its limit of 5). Stored cells never go bad and nothing else moves them.

What is the minimum total number of fuel cells that must be loaded at the base for the rover to reach the station? (The rover does not need to come back.)""",
     "Discrete jeep problem, solved by Dijkstra over (position, cells carried, depot contents at markers 1..8) with cost = cells loaded at the base; the depot-size bound used to keep the search finite (40) was doubled to 80 with the same result. One optimal plan uses six full loads and a final load of 3, building depots at km 1-4 and leaving km 4 with 5 cells for the last 5 km"),
    ("p03", V.p03, "hard", """Nine hikers must cross a rope bridge at night. Their crossing times and weights are:
- Ana: 5 minutes, 81 kg
- Bo: 12 minutes, 76 kg
- Cy: 13 minutes, 84 kg
- Dee: 15 minutes, 71 kg
- Ed: 17 minutes, 65 kg
- Flo: 20 minutes, 95 kg
- Gil: 21 minutes, 91 kg
- Hal: 23 minutes, 62 kg
- Ivy: 24 minutes, 51 kg

Rules:
- At most three people can be on the bridge at the same time, and their total weight may not exceed 200 kg.
- There is exactly one lantern, and every crossing (in either direction) must carry it. It cannot be thrown across or left on the bridge; it only gets back to the start side by someone carrying it.
- People who cross together move as one group at the pace of the slowest member of the group.
- Only crossing time counts; time spent on the banks is zero.

What is the minimum total time, in minutes, for all nine hikers to reach the far side?""",
     "Dijkstra over (set of people on the start side, lantern side) with every group of 1-3 people weighing at most 200 kg. The weight limit rules out the usual 'slowest three together' trip (Gil + Hal + Ivy = 204 kg), and the optimal plan even sends a mid-speed hiker (Ed, 17 min) back with the lantern"),
    ("p04", V.p04, "hard", """A small workshop has three machines: a saw, a drill and a paint booth. Five jobs, A to E, each consist of three operations that must be done in the order listed:

Job A (paint colour red): drill 2 hours, then paint 2 hours, then saw 2 hours
Job B (paint colour black): paint 4 hours, then saw 4 hours, then drill 1 hour
Job C (paint colour white): drill 3 hours, then saw 1 hour, then paint 4 hours
Job D (paint colour white): saw 1 hour, then paint 3 hours, then drill 2 hours
Job E (paint colour black): saw 3 hours, then drill 2 hours, then paint 5 hours

Rules:
- Each machine works on at most one operation at a time, and each job can be on at most one machine at a time. An operation, once started, runs without interruption for its full duration. Moving a job between machines takes no time. All jobs are available at time 0.
- The paint booth's current colour is the colour of the job it painted most recently; at time 0 it is white. Before painting a job of a different colour, the booth must be changed over, which takes: white to black 4 hours, black to white 6 hours, white to red 2 hours, red to white 5 hours, black to red 3 hours, red to black 2 hours. No changeover is needed between two jobs of the same colour.
- A changeover cannot overlap with painting, but it may be done while the booth is waiting for its next job to arrive. The saw and the drill never need changeovers.

What is the minimum possible time, in hours, from time 0 until all five jobs are completely finished?""",
     "Exhaustive enumeration of all 5! x 5! x 5! machine sequences, each evaluated as the earliest-start (semi-active) schedule with anticipatory changeovers; the JS cross-check evaluates the same sequences by longest paths in the disjunctive graph. The paint booth alone gives a lower bound of 22 (18 hours of painting plus at least 4 hours of changeovers in any colour order); with free changeovers the optimum would be 18"),
    ("p05", V.p05, "extreme", """Four married couples must cross a river from the west bank to the east bank. The husbands are Alan, Ben, Carl and Dan, and their wives are Amy (Alan's wife), Bea (Ben's wife), Cat (Carl's wife) and Dora (Dan's wife). In the middle of the river there is an island. There is one boat, which starts at the west bank with everyone.

Rules:
- The boat carries one or two people and cannot move empty.
- Only the four wives can row, so every trip must have at least one wife in the boat.
- One trip takes the boat from one of the three places (west bank, island, east bank) directly to one of the other two places. Everyone in the boat gets out at the end of each trip (anyone may get back in for a later trip).
- No wife may ever be in the company of a man other than her husband unless her husband is also present. This applies to the people in the boat during a trip and to the people at each of the three places after every trip.
- Nobody can swim or cross in any other way.

What is the minimum number of trips needed to get all eight people to the east bank?""",
     "Breadth-first search over (place of each of the 8 people, boat place): 3^8 x 3 states. Without the island the task is impossible with a two-person boat; with the island and all eight able to row it takes 16 trips; letting only the wives row raises it to 22 (only the husbands: 21)"),
    ("p06", V.p06, "extreme", """A sliding-block traffic puzzle is played on a 6 x 6 grid. Each letter marks one vehicle; a vehicle occupies all squares with its letter, in a straight line of 2 or 3 squares. A dot (.) is an empty square. Rows are numbered 1 to 6 from top to bottom and columns 1 to 6 from left to right.

  1 2 3 4 5 6
1 G . B B B E
2 G D D D . E
3 X X H . I .
4 . . H A I C
5 F . K A I C
6 F . K A J J

Vehicles lying along a row (X, B, D, J) can only slide left or right; vehicles lying along a column (G, E, H, I, A, C, F, K) can only slide up or down. One move slides one vehicle any number of squares (at least one) in one direction, through empty squares only. Vehicles cannot turn, jump over each other or leave the grid.

The goal is to get vehicle X to the right edge, so that X occupies squares (row 3, column 5) and (row 3, column 6). What is the minimum number of moves needed?""",
     "Breadth-first search over vehicle positions (Python) and over grid strings (JS)"),
    ("p07", V.p07, "extreme", """A board has nine spots: a hub (spot 0) and eight rim spots numbered 1 to 8 in order around a circle. The hub is connected to each of the eight rim spots, and each rim spot is connected to the two rim spots next to it (spot 8 is next to spot 1). There are no other connections.

Each spot holds one numbered token. At the moment:
- spot 0 (the hub) holds token 0;
- spots 1, 2, 3, 4, 5, 6, 7, 8 hold tokens 7, 8, 1, 2, 3, 4, 5, 6, respectively.

One move swaps the tokens on two connected spots. What is the minimum number of moves needed so that every spot k holds token k (for every k from 0 to 8)?""",
     "Breadth-first search over all 9! token arrangements. Every token needs 2 steps (distance-sum lower bound 16/2 = 8), yet 8 or 9 swaps are impossible; one optimal plan uses only swaps with the hub"),
    ("p08", V.p08, "extreme", """A 3 x 3 grid holds nine tiles numbered 1 to 9. It has four overlapping 2 x 2 blocks: top-left (rows 1-2, columns 1-2), top-right (rows 1-2, columns 2-3), bottom-left (rows 2-3, columns 1-2) and bottom-right (rows 2-3, columns 2-3).

One move turns one block a quarter turn clockwise: within that block, the tile in its top-left square moves to its top-right square, the top-right tile moves to the bottom-right square, the bottom-right tile moves to the bottom-left square, and the bottom-left tile moves to the top-left square. Tiles outside the block do not move. Anticlockwise turns are not allowed (three clockwise turns of the same block have the same effect as one anticlockwise turn, but they count as three moves).

Current grid (rows from top to bottom):
2 1 3
4 5 6
7 8 9

Goal grid:
1 2 3
4 5 6
7 8 9

What is the minimum number of moves needed to reach the goal grid?""",
     "Breadth-first search over all 9! = 362,880 arrangements (every arrangement is reachable; the hardest needs 15 moves). Each move is a 4-cycle, an odd permutation, so a single swap needs an odd number of moves; 1, 3, 5 and 7 are all impossible here"),
    ("p09", V.p09, "extreme", """A stack holds six pancakes of different sizes, numbered 1 (smallest) to 6 (largest). Each pancake has one burnt side. Reading the stack from top to bottom, it is currently:

1 (burnt side down), 2 (burnt side up), 3 (burnt side down), 4 (burnt side up), 5 (burnt side down), 6 (burnt side up)

One flip means: choose a number k from 1 to 6 and turn over the top k pancakes together as one block with a spatula. This reverses the order of those k pancakes and turns each of them upside down (a burnt side that faced up now faces down, and vice versa). The pancakes below them do not move.

What is the minimum number of flips needed to reach the stack 1, 2, 3, 4, 5, 6 from top to bottom with every burnt side facing down?""",
     "Breadth-first search over all 2^6 x 6! = 46,080 signed stacks (the farthest stack needs 12 flips)"),
    ("p10", V.p10, "extreme", """A sliding puzzle has 3 rows and 4 columns holding eleven coloured tiles and one empty square (_). There are four red tiles (R), three yellow tiles (Y) and four blue tiles (B); tiles of the same colour are identical and interchangeable.

Current position (rows from top to bottom):
B R R R
Y Y Y _
R B B B

Goal position:
R R R R
Y Y Y _
B B B B

One move slides a single tile that is horizontally or vertically next to the empty square into the empty square. Tiles cannot be lifted out, rotated or moved diagonally.

What is the minimum number of moves needed to reach the goal position?""",
     "Breadth-first search over all 138,600 colour arrangements (the farthest needs 34 moves). Only the two left-hand corner tiles are wrong (Manhattan lower bound 4), yet exchanging them needs 16 moves"),
]


def main(root):
    cases = []
    for cid, fn, diff, body, note in CASES:
        fn()
        cases.append({
            "id": cid,
            "prompt": body.strip() + "\n\n" + TAIL,
            "expected": V.RESULTS[cid],
            "notes": f"[{diff}] {note}. {VER} One optimal plan: {V.PLANS[cid]}.",
        })
    test = {
        "kind": "prompt",
        "id": "reasoning.planning-extreme",
        "version": "1.0.0",
        "name": "Shortest Plans: Extreme",
        "category": "reasoning",
        "description": "Ten optimal-plan puzzles whose state spaces (10^3 to 10^6 states) are far too large to search by hand: a weight-limited torch bridge, a jealous-couples crossing with an island where only the wives row, a job shop with paint changeovers, a discrete jeep (fuel depot) problem, capacitated lot sizing where the textbook zero-inventory rule is wrong, Rush Hour, token swapping on a wheel, clockwise-only rotation blocks, burnt pancakes and a coloured sliding puzzle. The answer is the proven minimum, so a model must both find an optimal plan and rule out anything shorter. Every minimum is verified by exhaustive search and an independent JS re-implementation.",
        "difficulty": "extreme",
        "tags": ["planning", "search", "optimisation", "scheduling", "frontier", "bfs-verified"],
        "hook": "Ten puzzles too big to brute-force by hand. One move over the minimum scores zero.",
        "maxOutputTokens": 32000,
        "estimate": {"inputTokens": 420, "outputTokens": 24000},
        "author": "Gauntlet Core",
        "createdAt": "2026-09-24",
        "scorer": {"type": "number", "tolerance": 0},
        "cases": cases,
    }
    path = f"{root}/tests/reasoning/planning-extreme.json"
    with open(path, "w") as fh:
        json.dump(test, fh, indent=2, ensure_ascii=False)
        fh.write("\n")
    print("wrote", path, len(cases), "cases")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "../..")
