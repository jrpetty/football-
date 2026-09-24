Write a JavaScript function `minFleetCost(depot, jobs, truckCost, maxTrucks)` that plans delivery trucks at minimum cost.

Input:
- `depot` is `[x, y]`, the location where every truck starts and finishes.
- `jobs` is an array of n jobs (0 <= n <= 400). Job i is `[px, py, qx, qy, start, end]`: a truck must be at the pickup point (px, py) at time `start` at the latest, the job then keeps the truck busy until time `end`, and at time `end` the truck is at the drop-off point (qx, qy). All coordinates are integers from 0 to 1,000,000; `start` and `end` are integers with 0 <= start < end <= 2,000,000,000.
- `truckCost` (integer, 0 to 1,000,000,000) is the fixed cost of every truck that is used; `maxTrucks` (integer, 1 to 1,000) is the largest number of trucks that may be used.

Rules:
- The distance between points (x1, y1) and (x2, y2) is |x1 - x2| + |y1 - y2|. Driving a distance d takes exactly d time units and costs d. Trucks may wait anywhere, for any length of time, for free.
- A truck performs a sequence of one or more jobs j1, j2, ..., jm. It leaves the depot whenever it likes, drives to the pickup point of j1 and must arrive no later than start(j1). After finishing a job at its drop-off point at time end, it drives to the pickup point of the next job and must arrive no later than that job's start, and so on. After its last job it drives back to the depot (there is no deadline for that). Consequently, job b can be done directly after job a by the same truck if and only if end(a) + distance(drop-off of a, pickup of b) <= start(b).
- The cost of a used truck is `truckCost` plus the total distance it drives while not performing a job: depot to the pickup of j1, each drop-off to the next pickup, and the last drop-off back to the depot. What happens during a job (between its pickup and its drop-off) costs nothing.
- Every job must be performed by exactly one truck, and at most `maxTrucks` trucks may be used. A truck that performs no job is not used and costs nothing.

Return the minimum possible total cost, or -1 if the jobs cannot all be performed with at most `maxTrucks` trucks. With no jobs, return 0. The answer always fits exactly in a JavaScript number.

Examples:
{examples}

{trailer}
