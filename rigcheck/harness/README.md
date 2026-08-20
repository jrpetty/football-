# RIGCHECK measurement harness

Everything in this directory exists to turn a real machine into rows in
`data/manual/`. The model's long-term accuracy depends on that lane being fed
with measurements taken the same way every time, on machines whose configuration
is known exactly. Both halves of that sentence are load-bearing: a fast, sloppy
measurement is worse than no measurement, because the calibrator cannot tell it
from signal.

| File | What it is |
|---|---|
| `run-benchmark.ps1` | One game, one settings combination, one row. |
| `run-suite.ps1` | A whole suite of games in one operator session, resumable, one CSV. |
| `detect-hardware.ps1` | Records what this machine actually is, and maps it to catalogue ids. |
| `lib/rigcheck-common.ps1` | The protocol itself. Shared, so the runners cannot drift apart. |
| `suites/core-12.json` | The twelve `coreLoop` games from `data/catalogue/games.json`. |
| `out/` | Raw PresentMon captures, session state, and the CSVs you import. |

---

## Read this before the first run

**None of these scripts has ever been executed on real hardware.** They were
written and reviewed on Linux, where Windows Management Instrumentation, ETW and
PresentMon do not exist. What *was* verified:

- every script parses cleanly under the PowerShell language parser;
- the platform-independent logic - frametime statistics, median, spread, CSV
  emission and append, JSON state, resume, catalogue-id matching, the CPU/GPU
  name slugger, the NVIDIA driver-version derivation - is covered by tests that
  run and pass;
- `run-suite.ps1` and `run-benchmark.ps1` were driven end to end against a fake
  PresentMon that emits synthetic 1.x-style and 2.x-style CSVs;
- the CSV they produce was fed to the real `scripts/import-manual.ts` and is
  accepted with no rejections.

What could **not** be verified, and what you should therefore check on the first
run, in this order:

0. **Be in this directory first.** PowerShell opens in `C:\WINDOWS\system32`,
   where these scripts are not. `cd` to wherever you unpacked `harness/` before
   anything below, or every command fails with a path error that looks like a
   broken script.
1. **PowerShell version.** `$PSVersionTable.PSVersion` - anything 5.1 or later.
2. **Execution policy.** Scripts are blocked by default. In the shell you are
   about to use: `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass`.

   If you would rather pass the policy per-command, the program name is part of
   the command and is not optional:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\detect-hardware.ps1
   ```

   Typing only `-ExecutionPolicy Bypass -File .\detect-hardware.ps1` makes
   PowerShell read `-ExecutionPolicy` as a command name and answer `The term
   '-ExecutionPolicy' is not recognized as the name of a cmdlet`. That error is
   about the missing `powershell`, not about the script.
3. **`.\detect-hardware.ps1`.** It needs no Administrator and no PresentMon, so
   it is the cheapest possible test of the shared library and of every WMI query
   in the harness. **Read its output properly** - see "Best-effort values" below.
4. **A throwaway benchmark run.** Before committing to an hour-long suite:
   `.\run-benchmark.ps1 -GameId counter-strike-2 -Resolution 1080p -Preset high -Runs 2 -WarmupSeconds 5 -CaptureSeconds 10`.
   This proves the PresentMon path end to end in about 30 seconds. Delete the
   resulting row; it is not a valid measurement.
5. **The raw capture.** Open the newest `out/raw_*.csv` and confirm it has a
   frametime column, and that the `col=` value in the emitted `source_note` is
   `MsBetweenPresents` or `FrameTime` (see the column note in Troubleshooting).
6. **The import.** Copy the emitted CSV into `rigcheck/data/manual/` and run
   `npm run import:manual`. Expect `1 accepted, 0 rejected`.

The most likely places for a first-run failure are the storage and PCIe-link
probes in `detect-hardware.ps1` (all wrapped in `try`/`catch`, so they should
degrade to a warning rather than a crash) and the exact PresentMon command-line
flags, which vary slightly by build.

---

## What to install

**PresentMon 2.x**, from Intel's PresentMon releases. Take the command-line
executable, not the GUI overlay.

Put it in one of:

- this directory, as `PresentMon.exe`;
- this directory under whatever name it shipped with - the harness globs
  `PresentMon*.exe`, so the stock `PresentMon-2.3.0-x64.exe` works unrenamed;
- `harness/tools/`;
- anywhere on `PATH`.

Nothing else is required. PresentMon reads ETW events that the graphics stack
already emits; there is no injection, no overlay, and no game modification.

Copy the **whole `harness/` directory** to the test machine, not individual
scripts. All three runners load `lib/rigcheck-common.ps1`, and `run-suite.ps1`
also needs `suites/`.

## Why it must run as Administrator

PresentMon collects frame data by opening an **ETW (Event Tracing for Windows)
session**. Creating a kernel ETW session is a privileged operation; without
elevation the session cannot start.

The failure mode is what makes this worth a hard check rather than a note:
unprivileged PresentMon does not necessarily error out loudly. It can produce an
empty or near-empty CSV, which downstream looks like "the game presented almost
no frames" rather than "the tool was not allowed to look". `Assert-Admin` runs
before anything else in `run-benchmark.ps1` and `run-suite.ps1` for that reason.

`detect-hardware.ps1` does **not** need Administrator - every source it reads is
readable by a normal user.

---

## The run protocol, and why each part of it exists

For every game, at fixed settings:

```
  for each of N runs:
      warm up for W seconds          (default 45)
      capture for C seconds          (default 60)
  discard run 1
  report the median of the rest
  warn if run-to-run spread exceeds 5%
```

**The warm-up is not padding.** Two things happen in the first tens of seconds
that have nothing to do with steady-state performance:

- *Shader compilation.* Modern DX12 and Vulkan titles compile pipeline state
  objects on demand. On a fresh driver, a fresh game build or a fresh machine,
  the first minute of play is dominated by that compilation and its stutter.
  Capturing through it measures the shader compiler.
- *Clocks and thermals settling.* A cold GPU boosts into a higher bin than it
  can sustain, and fan curves take time to respond. A short capture started
  immediately measures the boost bin, not the number the machine will hold. This
  cuts both ways on laptops, where sustained clocks can be far below the opening
  ones.

**Run 1 is discarded** because even after a warm-up it is consistently the
outlier: shader caches are still filling, the working set is still being paged
in, and Windows is often still doing first-launch work for the process. Keeping
it would drag every result down by an amount that varies by machine - which is
exactly the kind of *systematic* error a model cannot recover from.

**The median, not the mean.** A background task that spikes for two seconds
inflates one run. A mean absorbs that spike into the reported figure; a median
throws it away. Note the arithmetic honestly: with the default `-Runs 3` you
have two usable runs, and the median of two values *is* their mean. If you want
the median to actually do its job, use `-Runs 4` or `-Runs 5`. Three is the
default because it is the point where the time cost is still tolerable across a
twelve-game suite.

**The 5% spread warning** is the harness telling you the *environment* was not
controlled. It compares the fastest and slowest usable run against the reported
figure. Above 5%, the number is not reproducible, and a non-reproducible number
in a fixture set is worse than a missing one. Do not import a flagged row without
either re-running it or explaining the spread in `source_note`.

**Everything that changes what a measurement means is recorded**: resolution,
preset, upscaling tech and quality, frame generation, RT tier, API, game build,
driver version, memory configuration and storage class. The importer refuses to
form a comparison edge between two rows whose hard axes differ, so an unrecorded
axis does not merely lose information - it silently creates a false comparison.

---

## Running a single game

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass   # once per shell

.\run-benchmark.ps1 `
  -GameId cyberpunk-2077 `
  -Resolution 1440p `
  -Preset high `
  -CpuId amd-ryzen-7-5800x3d `
  -GpuId nvidia-geforce-rtx-3060-12gb `
  -ProcessName Cyberpunk2077.exe `
  -GameBuild 2.13
```

It prints the detected hardware, waits for you to get the scene running, then
runs the protocol and writes one row to `out/rigcheck_<timestamp>.csv`.

Useful switches: `-Runs`, `-WarmupSeconds`, `-CaptureSeconds`,
`-UpscalingTech`/`-UpscalingQuality`, `-FrameGen`, `-RtTier`.

`-CpuId` and `-GpuId` are technically optional and practically mandatory: rows
without them are rejected by the importer. See "Mapping hardware to catalogue
ids".

## Running a suite

```powershell
.\run-suite.ps1 -Suite core-12 -Resolution 1080p -Preset high `
  -CpuId amd-ryzen-7-5800x3d -GpuId nvidia-geforce-rtx-3060-12gb
```

For each game it shows the settings, the scene instructions and any caution from
the suite file, then waits:

```
   Launch the game and get the scene running, then [Enter]=ready, s=skip, q=quit:
```

- **Enter** - run the protocol on this game.
- **s** - skip it. You are asked for a reason; blank means "not installed". The
  game is recorded as skipped and the suite carries on.
- **q** - stop. State is saved; nothing is lost.

Every result is appended to **one CSV for the session**, and state is written
after every game. If the machine crashes, a game refuses to launch, or you close
the window, re-run the exact same command and it picks up where it stopped.

At the end it prints a summary table - game, avg, 1% low, spread - and flags
every run whose spread exceeded the threshold.

| Switch | Effect |
|---|---|
| `-ListSuites` | List the suites available and exit. |
| `-Only a,b` | Run only these game ids, **even if already captured**. This is how you redo a flagged game: the old row is replaced, not duplicated. |
| `-Skip a,b` | Exclude these game ids. |
| `-RetrySkipped` | Also re-offer games previously skipped. |
| `-Fresh` | Archive the existing state and CSV for this session and start over. |
| `-SessionId` | Name the session explicitly. Default is `<suite>_<resolution>_<preset>`. |
| `-HardwareJson` | Take memory, storage, driver and confirmed catalogue ids from a `detect-hardware.ps1` file. |
| `-GamesPath` | Where the games are installed, so the storage class is the games drive rather than the system drive. |
| `-SpreadWarnPercent` | Threshold for the spread flag. Default 5. |

Two guards will stop you, deliberately:

- Resuming a session whose recorded suite, resolution or preset differs from what
  you asked for. Mixing settings inside one CSV is how a fixture set gets quietly
  corrupted; use `-SessionId` or `-Fresh`.
- Resuming a session that was started on a **different machine**. Two rigs in one
  CSV is the same problem, worse.

### One session is one settings combination

The `core-12` defaults are a native, no-upscaling, no-RT, no-frame-generation
baseline at 1080p/high. That is the only pass that is comparable across every
vendor and generation. An RT pass or an upscaled pass is a **separate session**
with its own `-SessionId`, not a mixture inside one.

---

## Detecting hardware, and mapping it to catalogue ids

```powershell
.\detect-hardware.ps1
.\detect-hardware.ps1 -GamesPath D:\SteamLibrary -RamCl 30 -RamTrcd 38 -RamTrp 38 -RamTras 96
```

Writes `out/hardware-<timestamp>.json`: CPU (name, cores, threads, inferred P/E
split, clocks), GPU (name, VRAM, driver version), memory (total, channels, speed,
type, per-module detail, timings if you supply them), storage class of the system
drive and the games drive, OS build, primary display resolution and refresh rate,
motherboard model and chipset.

### Best-effort values

The script prints a block at the end listing **every field it had to guess and
why**. Read it. The ones that bite:

- **Memory channel count.** Inferred from the channel letters in the SMBIOS
  device locators (`DIMM_A1`, `Controller0-ChannelA-DIMM0`). When those are
  absent the script *guesses* dual channel and says so loudly. Slot count is not
  channel count: a four-slot consumer board with four sticks is still dual
  channel, and a HEDT board may be quad or octa. Single versus dual channel is a
  large effect on CPU-bound results, so **verify this in your BIOS** rather than
  trusting the guess.
- **Memory timings.** Not obtainable. SMBIOS carries no timing fields, and real
  timings live in SPD registers behind the SMBus, which needs a kernel driver.
  Read CL/tRCD/tRP/tRAS off your BIOS or CPU-Z and pass `-RamCl` etc.
- **CPU boost clock.** Windows does not expose it at all. Left null. The
  `maxClockSpeedMHz` field is whatever firmware reported, which is the base clock
  on most Intel parts and the boost clock on many AMD parts - it is recorded
  under its own name and deliberately not used as either.
- **P/E core split.** Algebra on cores and threads, applied only when the CPU
  name looks like a hybrid Intel part. Check it against the SKU.
- **NVMe generation.** Read from the PCIe link speed when the property is
  available; otherwise assumed gen3, flagged.
- **Chipset.** Not a WMI concept. Pattern-matched out of the LPC controller's
  device name, falling back to the board model string.

### The two id fields

```jsonc
"catalogueMapping": {
  "suggestedCpuId": "amd-ryzen-7-5800x3d",   // a slug guess. Never used automatically.
  "confirmedCpuId": "amd-ryzen-7-5800x3d",   // filled only on an exact catalogue match, or by you.
  "gpuCandidates": ["nvidia-geforce-rtx-3060-12gb", "nvidia-geforce-rtx-3060-8gb"]
}
```

When run from inside the repository, the script loads
`data/catalogue/{cpus,gpus}.json` and checks the slug against it:

- **exact unique match** - `confirmed*` is filled in for you;
- **several candidates** - they are listed and `confirmed*` stays empty. This is
  the normal case for GPUs, because one marketing name can cover two catalogue
  ids (RTX 3060 8GB and 12GB are different parts with materially different
  results). Pick the right one by hand;
- **nothing matches** - the part may genuinely be absent from the catalogue. Do
  not invent an id.

`run-suite.ps1 -HardwareJson` reads **only** the confirmed fields. That
asymmetry is the point: a wrong id attributes real measurements to the wrong
part, which corrupts the fitted model in a way that looks exactly like signal.

To map by hand, grep the catalogue:

```bash
node -e "require('./data/catalogue/gpus.json').records.filter(r=>/4070/.test(r.id)).forEach(r=>console.log(r.id,'|',r.fullName))"
```

Typical workflow:

```powershell
.\detect-hardware.ps1                    # 1. detect
notepad .\out\hardware-20260820_101500.json   # 2. read warnings, fill confirmed ids
.\run-suite.ps1 -Suite core-12 -HardwareJson .\out\hardware-20260820_101500.json
```

---

## Getting results into `data/manual/`

1. Copy the session CSV (`out/<session>/rigcheck_<session>.csv`) into
   `rigcheck/data/manual/`.
2. `npm run import:manual`.
3. Read the report. Every rejected row is listed with a reason, and every weight
   reduction is listed with the reason the fingerprint was incomplete.

The harness emits every column the schema knows about, so a complete run is
docked only for what you did not tell it. Passing `-GameBuild` is the usual
difference between weight 0.9 and weight 1.0.

`out/` is not committed. Only the CSV you deliberately copy into `data/manual/`
becomes part of the dataset.

---

## Suite file format

`suites/*.json`. `defaults` applies to every game; any key can be overridden per
game.

```jsonc
{
  "id": "core-12",
  "name": "RIGCHECK core loop (12 games)",
  "defaults": {
    "resolution": "1080p", "preset": "high",
    "runs": 3, "warmupSeconds": 45, "captureSeconds": 60,
    "upscalingTech": "none", "upscalingQuality": "native",
    "frameGen": false, "rtTier": "off"
  },
  "games": [
    {
      "id": "forza-horizon-5",            // MUST be a catalogue game id
      "name": "Forza Horizon 5",
      "processName": "ForzaHorizon5.exe", // what PresentMon filters on
      "api": "dx12",
      "builtInBenchmark": true,
      "scene":   "Settings > Video > Run Benchmark...",   // shown before the prompt
      "caution": "...",                                   // shown in yellow
      "notes":   "..."
    }
  ]
}
```

To make your own suite, copy `core-12.json`, change `id`, and edit the game list.
`-Suite mysuite` finds `suites/mysuite.json`; a path to a file works too.

**Process names are the field most likely to be wrong.** Verify each against
Task Manager > Details. The runner checks whether the named process is running
before it warms up and offers to re-check, capture without a filter, or skip.

**Games without a built-in benchmark need a fixed route** - Alan Wake 2,
Hogwarts Legacy, CS2, Dota 2 and Fortnite in `core-12`. Write the route down and
reuse it verbatim on every machine, or the numbers are not comparable between
rigs. Half the value of a fixture is that someone else can reproduce it.

---

## Troubleshooting

### PresentMon 1.x and 2.x emit different CSV columns

This is the single most likely cause of a confusing failure.

| | Frametime column | Also present |
|---|---|---|
| PresentMon 1.x | `MsBetweenPresents` (older builds: `msBetweenPresents`) | `MsBetweenDisplayChange`, `MsInPresentAPI` |
| PresentMon 2.x, default | `FrameTime` | `CPUBusy`, `GPUBusy`, `GPULatency`, `DisplayedTime` |
| PresentMon 2.x, `--v1_metrics` | `MsBetweenPresents` | the 1.x set |

`Get-FrameStats` accepts `MsBetweenPresents`, `msBetweenPresents`, `FrameTime`,
`MsBetweenDisplayChange`, `msBetweenDisplayChange` and `DisplayedTime`, in that
order of preference, and records which one it used in `source_note` as
`col=<name>`.

`MsBetweenPresents` and `FrameTime` are both CPU-side intervals between
consecutive frames and are directly comparable. **`DisplayedTime` is not** - it
measures displayed frames, which diverges from presented frames whenever frames
are dropped or generated. It is a last-resort fallback. If you see
`col=DisplayedTime` in a row's source note, treat that row with suspicion and
find out why the normal columns were missing.

If you get `No frametime column found`, the message lists the columns that *were*
present; add `--v1_metrics` to the PresentMon invocation or report the column set.

### Anti-cheat

PresentMon does **not** inject code into the game, hook Direct3D, or read the
game's memory. It consumes ETW events that the graphics stack emits system-wide.
That is why it is a far safer choice than an injecting overlay, and it is the
reason this harness uses it.

That said, be sensible:

- **Do not run injecting overlays at the same time.** RivaTuner/MSI Afterburner's
  on-screen display, Discord overlay, GeForce Experience overlay and the Steam
  overlay all hook the game. Some of them also present their own frames, which
  shows up in the capture as extra processes. Turn them off - it improves the
  data as well as the risk profile.
- **Kernel-level anti-cheat is the strictest case.** Riot Vanguard loads at boot
  and is the most aggressive; BattlEye and EAC are less so. Nothing here touches
  a protected process, but no third party can promise you an anti-cheat vendor's
  policy. Check the game's own rules.
- **Prefer offline, practice, replay or benchmark modes** over live competitive
  matches. This is the right call for data quality anyway: a live match is not a
  reproducible workload.
- Rainbow Six Siege launched through BattlEye may show as `RainbowSix_BE.exe`
  rather than `RainbowSix.exe`; set `processName` to whatever Task Manager shows.

### The spread is above 5%

The run-to-run numbers are printed as they happen. Their *shape* tells you which
problem you have:

- **Random scatter** - background load. Close browsers, chat clients, launchers,
  cloud sync, and check that Windows Update or an antivirus scan is not running.
  Reboot if the machine has been up for a long time.
- **Monotonic decline across runs** - thermal or power limiting. The GPU or CPU
  is dropping out of its boost bins as it heats up. That is a real property of
  the machine, not an error: record it in `source_note` rather than hiding it,
  and consider a longer warm-up so every run measures the sustained state.
- **One run wildly out** - something transient. Re-run.
- **Consistently high on one game only** - the scene is not identical between
  runs. This is the usual answer for games without a built-in benchmark.

Then redo just that game: `.\run-suite.ps1 -Suite core-12 -Only <game-id>`. It
replaces the row rather than adding a second one.

Do not "fix" a high spread by lowering settings, and do not average two bad runs.

### Other failures

| Symptom | Cause and fix |
|---|---|
| `cannot be loaded because running scripts is disabled` | `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass`. |
| `The term '-ExecutionPolicy' is not recognized` | The `powershell` at the start of the command is missing. Run `powershell -ExecutionPolicy Bypass -File .\detect-hardware.ps1`, from this directory. |
| `The argument ... does not exist` or a path error | You are still in `C:\WINDOWS\system32`. `cd` to the `harness` directory first. |
| None of this is working and you just want the check | Open the app, go to **System Health**, and use the in-page test on the "Read the machine" step. It needs no terminal. It reads less than this harness does, and says so. |
| `PresentMon needs Administrator...` | Re-open the shell as Administrator. |
| `PresentMon not found` | Put `PresentMon.exe` (or `PresentMon-2.x.y-x64.exe`) in `harness/`, `harness/tools/`, or on `PATH`. |
| `PresentMon produced no output for run N` | Not elevated, or the game is not presenting (minimised, on another GPU, or the `-ProcessName` filter matches nothing). |
| `Only N frames captured - too few to be meaningful` | The process filter is wrong, or the game was paused/loading during the capture. Check the `processName` against Task Manager. |
| `Capture contained N presenting processes` (warning) | No process filter, or an overlay presenting its own frames. The dominant process is used and named in `source_note`; set `processName` to remove the ambiguity. |
| `Missing lib\rigcheck-common.ps1` | You copied one script instead of the whole `harness/` directory. |
| `Refusing to append to <csv>: its header does not match` | You pointed a new run at a CSV written by an older version. Use a new `-SessionId`. |
| Importer says `gpu_id "..." is not in the catalogue` | The id is a guess, not a catalogue id. See "Mapping hardware to catalogue ids". |
| Importer says `Missing required column(s)` | The CSV was re-saved by a tool that changed the header - Excel is the usual culprit. Keep the file as written. |
| Every row rejected, `cpu_id...required` | You ran without `-CpuId`/`-GpuId`. Fill them in; the rest of the row is fine. |

### A note on file encoding

The CSVs are written as UTF-8 **without** a byte order mark, and every script in
this directory is pure ASCII. PowerShell 5.1 reads a BOM-less file as ANSI, and
its `-Encoding UTF8` *writes* a BOM; both directions cause trouble, so the
harness controls encoding explicitly instead of relying on defaults. If you edit
a suite file, keep it ASCII, or save it as UTF-8 and accept that 5.1 needs the
explicit encoding the harness already passes.
