import json, sys
sys.path.insert(0, '..')

OUT = "Output only the requested text: no title, preamble, explanation, notes or closing remarks, and no code fences."
COUNTING = ("How the checker counts: a word is any run of characters between spaces or line breaks that contains at least one letter or digit "
            "(so '-' alone is not a word, but '1.' is); a sentence ends with '.', '!' or '?' followed by a space, a line break or the end of the text; "
            "a line is any non-empty line; a paragraph is a block of text separated from the next by a blank line. "
            "Every range written as 'between A and B' includes both A and B. "
            "When a rule says a word or letter sequence must (or must not) appear, the checker counts every occurrence of that exact sequence of characters "
            "in the raw text, in ANY capitalisation unless the rule says otherwise, including occurrences inside longer words "
            "(for example 'Orbiting' contains 'orbit' and 'every' contains 'very').")

C = []
def case(cid, d, prompt, constraints, passing, failing, why):
    C.append(dict(id=cid, d=d, prompt=prompt.strip() + "\n\n" + COUNTING + "\n\n" + OUT, expected=constraints,
                  samples=dict(pass_=passing, fail=failing), why=why))

def gate(anchor, negs=(), tail='', flags='i'):
    """One regex check: the task-specific anchor must be present AND none of the forbidden patterns may occur.
    Negative rules are bundled behind a positive anchor so that random filler cannot score on them."""
    pat = '^(?=[\\s\\S]*' + anchor + ')' + ''.join('(?![\\s\\S]*' + n + ')' for n in negs) + ('[\\s\\S]*' + tail if tail else '')
    return {'check': 'regex', 'pattern': pat, 'flags': flags}

case('f01', 'easy', """
Write a three-line poem about rain.
Rules:
1. Exactly 3 lines, with no blank lines between them.
2. Use only lowercase letters: no capital letters anywhere.
3. Do not use any commas.
4. The letter sequence "umbrella" must appear exactly once.
5. The letter sequence "rain" must appear at least once.
6. The whole poem must be between 9 and 24 words long.
""", [
    {'check': 'line_count', 'min': 3, 'max': 3},
    {'check': 'include', 'text': 'umbrella', 'min': 1, 'max': 1},
    {'check': 'include', 'text': 'rain', 'min': 1},
    {'check': 'word_count', 'min': 9, 'max': 24},
    gate('rain', ['[,\\uFF0C]', '[\\p{Lu}\\p{Lt}]'], flags='u'),
], ["soft rain taps the tin roof\nmy red umbrella waits beside the door\nthe street drinks the grey sky slowly"],
   ["Soft rain, soft rain\nmy umbrella and your umbrella\nthe end"], "Warm-up: lowercase + no commas + exact occurrence count. The lowercase and comma rules are checked together with the required word 'rain' (one gated check) so filler text cannot score on them.")

case('f02', 'easy', """
Give five practical tips for saving money on groceries.
Rules:
1. Write exactly 5 lines, and every line must be a bullet point that starts with a hyphen followed by a space ("- ").
2. Nothing else: no heading, no introduction and no closing line.
3. The letter sequence "budget" must appear exactly twice in the whole answer.
4. The letter sequence "grocer" must appear at least once (for example in "grocery" or "groceries").
5. The whole answer must be between 24 and 39 words long.
""", [
    {'check': 'bullet_count', 'min': 5, 'max': 5},
    {'check': 'line_count', 'min': 5, 'max': 5},
    {'check': 'each_line_starts_with', 'text': '- '},
    {'check': 'include', 'text': 'budget', 'min': 2, 'max': 2},
    {'check': 'include', 'text': 'grocer', 'min': 1},
    {'check': 'word_count', 'min': 24, 'max': 39},
], ["- Plan a weekly grocery budget on Sunday.\n- Buy store brands instead of big names.\n- Freeze leftover bread and fruit.\n- Check your budget after every payday.\n- Shop with a written list."],
   ["Here are my tips:\n- Budget.\n- Budget more.\n- Plan meals.\n- Buy in bulk.\n- Compare prices."], "Exact bullet structure, substring counts, word range.")

case('f03', 'hard', """
Describe a morning at a fish market.
Rules:
1. Never use the letter "e" (neither "e" nor "E") anywhere in your answer.
2. Write exactly 4 sentences, each ending with a period.
3. The letter sequence "fish" must appear at least 2 times.
4. The whole answer must be between 28 and 39 words long.
5. The answer must end with a period.
""", [
    gate('fish', ['e'], tail='\\.\\s*$'),
    {'check': 'sentence_count', 'min': 4, 'max': 4},
    {'check': 'include', 'text': 'fish', 'min': 2},
    {'check': 'word_count', 'min': 28, 'max': 39},
], ["At dawn a crowd walks along a row of fish stalls. Fishing folk shout loud calls and hold up shiny cod. A small boy runs past with a pail of crabs. Gulls wait high up for scraps of fish."],
   ["The market opens at dawn. Traders shout. Gulls circle overhead while buyers haggle over fresh fish."], "Lipogram (no letter e) is hard for tokenised models; combined with exact sentence count. The no-'e' and final-period rules form one check gated on the required word 'fish'.")

case('f04', 'medium', """
Write an 8-line poem about a sports arena.
Rules:
1. Exactly 8 lines, with no blank lines.
2. The first letters of the lines, read from top to bottom, must spell GAUNTLET (line 1 starts with G, line 2 with A, and so on). Start each line directly with its word: no numbers, bullets or symbols in front.
3. Do not use any commas.
4. The letter sequences "arena", "crowd" and "whistle" must each appear at least once.
5. The whole poem must be between 24 and 39 words long.
""", [
    {'check': 'acrostic', 'word': 'GAUNTLET'},
    {'check': 'line_count', 'min': 8, 'max': 8},
    {'check': 'include', 'text': 'arena', 'min': 1},
    {'check': 'include', 'text': 'crowd', 'min': 1},
    {'check': 'include', 'text': 'whistle', 'min': 1},
    {'check': 'word_count', 'min': 24, 'max': 39},
    gate('arena', ['[,\\uFF0C]']),
], ["Gates swing open wide\nAll the crowd pours in\nUnder bright arena lights\nNervous fans lean forward\nThe whistle breaks the hush\nLate goals decide it\nEvery tackle draws a roar\nThousands sing on"],
   ["Gates open,\nAll cheer\nUnder lights\nNow\nThe game\nEnds\nLate\nTonight"], "Acrostic + line count + comma ban (gated on 'arena') + required words + word range.")

case('f05', 'medium', """
Invent a fictional film from the 1990s and describe it as a JSON object.
Rules:
1. Output only one JSON object (no code fences, no text before or after it).
2. It must have exactly these four keys: "title" (a string), "year" (an integer from 1990 to 1999, not in quotes), "tags" (an array of exactly three strings, each a single lowercase word made only of letters a-z), and "rating" (a number from 0.0 to 10.0 written with exactly one digit after the decimal point, not in quotes, e.g. 7.5).
3. Do not include any other keys.
""", [
    {'check': 'json_keys', 'keys': ['title', 'year', 'tags', 'rating']},
    {'check': 'regex', 'pattern': '"year"\\s*:\\s*199\\d\\s*[,}\\n]'},
    {'check': 'regex', 'pattern': '"tags"\\s*:\\s*\\[\\s*"[a-z]+"\\s*,\\s*"[a-z]+"\\s*,\\s*"[a-z]+"\\s*\\]'},
    {'check': 'regex', 'pattern': '"rating"\\s*:\\s*(10\\.0|\\d\\.\\d)\\s*[,}\\n]'},
    {'check': 'regex', 'pattern': '^\\s*\\{(?=[\\s\\S]*"title")[\\s\\S]*\\}\\s*$'},
], ['{"title": "The Lantern Keeper", "year": 1996, "tags": ["drama", "coastal", "family"], "rating": 7.8}'],
   ['```json\n{"title": "X", "year": "1996", "tags": ["Drama", "sea"], "rating": 8}\n```'], "Strict JSON shape via json_keys + regexes (number types, array length, decimal format). The 'nothing but one object' check is gated on the \"title\" key so an empty object scores nothing.")

case('f06', 'medium', """
Write a short piece about a lost umbrella.
Rules:
1. Exactly 3 paragraphs, separated by a single blank line.
2. The very first word of your answer must be "Yesterday" (with a capital Y).
3. The letter sequence "however" must appear exactly once in the whole answer (any capitalisation, so "However" counts too).
4. The letter sequence "umbrella" must appear at least 2 times.
5. The whole answer must be between 90 and 130 words long.
6. The answer must end with a question mark.
""", [
    {'check': 'paragraph_count', 'min': 3, 'max': 3},
    {'check': 'starts_with', 'text': 'Yesterday', 'caseSensitive': True},
    {'check': 'include', 'text': 'however', 'min': 1, 'max': 1},
    {'check': 'include', 'text': 'umbrella', 'min': 2},
    {'check': 'word_count', 'min': 90, 'max': 130},
    {'check': 'ends_with', 'text': '?'},
], ["Yesterday I left my green umbrella on the number twelve bus. It had followed me through three winters and one very windy wedding, and I only noticed it was gone when the first drops hit my collar.\n\nI called the depot the next morning. A patient woman searched the lost property shelf while I listened to the hum of the office radio. She found gloves, a trombone case and forty single socks. There was, however, no green umbrella.\n\nNow I walk to work under a borrowed black one that refuses to close properly. Somewhere a stranger is staying dry under my old friend. Is it wrong to hope the handle squeaks for them too?"],
   ["Yesterday I lost my umbrella. However, I found it. However, it broke.\n\nThe end."], "Paragraph structure + exact start + exact occurrence + end punctuation.")

case('f07', 'hard', """
Write a six-line description of a satellite circling the Earth.
Rules:
1. Exactly 6 lines, with no blank lines.
2. Every line must begin with a greater-than sign followed by a space ("> ").
3. The letter sequence "orbit" must appear at least 2 times (any capitalisation; occurrences inside longer words such as "orbiting" or "Orbital" also count).
4. The letter sequence "satellite" must appear at least once.
5. The letter sequence "star" must not appear anywhere, not even inside other words (so "start", "stare" and "starlight" are forbidden too), in any capitalisation.
6. Do not use any commas.
7. The whole answer must be between 24 and 39 words long.
""", [
    {'check': 'line_count', 'min': 6, 'max': 6},
    {'check': 'each_line_starts_with', 'text': '> '},
    {'check': 'include', 'text': 'orbit', 'min': 2},
    {'check': 'include', 'text': 'satellite', 'min': 1},
    gate('orbit', ['star', '[,\\uFF0C]']),
    {'check': 'word_count', 'min': 24, 'max': 39},
], ["> A small satellite hums above the clouds\n> It keeps a steady orbit\n> Its wings catch the sunrise\n> Oceans turn from black to blue\n> Its orbit crosses the map again\n> It sends home pictures"],
   ["> The satellite starts its orbit, slowly.\n> It is a star in the sky.\n> Orbit."], "Per-line prefix + forbidden substring trap ('start') + counts. The 'star' ban and comma ban are one check gated on the required 'orbit'.")

case('f08', 'hard', """
Explain how plants make their food from sunlight, for a six-year-old child.
Rules:
1. No word may be longer than 6 letters. (Punctuation such as commas and periods is not counted; apostrophes and hyphens ARE counted, so "plant's" has 7 characters and is too long.)
2. Write between 4 and 6 sentences.
3. The whole answer must be between 28 and 39 words long.
4. The letter sequences "sun", "leaf" and "water" must each appear at least once.
""", [
    {'check': 'max_word_length', 'max': 6},
    {'check': 'sentence_count', 'min': 4, 'max': 6},
    {'check': 'word_count', 'min': 28, 'max': 39},
    {'check': 'include', 'text': 'sun', 'min': 1},
    {'check': 'include', 'text': 'leaf', 'min': 1},
    {'check': 'include', 'text': 'water', 'min': 1},
], ["Plants are like tiny cooks. Roots drink water from the soil. Each green leaf soaks up light from the sun. The plant mixes light and water and air to make food. That food helps it grow."],
   ["Photosynthesis is the process by which plants convert sunlight into chemical energy. It happens in chloroplasts. The sun is important."], "Vocabulary restriction under a word-length limit is hard to self-monitor; required topic words.")

case('f09', 'hard', """
Write a telegram announcing that a traveller will arrive late.
Rules:
1. Write everything in CAPITAL LETTERS: no lowercase letters anywhere.
2. Exactly 4 lines, with no blank lines.
3. Every line must end with the word STOP (nothing may follow STOP on a line, not even punctuation).
4. Do not use any digits (write numbers as words).
5. The word "ARRIVE" must appear at least once, in capital letters (it also counts inside a longer word such as "ARRIVES").
6. The whole telegram must be between 16 and 32 words long (each STOP counts as a word).
""", [
    {'check': 'all_uppercase'},
    {'check': 'line_count', 'min': 4, 'max': 4},
    {'check': 'regex', 'pattern': '^(?!.*STOP\\s*$).*\\S.*$', 'flags': 'm', 'shouldMatch': False},
    gate('STOP', ['\\d'], flags=''),
    {'check': 'include', 'text': 'ARRIVE', 'caseSensitive': True, 'min': 1},
    {'check': 'word_count', 'min': 16, 'max': 32},
], ["TRAIN DELAYED BY SNOW NEAR THE BORDER STOP\nWILL ARRIVE TOMORROW AT NOON STOP\nPLEASE TELL AUNT MARGIT STOP\nKEEP THE SOUP WARM STOP"],
   ["Train delayed STOP\nWILL ARRIVE AT 12 STOP.\nLOVE"], "All-caps + per-line suffix (checked with a multiline negative regex) + digit ban (gated on STOP).")

case('f10', 'hard', """
Invent titles for seven fictional mystery novels.
Rules:
1. Write exactly 7 lines, numbered "1. " to "7. " in order (the number, a period, a space, then the title), with no blank lines and nothing else.
2. Every word in every title must start with a capital letter, including short words such as "of", "the", "and", "in" and "a". The check looks at the first letter of each space-separated token: tokens without letters (such as the "1." labels) are ignored, a hyphenated word only needs its first letter capitalised (e.g. "Well-kept"), and a token that starts with digits must still have a capital as its first letter (so avoid tokens like "2nd").
3. Do not use any commas.
4. Including the numbers (each "1." etc. counts as one word), the whole list must be between 21 and 39 words long.
""", [
    {'check': 'bullet_count', 'min': 7, 'max': 7},
    {'check': 'line_count', 'min': 7, 'max': 7},
    {'check': 'title_case_lines'},
    {'check': 'regex', 'pattern': '^\\s*1\\. \\S[^\\n]*\\n2\\. \\S[^\\n]*\\n3\\. \\S[^\\n]*\\n4\\. \\S[^\\n]*\\n5\\. \\S[^\\n]*\\n6\\. \\S[^\\n]*\\n7\\. \\S[^\\n]*\\s*$'},
    gate('^\\s*1\\. ', ['[,\\uFF0C]'], flags=''),
    {'check': 'word_count', 'min': 21, 'max': 39},
], ["1. The Silent Marsh\n2. A Key Below\n3. Murder At Dawn\n4. The Crow Knows\n5. Seven Letters\n6. Death In Blue\n7. The Last Train"],
   ["1. The Silence of the Marsh\n2. A Key, Beneath\n3. Murder\n4. Crow\n5. Letters\n6. Death\n8. Train"], "Title case including small words (models habitually lowercase 'of'/'the') + strict numbering; comma ban gated on the numbering.")

case('f11', 'extreme', """
Write a five-line poem about a cloud.
Rules:
1. Exactly 5 lines, with no blank lines.
2. The first letters of the lines, read from top to bottom, must spell CLOUD. Start each line directly with its word (no numbers, bullets or symbols).
3. Use only lowercase letters: no capital letters anywhere (the acrostic is checked case-insensitively).
4. Never use the letter "e" anywhere.
5. Do not use any commas.
6. The letter sequence "sky" must appear at least once.
7. The whole poem must be between 20 and 39 words long.
""", [
    {'check': 'acrostic', 'word': 'CLOUD'},
    {'check': 'line_count', 'min': 5, 'max': 5},
    {'check': 'include', 'text': 'sky', 'min': 1},
    {'check': 'word_count', 'min': 20, 'max': 39},
    gate('sky', ['[eE]', '[,\\uFF0C]', '[\\p{Lu}\\p{Lt}]'], flags='u'),
], ["calm sky brings a soft gray mist\nlow drifts roll by\nour kids point at its forms\nuntil a warm wind blows\ndays turn bright"],
   ["Clouds are here\nLovely\nOver\nUp\nDrifting, gently"], "Acrostic + lipogram + lowercase at once; the three bans are one check gated on the required word 'sky'.")

case('f12', 'extreme', """
Write a very short story about a lighthouse keeper.
Rules:
1. It must be exactly 50 words long.
2. It must have exactly 5 sentences.
3. The first word must be "Once" (capital O).
4. The story must end with the word "again" immediately followed by a period, i.e. the final characters are "again.".
5. The letter sequence "lantern" must appear exactly twice (any capitalisation; occurrences inside longer words such as "lanterns" also count).
6. Do not use any commas.
""", [
    {'check': 'word_count', 'min': 50, 'max': 50},
    {'check': 'sentence_count', 'min': 5, 'max': 5},
    {'check': 'starts_with', 'text': 'Once', 'caseSensitive': True},
    {'check': 'ends_with', 'text': 'again.', 'caseSensitive': True},
    {'check': 'include', 'text': 'lantern', 'min': 2, 'max': 2},
    gate('lantern', ['[,\\uFF0C]']),
], ["Once a year the keeper climbed the tower to polish the lantern. Storms had cracked the glass twice before. Ships still trusted the light that swept across the black water. One night the lantern failed and she carried a candle up the stairs. By dawn the bay was safe again."],
   ["Once upon a time, a keeper lit a lantern. The end."], "Exact word count and exact sentence count simultaneously, plus start/end anchors; comma ban gated on 'lantern'.")

case('f13', 'hard', """
Invent four fictional cities and describe them as JSON.
Rules:
1. Output only one JSON object (no code fences, no text before or after it).
2. The object must have exactly three keys: "cities", "count" and "source".
3. "cities" is an array of exactly 4 objects. Each object has exactly two keys: "name" (a string) and "population" (a positive integer, not in quotes).
4. "count" is the integer 4 (not in quotes).
5. "source" is null (the JSON value null, not a string).
6. This rule is checked on the raw text: the quoted string "name" (including its double quotes) must occur exactly 4 times and the quoted string "population" (including its double quotes) exactly 4 times. So do not use the words name or population, in double quotes, anywhere else (for example as a value).
""", [
    {'check': 'json_keys', 'keys': ['cities', 'count', 'source']},
    {'check': 'include', 'text': '"name"', 'min': 4, 'max': 4},
    {'check': 'include', 'text': '"population"', 'min': 4, 'max': 4},
    {'check': 'regex', 'pattern': '"population"\\s*:\\s*[1-9]\\d*\\s*[,}\\n]'},
    {'check': 'regex', 'pattern': '"count"\\s*:\\s*4\\s*[,}\\n]'},
    {'check': 'regex', 'pattern': '"source"\\s*:\\s*null\\s*[,}\\n]'},
    {'check': 'regex', 'pattern': '^\\s*\\{(?=[\\s\\S]*"cities")[\\s\\S]*\\}\\s*$'},
], ['{"cities": [{"name": "Varrowmere", "population": 48210}, {"name": "Quellstone", "population": 1203}, {"name": "Ostrava Nuova", "population": 350000}, {"name": "Lindqvist Harbour", "population": 7755}], "count": 4, "source": null}'],
   ['Here you go:\n{"cities": [{"name": "A", "population": "100"}], "count": "4", "source": "none"}'], "Nested JSON shape verified with json_keys plus raw-text occurrence counts and type regexes; the one-object check is gated on the \"cities\" key.")

case('f14', 'medium', """
Write a short dialogue between two friends, ANA and BEN, planning a picnic.
Rules:
1. Exactly 6 lines, with no blank lines and nothing before or after the dialogue.
2. The lines alternate speakers, starting with ANA: lines 1, 3 and 5 begin with "ANA: " and lines 2, 4 and 6 begin with "BEN: " (the name in capitals, a colon, then a space).
3. Do not use any question marks.
4. The letter sequence "tomorrow" must appear exactly once (any capitalisation).
5. The letter sequence "picnic" must appear at least once.
6. The whole dialogue, including the speaker labels, must be between 24 and 39 words long.
""", [
    {'check': 'line_count', 'min': 6, 'max': 6},
    {'check': 'regex', 'pattern': '^\\s*ANA: [^\\n]+\\nBEN: [^\\n]+\\nANA: [^\\n]+\\nBEN: [^\\n]+\\nANA: [^\\n]+\\nBEN: [^\\n]+\\s*$'},
    gate('ANA: ', ['\\?', '\\uFF1F'], flags=''),
    {'check': 'include', 'text': 'tomorrow', 'min': 1, 'max': 1},
    {'check': 'include', 'text': 'picnic', 'min': 1},
    {'check': 'word_count', 'min': 24, 'max': 39},
], ["ANA: A picnic tomorrow sounds lovely.\nBEN: I will bring the blanket.\nANA: I can bake lemon bars.\nBEN: Great. Juice is on me.\nANA: Meet at the fountain at noon.\nBEN: Perfect. See you there."],
   ["ANA: Picnic tomorrow?\nBEN: Sure, tomorrow works.\nANA: Great."], "Strict alternating speaker structure checked by one anchored regex; question-mark ban (gated on the ANA label).")

case('f15', 'extreme', """
Write a product description for a fictional electric kettle.
Rules:
1. Exactly 2 paragraphs, separated by a single blank line.
2. Exactly 5 sentences in total.
3. The whole description must be between 85 and 110 words long.
4. The letter sequence "kettle" must appear at least 3 times (any capitalisation; occurrences inside longer words such as "kettles" also count).
5. Never use the letter "z" (neither "z" nor "Z").
6. Never use the word "very" (the letter sequence "very" must not appear anywhere, so words such as "every" and "delivery" are forbidden too).
7. The description must end with exactly this sentence: Boil smarter.
""", [
    {'check': 'paragraph_count', 'min': 2, 'max': 2},
    {'check': 'sentence_count', 'min': 5, 'max': 5},
    {'check': 'word_count', 'min': 85, 'max': 110},
    {'check': 'include', 'text': 'kettle', 'min': 3},
    gate('kettle', ['z', 'very']),
    {'check': 'ends_with', 'text': 'Boil smarter.', 'caseSensitive': True},
], ["Meet the Tidewater One, a quiet electric kettle that brings a full litre of water to a rolling boil in under three minutes on a standard household socket. Its brushed steel body stays cool to the touch while the water inside it heats, so little hands and busy cooks can grab it safely.\n\nChoose from five preset temperatures for green tea, black tea, coffee or baby formula with a single tap on the glowing panel. This kettle remembers your last setting and switches itself off the moment the kettle runs dry, which saves both power and worry. Boil smarter."],
   ["The Zenith kettle is very fast. Every morning it boils. Boil smarter."], "Many interacting constraints incl. hidden-substring bans ('every' contains 'very'), bundled into one check gated on 'kettle'.")


json.dump([dict(id=c['id'], expected=c['expected'], samples={'pass': c['samples']['pass_'], 'fail': c['samples']['fail']}) for c in C], open('precision_samples.json', 'w'), indent=1)
json.dump(C, open('precision_cases.json', 'w'), indent=1, ensure_ascii=False)
print(len(C), 'cases')
