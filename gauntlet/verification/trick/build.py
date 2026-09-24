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


# ─────────────────────────────── False Premise ───────────────────────────────

FP_PRE = ("Some questions in this set are based on a false premise: they assume something that is not true or never happened. "
          "If the question is based on a false premise, your final answer must be exactly the two words FALSE PREMISE and nothing else. "
          "Otherwise, answer the question as asked, in the short form requested.")
FP = []


def fp(cid, prompt, lure, notes):
    FP.append({'id': cid, 'prompt': prompt + '\n\n' + ONE, 'expected': 'FALSE PREMISE', 'lure': lure, 'displayAnswer': 'FALSE PREMISE', 'notes': 'FALSE PREMISE. ' + notes})


def real(cid, prompt, expected, lure, notes):
    FP.append({'id': cid, 'prompt': prompt + '\n\n' + ONE, 'expected': expected, 'lure': lure, 'displayAnswer': expected[0], 'notes': 'REAL (looks like a trap). ' + notes})


fp('f01', "In which year did Albert Einstein receive his second Nobel Prize? Answer with a year.", '1921',
   "Einstein received exactly one Nobel Prize (Physics, 1921, awarded in 1922).")
real('r01', "In which field did Marie Curie win her second Nobel Prize? Answer with the field only, in one word.", ['Chemistry'], 'FALSE PREMISE',
     "Curie: Physics 1903 (shared), Chemistry 1911 (sole). She really did win two.")
fp('f02', "Which country did Neil Armstrong represent when he became the second person to walk on the Moon? Answer with the country name.", 'United States',
   "Armstrong was the FIRST person to walk on the Moon (Apollo 11, 20-21 July 1969); Buzz Aldrin was second.")
real('r02', "In which field did Linus Pauling win his second Nobel Prize? Answer with the field only, in one word.", ['Peace', 'Peace Prize', 'Nobel Peace Prize'], 'FALSE PREMISE',
     "Pauling: Chemistry 1954, Peace 1962 (awarded 1963). The only person with two unshared Nobel Prizes.")
fp('f03', "In which year did William Shakespeare publish his novel Moby-Dick? Answer with a year.", '1851',
   "Moby-Dick (1851) was written by Herman Melville; Shakespeare died in 1616 and wrote no novels.")
real('r03', "How many natural moons does Mars have? Answer with a whole number.", ['2', 'two'], 'FALSE PREMISE',
     "Phobos and Deimos. Stable, long-established fact.")
fp('f04', "What was the name of the ship on which Christopher Columbus reached Australia in 1492? Answer with the ship's name.", 'Santa Maria',
   "Columbus reached the Caribbean (the Bahamas) in 1492 and never reached Australia; Europeans first recorded Australia in 1606 (Janszoon).")
real('r04', "How many Nobel Prizes did the chemist Frederick Sanger win? Answer with a whole number.", ['2', 'two'], '1',
     "Sanger won the Chemistry prize twice: 1958 (insulin structure) and 1980 (nucleic-acid sequencing).")
fp('f05', "Which planet in our Solar System has an orbit that lies closer to the Sun than Mercury's orbit? Answer with the planet's name.", 'Venus',
   "Mercury is the innermost planet; no planet orbits inside it. (Blind check replaced an earlier chess question: 'the only piece allowed to move diagonally' could be read charitably as 'the bishop, the only piece that moves only diagonally'.)")
real('r05', "Which letter of the English alphabet does not appear anywhere in the names of the 50 US states? Answer with the letter only.", ['Q', 'the letter Q', 'letter Q'], 'FALSE PREMISE',
     "Checked against all 50 state names in build.py: q is the only missing letter (j: New Jersey, z: Arizona, x: Texas/New Mexico).")
fp('f06', "What is the smallest even prime number that is greater than 2? Answer with a number.", '4',
   "2 is the only even prime (every other even number is divisible by 2); checked up to 10,000 in build.py.")
real('r06', "How many hearts does an octopus have? Answer with a whole number.", ['3', 'three'], '1',
     "Two branchial (gill) hearts and one systemic heart.")
fp('f07', "Why do the Northern and Southern Hemispheres have summer during the same months of the year? Answer in at most five words.", 'Earth’s axial tilt',
   "They have opposite seasons: when it is summer in one hemisphere it is winter in the other (because of Earth's axial tilt).")
real('r07', "What is the national animal of Scotland? Answer with the animal only.", ['unicorn', 'the unicorn', 'a unicorn'], 'FALSE PREMISE',
     "Scotland's national animal is the unicorn (a heraldic/official national symbol). Sounds invented but is true.")
fp('f08', "What is the chemical symbol of the noble gas oxygen? Answer with the symbol only.", 'O',
   "Oxygen (O) is not a noble gas; it is in group 16. The noble gases are group 18 (He, Ne, Ar, Kr, Xe, Rn, Og).")
real('r08', "How many Nobel Prizes in Physics did John Bardeen win? Answer with a whole number.", ['2', 'two'], '1',
     "Bardeen won Physics in 1956 (transistor) and 1972 (BCS theory of superconductivity), the only person with two Physics prizes.")
fp('f09', "In which year did the RMS Titanic arrive in New York at the end of its first transatlantic crossing? Answer with a year.", '1912',
   "Titanic sank on its maiden voyage (15 April 1912) and never arrived in New York.")
real('r09', "What is the only mammal capable of true, sustained flapping flight? Answer with one word.", ['bat', 'bats', 'the bat'], 'FALSE PREMISE',
     "Bats are the only mammals capable of true powered flight (flying squirrels and colugos glide).")
fp('f10', "Which real number, when multiplied by itself, gives -1? Answer with the number.", 'i',
   "The square of every real number is >= 0; the square roots of -1 (i and -i) are not real numbers.")
real('r10', "In which country is the city of Timbuktu? Answer with the country name only.", ['Mali', 'Republic of Mali'], 'FALSE PREMISE',
     "Timbuktu (Tombouctou) is a real city in Mali, often assumed to be mythical.")
fp('f11', "Which US president was the first person to walk on the Moon? Answer with a name.", 'Neil Armstrong',
   "No US president has walked on the Moon; the first person to do so was astronaut Neil Armstrong, who was never president.")
real('r11', "Which planet in our Solar System spins in the opposite direction to most of the others AND has a day (one rotation) that is longer than its year? Answer with the planet's name only.", ['Venus'], 'FALSE PREMISE',
     "Venus: retrograde rotation, sidereal day ~243 Earth days > orbital period ~225 Earth days. Uranus is tilted but its day (~17 h) is far shorter than its year.")
fp('f12', "Under the Gregorian calendar, how many days does February have in a leap year that is not divisible by 4? Answer with a whole number.", '29',
   "Every Gregorian leap year is divisible by 4 (checked 1582-2999 in build.py), so no such year exists.")
real('r12', "How many bones are there in the middle ear of one adult human ear? Answer with a whole number.", ['3', 'three'], 'FALSE PREMISE',
     "The three ossicles: malleus, incus and stapes.")
fp('f13', "In which year did the Soviet Union land the first human on Mars? Answer with a year.", '1971',
   "No human has ever been to Mars. (The Soviet Mars 3 lander of 1971 was uncrewed.)")
fp('f14', "Which bone in the human arm is called the femur? Answer with the bone's location.", 'upper arm',
   "The femur is the thigh bone, in the leg. The upper-arm bone is the humerus.")
fp('f15', "According to the Bible story, how many animals of each kind did Moses take onto the ark? Answer with a whole number.", '2',
   "It was Noah, not Moses, who built the ark in the Genesis account (the 'Moses illusion').")


# ─────────────────────────────── Lightning Traps ───────────────────────────────

LT_PRE = ("LIGHTNING ROUND. Your entire reply must be ONE line, exactly in the form\nFINAL ANSWER: <answer>\n"
          "No working, no explanation, nothing before or after that line: a reply containing anything else scores zero. "
          "Give exactly one answer.")
LT = []


def lt(cid, prompt, alt, display, lure, good, bad, notes):
    c = {'id': cid, 'prompt': prompt, 'expected': regex_for(alt), 'lure': lure, 'displayAnswer': display, 'notes': notes}
    c['_good'] = good
    c['_bad'] = bad + [lure]
    LT.append(c)


lt('l01', "How many months of the year have at least 28 days?", r"12|twelve|all(?: 12| twelve)?(?: months)?(?: of them)?", '12', '1 (February)',
   ['12', 'twelve', 'All 12', 'all of them'], ['1', '11'],
   "Every month has at least 28 days. The phrase 'at least' removes the usual 'exactly 28' ambiguity.")
lt('l02', "A fair coin has just landed heads 9 times in a row. What is the probability, in percent, that the next toss lands heads?", r"50(?:\.0+)?\s*(?:%|percent)?", '50%', 'less than 50% ("tails is due")',
   ['50', '50%', '50 percent'], ['0.2', '0.1%', '5'],
   "Tosses of a fair coin are independent: 50%. Gambler's fallacy trap.")
lt('l03', "It takes 20 minutes to hard-boil one egg. How many minutes does it take to hard-boil 4 eggs cooked together at the same time in one pot? Assume the cooking time does not depend on how many eggs are in the pot.", r"20(?:\s*min(?:ute)?s?)?", '20 minutes', '80 minutes',
   ['20', '20 minutes'], ['5'],
   "Eggs cook in parallel; the prompt rules out the only physical objection.")
lt('l04', "A straight fence is 30 metres long, with a post every 3 metres including one at each end. How many posts are there?", r"(?:11|eleven)(?:\s*posts?)?", '11', '10',
   ['11', '11 posts', 'eleven'], ['9', '12'],
   "Fencepost error: 30/3 = 10 gaps, 11 posts.")
lt('l05', "A clock chimes once for each hour. At 4 o'clock it chimes 4 times, and it takes 6 seconds from the start of the first chime to the start of the last chime. The chimes are evenly spaced. At 7 o'clock, how many seconds are there from the start of the first chime to the start of the last chime?", r"12(?:\.0+)?(?:\s*s(?:ec(?:ond)?s?)?)?", '12 seconds', '10.5 seconds',
   ['12', '12 seconds', '12 s'], ['10.5', '14'],
   "4 chimes = 3 gaps of 2 s; 7 chimes = 6 gaps = 12 s.")
lt('l06', "A doctor gives you 4 pills and tells you to take one now and then one every 30 minutes until they are all gone. How many minutes pass between taking the first pill and taking the last one?", r"90(?:\s*min(?:ute)?s?)?", '90 minutes', '120 minutes',
   ['90', '90 minutes'], ['60', '150'],
   "4 pills = 3 gaps of 30 minutes.")
lt('l07', "What is 3 + 3 × 3?", r"12", '12', '18',
   ['12'], ['9', '21'],
   "Standard operator precedence (multiplication before addition): 3 + 9 = 12. Left-to-right gives the lure 18.")
lt('l08', "Divide 30 by one half. Then add 10. What number do you get?", r"70", '70', '25',
   ['70'], ['25', '15', '20'],
   "30 / (1/2) = 60; 60 + 10 = 70. 'Divide by one half' is not 'divide in half'.")
lt('l09', "In a running race, you overtake the runner who is in second place. What place are you in now?", r"2|2nd|second(?: place)?|2nd place", 'Second', 'First',
   ['2nd', 'second', 'Second place', '2'], ['1st', 'first', 'third'],
   "Overtaking the runner in second place puts you in second place (the runner in first is still ahead).")
lt('l10', "Which is heavier: a kilogram of feathers or a kilogram of steel? Answer feathers, steel or same.", r"same|the same|(?:they (?:weigh|are) )?(?:the )?same(?: weight)?|neither|equal", 'Same', 'Steel',
   ['same', 'Same', 'They weigh the same', 'neither'], ['steel', 'feathers'],
   "Both weigh exactly one kilogram, so they weigh the same.")
lt('l11', "Mary's father has five daughters. Four of them are named Nana, Nene, Nini and Nono. What is the name of the fifth daughter?", r"mary", 'Mary', 'Nunu',
   ['Mary', 'mary'], ['Nunu', 'Nana'],
   "The first words of the question name her: the fifth daughter is Mary. The vowel pattern suggests the lure Nunu.")
lt('l12', "What is 0.1 + 0.2? Give the exact value.", r"0?\.30*", '0.3', '0.30000000000000004',
   ['0.3', '.3', '0.30'], ['0.03', '3'],
   "Exact decimal arithmetic: 0.3 (the lure is the floating-point artefact).")
lt('l13', "How many two-cent stamps are there in a dozen?", r"12|twelve", '12', '6',
   ['12', 'twelve'], ['6', '24'],
   "A dozen of anything is 12; the 'two-cent' detail invites a division (the lure 6).")
lt('l14', "Which number is larger: 9.11 or 9.9? Answer with the number.", r"9\.90*", '9.9', '9.11',
   ['9.9', '9.90'], ['9.11', '9'],
   "Compare as decimals: 9.9 = 9.90 > 9.11. The lure comes from reading them like version numbers.")
lt('l15', "How many times does the letter r appear in the word strawberry?", r"3|three", '3', '2',
   ['3', 'three'], ['2', '1'],
   "s-t-R-a-w-b-e-R-R-y: 3 (checked in build.py).")
lt('l16', "How many times does the letter e appear in the word excellence?", r"4|four", '4', '3',
   ['4', 'four'], ['3', '5'],
   "E-x-c-E-l-l-E-n-c-E: 4 (checked in build.py).")
lt('l17', "If the day before yesterday was Thursday, what day of the week will the day after tomorrow be?", r"monday", 'Monday', 'Saturday',
   ['Monday'], ['Sunday', 'Saturday', 'Tuesday'],
   "Today is Saturday; the day after tomorrow is Monday (checked in build.py).")
lt('l18', "Is 91 a prime number? Answer yes or no.", r"no", 'No', 'Yes',
   ['No', 'no'], ['yes', 'Yes'],
   "91 = 7 x 13, so it is not prime (checked in build.py). It looks prime because it is odd and not divisible by 3 or 5.")
lt('l19', "What is 2 to the power of 10, minus 10 to the power of 3?", r"24", '24', '0',
   ['24'], ['-24', '0', '1024'],
   "2^10 = 1024 and 10^3 = 1000, so the difference is 24 (checked in build.py).")
lt('l20', "An analogue clock shows exactly 3:15. What is the smaller angle, in degrees, between the hour hand and the minute hand?", r"7\.50*(?:\s*(?:°|degrees?))?|7½", '7.5°', '0°',
   ['7.5', '7.5°', '7.5 degrees'], ['0', '75', '7'],
   "Minute hand at 90 degrees, hour hand a quarter of the way from 3 to 4: 90 + 7.5 = 97.5 degrees. Difference 7.5.")


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
    id='trick.modified-classics', name='Modified Classics', difficulty='medium',
    description="Famous riddles and puzzles (river crossing, Monty Hall, the bat and the ball, the surgeon riddle, the birthday paradox, lily pads, the portrait riddle) with one critical detail changed, so the answer every model has memorised is now wrong. Every key is recomputed by enumeration or search. It separates models that read the question from models that pattern-match it.",
    tags=['trick', 'riddles', 'memorisation', 'careful-reading'],
    hook="It looks like a puzzle you know. It isn't.",
    maxOutputTokens=16000,
    estimate={'inputTokens': avg_tokens(MC), 'outputTokens': 2500},
    scorer={'type': 'number'},
), MC)

write(dict(
    id='trick.false-premise', name='False Premise', difficulty='medium',
    description="Short questions that quietly assume something false (Einstein's second Nobel Prize, Shakespeare's novel Moby-Dick, the noble gas oxygen) mixed with true facts that sound invented (Curie's and Bardeen's second prizes, Scotland's unicorn, the octopus's three hearts). The model must answer FALSE PREMISE exactly when the question is built on a falsehood and answer normally otherwise, so both gullibility and blanket suspicion lose points. Graded by exact match: no judge needed.",
    tags=['trick', 'false-premise', 'hallucination', 'calibration'],
    hook="Why did Einstein win his second Nobel Prize?",
    maxOutputTokens=8000,
    preamble=FP_PRE,
    estimate={'inputTokens': avg_tokens(FP, FP_PRE), 'outputTokens': 900},
    scorer={'type': 'exact', 'normalize': 'alnum'},
), FP)

write(dict(
    id='trick.lightning-traps', name='Lightning Traps', difficulty='easy',
    description="Very short questions with a tempting fast wrong answer: cognitive-reflection classics, counting letters, decimal comparisons, fenceposts and clock chimes. The model has 30 seconds per answer (stated in the prompt and enforced by the harness) and must reply with a single FINAL ANSWER line; any extra text scores zero. It measures whether a model stops to think under pressure.",
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
