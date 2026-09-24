"""Themes for reasoning.deduction-grid-extreme. Every entity is a person standing at a numbered position
(1 = leftmost). Each category lists 8 values and three phrase templates:
  S = how a clue names the person with that value (subject),
  P = predicate "has that value", N = negated predicate.
The prompt prints these templates as a legend, which the independent checker parses back."""

T = {}

T['lane'] = dict(place='house', intro="Eight neighbours live in the eight houses of Linden Lane, numbered 1 to 8 from left to right.", people='people', cats=[
    ('Names', ['Ada', 'Bram', 'Cleo', 'Dev', 'Esi', 'Finn', 'Gus', 'Hana'], '{v}', 'is {v}', 'is not {v}'),
    ('Pets', ['cat', 'dog', 'parrot', 'rabbit', 'tortoise', 'hamster', 'ferret', 'goldfish'], 'the {v} owner', 'owns the {v}', 'does not own the {v}'),
    ('Drinks', ['tea', 'coffee', 'cocoa', 'juice', 'milk', 'water', 'lemonade', 'kefir'], 'the {v} drinker', 'drinks {v}', 'does not drink {v}'),
    ('Car colours', ['red', 'blue', 'green', 'white', 'black', 'silver', 'orange', 'yellow'], 'the driver of the {v} car', 'drives the {v} car', 'does not drive the {v} car'),
    ('Hobbies', ['chess', 'pottery', 'surfing', 'archery', 'baking', 'climbing', 'knitting', 'fencing'], 'the person who does {v}', 'does {v}', 'does not do {v}'),
    ('Ages', ['24', '29', '31', '36', '42', '47', '53', '60'], 'the {v}-year-old', 'is {v} years old', 'is not {v} years old'),
])

T['market'] = dict(place='stall', intro="Eight vendors run the eight stalls of a night market, numbered 1 to 8 from left to right.", people='vendors', cats=[
    ('Vendors', ['Amir', 'Beatriz', 'Chen', 'Dalia', 'Emre', 'Fatou', 'Goran', 'Hiro'], '{v}', 'is {v}', 'is not {v}'),
    ('Dishes', ['satay', 'tacos', 'pierogi', 'bao', 'falafel', 'crepes', 'ramen', 'arepas'], 'the {v} seller', 'sells {v}', 'does not sell {v}'),
    ('Lantern colours', ['amber', 'jade', 'crimson', 'violet', 'teal', 'gold', 'rose', 'ivory'], 'the vendor with the {v} lantern', 'has the {v} lantern', 'does not have the {v} lantern'),
    ('Home towns', ['Lagos', 'Lyon', 'Quito', 'Hanoi', 'Perth', 'Oslo', 'Cusco', 'Riga'], 'the vendor from {v}', 'is from {v}', 'is not from {v}'),
    ('Spices', ['cumin', 'saffron', 'paprika', 'sumac', 'clove', 'ginger', 'fennel', 'nutmeg'], 'the vendor who loves {v}', 'loves {v}', 'does not love {v}'),
    ('Ages', ['22', '27', '33', '38', '41', '46', '55', '63'], 'the {v}-year-old', 'is {v} years old', 'is not {v} years old'),
])

T['hotel'] = dict(place='room', intro="Eight guests stay in the eight rooms along one hotel corridor, numbered 1 to 8 from left to right.", people='guests', cats=[
    ('Guests', ['Ilse', 'Jonas', 'Kemal', 'Lucia', 'Mato', 'Nell', 'Oren', 'Priya'], '{v}', 'is {v}', 'is not {v}'),
    ('Countries', ['Peru', 'Kenya', 'Norway', 'Japan', 'Chile', 'Egypt', 'Latvia', 'Canada'], 'the guest from {v}', 'is from {v}', 'is not from {v}'),
    ('Suitcase colours', ['plum', 'navy', 'mustard', 'coral', 'olive', 'grey', 'mint', 'tan'], 'the guest with the {v} suitcase', 'has the {v} suitcase', 'does not have the {v} suitcase'),
    ('Breakfasts', ['porridge', 'waffles', 'omelette', 'muesli', 'pancakes', 'congee', 'toast', 'yoghurt'], 'the guest who ordered {v}', 'ordered {v}', 'did not order {v}'),
    ('Books', ['a thriller', 'a memoir', 'an atlas', 'poetry', 'a comic', 'a biography', 'a cookbook', 'a fable'], 'the guest reading {v}', 'is reading {v}', 'is not reading {v}'),
])

T['office'] = dict(place='desk', intro="Eight colleagues sit at eight desks in a row, numbered 1 to 8 from left to right.", people='colleagues', cats=[
    ('Colleagues', ['Anja', 'Boris', 'Carla', 'Dmitri', 'Elena', 'Felix', 'Greta', 'Hugo'], '{v}', 'is {v}', 'is not {v}'),
    ('Departments', ['sales', 'legal', 'design', 'finance', 'support', 'research', 'logistics', 'marketing'], 'the person in {v}', 'works in {v}', 'does not work in {v}'),
    ('Mug colours', ['red', 'blue', 'green', 'pink', 'black', 'white', 'purple', 'orange'], 'the owner of the {v} mug', 'has the {v} mug', 'does not have the {v} mug'),
    ('Desk plants', ['a fern', 'a cactus', 'an orchid', 'a bonsai', 'an ivy', 'a palm', 'a succulent', 'a lily'], 'the person with {v}', 'keeps {v}', 'does not keep {v}'),
    ('Languages', ['Dutch', 'Polish', 'Greek', 'Korean', 'Swahili', 'Turkish', 'Italian', 'Hindi'], 'the {v} speaker', 'speaks {v}', 'does not speak {v}'),
    ('Ages', ['23', '28', '34', '37', '43', '49', '52', '58'], 'the {v}-year-old', 'is {v} years old', 'is not {v} years old'),
])

T['camp'] = dict(place='pitch', intro="Eight campers have pitched their tents on eight pitches in a row, numbered 1 to 8 from left to right.", people='campers', cats=[
    ('Campers', ['Rowan', 'Sabine', 'Tariq', 'Ulla', 'Viktor', 'Wendy', 'Xavi', 'Yara'], '{v}', 'is {v}', 'is not {v}'),
    ('Tent colours', ['orange', 'khaki', 'blue', 'red', 'lime', 'grey', 'purple', 'yellow'], 'the camper with the {v} tent', 'has the {v} tent', 'does not have the {v} tent'),
    ('Dinners', ['chili', 'risotto', 'curry', 'goulash', 'paella', 'stew', 'noodles', 'pasta'], 'the camper cooking {v}', 'is cooking {v}', 'is not cooking {v}'),
    ('Instruments', ['guitar', 'banjo', 'flute', 'ukulele', 'harmonica', 'fiddle', 'drum', 'accordion'], 'the {v} player', 'plays the {v}', 'does not play the {v}'),
    ('Home cities', ['Bern', 'Porto', 'Graz', 'Leeds', 'Turku', 'Split', 'Ghent', 'Brno'], 'the camper from {v}', 'is from {v}', 'is not from {v}'),
])

T['studio'] = dict(place='easel', intro="Eight painters work at eight easels in a row in an art studio, numbered 1 to 8 from left to right.", people='painters', cats=[
    ('Painters', ['Alba', 'Bruno', 'Cyrus', 'Dora', 'Enzo', 'Flora', 'Gideon', 'Hedda'], '{v}', 'is {v}', 'is not {v}'),
    ('Subjects', ['a harbour', 'a forest', 'a portrait', 'a bridge', 'a horse', 'a garden', 'a volcano', 'a market'], 'the painter of {v}', 'is painting {v}', 'is not painting {v}'),
    ('Apron colours', ['teal', 'ochre', 'scarlet', 'indigo', 'sage', 'cream', 'maroon', 'slate'], 'the painter in the {v} apron', 'wears the {v} apron', 'does not wear the {v} apron'),
    ('Music', ['jazz', 'opera', 'reggae', 'techno', 'folk', 'blues', 'salsa', 'punk'], 'the {v} fan', 'listens to {v}', 'does not listen to {v}'),
    ('Mediums', ['oil', 'acrylic', 'gouache', 'pastel', 'charcoal', 'ink', 'tempera', 'watercolour'], 'the painter using {v}', 'uses {v}', 'does not use {v}'),
    ('Ages', ['19', '25', '30', '35', '44', '51', '57', '66'], 'the {v}-year-old', 'is {v} years old', 'is not {v} years old'),
])

T['bowling'] = dict(place='lane', intro="Eight bowlers play on eight lanes of a bowling alley, numbered 1 to 8 from left to right.", people='bowlers', cats=[
    ('Bowlers', ['Ansel', 'Bianca', 'Corin', 'Delia', 'Evander', 'Freya', 'Gale', 'Hector'], '{v}', 'is {v}', 'is not {v}'),
    ('Shirt colours', ['lilac', 'teal', 'orange', 'navy', 'lemon', 'brown', 'cherry', 'silver'], 'the bowler in the {v} shirt', 'wears the {v} shirt', 'does not wear the {v} shirt'),
    ('Snacks', ['nachos', 'pretzels', 'popcorn', 'wings', 'fries', 'onion rings', 'hot dogs', 'mozzarella sticks'], 'the bowler eating {v}', 'is eating {v}', 'is not eating {v}'),
    ('Teams', ['Comets', 'Otters', 'Falcons', 'Pumas', 'Vipers', 'Herons', 'Lynxes', 'Wolves'], 'the {v} player', 'plays for the {v}', 'does not play for the {v}'),
    ('Professions', ['nurse', 'pilot', 'baker', 'chemist', 'lawyer', 'plumber', 'teacher', 'tailor'], 'the {v}', 'is the {v}', 'is not the {v}'),
    ('Ages', ['21', '26', '32', '39', '45', '48', '56', '61'], 'the {v}-year-old', 'is {v} years old', 'is not {v} years old'),
])

T['theatre'] = dict(place='seat', intro="Eight friends sit in eight seats of one theatre row, numbered 1 to 8 from left to right.", people='friends', cats=[
    ('Friends', ['Ingo', 'Jolene', 'Kasimir', 'Leona', 'Mirek', 'Nadia', 'Osric', 'Pilar'], '{v}', 'is {v}', 'is not {v}'),
    ('Snacks', ['toffee', 'grapes', 'crisps', 'almonds', 'fudge', 'olives', 'mints', 'dates'], 'the friend eating {v}', 'is eating {v}', 'is not eating {v}'),
    ('Coat colours', ['camel', 'black', 'green', 'red', 'blue', 'white', 'grey', 'yellow'], 'the friend in the {v} coat', 'wears the {v} coat', 'does not wear the {v} coat'),
    ('Jobs', ['architect', 'farmer', 'surgeon', 'editor', 'florist', 'dentist', 'jeweller', 'librarian'], 'the {v}', 'is the {v}', 'is not the {v}'),
    ('Home towns', ['Arles', 'Bergen', 'Cadiz', 'Dundee', 'Essen', 'Faro', 'Gdansk', 'Hull'], 'the friend from {v}', 'is from {v}', 'is not from {v}'),
])

T['canal'] = dict(place='mooring', intro="Eight narrowboats are tied up at eight moorings along a canal, numbered 1 to 8 from left to right, each with its owner aboard.", people='owners', cats=[
    ('Owners', ['Agnes', 'Basil', 'Cora', 'Desmond', 'Edie', 'Fergus', 'Gwen', 'Horace'], '{v}', 'is {v}', 'is not {v}'),
    ('Boat names', ['Kestrel', 'Bramble', 'Juniper', 'Otterly', 'Moonbeam', 'Pennywhistle', 'Sorrel', 'Wanderer'], 'the owner of the Kestrel'.replace('Kestrel', '{v}'), 'owns the {v}', 'does not own the {v}'),
    ('Hull colours', ['green', 'blue', 'red', 'black', 'maroon', 'cream', 'yellow', 'purple'], 'the owner of the {v} boat', 'owns the {v} boat', 'does not own the {v} boat'),
    ('Dogs', ['terrier', 'collie', 'spaniel', 'lurcher', 'poodle', 'dachshund', 'whippet', 'beagle'], 'the {v} owner', 'has the {v}', 'does not have the {v}'),
    ('Cargoes', ['coal', 'timber', 'cider', 'bricks', 'grain', 'pottery', 'wool', 'books'], 'the owner carrying {v}', 'carries {v}', 'does not carry {v}'),
    ('Ages', ['28', '33', '39', '44', '50', '57', '62', '71'], 'the {v}-year-old', 'is {v} years old', 'is not {v} years old'),
])

T['gallery'] = dict(place='plinth', intro="Eight sculptors each show one sculpture on eight plinths in a row, numbered 1 to 8 from left to right, and each stands beside their own work.", people='sculptors', cats=[
    ('Sculptors', ['Arvo', 'Birte', 'Casper', 'Dagmar', 'Emeka', 'Fiona', 'Gunnar', 'Halina'], '{v}', 'is {v}', 'is not {v}'),
    ('Materials', ['bronze', 'marble', 'oak', 'glass', 'steel', 'clay', 'granite', 'resin'], 'the sculptor working in {v}', 'works in {v}', 'does not work in {v}'),
    ('Animals', ['a heron', 'a bear', 'a fox', 'a whale', 'an owl', 'a stag', 'a hare', 'a lynx'], 'the sculptor of {v}', 'sculpted {v}', 'did not sculpt {v}'),
    ('Scarf colours', ['saffron', 'cobalt', 'emerald', 'ruby', 'pearl', 'onyx', 'copper', 'lilac'], 'the sculptor in the {v} scarf', 'wears the {v} scarf', 'does not wear the {v} scarf'),
    ('Home cities', ['Aarhus', 'Bilbao', 'Cork', 'Dijon', 'Erfurt', 'Florence', 'Gothenburg', 'Heidelberg'], 'the sculptor from {v}', 'is from {v}', 'is not from {v}'),
    ('Ages', ['27', '31', '38', '42', '49', '54', '59', '68'], 'the {v}-year-old', 'is {v} years old', 'is not {v} years old'),
])
