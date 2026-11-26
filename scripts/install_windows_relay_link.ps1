param(
    [Parameter(Mandatory = $true)][string]$Image,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-f]{40}$')][string]$Commit,
    [switch]$Elevated
)
$ErrorActionPreference = 'Stop'
$root = [Environment]::GetFolderPath('ProgramFiles')
$target = Join-Path $root 'MangaFlow'
$appMarker = 'app\.relay-installation'
$revisionMarker = 'app\.mangaflow-local-commit'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$administrator = ([Security.Principal.WindowsPrincipal]::new($identity)).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $administrator) {
    if ($Elevated) { throw 'Administrator permission is required to install the Relay-enabled app.' }
    $engine = Join-Path ([Environment]::SystemDirectory) 'WindowsPowerShell\v1.0\powershell.exe'
    $commandLine = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
        ('"' + $PSCommandPath + '"'), '-Image', ('"' + [IO.Path]::GetFullPath($Image) + '"'), '-Commit', $Commit, '-Elevated')
    $process = Start-Process -FilePath $engine -Verb RunAs -ArgumentList $commandLine -Wait -PassThru -WindowStyle Hidden
    if ($process.ExitCode -ne 0) { throw "Protected MangaFlow installation failed ($($process.ExitCode))." }
    return
}

function Assert-RegularTree([string]$Directory) {
    $pending = [Collections.Generic.Stack[string]]::new()
    $pending.Push([IO.Path]::GetFullPath($Directory))
    while ($pending.Count -gt 0) {
        $current = $pending.Pop()
        if ([IO.File]::GetAttributes($current).HasFlag([IO.FileAttributes]::ReparsePoint)) {
            throw "The installation image contains a reparse point: $current"
        }
        foreach ($entry in [IO.Directory]::EnumerateFileSystemEntries($current)) {
            $attributes = [IO.File]::GetAttributes($entry)
            if ($attributes.HasFlag([IO.FileAttributes]::ReparsePoint)) { throw "Unexpected link: $entry" }
            if ($attributes.HasFlag([IO.FileAttributes]::Directory)) { $pending.Push($entry) }
        }
    }
}

function Assert-Image([string]$Directory) {
    foreach ($item in @('MangaFlow.exe', 'app\MangaFlow.cfg', 'runtime\bin\java.dll')) {
        if (-not (Test-Path -LiteralPath (Join-Path $Directory $item) -PathType Leaf)) {
            throw "The MangaFlow app image is incomplete: $item"
        }
    }
    Assert-RegularTree $Directory
}

$mutex = [Threading.Mutex]::new($false, 'Global\MangaFlowProtectedDesktopInstall')
$held = $false
try {
    try { $held = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $held = $true }
    if (-not $held) { throw 'MangaFlow is already being updated.' }
    Assert-Image $Image
    if ([IO.File]::GetAttributes($root).HasFlag([IO.FileAttributes]::ReparsePoint)) {
        throw 'Program Files is not on the usual protected install path.'
    }
    $installation = [guid]::NewGuid().ToString('N')
    if (Test-Path -LiteralPath $target) {
        Assert-Image $target
        $existingMarker = Join-Path $target $appMarker
        if (Test-Path -LiteralPath $existingMarker -PathType Leaf) {
            $previousId = [IO.File]::ReadAllText($existingMarker).Trim()
            if ($previousId -match '^[a-f0-9]{32}$') { $installation = $previousId }
        }
    }
    $id = [guid]::NewGuid().ToString('N')
    $stage = Join-Path $root "MangaFlow.stage-$id"
    $backup = Join-Path $root "MangaFlow.rollback-$id"
    [void][IO.Directory]::CreateDirectory($stage)
    $security = [Security.AccessControl.DirectorySecurity]::new()
    $security.SetAccessRuleProtection($true, $false)
    $administrators = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
    $security.SetOwner($administrators)
    foreach ($sid in @('S-1-5-32-544', 'S-1-5-18', 'S-1-5-11')) {
        $principal = [Security.Principal.SecurityIdentifier]::new($sid)
        $rights = if ($sid -eq 'S-1-5-11') { 'ReadAndExecute' } else { 'FullControl' }
        $security.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
            $principal, $rights, 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
    }
    Set-Acl -LiteralPath $stage -AclObject $security
    & robocopy.exe $Image $stage /E /COPY:DAT /DCOPY:DAT /XJ /R:1 /W:1 /NFL /NDL /NP /NJH /NJS /XF .relay-installation .mangaflow-local-commit | Out-Host
    if ($LASTEXITCODE -ge 8) { throw "MangaFlow staging failed (robocopy $LASTEXITCODE)." }
    Assert-Image $stage
    [IO.File]::WriteAllText((Join-Path $stage $appMarker), $installation)
    [IO.File]::WriteAllText((Join-Path $stage $revisionMarker), $Commit)
    & icacls.exe $stage /setowner '*S-1-5-32-544' /T /Q | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'MangaFlow files could not be assigned protected ownership.' }
    $saved = $false
    try {
        if (Test-Path -LiteralPath $target) {
            [IO.Directory]::Move($target, $backup)
            $saved = $true
        }
        [IO.Directory]::Move($stage, $target)
    }
    catch {
        if ($saved -and -not (Test-Path -LiteralPath $target)) { [IO.Directory]::Move($backup, $target) }
        throw
    }
    if ($saved) { Write-Host "Previous MangaFlow binaries: $backup" }
}
finally {
    if ($held) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
