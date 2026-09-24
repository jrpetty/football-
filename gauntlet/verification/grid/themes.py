from render import Theme

ORD = {1: 'first', 2: 'second', 3: 'third', 4: 'fourth', 5: 'fifth', 6: 'sixth'}
WORDNUM = {1: 'one', 2: 'two', 3: 'three', 4: 'four', 5: 'five'}


def people_theme(cats, subj_map, pred_map, npred_map, place, ordinal=False, people='people'):
    """Linear line of people. place e.g. 'spot' -> 'is in spot 3'."""
    def pp(p):
        return f"is in {place} {p+1}"
    def pn(p):
        return f"is not in {place} {p+1}"
    extra = {
        'end': f"is in one of the two end {place}s",
        'adj': f"is in a {place} next to",
        'notadj': f"is not in a {place} next to",
        'left': f"is somewhere to the left of",
        'immleft': f"is immediately to the left of",
        'dist': lambda X, Y, d: f"The {place} numbers of {X[0].lower()+X[1:] if X.startswith('The ') else X} and {Y} differ by exactly {d}.",
        'people': people,
    }
    return Theme(cats, lambda c, v: subj_map[c](v), lambda c, v: pred_map[c](v), lambda c, v: npred_map[c](v), pp, pn, extra)


# ---------------------------------------------------------------- T1: beach line (4x3)
def t_beach():
    cats = {
        'name': ['Hana', 'Diego', 'Maeve', 'Tobias'],
        'color': ['orange', 'teal', 'white', 'purple'],
        'snack': ['pretzels', 'grapes', 'trail mix', 'rice cakes'],
    }
    subj = {'name': lambda v: v, 'color': lambda v: f"the person with the {v} kayak", 'snack': lambda v: f"the person who brought {v}"}
    pred = {'name': lambda v: f"is {v}", 'color': lambda v: f"has the {v} kayak", 'snack': lambda v: f"brought {v}"}
    npred = {'name': lambda v: f"is not {v}", 'color': lambda v: f"does not have the {v} kayak", 'snack': lambda v: f"did not bring {v}"}
    return people_theme(cats, subj, pred, npred, 'spot')


# ---------------------------------------------------------------- T2: houseboats (4x3)
def t_boats():
    cats = {
        'owner': ['Ines', 'Rafe', 'Colette', 'Anders'],
        'dog': ['whippet', 'beagle', 'corgi', 'poodle'],
        'hull': ['green', 'yellow', 'navy', 'red'],
    }
    subj = {'owner': lambda v: v, 'dog': lambda v: f"the owner of the {v}", 'hull': lambda v: f"the owner of the {v} houseboat"}
    pred = {'owner': lambda v: f"is {v}", 'dog': lambda v: f"owns the {v}", 'hull': lambda v: f"owns the {v} houseboat"}
    npred = {'owner': lambda v: f"is not {v}", 'dog': lambda v: f"does not own the {v}", 'hull': lambda v: f"does not own the {v} houseboat"}
    th = people_theme(cats, subj, pred, npred, 'berth')
    th.extra.update({
        'left': 'is moored somewhere to the left of',
        'immleft': 'is moored immediately to the left of',
        'adj': 'is moored in a berth next to',
        'notadj': 'is not moored in a berth next to',
        'end': 'is moored in one of the two end berths',
    })
    th.pos_pred = lambda p: f"is moored in berth {p+1}"
    th.pos_npred = lambda p: f"is not moored in berth {p+1}"
    return th


# ---------------------------------------------------------------- T3: food trucks (5x3)
def t_trucks():
    cats = {
        'owner': ['Yusuf', 'Bettina', 'Kofi', 'Marisol', 'Lachlan'],
        'food': ['dumplings', 'arepas', 'falafel', 'crepes', 'ramen'],
        'color': ['lime', 'silver', 'maroon', 'sky-blue', 'black'],
    }
    subj = {'owner': lambda v: f"{v}'s truck", 'food': lambda v: f"the truck selling {v}", 'color': lambda v: f"the {v} truck"}
    pred = {'owner': lambda v: f"belongs to {v}", 'food': lambda v: f"sells {v}", 'color': lambda v: f"is painted {v}"}
    npred = {'owner': lambda v: f"does not belong to {v}", 'food': lambda v: f"does not sell {v}", 'color': lambda v: f"is not painted {v}"}
    th = people_theme(cats, subj, pred, npred, 'bay', people='trucks')
    th.pos_pred = lambda p: f"is parked in bay {p+1}"
    th.pos_npred = lambda p: f"is not parked in bay {p+1}"
    th.extra.update({'adj': 'is parked in a bay next to', 'notadj': 'is not parked in a bay next to',
                     'left': 'is parked somewhere to the left of', 'immleft': 'is parked immediately to the left of',
                     'end': 'is parked in one of the two end bays'})
    return th


# ---------------------------------------------------------------- T4: apartment floors (5x3, vertical)
def t_floors():
    cats = {
        'name': ['Greta', 'Oskar', 'Nadia', 'Pieter', 'Wen'],
        'instrument': ['cello', 'banjo', 'oboe', 'harp', 'tuba'],
        'plant': ['fern', 'cactus', 'orchid', 'bonsai', 'ivy'],
    }
    subj = {'name': lambda v: v, 'instrument': lambda v: f"the {v} player", 'plant': lambda v: f"the resident with the {v}"}
    pred = {'name': lambda v: f"is {v}", 'instrument': lambda v: f"plays the {v}", 'plant': lambda v: f"keeps the {v}"}
    npred = {'name': lambda v: f"is not {v}", 'instrument': lambda v: f"does not play the {v}", 'plant': lambda v: f"does not keep the {v}"}
    th = people_theme(cats, subj, pred, npred, 'floor')
    th.pos_pred = lambda p: f"lives on floor {p+1}"
    th.pos_npred = lambda p: f"does not live on floor {p+1}"
    th.extra.update({
        'adj': 'lives on a floor directly above or directly below',
        'notadj': 'does not live on a floor directly above or directly below',
        'left': 'lives on some floor below',
        'immleft': 'lives on the floor directly below',
        'end': 'lives on either the top floor or the bottom floor',
        'dist': lambda X, Y, d: f"The floor numbers of {X[0].lower()+X[1:] if X.startswith('The ') else X} and {Y} differ by exactly {d}.",
        'people': 'residents',
    })
    return th


# ---------------------------------------------------------------- T5: conference slots (5x4)
def t_talks():
    times = ['9:00', '10:00', '11:00', '12:00', '13:00']
    cats = {
        'speaker': ['Dr. Achebe', 'Dr. Lindqvist', 'Dr. Moreau', 'Dr. Okafor', 'Dr. Szabo'],
        'topic': ['glaciers', 'bee colonies', 'tidal power', 'soil fungi', 'meteorites'],
        'city': ['Porto', 'Tallinn', 'Hobart', 'Quito', 'Nagoya'],
        'drink': ['espresso', 'green tea', 'lemonade', 'hot chocolate', 'mineral water'],
    }
    subj = {'speaker': lambda v: v, 'topic': lambda v: f"the speaker on {v}", 'city': lambda v: f"the speaker from {v}", 'drink': lambda v: f"the speaker who ordered {v}"}
    pred = {'speaker': lambda v: f"is {v}", 'topic': lambda v: f"talks about {v}", 'city': lambda v: f"is from {v}", 'drink': lambda v: f"ordered {v}"}
    npred = {'speaker': lambda v: f"is not {v}", 'topic': lambda v: f"does not talk about {v}", 'city': lambda v: f"is not from {v}", 'drink': lambda v: f"did not order {v}"}
    th = people_theme(cats, subj, pred, npred, 'slot', people='speakers')
    th.pos_pred = lambda p: f"speaks at {times[p]}"
    th.pos_npred = lambda p: f"does not speak at {times[p]}"
    th.extra.update({
        'adj': 'speaks in a slot directly before or directly after',
        'notadj': 'does not speak in a slot directly before or directly after',
        'left': 'speaks at some point earlier than',
        'immleft': 'speaks in the slot directly before',
        'end': 'speaks either first (9:00) or last (13:00)',
        'dist': lambda X, Y, d: f"{X} and {Y} speak exactly {d} hours apart.",
    })
    return th


# ---------------------------------------------------------------- T6: ages (5x4 numeric) - greenhouse plots
def t_gardeners():
    cats = {
        'name': ['Aurelio', 'Birgit', 'Chidi', 'Dagny', 'Emeric'],
        'crop': ['leeks', 'melons', 'chard', 'peppers', 'radishes'],
        'tool': ['trowel', 'hoe', 'rake', 'pruner', 'dibber'],
        'age': ['27', '31', '34', '38', '45'],
    }
    ages = [27, 31, 34, 38, 45]
    subj = {'name': lambda v: v, 'crop': lambda v: f"the gardener growing {v}", 'tool': lambda v: f"the gardener with the {v}", 'age': lambda v: f"the {v}-year-old"}
    pred = {'name': lambda v: f"is {v}", 'crop': lambda v: f"grows {v}", 'tool': lambda v: f"has the {v}", 'age': lambda v: f"is {v} years old"}
    npred = {'name': lambda v: f"is not {v}", 'crop': lambda v: f"does not grow {v}", 'tool': lambda v: f"does not have the {v}", 'age': lambda v: f"is not {v} years old"}
    th = people_theme(cats, subj, pred, npred, 'plot', people='gardeners')
    th.pos_pred = lambda p: f"works plot {p+1}"
    th.pos_npred = lambda p: f"does not work plot {p+1}"
    th.extra.update({
        'adj': 'works a plot next to the plot of',
        'notadj': 'does not work a plot next to the plot of',
        'left': 'works a plot somewhere to the left of the plot of',
        'immleft': 'works the plot immediately to the left of the plot of',
        'end': 'works one of the two end plots',
        'numgt': lambda X, Y: f"{X} is older than {Y}.",
        'numdiff': lambda X, Y, d: f"{X} is exactly {d} years older than {Y}.",
        'dist': lambda X, Y, d: f"The plot numbers of {X[0].lower()+X[1:] if X.startswith('The ') else X} and {Y} differ by exactly {d}.",
    })
    return th, {'age': ages}


# ---------------------------------------------------------------- T7: round table (6x3 circle)
def t_table():
    cats = {
        'name': ['Farida', 'Gustavo', 'Hollis', 'Ingrid', 'Jomo', 'Katya'],
        'dessert': ['baklava', 'flan', 'tiramisu', 'mochi', 'pavlova', 'kheer'],
        'hat': ['scarlet', 'olive', 'gold', 'lilac', 'cream', 'charcoal'],
    }
    subj = {'name': lambda v: v, 'dessert': lambda v: f"the guest who ordered {v}", 'hat': lambda v: f"the guest in the {v} hat"}
    pred = {'name': lambda v: f"is {v}", 'dessert': lambda v: f"ordered {v}", 'hat': lambda v: f"wears the {v} hat"}
    npred = {'name': lambda v: f"is not {v}", 'dessert': lambda v: f"did not order {v}", 'hat': lambda v: f"does not wear the {v} hat"}
    extra = {
        'adj': 'sits next to',
        'notadj': 'does not sit next to',
        'immcw': 'sits immediately clockwise from',
        'opposite': 'sits directly opposite',
        'people': 'guests',
    }
    return Theme(cats, lambda c, v: subj[c](v), lambda c, v: pred[c](v), lambda c, v: npred[c](v),
                 lambda p: f"sits in seat {p+1}", lambda p: f"does not sit in seat {p+1}", extra)


# ---------------------------------------------------------------- T8: lockers (2x3 grid, 6x3)
LOCK = ['top-left', 'top-middle', 'top-right', 'bottom-left', 'bottom-middle', 'bottom-right']

def t_lockers():
    cats = {
        'owner': ['Lucian', 'Mireille', 'Nnamdi', 'Odile', 'Pranav', 'Quilla'],
        'sport': ['fencing', 'rowing', 'squash', 'archery', 'judo', 'curling'],
        'lock': ['brass', 'red', 'blue', 'green', 'black', 'white'],
    }
    subj = {'owner': lambda v: f"{v}'s locker", 'sport': lambda v: f"the locker of the {v} player" if v not in ('fencing', 'rowing', 'archery', 'judo', 'curling') else {'fencing': "the fencer's locker", 'rowing': "the rower's locker", 'archery': "the archer's locker", 'judo': "the judoka's locker", 'curling': "the curler's locker"}[v], 'lock': lambda v: f"the locker with the {v} padlock"}
    pred = {'owner': lambda v: f"belongs to {v}", 'sport': lambda v: {'fencing': 'belongs to the fencer', 'rowing': 'belongs to the rower', 'squash': 'belongs to the squash player', 'archery': 'belongs to the archer', 'judo': 'belongs to the judoka', 'curling': 'belongs to the curler'}[v], 'lock': lambda v: f"has the {v} padlock"}
    npred = {'owner': lambda v: f"does not belong to {v}", 'sport': lambda v: {'fencing': 'does not belong to the fencer', 'rowing': 'does not belong to the rower', 'squash': 'does not belong to the squash player', 'archery': 'does not belong to the archer', 'judo': 'does not belong to the judoka', 'curling': 'does not belong to the curler'}[v], 'lock': lambda v: f"does not have the {v} padlock"}
    extra = {
        'adj': 'shares a side with',
        'notadj': 'does not share a side with',
        'above': lambda X, Y: f"{X} is directly above {Y}.",
        'gimmleft': lambda X, Y: f"{X} is immediately to the left of {Y}, in the same row.",
        'samerow': lambda X, Y: f"{X} and {Y} are two different lockers in the same row.",
        'samecol': lambda X, Y: f"{X} and {Y} are two different lockers in the same column.",
        'diffrow': lambda X, Y: f"{X} and {Y} are in different rows.",
        'people': 'lockers',
    }
    return Theme(cats, lambda c, v: subj[c](v), lambda c, v: pred[c](v), lambda c, v: npred[c](v),
                 lambda p: f"is the {LOCK[p]} locker", lambda p: f"is not the {LOCK[p]} locker", extra)


# ---------------------------------------------------------------- T9: running lanes (6x4)
def t_lanes():
    cats = {
        'runner': ['Abebe', 'Brianna', 'Casimir', 'Delphine', 'Eero', 'Fumiko'],
        'country': ['Chile', 'Ghana', 'Norway', 'Vietnam', 'Canada', 'Greece'],
        'shoes': ['orange', 'grey', 'pink', 'violet', 'mint', 'bronze'],
        'club': ['Harriers', 'Comets', 'Otters', 'Falcons', 'Pumas', 'Vipers'],
    }
    subj = {'runner': lambda v: v, 'country': lambda v: f"the runner from {v}", 'shoes': lambda v: f"the runner in {v} shoes", 'club': lambda v: f"the {v[:-1]} runner" if v != 'Harriers' else 'the Harrier'}
    subj['club'] = lambda v: {'Harriers': 'the Harriers runner', 'Comets': 'the Comets runner', 'Otters': 'the Otters runner', 'Falcons': 'the Falcons runner', 'Pumas': 'the Pumas runner', 'Vipers': 'the Vipers runner'}[v]
    pred = {'runner': lambda v: f"is {v}", 'country': lambda v: f"is from {v}", 'shoes': lambda v: f"wears {v} shoes", 'club': lambda v: f"runs for the {v}"}
    npred = {'runner': lambda v: f"is not {v}", 'country': lambda v: f"is not from {v}", 'shoes': lambda v: f"does not wear {v} shoes", 'club': lambda v: f"does not run for the {v}"}
    th = people_theme(cats, subj, pred, npred, 'lane', people='runners')
    th.pos_pred = lambda p: f"is in lane {p+1}"
    th.pos_npred = lambda p: f"is not in lane {p+1}"
    th.extra.update({
        'adj': 'is in a lane next to',
        'notadj': 'is not in a lane next to',
        'left': 'is in a lower-numbered lane than',
        'immleft': 'is in the lane numbered exactly one lower than',
        'end': 'is in lane 1 or lane 6',
        'dist': lambda X, Y, d: f"The lane numbers of {X[0].lower()+X[1:] if X.startswith('The ') else X} and {Y} differ by exactly {d}.",
    })
    return th


# ---------------------------------------------------------------- T10: parking bays numeric (6x4)
def t_lab():
    cats = {
        'scientist': ['Adaeze', 'Bartosz', 'Cosima', 'Dmitri', 'Esperanza', 'Florian'],
        'animal': ['axolotl', 'gecko', 'tarantula', 'hedgehog', 'chinchilla', 'tortoise'],
        'project': ['vaccines', 'robotics', 'lasers', 'enzymes', 'drones', 'batteries'],
        'years': ['2', '3', '5', '8', '12', '13'],
    }
    yrs = [2, 3, 5, 8, 12, 13]
    subj = {'scientist': lambda v: v, 'animal': lambda v: f"the scientist who keeps the {v}", 'project': lambda v: f"the scientist working on {v}", 'years': lambda v: f"the scientist with {v} years at the institute"}
    pred = {'scientist': lambda v: f"is {v}", 'animal': lambda v: f"keeps the {v}", 'project': lambda v: f"works on {v}", 'years': lambda v: f"has been at the institute for {v} years"}
    npred = {'scientist': lambda v: f"is not {v}", 'animal': lambda v: f"does not keep the {v}", 'project': lambda v: f"does not work on {v}", 'years': lambda v: f"has not been at the institute for {v} years"}
    th = people_theme(cats, subj, pred, npred, 'office', people='scientists')
    th.pos_pred = lambda p: f"has office {p+1}"
    th.pos_npred = lambda p: f"does not have office {p+1}"
    th.extra.update({
        'adj': 'has an office next to the office of',
        'notadj': 'does not have an office next to the office of',
        'left': 'has a lower office number than',
        'immleft': 'has the office numbered exactly one lower than the office of',
        'end': 'has office 1 or office 6',
        'numgt': lambda X, Y: f"{X} has been at the institute longer than {Y}.",
        'numdiff': lambda X, Y, d: f"{X} has been at the institute exactly {d} years longer than {Y}.",
        'dist': lambda X, Y, d: f"The office numbers of {X[0].lower()+X[1:] if X.startswith('The ') else X} and {Y} differ by exactly {d}.",
    })
    return th, {'years': yrs}


# ---------------------------------------------------------------- T11: ferries (6x4 linear)
def t_ferries():
    cats = {
        'captain': ['Rasmus', 'Soledad', 'Thiago', 'Ulrike', 'Viktor', 'Wairimu'],
        'island': ['Arran', 'Ischia', 'Hvar', 'Paros', 'Lanai', 'Texel'],
        'cargo': ['timber', 'olive oil', 'copper', 'wool', 'cheese', 'glass'],
        'funnel': ['crimson', 'ochre', 'cobalt', 'jade', 'ivory', 'slate'],
    }
    subj = {'captain': lambda v: f"{v}'s ferry", 'island': lambda v: f"the ferry bound for {v}", 'cargo': lambda v: f"the ferry carrying {v}", 'funnel': lambda v: f"the ferry with the {v} funnel"}
    pred = {'captain': lambda v: f"is captained by {v}", 'island': lambda v: f"is bound for {v}", 'cargo': lambda v: f"carries {v}", 'funnel': lambda v: f"has the {v} funnel"}
    npred = {'captain': lambda v: f"is not captained by {v}", 'island': lambda v: f"is not bound for {v}", 'cargo': lambda v: f"does not carry {v}", 'funnel': lambda v: f"does not have the {v} funnel"}
    th = people_theme(cats, subj, pred, npred, 'pier', people='ferries')
    th.pos_pred = lambda p: f"is docked at pier {p+1}"
    th.pos_npred = lambda p: f"is not docked at pier {p+1}"
    th.extra.update({
        'adj': 'is docked at a pier next to',
        'notadj': 'is not docked at a pier next to',
        'left': 'is docked at a lower-numbered pier than',
        'immleft': 'is docked at the pier numbered exactly one lower than',
        'end': 'is docked at pier 1 or pier 6',
        'dist': lambda X, Y, d: f"The pier numbers of {X[0].lower()+X[1:] if X.startswith('The ') else X} and {Y} differ by exactly {d}.",
    })
    return th


# ---------------------------------------------------------------- T12: chess club round table (6x4 circle)
def t_chess():
    cats = {
        'name': ['Anouk', 'Bashir', 'Cleo', 'Dario', 'Elif', 'Fenna'],
        'opening': ['Sicilian', 'French', 'Caro-Kann', 'English', 'Dutch', 'Pirc'],
        'drink': ['kombucha', 'matcha', 'cider', 'lassi', 'horchata', 'kvass'],
        'town': ['Utrecht', 'Izmir', 'Lyon', 'Bergen', 'Tartu', 'Cusco'],
    }
    subj = {'name': lambda v: v, 'opening': lambda v: f"the {v} fan", 'drink': lambda v: f"the {v} drinker", 'town': lambda v: f"the member from {v}"}
    pred = {'name': lambda v: f"is {v}", 'opening': lambda v: f"favours the {v}", 'drink': lambda v: f"drinks {v}", 'town': lambda v: f"is from {v}"}
    npred = {'name': lambda v: f"is not {v}", 'opening': lambda v: f"does not favour the {v}", 'drink': lambda v: f"does not drink {v}", 'town': lambda v: f"is not from {v}"}
    extra = {
        'adj': 'sits next to',
        'notadj': 'does not sit next to',
        'immcw': 'sits immediately clockwise from',
        'opposite': 'sits directly opposite',
        'people': 'members',
    }
    return Theme(cats, lambda c, v: subj[c](v), lambda c, v: pred[c](v), lambda c, v: npred[c](v),
                 lambda p: f"sits in seat {p+1}", lambda p: f"does not sit in seat {p+1}", extra)


# ---------------------------------------------------------------- T13: cabins 3 rows x 2 cols (6x4 grid)
CABIN = ['north-west', 'north-east', 'middle-west', 'middle-east', 'south-west', 'south-east']

def t_cabins():
    cats = {
        'guest': ['Amara', 'Benedikt', 'Carys', 'Daisuke', 'Elodie', 'Faisal'],
        'activity': ['kayaking', 'birdwatching', 'pottery', 'yoga', 'fishing', 'painting'],
        'breakfast': ['porridge', 'waffles', 'shakshuka', 'congee', 'muesli', 'pancakes'],
        'door': ['red', 'blue', 'yellow', 'green', 'white', 'black'],
    }
    subj = {'guest': lambda v: f"{v}'s cabin", 'activity': lambda v: f"the cabin of the guest who chose {v}", 'breakfast': lambda v: f"the cabin that ordered {v}", 'door': lambda v: f"the cabin with the {v} door"}
    pred = {'guest': lambda v: f"is {v}'s", 'activity': lambda v: f"belongs to the guest who chose {v}", 'breakfast': lambda v: f"ordered {v}", 'door': lambda v: f"has the {v} door"}
    npred = {'guest': lambda v: f"is not {v}'s", 'activity': lambda v: f"does not belong to the guest who chose {v}", 'breakfast': lambda v: f"did not order {v}", 'door': lambda v: f"does not have the {v} door"}
    extra = {
        'adj': 'shares a wall with',
        'notadj': 'does not share a wall with',
        'above': lambda X, Y: f"{X} is directly north of {Y}.",
        'gimmleft': lambda X, Y: f"{X} is directly west of {Y}, in the same row.",
        'samerow': lambda X, Y: f"{X} and {Y} are two different cabins in the same row.",
        'samecol': lambda X, Y: f"{X} and {Y} are two different cabins in the same column.",
        'diffrow': lambda X, Y: f"{X} and {Y} are in different rows.",
        'people': 'cabins',
    }
    return Theme(cats, lambda c, v: subj[c](v), lambda c, v: pred[c](v), lambda c, v: npred[c](v),
                 lambda p: f"is the {CABIN[p]} cabin", lambda p: f"is not the {CABIN[p]} cabin", extra)


# ---------------------------------------------------------------- T14: train carriages (5x4 linear)
def t_train():
    cats = {
        'passenger': ['Ottilie', 'Ruairi', 'Sanjay', 'Tamsin', 'Vesna'],
        'genre': ['poetry', 'mystery', 'history', 'sci-fi', 'cookbook'],
        'destination': ['Bern', 'Ghent', 'Lille', 'Metz', 'Turin'],
        'snack': ['almonds', 'a pear', 'a scone', 'crisps', 'toffee'],
    }
    subj = {'passenger': lambda v: v, 'genre': lambda v: f"the passenger reading {v}" if v != 'cookbook' else 'the passenger reading a cookbook', 'destination': lambda v: f"the passenger going to {v}", 'snack': lambda v: f"the passenger eating {v}"}
    pred = {'passenger': lambda v: f"is {v}", 'genre': lambda v: f"is reading {v}" if v != 'cookbook' else 'is reading a cookbook', 'destination': lambda v: f"is going to {v}", 'snack': lambda v: f"is eating {v}"}
    npred = {'passenger': lambda v: f"is not {v}", 'genre': lambda v: f"is not reading {v}" if v != 'cookbook' else 'is not reading a cookbook', 'destination': lambda v: f"is not going to {v}", 'snack': lambda v: f"is not eating {v}"}
    th = people_theme(cats, subj, pred, npred, 'carriage', people='passengers')
    th.pos_pred = lambda p: f"is in carriage {p+1}"
    th.pos_npred = lambda p: f"is not in carriage {p+1}"
    th.extra.update({
        'adj': 'is in a carriage next to the carriage of',
        'notadj': 'is not in a carriage next to the carriage of',
        'left': 'is in a carriage nearer the front than',
        'immleft': 'is in the carriage directly in front of the carriage of',
        'end': 'is in the first or the last carriage',
        'dist': lambda X, Y, d: f"The carriage numbers of {X[0].lower()+X[1:] if X.startswith('The ') else X} and {Y} differ by exactly {d}.",
    })
    return th


# ---------------------------------------------------------------- T15: gallery wall (6x3 linear)
def t_gallery():
    cats = {
        'artist': ['Ilse', 'Jurgen', 'Kalani', 'Lorcan', 'Marit', 'Nuno'],
        'subject': ['a lighthouse', 'a fox', 'a market', 'a glacier', 'an orchard', 'a violin'],
        'frame': ['walnut', 'gilt', 'steel', 'bamboo', 'oak', 'resin'],
    }
    subj = {'artist': lambda v: f"{v}'s painting", 'subject': lambda v: f"the painting of {v}", 'frame': lambda v: f"the painting in the {v} frame"}
    pred = {'artist': lambda v: f"is by {v}", 'subject': lambda v: f"shows {v}", 'frame': lambda v: f"has the {v} frame"}
    npred = {'artist': lambda v: f"is not by {v}", 'subject': lambda v: f"does not show {v}", 'frame': lambda v: f"does not have the {v} frame"}
    th = people_theme(cats, subj, pred, npred, 'hook', people='paintings')
    th.pos_pred = lambda p: f"hangs on hook {p+1}"
    th.pos_npred = lambda p: f"does not hang on hook {p+1}"
    th.extra.update({
        'adj': 'hangs next to',
        'notadj': 'does not hang next to',
        'left': 'hangs somewhere to the left of',
        'immleft': 'hangs immediately to the left of',
        'end': 'hangs at one of the two ends of the row',
        'dist': lambda X, Y, d: f"The hook numbers of {X[0].lower()+X[1:] if X.startswith('The ') else X} and {Y} differ by exactly {d}.",
    })
    return th
