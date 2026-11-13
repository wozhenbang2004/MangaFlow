param(
    [Parameter(Mandatory = $true)][string]$PlanPath,
    [Parameter(Mandatory = $true)][int]$ParentPid,
    [switch]$Elevated
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$planFile = [IO.Path]::GetFullPath($PlanPath)
$work = Split-Path -Parent $planFile
$plan = Get-Content -LiteralPath $planFile -Raw -Encoding UTF8 | ConvertFrom-Json
$root = [IO.Path]::GetFullPath([string]$plan.InstallRoot)
$resultPath = [IO.Path]::GetFullPath([string]$plan.ResultPath)
$launcher = Join-Path $root 'MangaFlow.exe'
$mutex = $null
$ownsMutex = $false

function Write-Result([bool]$success, [string]$message) {
    $parent = Split-Path -Parent $resultPath
    [IO.Directory]::CreateDirectory($parent) | Out-Null
    @{ Success = $success; Message = $message } | ConvertTo-Json -Compress |
        Set-Content -LiteralPath $resultPath -Encoding UTF8
}
function App-File([string]$relative) {
    if (-not $relative.StartsWith('app/', [StringComparison]::Ordinal) -or
        $relative.Contains('\') -or $relative.Split('/').Where({ $_ -eq '..' -or $_ -eq '.' -or $_ -eq '' }).Count -gt 0) {
        throw 'The update contains an invalid application path.'
    }
    $appRoot = [IO.Path]::GetFullPath((Join-Path $root 'app')).TrimEnd('\') + '\'
    $resolved = [IO.Path]::GetFullPath((Join-Path $root $relative))
    if (-not $resolved.StartsWith($appRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The update path leaves the application directory.'
    }
    $ancestor = Split-Path -Parent $resolved
    while ($ancestor.Length -ge $appRoot.TrimEnd('\').Length) {
        if (Test-Path -LiteralPath $ancestor) {
            if ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw 'The application update directory contains a redirected path.'
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
    if (Get-Process -Id $ParentPid -ErrorAction SilentlyContinue) { return }
    if (Test-Path -LiteralPath $launcher -PathType Leaf) {
        Start-Process -FilePath $launcher -WorkingDirectory $root | Out-Null
    }
}
try {
    # The ordinary application close path flushes reading/preferences before its JVM terminates.
    $running = Get-Process -Id $ParentPid -ErrorAction SilentlyContinue
    if ($null -ne $running -and -not $running.WaitForExit(120000)) {
        throw 'The application did not close; no installed files were changed.'
    }
    $mutex = New-Object Threading.Mutex($false, 'Local\MangaFlowDesktopUpdate')
    $ownsMutex = $mutex.WaitOne(0)
    if (-not $ownsMutex) { throw 'Another MangaFlow update is already running.' }

    if ($plan.Mode -eq 'installer') {
        $installer = [IO.Path]::GetFullPath([string]$plan.Installer)
        Verify-File $installer ([pscustomobject]@{ Path = 'installer'; Size = $plan.InstallerSize; Sha256 = $plan.InstallerSha256 })
        $log = Join-Path $work 'windows-installer.log'
        if ([IO.Path]::GetExtension($installer).Equals('.msi', [StringComparison]::OrdinalIgnoreCase)) {
            $arguments = '/i "' + $installer + '" /passive /norestart /L*v "' + $log + '"'
            $process = Start-Process -FilePath "$env:SystemRoot\System32\msiexec.exe" -ArgumentList $arguments -PassThru -Wait
        } elseif ([IO.Path]::GetExtension($installer).Equals('.exe', [StringComparison]::OrdinalIgnoreCase)) {
            $process = Start-Process -FilePath $installer -PassThru -Wait
        } else { throw 'Unsupported installer type.' }
        if ($process.ExitCode -notin @(0, 1641, 3010)) {
            throw "Windows installer returned $($process.ExitCode). Log: $log"
        }
        Write-Result $true '安装已完成。'
        if (-not $Elevated) { Start-OldOrNew }
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
        $null = App-File ([string]$entry.Path)
        if ($entries.ContainsKey([string]$entry.Path)) { throw 'Duplicate application path.' }
        $entries[[string]$entry.Path] = $entry
    }
    $changed = @($plan.ChangedPaths | Sort-Object { if ([string]$_ -eq 'app/MangaFlow.cfg') { 1 } else { 0 } })
    $payload = [IO.Path]::GetFullPath([string]$plan.PayloadRoot)
    foreach ($relative in $changed) {
        if (-not $entries.ContainsKey([string]$relative)) { throw 'An update file is not in the verified manifest.' }
        $source = [IO.Path]::GetFullPath((Join-Path $payload ([string]$relative)))
        if (-not $source.StartsWith($payload.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid staged update path.' }
        Verify-File $source $entries[[string]$relative]
    }

    $backup = Join-Path $work ('backup-' + [Guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($backup) | Out-Null
    $applied = New-Object 'Collections.Generic.List[object]'
    try {
        foreach ($relative in $changed) {
            $destination = App-File ([string]$relative)
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
        foreach ($entry in $plan.Files) { Verify-File (App-File ([string]$entry.Path)) $entry }
    } catch {
        $originalFailure = $_
        for ($index = $applied.Count - 1; $index -ge 0; $index--) {
            $item = $applied[$index]
            if ($item.Existed) { Copy-Item -LiteralPath $item.Backup -Destination $item.Path -Force }
            elseif (Test-Path -LiteralPath $item.Path -PathType Leaf) { Remove-Item -LiteralPath $item.Path -Force }
        }
        throw $originalFailure
    }

    # The app directory is packaged code; obsolete JARs are retained in this update's backup.
    foreach ($jar in Get-ChildItem -LiteralPath (Join-Path $root 'app') -File -Filter '*.jar') {
        $relative = 'app/' + $jar.Name
        if (-not $entries.ContainsKey($relative)) {
            $old = Join-Path $backup $relative
            [IO.Directory]::CreateDirectory((Split-Path -Parent $old)) | Out-Null
            Move-Item -LiteralPath $jar.FullName -Destination $old -Force -ErrorAction SilentlyContinue
        }
    }
    Write-Result $true ('已更新到 ' + [string]$plan.TargetVersion + '。')
    if (-not $Elevated) { Start-OldOrNew }
    exit 0
} catch {
    $message = '更新未完成：' + $_.Exception.Message
    try { Write-Result $false $message } catch {}
    try {
        Add-Type -AssemblyName PresentationFramework
        [Windows.MessageBox]::Show($message, 'MangaFlow', 'OK', 'Warning') | Out-Null
    } catch {}
    if (-not $Elevated) { Start-OldOrNew }
    exit 1
} finally {
    if ($ownsMutex -and $null -ne $mutex) { $mutex.ReleaseMutex() }
    if ($null -ne $mutex) { $mutex.Dispose() }
}
