Write a JavaScript function `minTowerCost(parent, cost, reach, need)`.

A tree has n vertices numbered 0 to n - 1 (1 <= n <= 5,000). `parent[i]` is the parent of vertex i, or -1 for the root; exactly one vertex is the root and the parent links always form a single tree, but a parent's number may be larger or smaller than its child's. The distance between two vertices is the number of edges on the path between them (edges can be travelled in both directions, so the path may go up and then down).

You may build radio towers on any set of vertices. A tower on vertex t costs `cost[t]` (an integer from 1 to 1,000,000) and covers every vertex u whose distance from t is at most `reach[t]` (an integer from 0 to 20); in particular every tower covers its own vertex. `need` is a string of n characters, each `'0'` or `'1'`: vertex i must be covered by at least one tower if `need[i]` is `'1'`; vertices marked `'0'` may stay uncovered (but towers may still be built on them).

Return the minimum total cost of a set of towers that covers every vertex marked `'1'` (return 0 if no vertex is marked `'1'`). The tree can be a path of 5,000 vertices.

Examples:
{examples}

{trailer}
