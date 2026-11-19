#requires -Version 5.1
<#
.SYNOPSIS
Launch a bounded MangaFlow capture, optionally with an existing PresentMon console executable.
.EXAMPLE
.\scripts\windows\capture-performance.ps1 -Executable 'C:\MangaFlow\MangaFlow.exe' -Renderer Auto -Seconds 60 -Label shelf
.EXAMPLE
.\scripts\windows\capture-performance.ps1 -Executable 'C:\MangaFlow\MangaFlow.exe' -Renderer Direct3D -Seconds 60 -Label shelf -PresentMon 'C:\Tools\PresentMon.exe'
.NOTES
Does not change installed configuration, elevate, download tools, or close application sessions.
PresentMon starts after the new application's first diagnostic sample identifies its real PID;
this is an interaction capture, not a trace of process startup or synchronized GPU/CPU clocks.
The application remains open. Its capture stops automatically after 10..600 seconds.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$Executable,
    [ValidateSet('Auto', 'Direct3D', 'OpenGL', 'Software')][string]$Renderer = 'Auto',
    [int]$Seconds = 120,
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$Label,
    [string]$PresentMon
)

Set-StrictMode -Version 2
$ErrorActionPreference = 'Stop'
$exitCode = 0
$manifestPath = $null
$captureManifest = $null
$monitorProcess = $null
$launcherProcess = $null
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Resolve-Executable([string]$Path) {
    $item = Get-Item -LiteralPath $Path -ErrorAction Stop
    if ($item.PSIsContainer -or $item.Extension -ine '.exe') {
        throw "Expected an existing Windows .exe file: $Path"
    }
    return $item.FullName
}

function Read-FirstSample([string]$Path) {
    $stream = $null
    $reader = $null
    try {
        $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read,
            ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
        $reader = New-Object IO.StreamReader($stream, [Text.Encoding]::UTF8)
        $line = $reader.ReadLine()
        if ($line) { return ($line | ConvertFrom-Json -ErrorAction Stop) }
    } catch [IO.IOException] {
        # The writer may be opening or flushing the first sample.
    } catch [System.ArgumentException] {
        # A partial first JSON line can be visible before the first flush.
    } finally {
        if ($reader) { $reader.Dispose() }
        elseif ($stream) { $stream.Dispose() }
    }
    return $null
}

function Test-CaptureClosed([string]$Path) {
    $stream = $null
    try {
        $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::None)
        return $true
    } catch [IO.IOException] { return $false }
    finally { if ($stream) { $stream.Dispose() } }
}

function Assert-LaunchedProcess([int]$ApplicationId, [int]$LauncherId, [datetime]$LaunchedUtc) {
    $application = Get-Process -Id $ApplicationId -ErrorAction Stop
    if ($application.StartTime.ToUniversalTime() -lt $LaunchedUtc.AddSeconds(-2)) {
        throw "Diagnostic PID $ApplicationId predates this launch. Refusing to capture an existing session."
    }
    $currentId = $ApplicationId
    $visited = @{}
    for ($depth = 0; $depth -lt 32; $depth++) {
        if ($currentId -eq $LauncherId) { return $application }
        if ($visited.ContainsKey($currentId)) { break }
        $visited[$currentId] = $true
        $processInfo = Get-CimInstance -ClassName Win32_Process -Filter "ProcessId = $currentId"
        if (-not $processInfo) { break }
        if ($processInfo.CreationDate -and $processInfo.CreationDate.ToUniversalTime() -lt $LaunchedUtc.AddSeconds(-2)) { break }
        $currentId = [int]$processInfo.ParentProcessId
    }
    throw "Could not verify diagnostic PID $ApplicationId as this launcher's process or descendant. No process-name guessing is used."
}

function Save-Manifest {
    if ($manifestPath -and $captureManifest) {
        [IO.File]::WriteAllText($manifestPath, ($captureManifest | ConvertTo-Json -Depth 8), $utf8)
    }
}

function Convert-SampleTimestamp($Value) {
    if ($Value -is [DateTime]) { return $Value.ToUniversalTime() }
    if ($Value -is [DateTimeOffset]) { return $Value.UtcDateTime }
    # Java Instant may write nine fractional digits; .NET Framework DateTime has seven.
    $text = [regex]::Replace([string]$Value, '(\.\d{7})\d+(?=Z$)', '$1')
    return [DateTimeOffset]::Parse($text, [Globalization.CultureInfo]::InvariantCulture).UtcDateTime
}

try {
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { throw 'This launcher requires Windows.' }
    if (-not $env:LOCALAPPDATA) { throw 'LOCALAPPDATA is unavailable; the application diagnostics directory cannot be located.' }
    $applicationPath = Resolve-Executable $Executable
    $monitorPath = if ($PresentMon) { Resolve-Executable $PresentMon } else { $null }
    $captureSeconds = [Math]::Min(600, [Math]::Max(10, $Seconds))
    if ($captureSeconds -ne $Seconds) { Write-Warning "Seconds was clamped to $captureSeconds (supported range: 10..600)." }
    $safeLabel = [regex]::Replace($Label, '[^A-Za-z0-9_-]', '-').Trim('-')
    if (-not $safeLabel) { $safeLabel = 'capture' }
    if ($safeLabel.Length -gt 32) { $safeLabel = $safeLabel.Substring(0, 32) }
    $captureId = $safeLabel + '-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
    $diagnostics = Join-Path $env:LOCALAPPDATA 'MangaFlow\diagnostics'
    $null = New-Item -ItemType Directory -Path $diagnostics -Force
    $manifestPath = Join-Path $diagnostics ($captureId + '-launch.json')
    $rendererOverride = switch ($Renderer) {
        'Direct3D' { 'DIRECT3D' }
        'OpenGL' { 'OPENGL' }
        'Software' { 'SOFTWARE_FAST' }
        default { $null }
    }
    $captureManifest = [ordered]@{
        schema = 1
        status = 'launching'
        capture_id = $captureId
        label = $Label
        executable = $applicationPath
        requested_renderer = $Renderer
        skiko_environment_override = $rendererOverride
        requested_seconds = $captureSeconds
        started_utc = [DateTime]::UtcNow.ToString('o')
        finished_utc = $null
        launcher_process_id = $null
        launcher_exit_code = $null
        application_process_id = $null
        application_process_path = $null
        actual_renderers = @()
        first_sample_utc = $null
        last_sample_utc = $null
        observed_sample_span_seconds = $null
        elapsed_to_log_close_after_first_sample_seconds = $null
        jsonl = $null
        cpu_frames = $null
        jfr = $null
        jfr_available = $false
        presentmon_executable = $monitorPath
        presentmon_version = $null
        presentmon_session = $null
        presentmon_process_id = $null
        presentmon_started_utc = $null
        presentmon_exit_code = $null
        presentmon_csv = $null
        presentmon_stdout = $null
        presentmon_stderr = $null
        presentmon_cleanup_exit_code = $null
        errors = @()
        notes = @('CPU callback samples exclude native GPU submission, Present and scanout.',
            'PresentMon starts after PID discovery; its interval does not exactly match the CPU capture.',
            'JFR availability below means a nonempty closed file; use JDK tools to validate and inspect the recording.',
            'Auto clears only this launch environment override; existing JVM properties and Skiko fallback remain effective.')
    }
    Save-Manifest

    # Start the process with only temporary process-scope changes. In particular, Auto
    # must not inherit SKIKO_RENDER_API, and must not erase the caller's setting.
    $captureEnvironment = @{
        MANGAFLOW_PROFILE_START = '1'
        MANGAFLOW_PROFILE_ID = $captureId
        MANGAFLOW_PROFILE_SECONDS = [string]$captureSeconds
        SKIKO_RENDER_API = $rendererOverride
    }
    $inheritedEnvironment = @{}
    foreach ($name in $captureEnvironment.Keys) {
        $inheritedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    try {
        foreach ($name in $captureEnvironment.Keys) {
            [Environment]::SetEnvironmentVariable($name, $captureEnvironment[$name], 'Process')
        }
        $startInfo = New-Object Diagnostics.ProcessStartInfo
        $startInfo.FileName = $applicationPath
        $startInfo.WorkingDirectory = [IO.Path]::GetDirectoryName($applicationPath)
        $startInfo.UseShellExecute = $false
        $launchedUtc = [DateTime]::UtcNow
        $launcherProcess = [Diagnostics.Process]::Start($startInfo)
        if (-not $launcherProcess) { throw 'The application process could not be started.' }
        $captureManifest.launcher_process_id = $launcherProcess.Id
    } finally {
        foreach ($name in $inheritedEnvironment.Keys) {
            [Environment]::SetEnvironmentVariable($name, $inheritedEnvironment[$name], 'Process')
        }
    }
    Write-Host "Waiting for the new application's diagnostic PID (capture $captureId)..."
    $firstSample = $null
    $metricsFile = $null
    $startupWatch = [Diagnostics.Stopwatch]::StartNew()
    while ($startupWatch.Elapsed.TotalSeconds -lt 45) {
        $candidates = @(Get-ChildItem -LiteralPath $diagnostics -Filter ($captureId + '-*.jsonl') -File)
        if ($candidates.Count -gt 1) { throw 'More than one application capture has this unique ID; refusing an ambiguous PID.' }
        if ($candidates.Count -eq 1) {
            $metricsFile = $candidates[0].FullName
            $firstSample = Read-FirstSample $metricsFile
            if ($firstSample) { break }
        }
        Start-Sleep -Milliseconds 250
    }
    if (-not $firstSample) {
        throw 'No diagnostic sample appeared within 45 seconds. Use a build containing DesktopPerformance; if another instance owns the session, close it yourself and rerun. The launcher has not closed any app.'
    }
    $captureWatch = [Diagnostics.Stopwatch]::StartNew()
    if ($firstSample.schema -ne 1 -or -not $firstSample.process_id) { throw 'Unsupported diagnostic schema or missing process_id.' }
    $applicationProcess = Assert-LaunchedProcess ([int]$firstSample.process_id) $launcherProcess.Id $launchedUtc
    $captureManifest.application_process_id = $applicationProcess.Id
    $captureManifest.application_process_path = $applicationProcess.Path
    $captureStem = $metricsFile.Substring(0, $metricsFile.Length - '.jsonl'.Length)
    $framesFile = $captureStem + '-cpu-frames.csv'
    $jfrFile = $captureStem + '.jfr'
    $captureManifest.jsonl = $metricsFile
    $captureManifest.cpu_frames = $framesFile
    $captureManifest.jfr = $jfrFile
    $captureManifest.status = 'capturing'

    if ($monitorPath) {
        $sessionName = 'MangaFlow-' + [Guid]::NewGuid().ToString('N')
        $monitorCsv = $captureStem + '-presentmon.csv'
        $monitorOut = $captureStem + '-presentmon.stdout.log'
        $monitorErr = $captureStem + '-presentmon.stderr.log'
        $monitorArguments = @('--process_id', [string]$applicationProcess.Id,
            '--output_file', ('"{0}"' -f $monitorCsv), '--timed', [string]$captureSeconds,
            '--terminate_after_timed', '--terminate_on_proc_exit', '--track_gpu_video',
            '--no_console_stats', '--session_name', $sessionName)
        $captureManifest.presentmon_session = $sessionName
        $captureManifest.presentmon_version = (Get-Item -LiteralPath $monitorPath).VersionInfo.FileVersion
        $captureManifest.presentmon_started_utc = [DateTime]::UtcNow.ToString('o')
        $captureManifest.presentmon_csv = $monitorCsv
        $captureManifest.presentmon_stdout = $monitorOut
        $captureManifest.presentmon_stderr = $monitorErr
        $monitorProcess = Start-Process -FilePath $monitorPath -ArgumentList $monitorArguments -PassThru -NoNewWindow `
            -RedirectStandardOutput $monitorOut -RedirectStandardError $monitorErr
        $captureManifest.presentmon_process_id = $monitorProcess.Id
    } else {
        Write-Warning 'PresentMon was not supplied: GPU/display presentation metrics remain unavailable. CPU, resource and optional JFR capture continue.'
    }
    Save-Manifest
    Write-Host "Capturing PID $($applicationProcess.Id) for $captureSeconds seconds. Perform the labelled scenario now."
    Write-Host "Requested backend: $Renderer. The actual backend is recorded in JSONL (fallback may differ)."

    $nextProgress = 15.0
    $applicationCaptureClosed = $false
    $applicationClosedAfterSeconds = $null
    while ($captureWatch.Elapsed.TotalSeconds -lt ($captureSeconds + 30)) {
        $applicationCaptureClosed = (Test-CaptureClosed $metricsFile) -and (Test-CaptureClosed $framesFile)
        if ($applicationCaptureClosed -and $null -eq $applicationClosedAfterSeconds) {
            $applicationClosedAfterSeconds = $captureWatch.Elapsed.TotalSeconds
        }
        if ($applicationCaptureClosed -and (-not $monitorProcess -or $monitorProcess.HasExited)) { break }
        if ($captureWatch.Elapsed.TotalSeconds -ge $nextProgress) {
            Write-Host 'Capture is still running; keep the same window, display and scenario settings.'
            $nextProgress = $captureWatch.Elapsed.TotalSeconds + 15
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $applicationCaptureClosed) {
        $captureManifest.errors += 'Application logs did not close by the capture deadline; files may be partial.'
    }
    if ($monitorProcess) {
        if (-not $monitorProcess.HasExited) {
            $captureManifest.errors += "PresentMon did not finish by the deadline (PID $($monitorProcess.Id), own session $($captureManifest.presentmon_session)). No other session was stopped."
        } else {
            $monitorProcess.WaitForExit()
            $captureManifest.presentmon_exit_code = $monitorProcess.ExitCode
            if ($monitorProcess.ExitCode -ne 0) {
                $captureManifest.errors += "PresentMon failed with exit code $($monitorProcess.ExitCode). See $($captureManifest.presentmon_stderr). No elevation or session replacement was attempted."
            }
        }
        if (-not (Test-Path -LiteralPath $monitorCsv) -or @(Get-Content -LiteralPath $monitorCsv -TotalCount 2).Count -lt 2) {
            $captureManifest.errors += 'PresentMon produced no frame rows; presentation metrics are unavailable.'
        }
    }
    if (-not (Test-Path -LiteralPath $framesFile) -or @(Get-Content -LiteralPath $framesFile -TotalCount 2).Count -lt 2) {
        $captureManifest.errors += 'The CPU frame CSV has no frame rows; render callback metrics are unavailable.'
    }
    $samples = @(Get-Content -LiteralPath $metricsFile -Encoding UTF8 | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
    if (-not $samples.Count) { throw 'The JSONL contains no complete resource samples.' }
    $firstSampleUtc = Convert-SampleTimestamp $samples[0].timestamp
    $lastSampleUtc = Convert-SampleTimestamp $samples[-1].timestamp
    $observedSeconds = ($lastSampleUtc - $firstSampleUtc).TotalSeconds
    $captureManifest.first_sample_utc = $firstSampleUtc.ToString('o')
    $captureManifest.last_sample_utc = $lastSampleUtc.ToString('o')
    $captureManifest.observed_sample_span_seconds = $observedSeconds
    $captureManifest.elapsed_to_log_close_after_first_sample_seconds = $applicationClosedAfterSeconds
    if ($null -ne $applicationClosedAfterSeconds -and $applicationClosedAfterSeconds -lt ($captureSeconds - 2)) {
        $captureManifest.errors += "The logs closed only $([Math]::Round($applicationClosedAfterSeconds, 3)) seconds after discovery for a $captureSeconds second request (2-second sampling tolerance). Capture ended early or the collector joined late."
    }
    if ($null -ne $applicationClosedAfterSeconds -and [Math]::Abs($observedSeconds - $applicationClosedAfterSeconds) -gt 3) {
        $captureManifest.notes += 'The JSON wall-clock span differs from the monotonic observation; inspect delayed startup/sampling or clock adjustments. UTC sample span is not the capture timeout clock.'
    }
    $captureManifest.actual_renderers = @($samples | ForEach-Object { $_.renderer } | Sort-Object -Unique)
    if (-not @($samples | Where-Object { $_.render_hook }).Count) {
        $captureManifest.errors += 'The CPU render hook was unavailable throughout this capture.'
    }
    if (@($samples | Where-Object { $_.capture_queue_overflow -gt 0 }).Count) {
        $captureManifest.errors += 'CPU frame capture queue overflow was reported; the raw frame distribution is incomplete.'
    }
    # finishCapture closes CSV writers before dumping JFR; allow that final write to settle.
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        if ((Test-Path -LiteralPath $jfrFile) -and (Get-Item -LiteralPath $jfrFile).Length -gt 0 -and (Test-CaptureClosed $jfrFile)) { break }
        Start-Sleep -Milliseconds 500
    }
    $captureManifest.jfr_available = (Test-Path -LiteralPath $jfrFile) -and (Get-Item -LiteralPath $jfrFile).Length -gt 0 -and (Test-CaptureClosed $jfrFile)
    if (-not $captureManifest.jfr_available) { Write-Warning 'JFR is unavailable or did not finish writing. CPU/JSONL results are retained; no JFR success is claimed.' }
    $captureManifest.status = if ($captureManifest.errors.Count) { 'incomplete' } else { 'complete' }
    if ($captureManifest.errors.Count) {
        $exitCode = 1
        foreach ($captureError in $captureManifest.errors) { Write-Warning $captureError }
    }
    Write-Host "Capture status: $($captureManifest.status). Actual backend(s): $($captureManifest.actual_renderers -join ', ')"
    Write-Host "CPU frames: $framesFile"
    Write-Host "Resources:  $metricsFile"
    if ($monitorPath) { Write-Host "PresentMon: $monitorCsv" }
    Write-Host "Manifest:   $manifestPath"
    if ($applicationProcess.HasExited) { Write-Host 'The application exited; consult capture status for completeness.' }
    else { Write-Host 'The application remains open.' }
    Write-Host 'Keep original files for analysis; compare the same scenario and display settings.'
} catch {
    $exitCode = 1
    if ($captureManifest) {
        $captureManifest.status = 'failed'
        $captureManifest.errors += $_.Exception.Message
    }
    Write-Warning $_.Exception.Message
} finally {
    if ($launcherProcess -and $launcherProcess.HasExited -and $captureManifest) {
        $launcherProcess.WaitForExit()
        $captureManifest.launcher_exit_code = $launcherProcess.ExitCode
    }
    if ($monitorProcess -and -not $monitorProcess.HasExited) {
        # Only the unique session started by this invocation may be stopped. The
        # official console stop command lets the original writer flush its CSV.
        # Never use --stop_existing_session, kill a named process, or elevate.
        try {
            $stopArguments = @('--session_name', $captureManifest.presentmon_session, '--terminate_existing_session')
            $stopProcess = Start-Process -FilePath $monitorPath -ArgumentList $stopArguments -PassThru -NoNewWindow `
                -RedirectStandardOutput ($captureStem + '-presentmon-cleanup.stdout.log') `
                -RedirectStandardError ($captureStem + '-presentmon-cleanup.stderr.log')
            if ($stopProcess.WaitForExit(5000)) {
                $captureManifest.presentmon_cleanup_exit_code = $stopProcess.ExitCode
            }
            if (-not $monitorProcess.WaitForExit(5000)) {
                Write-Warning "Owned PresentMon PID $($monitorProcess.Id) did not stop; its unique session is $($captureManifest.presentmon_session). No other session or application was terminated."
            } else {
                $captureManifest.presentmon_exit_code = $monitorProcess.ExitCode
            }
        } catch {
            Write-Warning "Could not stop the owned PresentMon session: $($_.Exception.Message). No other session was targeted."
        }
    }
    if ($captureManifest) {
        if ($captureManifest.status -in @('launching', 'capturing')) {
            $captureManifest.status = 'interrupted'
            $captureManifest.errors += 'The launcher did not reach capture completion; files may still be writing.'
            $exitCode = 1
        }
        $captureManifest.finished_utc = [DateTime]::UtcNow.ToString('o')
        try { Save-Manifest } catch { Write-Warning "Could not save capture manifest: $($_.Exception.Message)"; $exitCode = 1 }
    }
}
exit $exitCode
