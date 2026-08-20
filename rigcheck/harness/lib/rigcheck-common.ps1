<#
.SYNOPSIS
  Shared measurement protocol for the RIGCHECK harness.

.DESCRIPTION
  Dot-sourced by run-benchmark.ps1 (single game) and run-suite.ps1 (whole suite).
  It exists so there is exactly ONE implementation of the protocol. Two copies
  would drift, and two slightly different protocols produce rows that look
  comparable and are not — the precise failure mode this project exists to avoid.

  Nothing in this file executes on load: it defines functions only, so it is safe
  to dot-source from anywhere.

  PowerShell 5.1 compatible. No PS7-only syntax (no ternary, no ??, no
  -AsHashtable, no ForEach-Object -Parallel).
#>

# --- Environment ------------------------------------------------------------

function Assert-Admin {
  <#
    PresentMon opens an ETW session. That is a privileged operation, and without
    it the capture silently yields nothing useful rather than failing loudly.
  #>
  $id = [Security.Principal.WindowsIdentity]::GetCurrent()
  $p = New-Object Security.Principal.WindowsPrincipal($id)
  if (-not $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'PresentMon needs Administrator to open an ETW session. Re-run this shell as Administrator.'
  }
}

function Find-PresentMon {
  <#
    PresentMon 2.x ships as PresentMon-2.x.y-x64.exe, not PresentMon.exe, so an
    exact-name-only search fails on a stock download. Try exact names first, then
    glob, so either layout works without the operator renaming anything.
  #>
  param([Parameter(Mandatory = $true)][string]$HarnessRoot)

  $toolsDir = Join-Path $HarnessRoot 'tools'
  $exact = @(
    'PresentMon.exe',
    (Join-Path $HarnessRoot 'PresentMon.exe'),
    (Join-Path $toolsDir 'PresentMon.exe')
  )
  foreach ($c in $exact) {
    $cmd = Get-Command $c -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    if (Test-Path -LiteralPath $c) { return (Resolve-Path -LiteralPath $c).Path }
  }

  foreach ($dir in @($HarnessRoot, $toolsDir)) {
    if (Test-Path -LiteralPath $dir) {
      $hit = @(Get-ChildItem -LiteralPath $dir -Filter 'PresentMon*.exe' -File -ErrorAction SilentlyContinue |
        Sort-Object -Property Name -Descending)
      if ($hit.Count -gt 0) { return $hit[0].FullName }
    }
  }

  throw "PresentMon not found. Put PresentMon.exe (or the stock PresentMon-2.x.y-x64.exe) in $HarnessRoot, in $toolsDir, or on PATH."
}

function Get-PresentMonVersion {
  <# Best effort. Used only to warn about 1.x, never to change behaviour. #>
  param([Parameter(Mandatory = $true)][string]$Path)
  try {
    $v = (Get-Item -LiteralPath $Path).VersionInfo.FileVersion
    if ($v) { return "$v".Trim() }
  } catch { }
  $name = Split-Path -Leaf $Path
  if ($name -match '(\d+\.\d+\.\d+)') { return $Matches[1] }
  return ''
}

# --- Hardware ---------------------------------------------------------------
# Logged automatically so a row can never be attributed to the wrong machine.
# detect-hardware.ps1 does the deep, auditable version of this; what follows is
# the minimum a CSV row needs, with the same honesty about what is a guess.

function Get-DriveLetterFromPath {
  param([string]$Path)
  if (-not $Path) { return $null }
  if ($Path -match '^([A-Za-z]):') { return $Matches[1].ToUpper() }
  return $null
}

function Get-PhysicalDiskForPath {
  <# Resolve a path to the physical disk backing it. Returns $null if it cannot. #>
  param([string]$Path)
  $letter = Get-DriveLetterFromPath -Path $Path
  if (-not $letter) { return $null }
  try {
    $part = Get-Partition -DriveLetter $letter -ErrorAction Stop | Select-Object -First 1
    if (-not $part) { return $null }
    $diskNumber = $part.DiskNumber
    $pd = @(Get-PhysicalDisk -ErrorAction Stop | Where-Object { "$($_.DeviceId)" -eq "$diskNumber" })
    if ($pd.Count -gt 0) { return $pd[0] }
    $disk = Get-Disk -Number $diskNumber -ErrorAction Stop
    $viaAssoc = @($disk | Get-PhysicalDisk -ErrorAction Stop)
    if ($viaAssoc.Count -gt 0) { return $viaAssoc[0] }
    return $null
  } catch {
    return $null
  }
}

function Get-NvmeLinkGen {
  <#
    PCIe link speed of the NVMe controller, so gen3 and gen4 are not conflated.
    Entirely best effort: returns 0 when it cannot be established, and every
    caller must treat 0 as "unknown", not as "gen3".
  #>
  param([Parameter(Mandatory = $true)]$PhysicalDisk)
  try {
    $pnp = @(Get-PnpDevice -Class 'DiskDrive' -ErrorAction Stop |
      Where-Object { $_.FriendlyName -eq $PhysicalDisk.FriendlyName })
    if ($pnp.Count -eq 0) { return 0 }
    $parent = (Get-PnpDeviceProperty -InstanceId $pnp[0].InstanceId -KeyName 'DEVPKEY_Device_Parent' -ErrorAction Stop).Data
    if (-not $parent) { return 0 }

    $speed = $null
    foreach ($key in @('DEVPKEY_PciDevice_CurrentLinkSpeed', '{3ab22e31-8264-4b4e-9af5-a8d2d8e33e62} 15')) {
      try {
        $speed = (Get-PnpDeviceProperty -InstanceId $parent -KeyName $key -ErrorAction Stop).Data
        if ($null -ne $speed) { break }
      } catch { }
    }
    if ($null -eq $speed) { return 0 }
    # Enumerated link speed: 1 = 2.5 GT/s (gen1) .. 5 = 32 GT/s (gen5).
    $n = [int]$speed
    if ($n -ge 1 -and $n -le 5) { return $n }
    return 0
  } catch {
    return 0
  }
}

function Get-StorageClass {
  <#
    Map a path to one of the catalogue's storage enum values. Returns the class
    plus whether it was a guess, because "sata-ssd" as a silent default on an
    unresolvable disk is exactly the kind of invented fact this project bans.
  #>
  param([string]$Path)

  $out = [pscustomobject]([ordered]@{
    Class      = 'sata-ssd'
    BusType    = ''
    MediaType  = ''
    Model      = ''
    BestEffort = $true
    Note       = ''
  })

  $pd = Get-PhysicalDiskForPath -Path $Path
  if (-not $pd) {
    $out.Note = "Could not resolve a physical disk for '$Path'. Defaulted to sata-ssd — set it by hand if that is wrong."
    return $out
  }

  $out.BusType = "$($pd.BusType)"
  $out.MediaType = "$($pd.MediaType)"
  $out.Model = "$($pd.FriendlyName)"

  if ($out.MediaType -eq 'HDD') {
    $out.Class = 'hdd'
    $out.BestEffort = $false
    $out.Note = 'Rotational media reported by the storage stack.'
    return $out
  }

  if ($out.BusType -eq 'NVMe') {
    $gen = Get-NvmeLinkGen -PhysicalDisk $pd
    if ($gen -ge 4) {
      $out.Class = 'nvme-gen4'
      $out.BestEffort = $false
      $out.Note = "NVMe, PCIe link speed reports gen$gen."
    } elseif ($gen -eq 3) {
      $out.Class = 'nvme-gen3'
      $out.BestEffort = $false
      $out.Note = 'NVMe, PCIe link speed reports gen3.'
    } else {
      $out.Class = 'nvme-gen3'
      $out.BestEffort = $true
      $out.Note = 'NVMe, but PCIe link speed could not be read. Assumed gen3 — check the drive and set nvme-gen4 by hand if it is a gen4 part.'
    }
    return $out
  }

  if ($out.MediaType -eq 'SSD') {
    $out.Class = 'sata-ssd'
    $out.BestEffort = $false
    $out.Note = "SSD on a $($out.BusType) bus."
    return $out
  }

  $out.Note = "Storage stack reported MediaType '$($out.MediaType)' on bus '$($out.BusType)', which does not map cleanly. Defaulted to sata-ssd."
  return $out
}

function Get-RamChannelGuess {
  <#
    THE field most likely to be wrong, and single vs dual channel is a large
    effect on CPU-bound results, so it gets its own function and its own warning.

    Slot count is not channel count. A four-slot consumer board with four sticks
    is still dual channel. We only trust a value derived from channel letters in
    the SMBIOS DeviceLocator ("DIMM_A1", "Channel B-DIMM 0"); everything else is
    reported as a guess.
  #>
  param([Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Modules)

  $out = [pscustomobject]([ordered]@{
    Channels   = 1
    BestEffort = $true
    Note       = ''
  })

  if ($Modules.Count -eq 0) {
    $out.Note = 'No memory modules reported by SMBIOS. Defaulted to 1 channel.'
    return $out
  }

  $letters = New-Object System.Collections.ArrayList
  foreach ($m in $Modules) {
    $loc = "$($m.DeviceLocator)"
    $letter = $null
    if ($loc -match '(?i)channel\s*([A-H])') { $letter = $Matches[1] }
    elseif ($loc -match '(?i)(?:^|[_\- ])([A-H])\s*[0-9]$') { $letter = $Matches[1] }
    if ($letter) {
      $letter = $letter.ToUpper()
      if (-not $letters.Contains($letter)) { [void]$letters.Add($letter) }
    }
  }

  if ($letters.Count -gt 0) {
    $n = $letters.Count
    # The importer only accepts 1, 2, 4 or 8; round DOWN to a legal value rather
    # than emit a row that is rejected at import time.
    if ($n -ge 8) { $n = 8 } elseif ($n -ge 4) { $n = 4 } elseif ($n -ge 2) { $n = 2 } else { $n = 1 }
    $out.Channels = $n
    $out.BestEffort = $false
    $out.Note = "Derived from SMBIOS channel letters: $((($letters.ToArray()) | Sort-Object) -join ', ')."
    return $out
  }

  if ($Modules.Count -ge 2) {
    $out.Channels = 2
    $out.Note = "GUESS: SMBIOS gave no channel letters. $($Modules.Count) modules populated, assumed dual channel because nearly every consumer desktop is. If this is a HEDT/Threadripper/Xeon board it is probably 4 or 8; if the sticks are in the wrong slots it may really be 1. VERIFY IN BIOS."
  } else {
    $out.Channels = 1
    $out.Note = 'GUESS: a single module is populated, so single channel. Verify in BIOS.'
  }
  return $out
}

function Get-HardwareProfile {
  <#
    The minimum a data/manual/ row needs. Every field that is a guess also lands
    in .Warnings so a caller can print it; nothing is silently smoothed over.
  #>
  [CmdletBinding()]
  param([string]$GamesPath = '')

  $warnings = New-Object System.Collections.ArrayList

  $cpu = Get-CimInstance -ClassName Win32_Processor -ErrorAction Stop | Select-Object -First 1

  $controllers = @(Get-CimInstance -ClassName Win32_VideoController -ErrorAction Stop |
    Where-Object { $_.Name -notmatch '(?i)basic display|remote display|displaylink|virtual|parsec|mirage|idd ' })
  if ($controllers.Count -eq 0) {
    $controllers = @(Get-CimInstance -ClassName Win32_VideoController -ErrorAction Stop)
  }
  $discrete = @($controllers | Where-Object { $_.Name -match '(?i)nvidia|geforce|titan|quadro|radeon r[579x]|radeon rx|radeon pro|radeon vii|arc\(tm\)|arc a\d|arc b\d' })
  if ($discrete.Count -gt 0) {
    $gpu = @($discrete | Sort-Object -Property AdapterRAM -Descending)[0]
  } else {
    $gpu = @($controllers | Sort-Object -Property AdapterRAM -Descending)[0]
  }
  if ($controllers.Count -gt 1) {
    [void]$warnings.Add("$($controllers.Count) display adapters present; using '$($gpu.Name)'. If the game renders on a different adapter this row is mis-attributed.")
  }

  $mem = @(Get-CimInstance -ClassName Win32_PhysicalMemory -ErrorAction Stop)
  $totalGB = 0
  if ($mem.Count -gt 0) {
    $totalGB = [math]::Round((($mem | Measure-Object -Property Capacity -Sum).Sum / 1GB), 0)
  }

  $chan = Get-RamChannelGuess -Modules $mem
  if ($chan.BestEffort) { [void]$warnings.Add("RAM channels: $($chan.Note)") }

  # Speed: prefer the CONFIGURED clock (what XMP/EXPO actually applied) over the
  # SPD-rated Speed field, because the rated speed of a kit running at JEDEC
  # defaults is a different machine as far as the model is concerned.
  $speed = 0
  $configured = @($mem | ForEach-Object { [int]$_.ConfiguredClockSpeed } | Where-Object { $_ -gt 0 })
  if ($configured.Count -gt 0) {
    $speed = ($configured | Measure-Object -Maximum).Maximum
    if ((($configured | Measure-Object -Minimum).Minimum) -ne $speed) {
      [void]$warnings.Add("Memory modules report different configured speeds ($(($configured | Sort-Object -Unique) -join '/') MT/s). Mixed kits run at the slowest common speed; recorded the highest, so check this.")
    }
  } else {
    $rated = @($mem | ForEach-Object { [int]$_.Speed } | Where-Object { $_ -gt 0 })
    if ($rated.Count -gt 0) {
      $speed = ($rated | Measure-Object -Maximum).Maximum
      [void]$warnings.Add('Memory: ConfiguredClockSpeed unavailable, used the SPD-rated Speed field. If XMP/EXPO is off this OVERSTATES the running speed.')
    }
  }

  $sysStore = Get-StorageClass -Path $env:SystemDrive
  $store = $sysStore
  $storeSource = 'system drive'
  if ($GamesPath) {
    $gameStore = Get-StorageClass -Path $GamesPath
    $store = $gameStore
    $storeSource = "games path '$GamesPath'"
    if ($gameStore.Class -ne $sysStore.Class) {
      [void]$warnings.Add("Games drive ($($gameStore.Class)) differs from the system drive ($($sysStore.Class)). Recorded the games drive, which is the one that matters for streaming and load stutter.")
    }
  }
  if ($store.BestEffort) { [void]$warnings.Add("Storage ($storeSource): $($store.Note)") }

  return [pscustomobject]([ordered]@{
    CpuName       = "$($cpu.Name)".Trim()
    GpuName       = "$($gpu.Name)".Trim()
    DriverVersion = "$($gpu.DriverVersion)".Trim()
    RamGB         = $totalGB
    RamChannels   = [Math]::Max(1, $chan.Channels)
    RamMTs        = $speed
    Storage       = $store.Class
    StorageNote   = $store.Note
    Warnings      = @($warnings.ToArray())
  })
}

# --- Frametime statistics ---------------------------------------------------

function Get-FrameStats {
  <#
    Turn one PresentMon CSV into avg / 1% low / 0.1% low.

    Two column vocabularies exist. PresentMon 1.x (and 2.x run with
    --v1_metrics) emits MsBetweenPresents; PresentMon 2.x by default emits
    FrameTime. Both are milliseconds between the start of consecutive frames, so
    either is usable — but only if the script recognises them, and a script that
    knows only the 1.x names dies on a stock 2.x capture.
  #>
  param([Parameter(Mandatory = $true)][string]$CsvPath)

  $rows = @(Import-Csv -Path $CsvPath)
  if ($rows.Count -eq 0) { throw "PresentMon produced no rows in $CsvPath" }

  $names = @($rows[0].PSObject.Properties.Name)

  $col = $null
  foreach ($c in @('MsBetweenPresents', 'msBetweenPresents', 'FrameTime', 'frameTime', 'MsBetweenDisplayChange', 'msBetweenDisplayChange', 'DisplayedTime')) {
    if ($names -contains $c) { $col = $c; break }
  }
  if (-not $col) { throw "No frametime column found in $CsvPath. Columns: $($names -join ', ')" }

  # A capture with no --process_name filter records EVERY presenting process:
  # the game, the launcher, the desktop compositor. Averaging across them is
  # silent corruption, so take the dominant process and say so out loud.
  $procCol = $null
  foreach ($c in @('Application', 'ProcessName', 'process_name')) {
    if ($names -contains $c) { $procCol = $c; break }
  }
  $selected = $rows
  $process = ''
  $mixNote = ''
  if ($procCol) {
    $groups = @($rows | Group-Object -Property $procCol | Sort-Object -Property Count -Descending)
    if ($groups.Count -gt 0) {
      $selected = @($groups[0].Group)
      $process = "$($groups[0].Name)"
    }
    if ($groups.Count -gt 1) {
      $others = (@($groups | Select-Object -Skip 1 | ForEach-Object { "$($_.Name) ($($_.Count))" }) -join ', ')
      $mixNote = "Capture contained $($groups.Count) presenting processes. Used '$process' ($($groups[0].Count) frames); ignored: $others."
      Write-Warning $mixNote
    }
  }

  $ft = @($selected | ForEach-Object { [double]$_.$col } | Where-Object { $_ -gt 0 })
  if ($ft.Count -lt 100) { throw "Only $($ft.Count) frames captured — too few to be meaningful." }

  $sorted = @($ft | Sort-Object -Descending)    # descending frametime = ascending FPS
  $avg = 1000.0 / (($ft | Measure-Object -Average).Average)
  # 1% low is conventionally the average of the worst 1% of FRAMETIMES.
  $n1 = [Math]::Max(1, [int]($sorted.Count * 0.01))
  $n01 = [Math]::Max(1, [int]($sorted.Count * 0.001))
  $low1 = 1000.0 / ((@($sorted[0..($n1 - 1)]) | Measure-Object -Average).Average)
  $low01 = 1000.0 / ((@($sorted[0..($n01 - 1)]) | Measure-Object -Average).Average)

  return [pscustomobject]([ordered]@{
    AvgFps   = [math]::Round($avg, 1)
    Low1Pct  = [math]::Round($low1, 1)
    Low01Pct = [math]::Round($low01, 1)
    Frames   = $ft.Count
    Column   = $col
    Process  = $process
    MixNote  = $mixNote
  })
}

function Get-Median {
  <#
    Median, not mean, so one background-task spike does not move the figure.

    NOTE: the obvious `$s[[int]($s.Count / 2)]` is wrong in PowerShell for some
    odd counts, because [int] rounds half to EVEN: [int]1.5 is 2, so a 3-element
    set returns its maximum. Floor is the only safe form.
  #>
  param([Parameter(Mandatory = $true)][double[]]$Values)

  if ($Values.Count -eq 0) { throw 'Get-Median: no values supplied.' }
  $s = @($Values | Sort-Object)
  $n = $s.Count
  if ($n % 2 -eq 1) { return $s[[int][math]::Floor($n / 2)] }
  $hi = [int]($n / 2)
  return ($s[$hi - 1] + $s[$hi]) / 2
}

function Get-RunSummary {
  <#
    The reduction step of the protocol, in one place:
      - discard run 1 (shader caches and clocks have not settled; it is
        consistently the outlier)
      - report the MEDIAN of the rest
      - compute run-to-run spread so a noisy machine is visible, not averaged away
  #>
  param(
    [Parameter(Mandatory = $true)][object[]]$Results,
    [double]$SpreadWarnPercent = 5
  )

  if ($Results.Count -eq 0) { throw 'Get-RunSummary: no runs supplied.' }

  if ($Results.Count -gt 1) {
    $usable = @($Results[1..($Results.Count - 1)])
  } else {
    $usable = @($Results)
  }

  $avgList = [double[]]@($usable | ForEach-Object { [double]$_.AvgFps })
  $avgFps = [math]::Round((Get-Median -Values $avgList), 1)
  $low1 = [math]::Round((Get-Median -Values ([double[]]@($usable | ForEach-Object { [double]$_.Low1Pct }))), 1)
  $low01 = [math]::Round((Get-Median -Values ([double[]]@($usable | ForEach-Object { [double]$_.Low01Pct }))), 1)

  $spread = 0
  if ($usable.Count -gt 1 -and $avgFps -gt 0) {
    $mn = ($avgList | Measure-Object -Minimum).Minimum
    $mx = ($avgList | Measure-Object -Maximum).Maximum
    $spread = [math]::Round((($mx - $mn) / $avgFps) * 100, 1)
  }

  return [pscustomobject]([ordered]@{
    AvgFps      = $avgFps
    Low1Pct     = $low1
    Low01Pct    = $low01
    SpreadPct   = $spread
    UsableRuns  = $usable.Count
    TotalRuns   = $Results.Count
    SpreadHigh  = ($spread -gt $SpreadWarnPercent)
    Column      = $Results[0].Column
  })
}

# --- Capture ----------------------------------------------------------------

function Invoke-CaptureRuns {
  <#
    Warm up, capture, repeat. The warm-up is not padding: it is shader
    compilation and clock/fan settling. Capturing through it measures the
    compiler, not the game.
  #>
  param(
    [Parameter(Mandatory = $true)][string]$PresentMonPath,
    [Parameter(Mandatory = $true)][string]$RawPrefix,
    [Parameter(Mandatory = $true)][string]$OutputDir,
    [int]$Runs = 3,
    [int]$WarmupSeconds = 45,
    [int]$CaptureSeconds = 60,
    [string]$ProcessName = ''
  )

  New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
  $results = New-Object System.Collections.ArrayList

  for ($i = 1; $i -le $Runs; $i++) {
    Write-Host "`nRun $i of ${Runs}: warming up $WarmupSeconds s..." -ForegroundColor Cyan
    if ($WarmupSeconds -gt 0) { Start-Sleep -Seconds $WarmupSeconds }

    $csv = Join-Path $OutputDir ("{0}_run{1}.csv" -f $RawPrefix, $i)
    # PresentMon will not overwrite an existing output file; on a resumed run a
    # stale file would otherwise be silently re-parsed as if it were fresh.
    if (Test-Path -LiteralPath $csv) { Remove-Item -LiteralPath $csv -Force }

    $pmArgs = @('--output_file', $csv, '--timed', $CaptureSeconds, '--terminate_after_timed', '--stop_existing_session')
    if ($ProcessName) { $pmArgs += @('--process_name', $ProcessName) }

    Write-Host "Capturing $CaptureSeconds s..." -ForegroundColor Cyan
    & $PresentMonPath @pmArgs | Out-Null
    if ($LASTEXITCODE -ne 0) {
      Write-Warning "PresentMon exited with code $LASTEXITCODE. Continuing to check for output."
    }

    if (-not (Test-Path -LiteralPath $csv)) {
      # Some PresentMon builds suffix the file rather than overwrite. Pick up the
      # newest match instead of failing on a filename technicality.
      $alt = @(Get-ChildItem -LiteralPath $OutputDir -Filter ("{0}_run{1}*.csv" -f $RawPrefix, $i) -File -ErrorAction SilentlyContinue |
        Sort-Object -Property LastWriteTime -Descending)
      if ($alt.Count -gt 0) {
        $csv = $alt[0].FullName
      } else {
        throw "PresentMon produced no output for run $i (expected $csv). Is it running as Administrator, and is the game actually presenting?"
      }
    }

    $stats = Get-FrameStats -CsvPath $csv
    Write-Host ("  avg {0} fps, 1% low {1}, 0.1% low {2} ({3} frames)" -f $stats.AvgFps, $stats.Low1Pct, $stats.Low01Pct, $stats.Frames)
    [void]$results.Add($stats)
  }

  return , @($results.ToArray())
}

# --- Output -----------------------------------------------------------------

function New-ManualCsvRow {
  <#
    ONE definition of the data/manual/ row shape, shared by the single-game and
    suite runners, so the two can never emit subtly different schemas.
  #>
  param(
    [string]$GpuId = '',
    [string]$CpuId = '',
    [Parameter(Mandatory = $true)][string]$GameId,
    [Parameter(Mandatory = $true)][string]$Resolution,
    [Parameter(Mandatory = $true)][string]$Preset,
    [Parameter(Mandatory = $true)][double]$AvgFps,
    $Low1Pct = $null,
    $Low01Pct = $null,
    [string]$UpscalingTech = 'none',
    [string]$UpscalingQuality = 'native',
    [bool]$FrameGen = $false,
    [string]$RtTier = 'off',
    $RamGB = $null,
    $RamChannels = $null,
    $RamMTs = $null,
    $RamCl = $null,
    [string]$Storage = '',
    [string]$GameBuild = '',
    [string]$DriverVersion = '',
    [string]$Api = '',
    [string]$Date = '',
    [string]$SourceNote = ''
  )

  if (-not $Date) { $Date = (Get-Date -Format 'yyyy-MM-dd') }

  return [pscustomobject]([ordered]@{
    gpu_id            = $GpuId
    cpu_id            = $CpuId
    game_id           = $GameId
    resolution        = $Resolution
    preset            = $Preset
    avg_fps           = $AvgFps
    low_1pct          = $Low1Pct
    low_01pct         = $Low01Pct
    upscaling_tech    = $UpscalingTech
    upscaling_quality = $UpscalingQuality
    frame_gen         = $FrameGen.ToString().ToLower()
    rt_tier           = $RtTier
    ram_gb            = $RamGB
    ram_channels      = $RamChannels
    ram_mts           = $RamMTs
    ram_cl            = $RamCl
    storage           = $Storage
    game_build        = $GameBuild
    driver_version    = $DriverVersion
    api               = $Api
    date              = $Date
    source_note       = $SourceNote
  })
}

function Add-CsvRow {
  <#
    Append one row to a CSV, creating it with a header if needed.

    Written through .NET rather than Export-Csv -Append for two reasons:

    1. Encoding. PowerShell 5.1's -Encoding UTF8 writes a BYTE ORDER MARK, and
       its default is ASCII, which silently mangles any non-ASCII character in a
       GPU name or source note into "?". This importer survives a BOM (its parser
       trims the field, and JavaScript trim() happens to strip U+FEFF) but Excel,
       naive parsers and diffs do not, so UTF-8 without BOM is written explicitly
       rather than relied on by luck.
    2. Header safety. Appending to a file whose header does not match the row
       being written would produce a CSV where columns and values disagree — data
       that looks valid and is not. That is checked here and refused.
  #>
  param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)][psobject]$Row
  )

  $lines = @($Row | ConvertTo-Csv -NoTypeInformation)
  $enc = New-Object System.Text.UTF8Encoding($false)

  if (Test-Path -LiteralPath $Path) {
    $existingHeader = @(Get-Content -LiteralPath $Path -TotalCount 1)
    if ($existingHeader.Count -gt 0 -and $existingHeader[0] -ne $lines[0]) {
      throw "Refusing to append to $Path : its header does not match the row being written.`n  file: $($existingHeader[0])`n  row : $($lines[0])"
    }
    $body = (@($lines[1..($lines.Count - 1)]) -join "`r`n") + "`r`n"
    [System.IO.File]::AppendAllText($Path, $body, $enc)
  } else {
    $dir = Split-Path -Parent $Path
    if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $body = ($lines -join "`r`n") + "`r`n"
    [System.IO.File]::WriteAllText($Path, $body, $enc)
  }
}

function Write-JsonAtomic {
  <# Write JSON via a temp file so an interrupt cannot leave a half-written state file. #>
  param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)]$Object,
    [int]$Depth = 8
  )
  $dir = Split-Path -Parent $Path
  if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
  $json = $Object | ConvertTo-Json -Depth $Depth
  $enc = New-Object System.Text.UTF8Encoding($false)
  $tmp = "$Path.tmp"
  [System.IO.File]::WriteAllText($tmp, $json, $enc)
  Move-Item -LiteralPath $tmp -Destination $Path -Force
}

function ConvertTo-Hashtable {
  <# PowerShell 5.1 has no ConvertFrom-Json -AsHashtable, so do it by hand. #>
  param([Parameter(Mandatory = $true)][AllowNull()]$InputObject)
  $ht = @{}
  if ($null -eq $InputObject) { return $ht }
  foreach ($p in $InputObject.PSObject.Properties) { $ht[$p.Name] = $p.Value }
  return $ht
}

function Get-OrDefault {
  <# Read a property off a ConvertFrom-Json object, falling back when absent/blank. #>
  param(
    [AllowNull()]$Object,
    [Parameter(Mandatory = $true)][string]$Name,
    $Default = $null
  )
  if ($null -eq $Object) { return $Default }
  $prop = $Object.PSObject.Properties[$Name]
  if ($null -eq $prop) { return $Default }
  if ($null -eq $prop.Value) { return $Default }
  if (($prop.Value -is [string]) -and $prop.Value -eq '') { return $Default }
  return $prop.Value
}
