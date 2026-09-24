DBG = {}

DBG['e1'] = dict(fn='addDecimalStrings', d='medium', examples=[
    (['0.1', '0.2'], '0.3'),
    (['-0.50', '0.5'], '0'),
    (['007.250', '0.750'], '8'),
    (['999999999999999999999999999999', '1'], '1000000000000000000000000000000'),
], prompt="""Write a JavaScript function `addDecimalStrings(a, b)` that adds two decimal numbers given as strings and returns the exact sum as a string.

Input: each of `a` and `b` matches the pattern `^[+-]?[0-9]+(\\.[0-9]+)?$`: an optional sign, one or more digits, and optionally a decimal point followed by one or more digits. Leading zeros and trailing zeros may appear (e.g. "007.2500", "-0.0"). Each string can have up to 1,000 digits, far beyond the precision of JavaScript numbers, so the result must be computed exactly (do not convert to a Number).

Output: the exact sum in canonical form:
- no plus sign; a minus sign only if the result is negative;
- the integer part has no leading zeros, except that it is exactly "0" when its value is zero (e.g. "0.25", "-0.5");
- the fractional part has no trailing zeros; if the result is a whole number there is no decimal point at all;
- zero is always written "0" (never "-0", "0.0" or "+0").

Examples:
{examples}

{trailer}""")

DBG['e2'] = dict(fn='smartTruncate', d='medium', examples=[
    (['hello world', 8], 'hello w…'),
    (['hello world', 7], 'hello…'),
    (['hello', 5], 'hello'),
    (['\U0001F600\U0001F600\U0001F600\U0001F600', 3], '\U0001F600\U0001F600…'),
], prompt="""Write a JavaScript function `smartTruncate(text, maxLen)` that shortens text for display.

Length is measured in Unicode code points, not UTF-16 code units: a character outside the Basic Multilingual Plane (for example most emoji, which JavaScript stores as a surrogate pair) counts as 1. Combining marks, variation selectors, skin-tone modifiers and zero-width joiners are separate code points and each count as 1; do not try to group code points into user-perceived characters, and never split a surrogate pair.

Rules, where `maxLen` is an integer (possibly 0 or negative):
1. If `text` has at most `maxLen` code points, return `text` unchanged.
2. Otherwise, if `maxLen < 1`, return the empty string.
3. Otherwise take the first `maxLen - 1` code points of `text`, remove any whitespace code points from the END of that prefix (whitespace means exactly the characters matched by JavaScript's `/\\s/`), and append the ellipsis character "…" (U+2026). Return the result.

Examples:
{examples}

{trailer}""")

DBG['e3'] = dict(fn='rankLeaderboard', d='medium', examples=[
    ([[['a', 10, 50], ['b', 20, 60], ['c', 20, 40]]], [[1, 'c'], [2, 'b'], [3, 'a']]),
    ([[['ann', 7, 30], ['bob', 9, 30], ['cat', 7, 30], ['dan', 7, 25], ['eve', 9, 30]]], [[1, 'bob'], [1, 'eve'], [3, 'dan'], [4, 'ann'], [4, 'cat']]),
    ([[]], []),
], prompt="""Write a JavaScript function `rankLeaderboard(entries)`.

`entries` is an array (possibly empty, up to 100,000 items) of `[name, score, time]` triples: `name` is a string (names are not necessarily unique), and `score` and `time` are finite numbers (possibly negative or non-integer).

Return an array of `[rank, name]` pairs, one per entry, ordered as follows:
- higher `score` first;
- among equal scores, lower `time` first;
- among entries with equal score AND equal time, keep their original order from the input.

Ranking uses standard competition ranking: an entry's rank is 1 plus the number of entries that are strictly better than it (higher score, or equal score and lower time). So entries with equal score and equal time share the same rank, and the next rank skips accordingly (1, 1, 3, ...). Compare numbers exactly with `<`, `>` and `===`.

Examples:
{examples}

{trailer}""")

DBG['e4'] = dict(fn='parseCsvRecord', d='hard', examples=[
    (['a,,c,'], ['a', '', 'c', '']),
    (['"she said ""hi""",ok'], ['she said "hi"', 'ok']),
    ([''], ['']),
    (['"abc"d,e'], None),
], prompt="""Write a JavaScript function `parseCsvRecord(record)` that splits one CSV record into its fields, or returns `null` if the record is malformed.

Rules (follow them exactly; whitespace is never trimmed):
1. Fields are separated by commas. A record with k commas outside quoted fields has exactly k+1 fields. The empty string is a record with one empty field, and a trailing comma produces a trailing empty field.
2. A field whose first character is a double quote (") is a quoted field. Its value is everything up to the closing quote. Inside a quoted field, two consecutive double quotes ("") stand for one literal double quote, and commas, newlines and all other characters are literal. The closing quote is a double quote that is not part of such a pair. After the closing quote, the next character must be a comma or the end of the record; anything else (even a space) makes the record malformed. A quoted field with no closing quote makes the record malformed.
3. A field whose first character is not a double quote is an unquoted field: it runs up to the next comma or the end of the record, and its value is taken verbatim. If an unquoted field contains a double quote anywhere, the record is malformed (for example ` "a"` with a leading space is malformed).
4. Return an array of field strings, or `null` if the record is malformed.

Examples:
{examples}

{trailer}""")

DBG['e5'] = dict(fn='romanToInt', d='medium', examples=[
    (['MCMXCIV'], 1994),
    (['XIV'], 14),
    (['IIII'], None),
    (['IC'], None),
], prompt="""Write a JavaScript function `romanToInt(s)` that converts a Roman numeral to an integer, but only if it is written in standard form.

Standard form, for values 1 to 3999:
- Symbols: I=1, V=5, X=10, L=50, C=100, D=500, M=1000, uppercase only.
- The numeral is written from thousands down to units. Thousands: "", M, MM, MMM. Hundreds: "", C, CC, CCC, CD, D, DC, DCC, DCCC, CM. Tens: "", X, XX, XXX, XL, L, LX, LXX, LXXX, XC. Units: "", I, II, III, IV, V, VI, VII, VIII, IX. A standard numeral is the concatenation of one choice from each group, in that order, and is not empty.
- Consequently the only subtractive pairs allowed are IV, IX, XL, XC, CD and CM, no symbol repeats more than three times in a row, and V, L and D never repeat.

Return the integer value if `s` is a standard-form numeral, and `null` for anything else (the empty string, lowercase letters, spaces or other characters, non-standard forms such as "IIII", "VX", "IC", "XM" or "IIV", and values of 4000 or more).

Examples:
{examples}

{trailer}""")

DBG['e6'] = dict(fn='normalizePath', d='medium', examples=[
    (['/home//user/./docs/../pics/'], '/home/user/pics'),
    (['a/b/c/../../../../d'], '../d'),
    (['/../../x'], '/x'),
    ([''], '.'),
], prompt="""Write a JavaScript function `normalizePath(path)` that normalises a Unix-style path purely as text (no file-system access).

Rules:
1. The path is absolute if it starts with "/", otherwise relative.
2. Split the path on "/". Ignore empty segments (from repeated or trailing slashes) and "." segments.
3. A ".." segment removes the most recent kept segment if there is one and it is not itself "..". Otherwise: in an absolute path the ".." is dropped (you cannot go above the root); in a relative path the ".." is kept.
4. Any other segment is a normal name and is kept as-is, including names such as "...", ".hidden", "a..b" and names containing spaces or backslashes.
5. Output: for an absolute path, "/" followed by the kept segments joined with "/" (just "/" if none remain). For a relative path, the kept segments joined with "/", or "." if none remain. There is never a trailing slash (except the root "/" itself), and a leading "//" is not special (it becomes "/").

Paths can be up to 200,000 characters long.

Examples:
{examples}

{trailer}""")
