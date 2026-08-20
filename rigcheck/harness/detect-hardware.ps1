#Requires -Version 5.1
<#
.SYNOPSIS
  Detect this machine's configuration and write it to JSON so it can be matched
  to RIGCHECK catalogue ids.

.DESCRIPTION
  A benchmark row is worthless if it is attributed to the wrong hardware, and the
  hardware a row belongs to has to be named in catalogue terms ("nvidia-geforce-
  rtx-3060-12gb"), not in whatever string Windows happens to report. This walks
  the machine, records what it can establish, and is explicit about what it had
  to guess.

  The output has two id fields per component:

    suggestedCpuId / suggestedGpuId   a slug derived from the detected name.
                                      A GUESS. Never used automatically.
    confirmedCpuId / confirmedGpuId   filled in only when the slug matched
                                      exactly one catalogue id, or by you.

  run-suite.ps1 -HardwareJson reads ONLY the confirmed ids. That asymmetry is
  deliberate: a wrong id silently attributes real measurements to the wrong part,
  which corrupts the fitted model in a way that looks like signal.

  Does NOT need Administrator. Every source used here is readable as a normal
  user; if you are already in an elevated shell for benchmarking that is fine too.

.PARAMETER GamesPath
  Where the games live, e.g. D:\SteamLibrary. If omitted, Steam library folders
  are detected and used when there is exactly one off the system drive.

.PARAMETER RamCl
  Memory timings CANNOT be read from Windows (see the notes on memory below).
  Pass what your BIOS or CPU-Z reports and they are recorded as operator-supplied.

.EXAMPLE
  .\detect-hardware.ps1

.EXAMPLE
  .\detect-hardware.ps1 -GamesPath D:\SteamLibrary -RamCl 16 -RamTrcd 19 -RamTrp 19 -RamTras 36

.NOTES
  UNTESTED ON REAL HARDWARE - see harness/README.md. Read the file it writes
  before trusting any of it, especially memory channel count.
#>

[CmdletBinding()]
param(
  [string]$GamesPath = '',
  [int]$RamCl = 0,
  [int]$RamTrcd = 0,
  [int]$RamTrp = 0,
  [int]$RamTras = 0,
  [string]$OutputDir = '',
  [string]$CataloguePath = ''
)

$ErrorActionPreference = 'Stop'

$libPath = Join-Path (Join-Path $PSScriptRoot 'lib') 'rigcheck-common.ps1'
if (-not (Test-Path -LiteralPath $libPath)) {
  throw "Missing $libPath. Copy the whole harness/ directory to the test machine, not just this script."
}
. $libPath

if (-not $OutputDir) { $OutputDir = Join-Path $PSScriptRoot 'out' }
if (-not $CataloguePath) {
  $CataloguePath = Join-Path (Join-Path (Split-Path -Parent $PSScriptRoot) 'data') 'catalogue'
}

# Every guess gets recorded here and printed at the end. Nothing is smoothed over.
$script:BestEffort = New-Object System.Collections.ArrayList
function Add-Guess {
  param([string]$Field, [string]$Why)
  [void]$script:BestEffort.Add([pscustomobject]([ordered]@{ field = $Field; why = $Why }))
}

function Get-RegValue {
  param([string]$Path, [string]$Name)
  try {
    $item = Get-ItemProperty -Path $Path -Name $Name -ErrorAction Stop
    return $item.$Name
  } catch {
    return $null
  }
}

function Clean-Text {
  param([string]$Text)
  if (-not $Text) { return '' }
  return (($Text -replace '\s+', ' ').Trim())
}

# --- CPU --------------------------------------------------------------------

function Get-CpuInfo {
  $cpus = @(Get-CimInstance -ClassName Win32_Processor -ErrorAction Stop)
  if ($cpus.Count -gt 1) {
    Add-Guess -Field 'cpu' -Why "$($cpus.Count) physical processors present; only the first is recorded. Multi-socket machines are not modelled."
  }
  $c = $cpus[0]
  $name = Clean-Text $c.Name

  # MaxClockSpeed is whatever firmware chose to report. On most Intel parts it is
  # the base clock, on many AMD parts it is the boost clock. It is NOT a reliable
  # answer to either question, so it is recorded under its own name and neither
  # base nor boost is inferred from it.
  $maxClock = [int]$c.MaxClockSpeed
  Add-Guess -Field 'cpu.maxClockSpeedMHz' -Why "Reported by firmware as $maxClock MHz. Vendors disagree about whether this is base or boost, so it is recorded as-is and not used for either."

  # Intel names carry the base clock; AMD names do not.
  $base = $null
  if ($name -match '@\s*([0-9.]+)\s*GHz') {
    $base = [int]([double]$Matches[1] * 1000)
  }
  if ($null -eq $base) {
    Add-Guess -Field 'cpu.baseClockMHz' -Why 'Not exposed by Windows and not present in the CPU name string. Left null rather than guessed - fill it from the vendor spec page if you need it.'
  }
  Add-Guess -Field 'cpu.boostClockMHz' -Why 'Windows does not expose the boost/turbo ceiling at all. Left null. Take it from the vendor spec page for the exact SKU.'

  $cores = [int]$c.NumberOfCores
  $threads = [int]$c.NumberOfLogicalProcessors

  # Hybrid split. For an Intel P+E part with SMT on the P cores only:
  #   threads = 2P + E, cores = P + E  =>  P = threads - cores, E = 2*cores - threads.
  # That algebra is also satisfied by an ordinary CPU with SMT disabled, so it is
  # only applied when the name looks like a hybrid Intel part, and even then it is
  # flagged as a guess.
  $pCores = $null
  $eCores = $null
  $isHybridName = ($name -match '(?i)core\s*(ultra|\(tm\)\s*ultra)') -or
                  ($name -match '(?i)i[3579]-1[2-9]\d{3}') -or
                  ($name -match '(?i)\b(1[2-9]\d{3}[A-Z]{0,2})\b.*intel') -or
                  ($name -match '(?i)intel.*\b1[2-9]\d{3}[A-Z]{0,2}\b')
  if ($isHybridName -and $threads -gt $cores -and $threads -lt (2 * $cores)) {
    $pCores = $threads - $cores
    $eCores = (2 * $cores) - $threads
    Add-Guess -Field 'cpu.pCores/eCores' -Why "INFERRED from cores=$cores threads=$threads on a name that looks like a hybrid Intel part: ${pCores}P + ${eCores}E. Windows does not report the split directly. Check it against the SKU."
  }

  return [pscustomobject]([ordered]@{
    name                 = $name
    vendor               = Clean-Text $c.Manufacturer
    cores                = $cores
    threads              = $threads
    pCores               = $pCores
    eCores               = $eCores
    baseClockMHz         = $base
    boostClockMHz        = $null
    maxClockSpeedMHz     = $maxClock
    currentClockSpeedMHz = [int]$c.CurrentClockSpeed
    socketDesignation    = Clean-Text $c.SocketDesignation
    l2CacheKB            = [int]$c.L2CacheSize
    l3CacheKB            = [int]$c.L3CacheSize
    addressWidth         = [int]$c.AddressWidth
    processorId          = ''   # deliberately not recorded: it is machine-identifying
  })
}

# --- GPU --------------------------------------------------------------------

function Get-GpuRegistryInfo {
  <#
    Win32_VideoController.AdapterRAM is a UInt32 and therefore CANNOT report more
    than 4095 MB - it wraps, so a 12GB card reports 4095MB or worse. The driver
    class key holds the real figure as a QWORD. Try that first, always.
  #>
  param([string]$AdapterName, [string]$PnpDeviceId)

  $classKey = 'HKLM:\SYSTEM\CurrentControlSet\Control\Class\{4d36e968-e325-11ce-bfc1-08002be10318}'
  $result = [pscustomobject]([ordered]@{ VramBytes = $null; Source = ''; DriverVersion = '' })
  try {
    $subs = @(Get-ChildItem -Path $classKey -ErrorAction Stop | Where-Object { $_.PSChildName -match '^\d{4}$' })
  } catch {
    return $result
  }

  $idFragment = ''
  if ($PnpDeviceId -match '(?i)(VEN_[0-9A-F]{4}&DEV_[0-9A-F]{4})') { $idFragment = $Matches[1] }

  foreach ($s in $subs) {
    $props = $null
    try { $props = Get-ItemProperty -Path $s.PSPath -ErrorAction Stop } catch { continue }
    $desc = "$($props.DriverDesc)"
    $match = $false
    if ($desc -and $AdapterName -and ($desc -eq $AdapterName)) { $match = $true }
    if (-not $match -and $idFragment -and "$($props.MatchingDeviceId)" -match [regex]::Escape($idFragment)) { $match = $true }
    if (-not $match) { continue }

    $result.DriverVersion = "$($props.DriverVersion)"
    $qw = $props.'HardwareInformation.qwMemorySize'
    if ($null -ne $qw) {
      $result.VramBytes = [int64]$qw
      $result.Source = 'registry qwMemorySize'
      return $result
    }
    $ms = $props.'HardwareInformation.MemorySize'
    if ($null -ne $ms) {
      try {
        if ($ms -is [byte[]]) { $result.VramBytes = [int64][System.BitConverter]::ToUInt32($ms, 0) }
        else { $result.VramBytes = [int64]$ms }
        $result.Source = 'registry MemorySize'
        return $result
      } catch { }
    }
    return $result
  }
  return $result
}

function Get-MarketingDriverVersion {
  <#
    Win32_VideoController.DriverVersion is the INF version (32.0.15.6094), which
    is not what anyone calls a driver version and not what a review quotes.
      NVIDIA: the last five digits of the final two fields are the branch, so
              32.0.15.6094 -> 156094 -> 56094 -> "560.94".
      AMD:    unrelated to the INF version; Adrenalin records its own string.
      Intel:  the INF version IS the marketing version.
  #>
  param([string]$Vendor, [string]$InfVersion)

  # Vendor tests are ordered and anchored. A naive -match 'ati' matches
  # "Intel Corporation" (Corpor-ATI-on), and a naive '\bamd\b' misses
  # "AuthenticAMD" because there is no word boundary inside it.
  if ($Vendor -match '(?i)nvidia') {
    $parts = @("$InfVersion" -split '\.')
    if ($parts.Count -eq 4) {
      $digits = ($parts[2] + $parts[3]) -replace '[^0-9]', ''
      if ($digits.Length -ge 5) {
        $last5 = $digits.Substring($digits.Length - 5)
        return ($last5.Substring(0, 3) + '.' + $last5.Substring(3, 2))
      }
    }
    return ''
  }

  if ($Vendor -match '(?i)\bintel\b|genuineintel') { return "$InfVersion" }

  if ($Vendor -match '(?i)advanced micro|authenticamd|(^|[^a-z])amd([^a-z]|$)|(^|[^a-z])ati([^a-z]|$)') {
    foreach ($p in @('HKLM:\SOFTWARE\AMD\CN', 'HKLM:\SOFTWARE\WOW6432Node\AMD\CN')) {
      foreach ($n in @('DriverVersion', 'RadeonSoftwareVersion', 'Version')) {
        $v = Get-RegValue -Path $p -Name $n
        if ($v) { return "$v" }
      }
    }
    return ''
  }

  return "$InfVersion"
}

function Get-GpuInfo {
  $all = @(Get-CimInstance -ClassName Win32_VideoController -ErrorAction Stop)
  $real = @($all | Where-Object { $_.Name -notmatch '(?i)basic display|remote display|displaylink|virtual|parsec|mirage|idd ' })
  if ($real.Count -eq 0) { $real = $all }

  $discrete = @($real | Where-Object { $_.Name -match '(?i)nvidia|geforce|titan|quadro|radeon r[579x]|radeon rx|radeon pro|radeon vii|arc\(tm\)|arc a\d|arc b\d' })
  if ($discrete.Count -gt 0) {
    $primary = @($discrete | Sort-Object -Property AdapterRAM -Descending)[0]
  } else {
    $primary = @($real | Sort-Object -Property AdapterRAM -Descending)[0]
  }
  if ($real.Count -gt 1) {
    Add-Guess -Field 'gpu' -Why "$($real.Count) display adapters present ($((@($real | ForEach-Object { $_.Name }) -join '; '))). Recorded '$($primary.Name)' as primary. If the game renders on a different adapter, every row is mis-attributed."
  }

  $name = Clean-Text $primary.Name
  $vendor = Clean-Text $primary.AdapterCompatibility
  $inf = "$($primary.DriverVersion)"
  $reg = Get-GpuRegistryInfo -AdapterName $name -PnpDeviceId "$($primary.PNPDeviceID)"

  $vramGB = $null
  $vramSource = ''
  if ($reg.VramBytes -and $reg.VramBytes -gt 0) {
    $vramGB = [math]::Round($reg.VramBytes / 1GB, 0)
    $vramSource = $reg.Source
  } elseif ($primary.AdapterRAM) {
    $vramGB = [math]::Round(([int64][uint32]$primary.AdapterRAM) / 1GB, 0)
    $vramSource = 'Win32_VideoController.AdapterRAM'
    Add-Guess -Field 'gpu.vramGB' -Why "Read from AdapterRAM, which is a 32-bit field and cannot report more than 4095 MB. If this card has 4GB or more the figure is WRONG - take VRAM from the vendor spec instead."
  } else {
    Add-Guess -Field 'gpu.vramGB' -Why 'Neither the driver class key nor AdapterRAM returned a size. Left null.'
  }

  $marketing = Get-MarketingDriverVersion -Vendor $vendor -InfVersion $inf
  if (-not $marketing) {
    $marketing = $inf
    Add-Guess -Field 'gpu.driverVersion' -Why "Could not derive the marketing driver version; recorded the INF version '$inf' instead. Reviews quote the marketing version (NVIDIA 560.94, AMD Adrenalin 24.1.1), so check this before comparing against published data."
  }

  return [pscustomobject]([ordered]@{
    name             = $name
    vendor           = $vendor
    vramGB           = $vramGB
    vramSource       = $vramSource
    driverVersion    = $marketing
    infDriverVersion = $inf
    driverDate       = $(if ($primary.DriverDate) { (Get-Date $primary.DriverDate -Format 'yyyy-MM-dd') } else { '' })
    videoProcessor   = Clean-Text $primary.VideoProcessor
    pnpDeviceId      = "$($primary.PNPDeviceID)"
    allAdapters      = @($real | ForEach-Object { Clean-Text $_.Name })
  })
}

# --- Memory -----------------------------------------------------------------

function Get-MemoryInfo {
  $mods = @(Get-CimInstance -ClassName Win32_PhysicalMemory -ErrorAction Stop)

  $typeMap = @{ 20 = 'DDR'; 21 = 'DDR2'; 24 = 'DDR3'; 26 = 'DDR4'; 30 = 'LPDDR4'; 34 = 'DDR5'; 35 = 'LPDDR5' }
  $formMap = @{ 8 = 'DIMM'; 12 = 'SODIMM' }

  $totalGB = 0
  if ($mods.Count -gt 0) {
    $totalGB = [math]::Round((($mods | Measure-Object -Property Capacity -Sum).Sum / 1GB), 0)
  }

  # Channels: the field this whole file exists to be honest about.
  $chan = Get-RamChannelGuess -Modules $mods
  if ($chan.BestEffort) {
    Add-Guess -Field 'memory.channels' -Why $chan.Note
  }

  $speed = $null
  $speedSource = ''
  $configured = @($mods | ForEach-Object { [int]$_.ConfiguredClockSpeed } | Where-Object { $_ -gt 0 })
  if ($configured.Count -gt 0) {
    $speed = ($configured | Measure-Object -Maximum).Maximum
    $speedSource = 'ConfiguredClockSpeed (what the modules are actually running at)'
    $min = ($configured | Measure-Object -Minimum).Minimum
    if ($min -ne $speed) {
      Add-Guess -Field 'memory.speedMTs' -Why "Modules report different configured speeds ($(($configured | Sort-Object -Unique) -join '/') MT/s). A mixed kit runs at the slowest common speed; the highest was recorded, so check this."
    }
  } else {
    $rated = @($mods | ForEach-Object { [int]$_.Speed } | Where-Object { $_ -gt 0 })
    if ($rated.Count -gt 0) {
      $speed = ($rated | Measure-Object -Maximum).Maximum
      $speedSource = 'SPD-rated Speed'
      Add-Guess -Field 'memory.speedMTs' -Why 'ConfiguredClockSpeed was unavailable, so the SPD-rated speed was recorded. If XMP/EXPO is not enabled the modules are running SLOWER than this says.'
    }
  }

  # Timings. There is no supported way to read these from Windows: SMBIOS type 17
  # carries no timing fields, and real timings live in SPD registers behind the
  # SMBus, which needs a kernel driver. So they are null unless you supply them.
  $timings = $null
  if ($RamCl -gt 0) {
    $timings = [pscustomobject]([ordered]@{
      cl     = $RamCl
      trcd   = $(if ($RamTrcd -gt 0) { $RamTrcd } else { $null })
      trp    = $(if ($RamTrp -gt 0) { $RamTrp } else { $null })
      tras   = $(if ($RamTras -gt 0) { $RamTras } else { $null })
      source = 'operator-supplied'
    })
  } else {
    Add-Guess -Field 'memory.timings' -Why 'NOT OBTAINABLE from Windows: SMBIOS carries no timing fields and SPD registers need a kernel driver. Left null. Read CL/tRCD/tRP/tRAS off your BIOS or CPU-Z and re-run with -RamCl 16 -RamTrcd 19 -RamTrp 19 -RamTras 36.'
  }

  $memType = ''
  $types = @($mods | ForEach-Object { $typeMap[[int]$_.SMBIOSMemoryType] } | Where-Object { $_ } | Sort-Object -Unique)
  if ($types.Count -eq 1) { $memType = $types[0] }
  elseif ($types.Count -gt 1) {
    $memType = ($types -join '/')
    Add-Guess -Field 'memory.type' -Why "Modules report more than one memory type ($memType), which should be impossible. Check the module list."
  }

  $moduleList = @($mods | ForEach-Object {
      [pscustomobject]([ordered]@{
        deviceLocator  = Clean-Text $_.DeviceLocator
        bankLabel      = Clean-Text $_.BankLabel
        capacityGB     = [math]::Round(($_.Capacity / 1GB), 0)
        configuredMTs  = [int]$_.ConfiguredClockSpeed
        ratedMTs       = [int]$_.Speed
        manufacturer   = Clean-Text $_.Manufacturer
        partNumber     = Clean-Text $_.PartNumber
        formFactor     = $(if ($formMap.ContainsKey([int]$_.FormFactor)) { $formMap[[int]$_.FormFactor] } else { "$($_.FormFactor)" })
        type           = $(if ($typeMap.ContainsKey([int]$_.SMBIOSMemoryType)) { $typeMap[[int]$_.SMBIOSMemoryType] } else { '' })
      })
    })

  $slots = $null
  try {
    $arr = @(Get-CimInstance -ClassName Win32_PhysicalMemoryArray -ErrorAction Stop)
    if ($arr.Count -gt 0) { $slots = [int]$arr[0].MemoryDevices }
  } catch { }

  return [pscustomobject]([ordered]@{
    totalGB            = $totalGB
    channels           = $chan.Channels
    channelsBestEffort = $chan.BestEffort
    channelsNote       = $chan.Note
    speedMTs           = $speed
    speedSource        = $speedSource
    type               = $memType
    timings            = $timings
    modulesPopulated   = $mods.Count
    slotsTotal         = $slots
    modules            = $moduleList
  })
}

# --- Storage ----------------------------------------------------------------

function Get-SteamLibraryPaths {
  <# Best effort, and only used to SUGGEST a games path. #>
  $paths = New-Object System.Collections.ArrayList
  $install = $null
  foreach ($k in @('HKLM:\SOFTWARE\WOW6432Node\Valve\Steam', 'HKLM:\SOFTWARE\Valve\Steam', 'HKCU:\SOFTWARE\Valve\Steam')) {
    $v = Get-RegValue -Path $k -Name 'InstallPath'
    if (-not $v) { $v = Get-RegValue -Path $k -Name 'SteamPath' }
    if ($v) { $install = "$v"; break }
  }
  if (-not $install) { return @() }
  [void]$paths.Add($install)

  $vdf = Join-Path (Join-Path $install 'steamapps') 'libraryfolders.vdf'
  if (Test-Path -LiteralPath $vdf) {
    try {
      $text = Get-Content -Raw -LiteralPath $vdf -ErrorAction Stop
      foreach ($m in ([regex]::Matches($text, '"path"\s*"([^"]+)"'))) {
        $p = $m.Groups[1].Value -replace '\\\\', '\'
        if ($p -and -not $paths.Contains($p)) { [void]$paths.Add($p) }
      }
    } catch { }
  }
  return @($paths.ToArray())
}

function Get-StorageInfo {
  $sysDrive = "$env:SystemDrive"
  $system = Get-StorageClass -Path $sysDrive
  if ($system.BestEffort) { Add-Guess -Field 'storage.system' -Why $system.Note }

  $libraries = @()
  foreach ($p in (Get-SteamLibraryPaths)) {
    $cls = Get-StorageClass -Path $p
    $libraries += [pscustomobject]([ordered]@{
      path      = $p
      class     = $cls.Class
      busType   = $cls.BusType
      mediaType = $cls.MediaType
      model     = $cls.Model
    })
  }

  $gamesPathUsed = $GamesPath
  if (-not $gamesPathUsed) {
    $offSystem = @($libraries | Where-Object { $_.path -and ($_.path.Substring(0, 1).ToUpper() -ne $sysDrive.Substring(0, 1).ToUpper()) })
    if ($offSystem.Count -eq 1) {
      $gamesPathUsed = $offSystem[0].path
      Add-Guess -Field 'storage.games' -Why "No -GamesPath given. Exactly one Steam library was found off the system drive ($gamesPathUsed), so that was used. If your games are elsewhere, re-run with -GamesPath."
    } elseif ($offSystem.Count -gt 1) {
      Add-Guess -Field 'storage.games' -Why "Steam libraries found on $($offSystem.Count) different drives ($((@($offSystem | ForEach-Object { $_.path }) -join '; '))). Cannot tell which one the benchmarked games are on - left null. Re-run with -GamesPath."
    }
  }

  $games = $null
  if ($gamesPathUsed) {
    $g = Get-StorageClass -Path $gamesPathUsed
    if ($g.BestEffort) { Add-Guess -Field 'storage.games' -Why $g.Note }
    $games = [pscustomobject]([ordered]@{
      path      = $gamesPathUsed
      class     = $g.Class
      busType   = $g.BusType
      mediaType = $g.MediaType
      model     = $g.Model
    })
  }

  $disks = @()
  try {
    $disks = @(Get-PhysicalDisk -ErrorAction Stop | ForEach-Object {
        [pscustomobject]([ordered]@{
          deviceId     = "$($_.DeviceId)"
          friendlyName = Clean-Text $_.FriendlyName
          mediaType    = "$($_.MediaType)"
          busType      = "$($_.BusType)"
          sizeGB       = [math]::Round(($_.Size / 1GB), 0)
        })
      })
  } catch {
    Add-Guess -Field 'storage.disks' -Why "Could not enumerate physical disks: $($_.Exception.Message)"
  }

  return [pscustomobject]([ordered]@{
    system    = [pscustomobject]([ordered]@{
      path      = $sysDrive
      class     = $system.Class
      busType   = $system.BusType
      mediaType = $system.MediaType
      model     = $system.Model
    })
    games     = $games
    libraries = $libraries
    disks     = $disks
  })
}

# --- OS and display ---------------------------------------------------------

function Get-OsInfo {
  $os = Get-CimInstance -ClassName Win32_OperatingSystem -ErrorAction Stop
  $cv = 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion'
  $ubr = Get-RegValue -Path $cv -Name 'UBR'
  $displayVersion = Get-RegValue -Path $cv -Name 'DisplayVersion'
  $build = "$($os.BuildNumber)"
  if ($null -ne $ubr) { $build = "$build.$ubr" }

  $uptimeDays = $null
  try { $uptimeDays = [math]::Round((New-TimeSpan -Start $os.LastBootUpTime -End (Get-Date)).TotalDays, 1) } catch { }
  if ($null -ne $uptimeDays -and $uptimeDays -gt 7) {
    Add-Guess -Field 'os.uptime' -Why "This machine has been up for $uptimeDays days. Long uptimes accumulate background state and memory pressure; reboot before a benchmarking session."
  }

  return [pscustomobject]([ordered]@{
    caption        = Clean-Text $os.Caption
    displayVersion = "$displayVersion"
    build          = $build
    version        = "$($os.Version)"
    architecture   = "$($os.OSArchitecture)"
    uptimeDays     = $uptimeDays
  })
}

function Get-DisplayInfo {
  $controllers = @(Get-CimInstance -ClassName Win32_VideoController -ErrorAction Stop |
    Where-Object { $_.CurrentHorizontalResolution -and $_.CurrentHorizontalResolution -gt 0 })

  $width = $null; $height = $null; $hz = $null
  if ($controllers.Count -gt 0) {
    $c = $controllers[0]
    $width = [int]$c.CurrentHorizontalResolution
    $height = [int]$c.CurrentVerticalResolution
    $hz = [int]$c.CurrentRefreshRate
  } else {
    Add-Guess -Field 'display' -Why 'No adapter reported a current display mode. Left null.'
  }
  if ($controllers.Count -gt 1) {
    Add-Guess -Field 'display' -Why "More than one adapter reports a display mode; the first was recorded. On a multi-monitor setup make sure the game is on the panel you think it is."
  }
  if ($hz -and $hz -le 60) {
    Add-Guess -Field 'display.refreshHz' -Why "Refresh rate reads $hz Hz. If this panel is capable of more, Windows is set wrong - fix it before benchmarking, because a 60 Hz mode with v-sync on caps every result at 60 fps."
  }

  $monitors = @()
  try {
    foreach ($m in @(Get-CimInstance -Namespace 'root\wmi' -ClassName 'WmiMonitorID' -ErrorAction Stop)) {
      $decode = {
        param($arr)
        if (-not $arr) { return '' }
        return ((($arr | Where-Object { $_ -gt 0 } | ForEach-Object { [char]$_ }) -join '').Trim())
      }
      $monitors += [pscustomobject]([ordered]@{
        manufacturer = (& $decode $m.ManufacturerName)
        model        = (& $decode $m.UserFriendlyName)
      })
    }
  } catch { }

  # A resolution that is not one of the catalogue's four is not directly usable.
  $catalogueRes = ''
  if ($width -and $height) {
    $key = "${width}x${height}"
    $map = @{ '1920x1080' = '1080p'; '2560x1440' = '1440p'; '3840x2160' = '2160p'; '3440x1440' = '3440x1440' }
    if ($map.ContainsKey($key)) {
      $catalogueRes = $map[$key]
    } else {
      Add-Guess -Field 'display.catalogueResolution' -Why "Desktop is ${key}, which is not one of the catalogue's four resolutions (1080p, 1440p, 2160p, 3440x1440). Set the game to one of those before capturing, or the row cannot be imported."
    }
  }

  return [pscustomobject]([ordered]@{
    widthPx             = $width
    heightPx            = $height
    refreshHz           = $hz
    catalogueResolution = $catalogueRes
    monitors            = $monitors
  })
}

# --- Motherboard ------------------------------------------------------------

function Get-MotherboardInfo {
  $bb = Get-CimInstance -ClassName Win32_BaseBoard -ErrorAction Stop | Select-Object -First 1
  $cs = Get-CimInstance -ClassName Win32_ComputerSystem -ErrorAction Stop | Select-Object -First 1
  $bios = Get-CimInstance -ClassName Win32_BIOS -ErrorAction Stop | Select-Object -First 1

  $product = Clean-Text $bb.Product

  # Chipset is not a WMI concept. Two best-effort routes, both flagged.
  $chipset = ''
  $chipsetSource = ''
  try {
    $lpc = @(Get-CimInstance -ClassName Win32_PnPEntity -ErrorAction Stop |
      Where-Object { $_.Name -match '(?i)(LPC|eSPI) Controller' })
    foreach ($d in $lpc) {
      if ("$($d.Name)" -match '(?i)\b([HBZQWXR]\d{2,3}[A-Z]?)\b') {
        $chipset = $Matches[1].ToUpper()
        $chipsetSource = "PnP device name '$(Clean-Text $d.Name)'"
        break
      }
    }
  } catch { }

  if (-not $chipset -and $product -match '(?i)\b([XBZHA]\d{3}E?)\b') {
    $chipset = $Matches[1].ToUpper()
    $chipsetSource = "parsed out of the board model '$product'"
  }

  if ($chipset) {
    Add-Guess -Field 'motherboard.chipset' -Why "'$chipset' was $chipsetSource. Windows does not report the chipset directly, so this is pattern-matching and can be wrong - check against the board's spec page."
  } else {
    Add-Guess -Field 'motherboard.chipset' -Why 'Could not be established from PnP device names or the board model string. Left blank - fill it in from the board spec page.'
  }

  return [pscustomobject]([ordered]@{
    manufacturer  = Clean-Text $bb.Manufacturer
    product       = $product
    version       = Clean-Text $bb.Version
    chipset       = $chipset
    chipsetSource = $chipsetSource
    systemModel   = Clean-Text $cs.Model
    systemVendor  = Clean-Text $cs.Manufacturer
    biosVersion   = Clean-Text $bios.SMBIOSBIOSVersion
    biosDate      = $(if ($bios.ReleaseDate) { (Get-Date $bios.ReleaseDate -Format 'yyyy-MM-dd') } else { '' })
  })
}

# --- Catalogue mapping ------------------------------------------------------

function ConvertTo-CatalogueSlug {
  <#
    Turn a Windows-reported name into the shape a catalogue id takes. This is a
    GUESS by construction: it happens to be exact for common parts
    ("AMD Ryzen 5 3600 6-Core Processor" -> amd-ryzen-5-3600) and it will be
    wrong for others, which is why nothing consumes it without confirmation.
  #>
  param([string]$Name, [string]$Vendor)

  $s = " $Name "
  $s = $s -replace '\((R|TM|r|tm)\)', ' '
  # Modern Intel reports "13th Gen Intel(R) Core(TM) i5-13600K"; the generation
  # prefix is not part of any catalogue id.
  $s = $s -replace '(?i)\s\d+(st|nd|rd|th)\s+Gen(eration)?\s', ' '
  $s = $s -replace '(?i)\s+CPU\s+', ' '
  $s = $s -replace '(?i)@\s*[0-9.]+\s*GHz', ' '
  $s = $s -replace '(?i)\b\d+-Core\b', ' '
  $s = $s -replace '(?i)\bProcessor\b', ' '
  $s = $s -replace '(?i)\bwith Radeon.*$', ' '
  $s = $s -replace '(?i)\bGraphics\b\s*$', ' '
  $s = $s -replace '[^A-Za-z0-9]+', ' '
  $s = (Clean-Text $s).ToLower()

  # Ordered, anchored vendor tests - see the note in Get-MarketingDriverVersion.
  $v = ''
  if ($Vendor -match '(?i)nvidia') { $v = 'nvidia' }
  elseif ($Vendor -match '(?i)\bintel\b|genuineintel') { $v = 'intel' }
  elseif ($Vendor -match '(?i)advanced micro|authenticamd|(^|[^a-z])amd([^a-z]|$)|(^|[^a-z])ati([^a-z]|$)') { $v = 'amd' }

  if ($v -and -not ($s -match "^$v\b")) { $s = "$v $s" }
  return ($s -replace '\s+', '-')
}

function Get-CatalogueIds {
  param([string]$File)
  $p = Join-Path $CataloguePath $File
  if (-not (Test-Path -LiteralPath $p)) { return $null }
  try {
    $d = Get-Content -Raw -Encoding UTF8 -LiteralPath $p | ConvertFrom-Json
    return @($d.records | ForEach-Object { "$($_.id)" })
  } catch {
    return $null
  }
}

function Resolve-CatalogueId {
  <#
    Confirm a slug against the catalogue when the catalogue is reachable.
    Exact unique match -> confirmed. Anything else -> candidates for a human.
  #>
  param([string]$Slug, [string[]]$Ids, [string]$Label)

  $out = [pscustomobject]([ordered]@{ Confirmed = ''; Candidates = @(); Note = '' })
  if ($null -eq $Ids) {
    $out.Note = "Catalogue not found at $CataloguePath, so '$Slug' could not be checked. Map it by hand against data/catalogue/."
    Add-Guess -Field "catalogueMapping.$Label" -Why $out.Note
    return $out
  }

  if ($Ids -contains $Slug) {
    $out.Confirmed = $Slug
    $out.Note = "Exact match against the catalogue."
    return $out
  }

  # Variant SKUs (RTX 3060 8GB vs 12GB) are separate catalogue ids, so a plain
  # slug will not match either. Offer both rather than picking one.
  $cands = @($Ids | Where-Object { $_ -like "$Slug*" })
  if ($cands.Count -eq 0) {
    $tail = ($Slug -split '-')[-1]
    if ($tail.Length -ge 3) { $cands = @($Ids | Where-Object { $_ -like "*$tail*" }) }
  }
  $out.Candidates = @($cands | Select-Object -First 8)
  if ($out.Candidates.Count -eq 1) {
    $out.Note = "One catalogue id starts with '$Slug'. It is very likely right, but it is not an exact match, so confirm it by hand."
  } elseif ($out.Candidates.Count -gt 1) {
    $out.Note = "$($out.Candidates.Count) catalogue ids could match '$Slug'. Pick one by hand - variants differ in ways that change results (VRAM size above all)."
  } else {
    $out.Note = "Nothing in the catalogue looks like '$Slug'. The part may be missing from the catalogue; do not invent an id."
  }
  Add-Guess -Field "catalogueMapping.$Label" -Why $out.Note
  return $out
}

# --- Main -------------------------------------------------------------------

Write-Host ''
Write-Host ('-' * 76) -ForegroundColor Cyan
Write-Host ' RIGCHECK hardware detection' -ForegroundColor Cyan
Write-Host ('-' * 76) -ForegroundColor Cyan

$cpu = Get-CpuInfo
$gpu = Get-GpuInfo
$memory = Get-MemoryInfo
$storage = Get-StorageInfo
$os = Get-OsInfo
$display = Get-DisplayInfo
$board = Get-MotherboardInfo

$cpuSlug = ConvertTo-CatalogueSlug -Name $cpu.name -Vendor $cpu.vendor
$gpuSlug = ConvertTo-CatalogueSlug -Name $gpu.name -Vendor $gpu.vendor
$cpuIds = Get-CatalogueIds -File 'cpus.json'
$gpuIds = Get-CatalogueIds -File 'gpus.json'
$cpuMatch = Resolve-CatalogueId -Slug $cpuSlug -Ids $cpuIds -Label 'cpu'
$gpuMatch = Resolve-CatalogueId -Slug $gpuSlug -Ids $gpuIds -Label 'gpu'

$mapping = [pscustomobject]([ordered]@{
  suggestedCpuId = $cpuSlug
  suggestedGpuId = $gpuSlug
  confirmedCpuId = $cpuMatch.Confirmed
  confirmedGpuId = $gpuMatch.Confirmed
  cpuCandidates  = @($cpuMatch.Candidates)
  gpuCandidates  = @($gpuMatch.Candidates)
  cpuNote        = $cpuMatch.Note
  gpuNote        = $gpuMatch.Note
  howToConfirm   = "Find the exact id in rigcheck/data/catalogue/{cpus,gpus}.json and put it in confirmedCpuId / confirmedGpuId. run-suite.ps1 -HardwareJson reads ONLY the confirmed fields; the suggested ones are never used automatically."
})

$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$outPath = Join-Path $OutputDir "hardware-$stamp.json"

$doc = [pscustomobject]([ordered]@{
  schema           = 'rigcheck-hardware/1'
  generatedAt      = (Get-Date -Format 'o')
  generatedBy      = 'harness/detect-hardware.ps1'
  machineName      = ''    # deliberately blank: not needed, and it identifies the operator
  cpu              = $cpu
  gpu              = $gpu
  memory           = $memory
  storage          = $storage
  os               = $os
  display          = $display
  motherboard      = $board
  catalogueMapping = $mapping
  bestEffort       = @($script:BestEffort.ToArray())
})

Write-JsonAtomic -Path $outPath -Object $doc -Depth 10

# --- Report -----------------------------------------------------------------

function Show-Field {
  param([string]$Label, $Value, [string]$Suffix = '')
  $v = "$Value"
  if (-not $v) { $v = '(not established)' }
  Write-Host ("  {0,-16} {1}{2}" -f $Label, $v, $Suffix)
}

Write-Host ''
Show-Field 'CPU' $cpu.name
Show-Field '' ("$($cpu.cores) cores / $($cpu.threads) threads" + $(if ($cpu.pCores) { " (inferred $($cpu.pCores)P + $($cpu.eCores)E)" } else { '' }))
Show-Field '' ("base $(if ($cpu.baseClockMHz) { "$($cpu.baseClockMHz) MHz" } else { 'unknown' }), boost unknown, firmware max $($cpu.maxClockSpeedMHz) MHz")
Show-Field 'GPU' $gpu.name
Show-Field '' ("$(if ($gpu.vramGB) { "$($gpu.vramGB) GB" } else { 'VRAM unknown' }) via $($gpu.vramSource)")
Show-Field '' ("driver $($gpu.driverVersion)  (INF $($gpu.infDriverVersion))")
Show-Field 'Memory' ("$($memory.totalGB) GB, $($memory.channels) channel(s), $($memory.speedMTs) MT/s $($memory.type)")
Show-Field '' ("$($memory.modulesPopulated) of $(if ($memory.slotsTotal) { $memory.slotsTotal } else { '?' }) slots populated")
Show-Field '' ("timings $(if ($memory.timings) { "CL$($memory.timings.cl) (operator-supplied)" } else { 'not obtainable - see warnings' })")
Show-Field 'Storage (sys)' "$($storage.system.class)  $($storage.system.model)"
if ($storage.games) { Show-Field 'Storage (games)' "$($storage.games.class)  $($storage.games.path)" }
Show-Field 'OS' "$($os.caption) $($os.displayVersion) build $($os.build)"
Show-Field 'Display' ("$($display.widthPx)x$($display.heightPx) @ $($display.refreshHz) Hz" + $(if ($display.catalogueResolution) { " = $($display.catalogueResolution)" } else { '' }))
Show-Field 'Motherboard' "$($board.manufacturer) $($board.product)  chipset $(if ($board.chipset) { $board.chipset } else { '?' })"

Write-Host ''
Write-Host '  CATALOGUE MAPPING' -ForegroundColor Cyan
Write-Host ("    cpu  suggested '{0}'" -f $cpuSlug)
if ($cpuMatch.Confirmed) {
  Write-Host ("         CONFIRMED '{0}'" -f $cpuMatch.Confirmed) -ForegroundColor Green
} else {
  Write-Host ("         NOT CONFIRMED - {0}" -f $cpuMatch.Note) -ForegroundColor Yellow
  foreach ($c in $cpuMatch.Candidates) { Write-Host "           candidate: $c" -ForegroundColor Yellow }
}
Write-Host ("    gpu  suggested '{0}'" -f $gpuSlug)
if ($gpuMatch.Confirmed) {
  Write-Host ("         CONFIRMED '{0}'" -f $gpuMatch.Confirmed) -ForegroundColor Green
} else {
  Write-Host ("         NOT CONFIRMED - {0}" -f $gpuMatch.Note) -ForegroundColor Yellow
  foreach ($c in $gpuMatch.Candidates) { Write-Host "           candidate: $c" -ForegroundColor Yellow }
}

Write-Host ''
Write-Host ('-' * 76) -ForegroundColor Yellow
Write-Host " BEST-EFFORT VALUES - $($script:BestEffort.Count) field(s) below are not certain" -ForegroundColor Yellow
Write-Host ('-' * 76) -ForegroundColor Yellow
foreach ($g in $script:BestEffort) {
  Write-Host ("  {0}" -f $g.field) -ForegroundColor Yellow
  $words = @($g.why -split '\s+')
  $line = ''
  foreach ($w in $words) {
    if (($line.Length + $w.Length + 1) -gt 70) { Write-Host "      $line" -ForegroundColor DarkYellow; $line = $w }
    elseif ($line -eq '') { $line = $w }
    else { $line = "$line $w" }
  }
  if ($line) { Write-Host "      $line" -ForegroundColor DarkYellow }
}

Write-Host ''
Write-Host "  Wrote $outPath" -ForegroundColor Green
Write-Host '  Read it, fix anything the warnings above flagged, fill in the confirmed ids,' -ForegroundColor Green
Write-Host '  then feed it to the runner:' -ForegroundColor Green
Write-Host ("    .\run-suite.ps1 -Suite core-12 -HardwareJson `"$outPath`"") -ForegroundColor Green
