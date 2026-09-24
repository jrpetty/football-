"""Builds tests/instruction/extreme-constraints.json plus extreme_cases.json (constraints + passing
sample + near-miss sample per case) for check.mts, which proves every sample against the real
checker in src/scoring/constraints.ts.
Usage (from verification/frontier-reasoning): python3 build_extreme_constraints.py [gauntlet-root, default ../..] [cases dir, default .]
then: node check.mts extreme_cases.json"""
import json
import sys

ALL = ("All of these rules are checked automatically and must hold at the same time: breaking any single rule scores zero for this task.")

COUNTING = (
    "How the checker counts (these definitions are exact):\n"
    "- A word is any run of characters between spaces or line breaks that contains at least one letter or digit (so '-' or '~' on its own is not a word, but 'A:', '1.' and 'x2' are words).\n"
    "- A sentence ends at '.', '!' or '?' when it is followed by a space, a line break or the end of the text (a closing quote or bracket may come in between). A line break on its own does not end a sentence.\n"
    "- A line is any line that is not empty; spaces at the start or end of a line are ignored.\n"
    "- A paragraph is a block of text separated from the next block by a blank line.\n"
    "- A bullet point is a line that starts with '-', '*', '\u2022' or a number followed by '.' or ')', and then a space.\n"
    "- When a rule says a letter sequence must appear exactly n times, or must not appear, every occurrence counts, in any capitalisation unless the rule says otherwise, including occurrences inside longer words.\n"
    "- 'Never use the letter x' means that neither the lower-case nor the capital form of that letter may appear anywhere in your answer."
)
WORDLEN = "- A word's length counts its letters, digits, apostrophes and hyphens; other punctuation attached to it (such as a final period or a colon) is not counted."
ACRO = "- For the acrostic, the checker takes the first letter of each line, skipping any characters before it that are not letters."
TITLE = "- A word starts with a capital letter when its first letter (its first alphabetic character) is a capital letter; a token such as '>>' has no letters and is not a word."
OUT = "Output only the requested text: no title, preamble, explanation, notes or closing remarks, and no code fences."

PL = r'(?:1-[2-8]|2-[3-8]|3-[4-8]|4-[5-8]|5-[6-8]|6-[78]|7-8)'
MINS = r'(?:15|30|45|60|75|90|105|120)'


def x12_obj(letter, last):
    return (r'[ \t]*\{ *"name" *: *"' + letter + r'[a-z]+ ' + letter + r'[a-z]+" *, *"players" *: *"' + PL
            + r'" *, *"minutes" *: *' + MINS + r' *\}' + ('' if last else ' *,') + r'[ \t]*\n')


X12_SHAPE = r'^\s*\[[ \t]*\n' + ''.join(x12_obj(L, L == 'E') for L in 'BRAVE') + r'[ \t]*\][ \t]*\s*$'


C = []


def case(cid, diff, task, rules, extra_defs, constraints, passing, failing, why):
    rules_txt = "\n".join(f"{i}. {r}" for i, r in enumerate(rules, 1))
    defs = COUNTING + ("\n" + "\n".join(extra_defs) if extra_defs else "")
    prompt = f"{task.strip()}\n\nRules:\n{rules_txt}\n\n{ALL}\n\n{defs}\n\n{OUT}"
    C.append(dict(id=cid, diff=diff, prompt=prompt, expected=constraints, samples=dict(pass_=[passing], fail=[failing]), why=why))


# ---------------------------------------------------------------- x01 lipogram storm
case("x01", "hard", "Describe a thunderstorm rolling over a harbour town at night.", [
    'Never use the letter "e".',
    "Write exactly 5 sentences.",
    "Write exactly 60 words.",
    'The letter sequence "storm" must appear exactly 2 times (words such as "storms" or "thunderstorm" also count).',
    "Do not use any commas.",
    'The first word must be "At", with a capital A.',
    'The text must end with the word "dawn" followed by a period, i.e. its last five characters are "dawn.".',
    "No word may be longer than 9 characters.",
], [WORDLEN], [
    {"check": "no_letter", "letter": "e"},
    {"check": "sentence_count", "min": 5, "max": 5},
    {"check": "word_count", "min": 60, "max": 60},
    {"check": "include", "text": "storm", "min": 2, "max": 2},
    {"check": "no_commas"},
    {"check": "starts_with", "text": "At ", "caseSensitive": True},
    {"check": "ends_with", "text": "dawn.", "caseSensitive": True},
    {"check": "max_word_length", "max": 9},
],
    "At midnight a wild storm rolls in from a black and angry sky. Rain pounds on roofs and small boats rock hard at anchor. Lightning cracks and a loud boom jolts all windows along a quay. Fishing folk stay indoors by warm lamps as this storm howls on. It fails at last and a calm sky turns pink at dawn.",
    "At midnight a wild storm rolls in from a black and angry sky. Rain pounds on roofs and small boats rock hard at anchor. Lightning cracks and a loud boom jolts all windows along a quay. Fishing folk stay indoors by warm lamps as this storm howls on. It fails at last and a calm sky turns pink and gold at dawn.",
    "Lipogram (no e) with exact word AND sentence counts, an exact substring count, fixed first word and last word, and a word-length cap that rules out 'thunderstorm'. The near miss is 62 words (every other rule holds).")

# ---------------------------------------------------------------- x02 acrostic LABYRINTHS
case("x02", "extreme", "Write a 10-line poem about getting lost in a hedge maze.", [
    "Write exactly 10 lines, with no blank lines between them.",
    "The first letters of the lines, read from top to bottom, must spell LABYRINTHS (line 1 starts with l, line 2 with a, and so on).",
    "Every line must consist of exactly 5 words made only of the lower-case letters a to z, separated by single spaces: no capital letters, digits, punctuation marks or other symbols anywhere.",
    'Never use the letter "e".',
    'The letter sequence "path" must appear exactly 2 times.',
    'The letter sequence "you" must appear exactly 4 times (inside words such as "your" it also counts).',
], [ACRO], [
    {"check": "acrostic", "word": "LABYRINTHS"},
    {"check": "line_count", "min": 10, "max": 10},
    {"check": "word_count", "min": 50, "max": 50},
    {"check": "regex", "pattern": "^\\s*(?:[a-z]+(?: [a-z]+){4}[ \\t]*\\n[ \\t]*){9}[a-z]+(?: [a-z]+){4}\\s*$"},
    {"check": "all_lowercase"},
    {"check": "no_letter", "letter": "e"},
    {"check": "include", "text": "path", "min": 2, "max": 2},
    {"check": "include", "text": "you", "min": 4, "max": 4},
],
    "lost among tall dark shrubs\na narrow path turns again\nbut no way out shows\nyou walk for hours now\nround and round you go\nin cold dim moonlight still\nno map can aid you\ntry that path at last\nhark a bird calls out\nsunlight finds you at dawn",
    "lost among tall dark shrubs\na narrow path turns again\nbut no way out shows\nyou walk for hours now\nround and round you go\nin cold dim moonlight still\nno map can aid you\ntry that path at last\nhark a bird calls out\nsunlight finds you at dawn.",
    "Long acrostic + e-lipogram + strict 5-words-per-line shape (full-text regex) + two exact substring counts ('you' also hides in 'your'). The near miss adds a final period.")

# ---------------------------------------------------------------- x03 JSON island
case("x03", "hard", "Invent a small island nation and describe it as a JSON object.", [
    "Output exactly one JSON object and nothing else (no code fences, no text before or after it).",
    'The object must have exactly four keys, in this order: "island", "population", "towns", "motto". No other keys.',
    '"island" is a string: a 5-letter name made of one capital letter followed by 4 lower-case letters, and the name must read the same forwards and backwards when capitalisation is ignored (like "Nolon").',
    '"population" is a whole number with exactly 4 digits, written without quotes, whose digits strictly increase from left to right (like 1359).',
    '"towns" is an array of exactly 3 strings; each is a single word made of one capital letter followed only by lower-case letters.',
    '"motto" is a string of exactly 6 words made only of lower-case letters a to z, separated by single spaces.',
    'Never use the letter "e" anywhere in the output (the four key names contain no "e").',
    'Across the whole output, the letter sequence "sun" must appear exactly once and the letter sequence "wind" must appear exactly once.',
], [], [
    {"check": "json"},
    {"check": "json_keys", "keys": ["island", "population", "towns", "motto"]},
    {"check": "regex", "pattern": "^\\s*\\{\\s*\"island\"\\s*:\\s*\"[A-Z][a-z]{4}\"\\s*,\\s*\"population\"\\s*:\\s*\\d{4}\\s*,\\s*\"towns\"\\s*:\\s*\\[\\s*\"[A-Z][a-z]*\"\\s*,\\s*\"[A-Z][a-z]*\"\\s*,\\s*\"[A-Z][a-z]*\"\\s*\\]\\s*,\\s*\"motto\"\\s*:\\s*\"[a-z]+(?: [a-z]+){5}\"\\s*\\}\\s*$"},
    {"check": "regex", "pattern": "\"island\"\\s*:\\s*\"([a-z])([a-z])[a-z]\\2\\1\"", "flags": "i"},
    {"check": "regex", "pattern": "\"population\"\\s*:\\s*(?=\\d{4}\\s*[,}])1?2?3?4?5?6?7?8?9?\\s*[,}]"},
    {"check": "no_letter", "letter": "e"},
    {"check": "include", "text": "sun", "min": 1, "max": 1},
    {"check": "include", "text": "wind", "min": 1, "max": 1},
],
    '{"island": "Nolon", "population": 2468, "towns": ["Korvik", "Dalby", "Mira"], "motto": "sun and wind will guard us"}',
    '{"island": "Nolon", "population": 2486, "towns": ["Korvik", "Dalby", "Mira"], "motto": "sun and wind will guard us"}',
    "Exact JSON shape (full-text regex) + palindrome check via backreferences + strictly increasing digits + e-lipogram over the whole output + exact substring counts. The near miss has population 2486 (digits not increasing).")

# ---------------------------------------------------------------- x04 headlines
case("x04", "hard", "Write 8 headlines for a fictional newspaper covering the opening of the first moon base.", [
    "Write exactly 8 lines (one headline per line), with no blank lines.",
    'Every line must start with ">> " (two greater-than signs and a space).',
    "Every word must start with a capital letter, including short words such as \"A\", \"Of\", \"To\" and \"On\".",
    'Never use the letter "s".',
    "No word may be longer than 8 characters.",
    'The letter sequence "Moon" must appear exactly 3 times, always with a capital M (so the lower-case "moon" must not appear at all; occurrences inside longer words such as "Moonlit" also count).',
    "The whole answer must be exactly 44 words long (the \">>\" markers are not words).",
    "Do not use any commas or digits.",
], [TITLE, WORDLEN], [
    {"check": "line_count", "min": 8, "max": 8},
    {"check": "each_line_starts_with", "text": ">> "},
    {"check": "title_case_lines"},
    {"check": "no_letter", "letter": "s"},
    {"check": "max_word_length", "max": 8},
    {"check": "include", "text": "Moon", "caseSensitive": True, "min": 3, "max": 3},
    {"check": "include", "text": "moon", "min": 3, "max": 3},
    {"check": "word_count", "min": 44, "max": 44},
    {"check": "no_commas"},
    {"check": "regex", "pattern": "\\d", "shouldMatch": False},
],
    ">> Moon Camp Open For Work\n>> Crew Of Eight Land On Rim\n>> Robot Digger Hit Ice Under Crater\n>> Moon Ice Water Now On Tap\n>> Doctor Warn Of Low Gravity Pain\n>> Night Party Lit Dark Plain\n>> Fan Can Rent A Moon Room\n>> Rocket Due In June",
    ">> Moon Camp Open For Work\n>> Crew Of Eight Land On Rim\n>> Robot Digger Hit Ice Under Crater\n>> Moon Ice Water Now On Tap\n>> Doctor Warn Of Low Gravity Pain\n>> Night Party Lit Dark Plain\n>> Fan Can Rent A Moon Room\n>> Rocket Due Back In June",
    "Title case including small words + banned letter s (no plurals, no 'is', 'as') + per-line prefix + word-length cap + case-sensitive exact count + exact total word count. The near miss is 45 words.")

# ---------------------------------------------------------------- x05 seven-word sentences without a
case("x05", "extreme", "Write about a lighthouse keeper's last night on duty.", [
    "Write exactly 6 sentences, each ending with a period, all in one paragraph on a single line (no line breaks).",
    "Every sentence must contain exactly 7 words, so the whole text has exactly 42 words. Separate words with single spaces and do not use '.', '!' or '?' anywhere except at the end of each sentence.",
    'Never use the letter "a".',
    'The letter sequence "light" must appear exactly 3 times (words such as "lighthouse" or "lights" also count, but "bright" does not contain it).',
    'The first word must be "Tonight".',
    'The text must end with "home." (the word "home" followed by a period).',
    "Do not use any commas.",
], [], [
    {"check": "sentence_count", "min": 6, "max": 6},
    {"check": "word_count", "min": 42, "max": 42},
    {"check": "regex", "pattern": "^\\s*(?:[^\\s.!?]+ ){6}[^\\s.!?]+\\.(?: (?:[^\\s.!?]+ ){6}[^\\s.!?]+\\.){5}\\s*$"},
    {"check": "no_letter", "letter": "a"},
    {"check": "include", "text": "light", "min": 3, "max": 3},
    {"check": "starts_with", "text": "Tonight ", "caseSensitive": True},
    {"check": "ends_with", "text": "home.", "caseSensitive": True},
    {"check": "no_commas"},
],
    "Tonight old Tom climbs up the lighthouse. He trims the wick for this night. The bright light sweeps over cold rocks. Ships drift by under his firm glow. By sunrise his long duty is over. He shuts the light then drives home.",
    "Tonight old Tom climbs up the lighthouse. He trims the wick for this night. The bright light sweeps over cold rocks. Ships drift by under his firm glow. By sunrise his long duty is over. He shuts the light then walks home.",
    "A-lipogram (bans 'a', 'and', 'at', 'was', 'that') with exactly 7 words in each of 6 sentences (full-text regex) plus exact word, sentence and substring counts. The near miss writes 'walks' (contains a).")

# ---------------------------------------------------------------- x06 bullets without 'the'/'and'
case("x06", "hard", "Give seven tips for keeping a houseplant alive.", [
    'Write exactly 7 lines, and every line must be a bullet point starting with a hyphen and a space ("- "). Nothing else: no heading, introduction or closing line.',
    "Every line must end with a period.",
    "Use only lower-case letters: no capital letters anywhere.",
    'The letter sequence "the" must never appear, not even inside other words (so "they", "other", "weather" and "then" are forbidden too).',
    'The letter sequence "and" must never appear, not even inside other words (so "hand", "sand" and "understand" are forbidden too).',
    'The word "because" must appear exactly 3 times.',
    'The whole answer must be exactly 56 words long (the "-" markers are not words).',
    "No word may be longer than 8 characters.",
    "Do not use any commas.",
], [WORDLEN], [
    {"check": "bullet_count", "min": 7, "max": 7},
    {"check": "each_line_starts_with", "text": "- "},
    {"check": "regex", "pattern": "^(?!.*\\.\\s*$).*\\S.*$", "flags": "m", "shouldMatch": False},
    {"check": "all_lowercase"},
    {"check": "exclude", "text": "the"},
    {"check": "exclude", "text": "and"},
    {"check": "include", "text": "because", "min": 3, "max": 3},
    {"check": "word_count", "min": 56, "max": 56},
    {"check": "max_word_length", "max": 8},
    {"check": "no_commas"},
],
    "- water it only when top soil feels dry.\n- keep near a bright window because light helps.\n- pick a pot with holes because roots rot.\n- wipe dust off its leaves every few weeks.\n- feed it once a month during warm spring.\n- turn its pot often because stems lean sunward.\n- check for pests on stems every single week.",
    "- water only when the top soil feels dry.\n- keep near a bright window because light helps.\n- pick a pot with holes because roots rot.\n- wipe dust off its leaves every few weeks.\n- feed it once a month during warm spring.\n- turn its pot often because stems lean sunward.\n- check for pests on stems every single week.",
    "Bans the two commonest English letter sequences 'the' and 'and' (also inside words), with exact bullet structure, exact word count, exact 'because' count and a length cap. The near miss slips in one 'the' and is otherwise valid.")

# ---------------------------------------------------------------- x07 dialogue without 'no'
case("x07", "extreme", "Write a short dialogue between a customer (A) and a railway ticket clerk (B).", [
    'Write exactly 8 lines that alternate between the two speakers, starting with the customer: lines 1, 3, 5 and 7 start with "A: " and lines 2, 4, 6 and 8 start with "B: ". No blank lines and no other lines.',
    "Every A line must end with a question mark, and every B line must end with a period.",
    'The letter sequence "no" must never appear, in any capitalisation, not even inside other words (so "not", "know", "now", "none" and "north" are forbidden too).',
    'The letter sequence "ticket" must appear exactly 3 times (words such as "tickets" also count).',
    'The whole dialogue must be exactly 64 words long. The speaker labels "A:" and "B:" count as words.',
    "Do not use any digits, commas or exclamation marks.",
], [], [
    {"check": "line_count", "min": 8, "max": 8},
    {"check": "regex", "pattern": "^\\s*A: [^\\n]*\\?[ \\t]*\\n[ \\t]*B: [^\\n]*\\.[ \\t]*\\n[ \\t]*A: [^\\n]*\\?[ \\t]*\\n[ \\t]*B: [^\\n]*\\.[ \\t]*\\n[ \\t]*A: [^\\n]*\\?[ \\t]*\\n[ \\t]*B: [^\\n]*\\.[ \\t]*\\n[ \\t]*A: [^\\n]*\\?[ \\t]*\\n[ \\t]*B: [^\\n]*\\.\\s*$"},
    {"check": "exclude", "text": "no"},
    {"check": "include", "text": "ticket", "min": 3, "max": 3},
    {"check": "word_count", "min": 64, "max": 64},
    {"check": "regex", "pattern": "\\d", "shouldMatch": False},
    {"check": "no_commas"},
    {"check": "exclude", "text": "!"},
],
    "A: Could I buy a ticket to Leeds for today?\nB: The last direct train leaves at six.\nA: How much will a return ticket cost me?\nB: It costs forty pounds.\nA: May I pay by card at this desk?\nB: Of course. Please tap your card on the reader.\nA: Where do I collect the printed ticket?\nB: It prints right here.",
    "A: Could I buy a ticket to Leeds for today?\nB: The last direct train leaves at six.\nA: How much will a return ticket cost me?\nB: It costs forty pounds.\nA: May I pay by card at this desk?\nB: Of course. Please tap your card on the reader.\nA: Where do I collect the printed ticket?\nB: It prints right now.",
    "Strict alternating speaker structure (full-text regex) + a banned two-letter sequence that hides in 'not', 'know', 'now', 'cannot' + exact word count including labels + exact substring count. The near miss ends with 'now' (contains 'no') and is otherwise valid.")

# ---------------------------------------------------------------- x08 tea without t
case("x08", "extreme", "Write step-by-step instructions for making a hot drink from tea leaves, milk and sugar.", [
    'Write exactly 6 lines, numbered "1. " to "6. " in order (the number, a period, a space), with no blank lines and nothing else.',
    "After its number, every line has exactly 6 words: the first word is one capital letter followed only by lower-case letters, the other five words consist only of lower-case letters, words are separated by single spaces, and the line ends with a period straight after the sixth word. No other punctuation.",
    'Never use the letter "t" (so you cannot write "tea", "the", "it", "water" or "stir").',
    'The word "mug" must appear exactly 2 times.',
    'The letter sequence "brew" must appear exactly 2 times.',
    "The whole answer must be exactly 42 words long (each \"1.\" and so on counts as one word).",
], [], [
    {"check": "line_count", "min": 6, "max": 6},
    {"check": "bullet_count", "min": 6, "max": 6},
    {"check": "regex", "pattern": "^\\s*1\\. [A-Z][a-z]*(?: [a-z]+){5}\\.[ \\t]*\\n[ \\t]*2\\. [A-Z][a-z]*(?: [a-z]+){5}\\.[ \\t]*\\n[ \\t]*3\\. [A-Z][a-z]*(?: [a-z]+){5}\\.[ \\t]*\\n[ \\t]*4\\. [A-Z][a-z]*(?: [a-z]+){5}\\.[ \\t]*\\n[ \\t]*5\\. [A-Z][a-z]*(?: [a-z]+){5}\\.[ \\t]*\\n[ \\t]*6\\. [A-Z][a-z]*(?: [a-z]+){5}\\.\\s*$"},
    {"check": "no_letter", "letter": "t"},
    {"check": "include", "text": "mug", "min": 2, "max": 2},
    {"check": "include", "text": "brew", "min": 2, "max": 2},
    {"check": "word_count", "min": 42, "max": 42},
],
    "1. Boil a small pan of milk.\n2. Drop some black leaves in quickly.\n3. Brew on a low flame briefly.\n4. Pour your brew in a mug.\n5. Add honey or sugar as needed.\n6. Sip slowly from your warm mug.",
    "1. Boil a small pan of milk.\n2. Drop some tea leaves in quickly.\n3. Brew on a low flame briefly.\n4. Pour your brew in a mug.\n5. Add honey or sugar as needed.\n6. Sip slowly from your warm mug.",
    "T-lipogram on a topic whose key words ('tea', 'water', 'kettle', 'stir', 'minutes') all contain t, plus an exact numbered 6x6 word grid (full-text regex) and exact counts. The near miss writes 'tea' and is otherwise valid.")

# ---------------------------------------------------------------- x09 no a and no e
case("x09", "extreme", "Write a short motivational message for a friend who is starting a new job tomorrow.", [
    'Never use the letter "a" and never use the letter "e".',
    "Write exactly 4 sentences.",
    "Write exactly 36 words.",
    'The first word must be "You", with a capital Y.',
    'The letter sequence "you" must appear exactly 5 times in any capitalisation (inside words such as "your" it also counts).',
    'The text must end with an exclamation mark ("!").',
    "Do not use any commas.",
    "No word may be longer than 7 characters.",
], [WORDLEN], [
    {"check": "no_letter", "letter": "a"},
    {"check": "no_letter", "letter": "e"},
    {"check": "sentence_count", "min": 4, "max": 4},
    {"check": "word_count", "min": 36, "max": 36},
    {"check": "starts_with", "text": "You ", "caseSensitive": True},
    {"check": "include", "text": "you", "min": 5, "max": 5},
    {"check": "ends_with", "text": "!"},
    {"check": "no_commas"},
    {"check": "max_word_length", "max": 7},
],
    "You will do good work in this job. Your skills brought you to this spot through long hours of study. Trust your gut in rough spots of this first month. Go on now: you will triumph!",
    "You will do good work in this job. Your skills brought you to this spot through long hours of study. Trust your gut in rough spots of your first month. Go on now: you will triumph!",
    "Double lipogram (no a, no e) with exact word and sentence counts, a fixed first word, an exact 'you' count that also catches 'your', and a length cap. The near miss has 6 occurrences of 'you'.")

# ---------------------------------------------------------------- x10 STREETCAR acrostic without o
case("x10", "extreme", "Write a 9-line poem about a city waking up in the morning.", [
    "Write exactly 9 lines, with no blank lines.",
    'Every line must start with "~ " (a tilde and a space).',
    "Ignoring the \"~ \" at the start, the first letters of the lines, read from top to bottom, must spell STREETCAR.",
    'After the "~ ", every line must have exactly 6 words separated by single spaces, so the poem has exactly 54 words in total (the "~" markers are not words).',
    'Every word must start with a capital letter, including short words such as "The", "And" and "With".',
    'Never use the letter "o".',
    'The letter sequence "light" must appear exactly 2 times (words such as "Sunlight" also count).',
    "Do not use any commas.",
], [ACRO, TITLE], [
    {"check": "line_count", "min": 9, "max": 9},
    {"check": "each_line_starts_with", "text": "~ "},
    {"check": "acrostic", "word": "STREETCAR"},
    {"check": "regex", "pattern": "^\\s*(?:~ \\S+(?: \\S+){5}[ \\t]*\\n[ \\t]*){8}~ \\S+(?: \\S+){5}\\s*$"},
    {"check": "word_count", "min": 54, "max": 54},
    {"check": "title_case_lines"},
    {"check": "no_letter", "letter": "o"},
    {"check": "include", "text": "light", "min": 2, "max": 2},
    {"check": "no_commas"},
],
    "~ Street Lamps Fade As Day Begins\n~ Trams Rattle Past The Silent Bakery\n~ Red Light Slips Between Tall Cranes\n~ Every Market Stall Swings Its Shutters\n~ Early Buses Hum Past Sleepy Parks\n~ Taxis Queue Beside The Busy Square\n~ Cafe Glass Glitters With Fresh Light\n~ All The Bridges Fill With Traffic\n~ Rain Clears And The City Stirs",
    "~ Street Lamps Fade As Day Begins\n~ Trams Rattle Past The Silent Bakery\n~ Red Light Slips Between Tall Cranes\n~ Every Market Stall Swings Its Shutters\n~ Early Buses Hum Past Sleepy Parks\n~ Taxis Queue Beside The Busy Square\n~ Cafe Glass Glitters With Fresh Light\n~ All The Bridges Fill With Traffic\n~ Rain Clears And The Town Stirs",
    "Nine-letter acrostic behind a per-line prefix + title case + o-lipogram (bans 'of', 'to', 'on', 'for', 'from') + 6-words-per-line shape + exact counts. The near miss writes 'Town' (contains o) and is otherwise valid.")

# ---------------------------------------------------------------- x11 Zyloq product copy
case("x11", "hard", "Write a product description for a fictional smart kettle called the Zyloq.", [
    "Write exactly 3 paragraphs separated by single blank lines, with no line breaks inside a paragraph.",
    "Each paragraph must contain exactly 2 sentences (so 6 sentences in total). Every sentence ends with '.', '!' or '?', and these three characters must not be used anywhere else.",
    "The whole description must be exactly 75 words long.",
    'The name "Zyloq" must appear exactly 3 times, always spelled with a capital Z.',
    'The letter sequences "very" and "really" must never appear, not even inside other words (so "every" and "delivery" are forbidden too).',
    "Do not use any digits.",
    'The text must end with "Zyloq." (the name followed by a period).',
    "No word may be longer than 10 characters.",
], [WORDLEN], [
    {"check": "paragraph_count", "min": 3, "max": 3},
    {"check": "sentence_count", "min": 6, "max": 6},
    {"check": "regex", "pattern": "^\\s*[^\\n.!?]+[.!?] [^\\n.!?]+[.!?][ \\t]*\\n[ \\t]*\\n[ \\t]*[^\\n.!?]+[.!?] [^\\n.!?]+[.!?][ \\t]*\\n[ \\t]*\\n[ \\t]*[^\\n.!?]+[.!?] [^\\n.!?]+[.!?]\\s*$"},
    {"check": "word_count", "min": 75, "max": 75},
    {"check": "include", "text": "Zyloq", "caseSensitive": True, "min": 3, "max": 3},
    {"check": "exclude", "text": "very"},
    {"check": "exclude", "text": "really"},
    {"check": "regex", "pattern": "\\d", "shouldMatch": False},
    {"check": "ends_with", "text": "Zyloq.", "caseSensitive": True},
    {"check": "max_word_length", "max": 10},
],
    "Meet the Zyloq, a kettle that listens. Tell it the heat you want and it warms water to that exact point.\n\nA quiet base glows blue while it works and green when your drink is ready. Your phone gets a gentle alert from the Zyloq so tea never goes cold.\n\nA steel body keeps small hands safe and a filter traps scale before it reaches your cup. Mornings start calmly and quietly with the Zyloq.",
    "Meet the Zyloq, a kettle that listens. Tell it the heat you want and it warms water to that exact point.\n\nA quiet base glows blue while it works and green when your drink is ready. Your phone gets a gentle alert from the Zyloq so tea never goes cold.\n\nA steel body keeps every hand safe and a filter traps scale before it reaches your cup. Mornings start calmly and quietly with the Zyloq.",
    "Paragraph x sentence grid (full-text regex) + exact word count + case-sensitive brand count + banned sequences that hide in common words ('every', 'delivery') + fixed ending. The near miss uses 'every'.")

# ---------------------------------------------------------------- x12 JSON board games
case("x12", "hard", "Invent five board games and list them as a JSON array.", [
    "Output only a JSON array (no code fences, no text before or after it) written on exactly 7 lines: the first line is just \"[\", each of the next five lines holds one complete object (the first four followed by a comma), and the last line is just \"]\". Lines 2 to 6 may be indented with spaces.",
    'Each object has exactly three keys in this order: "name", "players", "minutes".',
    '"name" is a string of exactly two words separated by one space; each word is one capital letter followed by one or more lower-case letters, and both words start with the same letter (like "Silver Sails"). The five names must start with the letters B, R, A, V and E, in that order (so the first game is "B... B...", the second "R... R...", and so on).',
    '"players" is a string of the form "min-max" with single-digit numbers from 1 to 8 and min smaller than max (like "2-4"); all five "players" strings must be different.',
    '"minutes" is a whole number without quotes that is one of 15, 30, 45, 60, 75, 90, 105 or 120; all five values must be different.',
    'Never use the letter "o" anywhere in the output.',
    'The letter sequence "game" must not appear anywhere, in any capitalisation.',
], [], [
    {"check": "json"},
    {"check": "line_count", "min": 7, "max": 7},
    {"check": "regex", "pattern": X12_SHAPE},
    {"check": "regex", "pattern": "\"minutes\" *: *(\\d+)\\b[\\s\\S]*\"minutes\" *: *\\1\\b", "shouldMatch": False},
    {"check": "regex", "pattern": "\"players\" *: *\"(\\d-\\d)\"[\\s\\S]*\"players\" *: *\"\\1\"", "shouldMatch": False},
    {"check": "no_letter", "letter": "o"},
    {"check": "exclude", "text": "game"},
],
    '[\n  {"name": "Brass Bandits", "players": "2-4", "minutes": 45},\n  {"name": "Ruby Raiders", "players": "3-6", "minutes": 60},\n  {"name": "Amber Alleys", "players": "1-4", "minutes": 30},\n  {"name": "Velvet Vaults", "players": "2-5", "minutes": 90},\n  {"name": "Emerald Empires", "players": "4-8", "minutes": 120}\n]',
    '[\n  {"name": "Brass Barons", "players": "2-4", "minutes": 45},\n  {"name": "Ruby Raiders", "players": "3-6", "minutes": 60},\n  {"name": "Amber Alleys", "players": "1-4", "minutes": 30},\n  {"name": "Velvet Vaults", "players": "2-5", "minutes": 90},\n  {"name": "Emerald Empires", "players": "4-8", "minutes": 120}\n]',
    "Exact line-by-line JSON layout + per-field formats (full-text regex) + all-different checks via backreferences + an o-lipogram + a banned word. The near miss uses 'Barons' (contains o).")


def main(root, outdir):
    with open(f"{outdir}/extreme_cases.json", "w") as fh:
        # every passing sample is also checked with harmless whitespace around it (a trailing newline, a
        # leading blank line) so that the constraints do not punish invisible formatting differences
        json.dump([dict(id=c["id"], expected=c["expected"], samples={"pass": [v for p in c["samples"]["pass_"] for v in (p, p + "\n", "\n" + p + "\n\n")], "fail": c["samples"]["fail"]}) for c in C], fh, indent=1)
    cases = []
    for c in C:
        cases.append({
            "id": c["id"],
            "prompt": c["prompt"],
            "expected": c["expected"],
            "notes": f"[{c['diff']}] {c['why']} Satisfiability: this passing example scores 1.0 under src/scoring/constraints.ts and a near miss was checked to fail (scripts: frontier-reasoning/build_extreme_constraints.py + check.mts): <<<{c['samples']['pass_'][0]}>>>",
        })
    test = {
        "kind": "prompt",
        "id": "instruction.extreme-constraints",
        "version": "1.0.0",
        "name": "Extreme Constraints",
        "category": "instruction",
        "description": "Twelve writing tasks that each stack 6-10 interacting, machine-checked constraints that must ALL hold at once: exact word and sentence counts together with lipograms (no e, no a, both, no t, no o, no s), long acrostics behind line prefixes, fixed words per line or per sentence, forbidden letter sequences that hide inside common words ('the', 'and', 'no', 'very'), title case, word-length caps and strict JSON layouts with palindrome, increasing-digit and all-different checks. Scoring is all-or-nothing per task, and every counting rule is restated exactly as the checker applies it, so a miss is a genuine self-monitoring failure.",
        "difficulty": "extreme",
        "tags": ["instruction-following", "constraints", "lipogram", "acrostic", "json", "frontier", "all-or-nothing"],
        "hook": "Sixty words. Five sentences. No letter e. All at once, or zero.",
        "maxOutputTokens": 32000,
        "estimate": {"inputTokens": 700, "outputTokens": 16000},
        "author": "Gauntlet Core",
        "createdAt": "2026-09-24",
        "scorer": {"type": "constraints", "allOrNothing": True},
        "cases": cases,
    }
    with open(f"{root}/tests/instruction/extreme-constraints.json", "w") as fh:
        json.dump(test, fh, indent=2, ensure_ascii=False)
        fh.write("\n")
    print("wrote", len(cases), "cases")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "../..", sys.argv[2] if len(sys.argv) > 2 else ".")
