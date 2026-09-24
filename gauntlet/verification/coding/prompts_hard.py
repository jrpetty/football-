HARD = {}

HARD['h1'] = dict(fn='evaluate', d='hard', examples=[
    (['1 + 2 * 3'], 7),
    (['-2^2'], -4),
    (['2^-2'], 0.25),
    (['2^3^2'], 512),
    (['-7 % 3'], -1),
    (['2(3)'], None),
    (['1/0'], None),
], prompt="""Write a JavaScript function `evaluate(expr)` that evaluates an arithmetic expression given as a string and returns its value as a number, or `null` if the expression is invalid or cannot be evaluated.

Syntax:
- Numbers: one or more digits, optionally followed by a decimal point and one or more digits (e.g. `12`, `0.5`, `007.25`). Leading zeros are allowed and the value is decimal. Forms such as `.5`, `5.`, `1e3` are invalid. A number's value is the nearest double-precision value (what JavaScript's `Number()` gives for that text). A number literal too large to be finite (for example a 1 followed by 400 zeros) makes the whole expression invalid, so return `null`; a literal so small that it rounds to 0 is fine.
- Binary operators: `+`, `-`, `*`, `/`, `%`, `^`. Unary prefix operators: `+` and `-` (they may be repeated, e.g. `--3` is 3).
- Parentheses `(` `)`.
- Spaces and tabs may appear between tokens and are ignored. Any other character makes the expression invalid.
- Every binary operator needs an operand on both sides; there is no implicit multiplication, so `2(3)`, `(1)(2)` and `2 3` are invalid. Empty input, unbalanced parentheses and `()` are invalid.

Precedence and associativity (highest first):
1. `^` (power), right-associative: `2^3^2` = 2^(3^2) = 512.
2. Unary `+` and `-`. They bind less tightly than `^` on their right, so `-2^2` = -(2^2) = -4. The right operand of `^` may itself start with a unary sign: `2^-2` = 0.25 and `2^-1^-1` = 2^(-(1^(-1))) = 0.5.
3. `*`, `/`, `%`, left-associative. `/` is true division. `%` is JavaScript's remainder (the result has the sign of the left operand, e.g. `-7 % 3` = -1).
4. `+`, `-`, left-associative.

Evaluation errors: return `null` if any operation produces a non-finite result, i.e. division or remainder by zero, zero raised to a negative power, overflow to Infinity, or NaN (for example `(-8)^(1/3)`). Use ordinary double-precision arithmetic (as JavaScript's `+ - * / % **` operators do). If the final result is negative zero, return 0.

Limits: expressions can be up to 200,000 characters long, with parentheses nested up to 50,000 levels deep and long chains of unary signs, so make sure your solution does not overflow the call stack. Do not use `eval`, `new Function` or similar: dynamic code evaluation is disabled in the test sandbox (and JavaScript's own operator rules differ from these anyway).

Examples:
{examples}

{trailer}""")

HARD['h2'] = dict(fn='regexMatch', d='extreme', examples=[
    (['(ab|cd)*e', 'abcdabe'], True),
    (['colou?r', 'colouur'], False),
    (['[a-cx-z]+', 'abzyx'], True),
    (['a{2}', 'a{2}'], True),
    (['\\.\\*', '.*'], True),
], prompt="""Write a JavaScript function `regexMatch(pattern, text)` that returns `true` if the WHOLE of `text` matches `pattern` and `false` otherwise. Implement the matching yourself: do not use JavaScript's built-in `RegExp`, regex literals, or any library.

Pattern syntax (this is the complete language; anything not listed is an ordinary literal character):
- `c` - any character other than the special characters `( ) [ ] | * + ? . \\` matches itself. For example `{`, `}`, `^`, `$`, `-` and `,` are ordinary literals.
- `.` matches any single character, including a newline.
- `\\x` - a backslash followed by any character x matches x literally (e.g. `\\.`, `\\*`, `\\\\`, `\\(`).
- `[...]` - a character class matching exactly one character. A `^` immediately after `[` negates the class. The class body is everything after `[` (and after the `^`, if present) up to the closing `]`, where a `]` that is the very FIRST character of the body is a literal member rather than the end (so `[]a]` matches `]` or `a`, and `[^]]` matches any character except `]`). Read the body from left to right: if the current character is followed by `-` and then by at least one more body character, those three characters form an inclusive range by UTF-16 code (e.g. `a-z`); otherwise the current character is a literal member. Consequently a `-` that is the first or last character of the body is literal, and after a range a following `-` starts over (so `[a-c-e]` contains a, b, c, `-` and e). Every other character inside a class, including `\`, `[`, `.`, `^` (when not first) and `|`, is literal. In the tests every range has its first character's code less than or equal to its second's, and every class body is non-empty.
- `( ... )` groups a sub-pattern. `A|B` is alternation (lowest precedence; either side may be empty, e.g. `x(|y)z` matches `xz` and `xyz`).
- `*`, `+`, `?` placed after an atom (a character, `.`, escape, class or group) mean zero or more, one or more, and zero or one repetitions of that atom. Each atom has at most one quantifier.
- Concatenation binds tighter than `|`; quantifiers bind tighter than concatenation. An empty pattern matches only the empty string.

A valid pattern is one built only from the rules above: parentheses are balanced; every quantifier directly follows an atom (never at the start of the pattern or of a group or alternative, and never after another quantifier); every backslash is followed by a character; and every class is closed. Empty groups `()` and empty alternatives are allowed and match the empty string. Every pattern in the tests is valid. Characters are compared as JavaScript UTF-16 code units. Patterns are up to 200 characters and texts up to 20,000 characters, and the tests include patterns that make naive backtracking take exponential time (e.g. `((a*)*)*b` against a long run of `a`s), so your matcher must run in polynomial time.

Examples:
{examples}

{trailer}""")

HARD['h3'] = dict(fn='meridianAddDays', d='hard', examples=[
    (['2026-03-12', 20], '2026-04-04'),
    (['2028-06-28', 1], '2028-LD'),
    (['2026-13-28', 1], '2026-YD'),
    (['2026-YD', 1], '2027-01-01'),
    (['90-06-28', 1], '90-07-01'),
], prompt="""The (fictional) Meridian calendar works like this:
- Every year has 13 months, numbered 01 to 13, and every month has exactly 28 days, numbered 01 to 28.
- In a leap year, one extra day called Leap Day comes immediately after the 28th day of month 06 and before the 1st day of month 07. It belongs to no month.
- Every year ends with one extra day called Year Day, immediately after the 28th day of month 13. It belongs to no month. The next day is day 01 of month 01 of the following year.
- So a common year has 365 days and a leap year has 366.
- Year Y is a leap year if Y is divisible by 6 but not by 90, or if Y is divisible by 360. (So 6, 12 and 84 are leap years; 90, 180 and 270 are not; 360 and 720 are.)
- Years are positive integers starting at year 1; there is no year 0.

Dates are written as strings:
- an ordinary day: `Y-MM-DD`, e.g. `2026-03-12` or `7-13-28`;
- Leap Day: `Y-LD`, e.g. `2028-LD`;
- Year Day: `Y-YD`, e.g. `2026-YD`.
Y is the year as a plain decimal integer with no leading zeros and no padding (it may have many digits); MM and DD always have exactly two digits.

Write a JavaScript function `meridianAddDays(date, days)` that returns the date that is `days` days after `date` (or before it, if `days` is negative), in the same format. `date` is always a valid date. `days` is an integer with absolute value up to 10^12, the input year is at most 10^9, and the tests guarantee the result is in year 1 or later (and below year 10^10). The function must be fast even for the largest values (do not step one day or one year at a time across millions of years). Do not use JavaScript's `Date`; it does not apply to this calendar.

Examples:
{examples}

{trailer}""")

HARD['h4'] = dict(fn='simulateCache', d='hard', examples=[
    ([2, 10, [['put', 0, 'a', 1], ['put', 1, 'b', 2], ['get', 2, 'a'], ['put', 3, 'c', 3], ['get', 4, 'b'], ['get', 4, 'a'], ['get', 4, 'c']]], [1, None, 1, 3]),
    ([2, 5, [['put', 0, 'a', 1], ['get', 4, 'a'], ['get', 5, 'a']]], [1, None]),
    ([2, 3, [['put', 0, 'a', 1], ['put', 1, 'b', 2], ['put', 3, 'c', 3], ['get', 3, 'b'], ['get', 3, 'c'], ['get', 3, 'a']]], [2, 3, None]),
], prompt="""Write a JavaScript function `simulateCache(capacity, ttl, events)` that simulates a key-value cache with least-recently-used (LRU) eviction and a time-to-live (TTL), and returns the results of all reads.

Inputs:
- `capacity`: integer >= 1, the maximum number of live entries.
- `ttl`: integer >= 1.
- `events`: an array (up to 200,000 items) processed in order. Each event is either `["put", time, key, value]` or `["get", time, key]`, where `time` is an integer from 0 to 10^9, times never decrease from one event to the next (several events may share the same time), `key` is a string of at most 20 characters and `value` is a number.
- Also 1 <= capacity <= 200,000 and 1 <= ttl <= 10^9.

Rules:
1. Every entry has an expiry time. An entry written by a put at time t expires at time t + ttl. During any event whose time is greater than or equal to an entry's expiry time, that entry is expired: it behaves exactly as if it were not in the cache (it is not returned, does not count towards capacity and is never chosen for eviction).
2. `get`: if the key has a live (unexpired) entry, the result is its value and the entry becomes the most recently used. A get does NOT change the entry's expiry time. Otherwise the result is `null`.
3. `put`: if the key has a live entry, replace its value, set its expiry to time + ttl, and make it the most recently used. Otherwise, if the number of live entries equals `capacity`, first remove the live entry that was least recently used; then insert the new entry (expiry time + ttl) as the most recently used.
4. "Used" means written by a put or returned by a successful get; recency follows the order of events (for events with equal times, later in the array means more recent).

Return an array containing the result of every `get`, in order (the value, or `null`).

Examples:
{examples}

{trailer}""")

HARD['h5'] = dict(fn='evaluateSheet', d='extreme', examples=[
    ([{'A1': '2', 'A2': '3', 'A3': '=A1*A2+1', 'B1': '=A3/(A1-2)'}], {'A1': 2, 'A2': 3, 'A3': 7, 'B1': '#DIV/0!'}),
    ([{'A1': '=B1', 'B1': '=A1', 'C1': '=A1+Q7', 'D1': '=Q7/0'}], {'A1': '#CYCLE!', 'B1': '#CYCLE!', 'C1': '#CYCLE!', 'D1': '#REF!'}),
    ([{'A1': '1', 'A2': '2', 'A3': '=SUM(A1:A2)', 'A4': '=SUM(A1:A5)'}], {'A1': 1, 'A2': 2, 'A3': 3, 'A4': '#CYCLE!'}),
], prompt="""Write a JavaScript function `evaluateSheet(cells)` that evaluates a small spreadsheet.

Input: `cells` is a plain object whose keys are cell names and whose values are the cells' raw contents (strings).
- A cell name is one uppercase letter A-Z followed by a row number from 1 to 999 without leading zeros (e.g. `A1`, `Z999`).
- A raw content is either a number literal (an optional `-`, digits, and optionally `.` followed by digits, e.g. `5`, `-2.5`) whose value is that number, or a formula: `=` followed by an expression.

Formula grammar (spaces may appear anywhere between tokens):
```
expr    := term (("+" | "-") term)*
term    := unary (("*" | "/") unary)*
unary   := "-" unary | primary
primary := number | cellname | "SUM(" cellname ":" cellname ")" | "(" expr ")"
number  := digits ["." digits]
```
`+ - * /` are ordinary double-precision floating-point arithmetic, left-associative, with the usual precedence. `SUM(X:Y)` adds the values of all cells PRESENT in `cells` that lie in the rectangle with corners X and Y (inclusive; the corners may be given in any order, e.g. `SUM(C3:A1)` covers columns A-C and rows 1-3). Cells in the rectangle that are not present in `cells` are simply skipped, and a SUM over no present cells is 0. The two corner names only define the rectangle: they are NOT references, so a corner that is not present in `cells` does not cause an error. SUM starts from 0 and adds the present cells in column-major order: column A before column B and so on, and within a column in increasing row number (so for `SUM(A1:B2)` the order is A1, A2, B1, B2), one addition at a time.

Dependencies: a formula depends on every cell it names directly (outside SUM) and on every present cell inside each of its SUM rectangles. Number literals have at most 15 significant digits and absolute value at most 10^9.

Results - each cell's value is a number or one of the error strings `"#CYCLE!"`, `"#REF!"`, `"#DIV/0!"`:
1. A cell that lies on a dependency cycle (it depends on itself directly, through a SUM rectangle that contains it, or through a chain of other cells) has the value `"#CYCLE!"`.
2. Otherwise a formula that directly names a cell not present in `cells` produces the error `"#REF!"`.
3. Otherwise, a division whose divisor evaluates to exactly 0 produces `"#DIV/0!"`.
4. Errors propagate: if any cell a formula depends on has an error value, the formula's value is an error. When several errors apply to one formula (from its dependencies, a missing name, or its own division by zero), the result is the most severe one, in the order `"#CYCLE!"` > `"#REF!"` > `"#DIV/0!"`. So a cell that depends on a cycle without being on it gets `"#CYCLE!"`.
5. Numeric results are returned as plain JavaScript numbers with no rounding.

Return a new object mapping every input cell name to its value. Sheets have up to 5,000 cells and dependency chains can be thousands of cells long, so avoid deep recursion.

Examples:
{examples}

{trailer}""")
