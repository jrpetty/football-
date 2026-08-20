# spec-mapper — parser agent log

Agent: `spec-mapper`. Remit: the missing layer between `src/parse/wikitable.ts`
(which aligns a specification table into rows) and the canonical `GpuRecord` /
`CpuRecord` shapes the reconciler merges.

## Read this first

**Wikipedia and every other specification source is egress-blocked in this
environment, so this mapping has never been run against a real page.** It is
tested against synthetic fixtures I wrote from memory of what those tables look
like. That is a deliberate, useful thing to build now — the column-identification
and unit-normalisation logic is the part that takes iteration, and it is far
easier to iterate on when the scaffolding, the tests and the reporting already
exist — but the correct summary of its status is:

> The shapes are hypotheses. The arithmetic, the reporting and the never-fabricate
> rules are real and enforced. Nothing here should be merged into the catalogue
> until it has been run against a real cached page and the report read by a human.

`scripts/parse.ts` says the same thing at the top of its output every time it runs.

## Files

| File | Lines | What it is |
|---|---|---|
| `src/parse/spec-mapper.ts` | 1,339 | The mapping layer (this agent's product) |
| `tests/spec-mapper.test.ts` | 710 | 107 tests |
| `tests/fixtures/nvidia-gpu-list.synthetic.html` | 143 | **Synthetic** Nvidia-style GPU list |
| `tests/fixtures/amd-gpu-list.synthetic.html` | 76 | **Synthetic** AMD-style GPU list |
| `tests/fixtures/intel-cpu-list.synthetic.html` | 85 | **Synthetic** Intel-style CPU list |
| `tests/fixtures/amd-cpu-list.synthetic.html` | 47 | **Synthetic** AMD-style CPU list |
| `scripts/parse.ts` | 169 | Wave 3 entry point: alias parser + spec parser |

`npx vitest run tests/spec-mapper.test.ts tests/wikitable.test.ts` → 121 passed.
`npx tsc -b --noEmit` → clean.

## What it does

`parseSpecPage(html, kind, ctx)` runs: unwrap MediaWiki JSON envelope → `extractTables`
→ `expandTable` → `gridToRecords` → `mapColumns` → `rowToGpu`/`rowToCpu` → fill-only
merge on duplicate ids → a report.

**Column identification.** 39 GPU rules across 15 fields and 30 CPU rules across 18 fields, each a regex over the
normalised header text with an optional `not` guard and a score. Every
(field, column) candidate is collected and assigned greedily, highest score first,
so one column serves exactly one field. The scores are what stop the near-misses:
"Memory clock" must not win the core-clock slot, "Memory Bus type" must not win
bus *width*, and "Processing power (GFLOPS)" must not be read as a *process* node.
Compound headers from colspan groups arrive pre-joined by `gridToRecords`
("Memory Bus width (bit)"), and the rules are written against that form.

**Unit normalisation.** `normaliseUnit(raw, unit)` takes the unit the *caller*
wants, not the unit in the cell: `('1.5 GHz', 'MHz')` → 1500, `('1,506 MHz','MHz')`
→ 1506, `('768 MB','GB')` → 0.75, `('4 × 512 KB','MB')` → 2, `('256-bit','bit')`
→ 256, `('2 TB/s','GB/s')` → 2000, `('2560:160:64','count')` → 2560,
`('May 27, 2016','date')` → `'2016-05-27'`. Ranges resolve to the upper bound
(`parseNumeric`'s existing behaviour — boost clocks are quoted that way).

Three heuristics fire only when a cell carries no unit at all, and each is safe
for the hardware in scope but not in general: a clock below 100 is GHz; a VRAM
figure of 128 or more is MB; an FP32 figure above 1000 is GFLOPS. They are
commented at the site.

**Ids and variants.** `slugify(vendor, name, variant)` reproduces the catalogue
convention — `nvidia-geforce-gtx-1060-6gb`, `amd-radeon-rx-480-8gb`,
`intel-arc-a770-16gb` (no doubled vendor), `intel-core-i7-2700k`,
`intel-uhd-770` (Intel's filler word "Graphics" dropped). `splitVariant` peels
the three variant axes the catalogue actually uses: memory size
("GTX 1060 6 GB" → `6GB`), memory technology ("GT 1030 DDR4" → `DDR4`) and a
short trailing parenthetical ("Titan X (Pascal)" → `Pascal`), while treating
"Limited Edition" as decoration and a comma-bearing parenthetical as description.

**Derived capability blocks.** The reconciler rejects a GPU with no
`caps.meshShaders`, and no specification table states it. So `deriveGpuCaps` /
`deriveCpuCaps` / `deriveDriverStatus` apply the rules already written down in
`agents/BRIEF.md` (Turing+/RDNA 2+/Arc → mesh shaders and DX 12_2, and so on),
keyed on the architecture names already used in `data/catalogue`. An architecture
the tables do not recognise yields **no** caps rather than a default — that record
is then rejected by the reconciler, which is the honest outcome. Every emitted
record's provenance note says these fields are rule-derived, not page-derived.

## Never fabricate — how it is actually enforced

- `isMissing` treats `""`, `N/A`, `?`, `—`, `Unknown`, `TBA`, `None`, `Varies` and
  friends as missing. Every unit returns `null` for them. There is a test that
  asserts `null` **and** `not.toBe(0)` for all ten units against eleven such cells.
- A cell quoting two alternatives ("3 GB or 6 GB", "1506/1683 MHz") resolves to
  `null` rather than picking one — but "8 GB GDDR6/GDDR6X" (one size, two memory
  technologies) still parses, which is why the detection is delimiter-anchored.
- A *name* covering two configurations ("GTX 1050 2 GB / 3 GB") skips the row with
  that reason, because splitting it correctly needs per-SKU specs the row does not
  contain.
- Each numeric field has a plausibility range. An out-of-range value is a parse
  failure wearing a number's clothes: it is dropped and written to `_conflicts`
  with the value and provenance, never into the field.
- Two internal consistency checks mirror the reconciler's own rules. If a stated
  FP32 figure disagrees with `shaders × boost × 2` by more than 8%, or a
  bus/bandwidth pair implies an impossible memory clock, the weaker figure is
  dropped to `_conflicts` and **the rest of the record survives**. Without this, a
  single bad cell costs the whole SKU at reconcile time.
- Rows without a usable name are skipped with a reason. So are section-divider
  rows (a colspan cell expanded across the row) and whole tables with no
  identifiable name column. Nothing is dropped silently: `skipped[]` names the row
  and the reason, `tables[]` records every table's headers and why it was or was
  not used, `columnsMissing` names every expected field no column matched.

## What it cannot do

1. **It has never seen real HTML.** Everything below follows from that.
2. **CPU `socket` and `memoryType` will usually be missing.** Both are *required*
   by the reconciler. On the real pages they typically live in a section heading
   or a separate platform table, not in the per-row columns. The mapper reads them
   when a column exists (and `expandTable` correctly carries a rowspan-merged
   socket down a whole table, which the Intel fixture exercises) but it does not
   invent them. Expect a large rejection count on the first CPU run — that is the
   design, not a bug, but it means CPU pages need a section-level platform map
   before they yield usable records.
3. **`architecture` is often absent on Nvidia-style pages.** The headings there
   are marketing series ("GeForce 10 series"), not architectures, and
   `detectArchitecture` deliberately refuses to pass an unrecognised string
   through. No architecture → no caps → reconciler rejection. A hand-written
   series→architecture map is the fix, and it is a small one.
4. **No cross-page merging.** PCI ids, physical dimensions, driver EOL dates,
   `igpuId` links, real upscaling support and iGPU/APU parts all come from other
   sources and are not attempted here.
5. **Intel marketing node names resolve to `null`.** "Intel 7" is a 10 nm-class
   process; reading the digit would write a false number. Recorded gap instead.
6. **AMD's "Game clock" column is intentionally unmapped.** It is neither base nor
   boost, and writing it into `boostClockMHz` would understate parts and then feed
   a wrong FP32 derivation.
7. **`formFactor` is assumed `desktop`** unless the name says mobile/laptop/Max-Q.
   Page selection is the real control here.
8. **Inherited hazard:** `extractTables`' non-greedy regex stops at the first
   `</table>`, so a table nested inside another (navboxes, infoboxes) truncates the
   outer one. Not a problem for the list tables themselves, but worth knowing when
   a table parses into nonsense.
9. **Inherited hazard:** `gridToRecords` keys rows by header *text*, so two columns
   with identical headers collapse and the later one wins. `mapColumns` is fed the
   same collapsed key list so indices stay consistent, but a table repeating a
   header (e.g. "Boost" under two different groups) will silently lose a column.

## Id round-trip against the existing catalogue — measured

I ran `slugify(vendor, brand, variant)` over every record already in
`data/catalogue/` and compared against the stored id:

- **GPUs: 236 / 265 round-trip.** 29 diverge.
- **CPUs: 436 / 442 round-trip.** 6 diverge.

The divergences are all cases where the seed agents abbreviated by hand, and they
group cleanly:

| Class | Count | Example |
|---|---|---|
| iGPU / APU parts with hand-shortened codenames | 20 | `intel-uhd-770-adl` vs `intel-uhd-770-alder-lake`; `amd-vega-8-cezanne` vs `amd-radeon-vega-8-ryzen-7-5700g` |
| Variant abbreviated by hand | 5 | `nvidia-geforce-gt-730-gddr5` vs `…-gddr5-64-bit`; `nvidia-geforce-gt-640` (variant `DDR3` omitted from the id) |
| "PRO" dropped from an AMD APU name | 6 | `amd-ryzen-7-4750g` vs `amd-ryzen-7-pro-4750g` |
| Cooling variant folded into the base id | 2 | `amd-radeon-rx-vega-64` (variant `Air Cooled`) |
| Seed-side oddity | 1 | catalogue `amd-radeon-radeon-vii`; slugify yields the cleaner `amd-radeon-vii` |

**This matters** because a divergent id looks like a *new part* to the reconciler,
not an update — you would get two records for one SKU, and `duplicates` in
`data/reconcile-report.json` would not catch it. The fix is a small hand-maintained
id-alias map applied after `slugify`, which should be written **with the real
parsed output in hand** rather than guessed at now. Note that the practically
relevant subset for the desktop discrete lists is small: about 8 parts, mostly
GT 730/740/640 memory variants and Vega 64 cooling variants.

## Exactly what to do when real HTML is first available

In order. Do not skip to step 6.

1. `npm run harvest` in an unrestricted environment, then `npx tsx scripts/parse.ts`.
   It writes `agents/out/parsed-<source>.json` per cached page and prints per-source
   counts. It does not touch the catalogue.
2. Open the `parseReport.tables` block of each output **first**. It lists every
   table's real header strings. This is the ground truth the alias tables were
   guessed against. Diff it against `GPU_RULES` / `CPU_RULES` and add what is
   missing — this is expected to be the bulk of the work, and it is a
   ten-lines-at-a-time job, not a redesign.
3. Check `parseReport.columnsMissing`. A field missing on *every* table means a
   header vocabulary I did not anticipate, not an absent column.
4. Check tables reported `used: false`. "No model-name column identified" on a real
   specification table means the name rules need a new alias; on a legend or
   navbox it is correct behaviour.
5. Sample ten records against the page by eye, specifically: clocks (the sub-100
   GHz heuristic), memory size (the ≥128 MB heuristic), FP32 (GFLOPS vs TFLOPS),
   and dates. A systematic factor-of-1000 error is the most likely first bug and
   the easiest to miss in aggregate.
6. Count `_conflicts` across the output. A *high* count is a symptom of a
   mis-mapped column (shaders reading a TMU count, say), not of bad source data.
   Investigate before accepting.
7. Write the id-alias map described above, using the measured divergence list.
8. Only now run `npm run reconcile`, and read `data/reconcile-report.json`:
   rejections, duplicates, and `unverifiedAgainstPciRegistry` together will show
   whether the mapping produced parts that actually exist.
9. Expect CPU pages to need a section-level socket/memory map (point 2 of "cannot
   do") before they produce accepted records at all.

## Changes outside my own files

One, minimal, and it fixes a live bug: `scripts/parse-aliases.ts` guards its
`main()` with `process.argv[1].endsWith('parse-aliases.ts')`, so the previous
`scripts/parse.ts` — which was `import './parse-aliases.ts'` — **ran nothing at
all** when invoked as `npm run parse`. I exported `main` (one word) and call it
explicitly. Direct invocation of `parse-aliases.ts` still self-runs exactly as
before. I regenerated `data/aliases/pci-devices.json` while testing and restored
it, because re-running it rewrites `retrievedAt` to today for a cache fetched on
2026-08-16 — a separate pre-existing provenance bug worth fixing on its own.

## What I am least sure about

- Whether real header text is close enough to my fixtures for the alias tables to
  hit at all on the first run. My guess is 60–80% of columns, with the name, date,
  clock and memory groups landing and the long tail (transistor counts, die sizes,
  fill rates, per-vendor oddities) missing — but that is a guess, and the report
  exists so it does not have to stay one.
- Whether the real pages split one part across sibling tables often enough for the
  fill-only merge to matter, or whether it will mostly fire on genuine duplicates
  (rebadges) that ought to be reported rather than merged.
- The upscaling lists in `deriveGpuCaps` mirror the seed catalogue's conventions,
  which are themselves inconsistent between the seed agents (Nvidia parts list
  `xess`/`tsr`, AMD parts do not). I matched the existing data rather than
  imposing a new rule, but this field should come from a real capability source.
