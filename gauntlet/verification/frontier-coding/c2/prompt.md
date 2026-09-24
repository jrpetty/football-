Write a JavaScript function `coverageArea(polygons, k)` that returns the exact area of the part of the plane covered by at least `k` of the given polygons.

Input:
- `polygons` is an array of m polygons, 1 <= m <= 80. Each polygon is an array of at least 3 vertices `[x, y]` with integer coordinates, -10^9 <= x, y <= 10^9. Its boundary consists of the edges from each vertex to the next one and from the last vertex back to the first.
- Every edge is horizontal, vertical, or diagonal at exactly 45 degrees (that is, |dx| = |dy| for a diagonal edge).
- Every polygon is simple: consecutive vertices are distinct, and its boundary never crosses, touches or overlaps itself, except that consecutive edges share their common vertex. Consecutive edges may be collinear (a vertex may lie in the middle of a straight side). The vertices may be listed clockwise or counter-clockwise.
- Different polygons may overlap, cross each other, share whole edges or parts of edges, touch at single points, contain one another, or be identical.
- `k` is an integer with 1 <= k <= m.
- The total number of vertices over all polygons is at most 1,500.

A point is covered by a polygon if it lies strictly inside that polygon (boundary points are not covered; this does not change any area). Compute the area of the set of points that are covered by at least k different polygons.

Return the area as an exact reduced fraction in a string `"p/q"`: p >= 0 and q >= 1 are integers with no common factor greater than 1, written in decimal without leading zeros or spaces; an integer area is written with q = 1 (e.g. `"18/1"`), and an empty region is `"0/1"`. The answer must be exact even when the area is far larger than 2^53.

Examples:
{examples}

{trailer}
