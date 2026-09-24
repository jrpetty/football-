// Plausible greedy: process jobs by start time; give each job to the available truck with the cheapest empty drive,
// or start a new truck if that is cheaper (or no truck can make it); -1 if more than maxTrucks trucks are needed.
function minFleetCost(depot, jobs, truckCost, maxTrucks) {
  const d = (a, b) => Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]);
  const order = jobs.map((j, i) => i).sort((a, b) => jobs[a][4] - jobs[b][4]);
  const trucks = []; let cost = 0;
  for (const i of order) {
    const [px, py, qx, qy, s, e] = jobs[i];
    let best = -1, bestC = truckCost + d(depot, [px, py]);
    trucks.forEach((t, k) => { const dd = d(t.pos, [px, py]); if (t.free + dd <= s && dd < bestC) { bestC = dd; best = k; } });
    if (best < 0 && trucks.length >= maxTrucks) {
      trucks.forEach((t, k) => { const dd = d(t.pos, [px, py]); if (t.free + dd <= s && (best < 0 || dd < bestC)) { bestC = dd; best = k; } });
      if (best < 0) return -1;
    }
    cost += bestC;
    if (best < 0) trucks.push({ pos: [qx, qy], free: e }); else { trucks[best].pos = [qx, qy]; trucks[best].free = e; }
  }
  for (const t of trucks) cost += d(t.pos, depot);
  return cost;
}
