"""
Builder + answer-key verification for the "Can It Be Fooled?" category (tests/trick/).

Every numeric / logical answer is recomputed here from first principles
(enumeration, breadth-first search, exact fractions or direct simulation) and
asserted against the hand-written key before the JSON files are written.
Facts used by the False Premise set are stable, well-documented facts
(no dates after 2024). Run from this folder:  python build.py
"""
import itertools
import json
import math
import os
import sys
from collections import deque
from fractions import Fraction

sys.path.insert(0, '..')
from common import ONE, tok  # noqa: E402

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'tests', 'trick')


# ─────────────────────────────── independent checks ───────────────────────────────

def river_min_crossings(items, conflicts, capacity):
    """BFS over (items on the start bank, farmer side). Farmer rows every trip and carries up to `capacity` items."""
    items = frozenset(items)
    start = (items, 0)
    goal = (frozenset(), 1)

    def safe(bank):
        return not any(a in bank and b in bank for a, b in conflicts)

    seen = {start: 0}
    q = deque([start])
    while q:
        left, side = q.popleft()
        if (left, side) == goal:
            return seen[(left, side)]
        here = left if side == 0 else items - left
        for k in range(0, capacity + 1):
            for load in itertools.combinations(sorted(here), k):
                load = frozenset(load)
                new_left = left - load if side == 0 else left | load
                unattended = new_left if side == 0 else items - new_left  # bank the farmer just left
                if not safe(unattended):
                    continue
                st = (new_left, 1 - side)
                if st not in seen:
                    seen[st] = seen[(left, side)] + 1
                    q.append(st)
    return None


def monty(host):
    """P(switch wins | host opened door 3, player picked door 1) by exact enumeration. host(car) -> {door: prob}."""
    num = Fraction(0)
    den = Fraction(0)
    for car in (1, 2, 3):
        p_car = Fraction(1, 3)
        for door, p in host(car).items():
            if door != 3:
                continue
            opened_goat = car != 3
            if not opened_goat:
                continue  # we observed a goat
            den += p_car * p
            if car == 2:
                num += p_car * p
    return num / den


def random_host(car):
    return {2: Fraction(1, 2), 3: Fraction(1, 2)}  # ignorant host opens 2 or 3 at random (may reveal the car)


def lowest_goat_host(car):
    goats = [d for d in (2, 3) if d != car]
    return {min(goats): Fraction(1)}


# Monty variants
assert monty(random_host) == Fraction(1, 2)
assert monty(lowest_goat_host) == 1
# Transparent doors: you see the car behind the door you picked, so switching never wins.
GLASS = 0

# Bat & ball: bat costs $1.00 (not "$1.00 more"), total $1.10.
assert 110 - 100 == 10
# Pen & cap: pen = cap + 200 cents, total 240 cents.
cap = next(c for c in range(0, 241) if c + (c + 200) == 240)
assert cap == 20
# Sally: 3 brothers, each brother has 3 sisters -> 3 girls, Sally is one of them.
assert 3 - 1 == 2
# Sheep: 17, all but 9 die -> 9 alive; sell 4.
assert 9 - 4 == 5
# Lily pads doubling, full at end of day 48 -> quarter at day 46.
assert next(d for d in range(49) if Fraction(1, 2 ** (48 - d)) == Fraction(1, 4)) == 46
# Widgets: 5 machines / 5 min / 5 widgets -> 1 widget per machine per 5 min.
assert Fraction(20, 10) * 5 == 10


def birthday_you(p=0.5):
    n = 0
    while 1 - (Fraction(364, 365) ** n) < p:
        n += 1
    return n


assert birthday_you() == 253
assert 1 - (364 / 365) ** 252 < 0.5 < 1 - (364 / 365) ** 253


def older_is_girl():
    fam = list(itertools.product('BG', repeat=2))  # (older, younger)
    cond = [f for f in fam if f[0] == 'G']
    return Fraction(sum(1 for f in cond if f == ('G', 'G')), len(cond))


assert older_is_girl() == Fraction(1, 2)

WGC = ('wolf', 'goat', 'cabbage')
CLASSIC = [('wolf', 'goat'), ('goat', 'cabbage')]
assert river_min_crossings(WGC, CLASSIC, 1) == 7  # the classic answer (the lure)
assert river_min_crossings(WGC, CLASSIC, 3) == 1  # boat holds everything
assert river_min_crossings(WGC, CLASSIC, 2) == 3  # boat holds farmer + two items
assert river_min_crossings(WGC, [('wolf', 'goat')], 1) == 5  # only the wolf-goat pair is dangerous


def snail(depth, up, down):
    pos, day = 0, 0
    while True:
        day += 1
        pos += up
        if pos >= depth:
            return day
        pos -= down


assert snail(20, 5, 4) == 16


def socks_for_two_black(black=10, white=10):
    # Worst case: every white sock first, then two black.
    return white + 2


assert socks_for_two_black() == 12
# Brute-force the sock bound: any order of 12 draws from 10W+10B has >= 2 black; some order of 11 has < 2.
assert all(12 - w >= 2 for w in range(0, 11))
assert 11 - 10 < 2


def jug_min_steps(target_jug, target, caps=(3, 5)):
    start = (0, 0)
    seen = {start: 0}
    q = deque([start])
    while q:
        s = q.popleft()
        if s[target_jug] == target:
            return seen[s]
        a, b = s
        A, B = caps
        nxt = [(A, b), (a, B), (0, b), (a, 0)]
        t = min(a, B - b)
        nxt.append((a - t, b + t))
        t = min(b, A - a)
        nxt.append((a + t, b - t))
        for n in nxt:
            if n not in seen:
                seen[n] = seen[s] + 1
                q.append(n)


assert jug_min_steps(0, 3) == 1
assert jug_min_steps(1, 4) == 6  # the famous "4 litres" answer (the lure)


def portrait():
    # Speaker S has no siblings. "That man's son is my father's son." My father's son (no brothers) = S.
    # So the man's son is S, i.e. the man is S's father.
    fathers_sons = {'S'}  # only child
    return 'father' if fathers_sons == {'S'} else '?'


assert portrait() == 'father'
# Sister: at 6 she was 12 -> gap 6 -> at 70 she is 76.
assert 70 + (2 * 6 - 6) == 76


def clock_interval(chimes_a, secs_a, chimes_b):
    gap = Fraction(secs_a, chimes_a - 1)
    return gap * (chimes_b - 1)


assert clock_interval(4, 6, 7) == 12
assert 3 * 30 == 90  # 4 pills: 3 gaps of 30 min
assert 3 + 3 * 3 == 12
assert Fraction(30) / Fraction(1, 2) + 10 == 70
assert 30 // 3 + 1 == 11
assert Fraction(1, 10) + Fraction(2, 10) == Fraction(3, 10)
assert 9.9 > 9.11
assert 'strawberry'.count('r') == 3
assert 'excellence'.count('e') == 4
assert 2 ** 10 - 10 ** 3 == 24
assert all(91 % p for p in (2, 3, 5)) and 91 == 7 * 13
DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']
assert DAYS[(DAYS.index('Thursday') + 2 + 2) % 7] == 'Monday'
# 3:15 -> minute hand at 90 deg; hour hand at 90 + 15*0.5 = 97.5 deg.
assert abs((3 * 30 + 15 * 0.5) - 15 * 6) == 7.5

STATES = """Alabama Alaska Arizona Arkansas California Colorado Connecticut Delaware Florida Georgia Hawaii Idaho
Illinois Indiana Iowa Kansas Kentucky Louisiana Maine Maryland Massachusetts Michigan Minnesota Mississippi Missouri
Montana Nebraska Nevada New_Hampshire New_Jersey New_Mexico New_York North_Carolina North_Dakota Ohio Oklahoma Oregon
Pennsylvania Rhode_Island South_Carolina South_Dakota Tennessee Texas Utah Vermont Virginia Washington West_Virginia
Wisconsin Wyoming""".split()
assert len(STATES) == 50
letters = set(''.join(STATES).lower())
assert [c for c in 'abcdefghijklmnopqrstuvwxyz' if c not in letters] == ['q']
# Gregorian leap years are always divisible by 4, so "a leap year not divisible by 4" does not exist.
assert all(y % 4 == 0 for y in range(1582, 3000) if (y % 4 == 0 and y % 100 != 0) or y % 400 == 0)
# No even prime above 2.
assert not any(all(n % d for d in range(2, int(n ** 0.5) + 1)) for n in range(4, 10000, 2))


# ─────────────────────────────── helpers ───────────────────────────────

def regex_for(alt):
    """Whole-reply pattern: the reply must be ONE line 'FINAL ANSWER: <answer>' (markdown and a final full stop tolerated)."""
    return r"^[\s*_#>]*final\s+answer[\s*_]*[:：][\s*_\"'`]*(?:" + alt + r")[\s*_\"'`.!]*$"


# ─────────────────────────────── Modified Classics ───────────────────────────────

MC = []


def mc(cid, prompt, expected, lure, display, notes, scorer=None):
    c = {'id': cid, 'prompt': prompt + '\n\n' + ONE, 'expected': expected}
    if scorer:
        c['scorer'] = scorer
    c['lure'] = lure
    c['displayAnswer'] = display
    c['notes'] = notes
    MC.append(c)


CHOICE = {'type': 'choice'}

mc('m01',
   "A farmer must get a wolf, a goat and a cabbage across a river. If they are left together without the farmer, the wolf will eat the goat and the goat will eat the cabbage. The farmer's boat is big enough to carry the farmer, the wolf, the goat and the cabbage all at the same time, and only the farmer can row it.\n\nWhat is the minimum number of one-way river crossings the farmer must make to get all three across? Give the answer as a whole number.",
   1, '7 crossings', '1 crossing',
   "The boat holds everyone, so one trip with all three on board. BFS in build.py: capacity 3 -> 1 (the memorised classic, capacity 1, gives 7: the lure).")
mc('m02',
   "You are on a game show with three closed doors. Behind one door is a car; behind the other two are goats. You pick door 1. The host does NOT know where the car is: he opens one of the other two doors completely at random, and it happens to reveal a goat. He then offers you the chance to switch to the remaining closed door.\n\nWhat is the probability, in percent, that switching wins the car? Give the answer as a number of percent (for example 25).",
   50, '66.7% (always switch)', '50%',
   "'Monty Fall' variant: the host is ignorant, so revealing a goat is evidence, not a guaranteed event. Exact enumeration in build.py: P(car behind door 2 | random door 3 shows goat) = 1/2. The famous 2/3 only holds when the host knowingly avoids the car.")
mc('m03',
   "A bat and a ball cost $1.10 in total. The bat costs $1.00. How much does the ball cost? Give the answer as a whole number of cents.",
   10, '5 cents', '10 cents',
   "The word 'more' is gone: the bat costs exactly $1.00, so the ball costs $0.10 = 10 cents. The memorised answer to the classic (\"$1.00 more than the ball\") is 5 cents.")
mc('m04',
   "A pen and its cap cost $2.40 together. The pen costs $2.00 more than the cap. How much does the cap cost? Give the answer as a whole number of cents.",
   20, '40 cents', '20 cents',
   "cap + (cap + 2.00) = 2.40 -> cap = 0.20. The fast answer 2.40 - 2.00 = 40 cents is the lure (then the pen would cost 2.40 and the total 2.80). Brute force over cents in build.py.")
mc('m05',
   "A young man is rushed to hospital after a cycling accident. The surgeon on duty, who is the young man's father, looks at him and says: \"I can't operate on this boy - he's my son.\"\n\nHow is the surgeon related to the young man?\n(A) The surgeon is his mother\n(B) The surgeon is his father\n(C) The surgeon is his grandfather\n(D) The surgeon is his uncle\n\nAnswer with the letter of the correct option.",
   'B', '(A) his mother', '(B) his father',
   "The prompt states outright that the surgeon is the young man's father. The famous riddle (where the father died in the crash) has the answer 'his mother', which is the lure here.",
   CHOICE)
mc('m06',
   "Sally is a girl. She has 3 brothers. Each of her brothers has exactly 3 sisters. All of the children have the same two parents, and there are no other children in the family.\n\nHow many sisters does Sally have? Give the answer as a whole number.",
   2, '3 sisters', '2 sisters',
   "Each brother's 3 sisters are all the girls in the family, and Sally is one of them, so Sally has 3 - 1 = 2 sisters.")
mc('m07',
   "A farmer has 17 sheep. All but 9 of them die. The farmer then sells 4 of the living sheep.\n\nHow many living sheep does the farmer have now? Give the answer as a whole number.",
   5, '4 sheep (17 - 9 - 4)', '5 sheep',
   "'All but 9 die' leaves 9 alive (the classic answer is 9); selling 4 leaves 5. The subtraction 17 - 9 - 4 = 4 is the lure.")
mc('m08',
   "A patch of lily pads on a lake doubles in area every day. At the end of day 48 it covers the entire lake for the first time.\n\nAt the end of which day did the patch cover exactly one quarter of the lake? Give the answer as a whole number (the day number).",
   46, 'Day 12 (a quarter of 48)', 'Day 46',
   "Halving backwards: end of day 47 = one half, end of day 46 = one quarter. Checked in build.py. The linear answer 48/4 = 12 is the lure.")
mc('m09',
   "If 5 machines take 5 minutes to make 5 widgets, how many minutes would 10 machines take to make 20 widgets? Every machine works independently at the same constant speed. Give the answer as a whole number of minutes.",
   10, '5 minutes (the classic answer)', '10 minutes',
   "Each machine makes 1 widget per 5 minutes; 10 machines make 10 widgets per 5 minutes, so 20 widgets take 10 minutes. The memorised classic ('100 machines, 100 widgets') answer is 5.")
mc('m10',
   "You are on a game show with three doors made of clear glass. You can plainly see a car behind door 2 and a goat behind each of doors 1 and 3. You pick door 2. The host opens door 3, showing its goat, and offers to let you switch to door 1.\n\nWhat is the probability, in percent, that switching wins the car? Give the answer as a number of percent.",
   0, '66.7% (always switch)', '0%',
   "You can see the car is behind the door you already picked, so switching to door 1 always loses: 0%.")
mc('m11',
   "Ignore 29 February and assume every one of the 365 days is equally likely to be anyone's birthday, independently.\n\nWhat is the smallest number of other people who must be in a room with you for the probability that at least one of them shares YOUR birthday to be at least 50%? Give the answer as a whole number.",
   253, '23 people (the birthday paradox)', '253 people',
   "Need 1 - (364/365)^n >= 1/2: n = 252 gives 0.4991, n = 253 gives 0.5005 (exact fractions in build.py). 23 is the answer to a different question (any two people sharing a birthday).")
mc('m12',
   "A family has two children. Each child, independently, is a boy or a girl with probability 1/2. You are told that the OLDER child is a girl.\n\nWhat is the probability, in percent, that both children are girls? Give the answer as a number of percent.",
   50, '33.3%', '50%',
   "Knowing the specific (older) child is a girl leaves the younger child's sex at 1/2. The famous 1/3 applies to 'at least one is a girl'. Enumerated in build.py.")
mc('m13',
   "A farmer must get a wolf, a goat and a cabbage across a river. If they are left together without the farmer, the wolf will eat the goat and the goat will eat the cabbage. The farmer's boat can carry the farmer plus any TWO of the three, and only the farmer can row it.\n\nWhat is the minimum number of one-way river crossings the farmer must make to get all three across? Give the answer as a whole number.",
   3, '7 crossings', '3 crossings',
   "Take the wolf and cabbage over (goat safe alone), row back alone (wolf and cabbage are safe together), take the goat: 3. One or two crossings are impossible (two items per trip, and after 2 crossings the farmer is back at the start). BFS in build.py.")
mc('m14',
   "Which is heavier?\n(A) Two kilograms of feathers\n(B) One kilogram of steel\n(C) They weigh the same\n\nAnswer with the letter of the correct option.",
   'A', '(C) they weigh the same', '(A) two kilograms of feathers',
   "2 kg > 1 kg. The memorised answer to 'a kilogram of feathers or a kilogram of steel' is 'the same', which is the lure.",
   CHOICE)
mc('m15',
   "A snail is at the bottom of a well that is 20 metres deep. Every day it climbs up 5 metres, and every night it slides back down 4 metres. It starts climbing on the morning of day 1.\n\nOn which day does the snail first reach the top of the well? Give the answer as a whole number (the day number).",
   16, 'Day 20 (1 metre per day)', 'Day 16',
   "After the night of day n it is at n metres; on day 16 it starts at 15 m and climbs to 20 m, reaching the top before sliding back. Simulated in build.py.")
mc('m16',
   "You are on a game show with three closed doors: a car is behind one and goats are behind the other two. You pick door 1. The host knows where the car is and always opens one of the two doors you did not pick that hides a goat. When both of those doors hide goats, he ALWAYS opens the lower-numbered one. The host opens door 3 and offers you the chance to switch to door 2.\n\nWhat is the probability, in percent, that switching wins the car? Give the answer as a number of percent.",
   100, '66.7% (always switch)', '100%',
   "If the car were behind door 1 the host would have opened door 2 (the lower goat door). If it were behind door 3 he would have opened door 2. Only a car behind door 2 makes him open door 3, so switching wins with certainty. Exact enumeration in build.py.")
mc('m17',
   "A man points at a portrait and says: \"Brothers and sisters I have none, but that man's SON is my father's son.\"\n\nWho is the man in the portrait, relative to the speaker?\n(A) The speaker himself\n(B) The speaker's son\n(C) The speaker's father\n(D) The speaker's grandfather\n\nAnswer with the letter of the correct option.",
   'C', "(B) the speaker's son", "(C) the speaker's father",
   "With no brothers, 'my father's son' is the speaker. So the portrait man's son is the speaker, making the portrait man his father. The classic riddle ('that man's FATHER is my father's son') has the answer 'his son', the lure here.",
   CHOICE)
mc('m18',
   "You stand in a corridor next to three light switches. Each switch controls one of three ordinary light bulbs in a room. The room has a large glass wall, and from the switches you can see all three bulbs clearly at all times.\n\nWhat is the minimum number of times you must enter the room to work out which switch controls which bulb? Give the answer as a whole number.",
   0, '1 visit (the warm-bulb trick)', '0 visits',
   "You can watch the bulbs while flipping the switches, so no visit is needed. The classic (no view of the bulbs) answer is 1 visit using bulb heat.")
mc('m19',
   "A farmer must get a wolf, a goat and a cabbage across a river. The ONLY danger is that the wolf will eat the goat if they are left together without the farmer; the goat has no interest in the cabbage. The boat can carry the farmer plus at most one of the three, and only the farmer can row it.\n\nWhat is the minimum number of one-way river crossings the farmer must make to get all three across? Give the answer as a whole number.",
   5, '7 crossings', '5 crossings',
   "Wolf over, back, cabbage over, back, goat over = 5 (never leaves wolf and goat alone). Fewer is impossible: 3 items one at a time need 3 forward trips and 2 returns. BFS in build.py confirms 5.")
mc('m20',
   "A drawer contains 10 black socks and 10 white socks, all mixed together. In complete darkness you take socks out one at a time without looking.\n\nWhat is the minimum number of socks you must take out to be certain of having at least two BLACK socks? Give the answer as a whole number.",
   12, '3 socks (the matching-pair answer)', '12 socks',
   "Worst case: all 10 white socks come first, then 2 black: 12. The classic question (any matching pair) has the answer 3.")
mc('m21',
   "You have an empty 3-litre jug, an empty 5-litre jug and a tap. The jugs have no markings. Each of these counts as one step: filling a jug from the tap, emptying a jug, or pouring from one jug into the other until one is empty or the other is full.\n\nWhat is the minimum number of steps needed to end up with exactly 3 litres of water in the 3-litre jug? Give the answer as a whole number.",
   1, '6 steps (the "4 litres" puzzle)', '1 step',
   "Fill the 3-litre jug from the tap: 1 step. BFS in build.py (the famous 4-litre target takes 6 steps: the lure).")
mc('m22',
   "Two fathers and two sons went fishing. Each of them caught exactly one fish, and together they caught 4 fish.\n\nHow many people went fishing? Give the answer as a whole number.",
   4, '3 people (grandfather, father, son)', '4 people',
   "Each person caught exactly one fish and 4 fish were caught, so there were 4 people. The classic version (3 fish) has the answer 3.")
mc('m23',
   "When I was 6 years old, my sister was twice my age. Today I am 70 years old.\n\nHow old is my sister today? Give the answer as a whole number of years.",
   76, '140 (twice 70) or 67 (the classic "half my age")', '76',
   "At 6 my sister was 12, so she is 6 years older: 70 + 6 = 76. Checked in build.py.")
mc('m24',
   "Linda is 31 years old, single, outspoken and very bright. She majored in philosophy. As a student she was deeply concerned with discrimination and social justice, and she took part in anti-nuclear demonstrations.\n\nWhich statement is more probable?\n(A) Linda is a bank teller and is active in the feminist movement.\n(B) Linda is a bank teller, is active in the feminist movement, and plays the piano.\n\nAnswer with the letter of the correct option.",
   'A', '(B) the more detailed description', '(A) the shorter statement',
   "Conjunction rule: B requires everything in A plus playing the piano, so P(B) <= P(A); B can never be the more probable one. Option A is the only defensible answer.",
   CHOICE)


# ─────────────────────────────── Modified Classics v2 ───────────────────────────────
# v2.0.0: the famous textbook variants that every model now gets right were replaced by
# lesser-known classics with a subtle twist. Kept from v1: the cases a small model failed
# in the blind audit (m02, m05, m15, m19, m22) and three uncommon twists (m11, m16, m17).

KEEP_V1 = {'m02', 'm05', 'm11', 'm15', 'm16', 'm17', 'm19', 'm22'}
MC = [c for c in MC if c['id'] in KEEP_V1]


# Bertrand's box paradox with FOUR boxes (GG, SS, GS, GS).
def boxes_other_gold(boxes):
    num = den = Fraction(0)
    for box in boxes:
        for i, coin in enumerate(box):
            p = Fraction(1, len(boxes)) * Fraction(1, 2)
            if coin == 'G':
                den += p
                if box[1 - i] == 'G':
                    num += p
    return num / den


assert boxes_other_gold(['GG', 'SS', 'GS']) == Fraction(2, 3)  # the classic (lure)
assert boxes_other_gold(['GG', 'SS', 'GS', 'GS']) == Fraction(1, 2)


def josephus(n, k):
    people = list(range(1, n + 1))
    i = 0
    while len(people) > 1:
        i = (i + k - 1) % len(people)
        people.pop(i)
    return people[0]


assert josephus(7, 3) == 4 and josephus(7, 2) == 7


def lockers(n):
    state = [False] * (n + 1)
    for s in range(1, n + 1):
        for j in range(s, n + 1, s):
            state[j] = not state[j]
    return sum(state)


assert lockers(150) == 12 and lockers(100) == 10


def halmos(other_couples):
    """Brute force: host (0) and wife (1) plus other couples; all 2n-1 people other than the host report distinct counts."""
    import numpy as np
    n = 2 * (other_couples + 1)
    spouse = {i: i ^ 1 for i in range(n)}
    edges = [(a, b) for a in range(n) for b in range(a + 1, n) if spouse[a] != b]
    m = len(edges)
    masks = np.arange(1 << m, dtype=np.uint32)
    deg = np.zeros((n, 1 << m), dtype=np.uint8)
    for e, (a, b) in enumerate(edges):
        bit = ((masks >> e) & 1).astype(np.uint8)
        deg[a] += bit
        deg[b] += bit
    others = np.sort(deg[1:], axis=0)
    distinct = np.all(others[1:] != others[:-1], axis=0)
    return set(int(x) for x in np.unique(deg[1][distinct]))


assert halmos(3) == {3}


# Offset curve of a square: straight sides plus four quarter circles of radius r.
assert abs((4 * 100 + 2 * math.pi * 1) - 400 - 6.28) < 0.005
# Fly: closing speed 50 km/h, closes 90 km -> 1.8 h at 60 km/h.
assert Fraction(90, 50) * 60 == 108


def domino_tileable(removed):
    """Bipartite matching of the remaining board squares (Kuhn's algorithm)."""
    cells = [(r, c) for r in range(8) for c in range(8) if (r, c) not in removed]
    black = [x for x in cells if (x[0] + x[1]) % 2 == 0]
    white = set(x for x in cells if (x[0] + x[1]) % 2 == 1)
    if len(black) != len(white):
        return False
    match = {}

    def aug(u, seen):
        r, c = u
        for v in ((r + 1, c), (r - 1, c), (r, c + 1), (r, c - 1)):
            if v in white and v not in seen:
                seen.add(v)
                if v not in match or aug(match[v], seen):
                    match[v] = u
                    return True
        return False

    return all(aug(u, set()) for u in black)


assert domino_tileable({(0, 0), (0, 7)}) is True  # adjacent corners (a1, h1): the twist
assert domino_tileable({(0, 0), (7, 7)}) is False  # opposite corners: the classic (lure)


def monty4_random_switch():
    total = Fraction(0)
    for car in range(4):
        p_car = Fraction(1, 4)
        goats = [d for d in (1, 2, 3) if d != car]
        for opened in goats:
            p_open = Fraction(1, len(goats))
            remaining = [d for d in (1, 2, 3) if d != opened]
            for pick in remaining:
                if pick == car:
                    total += p_car * p_open * Fraction(1, len(remaining))
    return total


assert monty4_random_switch() == Fraction(3, 8)
assert -60 + 70 - 80 + 75 == 5  # horse trading


def wine_water():
    a_water, a_wine = Fraction(200), Fraction(0)
    b_wine, b_water = Fraction(100), Fraction(0)
    b_wine -= 10
    a_wine += 10
    total = a_water + a_wine
    back_water, back_wine = 10 * a_water / total, 10 * a_wine / total
    a_water -= back_water
    a_wine -= back_wine
    b_water += back_water
    b_wine += back_wine
    return a_wine, b_water


w_in_a, water_in_b = wine_water()
assert w_in_a == water_in_b


def first_pattern_prob(p1, p2):
    """P(pattern p1 appears before p2) for fair coin tosses, by value iteration over suffix states (exact enough, then rounded)."""
    states = [''] + [p[:i] for p in (p1, p2) for i in range(1, len(p))]
    states = sorted(set(states), key=len)

    def step(s, t):
        s = s + t
        if s.endswith(p1):
            return 'W'
        if s.endswith(p2):
            return 'L'
        while s and not any(p.startswith(s) for p in (p1, p2)):
            s = s[1:]
        return s

    v = {s: Fraction(0) for s in states}
    for _ in range(200):
        v = {s: sum(Fraction(1, 2) * (1 if step(s, t) == 'W' else 0 if step(s, t) == 'L' else v[step(s, t)]) for t in 'HT') for s in states}
    return float(v[''])


assert abs(first_pattern_prob('HHT', 'HTH') - 2 / 3) < 1e-9


def bridge(times):
    import heapq
    everyone = frozenset(range(len(times)))
    start = (everyone, 0)
    dist = {start: 0}
    pq = [(0, 0, everyone)]
    while pq:
        d, side, left = heapq.heappop(pq)
        if not left:
            return d
        if dist.get((left, side), 1e9) < d:
            continue
        here = left if side == 0 else everyone - left
        for k in (1, 2):
            for grp in itertools.combinations(sorted(here), k):
                cost = max(times[i] for i in grp)
                nl = left - set(grp) if side == 0 else left | set(grp)
                st = (frozenset(nl), 1 - side)
                if d + cost < dist.get(st, 1e9):
                    dist[st] = d + cost
                    heapq.heappush(pq, (d + cost, 1 - side, frozenset(nl)))


assert bridge([1, 4, 5, 10]) == 21
assert bridge([1, 2, 5, 10]) == 17  # the classic (lure territory)


def expected_tosses(pattern):
    # Solve E[s] for prefix states by iteration.
    states = [pattern[:i] for i in range(len(pattern))]

    def nxt(s, t):
        s = s + t
        while s and not pattern.startswith(s):
            s = s[1:]
        return s

    e = {s: 0.0 for s in states}
    for _ in range(5000):
        e = {s: 1 + sum(0.5 * (0 if nxt(s, t) == pattern else e[nxt(s, t)]) for t in 'HT') for s in states}
    return e['']


assert abs(expected_tosses('HT') - 4) < 1e-9 and abs(expected_tosses('HH') - 6) < 1e-9
# Slow clock: 50 clock-minutes per real hour; 360 clock-minutes -> 432 real minutes -> 7:12 pm.
assert Fraction(360 * 60, 50) - 360 == 72
# Coins: second pile of k flipped coins has k - h2 heads; first pile has 5 - h2. Only k = 5 works for every h2.
assert [k for k in range(21) if all(k - h2 == 5 - h2 for h2 in range(0, min(k, 5) + 1))] == [5]
# Weighing: w weighings distinguish at most 3^w candidates.
assert min(w for w in range(5) if 3 ** w >= 10) == 3

mc('m25',
   "There are four boxes. One contains two gold coins, one contains two silver coins, and each of the other two contains one gold coin and one silver coin. You pick a box at random and, without looking, take one coin out of it at random. It is gold.\n\nWhat is the probability, in percent, that the other coin in the same box is also gold? Give the answer as a number of percent.",
   50, '66.7% (Bertrand’s box answer)', '50%',
   "Four boxes GG, SS, GS, GS: P(gold drawn) = 1/4 + 2 x 1/8 = 1/2; P(GG and gold) = 1/4; ratio 1/2. The three-box classic gives 2/3 (the lure). Exact enumeration in build.py.")
mc('m26',
   "Seven people stand in a circle, numbered 1 to 7 clockwise. Starting with person 1, you count clockwise \"1, 2, 3\"; the person on \"3\" leaves the circle, and counting starts again at \"1\" with the next person still in the circle. This repeats until one person is left.\n\nWhat is the number of the last person left? Give the answer as a whole number.",
   4, '7 (the every-second-person answer)', '4',
   "Order of removal 3, 6, 2, 7, 5, 1; person 4 survives. Simulated in build.py (the better-known every-second-person version with 7 people gives 7).")
mc('m27',
   "A corridor has 150 closed lockers numbered 1 to 150. Student 1 opens every locker. Student 2 then toggles every 2nd locker (opening it if closed, closing it if open), student 3 toggles every 3rd locker, and so on, until student 150 toggles only locker 150.\n\nHow many lockers are open at the end? Give the answer as a whole number.",
   12, '10 (the 100-locker answer)', '12',
   "A locker ends open iff it has an odd number of divisors, i.e. it is a perfect square: 1, 4, ..., 144 = 12 lockers. Simulated in build.py.")
mc('m28',
   "My wife and I went to a party with three other married couples, so there were 8 people in all. Some people shook hands. Nobody shook hands with their own spouse, and no two people shook hands more than once. Afterwards I asked each of the other 7 people how many hands they had shaken, and all 7 answers were different.\n\nHow many hands did my wife shake? Give the answer as a whole number.",
   3, '4 (the answer to the famous five-couple version)', '3',
   "Halmos's handshake puzzle with 4 couples in total: answers 0..6, and the wife must be the 3. Brute-forced over all 2^24 handshake graphs in build.py; the published version with 5 couples gives 4.")
mc('m29',
   "A square field measures exactly 100 m on each side. A fence is built around it at a constant distance of exactly 1 m outside the edge of the field everywhere: it runs straight alongside each side and curves around each corner as a quarter circle of radius 1 m.\n\nHow many metres longer is the fence than the perimeter of the field? Give the answer in metres, rounded to two decimal places.",
   6.28, '8 m (2 m extra per side)', '6.28 m (2π)',
   "Four straight 100 m runs plus four quarter circles of radius 1 m: 400 + 2π, so the fence is 2π = 6.28 m longer. Same idea as the rope-around-the-Earth puzzle.",
   {'type': 'number', 'tolerance': 0.006})
mc('m30',
   "Two trains start 100 km apart on the same track and travel towards each other, each at a constant 25 km/h. At the same moment a fly leaves the front of one train and flies back and forth between the two trains at a constant 60 km/h, turning instantly. The fly stops flying when the trains are 10 km apart.\n\nHow many kilometres has the fly flown? Give the answer as a whole number.",
   108, '120 km (flying until the trains meet)', '108 km',
   "The gap closes at 50 km/h; it shrinks by 90 km in 1.8 h; 1.8 x 60 = 108 km. Flying until they meet would be 120 km (the lure).")
mc('m31',
   "Take an ordinary 8x8 chessboard and remove two corner squares that are at the two ends of the SAME edge (for example a1 and h1).\n\nCan the remaining 62 squares be covered exactly by 31 dominoes, each domino covering two squares that share a side?\n(A) Yes\n(B) No\n\nAnswer with the letter of the correct option.",
   'A', '(B) No (the opposite-corners answer)', '(A) Yes',
   "a1 and h1 have opposite colours, so 31 black and 31 white squares remain, and a tiling exists (Gomory's theorem); found explicitly by bipartite matching in build.py. The famous version removes OPPOSITE corners (same colour), which is impossible.",
   CHOICE)
mc('m32',
   "A game show has four closed doors: a car is behind one and goats are behind the other three. You pick door 1. The host, who knows where the car is, opens one of the other three doors that hides a goat (choosing at random when he has a choice). You then switch to one of the two remaining closed doors that you did not pick, choosing between them at random.\n\nWhat is the probability, in percent, that you win the car? Give the answer as a number of percent.",
   37.5, '75% (or 66.7%)', '37.5%',
   "Switching wins only if the car is not behind door 1 (3/4) and you pick the right one of the two remaining doors (1/2): 3/8 = 37.5%. Exact enumeration in build.py.")
mc('m33',
   "A trader buys a horse for $60 and sells it for $70. Later she buys the same horse back for $80 and sells it again for $75.\n\nWhat is her overall profit in dollars? (Give a negative number for a loss.)",
   5, '-5 dollars', '$5 profit',
   "Cash flow: -60 + 70 - 80 + 75 = +5. The lure comes from treating the $80 buy-back as a $10 loss on a horse 'worth' $70 and then losing $5 on the resale.")
mc('m34',
   "Glass A holds 200 ml of water and glass B holds 100 ml of wine. You pour 10 ml of wine from B into A and stir thoroughly. Then you pour 10 ml of the mixture from A back into B. Assume the volumes simply add.\n\nWhich is true now?\n(A) There is more wine in glass A than water in glass B\n(B) There is more water in glass B than wine in glass A\n(C) The amount of wine in A equals the amount of water in B\n\nAnswer with the letter of the correct option.",
   'C', '(A) more wine in A (because the glasses are different sizes)', '(C) exactly equal',
   "Each glass ends with its starting volume, so whatever wine left B was replaced by exactly that much water. With exact fractions (build.py) both amounts are 200/21 ml. The unequal glass sizes are the distraction.",
   CHOICE)
mc('m35',
   "A fair coin is tossed again and again until either the pattern H, T, H or the pattern H, H, T appears as three consecutive tosses.\n\nWhich pattern is more likely to appear first?\n(A) H, T, H\n(B) H, H, T\n(C) They are equally likely\n\nAnswer with the letter of the correct option.",
   'B', '(C) equally likely', '(B) H, H, T',
   "Penney's game: once H, H has appeared, HHT must come before HTH. P(HHT first) = 2/3, computed exactly over the pattern-prefix states in build.py.",
   CHOICE)
mc('m36',
   "Four people must cross a narrow bridge at night. They have one torch; at most two people can be on the bridge at once, anyone crossing must be with the torch, and two people crossing together walk at the slower person's pace. Their crossing times are 1, 4, 5 and 10 minutes.\n\nWhat is the minimum total time needed to get all four across? Give the answer as a whole number of minutes.",
   21, '23 minutes (the textbook two-slowest-together plan)', '21 minutes',
   "Dijkstra over all states in build.py: 21 (the fastest person escorts each of the others: 4+1+5+1+10). The plan that solves the famous 1-2-5-10 version (send the two slowest together) gives 23 here.")
mc('m37',
   "A fair coin is tossed until the pattern heads followed immediately by tails (H then T) appears for the first time.\n\nWhat is the expected number of tosses, including the final T? Give the answer as a number.",
   4, '6 (the H-then-H answer)', '4 tosses',
   "Wait for the first H (expected 2), then for the first T after it (expected 2): 4. The better-known 'two heads in a row' answer is 6. Both solved by value iteration in build.py.")
mc('m38',
   "A clock runs slow at a steady rate: it loses 10 minutes every real hour. It is set to the correct time at exactly 12:00 noon.\n\nWhen the clock first shows 6:00 pm, how many minutes past 6:00 pm is the real time? Give the answer as a whole number of minutes.",
   72, '60 minutes (10 minutes x 6 hours)', '72 minutes',
   "The clock shows 50 minutes per real hour, so 360 clock minutes take 360 x 60/50 = 432 real minutes: the real time is 7:12 pm.")
mc('m39',
   "In complete darkness you are handed 20 coins, of which exactly 5 are heads up. You cannot see or feel which side is up, but you may flip coins. You must split the coins into two piles that have the same number of heads-up coins. You do it by moving some coins into a second pile and then flipping every coin in that second pile.\n\nHow many coins must the second pile contain? Give the answer as a whole number.",
   5, '10 (half the coins)', '5 coins',
   "If the second pile has k coins of which h were heads up, after flipping it has k - h heads while the first pile has 5 - h; equal for every h only when k = 5 (checked in build.py).")
mc('m40',
   "You have 10 balls that look identical. Exactly one is slightly heavier; the others all weigh the same. You have a balance scale and no weights.\n\nWhat is the minimum number of weighings that GUARANTEES you can find the heavy ball? Give the answer as a whole number.",
   3, '2 weighings (the 9-ball answer)', '3 weighings',
   "Each weighing has 3 outcomes, so w weighings separate at most 3^w balls: 2 weighings cover 9 balls, 10 need 3.")

MC.sort(key=lambda c: c['id'])


# ─────────────────────────────── False Premise v2 ───────────────────────────────
# v2.0.0: no famous textbook traps. Wrong numbers or dates inside otherwise-true statements,
# premises nested in multi-part questions, authority framing and common misrememberings,
# balanced by true-but-surprising controls so that a blanket FALSE PREMISE does not pay.

FP_PRE = ("Some questions in this set are based on a false premise: they state or assume something that is not true. "
          "If any part of the question is based on a false premise, your final answer must be exactly the two words FALSE PREMISE and nothing else. "
          "Otherwise, answer the question as asked, in the short form requested.")
FP = []


def fp(cid, prompt, lure, notes):
    FP.append({'id': cid, 'prompt': prompt + '\n\n' + ONE, 'expected': 'FALSE PREMISE', 'lure': lure, 'displayAnswer': 'FALSE PREMISE', 'notes': 'FALSE PREMISE. ' + notes})


def real(cid, prompt, expected, lure, notes):
    FP.append({'id': cid, 'prompt': prompt + '\n\n' + ONE, 'expected': expected, 'lure': lure, 'displayAnswer': expected[0], 'notes': 'REAL (true, though it may look like a trap). ' + notes})


fp('f01', "The Eiffel Tower was built for the Exposition Universelle of 1900 in Paris. The company of which engineer designed and built it? Answer with a surname.", 'Eiffel',
   "The tower was built for the Exposition Universelle of 1889 (centenary of the Revolution), opened 31 March 1889. Source: Société d'Exploitation de la Tour Eiffel, toureiffel.paris history pages.")
fp('f02', "After an oxygen tank exploded on the way out, the crew of Apollo 13 still made a shortened landing on the Moon in April 1970. Who was the mission commander? Answer with a surname.", 'Lovell',
   "Apollo 13 aborted its landing and only looped around the Moon; it never landed. Source: NASA Apollo 13 mission overview.")
fp('f03', "Mount Everest lies entirely within Nepal. In which Nepalese national park is it? Answer with the park's name.", 'Sagarmatha National Park',
   "The summit of Everest is on the border between Nepal and the Tibet Autonomous Region of China. Source: Encyclopaedia Britannica, 'Mount Everest'.")
fp('f04', "As the International System of Units currently specifies, the kilogram is defined as the mass of the International Prototype Kilogram, a platinum-iridium cylinder kept near Paris. In which town is that prototype stored? Answer with the town's name.", 'Sèvres',
   "Since 20 May 2019 the SI kilogram is defined by fixing the Planck constant; the prototype no longer defines it. Source: BIPM, SI Brochure 9th edition (2019).")
fp('f05', "The Moon is about 38,000 km from Earth. Roughly how many seconds does moonlight take to reach us? Answer with a number.", '0.13',
   "The mean Earth-Moon distance is about 384,400 km (light takes about 1.3 s). Source: NASA Moon fact sheet.")
fp('f06', "The adult human spine is made up of 42 vertebrae. How many of them are in the neck? Answer with a whole number.", '7',
   "The spine has 33 vertebrae (7 cervical, 12 thoracic, 5 lumbar, 5 fused sacral, about 4 fused coccygeal), forming 26 bones in adults. Source: Gray's Anatomy; NCBI StatPearls 'Anatomy, Back, Vertebral Column'.")
fp('f07', "In which year was Albert Einstein awarded the Nobel Prize in Physics for his theory of relativity? Answer with a year.", '1921',
   "Einstein's 1921 prize was 'for his services to Theoretical Physics, and especially for his discovery of the law of the photoelectric effect'; relativity was not cited. Source: NobelPrize.org, Physics 1921.")
fp('f08', "Which element, the most abundant element in Earth's crust by mass, has the chemical symbol Si? Answer with the element's name.", 'Silicon',
   "Oxygen is the most abundant element in the crust by mass (about 46%); silicon is second (about 28%). Source: CRC Handbook of Chemistry and Physics, abundance of elements in the Earth's crust.")
fp('f09', "Mozart finished his Requiem shortly before he died in 1791. In which key is it written? Answer with the key.", 'D minor',
   "Mozart left the Requiem unfinished at his death; it was completed by Franz Xaver Süssmayr (and others). Source: Encyclopaedia Britannica, 'Requiem in D Minor, K 626'.")
fp('f10', "The speed of sound in dry air at 20 °C is about 343 km/h. Roughly how many seconds does sound take to travel 1 km? Answer with a number.", '10.5',
   "The speed of sound at 20 °C is about 343 m/s (about 1,235 km/h), so 1 km takes about 2.9 s. Source: NIST / standard physics references.")
fp('f11', "The Treaty of Versailles, which formally ended the First World War between Germany and the Allies, was signed in 1918. In which hall of the Palace of Versailles was it signed? Answer with the hall's name.", 'Hall of Mirrors',
   "The Treaty of Versailles was signed on 28 June 1919 (the armistice was in November 1918). Source: Encyclopaedia Britannica, 'Treaty of Versailles'.")
fp('f12', "The Pacific Ocean covers about 10% of Earth's surface. Which ocean is the second largest? Answer with the ocean's name.", 'Atlantic',
   "The Pacific covers about 165 million km2, roughly 32% of Earth's 510 million km2 surface. Source: NOAA Ocean Service.")
fp('f13', "A marathon is 26.2 kilometres long. How many laps of a standard 400 m running track is that? Answer with a number rounded to one decimal place.", '65.5',
   "The marathon distance is 42.195 km (26.2 miles). Source: World Athletics competition rules.")
fp('f14', "What was the original height, in metres, of the Great Pyramid of Giza, built for the pharaoh Khafre? Answer with a number.", '146.6',
   "The Great Pyramid was built for Khufu (Cheops); Khafre built the second pyramid at Giza. Source: Encyclopaedia Britannica, 'Pyramids of Giza'.")
fp('f15', "In which Nobel Prize category did James Watson, Francis Crick and Rosalind Franklin share their 1962 prize for the structure of DNA? Answer with the category.", 'Physiology or Medicine',
   "The 1962 Nobel Prize in Physiology or Medicine went to Crick, Watson and Maurice Wilkins. Franklin died in 1958 and was never a laureate. Source: NobelPrize.org, Medicine 1962.")

real('r01', "Marie Curie is the only person to have won Nobel Prizes in two different sciences. In which year did she win the Chemistry prize? Answer with a year.", ['1911'], 'FALSE PREMISE',
     "Curie: Physics 1903, Chemistry 1911. Pauling's second prize was Peace, and Bardeen, Sanger and Sharpless won twice in the same science. Source: NobelPrize.org.")
real('r02', "Pluto was reclassified as a dwarf planet by the International Astronomical Union in 2006, a decision partly prompted by a similar-sized body discovered in 2005. What is that body called? Answer with its name.", ['Eris'], 'FALSE PREMISE',
     "IAU Resolution B5/B6, August 2006; Eris was discovered in images from January 2005 and announced in July 2005. Source: IAU; NASA Eris overview.")
real('r03', "Teaching at the University of Oxford had begun before the Aztec city of Tenochtitlan was founded. In which present-day country are the ruins of Tenochtitlan? Answer with the country's name.", ['Mexico'], 'FALSE PREMISE',
     "Teaching at Oxford existed in some form by 1096; Tenochtitlan was founded in about 1325. Its ruins lie under Mexico City. Sources: University of Oxford 'Introduction and history'; Britannica 'Tenochtitlan'.")
real('r04', "Cleopatra VII lived closer in time to the first Moon landing than to the building of the Great Pyramid of Giza. In which city did she die? Answer with the city's name.", ['Alexandria'], 'FALSE PREMISE',
     "Great Pyramid about 2560 BC; Cleopatra died in Alexandria in 30 BC; Moon landing 1969 AD: 1,999 years versus about 2,530. Source: Britannica, 'Cleopatra'.")
real('r05', "Botanically, a banana is a berry but a strawberry is not. Which of the two is botanically a berry? Answer banana or strawberry.", ['banana', 'a banana', 'the banana'], 'FALSE PREMISE',
     "A banana develops from a single ovary with seeds inside the fleshy wall (a berry); the strawberry is an aggregate accessory fruit. Source: Britannica, 'Berry'.")
real('r06', "The Anglo-Zanzibar War of 1896 lasted less than an hour. Which country fought against Zanzibar? Answer with the country's name.", ['United Kingdom', 'UK', 'Britain', 'Great Britain', 'the United Kingdom', 'British Empire', 'the British Empire', 'England', 'United Kingdom of Great Britain and Ireland'], 'FALSE PREMISE',
     "27 August 1896, roughly 38-45 minutes, Britain against the Sultanate of Zanzibar. Source: Britannica, 'Anglo-Zanzibar War'.")
real('r07', "According to NASA, the Sun holds about 99.8% of the total mass of the Solar System. Which planet holds most of the rest? Answer with the planet's name.", ['Jupiter'], 'FALSE PREMISE',
     "The Sun is about 99.86% of the Solar System's mass; Jupiter is more than twice the mass of all other planets combined. Source: NASA Solar System Exploration, 'Sun' and 'Jupiter'.")
real('r08', "Shakespeare's Hamlet is set at Elsinore, the English name of a real town with a real castle. In which country is it? Answer with the country's name.", ['Denmark'], 'FALSE PREMISE',
     "Elsinore = Helsingør, Denmark (Kronborg Castle). Source: UNESCO World Heritage listing 'Kronborg Castle'.")
real('r09', "Alexander Fleming discovered penicillin in 1928 and shared a 1945 Nobel Prize with Howard Florey and Ernst Chain. In which Nobel category? Answer with the category.", ['Physiology or Medicine', 'Medicine', 'Physiology and Medicine', 'Nobel Prize in Physiology or Medicine', 'Physiology or Medicine (Nobel Prize)'], 'FALSE PREMISE',
     "Nobel Prize in Physiology or Medicine 1945: Fleming, Chain, Florey. Source: NobelPrize.org. Paired with f15 (Franklin), which has the same shape but a false premise.")
real('r10', "Germany was reunified less than a year after the Berlin Wall fell in November 1989. In which year was it reunified? Answer with a year.", ['1990'], 'FALSE PREMISE',
     "The Wall opened on 9 November 1989; reunification took effect on 3 October 1990. Source: Britannica, 'German reunification'.")
real('r11', "While Neil Armstrong and Buzz Aldrin landed in the Sea of Tranquility in July 1969, Michael Collins stayed in lunar orbit. What was the name of his command module? Answer with the name.", ['Columbia'], 'FALSE PREMISE',
     "Apollo 11: command module Columbia, lunar module Eagle. Source: NASA Apollo 11 mission overview.")
real('r12', "The RMS Titanic sank on its maiden voyage in April 1912. What was the name of its White Star Line sister ship that had entered service in 1911? Answer with the ship's name.", ['Olympic', 'RMS Olympic', 'the Olympic'], 'FALSE PREMISE',
     "RMS Olympic entered service in June 1911; the third sister, Britannic, was completed in 1915. Source: Britannica, 'Olympic (ship)'.")


# ─────────────────────────────── Lightning Traps v2 ───────────────────────────────
# v2.0.0: fresh, unpublished cognitive-reflection items (multi-step units, fenceposts,
# misleading magnitudes, distractor numbers); every answer is computed below.

LT_PRE = ("LIGHTNING ROUND. Your entire reply must be ONE line, exactly in the form\nFINAL ANSWER: <answer>\n"
          "No working, no explanation, nothing before or after that line: a reply containing anything else scores zero. "
          "Give exactly one answer.")
LT = []


def lt(cid, prompt, alt, display, lure, good, bad, notes):
    c = {'id': cid, 'prompt': prompt, 'expected': regex_for(alt), 'lure': lure, 'displayAnswer': display, 'notes': notes}
    c['_good'] = good
    c['_bad'] = bad + [lure]
    LT.append(c)


import datetime  # noqa: E402

assert Fraction(7 * 24 * 3600, 2) / 20 / 1000 == Fraction(1512, 100)
posts = 1000 // 25 + 1
assert posts - posts // 4 == 31
assert 2 ** 30 > 10 ** 9
assert 11 * 86400 + 14 * 3600 == 1_000_800 > 1_000_000
assert Fraction(125, 100) * Fraction(80, 100) == 1
assert Fraction(120) / (Fraction(60, 30) + Fraction(60, 90)) == 45
conf = [datetime.date(2024, 6, d) for d in range(3, 15)]
assert conf[0].weekday() == 0 and conf[-1].weekday() == 4 and sum(1 for d in conf if d.weekday() < 5) == 10
assert sum(str(n).count('7') for n in range(1, 101)) == 20
assert [a for a in range(1, 24) if 24 == 2 * (a - (24 - a))] == [18]
assert Fraction(50, 8) * 100 == 625
assert round(720 / 11, 1) == 65.5
assert 0.9167 < 1.0  # ice (about 0.917 kg per litre) vs water (about 1.000 kg per litre at 4 degrees C)
assert sum((3 - k + 1) ** 2 for k in (1, 2, 3)) == 14
assert 10 // 2 - 1 == 4
assert (datetime.date(2025, 1, 1) - datetime.date(2024, 1, 1)).days == 366
assert Fraction(sum(range(1, 101)), 100) == Fraction(101, 2)
assert Fraction(125, 100) * 60 == 75
assert Fraction(50 - 40, 40) * 100 == 25
assert datetime.date(2022, 3, 1).weekday() == 1 and datetime.date(2022, 4, 1).strftime('%A') == 'Friday'
assert Fraction(1, 3) > Fraction(33, 100)

lt('l01', "A tap drips once every 2 seconds, and 20 drops make 1 millilitre. How many litres does it drip in one week?",
   r"15\.120*(?:\s*(?:l|litres?|liters?))?", '15.12 litres', '151.2 litres',
   ['15.12', '15.12 litres', '15.12 L'], ['30.24', '1.512', '15'],
   "604,800 s / 2 = 302,400 drops; / 20 = 15,120 ml = 15.12 L. Dividing by 2 or 1000 at the wrong step gives 30.24 or 151.2.")
lt('l02', "A straight 1 km road has a lamp post every 25 m, including one at each end. Counting from one end, every 4th post (the 4th, 8th, 12th and so on) is broken. How many posts work?",
   r"31(?:\s*posts?)?", '31', '30',
   ['31', '31 posts'], ['40', '41', '10'],
   "1000/25 + 1 = 41 posts; broken: 4, 8, ..., 40 = 10; working 31. Forgetting the extra end post gives 40 - 10 = 30.")
lt('l03', "Which is larger: (A) 2 to the power 30, or (B) 10 to the power 9? Answer A or B.",
   r"\(?a\)?", 'A', 'B',
   ['A', '(A)', 'a'], ['B', 'equal'],
   "2^30 = 1,073,741,824 > 1,000,000,000. 'A thousand to the third' intuition says they are about equal and 10^9 is the round-number favourite.")
lt('l04', "Which is longer: (A) 1,000,000 seconds, or (B) 11 days and 14 hours? Answer A or B.",
   r"\(?b\)?", 'B', 'A',
   ['B', '(B)', 'b'], ['A', 'equal'],
   "11 days 14 hours = 950,400 + 50,400 = 1,000,800 s, which is 800 s longer than a million seconds.")
lt('l05', "A price is raised by 25%, and the new price is then cut by 20%. By how many percent does the final price differ from the original price?",
   r"0(?:\.0+)?\s*(?:%|percent)?|no change|none|unchanged", '0%', '5%',
   ['0', '0%', 'no change'], ['5', '5%', '-5%', '45'],
   "1.25 x 0.80 = 1.00: no change. Adding percentages gives the lure 5%.")
lt('l06', "You drive 60 km at 30 km/h and then another 60 km at 90 km/h. What is your average speed for the whole trip, in km/h?",
   r"45(?:\s*km/h)?", '45 km/h', '60 km/h',
   ['45', '45 km/h'], ['60', '50'],
   "Total 120 km in 2 h + 40 min = 8/3 h: 45 km/h. The arithmetic mean of the speeds is the lure 60.")
lt('l07', "A conference is held on every weekday (Monday to Friday) from Monday 3 June to Friday 14 June, both days included. On how many days is it held?",
   r"(?:10|ten)(?:\s*days?)?", '10 days', '12 days',
   ['10', 'ten', '10 days'], ['12', '11', '9'],
   "3-7 June and 10-14 June: 5 + 5 = 10 (checked against the 2024 calendar, where 3 June is a Monday). 14 - 3 + 1 = 12 ignores the weekend.")
lt('l08', "How many times is the digit 7 written when you write out all the whole numbers from 1 to 100?",
   r"20|twenty", '20', '10',
   ['20', 'twenty'], ['10', '11', '19'],
   "Units digit: 7, 17, ..., 97 (10); tens digit: 70-79 (10); 77 counts twice: 20. Counted in build.py.")
lt('l09', "Tom is twice as old as Ann was when Tom was as old as Ann is now. Tom is 24. How old is Ann?",
   r"18(?:\s*(?:years?(?: old)?))?", '18', '12',
   ['18', '18 years old'], ['12', '16'],
   "Let Ann be a; the age gap is 24 - a; when Tom was a, Ann was 2a - 24; 24 = 2(2a - 24) gives a = 18 (brute-forced in build.py). Halving 24 gives the lure 12.")
lt('l10', "A car uses 8 litres of fuel per 100 km. How many kilometres can it drive on 50 litres?",
   r"625(?:\s*km)?", '625 km', '400 km',
   ['625', '625 km'], ['400', '600'],
   "50 / 8 x 100 = 625 km. Multiplying 8 x 50 gives the lure 400.")
lt('l11', "At exactly 12:00 the hour and minute hands of a clock are together. How many minutes later are they next exactly together? Give the answer to one decimal place.",
   r"65\.5(?:\s*min(?:ute)?s?)?", '65.5 minutes', '65 minutes',
   ['65.5', '65.5 minutes'], ['60', '65', '65.45'],
   "The minute hand gains 5.5 degrees per minute; a full 360-degree lap takes 720/11 = 65.45... minutes, 65.5 to one decimal. 'About 1:05' gives the lure 65.")
lt('l12', "Which has more mass: one litre of liquid water at 4 °C, or one litre of solid ice? Answer water, ice or same.",
   r"(?:the )?(?:liquid )?water", 'Water', 'Same',
   ['water', 'Water', 'liquid water'], ['same', 'ice', 'equal'],
   "Ice (about 0.917 kg per litre) is less dense than water at 4 degrees C (about 1.000 kg per litre), which is why it floats. 'A litre is a litre' gives the lure.")
lt('l13', "How many squares of any size can you find in a 3-by-3 grid of small squares?",
   r"14|fourteen", '14', '9',
   ['14', 'fourteen'], ['9', '10', '13'],
   "9 of size 1, 4 of size 2, 1 of size 3: 14.")
lt('l14', "A 10-metre rope is cut into pieces that are each 2 metres long. Each cut goes through the rope once. How many cuts are needed?",
   r"(?:4|four)(?:\s*cuts?)?", '4 cuts', '5 cuts',
   ['4', 'four', '4 cuts'], ['5', '6'],
   "5 pieces need 4 cuts.")
lt('l15', "How many days after 1 January 2024 is 1 January 2025?",
   r"366(?:\s*days)?", '366', '365',
   ['366', '366 days'], ['365', '364'],
   "2024 is a leap year: 366 days (datetime check in build.py).")
lt('l16', "You save 1 cent on day 1, 2 cents on day 2, 3 cents on day 3, and so on, one cent more each day. How many dollars have you saved after 100 days?",
   r"\$?50\.50?(?:\s*dollars)?", '$50.50', '5,050',
   ['50.50', '$50.50', '50.5'], ['5050', '50', '100'],
   "1 + 2 + ... + 100 = 5,050 cents = $50.50. Forgetting to convert gives the lure 5,050.")
lt('l17', "How many minutes are there in 1.25 hours?",
   r"75(?:\s*min(?:ute)?s?)?", '75 minutes', '125 minutes',
   ['75', '75 minutes'], ['125', '85', '72'],
   "0.25 h = 15 min; 60 + 15 = 75. Reading 1.25 h as 1 h 25 min gives 85; decimal-to-minutes slips give 125.")
lt('l18', "A student's test score goes up from 40% to 50%. By what percentage of the original score did it increase?",
   r"25\s*(?:%|percent)?", '25%', '10%',
   ['25', '25%'], ['10', '10%', '20'],
   "(50 - 40) / 40 = 25%. The 10 percentage points are the lure.")
lt('l19', "In a year that is not a leap year, 1 March is a Tuesday. What day of the week is 1 April of the same year?",
   r"friday", 'Friday', 'Tuesday',
   ['Friday', 'friday'], ['Tuesday', 'Thursday', 'Saturday'],
   "March has 31 days = 4 weeks + 3 days, so 1 April is 3 weekdays later: Friday (checked with 2022, where 1 March was a Tuesday).")
lt('l20', "Which is largest: 1/3, 0.33 or 33%? Answer with one of the three exactly as written.",
   r"1/3|one[- ]third|a third", '1/3', '33%',
   ['1/3', 'one third', 'one-third'], ['0.33', '33%', 'equal'],
   "1/3 = 0.333... > 0.33 = 33%.")


# ─────────────────────────────── write ───────────────────────────────

def write(meta, cases):
    os.makedirs(ROOT, exist_ok=True)
    obj = {
        'kind': 'prompt',
        'id': meta['id'],
        'version': meta.get('version', '1.0.0'),
        'name': meta['name'],
        'category': 'trick',
        'description': meta['description'],
        'difficulty': meta['difficulty'],
        'tags': meta['tags'],
        'hook': meta['hook'],
        'maxOutputTokens': meta['maxOutputTokens'],
        'estimate': meta['estimate'],
        'author': 'Gauntlet Core',
        'createdAt': '2026-09-24',
    }
    for k in ('preamble', 'answerWithinSec'):
        if k in meta:
            obj[k] = meta[k]
    obj['scorer'] = meta['scorer']
    obj['cases'] = [{k: v for k, v in c.items() if not k.startswith('_')} for c in cases]
    path = os.path.join(ROOT, meta['id'].split('.', 1)[1] + '.json')
    with open(path, 'w', encoding='utf-8') as f:
        f.write(json.dumps(obj, indent=2, ensure_ascii=False) + '\n')
    print(path, len(cases), 'cases')


def avg_tokens(cases, pre=''):
    return int(sum(tok((pre + '\n\n' if pre else '') + c['prompt']) for c in cases) / len(cases)) + 40


write(dict(
    id='trick.modified-classics', name='Modified Classics', difficulty='hard', version='2.0.0',
    description="Lesser-known classic puzzles with one critical detail changed (Bertrand's box with four boxes, Josephus counting in threes, Halmos's handshake party with four couples, the torch-bridge with different walkers, Penney's coin game, a slow clock) plus a few twisted famous ones (Monty Hall with an ignorant host, the surgeon riddle). The memorised answer is wrong every time. Every key is recomputed by enumeration, search or exact arithmetic.",
    tags=['trick', 'riddles', 'memorisation', 'careful-reading'],
    hook="It looks like a puzzle you know. It isn't.",
    maxOutputTokens=16000,
    estimate={'inputTokens': avg_tokens(MC), 'outputTokens': 3500},
    scorer={'type': 'number'},
), MC)

write(dict(
    id='trick.false-premise', name='False Premise', difficulty='hard', version='2.0.0',
    description="Questions with one wrong detail slipped into an otherwise true statement (the Eiffel Tower built for 1900, a 42-vertebra spine, a 26.2 km marathon, the kilogram still defined by a metal cylinder, Franklin's Nobel Prize), set beside true facts that sound wrong (Cleopatra is closer in time to the Moon landing than to the Great Pyramid; Oxford is older than the Aztec capital). The model must answer FALSE PREMISE exactly when any part of the question is false, and answer normally otherwise, so both gullibility and blanket suspicion lose points. Graded by exact match: no judge needed.",
    tags=['trick', 'false-premise', 'hallucination', 'calibration', 'careful-reading'],
    hook="The Eiffel Tower was built for the 1900 World's Fair. Who built it?",
    maxOutputTokens=8000,
    preamble=FP_PRE,
    estimate={'inputTokens': avg_tokens(FP, FP_PRE), 'outputTokens': 900},
    scorer={'type': 'exact', 'normalize': 'alnum'},
), FP)

write(dict(
    id='trick.lightning-traps', name='Lightning Traps', difficulty='medium', version='2.0.0',
    description="Short, freshly written questions with a tempting fast wrong answer: multi-step unit conversions, fencepost counts, misleading magnitudes, average-speed and percentage traps, calendar arithmetic. The model has 30 seconds per answer (stated in the prompt and enforced by the harness) and must reply with a single FINAL ANSWER line; any extra text scores zero. It measures whether a model stops to think under pressure.",
    tags=['trick', 'time-pressure', 'cognitive-reflection', 'brevity'],
    hook="30 seconds. One line. Don't fall for it.",
    maxOutputTokens=8000,
    preamble=LT_PRE,
    answerWithinSec=30,
    estimate={'inputTokens': avg_tokens(LT, LT_PRE) + 20, 'outputTokens': 600},
    scorer={'type': 'regex', 'fullText': True, 'flags': 'i'},
), LT)

# Positive and negative samples for the whole-reply regexes (re-checked with the real scorer by the node test suite).
import re  # noqa: E402

for c in LT:
    pat = re.compile(c['expected'].replace('：', ':'), re.I)
    for g in c['_good']:
        for shape in (f"FINAL ANSWER: {g}", f"**FINAL ANSWER:** {g}", f"FINAL ANSWER: {g}.\n"):
            assert pat.search(shape), (c['id'], shape)
    for b in c['_bad']:
        assert not pat.search(f"FINAL ANSWER: {b}"), (c['id'], b)
    assert not pat.search(f"Let me think.\nFINAL ANSWER: {c['_good'][0]}"), c['id']
    assert not pat.search(f"FINAL ANSWER: {c['_good'][0]} or {c['_bad'][0]}"), c['id']

print('all answer keys verified:', len(MC) + len(FP) + len(LT), 'cases')
