param(
    [Parameter(Mandatory = $true)][string]$PlanPath,
    [Parameter(Mandatory = $true)][int]$ParentPid,
    [int]$LauncherPid = 0,
    [switch]$Launch,
    [switch]$Elevated
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$planFile = [IO.Path]::GetFullPath($PlanPath)
$work = Split-Path -Parent $planFile

if ($Launch) {
    # Older jpackage launchers terminate their entire job when the JVM exits (JDK-8325203).
    # Local WMI creates the worker outside the calling launcher's job.
    $powershell = Join-Path $PSHOME 'powershell.exe'
    $arguments = ('"{0}" -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "{1}" -PlanPath "{2}" -ParentPid {3} -LauncherPid {4}' -f
        $powershell, $PSCommandPath, $planFile, $ParentPid, $LauncherPid)
    $startup = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{
        ShowWindow = [uint16]0
        WinstationDesktop = 'WinSta0\Default'
    }
    $worker = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
        CommandLine = $arguments
        ProcessStartupInformation = $startup
    }
    if ($worker.ReturnValue -ne 0) { throw "Could not start MangaFlow's update worker (WMI $($worker.ReturnValue))." }
    exit 0
}

$mutex = $null
$ownsMutex = $false
$running = $null
$nativeLauncher = $null
$accepted = $Elevated.IsPresent

function Write-Result([bool]$success, [string]$message) {
    $parent = Split-Path -Parent $resultPath
    [IO.Directory]::CreateDirectory($parent) | Out-Null
    $json = @{ Success = $success; Message = $message } | ConvertTo-Json -Compress
    [IO.File]::WriteAllText($resultPath, $json)
}
function Show-UpdateMessage([string]$message, [string]$icon) {
    try {
        Add-Type -AssemblyName PresentationFramework
        [Windows.MessageBox]::Show($message, 'MangaFlow', 'OK', $icon) | Out-Null
    } catch {}
}
function Install-File([string]$relative) {
    $segments = $relative.Split('/')
    if ((-not ($relative -ceq 'MangaFlow.exe' -or
            $relative.StartsWith('app/', [StringComparison]::Ordinal) -or
            $relative.StartsWith('runtime/', [StringComparison]::Ordinal))) -or
        $relative.Length -gt 500 -or $relative.Contains('\') -or
        $segments.Where({ $_ -eq '..' -or $_ -eq '.' -or $_ -eq '' -or
            $_.IndexOfAny([char[]]':*?"<>|') -ge 0 }).Count -gt 0) {
        throw 'The update contains an invalid package path.'
    }
    $base = $root.TrimEnd('\') + '\'
    $resolved = [IO.Path]::GetFullPath((Join-Path $root $relative))
    if (-not $resolved.StartsWith($base, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The update path leaves the installed application.'
    }
    $ancestor = $resolved
    while ($ancestor.StartsWith($base, [StringComparison]::OrdinalIgnoreCase)) {
        if (Test-Path -LiteralPath $ancestor) {
            if ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw 'The application update path contains a redirected file or directory.'
            }
        }
        $next = Split-Path -Parent $ancestor
        if ($next -eq $ancestor -or [string]::IsNullOrEmpty($next)) { break }
        $ancestor = $next
    }
    return $resolved
}
function Verify-File([string]$path, $entry) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Update file is missing: $($entry.Path)" }
    if ((Get-Item -LiteralPath $path).Length -ne [long]$entry.Size -or
        (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne [string]$entry.Sha256) {
        throw "Update file verification failed: $($entry.Path)"
    }
}
function Start-OldOrNew {
    if ($null -ne $running -and -not $running.HasExited) { return }
    if (Test-Path -LiteralPath $launcher -PathType Leaf) {
        Start-Process -FilePath $launcher -ArgumentList '--after-update' -WorkingDirectory $root | Out-Null
    }
}
try {
    $plan = Get-Content -LiteralPath $planFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $root = [IO.Path]::GetFullPath([string]$plan.InstallRoot)
    $resultPath = [IO.Path]::GetFullPath([string]$plan.ResultPath)
    $launcher = Join-Path $root 'MangaFlow.exe'
    $env:LOCALAPPDATA = [IO.Path]::GetFullPath([string]$plan.LocalAppData)
    $mutex = New-Object Threading.Mutex($false, 'Local\MangaFlowDesktopUpdate')
    $ownsMutex = $mutex.WaitOne(0)
    if (-not $ownsMutex) { throw 'Another MangaFlow update is already running.' }

    if (-not $Elevated) {
        $running = Get-Process -Id $ParentPid
        if ($running.SessionId -ne (Get-Process -Id $PID).SessionId) {
            throw 'The update worker is outside the desktop session.'
        }
        $null = $running.Handle
        if ($LauncherPid -gt 0) {
            $nativeLauncher = Get-Process -Id $LauncherPid
            $null = $nativeLauncher.Handle
        }

        [IO.File]::WriteAllText((Join-Path $work 'ready'), '')
        $proceed = Join-Path $work 'apply'
        $approvalTimer = [Diagnostics.Stopwatch]::StartNew()
        while (-not (Test-Path -LiteralPath $proceed -PathType Leaf)) {
            if ($running.HasExited -or $approvalTimer.ElapsedMilliseconds -ge 20000) { exit 1 }
            Start-Sleep -Milliseconds 100
        }
        $accepted = $true
        # Replacing files must wait for a successful save-and-close and for jpackage to release the EXE.
        if (-not $running.WaitForExit(120000) -or $running.ExitCode -ne 0) {
            throw '漫流未能正常保存并退出。请处理保存错误后重试。'
        }
        if ($null -ne $nativeLauncher -and -not $nativeLauncher.WaitForExit(10000)) {
            throw '漫流启动器仍在运行。请关闭后重试。'
        }
    }

    if ($plan.Mode -eq 'installer') {
        $installer = [IO.Path]::GetFullPath([string]$plan.Installer)
        Verify-File $installer ([pscustomobject]@{ Path = 'installer'; Size = $plan.InstallerSize; Sha256 = $plan.InstallerSha256 })
        $log = Join-Path $work 'windows-installer.log'
        $config = Join-Path $root 'app\MangaFlow.cfg'
        $previousHash = if (Test-Path -LiteralPath $config -PathType Leaf) { (Get-FileHash -LiteralPath $config).Hash }
        if ([IO.Path]::GetExtension($installer).Equals('.msi', [StringComparison]::OrdinalIgnoreCase)) {
            $arguments = '/i "' + $installer + '" /passive /norestart /L*v "' + $log + '"'
            $process = Start-Process -FilePath "$env:SystemRoot\System32\msiexec.exe" -ArgumentList $arguments -PassThru -Wait
        } elseif ([IO.Path]::GetExtension($installer).Equals('.exe', [StringComparison]::OrdinalIgnoreCase)) {
            $process = Start-Process -FilePath $installer -PassThru -Wait
        } else { throw 'Unsupported installer type.' }
        if ($process.ExitCode -notin @(0, 1641, 3010)) {
            throw "Windows installer returned $($process.ExitCode). Log: $log"
        }
        if ($process.ExitCode -in @(1641, 3010)) {
            $message = '安装已完成。请重启 Windows，再从开始菜单打开漫流。'
        } elseif ((Test-Path -LiteralPath $config -PathType Leaf) -and (Get-FileHash -LiteralPath $config).Hash -ne $previousHash) {
            Write-Result $true '安装已完成。'
            Start-OldOrNew
            exit 0
        } else {
            # An MSI launched from a portable image may have upgraded a different directory.
            $message = '安装已完成。请从开始菜单打开新版漫流。'
        }
        Write-Result $true $message
        Show-UpdateMessage $message 'Information'
        exit 0
    }
    if ($plan.Mode -ne 'incremental') { throw 'Unknown update operation.' }
    if (-not (Test-Path -LiteralPath (Join-Path $root 'app\MangaFlow.cfg') -PathType Leaf)) {
        throw 'The installed MangaFlow application could not be found.'
    }

    # Elevate only the installed-code replacement. UAC cancellation returns to the old application.
    if (-not $Elevated) {
        $probe = Join-Path $root ('app\.mangaflow-permission-' + [Guid]::NewGuid().ToString('N'))
        $writable = $false
        try {
            [IO.File]::WriteAllText($probe, '')
            Remove-Item -LiteralPath $probe -Force
            $writable = $true
        } catch { $writable = $false }
        if (-not $writable) {
            if ($ownsMutex) { $mutex.ReleaseMutex(); $ownsMutex = $false }
            $argsLine = '-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "' +
                $PSCommandPath + '" -PlanPath "' + $planFile + '" -ParentPid ' + $ParentPid + ' -Elevated'
            $elevatedProcess = Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -ArgumentList $argsLine -Verb RunAs -WindowStyle Hidden -PassThru -Wait
            if ($elevatedProcess.ExitCode -ne 0) { throw 'The elevated update failed; the previous version was retained.' }
            if (-not $Elevated) { Start-OldOrNew }
            exit 0
        }
    }

    $entries = @{}
    foreach ($entry in $plan.Files) {
        $null = Install-File ([string]$entry.Path)
        if ($entries.ContainsKey([string]$entry.Path)) { throw 'Duplicate application path.' }
        $entries[[string]$entry.Path] = $entry
    }
    if (-not $entries.ContainsKey('app/MangaFlow.cfg')) { throw 'Missing startup configuration.' }
    $changed = @($plan.ChangedPaths | Sort-Object {
        if ([string]$_ -eq 'MangaFlow.exe') { 2 }
        elseif ([string]$_ -eq 'app/MangaFlow.cfg') { 1 }
        else { 0 }
    })
    $payload = [IO.Path]::GetFullPath([string]$plan.PayloadRoot)
    foreach ($relative in $changed) {
        if (-not $entries.ContainsKey([string]$relative)) { throw 'An update file is not in the verified manifest.' }
        $source = [IO.Path]::GetFullPath((Join-Path $payload ([string]$relative)))
        if (-not $source.StartsWith($payload.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid staged update path.' }
        Verify-File $source $entries[[string]$relative]
    }

    # The manifest describes the full new runtime. Remove obsolete packaged
    # runtime files and app JARs transactionally, never user data outside them.
    $obsolete = New-Object 'Collections.Generic.List[string]'
    foreach ($jar in Get-ChildItem -LiteralPath (Join-Path $root 'app') -File -Filter '*.jar') {
        $relative = 'app/' + $jar.Name
        if (-not $entries.ContainsKey($relative)) { $obsolete.Add($relative) }
    }
    if ([int]$plan.Protocol -eq 2) {
        if (-not $entries.ContainsKey('MangaFlow.exe') -or
            @($entries.Keys | Where-Object { $_.StartsWith('runtime/') }).Count -eq 0) {
            throw 'The target update is missing its launcher or runtime inventory.'
        }
        $queue = New-Object 'Collections.Generic.Queue[string]'
        $queue.Enqueue((Join-Path $root 'runtime'))
        $rootPrefix = $root.TrimEnd('\') + '\'
        while ($queue.Count -gt 0) {
            $directory = $queue.Dequeue()
            foreach ($entry in Get-ChildItem -LiteralPath $directory -Force) {
                if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                    throw 'A Java runtime directory contains a redirected path.'
                }
                if ($entry.PSIsContainer) { $queue.Enqueue($entry.FullName); continue }
                $relative = $entry.FullName.Substring($rootPrefix.Length).Replace('\', '/')
                $null = Install-File $relative
                if (-not $entries.ContainsKey($relative)) { $obsolete.Add($relative) }
            }
        }
    }
    if ($obsolete.Count -gt 4000) { throw 'Too many obsolete files in the installed runtime.' }
    foreach ($relative in $obsolete) {
        if ($entries.ContainsKey($relative)) { throw 'Obsolete-file inventory conflicts with target files.' }
        $null = Install-File $relative
    }

    $backup = Join-Path $work ('backup-' + [Guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($backup) | Out-Null
    $applied = New-Object 'Collections.Generic.List[object]'
    try {
        foreach ($relative in $changed) {
            $destination = Install-File ([string]$relative)
            $old = Join-Path $backup ([string]$relative)
            $existed = Test-Path -LiteralPath $destination -PathType Leaf
            if ($existed) {
                [IO.Directory]::CreateDirectory((Split-Path -Parent $old)) | Out-Null
                Copy-Item -LiteralPath $destination -Destination $old -Force
            }
            $applied.Add([pscustomobject]@{ Path = $destination; Backup = $old; Existed = $existed })
            [IO.Directory]::CreateDirectory((Split-Path -Parent $destination)) | Out-Null
            $temporary = $destination + '.mangaflow-incoming'
            Copy-Item -LiteralPath (Join-Path $payload ([string]$relative)) -Destination $temporary -Force
            Move-Item -LiteralPath $temporary -Destination $destination -Force
        }
        foreach ($entry in $plan.Files) { Verify-File (Install-File ([string]$entry.Path)) $entry }
        foreach ($relative in $obsolete) {
            $destination = Install-File ([string]$relative)
            if (-not (Test-Path -LiteralPath $destination -PathType Leaf)) { continue }
            $old = Join-Path $backup ([string]$relative)
            [IO.Directory]::CreateDirectory((Split-Path -Parent $old)) | Out-Null
            Copy-Item -LiteralPath $destination -Destination $old -Force
            $applied.Add([pscustomobject]@{ Path = $destination; Backup = $old; Existed = $true })
            Remove-Item -LiteralPath $destination -Force
        }
        foreach ($entry in $plan.Files) { Verify-File (Install-File ([string]$entry.Path)) $entry }
    } catch {
        $originalFailure = $_
        for ($index = $applied.Count - 1; $index -ge 0; $index--) {
            $item = $applied[$index]
            if ($item.Existed) { Copy-Item -LiteralPath $item.Backup -Destination $item.Path -Force }
            elseif (Test-Path -LiteralPath $item.Path -PathType Leaf) { Remove-Item -LiteralPath $item.Path -Force }
        }
        throw $originalFailure
    }

    Write-Result $true ('已更新到 ' + [string]$plan.TargetVersion + '。')
    if (-not $Elevated) { Start-OldOrNew }
    exit 0
} catch {
    $message = '更新未完成：' + $_.Exception.Message
    try { [IO.File]::WriteAllText((Join-Path $work 'handoff-error.txt'), $message) } catch {}
    if ($accepted) {
        try { Write-Result $false $message } catch {}
        Show-UpdateMessage $message 'Warning'
        if (-not $Elevated) { Start-OldOrNew }
    }
    exit 1
} finally {
    if ($ownsMutex -and $null -ne $mutex) { $mutex.ReleaseMutex() }
    if ($null -ne $mutex) { $mutex.Dispose() }
    if ($null -ne $running) { $running.Dispose() }
    if ($null -ne $nativeLauncher) { $nativeLauncher.Dispose() }
}
