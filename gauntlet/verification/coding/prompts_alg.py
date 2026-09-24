TRAILER = ("Respond with a single ```javascript code block containing the complete function (plain JavaScript, no imports, no TypeScript). "
           "The function must have exactly the name and parameters given above; you may define helper functions inside the same code block. "
           "Do not read input or print output: the function is called directly with the arguments and its return value is checked against hidden tests, "
           "including large inputs with a time limit of about 2 seconds per test.")

ALG = {}

ALG['a1'] = dict(fn='coverageProfile', d='medium', examples=[
    ([[[1, 5, 2], [3, 8, 1]]], [[1, 3, 2], [3, 5, 3], [5, 8, 1]]),
    ([[[0, 5, 2], [5, 9, 2]]], [[0, 9, 2]]),
    ([[[0, 3, 1], [7, 9, 1]]], [[0, 3, 1], [7, 9, 1]]),
], prompt="""Write a JavaScript function `coverageProfile(intervals)`.

Input: `intervals` is an array of `[start, end, weight]` triples of integers with `start < end` and `weight >= 1`. Each triple covers the half-open range [start, end): it includes `start` and excludes `end`. Coordinates can be as large as plus or minus 10^12 (they are exact integers in JavaScript numbers). There are at most 50,000 intervals, in no particular order.

Output: an array of segments `[a, b, total]` describing how much weight covers each point of the number line:
- `total` is the sum of the weights of all intervals that contain the points of [a, b); every point of [a, b) has this same total;
- only segments with total > 0 are included (uncovered gaps are omitted);
- segments are sorted by `a`, do not overlap, and have `a < b`;
- two consecutive segments that touch (the first one's `b` equals the next one's `a`) must have different totals: if they would have the same total, merge them into a single segment;
- return `[]` when the input is empty.

Examples:
{examples}

{trailer}""")

ALG['a2'] = dict(fn='cheapestTrip', d='medium', examples=[
    ([4, [[0, 1, 10], [1, 2, 10], [2, 3, 10], [0, 3, 35]], 0, 3, 1], 17),
    ([2, [[0, 1, 7]], 1, 0, 0], 7),
    ([3, [[0, 1, 5]], 0, 2, 2], -1),
], prompt="""Write a JavaScript function `cheapestTrip(n, roads, start, target, coupons)`.

There are `n` cities numbered 0 to n-1. `roads` is an array of `[u, v, cost]` triples: a road between cities u and v that can be driven in either direction, paying `cost` (an integer from 1 to 1,000,000) each time it is driven. There may be several roads between the same two cities, and a road may connect a city to itself.

You also hold `coupons` (an integer from 0 to 10) discount coupons. Each time you drive a road you may use at most one coupon on that drive, which reduces the price of that drive to floor(cost / 2). Each coupon can be used only once, and you do not have to use all of them.

Return the minimum total price to get from city `start` to city `target`, or -1 if `target` cannot be reached. If `start === target`, return 0. Sizes: up to 20,000 cities and 50,000 roads.

Examples:
{examples}

{trailer}""")

ALG['a3'] = dict(fn='charAt', d='hard', examples=[
    (['ab3(c2(d))e', 5], 'c'),
    (['ab3(c2(d))e', 12], ''),
    (['2(a)3(b)', 4], 'b'),
], prompt="""Write a JavaScript function `charAt(pattern, index)`.

`pattern` describes a (possibly astronomically long) string using this syntax:
- A lowercase letter a-z stands for itself.
- `N(...)`, where N is a positive decimal integer without leading zeros (1 <= N <= 10^9), stands for the pattern inside the parentheses repeated N times. The parentheses always contain at least one letter somewhere inside them.
- Repetitions can be nested (up to 1,000 levels deep) and placed one after another; everything else is simply concatenated.
For example, `ab3(c2(d))e` means `abcddcddcdde`.

Patterns are always valid and contain only lowercase letters, digits and parentheses, and are at most 100,000 characters long. The decoded string can be far longer than 2^53 characters, so it cannot be built in memory.

`index` is an integer with 0 <= index <= 9,007,199,254,740,991 (Number.MAX_SAFE_INTEGER). Return the character at position `index` (0-based) of the decoded string as a one-character string, or the empty string `""` if `index` is greater than or equal to the decoded length.

Examples:
{examples}

{trailer}""")

ALG['a4'] = dict(fn='countSplits', d='medium', examples=[
    (['1234', 34], 5),
    (['10', 10], 1),
    (['1010', 10], 1),
    (['0', 5], 0),
], prompt="""Write a JavaScript function `countSplits(digits, limit)`.

`digits` is a string of 1 to 100,000 characters, each '0' to '9'. `limit` is an integer with 1 <= limit <= 1,000,000,000.

Count the number of ways to cut `digits` into one or more consecutive, non-empty pieces (keeping the original order and using every character exactly once) such that every piece:
- does not start with the character '0' (so "0", "07" and "00" are all invalid pieces), and
- has a decimal value that is at most `limit`.

Return the count modulo 1,000,000,007.

For example, `countSplits("1234", 34)` is 5: 1|2|3|4, 12|3|4, 1|23|4, 1|2|34 and 12|34.

Examples:
{examples}

{trailer}""")

ALG['a5'] = dict(fn='componentsAfterCuts', d='medium', examples=[
    ([3, [[0, 1], [1, 2]], [0, 1]], [2, 3]),
    ([3, [[0, 1], [0, 1], [1, 2]], [0, 1, 2]], [1, 2, 3]),
    ([4, [[0, 1], [2, 2]], [1]], [3]),
], prompt="""Write a JavaScript function `componentsAfterCuts(n, edges, cuts)`.

An undirected graph has `n` nodes numbered 0 to n-1 (1 <= n <= 20,000). `edges` is an array of `[u, v]` pairs (up to 50,000); the edge with array index i is edge i. Edges may repeat (parallel edges) and may be self-loops (u === v).

`cuts` is an array of distinct edge indices. The edges are removed from the graph one at a time, in the order given in `cuts`. Return an array of the same length as `cuts` whose element k is the number of connected components of the graph immediately after the first k+1 cuts have been made. Every node counts, including isolated nodes (a node with no remaining edges is a component by itself).

Examples:
{examples}

{trailer}""")

ALG['a6'] = dict(fn='longestWindow', d='medium', examples=[
    ([[1, 2, 1, 3, 1, 2, 1], 2, 7], 3),
    ([[0, 0, 0, 0], 1, 0], 4),
    ([[5], 1, 4], 0),
], prompt="""Write a JavaScript function `longestWindow(nums, maxDistinct, maxSum)`.

`nums` is an array of up to 200,000 integers, each between 0 and 10,000. `maxDistinct` and `maxSum` are integers >= 0.

Return the length of the longest contiguous, non-empty subarray of `nums` that contains at most `maxDistinct` distinct values AND whose elements sum to at most `maxSum`. If no non-empty subarray qualifies (for example when `nums` is empty or `maxDistinct` is 0), return 0.

Examples:
{examples}

{trailer}""")

ALG['a7'] = dict(fn='topWords', d='easy', examples=[
    (['Hello hello HELLO world', 5], [['hello', 3], ['world', 1]]),
    (['b a c b a c', 2], [['a', 2], ['b', 2]]),
    (["Don't stop", 3], [['don', 1], ['stop', 1], ['t', 1]]),
], prompt="""Write a JavaScript function `topWords(text, k)`.

A word is a maximal run of ASCII letters (A-Z or a-z). Every other character, including digits, apostrophes, hyphens, whitespace, punctuation and all non-ASCII characters (such as é or ï), separates words. Words are compared case-insensitively and reported in lowercase.

Return the `k` most frequent words (k >= 0) as an array of `[word, count]` pairs, sorted by count from highest to lowest, with ties broken by the word in ascending alphabetical (code-unit) order. If there are fewer than `k` distinct words, return all of them. Return `[]` if there are no words or k is 0.

Examples:
{examples}

{trailer}""")

ALG['a8'] = dict(fn='unionArea', d='hard', examples=[
    ([[[0, 0, 2, 2], [1, 1, 3, 3]]], 7),
    ([[[0, 0, 4, 4], [1, 1, 2, 2]]], 16),
    ([[]], 0),
], prompt="""Write a JavaScript function `unionArea(rects)`.

`rects` is an array of up to 2,000 axis-aligned rectangles, each given as `[x1, y1, x2, y2]` with integer coordinates, `x1 < x2` and `y1 < y2`, and every coordinate between -10^9 and 10^9. Rectangles may overlap, touch, contain each other or be identical.

Return the total area of the region covered by at least one rectangle, as a number. The tests guarantee that the answer is at most 2^53 - 1, so it is exactly representable, but be careful that intermediate calculations stay exact.

Examples:
{examples}

{trailer}""")
