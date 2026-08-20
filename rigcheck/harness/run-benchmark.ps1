#Requires -Version 5.1
<#
.SYNOPSIS
  RIGCHECK benchmark harness. Captures frametimes via PresentMon and emits rows
  directly in the data/manual/ CSV schema.

.DESCRIPTION
  Deploy on a test machine, run it, and drop the resulting CSV into
  rigcheck/data/manual/. No game modification, no injection: PresentMon reads
  ETW events the graphics stack already emits.

  The run protocol is fixed deliberately. Without a fixed warm-up, a fixed
  scene and repeated runs, human-played sessions vary so much that the data is
  noise - and noisy fixtures are worse than no fixtures, because they look like
  signal to the calibrator.

  For a whole suite of games in one session, use run-suite.ps1. It runs this
  same protocol game after game, resumes after an interruption and writes one
  CSV for the session; this script stays the tool for a single measurement.

.NOTES
  Requires: Administrator (ETW capture), PresentMon 2.x on PATH or alongside.
  PresentMon 1.x and 2.x have different CSV schemas; both column vocabularies
  are recognised (see Get-FrameStats in lib/rigcheck-common.ps1).

  The protocol itself lives in lib/rigcheck-common.ps1 so that this script and
  run-suite.ps1 cannot drift apart. Copy the whole harness/ directory to a test
  machine, not just this file.
#>

[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$GameId,
  [Parameter(Mandatory = $true)][ValidateSet('1080p', '1440p', '2160p', '3440x1440')][string]$Resolution,
  [Parameter(Mandatory = $true)][string]$Preset,
  [string]$ProcessName,
  [int]$Runs = 3,
  [int]$WarmupSeconds = 45,
  [int]$CaptureSeconds = 60,
  [ValidateSet('none', 'dlss', 'fsr', 'xess', 'tsr')][string]$UpscalingTech = 'none',
  [ValidateSet('native', 'quality', 'balanced', 'performance', 'ultra-performance')][string]$UpscalingQuality = 'native',
  [switch]$FrameGen,
  [string]$RtTier = 'off',
  [string]$GameBuild = '',
  [string]$OutputDir = "$PSScriptRoot\out",
  [string]$CpuId = '',
  [string]$GpuId = ''
)

$ErrorActionPreference = 'Stop'

# The measurement protocol - admin check, PresentMon discovery, hardware profile,
# frametime statistics, median, CSV row shape - is shared with run-suite.ps1.
# One implementation, so the single-game and suite runners cannot disagree about
# what a measurement means.
$libPath = Join-Path (Join-Path $PSScriptRoot 'lib') 'rigcheck-common.ps1'
if (-not (Test-Path -LiteralPath $libPath)) {
  throw "Missing $libPath. Copy the whole harness/ directory to the test machine, not just this script."
}
. $libPath

# --- Main -------------------------------------------------------------------
Assert-Admin
$pm = Find-PresentMon -HarnessRoot $PSScriptRoot
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$hw = Get-HardwareProfile

Write-Host "RIGCHECK harness" -ForegroundColor Cyan
Write-Host "  CPU     $($hw.CpuName)"
Write-Host "  GPU     $($hw.GpuName)  driver $($hw.DriverVersion)"
Write-Host "  RAM     $($hw.RamGB)GB, $($hw.RamChannels) channel(s), $($hw.RamMTs) MT/s"
Write-Host "  Storage $($hw.Storage)"
# Anything the detector had to guess is said out loud rather than left in the row.
foreach ($w in @($hw.Warnings)) { Write-Warning $w }
Write-Host ""
if (-not $CpuId -or -not $GpuId) {
  Write-Warning "CpuId/GpuId not supplied. Detected names are written to source_note; you must map them to catalogue ids before import, or the importer will reject the rows."
}

Write-Host "Start the game, load the benchmark scene, then leave it running." -ForegroundColor Yellow
Write-Host "Warm-up is $WarmupSeconds s per run (shader compilation and clock settling), capture is $CaptureSeconds s."
Read-Host "Press Enter when the scene is running"

# Warm up, capture, repeat - and then discard run 1, because shader caches and
# clocks have not settled and it is consistently the outlier. The MEDIAN of the
# rest is reported, not the mean, so one background-task spike cannot move the
# recorded figure. Run-to-run spread is computed so a noisy machine is visible.
$results = @(Invoke-CaptureRuns -PresentMonPath $pm -RawPrefix "raw_${GameId}_${Resolution}" -OutputDir $OutputDir `
    -Runs $Runs -WarmupSeconds $WarmupSeconds -CaptureSeconds $CaptureSeconds -ProcessName $ProcessName)

$sum = Get-RunSummary -Results $results
$avgFps = $sum.AvgFps
$low1 = $sum.Low1Pct
$low01 = $sum.Low01Pct
$spread = $sum.SpreadPct
if ($sum.SpreadHigh) {
  Write-Warning "Run-to-run spread is $spread%. That is high - check for background load before trusting this row."
}

$outCsv = Join-Path $OutputDir "rigcheck_$(Get-Date -Format yyyyMMdd_HHmmss).csv"
$row = New-ManualCsvRow -GpuId $GpuId -CpuId $CpuId -GameId $GameId `
  -Resolution $Resolution -Preset $Preset `
  -AvgFps $avgFps -Low1Pct $low1 -Low01Pct $low01 `
  -UpscalingTech $UpscalingTech -UpscalingQuality $UpscalingQuality `
  -FrameGen ([bool]$FrameGen.IsPresent) -RtTier $RtTier `
  -RamGB $hw.RamGB -RamChannels $hw.RamChannels -RamMTs $hw.RamMTs `
  -Storage $hw.Storage -GameBuild $GameBuild -DriverVersion $hw.DriverVersion `
  -SourceNote "rigcheck harness; detected CPU '$($hw.CpuName)', GPU '$($hw.GpuName)'; median of $($sum.UsableRuns) runs after discarding warm-up run; spread $spread%; PresentMon col=$($sum.Column)"

Add-CsvRow -Path $outCsv -Row $row

Write-Host "`nWrote $outCsv" -ForegroundColor Green
Write-Host "avg $avgFps fps, 1% low $low1, 0.1% low $low01"
Write-Host "Copy this file into rigcheck/data/manual/ and run: npm run import:manual"
