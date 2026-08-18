# RIGCHECK catalogue agent brief

Read this in full before producing output. The canonical TypeScript definitions are
in `rigcheck/src/core/types.ts` — your JSON must satisfy `GpuRecord`, `CpuRecord`
or `GameRecord` exactly.

## Environment constraint you must respect

Wikipedia, TechPowerUp, pci-ids.ucw.cz, OpenBenchmarking and vulkan.gpuinfo.org
are **blocked by this session's egress policy** (403 at the proxy). Do not attempt
to fetch them, do not look for mirrors, do not route around the block. Only
`raw.githubusercontent.com` and package registries are reachable.

Therefore you are producing a **seed catalogue from your own knowledge**, which the
harvest pipeline will later overwrite when run in an unrestricted environment.
This makes accuracy discipline critical:

- Every record you emit gets `provenance` id `model-knowledge`, which is already
  defined in the provenance table. Do not invent other provenance ids.
- **If you are not confident in a field, set it `null`. Never guess a number.**
  A null is a coverage gap we report honestly. A wrong number silently corrupts
  the fitted model and is far worse.
- Prefer to omit an entire SKU you are unsure exists over inventing it.
- Do not pad the list to hit a count. Report the shortfall in your log instead.

## Internal consistency rules (a validator enforces these — violations are rejected)

GPUs:
- `fp32TFLOPS` must be within 8% of `shaders * boostClockMHz * 2 / 1e6`.
  If you are unsure of shaders or clock, null all three rather than emit an
  inconsistent triple.
- `memBandwidthGBs` must be within 8% of `memBusBits * effectiveMemClockMTs / 8 / 1000`.
- Within one family, higher-tier parts must not have lower shader counts.
- `vramGB` must be a real configuration for that part. Where one marketing name
  covers two configurations (GTX 1060 3GB/6GB, RTX 3060 8GB/12GB, RX 5500 XT
  4GB/8GB), emit **two separate records** with distinct `id` and `variant`.

CPUs:
- `threads >= cores`. For hybrid Intel parts, `cores = pCores + eCores` and
  `threads = pCores*2 + eCores`.
- `socket` and `memoryType` are REQUIRED and must never be null — the inventory
  optimiser is unsound without them.
- `vcache: true` only for parts that genuinely have stacked cache.

## Capability gates — get these right, they drive WILL_NOT_RUN

- `meshShaders`: true for Turing (RTX 20 / GTX 16) and later, RDNA 2 and later,
  Intel Arc. False for Pascal, Maxwell, Kepler, GCN, RDNA 1, and all pre-Xe iGPUs.
  This is the single most load-bearing gate in the product (Alan Wake 2 etc).
- `rayTracing`: hardware RT only. Turing+, RDNA 2+, Arc.
- `dxFeatureLevel`: '12_2' for Turing+/RDNA2+/Arc, '12_1' for Pascal/Maxwell 2 and
  RDNA 1/Vega, '12_0' for GCN 1.1+, '11_0' for Fermi and older.
- `shaderModel`: '6_7' for DX12U parts, '6_5'/'6_0' for older DX12, '5_1' for DX11.
- `driverStatus`: 'eol' for Kepler and older Nvidia, GCN 1.0-3 AMD; 'legacy' for
  Maxwell/Pascal and GCN 4/5; 'maintenance' / 'current' for recent parts.
  Set `driverEolDate` where you are confident of the date, else null.

## Output format

Write exactly one file: `rigcheck/agents/out/<your-agent-id>.json`

```json
{
  "agentId": "gpu-nvidia",
  "kind": "gpu",
  "provenance": {
    "model-knowledge": {
      "id": "model-knowledge",
      "source": "Model knowledge (seed)",
      "url": "",
      "licence": "n/a",
      "retrievedAt": "2026-08-16",
      "note": "Seed pending harvest; Wikipedia and vendor spec sources egress-blocked in this environment."
    }
  },
  "records": [ /* GpuRecord[] | CpuRecord[] | GameRecord[] */ ],
  "expectedCount": 420,
  "actualCount": 388,
  "gaps": ["..."],
  "uncertainties": ["..."]
}
```

Every record needs `_prov`, mapping each populated field name to `["model-knowledge"]`.
To keep the file manageable you may instead emit `"_prov": {"*": ["model-knowledge"]}`
meaning "all fields from this source" — the loader expands it.

Also write `rigcheck/agents/log/<your-agent-id>.md` covering: what you produced,
expected vs actual counts and **why** the gap exists, which fields you most often
had to null, and what you are least sure about. Be specific — "parsed 340 records"
is useless without "of an expected ~380; 40 missing because X".

## Hard rules

- Write ONLY your two files. Never modify another agent's output.
- Do not edit anything in `rigcheck/src/`.
- Do not run `npm install` or any build.
- Emit valid JSON. Verify with `node -e "JSON.parse(require('fs').readFileSync('<file>','utf8'))"` before finishing.
