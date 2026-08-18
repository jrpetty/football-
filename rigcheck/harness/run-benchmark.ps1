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
  noise — and noisy fixtures are worse than no fixtures, because they look like
  signal to the calibrator.

.NOTES
  Requires: Administrator (ETW capture), PresentMon 2.x on PATH or alongside.
  PresentMon 1.x and 2.x have different CSV schemas; this script targets 2.x and
  falls back to 1.x column names if it detects them.
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

function Assert-Admin {
  $id = [Security.Principal.WindowsIdentity]::GetCurrent()
  $p = New-Object Security.Principal.WindowsPrincipal($id)
  if (-not $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'PresentMon needs Administrator to open an ETW session. Re-run this shell as Administrator.'
  }
}

function Find-PresentMon {
  $candidates = @('PresentMon.exe', "$PSScriptRoot\PresentMon.exe", "$PSScriptRoot\tools\PresentMon.exe")
  foreach ($c in $candidates) {
    $cmd = Get-Command $c -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    if (Test-Path $c) { return (Resolve-Path $c).Path }
  }
  throw "PresentMon not found. Place PresentMon.exe in $PSScriptRoot or put it on PATH."
}

# --- Hardware detection -----------------------------------------------------
# Logged automatically so a row can never be attributed to the wrong machine.
function Get-HardwareProfile {
  $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
  $gpu = Get-CimInstance Win32_VideoController |
    Where-Object { $_.AdapterRAM -ne $null -or $_.Name -notmatch 'Basic Display' } |
    Sort-Object AdapterRAM -Descending | Select-Object -First 1

  $mem = Get-CimInstance Win32_PhysicalMemory
  $totalGB = [math]::Round(($mem | Measure-Object -Property Capacity -Sum).Sum / 1GB, 0)
  # Distinct channel count is approximated by populated banks; verify manually
  # on boards that report oddly, because single vs dual channel is a large effect.
  $channels = ($mem | Select-Object -ExpandProperty BankLabel -Unique).Count
  $speed = ($mem | Measure-Object -Property Speed -Maximum).Maximum

  $disk = Get-PhysicalDisk | Where-Object { $_.DeviceId -eq 0 } | Select-Object -First 1
  $storage = 'sata-ssd'
  if ($disk) {
    if ($disk.MediaType -eq 'HDD') { $storage = 'hdd' }
    elseif ($disk.BusType -eq 'NVMe') { $storage = 'nvme-gen3' }
  }

  [pscustomobject]@{
    CpuName       = $cpu.Name.Trim()
    GpuName       = $gpu.Name.Trim()
    DriverVersion = $gpu.DriverVersion
    RamGB         = $totalGB
    RamChannels   = [Math]::Max(1, $channels)
    RamMTs        = $speed
    Storage       = $storage
  }
}

# --- Frametime statistics ---------------------------------------------------
function Get-FrameStats {
  param([string]$CsvPath)

  $rows = Import-Csv $CsvPath
  if (-not $rows -or $rows.Count -eq 0) { throw "PresentMon produced no rows in $CsvPath" }

  # PresentMon 2.x uses MsBetweenPresents; 1.x used the same name but older
  # builds emit msBetweenPresents. Accept either rather than failing late.
  $col = $null
  foreach ($c in @('MsBetweenPresents', 'msBetweenPresents', 'MsBetweenDisplayChange')) {
    if ($rows[0].PSObject.Properties.Name -contains $c) { $col = $c; break }
  }
  if (-not $col) { throw "No frametime column found in $CsvPath. Columns: $($rows[0].PSObject.Properties.Name -join ', ')" }

  $ft = $rows | ForEach-Object { [double]$_.$col } | Where-Object { $_ -gt 0 }
  if ($ft.Count -lt 100) { throw "Only $($ft.Count) frames captured — too few to be meaningful." }

  $sorted = $ft | Sort-Object -Descending    # descending frametime = ascending FPS
  $avg = 1000.0 / (($ft | Measure-Object -Average).Average)
  # 1% low is conventionally the average of the worst 1% of FRAMETIMES.
  $n1 = [Math]::Max(1, [int]($sorted.Count * 0.01))
  $n01 = [Math]::Max(1, [int]($sorted.Count * 0.001))
  $low1 = 1000.0 / (($sorted[0..($n1 - 1)] | Measure-Object -Average).Average)
  $low01 = 1000.0 / (($sorted[0..($n01 - 1)] | Measure-Object -Average).Average)

  [pscustomobject]@{
    AvgFps  = [math]::Round($avg, 1)
    Low1Pct = [math]::Round($low1, 1)
    Low01Pct = [math]::Round($low01, 1)
    Frames  = $ft.Count
  }
}

# --- Main -------------------------------------------------------------------
Assert-Admin
$pm = Find-PresentMon
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$hw = Get-HardwareProfile

Write-Host "RIGCHECK harness" -ForegroundColor Cyan
Write-Host "  CPU     $($hw.CpuName)"
Write-Host "  GPU     $($hw.GpuName)  driver $($hw.DriverVersion)"
Write-Host "  RAM     $($hw.RamGB)GB, $($hw.RamChannels) channel(s), $($hw.RamMTs) MT/s"
Write-Host "  Storage $($hw.Storage)"
Write-Host ""
if (-not $CpuId -or -not $GpuId) {
  Write-Warning "CpuId/GpuId not supplied. Detected names are written to source_note; you must map them to catalogue ids before import, or the importer will reject the rows."
}

Write-Host "Start the game, load the benchmark scene, then leave it running." -ForegroundColor Yellow
Write-Host "Warm-up is $WarmupSeconds s per run (shader compilation and clock settling), capture is $CaptureSeconds s."
Read-Host "Press Enter when the scene is running"

$results = @()
for ($i = 1; $i -le $Runs; $i++) {
  Write-Host "`nRun $i of ${Runs}: warming up $WarmupSeconds s..." -ForegroundColor Cyan
  Start-Sleep -Seconds $WarmupSeconds

  $csv = Join-Path $OutputDir "raw_${GameId}_${Resolution}_run${i}.csv"
  $args = @('--output_file', $csv, '--timed', $CaptureSeconds, '--terminate_after_timed', '--stop_existing_session')
  if ($ProcessName) { $args += @('--process_name', $ProcessName) }

  Write-Host "Capturing $CaptureSeconds s..." -ForegroundColor Cyan
  & $pm @args | Out-Null

  $stats = Get-FrameStats -CsvPath $csv
  Write-Host ("  avg {0} fps, 1% low {1}, 0.1% low {2} ({3} frames)" -f $stats.AvgFps, $stats.Low1Pct, $stats.Low01Pct, $stats.Frames)
  $results += $stats
}

# The first run is discarded: shader caches and clocks have not settled, and it
# is consistently the outlier. Report the MEDIAN of the rest, not the mean, so a
# single background-task spike does not move the recorded figure.
$usable = if ($results.Count -gt 1) { $results[1..($results.Count - 1)] } else { $results }
function Median([double[]]$xs) {
  $s = $xs | Sort-Object
  if ($s.Count % 2) { return $s[[int]($s.Count / 2)] }
  return ($s[$s.Count / 2 - 1] + $s[$s.Count / 2]) / 2
}
$avgFps = [math]::Round((Median ($usable.AvgFps)), 1)
$low1 = [math]::Round((Median ($usable.Low1Pct)), 1)
$low01 = [math]::Round((Median ($usable.Low01Pct)), 1)

$spread = if ($usable.Count -gt 1) {
  $mn = ($usable.AvgFps | Measure-Object -Minimum).Minimum
  $mx = ($usable.AvgFps | Measure-Object -Maximum).Maximum
  [math]::Round((($mx - $mn) / $avgFps) * 100, 1)
} else { 0 }
if ($spread -gt 5) {
  Write-Warning "Run-to-run spread is $spread%. That is high — check for background load before trusting this row."
}

$outCsv = Join-Path $OutputDir "rigcheck_$(Get-Date -Format yyyyMMdd_HHmmss).csv"
$row = [pscustomobject]@{
  gpu_id            = $GpuId
  cpu_id            = $CpuId
  game_id           = $GameId
  resolution        = $Resolution
  preset            = $Preset
  avg_fps           = $avgFps
  low_1pct          = $low1
  low_01pct         = $low01
  upscaling_tech    = $UpscalingTech
  upscaling_quality = $UpscalingQuality
  frame_gen         = $FrameGen.IsPresent.ToString().ToLower()
  rt_tier           = $RtTier
  ram_gb            = $hw.RamGB
  ram_channels      = $hw.RamChannels
  ram_mts           = $hw.RamMTs
  storage           = $hw.Storage
  game_build        = $GameBuild
  driver_version    = $hw.DriverVersion
  date              = (Get-Date -Format yyyy-MM-dd)
  source_note       = "rigcheck harness; detected CPU '$($hw.CpuName)', GPU '$($hw.GpuName)'; median of $($usable.Count) runs after discarding warm-up run; spread $spread%"
}
$row | Export-Csv -Path $outCsv -NoTypeInformation

Write-Host "`nWrote $outCsv" -ForegroundColor Green
Write-Host "avg $avgFps fps, 1% low $low1, 0.1% low $low01"
Write-Host "Copy this file into rigcheck/data/manual/ and run: npm run import:manual"
