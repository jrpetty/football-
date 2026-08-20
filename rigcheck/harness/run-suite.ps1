#Requires -Version 5.1
<#
.SYNOPSIS
  RIGCHECK suite runner. Captures a whole suite of games in one operator session
  and appends every result to a single CSV in the data/manual/ schema.

.DESCRIPTION
  run-benchmark.ps1 measures one game per invocation. That is fine for one-off
  checks and miserable for a full pass: twelve invocations means twelve chances
  to mistype a setting, and settings that drift between games produce rows that
  look comparable and are not.

  This runs the same protocol — same warm-up, same timed capture, same discard of
  run 1, same median, same spread warning — across a suite defined in
  harness/suites/*.json, holding every setting fixed for the whole session.

  It is interruptible. State is written after every game, so closing the window,
  a crash, or a game that will not launch costs you that game and nothing else:
  re-run the same command and it picks up where it stopped.

.PARAMETER Suite
  Suite name (a file in harness/suites/, without .json) or a path to a suite file.

.PARAMETER Only
  Re-run just these game ids, ignoring any state that says they are done. This is
  the fix for a game that came back with a high spread: redo that one, in place,
  in the same session.

.EXAMPLE
  .\run-suite.ps1 -Suite core-12 -Resolution 1080p -Preset high -CpuId amd-ryzen-5-3600 -GpuId nvidia-geforce-rtx-3060-12gb

.EXAMPLE
  .\run-suite.ps1 -Suite core-12 -Only hogwarts-legacy    # redo one noisy game

.NOTES
  Requires Administrator (ETW capture) and PresentMon 2.x.
  UNTESTED ON REAL HARDWARE — see harness/README.md before the first run.
#>

[CmdletBinding()]
param(
  [string]$Suite = 'core-12',
  [ValidateSet('1080p', '1440p', '2160p', '3440x1440')][string]$Resolution = '1080p',
  [string]$Preset = '',
  [ValidateRange(1, 20)][int]$Runs = 0,
  [ValidateRange(0, 3600)][int]$WarmupSeconds = -1,
  [ValidateRange(0, 3600)][int]$CaptureSeconds = -1,
  [string]$CpuId = '',
  [string]$GpuId = '',
  [string]$HardwareJson = '',
  [string]$GamesPath = '',
  [string]$GameBuild = '',
  [string]$OutputDir = '',
  [string]$SessionId = '',
  [string[]]$Only = @(),
  [string[]]$Skip = @(),
  [double]$SpreadWarnPercent = 5,
  [switch]$Fresh,
  [switch]$RetrySkipped,
  [switch]$ListSuites
)

$ErrorActionPreference = 'Stop'

$libPath = Join-Path (Join-Path $PSScriptRoot 'lib') 'rigcheck-common.ps1'
if (-not (Test-Path -LiteralPath $libPath)) {
  throw "Missing $libPath. Copy the whole harness/ directory to the test machine, not just this script."
}
. $libPath

if (-not $OutputDir) { $OutputDir = Join-Path $PSScriptRoot 'out' }
$suitesDir = Join-Path $PSScriptRoot 'suites'

# --- Console helpers --------------------------------------------------------

function Write-Rule {
  param([string]$Colour = 'DarkGray')
  Write-Host ('-' * 76) -ForegroundColor $Colour
}

function Write-Wrapped {
  <# Wrap long scene/caution text so it is readable in an 80-column console. #>
  param(
    [string]$Text,
    [string]$Indent = '    ',
    [int]$Width = 72,
    [string]$Colour = 'Gray'
  )
  if (-not $Text) { return }
  $line = ''
  foreach ($word in ($Text -split '\s+')) {
    if ($word -eq '') { continue }
    if (($line.Length + $word.Length + 1) -gt $Width) {
      Write-Host ($Indent + $line) -ForegroundColor $Colour
      $line = $word
    } elseif ($line -eq '') {
      $line = $word
    } else {
      $line = "$line $word"
    }
  }
  if ($line -ne '') { Write-Host ($Indent + $line) -ForegroundColor $Colour }
}

function Read-Choice {
  <#
    Read one operator answer and normalise it. Returns 'ready', 'skip' or 'quit'.
    A closed stdin returns 'quit' rather than spinning forever.
  #>
  param([string]$Prompt)
  while ($true) {
    $raw = Read-Host $Prompt
    if ($null -eq $raw) {
      Write-Host ''
      Write-Warning 'Input stream closed. Treating that as quit; state has been saved.'
      return 'quit'
    }
    $a = "$raw".Trim().ToLower()
    if ($a -eq '' -or $a -eq 'y' -or $a -eq 'yes' -or $a -eq 'ready' -or $a -eq 'go') { return 'ready' }
    if ($a -eq 's' -or $a -eq 'skip' -or $a -eq 'n' -or $a -eq 'no' -or $a -eq 'ni' -or $a -eq 'not installed') { return 'skip' }
    if ($a -eq 'q' -or $a -eq 'quit' -or $a -eq 'exit' -or $a -eq 'abort') { return 'quit' }
    Write-Host "  Not one of: [Enter] ready, s = skip, q = quit." -ForegroundColor Yellow
  }
}

function Format-Duration {
  param([int]$Seconds)
  if ($Seconds -lt 90) { return "$Seconds s" }
  $m = [math]::Round($Seconds / 60.0, 0)
  if ($m -lt 90) { return "$m min" }
  return ("{0:N1} h" -f ($Seconds / 3600.0))
}

# --- Suite loading ----------------------------------------------------------

function Get-AvailableSuites {
  if (-not (Test-Path -LiteralPath $suitesDir)) { return @() }
  return @(Get-ChildItem -LiteralPath $suitesDir -Filter '*.json' -File -ErrorAction SilentlyContinue)
}

if ($ListSuites) {
  Write-Host "Suites in $suitesDir" -ForegroundColor Cyan
  $found = Get-AvailableSuites
  if ($found.Count -eq 0) { Write-Host '  (none)' -ForegroundColor Yellow }
  foreach ($f in $found) {
    try {
      $d = Get-Content -Raw -LiteralPath $f.FullName | ConvertFrom-Json
      Write-Host ("  {0,-16} {1} game(s)  {2}" -f $d.id, @($d.games).Count, $d.name)
    } catch {
      Write-Host ("  {0,-16} UNREADABLE: {1}" -f $f.BaseName, $_.Exception.Message) -ForegroundColor Red
    }
  }
  return
}

function Resolve-SuitePath {
  param([Parameter(Mandatory = $true)][string]$Name)
  if ($Name -match '\.json$') {
    if (Test-Path -LiteralPath $Name) { return (Resolve-Path -LiteralPath $Name).Path }
    throw "Suite file not found: $Name"
  }
  $p = Join-Path $suitesDir "$Name.json"
  if (Test-Path -LiteralPath $p) { return (Resolve-Path -LiteralPath $p).Path }
  $available = (@(Get-AvailableSuites | ForEach-Object { $_.BaseName }) -join ', ')
  throw "No suite '$Name' in $suitesDir. Available: $available"
}

$suitePath = Resolve-SuitePath -Name $Suite
try {
  $def = Get-Content -Raw -LiteralPath $suitePath | ConvertFrom-Json
} catch {
  throw "Suite $suitePath is not valid JSON: $($_.Exception.Message)"
}

$suiteGames = @($def.games)
if ($suiteGames.Count -eq 0) { throw "Suite $suitePath defines no games." }
foreach ($g in $suiteGames) {
  if (-not (Get-OrDefault -Object $g -Name 'id')) {
    throw "Suite $suitePath has a game entry with no id. Every entry needs a catalogue game id."
  }
}
$suiteId = Get-OrDefault -Object $def -Name 'id' -Default ([System.IO.Path]::GetFileNameWithoutExtension($suitePath))
$defaults = Get-OrDefault -Object $def -Name 'defaults'

# Command line beats suite defaults beats built-in defaults.
if (-not $PSBoundParameters.ContainsKey('Resolution')) {
  $Resolution = [string](Get-OrDefault -Object $defaults -Name 'resolution' -Default $Resolution)
}
if (-not $Preset) { $Preset = [string](Get-OrDefault -Object $defaults -Name 'preset' -Default 'high') }
if ($Runs -le 0) { $Runs = [int](Get-OrDefault -Object $defaults -Name 'runs' -Default 3) }
if ($WarmupSeconds -lt 0) { $WarmupSeconds = [int](Get-OrDefault -Object $defaults -Name 'warmupSeconds' -Default 45) }
if ($CaptureSeconds -lt 0) { $CaptureSeconds = [int](Get-OrDefault -Object $defaults -Name 'captureSeconds' -Default 60) }

$defUpTech = [string](Get-OrDefault -Object $defaults -Name 'upscalingTech' -Default 'none')
$defUpQual = [string](Get-OrDefault -Object $defaults -Name 'upscalingQuality' -Default 'native')
$defFrameGen = [bool](Get-OrDefault -Object $defaults -Name 'frameGen' -Default $false)
$defRtTier = [string](Get-OrDefault -Object $defaults -Name 'rtTier' -Default 'off')

if ($Runs -lt 2) {
  Write-Warning "Runs = $Runs. The protocol discards run 1, so a single run leaves nothing to median and no spread to check. Two is the bare minimum, three is the standard."
}
if ($WarmupSeconds -lt 30) {
  Write-Warning "WarmupSeconds = $WarmupSeconds. Below about 30 s you are measuring shader compilation and unsettled clocks, not the game. Only do this for a dry run."
}

# --- Environment ------------------------------------------------------------

Assert-Admin
$pm = Find-PresentMon -HarnessRoot $PSScriptRoot
$pmVersion = Get-PresentMonVersion -Path $pm

Write-Host ''
Write-Rule 'Cyan'
Write-Host " RIGCHECK suite runner - $($def.name)" -ForegroundColor Cyan
Write-Rule 'Cyan'
Write-Host "  PresentMon  $pm"
if ($pmVersion) { Write-Host "              version $pmVersion" }
if ($pmVersion -and ($pmVersion -match '^\s*1\.')) {
  Write-Warning 'This looks like PresentMon 1.x. It will probably work (the 1.x column names are handled) but 2.x is what this harness targets.'
}

$hw = Get-HardwareProfile -GamesPath $GamesPath
$ramCl = $null

# A reviewed hardware file beats live detection: detect-hardware.ps1 goes deeper,
# and its values have had a human look at them.
if ($HardwareJson) {
  if (-not (Test-Path -LiteralPath $HardwareJson)) { throw "Hardware file not found: $HardwareJson" }
  $hwFile = Get-Content -Raw -LiteralPath $HardwareJson | ConvertFrom-Json
  Write-Host "  Hardware    $HardwareJson (overrides live detection)" -ForegroundColor DarkCyan

  $mem = Get-OrDefault -Object $hwFile -Name 'memory'
  if ($mem) {
    $v = Get-OrDefault -Object $mem -Name 'totalGB';  if ($null -ne $v) { $hw.RamGB = $v }
    $v = Get-OrDefault -Object $mem -Name 'channels'; if ($null -ne $v) { $hw.RamChannels = $v }
    $v = Get-OrDefault -Object $mem -Name 'speedMTs'; if ($null -ne $v) { $hw.RamMTs = $v }
    $t = Get-OrDefault -Object $mem -Name 'timings'
    if ($t) { $ramCl = Get-OrDefault -Object $t -Name 'cl' }
  }
  $st = Get-OrDefault -Object $hwFile -Name 'storage'
  if ($st) {
    $target = Get-OrDefault -Object $st -Name 'games'
    if (-not $target) { $target = Get-OrDefault -Object $st -Name 'system' }
    $v = Get-OrDefault -Object $target -Name 'class'; if ($v) { $hw.Storage = $v }
  }
  $gp = Get-OrDefault -Object $hwFile -Name 'gpu'
  if ($gp) {
    $v = Get-OrDefault -Object $gp -Name 'driverVersion'; if ($v) { $hw.DriverVersion = $v }
    $v = Get-OrDefault -Object $gp -Name 'name';          if ($v) { $hw.GpuName = $v }
  }
  $cp = Get-OrDefault -Object $hwFile -Name 'cpu'
  if ($cp) { $v = Get-OrDefault -Object $cp -Name 'name'; if ($v) { $hw.CpuName = $v } }

  # Only ids a human CONFIRMED are used. The suggested* ids in that file are a
  # slug guess and must never silently become provenance.
  $map = Get-OrDefault -Object $hwFile -Name 'catalogueMapping'
  if ($map) {
    if (-not $CpuId) { $CpuId = [string](Get-OrDefault -Object $map -Name 'confirmedCpuId' -Default '') }
    if (-not $GpuId) { $GpuId = [string](Get-OrDefault -Object $map -Name 'confirmedGpuId' -Default '') }
  }
}

Write-Host "  CPU         $($hw.CpuName)"
Write-Host "  GPU         $($hw.GpuName)  driver $($hw.DriverVersion)"
Write-Host "  RAM         $($hw.RamGB)GB, $($hw.RamChannels) channel(s), $($hw.RamMTs) MT/s"
Write-Host "  Storage     $($hw.Storage)"
foreach ($w in @($hw.Warnings)) { Write-Warning $w }
Write-Host ''

if (-not $CpuId -or -not $GpuId) {
  Write-Warning "CpuId/GpuId not supplied. Detected names go into source_note, but you must map them to catalogue ids before import or the importer will reject every row. Run detect-hardware.ps1 and fill in catalogueMapping.confirmedCpuId / confirmedGpuId, or pass -CpuId/-GpuId."
}

# --- Session and resume state ----------------------------------------------

if (-not $SessionId) { $SessionId = "$($suiteId)_$($Resolution)_$($Preset)" }
$SessionId = ($SessionId -replace '[^A-Za-z0-9._-]', '-')
$sessionDir = Join-Path $OutputDir $SessionId
$statePath = Join-Path $sessionDir 'state.json'
$suiteCsv = Join-Path $sessionDir "rigcheck_$SessionId.csv"
New-Item -ItemType Directory -Force -Path $sessionDir | Out-Null

if ($Fresh) {
  $stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
  foreach ($p in @($statePath, $suiteCsv)) {
    if (Test-Path -LiteralPath $p) {
      $bak = "$p.bak-$stamp"
      Move-Item -LiteralPath $p -Destination $bak -Force
      Write-Host "  -Fresh: moved existing $(Split-Path -Leaf $p) to $(Split-Path -Leaf $bak)" -ForegroundColor Yellow
    }
  }
}

$gameState = @{}
if (Test-Path -LiteralPath $statePath) {
  $prior = Get-Content -Raw -LiteralPath $statePath | ConvertFrom-Json

  $priorSuite = [string](Get-OrDefault -Object $prior -Name 'suiteId' -Default '')
  $priorRes = [string](Get-OrDefault -Object $prior -Name 'resolution' -Default '')
  $priorPreset = [string](Get-OrDefault -Object $prior -Name 'preset' -Default '')
  if ($priorSuite -ne $suiteId -or $priorRes -ne $Resolution -or $priorPreset -ne $Preset) {
    throw "Session '$SessionId' already exists for suite '$priorSuite' at $priorRes/$priorPreset, which is not what you asked for ($suiteId at $Resolution/$Preset). Use -SessionId to start a separate session, or -Fresh to archive this one. Mixing settings inside one CSV is how a fixture set gets quietly corrupted."
  }

  # A resumed session on a DIFFERENT machine would put two rigs in one CSV.
  $priorHw = Get-OrDefault -Object $prior -Name 'hardware'
  $priorCpu = [string](Get-OrDefault -Object $priorHw -Name 'CpuName' -Default '')
  $priorGpu = [string](Get-OrDefault -Object $priorHw -Name 'GpuName' -Default '')
  if ($priorCpu -and ($priorCpu -ne $hw.CpuName -or $priorGpu -ne $hw.GpuName)) {
    throw "Session '$SessionId' was started on a different machine (CPU '$priorCpu', GPU '$priorGpu'; this machine is '$($hw.CpuName)' / '$($hw.GpuName)'). Refusing to append another rig's rows to the same CSV. Use -SessionId for this machine."
  }

  $gameState = ConvertTo-Hashtable -InputObject (Get-OrDefault -Object $prior -Name 'games')
  $done = @($gameState.Keys | Where-Object { "$($gameState[$_].status)" -eq 'captured' })
  Write-Host "  Resuming session '$SessionId': $($done.Count) game(s) already captured." -ForegroundColor Green
} else {
  Write-Host "  New session '$SessionId'." -ForegroundColor Green
}
Write-Host "  Output      $suiteCsv"
Write-Host "  State       $statePath"
Write-Host ''

function Save-SuiteState {
  $obj = [pscustomobject]([ordered]@{
    schema       = 'rigcheck-suite-state/1'
    suiteId      = $suiteId
    suiteFile    = $suitePath
    sessionId    = $SessionId
    resolution   = $Resolution
    preset       = $Preset
    runs         = $Runs
    warmupSeconds = $WarmupSeconds
    captureSeconds = $CaptureSeconds
    cpuId        = $CpuId
    gpuId        = $GpuId
    outputCsv    = $suiteCsv
    updatedAt    = (Get-Date -Format 'o')
    hardware     = $hw
    games        = $gameState
  })
  Write-JsonAtomic -Path $statePath -Object $obj -Depth 8
}

function Set-GameState {
  param(
    [string]$Id,
    [string]$Status,
    [string]$Reason = '',
    $Summary = $null
  )
  $entry = [ordered]@{
    status = $Status
    at     = (Get-Date -Format 'o')
    reason = $Reason
  }
  if ($Summary) {
    $entry['avgFps'] = $Summary.AvgFps
    $entry['low1Pct'] = $Summary.Low1Pct
    $entry['low01Pct'] = $Summary.Low01Pct
    $entry['spreadPct'] = $Summary.SpreadPct
    $entry['usableRuns'] = $Summary.UsableRuns
    $entry['column'] = $Summary.Column
  }
  $script:gameState[$Id] = [pscustomobject]$entry
  Save-SuiteState
}

# --- Work list --------------------------------------------------------------

function Expand-IdList {
  <#
    Accept -Only a,b (a real array from a PowerShell prompt) and -Only "a,b"
    (one string, which is what pwsh -File and a pasted command line produce).
    Getting this wrong silently runs nothing, so handle both.
  #>
  param([string[]]$Values)
  $out = New-Object System.Collections.ArrayList
  foreach ($v in @($Values)) {
    if ($null -eq $v) { continue }
    foreach ($part in ("$v" -split '[,;]')) {
      $p = $part.Trim()
      if ($p) { [void]$out.Add($p) }
    }
  }
  return @($out.ToArray())
}

$Only = Expand-IdList -Values $Only
$Skip = Expand-IdList -Values $Skip

$knownIds = @($suiteGames | ForEach-Object { [string]$_.id })
foreach ($id in (@($Only) + @($Skip))) {
  if ($knownIds -notcontains $id) {
    throw "'$id' is not a game in suite '$suiteId'. Suite contains: $($knownIds -join ', ')"
  }
}

$queue = @($suiteGames)
if ($Only.Count -gt 0) { $queue = @($queue | Where-Object { $Only -contains [string]$_.id }) }
if ($Skip.Count -gt 0) { $queue = @($queue | Where-Object { $Skip -notcontains [string]$_.id }) }
if ($queue.Count -eq 0) { throw "Nothing to run: -Only/-Skip filtered every game out of suite '$suiteId'." }

function Get-GameSetting {
  param($Game, [string]$Name, $Default)
  return (Get-OrDefault -Object $Game -Name $Name -Default $Default)
}

$dueCount = 0
$estSeconds = 0
foreach ($g in $queue) {
  $st = $gameState[$g.id]
  $prev = ''
  if ($st) { $prev = "$($st.status)" }
  $forced = ($Only -contains $g.id)
  if (-not $forced -and $prev -eq 'captured') { continue }
  if (-not $forced -and $prev -eq 'skipped' -and -not $RetrySkipped) { continue }
  $dueCount++
  $r = [int](Get-GameSetting -Game $g -Name 'runs' -Default $Runs)
  $w = [int](Get-GameSetting -Game $g -Name 'warmupSeconds' -Default $WarmupSeconds)
  $c = [int](Get-GameSetting -Game $g -Name 'captureSeconds' -Default $CaptureSeconds)
  $estSeconds += $r * ($w + $c)
}

Write-Host "  $dueCount game(s) due, roughly $(Format-Duration $estSeconds) of capture time." -ForegroundColor Cyan
Write-Host '  Plus however long each game takes to launch and load - budget double.' -ForegroundColor DarkGray
Write-Host ''
Write-Host '  Before you start: close browsers, chat clients, launchers-with-overlays and' -ForegroundColor DarkGray
Write-Host '  anything that updates in the background. They are the usual cause of a high' -ForegroundColor DarkGray
Write-Host '  run-to-run spread, and a high spread makes the whole session unusable.' -ForegroundColor DarkGray
Write-Host ''

# --- Main loop --------------------------------------------------------------

$index = 0
$total = $queue.Count
$quit = $false

foreach ($game in $queue) {
  $index++
  if ($quit) { break }

  $gid = [string]$game.id
  $gname = [string](Get-GameSetting -Game $game -Name 'name' -Default $gid)
  $forced = ($Only -contains $gid)
  $prior = $gameState[$gid]
  $priorStatus = ''
  if ($prior) { $priorStatus = "$($prior.status)" }

  if (-not $forced -and $priorStatus -eq 'captured') {
    Write-Host ("[{0,2}/{1}] {2,-32} already captured ({3} fps) - skipping" -f $index, $total, $gname, $prior.avgFps) -ForegroundColor DarkGreen
    continue
  }
  if (-not $forced -and $priorStatus -eq 'skipped' -and -not $RetrySkipped) {
    Write-Host ("[{0,2}/{1}] {2,-32} previously skipped - skipping (use -RetrySkipped to retry)" -f $index, $total, $gname) -ForegroundColor DarkYellow
    continue
  }

  $gPreset = [string](Get-GameSetting -Game $game -Name 'preset' -Default $Preset)
  $gUpTech = [string](Get-GameSetting -Game $game -Name 'upscalingTech' -Default $defUpTech)
  $gUpQual = [string](Get-GameSetting -Game $game -Name 'upscalingQuality' -Default $defUpQual)
  $gFrameGen = [bool](Get-GameSetting -Game $game -Name 'frameGen' -Default $defFrameGen)
  $gRtTier = [string](Get-GameSetting -Game $game -Name 'rtTier' -Default $defRtTier)
  $gApi = [string](Get-GameSetting -Game $game -Name 'api' -Default '')
  $gProc = [string](Get-GameSetting -Game $game -Name 'processName' -Default '')
  $gRuns = [int](Get-GameSetting -Game $game -Name 'runs' -Default $Runs)
  $gWarm = [int](Get-GameSetting -Game $game -Name 'warmupSeconds' -Default $WarmupSeconds)
  $gCap = [int](Get-GameSetting -Game $game -Name 'captureSeconds' -Default $CaptureSeconds)
  $gScene = [string](Get-GameSetting -Game $game -Name 'scene' -Default '')
  $gCaution = [string](Get-GameSetting -Game $game -Name 'caution' -Default '')
  $gNotes = [string](Get-GameSetting -Game $game -Name 'notes' -Default '')
  $gBench = [bool](Get-GameSetting -Game $game -Name 'builtInBenchmark' -Default $false)

  Write-Host ''
  Write-Rule 'White'
  Write-Host (" [{0,2}/{1}]  {2}" -f $index, $total, $gname) -ForegroundColor White
  Write-Host ("            {0}" -f $gid) -ForegroundColor DarkGray
  Write-Rule 'White'
  $upDesc = $gUpTech
  if ($gUpTech -ne 'none') { $upDesc = "$gUpTech/$gUpQual" }
  Write-Host ("   Row settings   : {0} / {1} / upscaling {2} / RT {3}{4}" -f $Resolution, $gPreset, $upDesc, $gRtTier, $(if ($gFrameGen) { ' / frame-gen ON' } else { '' }))
  if ($gApi) { Write-Host ("   API            : {0}" -f $gApi) }
  Write-Host ("   Capture filter : {0}" -f $(if ($gProc) { $gProc } else { '(none - dominant process will be used)' }))
  Write-Host ("   Protocol       : {0} runs x ({1} s warm-up + {2} s capture) = {3}" -f $gRuns, $gWarm, $gCap, (Format-Duration ($gRuns * ($gWarm + $gCap))))
  Write-Host ("   Built-in bench : {0}" -f $(if ($gBench) { 'yes' } else { 'no - use a FIXED repeatable route' }))
  if ($priorStatus -eq 'failed') {
    Write-Host ("   Last attempt   : FAILED - {0}" -f $prior.reason) -ForegroundColor Yellow
  }

  if ($gScene) {
    Write-Host ''
    Write-Host '   SCENE' -ForegroundColor Cyan
    Write-Wrapped -Text $gScene -Indent '     ' -Colour 'Gray'
  }
  if ($gCaution) {
    Write-Host ''
    Write-Host '   CAUTION' -ForegroundColor Yellow
    Write-Wrapped -Text $gCaution -Indent '     ' -Colour 'Yellow'
  }
  if ($gNotes) {
    Write-Host ''
    Write-Host '   NOTES' -ForegroundColor DarkCyan
    Write-Wrapped -Text $gNotes -Indent '     ' -Colour 'DarkGray'
  }

  Write-Host ''
  $choice = Read-Choice -Prompt '   Launch the game and get the scene running, then [Enter]=ready, s=skip, q=quit'

  if ($choice -eq 'quit') {
    Set-GameState -Id $gid -Status 'pending' -Reason 'operator quit before capture'
    $quit = $true
    break
  }
  if ($choice -eq 'skip') {
    $reason = Read-Host '   Reason (blank = not installed)'
    if (-not $reason) { $reason = 'not installed' }
    Set-GameState -Id $gid -Status 'skipped' -Reason $reason
    Write-Host "   Recorded as skipped: $reason" -ForegroundColor DarkYellow
    continue
  }

  # Process check. A wrong process name captures nothing, which is a loud
  # failure - but catching it here saves the operator the warm-up first.
  $useProc = $gProc
  if ($gProc) {
    $bare = $gProc -replace '\.exe$', ''
    $checking = $true
    while ($checking) {
      $running = @(Get-Process -Name $bare -ErrorAction SilentlyContinue)
      if ($running.Count -gt 0) { $checking = $false; break }
      Write-Warning "Process '$gProc' is not running. PresentMon would capture nothing."
      $ans = Read-Host '   [Enter]=re-check, a=capture with no process filter, s=skip this game'
      if ($null -eq $ans) { $ans = 's' }
      $ans = "$ans".Trim().ToLower()
      if ($ans -eq 'a') { $useProc = ''; $checking = $false }
      elseif ($ans -eq 's') { $checking = $false; $useProc = $null }
    }
    if ($null -eq $useProc) {
      Set-GameState -Id $gid -Status 'skipped' -Reason "process '$gProc' never appeared"
      Write-Host "   Recorded as skipped." -ForegroundColor DarkYellow
      continue
    }
  }

  # Capture, with retry, because a mistimed confirmation is a normal event.
  $captured = $false
  $attempt = 0
  while (-not $captured) {
    $attempt++
    try {
      $rawPrefix = "raw_${gid}_${Resolution}"
      if ($attempt -gt 1) { $rawPrefix = "raw_${gid}_${Resolution}_try${attempt}" }
      $results = @(Invoke-CaptureRuns -PresentMonPath $pm -RawPrefix $rawPrefix -OutputDir $sessionDir `
          -Runs $gRuns -WarmupSeconds $gWarm -CaptureSeconds $gCap -ProcessName $useProc)
      $sum = Get-RunSummary -Results $results -SpreadWarnPercent $SpreadWarnPercent

      Write-Host ''
      Write-Host ("   RESULT  avg {0} fps, 1% low {1}, 0.1% low {2}  (median of {3} run(s) after discarding run 1)" -f `
          $sum.AvgFps, $sum.Low1Pct, $sum.Low01Pct, $sum.UsableRuns) -ForegroundColor Green
      if ($sum.SpreadHigh) {
        Write-Warning "Run-to-run spread is $($sum.SpreadPct)%. That is above $SpreadWarnPercent% - check for background load before trusting this row. Re-run just this game later with: -Only $gid"
      } else {
        Write-Host ("   Spread  {0}%" -f $sum.SpreadPct) -ForegroundColor DarkGray
      }

      $sceneNote = $gScene
      if ($sceneNote.Length -gt 160) { $sceneNote = $sceneNote.Substring(0, 157) + '...' }
      $note = "rigcheck harness suite '$suiteId'"
      $note += "; detected CPU '$($hw.CpuName)', GPU '$($hw.GpuName)'"
      $note += "; median of $($sum.UsableRuns) run(s) after discarding warm-up run"
      $note += "; spread $($sum.SpreadPct)%"
      $note += "; PresentMon $pmVersion col=$($sum.Column)"
      if ($useProc) { $note += "; process $useProc" } else { $note += '; no process filter (dominant process used)' }
      if ($sceneNote) { $note += "; scene: $sceneNote" }

      $row = New-ManualCsvRow -GpuId $GpuId -CpuId $CpuId -GameId $gid `
        -Resolution $Resolution -Preset $gPreset `
        -AvgFps $sum.AvgFps -Low1Pct $sum.Low1Pct -Low01Pct $sum.Low01Pct `
        -UpscalingTech $gUpTech -UpscalingQuality $gUpQual -FrameGen $gFrameGen -RtTier $gRtTier `
        -RamGB $hw.RamGB -RamChannels $hw.RamChannels -RamMTs $hw.RamMTs -RamCl $ramCl `
        -Storage $hw.Storage -GameBuild $GameBuild -DriverVersion $hw.DriverVersion `
        -Api $gApi -SourceNote $note

      Add-CsvRow -Path $suiteCsv -Row $row
      Set-GameState -Id $gid -Status 'captured' -Summary $sum
      Write-Host "   Appended to $(Split-Path -Leaf $suiteCsv)" -ForegroundColor DarkGreen
      $captured = $true
    } catch {
      $msg = $_.Exception.Message
      Write-Host ''
      Write-Host "   CAPTURE FAILED: $msg" -ForegroundColor Red
      Set-GameState -Id $gid -Status 'failed' -Reason $msg
      $ans = Read-Host '   [Enter]=continue to next game, r=retry this game, q=quit and save'
      if ($null -eq $ans) { $ans = 'q' }
      $ans = "$ans".Trim().ToLower()
      if ($ans -eq 'r') { continue }
      if ($ans -eq 'q') { $quit = $true }
      break
    }
  }
}

# --- Summary ----------------------------------------------------------------

Save-SuiteState

Write-Host ''
Write-Rule 'Cyan'
Write-Host " SUITE SUMMARY - $suiteId at $Resolution / $Preset" -ForegroundColor Cyan
Write-Rule 'Cyan'

$summary = @()
$flagged = @()
$capturedCount = 0
foreach ($g in $suiteGames) {
  $st = $gameState[$g.id]
  $status = 'not run'
  $avg = ''
  $low1 = ''
  $spread = ''
  $flag = ''
  if ($st) {
    $status = "$($st.status)"
    if ($status -eq 'captured') {
      $capturedCount++
      $avg = ('{0:N1}' -f [double]$st.avgFps)
      $low1 = ('{0:N1}' -f [double]$st.low1Pct)
      $spread = ('{0:N1}%' -f [double]$st.spreadPct)
      if ([double]$st.spreadPct -gt $SpreadWarnPercent) {
        $flag = "SPREAD >$SpreadWarnPercent%"
        $flagged += $g.id
      }
    } elseif ($status -eq 'skipped' -or $status -eq 'failed') {
      $flag = "$($st.reason)"
      if ($flag.Length -gt 58) { $flag = $flag.Substring(0, 55) + '...' }
    }
  }
  $summary += [pscustomobject]([ordered]@{
    game    = [string](Get-OrDefault -Object $g -Name 'name' -Default $g.id)
    status  = $status
    avg     = $avg
    'low1%' = $low1
    spread  = $spread
    flag    = $flag
  })
}

$summary | Format-Table -AutoSize | Out-String -Width 200 | Write-Host

Write-Host ("  Captured {0} of {1} game(s) in this suite." -f $capturedCount, $suiteGames.Count)

if ($flagged.Count -gt 0) {
  Write-Host ''
  Write-Warning "$($flagged.Count) run(s) exceeded $SpreadWarnPercent% run-to-run spread: $($flagged -join ', ')"
  Write-Host '  A high spread means the machine was not quiet, or the scene was not identical' -ForegroundColor Yellow
  Write-Host '  between runs. Those rows are not trustworthy. Close background load and redo:' -ForegroundColor Yellow
  Write-Host ("    .\run-suite.ps1 -Suite $suiteId -Only $($flagged -join ',')") -ForegroundColor Yellow
  Write-Host '  (-Only re-runs in place and appends a corrected row; delete the bad row from' -ForegroundColor Yellow
  Write-Host '   the CSV by hand before importing, or the old figure is imported too.)' -ForegroundColor Yellow
}

$pending = @($summary | Where-Object { $_.status -eq 'not run' -or $_.status -eq 'pending' -or $_.status -eq 'failed' })
if ($pending.Count -gt 0) {
  Write-Host ''
  Write-Host "  $($pending.Count) game(s) still outstanding. Re-run the same command to resume:" -ForegroundColor Cyan
  Write-Host ("    .\run-suite.ps1 -Suite $suiteId -Resolution $Resolution -Preset $Preset") -ForegroundColor Cyan
}

Write-Host ''
if ($capturedCount -gt 0) {
  Write-Host "  Wrote $suiteCsv" -ForegroundColor Green
  if (-not $CpuId -or -not $GpuId) {
    Write-Warning 'cpu_id / gpu_id are blank in every row. Fill them in before import or all rows are rejected.'
  }
  Write-Host '  Next: copy it into rigcheck/data/manual/ and run  npm run import:manual' -ForegroundColor Green
} else {
  Write-Host '  Nothing was captured, so no CSV row was written.' -ForegroundColor Yellow
}
