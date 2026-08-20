# harness - measurement harness agent log

## What I produced

Extended `rigcheck/harness/` from a single-game PresentMon runner into a full
measurement lane. Nothing outside `harness/` was touched except this log.

| Path | Status |
|---|---|
| `harness/run-suite.ps1` | new - suite runner, resumable, one CSV per session |
| `harness/detect-hardware.ps1` | new - machine configuration to JSON, mapped to catalogue ids |
| `harness/README.md` | new - operator guide |
| `harness/suites/core-12.json` | new - the 12 `coreLoop` games |
| `harness/lib/rigcheck-common.ps1` | new - the shared protocol, extracted |
| `harness/run-benchmark.ps1` | modified - now uses the shared protocol; behaviour preserved, four bugs fixed |
| `harness/.gitignore` | new - `out/`, PresentMon binaries |

All PowerShell 5.1 compatible: no ternary, no `??`, no `?.`, no
`ConvertFrom-Json -AsHashtable`, no `ForEach-Object -Parallel`, no `$IsWindows`,
no `Join-Path -AdditionalChildPath`, no `Split-Path -LeafBase`, no `&&`/`||`.

## The decision worth arguing about: I refactored the working script

The brief said extend `run-benchmark.ps1`, not replace it. I read that as "keep
its protocol and discipline", and it left a choice: duplicate the protocol inside
the suite runner, or extract it.

I extracted it to `lib/rigcheck-common.ps1` and pointed both runners at it. Two
implementations of "what a measurement means" in a project whose entire thesis is
that non-comparable fixtures are worse than no fixtures would be the wrong trade
- they would drift, and the resulting rows would look comparable and not be. The
edits to `run-benchmark.ps1` are confined to: the doc block, a dot-source, the
`Find-PresentMon -HarnessRoot` call, replacing the inline capture loop with
`Invoke-CaptureRuns` + `Get-RunSummary`, and replacing the inline row literal with
`New-ManualCsvRow`. Its parameters, prompts, console output and CSV semantics are
unchanged. The cost is that the harness is now a directory to copy rather than one
file; the README says so in two places.

## Bugs found in the existing script while extracting it

These were latent in `run-benchmark.ps1` and are fixed in the shared library.

1. **The median was wrong for some odd run counts.** `$s[[int]($s.Count / 2)]`
   uses PowerShell's banker's rounding: `[int]1.5` is `2`, so a three-element set
   returned its **maximum**, not its median. Correct for counts 2, 4, 5, 9; wrong
   for 3 and 7. With the default `-Runs 3` there are two usable runs, so the even
   path ran and the bug stayed hidden; `-Runs 4` would have hit it. Now
   `[int][math]::Floor($n / 2)`, with unit tests for counts 1-7.

2. **A stock PresentMon 2.x capture would have failed outright.** The frametime
   column list was `MsBetweenPresents`, `msBetweenPresents`,
   `MsBetweenDisplayChange` - all PresentMon 1.x names. PresentMon 2.x emits
   `FrameTime` by default and only restores the old names under `--v1_metrics`.
   The script's own doc block claimed it "targets 2.x". `FrameTime` and
   `DisplayedTime` are now accepted, in a documented order of preference, and the
   column actually used is recorded in `source_note` as `col=<name>`.

3. **`ram_channels` could emit a value the importer rejects.** Channels were
   counted as distinct SMBIOS `BankLabel` values, which is slot count, not channel
   count, and can be any integer. `import-manual.ts` accepts only 1, 2, 4 or 8, so
   a three-stick machine produced a row rejected at import. Channels are now
   derived from channel letters in `DeviceLocator` and clamped to the legal set.

4. **A capture without `-ProcessName` averaged across every presenting process.**
   PresentMon with no filter records the game, the launcher, overlays and the
   desktop compositor; `Get-FrameStats` consumed all of it. That is silent
   corruption - a plausible-looking number that is a blend of a game and DWM.
   It now groups by process, uses the dominant one, and warns naming what it
   discarded.

Also fixed while in there: `$args = @(...)` assigned to PowerShell's automatic
`$args` variable (renamed `$pmArgs`), and `Find-PresentMon` looked only for
`PresentMon.exe` when the stock 2.x download is `PresentMon-2.x.y-x64.exe`.

## What I verified, and how

No Windows in this environment, so I installed PowerShell 7.4.6 and tested
everything that is not WMI or ETW.

- **Parse.** All four scripts parse clean under
  `[System.Management.Automation.Language.Parser]::ParseFile`. This catches
  syntax, not semantics, and PS7 accepts a superset of 5.1, so I also audited by
  hand for PS7-only constructs (list above).
- **Unit tests.** `Get-Median` (odd/even/single/unsorted), `Get-RamChannelGuess`
  (letters, no letters, tri-channel clamp, empty), `Get-OrDefault`,
  `ConvertTo-Hashtable`, `Get-DriveLetterFromPath`, `ConvertTo-CatalogueSlug`,
  `Get-MarketingDriverVersion`, `Resolve-CatalogueId`, `Clean-Text`.
- **Frametime statistics** against synthetic PresentMon CSVs in both the 1.x and
  2.x column vocabularies, including a two-process capture, a too-short capture
  and an unrecognised column set.
- **End to end.** A sandbox copy of the harness with `Assert-Admin` and
  `Get-HardwareProfile` stubbed, plus a fake `PresentMon.exe` emitting synthetic
  frametimes with a deliberately slow run 1, driven through `run-suite.ps1` and
  `run-benchmark.ps1` over piped stdin. Exercised: capture, skip with a reason,
  quit and resume, `-Only` redo, spread flagging, the different-machine guard,
  bad `-Only` id, unknown suite name, and row replacement.
- **`detect-hardware.ps1` end to end** with every WMI class stubbed, checking the
  full document assembly and report - including the P/E inference producing 8P+8E
  for an i7-13700K and the NVIDIA INF-to-branch derivation producing 560.94 from
  32.0.15.6094.
- **Against the real importer.** Every emitted CSV was fed to
  `scripts/import-manual.ts` with the real catalogue: accepted, zero rejections,
  weight 0.9 (docked only for `game_build`, which the operator supplies).
- **Against the real catalogue.** `Resolve-CatalogueId` confirms
  `amd-ryzen-5-3600`, `intel-core-i7-13700k`, `amd-radeon-rx-6800-xt` and
  `nvidia-geforce-rtx-4070-super` exactly, and correctly refuses to auto-confirm
  `nvidia-geforce-rtx-3060` because the catalogue splits it into 8GB and 12GB.

Two real bugs were caught by the end-to-end runs that no amount of reading would
have found: `return ,@($arr)` combined with `@(...)` at the call site
double-wrapped the results array, so the summary tried to cast an array to
`[double]`; and vendor detection by substring matched **"Intel Corporation" as
AMD**, because "Corpor**ati**on" contains "ati". Both fixed, both now tested.

## Untested assumptions - what a first run on real hardware must check

These are the things I could not verify and would not bet on:

1. **PresentMon's exact flags.** `--output_file --timed --terminate_after_timed
   --stop_existing_session --process_name` are carried over unchanged from the
   original script. I could not confirm all five exist in every 2.x build.
2. **Whether PresentMon 2.x really emits `FrameTime`.** I am confident from the
   2.x metric set, but if a build emits something else the error message lists the
   columns it saw, which is the diagnostic.
3. **The whole WMI surface.** `Win32_Processor`, `Win32_VideoController`,
   `Win32_PhysicalMemory`, `Win32_PhysicalMemoryArray`, `Win32_OperatingSystem`,
   `Win32_BaseBoard`, `Win32_BIOS`, `Win32_ComputerSystem`, `Win32_PnPEntity`,
   `root\wmi WmiMonitorID` were all exercised against fakes only.
4. **The GPU VRAM registry route.** Reading
   `HardwareInformation.qwMemorySize` from the display class key is the standard
   way around `AdapterRAM`'s 32-bit ceiling, but the subkey-to-adapter match (by
   `DriverDesc`, falling back to a `VEN_xxxx&DEV_xxxx` fragment) is untested. It
   degrades to `AdapterRAM` with a warning that the figure is wrong above 4GB.
5. **The NVMe PCIe link-speed probe.** `Get-PnpDeviceProperty` with
   `DEVPKEY_PciDevice_CurrentLinkSpeed` (and the raw GUID form) is speculative,
   including the assumption that the value is the enum 1..5 rather than a raw
   GT/s figure. Fully guarded; on failure it reports `nvme-gen3` and says the
   generation is a guess.
6. **The chipset heuristic.** Regex over LPC/eSPI controller names, falling back
   to the board model string. Verified against strings I wrote; will produce a
   plausible-looking wrong answer on a board whose model contains a
   chipset-shaped token. Always flagged as best effort.
7. **`processName` for all twelve games.** Written from knowledge and explicitly
   marked in the suite file as verify-on-first-run. A wrong name fails loudly
   (no frames captured) rather than silently, and the runner checks whether the
   named process is running before it warms up.
8. **`Read-Host` behaviour at end-of-stream** differs between the PS7 Linux
   console I tested on and a real Windows console. It is handled (a closed stream
   is treated as quit) but a Windows operator will never see that path.
9. **Elapsed-time estimates** assume the game launches instantly. The runner says
   "budget double".

## Deliberate omissions

- **No manual-entry mode.** Several core-loop games print their own average FPS
  (Shadow of the Tomb Raider even splits CPU-bound and GPU-bound), and Factorio's
  meaningful figure is UPS from its headless `--benchmark`, which PresentMon
  cannot see because it presents no frames. Letting the operator type those in
  would produce rows with no run count, no median and no spread sitting beside
  rows that have all three. The suite keeps one protocol; Factorio carries a
  `caution` explaining that its row saturates at its 60 UPS cap and that skipping
  it is a legitimate choice, and SOTTR's split figures are directed to
  `source_note`.
- **No parallelism, no automation of the game itself.** The operator prompt is
  the design: nothing here can know that the benchmark scene is actually running.
- **`processorId`, machine name and disk serials are deliberately not recorded.**
  They identify the operator's machine and buy the model nothing.
- **The suite ships one file.** `core-12.json` mirrors the catalogue exactly:
  same 12 ids, same order, same names, same `builtInBenchmark` flags, and every
  `api` value is one the catalogue lists for that game. Verified programmatically.

## Note on the repository state

A concurrent session committed `d538230 Add the spec-parser mapping layer and the
multi-game harness` while I was working. It swept up my then-in-progress harness
files alongside its own unrelated changes. Nothing was lost - the working tree is
consistent with `HEAD` - but the commit message describes work that is only
partly what those files are, and `harness/README.md`,
`harness/detect-hardware.ps1` and `harness/.gitignore` were still untracked when
I finished.
