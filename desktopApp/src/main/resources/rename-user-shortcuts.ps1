$ErrorActionPreference = 'Stop'
$target = $env:KAYO_SHORTCUT_TARGET
if (-not $target) { return }

# The official MSI owns the common Desktop and Programs entries and removes them on upgrade.
# Look only in the current user's known folders. Keep custom links and all taskbar pins.
$desktop = [Environment]::GetFolderPath('DesktopDirectory')
$programs = [Environment]::GetFolderPath('Programs')
$slots = @()
if ($desktop -and $desktop -ne [Environment]::GetFolderPath('CommonDesktopDirectory')) {
    $slots += @{ Old = (Join-Path $desktop 'MangaFlow.lnk'); New = (Join-Path $desktop 'Kayo.lnk') }
}
if ($programs -and $programs -ne [Environment]::GetFolderPath('CommonPrograms')) {
    $slots += @{ Old = (Join-Path $programs 'MangaFlow\MangaFlow.lnk'); New = (Join-Path $programs 'Kayo\Kayo.lnk') }
}
$shell = $null
foreach ($slot in $slots) {
    try {
        if (-not [IO.File]::Exists($slot.Old) -or (Test-Path -LiteralPath $slot.New)) { continue }
        $oldFolder = [IO.Path]::GetDirectoryName($slot.Old)
        $newFolder = [IO.Path]::GetDirectoryName($slot.New)
        if (([IO.File]::GetAttributes($slot.Old) -band [IO.FileAttributes]::ReparsePoint) -or
            ([IO.File]::GetAttributes($oldFolder) -band [IO.FileAttributes]::ReparsePoint)) { continue }
        if ([IO.Directory]::Exists($newFolder) -and
            ([IO.File]::GetAttributes($newFolder) -band [IO.FileAttributes]::ReparsePoint)) { continue }
        if (-not $shell) { $shell = New-Object -ComObject WScript.Shell }
        $link = $shell.CreateShortcut($slot.Old)
        if (-not $link.TargetPath -or $link.Arguments -or
            -not [String]::Equals([IO.Path]::GetFullPath($link.TargetPath), $target, [StringComparison]::OrdinalIgnoreCase)) { continue }
        [void][IO.Directory]::CreateDirectory($newFolder)
        [IO.File]::Move($slot.Old, $slot.New)
    } catch {
        # A missing, protected, or concurrently changed personal link does not affect this update.
    }
}
