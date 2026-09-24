import sys, json, random
sys.path.insert(0, '..')
from common import ONE
from explore import build, CONFIGS
from render import render
from gridlib import count_solutions
import themes

# (case id, config, seed, difficulty, question category, intro, category labels, definitions, question text, position labels)
LIN_DEF = ("Spots are numbered 1 to {n} from left to right. \"Somewhere to the left of\" means in a lower-numbered {w} (not necessarily adjacent). "
           "\"Immediately to the left of\" means in the {w} numbered exactly one lower. \"Next to\" means in a {w} whose number differs by exactly 1.")

PICKS = [
 dict(id='c01', cfg='beach', seed=3, d='easy', ask='name',
      intro="Four friends are standing in a line on a beach, in spots numbered 1 to 4 from left to right. Each friend has a kayak of a different colour and brought a different snack.",
      labels={'name': 'Names', 'color': 'Kayak colours', 'snack': 'Snacks'},
      defs="\"Somewhere to the left of\" means in a lower-numbered spot (not necessarily adjacent). \"Immediately to the left of\" means in the spot numbered exactly one lower. \"In a spot next to\" means in a spot whose number differs by exactly 1.",
      q="Who is standing in spots 1, 2, 3 and 4? List the four names in that order.", pos=['1', '2', '3', '4']),
 dict(id='c02', cfg='boats', seed=1, d='easy', ask='dog',
      intro="Four houseboats are moored side by side in berths numbered 1 to 4 from left to right. Each houseboat has a different owner, a different hull colour and a different dog on board.",
      labels={'owner': 'Owners', 'dog': 'Dogs', 'hull': 'Hull colours'},
      defs="\"Moored somewhere to the left of\" means in a lower-numbered berth (not necessarily adjacent). \"Moored immediately to the left of\" means in the berth numbered exactly one lower. \"In a berth next to\" means in a berth whose number differs by exactly 1.",
      q="Which dog is on the houseboat in each of berths 1, 2, 3 and 4? List the four dog breeds in that order.", pos=None),
 dict(id='c03', cfg='trucks', seed=2, d='medium', ask='food',
      intro="Five food trucks are parked in a row in bays numbered 1 to 5 from left to right. Each truck has a different owner, sells a different food and is painted a different colour.",
      labels={'owner': 'Owners', 'food': 'Foods', 'color': 'Colours'},
      defs="\"Parked somewhere to the left of\" means in a lower-numbered bay (not necessarily adjacent). \"Parked immediately to the left of\" means in the bay numbered exactly one lower. \"In a bay next to\" means in a bay whose number differs by exactly 1. \"The bay numbers differ by exactly k\" means the absolute difference of the two bay numbers is k.",
      q="Which food is sold in each of bays 1 to 5? List the five foods in bay order.", pos=None),
 dict(id='c04', cfg='floors', seed=2, d='medium', ask='name',
      intro="A five-storey building has one apartment per floor, on floors numbered 1 (the bottom floor) to 5 (the top floor). Each apartment has one resident. Each resident plays a different instrument and keeps a different plant.",
      labels={'name': 'Residents', 'instrument': 'Instruments', 'plant': 'Plants'},
      defs="\"Lives on some floor below\" means on a lower-numbered floor (not necessarily adjacent). \"Lives on the floor directly below\" means on the floor numbered exactly one lower. \"Lives on a floor directly above or directly below\" means the floor numbers differ by exactly 1. \"The floor numbers differ by exactly k\" means the absolute difference of the two floor numbers is k.",
      q="Who lives on each floor? List the five residents from floor 1 up to floor 5.", pos=None),
 dict(id='c05', cfg='talks', seed=2, d='medium', ask='topic',
      intro="At a one-track science meeting, five speakers give one talk each, in five consecutive one-hour slots starting at 9:00, 10:00, 11:00, 12:00 and 13:00. Each speaker talks on a different topic, comes from a different city and ordered a different drink.",
      labels={'speaker': 'Speakers', 'topic': 'Topics', 'city': 'Cities', 'drink': 'Drinks'},
      defs="\"Speaks at some point earlier than\" means in an earlier slot (not necessarily adjacent). \"Speaks in the slot directly before\" means exactly one hour earlier. \"Speaks in a slot directly before or directly after\" means the two start times are exactly one hour apart. \"Speak exactly k hours apart\" means their start times differ by exactly k hours.",
      q="What is the topic of each talk? List the five topics in time order, from the 9:00 talk to the 13:00 talk.", pos=None),
 dict(id='c06', cfg='train', seed=0, d='medium', ask='destination',
      intro="Five passengers each sit in a different carriage of a train. The carriages are numbered 1 to 5, with carriage 1 at the front. Each passenger is reading a different kind of book, is going to a different destination and is eating a different snack.",
      labels={'passenger': 'Passengers', 'genre': 'Books (poetry, mystery, history, sci-fi, or a cookbook)', 'destination': 'Destinations', 'snack': 'Snacks'},
      defs="\"In a carriage nearer the front than\" means in a lower-numbered carriage (not necessarily adjacent). \"In the carriage directly in front of\" means in the carriage numbered exactly one lower. \"In a carriage next to\" means the carriage numbers differ by exactly 1. \"The carriage numbers differ by exactly k\" means the absolute difference of the two carriage numbers is k.",
      q="Where is the passenger in each carriage going? List the five destinations for carriages 1 to 5, in that order.", pos=None),
 dict(id='c07', cfg='gardeners', seed=5, d='hard', ask='age',
      intro="Five gardeners each work one of five neighbouring plots, numbered 1 to 5 from left to right. Each gardener grows a different crop, owns a different tool and has a different age.",
      labels={'name': 'Gardeners', 'crop': 'Crops', 'tool': 'Tools', 'age': 'Ages (in years)'},
      defs="\"Works a plot somewhere to the left of the plot of\" means a lower-numbered plot (not necessarily adjacent). \"Works the plot immediately to the left of the plot of\" means the plot numbered exactly one lower. \"Works a plot next to the plot of\" means the plot numbers differ by exactly 1. \"Is older than\" compares ages, and \"is exactly k years older than\" means the age difference is exactly k years. \"The plot numbers differ by exactly k\" means the absolute difference of the two plot numbers is k.",
      q="How old is the gardener on each plot? List the five ages for plots 1 to 5, in that order, as numbers.", pos=None),
 dict(id='c08', cfg='table', seed=3, d='hard', ask='name',
      intro="Six guests sit at a round table with six seats numbered 1 to 6 in clockwise order. Each guest ordered a different dessert and wears a hat of a different colour.",
      labels={'name': 'Guests', 'dessert': 'Desserts', 'hat': 'Hat colours'},
      defs="Seat 2 is immediately clockwise from seat 1, seat 3 is immediately clockwise from seat 2, and so on, and seat 1 is immediately clockwise from seat 6. \"Sits next to\" means in one of the two seats immediately clockwise or immediately counter-clockwise from that person. \"Sits directly opposite\" means exactly three seats away (seat 1 is opposite seat 4, seat 2 opposite seat 5, seat 3 opposite seat 6).",
      q="Who sits in each seat? List the six guests for seats 1 to 6, in that order.", pos=None),
 dict(id='c09', cfg='lockers', seed=1, d='hard', ask='owner',
      intro="Six lockers are arranged in two rows (top and bottom) and three columns (left, middle and right), giving the top-left, top-middle, top-right, bottom-left, bottom-middle and bottom-right lockers. Each locker belongs to a different person, who plays a different sport, and each locker has a padlock of a different colour.",
      labels={'owner': 'Owners', 'sport': 'Sports (fencer, rower, squash player, archer, judoka, curler)', 'lock': 'Padlock colours'},
      defs="\"Directly above\" means in the same column and in the row immediately above. \"Immediately to the left of, in the same row\" means in the same row and in the column immediately to the left. \"Shares a side with\" means directly above, directly below, or immediately to the left or right in the same row (lockers that touch only at a corner do not share a side). \"The same row\" and \"the same column\" have their usual meaning.",
      q="Who owns each locker? List the six owners in this order: top-left, top-middle, top-right, bottom-left, bottom-middle, bottom-right.", pos=None),
 dict(id='c10', cfg='gallery', seed=1, d='hard', ask='artist',
      intro="Six paintings hang in a single row on a gallery wall, on hooks numbered 1 to 6 from left to right. Each painting is by a different artist, shows a different subject and has a different frame.",
      labels={'artist': 'Artists', 'subject': 'Subjects', 'frame': 'Frames'},
      defs="\"Hangs somewhere to the left of\" means on a lower-numbered hook (not necessarily adjacent). \"Hangs immediately to the left of\" means on the hook numbered exactly one lower. \"Hangs next to\" means the hook numbers differ by exactly 1. \"The hook numbers differ by exactly k\" means the absolute difference of the two hook numbers is k.",
      q="Which artist painted the painting on each hook? List the six artists for hooks 1 to 6, in that order.", pos=None),
 dict(id='c11', cfg='lanes', seed=0, d='hard', ask='country',
      intro="Six runners line up in lanes numbered 1 to 6. Each runner is from a different country, wears shoes of a different colour and runs for a different club.",
      labels={'runner': 'Runners', 'country': 'Countries', 'shoes': 'Shoe colours', 'club': 'Clubs'},
      defs="\"In a lower-numbered lane than\" means any lower lane number (not necessarily adjacent). \"In the lane numbered exactly one lower than\" means what it says. \"In a lane next to\" means the lane numbers differ by exactly 1. \"The lane numbers differ by exactly k\" means the absolute difference of the two lane numbers is k. \"Exactly one of these two statements is true\" means one of them is true and the other is false.",
      q="Which country is each lane's runner from? List the six countries for lanes 1 to 6, in that order.", pos=None),
 dict(id='c12', cfg='lab', seed=3, d='extreme', ask='scientist',
      intro="Six scientists have offices numbered 1 to 6 along one corridor. Each scientist keeps a different animal in the lab, works on a different project and has been at the institute for a different number of years.",
      labels={'scientist': 'Scientists', 'animal': 'Animals', 'project': 'Projects', 'years': 'Years at the institute'},
      defs="\"Has a lower office number than\" means any lower office number (not necessarily adjacent). \"Has the office numbered exactly one lower than the office of\" means what it says. \"Has an office next to the office of\" means the office numbers differ by exactly 1. \"The office numbers differ by exactly k\" means the absolute difference of the two office numbers is k. \"Has been at the institute longer than\" compares years; \"exactly k years longer\" means the difference is exactly k years. \"If P, then Q\" is false only when P is true and Q is false; otherwise it is true. \"Of X and Y (two different scientists), one ... and the other ...\" means X and Y are different people, and one of them satisfies the first description while the other satisfies the second.",
      q="Who has each office? List the six scientists for offices 1 to 6, in that order.", pos=None),
 dict(id='c13', cfg='ferries', seed=4, d='extreme', ask='island',
      intro="Six ferries are docked at piers numbered 1 to 6. Each ferry has a different captain, is bound for a different island, carries a different cargo and has a funnel painted a different colour.",
      labels={'captain': 'Captains', 'island': 'Islands', 'cargo': 'Cargoes', 'funnel': 'Funnel colours'},
      defs="\"Docked at a lower-numbered pier than\" means any lower pier number (not necessarily adjacent). \"Docked at the pier numbered exactly one lower than\" means what it says. \"Docked at a pier next to\" means the pier numbers differ by exactly 1. \"The pier numbers differ by exactly k\" means the absolute difference of the two pier numbers is k. \"Exactly one of these two statements is true\" means one is true and the other is false. \"If P, then Q\" is false only when P is true and Q is false; otherwise it is true. \"Of X and Y (two different ferries), one ... and the other ...\" means X and Y are different ferries, and one of them satisfies the first description while the other satisfies the second.",
      q="Which island is each ferry bound for? List the six islands for piers 1 to 6, in that order.", pos=None),
 dict(id='c14', cfg='chess', seed=3, d='extreme', ask='name',
      intro="Six chess club members sit around a round table with seats numbered 1 to 6 in clockwise order. Each member favours a different chess opening, drinks a different drink and comes from a different town.",
      labels={'name': 'Members', 'opening': 'Favourite openings', 'drink': 'Drinks', 'town': 'Towns'},
      defs="Seat 2 is immediately clockwise from seat 1, seat 3 immediately clockwise from seat 2, and so on, and seat 1 is immediately clockwise from seat 6. \"Sits next to\" means in one of the two seats immediately clockwise or immediately counter-clockwise. \"Sits directly opposite\" means exactly three seats away (1 and 4, 2 and 5, 3 and 6). \"Exactly one of these two statements is true\" means one is true and the other is false. \"If P, then Q\" is false only when P is true and Q is false; otherwise it is true. \"Of X and Y (two different members), one ... and the other ...\" means X and Y are different people, and one of them satisfies the first description while the other satisfies the second.",
      q="Who sits in each seat? List the six members for seats 1 to 6, in that order.", pos=None),
 dict(id='c15', cfg='cabins', seed=0, d='extreme', ask='guest',
      intro="Six holiday cabins are arranged in three rows (north, middle and south) and two columns (west and east), giving the north-west, north-east, middle-west, middle-east, south-west and south-east cabins. Each cabin has a different guest, who chose a different activity and ordered a different breakfast, and each cabin has a door of a different colour.",
      labels={'guest': 'Guests', 'activity': 'Activities', 'breakfast': 'Breakfasts', 'door': 'Door colours'},
      defs="\"Directly north of\" means in the same column and in the row immediately to the north. \"Directly west of, in the same row\" means in the same row and in the west column while the other is in the east column. \"Shares a wall with\" means directly north or south in the same column, or directly west or east in the same row (cabins touching only at a corner do not share a wall). \"The same row\", \"different rows\" and \"the same column\" have their usual meaning. \"Exactly one of these two statements is true\" means one is true and the other is false. \"If P, then Q\" is false only when P is true and Q is false; otherwise it is true. \"Of X and Y (two different cabins), one ... and the other ...\" means X and Y are different cabins, and one of them satisfies the first description while the other satisfies the second.",
      q="Whose cabin is whose? List the six guests in this order: north-west, north-east, middle-west, middle-east, south-west, south-east.", pos=None),
]

GENERIC_EXTRA = "\"Exactly one of these two statements is true\" means one is true and the other is false. \"If P, then Q\" is false only when P is true and Q is false; otherwise it is true."


def build_case(p):
    th, cats, res = build(p['cfg'], p['seed'])
    sol, clues = res
    geo = CONFIGS[p['cfg']]['geo']
    order = list(clues)
    random.Random(p['seed'] * 7 + 1).shuffle(order)
    lines = [f"{i+1}. {render(c, th, geo)}" for i, c in enumerate(order)]
    n = geo.n
    # sanity: unique
    cnt, sols = count_solutions(n, cats, clues, cap=3, collect=True)
    assert cnt == 1
    ans = []
    for pos in range(n):
        v = [v for v in range(n) if sol[p['ask']][v] == pos][0]
        ans.append(th.cats[p['ask']][v])
    table = []
    for pos in range(n):
        row = []
        for c in cats:
            v = [v for v in range(n) if sol[c][v] == pos][0]
            row.append(th.cats[c][v])
        table.append(row)
    def disp(c):
        vals = list(th.cats[c])
        if c in ('age', 'years'):
            return sorted(vals, key=int)
        return sorted(vals, key=lambda x: x.lower().replace('a ', '', 1) if x.startswith('a ') else x.lower())
    catlines = '\n'.join(f"- {p['labels'][c]}: {', '.join(disp(c))}" for c in cats)
    assert ans != disp(p['ask']) and ans != disp(p['ask'])[::-1], ('answer order leaks from listing', p['id'])
    defs = p['defs']
    kinds = {c.kind for c in clues}
    if ('xor' in kinds or 'ifthen' in kinds) and 'Exactly one of these two' not in defs:
        defs += ' ' + GENERIC_EXTRA
    if 'ofxy' in kinds and 'Of X and Y' not in defs:
        defs += " \"Of X and Y (two different ...), one ... and the other ...\" means X and Y are different, and one of them satisfies the first description while the other satisfies the second."
    unit = {'name': 'names', 'owner': 'names', 'dog': 'dog breeds', 'food': 'foods', 'topic': 'topics', 'destination': 'destinations',
            'age': 'ages', 'artist': 'names', 'country': 'countries', 'scientist': 'names', 'island': 'island names', 'guest': 'names'}[p['ask']]
    prompt = (
        f"{p['intro']} No two share a value in any category, and every value listed below is used exactly once.\n\n"
        f"Categories:\n{catlines}\n\n"
        f"Definitions: {defs}\n\n"
        f"Clues:\n" + '\n'.join(lines) + "\n\n"
        f"Exactly one arrangement satisfies all of the clues.\n\n"
        f"Question: {p['q']}\n\n"
        f"Answer format: the {n} {unit} separated by commas, in the order asked, each written exactly as in the category list above (for example: first, second, third, ...). {ONE}"
    )
    kind_hist = {}
    for c in clues:
        kind_hist[c.kind] = kind_hist.get(c.kind, 0) + 1
    notes = (f"Generated by grid/gridlib.py (config {p['cfg']}, seed {p['seed']}); {len(clues)} clues, minimised so that removing any single clue breaks uniqueness where possible. "
             f"Exhaustive solver (all permutations per category with clue pruning) finds exactly one solution; independently re-verified by grid/verify.mjs, which parses the English clues back from this prompt and brute-forces every arrangement. "
             f"Full solution by position: " + ' | '.join(f"{i+1}: " + ', '.join(r) for i, r in enumerate(table)) + f". Clue kinds: {kind_hist}.")
    return dict(id=p['id'], difficulty=p['d'], prompt=prompt, expected=[', '.join(ans)], notes=notes,
                solution=table, cats=cats, values={c: th.cats[c] for c in cats}, cfg=p['cfg'])


if __name__ == '__main__':
    only = sys.argv[1:] or None
    out = []
    for p in PICKS:
        if only and p['id'] not in only:
            continue
        c = build_case(p)
        out.append(c)
        print(c['id'], c['difficulty'], c['expected'][0], flush=True)
    json.dump(out, open('grid_cases.json' if not only else 'grid_cases_partial.json', 'w'), indent=1)
