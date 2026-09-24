Write a JavaScript function `palindromeRepeats(parts)`.

The input describes a string s in run-length form: `parts` is a non-empty array of pairs `[text, times]`, where `text` is a non-empty string of lowercase letters `a` to `z` and `times` is a positive integer. s is the concatenation, in order, of each `text` repeated `times` times; for example `[["ab", 3], ["c", 1]]` describes `"abababc"`. The length n of s is between 1 and 200,000 (the pairs can describe it much more compactly, e.g. `[["a", 200000]]`).

Definitions:
- A palindrome is a non-empty string that reads the same forwards and backwards.
- An occurrence of a string t in s is a start index i (0-based) with `s.slice(i, i + t.length) === t`. Occurrences may overlap.
- A palindrome t is *doubled* if it has two occurrences i < j that do not overlap, i.e. j >= i + t.length.

Return an array `[count, longest, total]`, where:
- `count` is the number of distinct doubled palindromes;
- `longest` is the length of the longest doubled palindrome, or 0 if there is none;
- `total` is the sum, over all distinct doubled palindromes t, of the number of occurrences of t in s (all occurrences count, including overlapping ones).

All three values fit exactly in a JavaScript number. The tests include strings of length 200,000 with an enormous number of palindromic occurrences (for example 200,000 copies of one letter), and deep recursion overflows the call stack in the test environment.

Examples:
{examples}

{trailer}
