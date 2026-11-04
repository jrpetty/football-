"""Themes for reasoning.grid-ladder (same format as grid_extreme/themes.py, but every category lists 12 values so
the same theme can be used from a 4-person grid up to a 12-person grid; a level uses the first n values).
Every entity is a person at a numbered position (1 = leftmost). Templates per category:
  S = how a clue names the person with that value, P = "has that value", N = negated predicate.
The prompt prints these templates as a legend, which the independent checker (grid_extreme/verify_sat.py) parses back.
Names are invented and drawn from many backgrounds; ages are the only numeric category."""

T = {}

T['terrace'] = dict(place='house', people='neighbours',
                    intro="{C} neighbours live in the {n} houses of Linden Terrace, numbered 1 to {N} from left to right.", cats=[
    ('Neighbours', ['Ada', 'Bram', 'Cleo', 'Dev', 'Esi', 'Finn', 'Gus', 'Hana', 'Ivo', 'Jun', 'Kofi', 'Lena'], '{v}', 'is {v}', 'is not {v}'),
    ('Pets', ['cat', 'dog', 'parrot', 'rabbit', 'tortoise', 'hamster', 'ferret', 'goldfish', 'gecko', 'canary', 'guinea pig', 'chinchilla'],
     'the {v} owner', 'owns the {v}', 'does not own the {v}'),
    ('Drinks', ['tea', 'coffee', 'cocoa', 'juice', 'milk', 'water', 'lemonade', 'kefir', 'lassi', 'kombucha', 'cola', 'ginger beer'],
     'the {v} drinker', 'drinks {v}', 'does not drink {v}'),
    ('Car colours', ['red', 'blue', 'green', 'white', 'black', 'silver', 'orange', 'yellow', 'purple', 'brown', 'gold', 'pink'],
     'the driver of the {v} car', 'drives the {v} car', 'does not drive the {v} car'),
    ('Hobbies', ['chess', 'pottery', 'surfing', 'archery', 'baking', 'climbing', 'knitting', 'fencing', 'rowing', 'juggling', 'origami', 'karate'],
     'the person who does {v}', 'does {v}', 'does not do {v}'),
    ('Jobs', ['nurse', 'pilot', 'baker', 'chemist', 'lawyer', 'plumber', 'teacher', 'tailor', 'barber', 'vet', 'welder', 'judge'],
     'the {v}', 'is the {v}', 'is not the {v}'),
    ('Instruments', ['guitar', 'banjo', 'flute', 'ukulele', 'harmonica', 'fiddle', 'drum', 'accordion', 'cello', 'trumpet', 'harp', 'oboe'],
     'the {v} player', 'plays the {v}', 'does not play the {v}'),
    ('Ages', ['24', '29', '31', '36', '42', '47', '53', '60', '21', '58', '64', '71'], 'the {v}-year-old', 'is {v} years old', 'is not {v} years old'),
])

T['marina'] = dict(place='berth', people='sailors',
                   intro="{C} sailors have moored their boats at the {n} berths of a marina pontoon, numbered 1 to {N} from left to right.", cats=[
    ('Sailors', ['Amara', 'Bjorn', 'Chidi', 'Dana', 'Elif', 'Farid', 'Greta', 'Hamza', 'Ines', 'Joao', 'Kaia', 'Luca'], '{v}', 'is {v}', 'is not {v}'),
    ('Boat colours', ['teal', 'white', 'navy', 'red', 'yellow', 'black', 'green', 'orange', 'grey', 'maroon', 'lilac', 'cream'],
     'the sailor with the {v} boat', 'has the {v} boat', 'does not have the {v} boat'),
    ('Home ports', ['Brest', 'Cowes', 'Kiel', 'Malmo', 'Bergen', 'Cadiz', 'Galway', 'Oban', 'Split', 'Genoa', 'Porto', 'Tromso'],
     'the sailor from {v}', 'is from {v}', 'is not from {v}'),
    ('Catches', ['mackerel', 'cod', 'bass', 'herring', 'plaice', 'pollock', 'sole', 'crabs', 'squid', 'tuna', 'hake', 'prawns'],
     'the sailor who caught {v}', 'caught {v}', 'did not catch {v}'),
    ('Dogs', ['terrier', 'collie', 'spaniel', 'lurcher', 'poodle', 'dachshund', 'whippet', 'beagle', 'boxer', 'husky', 'pug', 'labrador'],
     'the {v} owner', 'has the {v}', 'does not have the {v}'),
    ('Snacks', ['toffee', 'grapes', 'crisps', 'almonds', 'fudge', 'olives', 'mints', 'dates', 'flapjack', 'pretzels', 'figs', 'oatcakes'],
     'the sailor eating {v}', 'is eating {v}', 'is not eating {v}'),
    ('Jackets', ['tartan', 'striped', 'quilted', 'waxed', 'fleece', 'denim', 'tweed', 'leather', 'oilskin', 'corduroy', 'suede', 'canvas'],
     'the sailor in the {v} jacket', 'wears the {v} jacket', 'does not wear the {v} jacket'),
    ('Ages', ['26', '33', '38', '41', '47', '52', '59', '63', '29', '44', '68', '72'], 'the {v}-year-old', 'is {v} years old', 'is not {v} years old'),
])

T['allotments'] = dict(place='plot', people='gardeners',
                       intro="{C} gardeners each tend one of the {n} plots in a row at the town allotments, numbered 1 to {N} from left to right.", cats=[
    ('Gardeners', ['Ayesha', 'Bernard', 'Chiara', 'Dmitri', 'Efua', 'Florian', 'Gwen', 'Haruto', 'Isla', 'Jaswinder', 'Kwame', 'Lotte'], '{v}', 'is {v}', 'is not {v}'),
    ('Vegetables', ['leeks', 'carrots', 'beans', 'onions', 'potatoes', 'kale', 'beetroot', 'parsnips', 'radishes', 'squash', 'garlic', 'celery'],
     'the gardener growing {v}', 'grows {v}', 'does not grow {v}'),
    ('Flowers', ['roses', 'tulips', 'dahlias', 'lupins', 'peonies', 'irises', 'poppies', 'asters', 'lilies', 'pansies', 'freesias', 'marigolds'],
     'the gardener who loves {v}', 'loves {v}', 'does not love {v}'),
    ('Shed colours', ['green', 'blue', 'red', 'white', 'black', 'yellow', 'brown', 'grey', 'purple', 'orange', 'pink', 'cream'],
     'the gardener with the {v} shed', 'has the {v} shed', 'does not have the {v} shed'),
    ('Tools', ['spade', 'hoe', 'rake', 'trowel', 'fork', 'shears', 'dibber', 'sieve', 'barrow', 'loppers', 'edger', 'sprayer'],
     'the gardener using the {v}', 'uses the {v}', 'does not use the {v}'),
    ('Lunches', ['soup', 'sandwiches', 'samosas', 'pasties', 'sushi', 'salad', 'quiche', 'wraps', 'pies', 'dumplings', 'bagels', 'falafel'],
     'the gardener who brought {v}', 'brought {v}', 'did not bring {v}'),
    ('Birds', ['robin', 'wren', 'blackbird', 'magpie', 'jay', 'thrush', 'starling', 'finch', 'sparrow', 'pigeon', 'kestrel', 'owl'],
     'the gardener who saw the {v}', 'saw the {v}', 'did not see the {v}'),
    ('Ages', ['34', '39', '45', '51', '57', '62', '69', '23', '28', '48', '66', '74'], 'the {v}-year-old', 'is {v} years old', 'is not {v} years old'),
])

T['office'] = dict(place='desk', people='colleagues',
                   intro="{C} colleagues sit at {n} desks in a row, numbered 1 to {N} from left to right.", cats=[
    ('Colleagues', ['Anja', 'Boris', 'Carla', 'Deniz', 'Elena', 'Felix', 'Grace', 'Hugo', 'Ishaan', 'Jamila', 'Kenji', 'Luz'], '{v}', 'is {v}', 'is not {v}'),
    ('Departments', ['sales', 'legal', 'design', 'finance', 'support', 'research', 'logistics', 'marketing', 'payroll', 'security', 'training', 'catering'],
     'the person in {v}', 'works in {v}', 'does not work in {v}'),
    ('Mug colours', ['red', 'blue', 'green', 'pink', 'black', 'white', 'purple', 'orange', 'yellow', 'grey', 'brown', 'gold'],
     'the owner of the {v} mug', 'has the {v} mug', 'does not have the {v} mug'),
    ('Languages', ['Dutch', 'Polish', 'Greek', 'Korean', 'Swahili', 'Turkish', 'Italian', 'Hindi', 'Welsh', 'Tagalog', 'Hebrew', 'Finnish'],
     'the {v} speaker', 'speaks {v}', 'does not speak {v}'),
    ('Desk plants', ['a fern', 'a cactus', 'an orchid', 'a bonsai', 'an ivy', 'a palm', 'a succulent', 'a lily', 'a yucca', 'a monstera', 'an aloe', 'a begonia'],
     'the person with {v}', 'keeps {v}', 'does not keep {v}'),
    ('Lunches', ['ramen', 'burritos', 'salad', 'curry', 'pizza', 'sushi', 'falafel', 'soup', 'noodles', 'kebab', 'risotto', 'tacos'],
     'the person who ordered {v}', 'ordered {v}', 'did not order {v}'),
    ('Commutes', ['bike', 'bus', 'train', 'tram', 'car', 'ferry', 'scooter', 'foot', 'taxi', 'motorbike', 'coach', 'skateboard'],
     'the person who commutes by {v}', 'commutes by {v}', 'does not commute by {v}'),
    ('Ages', ['22', '27', '33', '38', '43', '49', '52', '58', '25', '41', '61', '65'], 'the {v}-year-old', 'is {v} years old', 'is not {v} years old'),
])

T['market'] = dict(place='stall', people='vendors',
                   intro="{C} vendors run the {n} stalls of a night market, numbered 1 to {N} from left to right.", cats=[
    ('Vendors', ['Amir', 'Beatriz', 'Chen', 'Dalia', 'Emre', 'Fatou', 'Goran', 'Hiro', 'Imani', 'Jakub', 'Keoni', 'Lior'], '{v}', 'is {v}', 'is not {v}'),
    ('Dishes', ['satay', 'tacos', 'pierogi', 'bao', 'falafel', 'crepes', 'ramen', 'arepas', 'gyoza', 'empanadas', 'churros', 'laksa'],
     'the {v} seller', 'sells {v}', 'does not sell {v}'),
    ('Lantern colours', ['amber', 'jade', 'crimson', 'violet', 'teal', 'gold', 'rose', 'ivory', 'indigo', 'coral', 'silver', 'olive'],
     'the vendor with the {v} lantern', 'has the {v} lantern', 'does not have the {v} lantern'),
    ('Home towns', ['Lagos', 'Lyon', 'Quito', 'Hanoi', 'Perth', 'Oslo', 'Cusco', 'Riga', 'Accra', 'Leeds', 'Seoul', 'Turin'],
     'the vendor from {v}', 'is from {v}', 'is not from {v}'),
    ('Spices', ['cumin', 'saffron', 'paprika', 'sumac', 'clove', 'ginger', 'fennel', 'nutmeg', 'cardamom', 'turmeric', 'anise', 'mace'],
     'the vendor who loves {v}', 'loves {v}', 'does not love {v}'),
    ('Aprons', ['striped', 'checked', 'denim', 'linen', 'leather', 'canvas', 'floral', 'tartan', 'spotted', 'plain', 'quilted', 'paisley'],
     'the vendor in the {v} apron', 'wears the {v} apron', 'does not wear the {v} apron'),
    ('Music', ['jazz', 'opera', 'reggae', 'techno', 'folk', 'blues', 'salsa', 'punk', 'soul', 'grime', 'ska', 'disco'],
     'the {v} fan', 'listens to {v}', 'does not listen to {v}'),
    ('Ages', ['22', '27', '33', '38', '41', '46', '55', '63', '30', '50', '59', '67'], 'the {v}-year-old', 'is {v} years old', 'is not {v} years old'),
])

for _t in T.values():
    for _c in _t['cats']:
        assert len(_c[1]) == 12 and len(set(_c[1])) == 12, _c[0]
