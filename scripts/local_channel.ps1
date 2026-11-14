[CmdletBinding()]
param(
    [string]$Root = 'D:\Kayokoi\MangaFlowLocal',
    [switch]$NoLaunch,
    [switch]$BuildOnly,
    [switch]$Rebuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$repository = 'https://github.com/wozhenbang2004/MangaFlow.git'
$dependencyRepository = 'https://github.com/UnhingedSoftware/tapline.git'
$rootPath = [IO.Path]::GetFullPath($Root).TrimEnd('\')
$ownerName = '.mangaflow-local-channel'
$ownerValue = 'MangaFlowLocal-v1 https://github.com/wozhenbang2004/MangaFlow.git'
$lock = $null
$script:logPath = $null

function Write-Message([string]$Message) {
    Write-Host $Message
    if ($script:logPath) { [IO.File]::AppendAllText($script:logPath, $Message + [Environment]::NewLine) }
}

function Assert-OwnedPath([string]$Path) {
    $absolute = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    if ($absolute -ne $rootPath -and -not $absolute.StartsWith($rootPath + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "路径不属于本机构建目录，已停止：$absolute"
    }
    $probe = $absolute
    while ($probe -and ($probe -eq $rootPath -or $probe.StartsWith($rootPath + '\', [StringComparison]::OrdinalIgnoreCase))) {
        if (Test-Path -LiteralPath $probe) {
            if ((Get-Item -LiteralPath $probe -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "本机构建路径含目录链接，已停止：$probe"
            }
        }
        if ($probe -eq $rootPath) { break }
        $probe = [IO.Path]::GetDirectoryName($probe)
    }
    return $absolute
}

function Invoke-Checked([string]$Program, [string[]]$Arguments, [string]$Directory) {
    Write-Message ('> ' + [IO.Path]::GetFileName($Program) + ' ' + ($Arguments -join ' '))
    Push-Location -LiteralPath $Directory
    $previous = $ErrorActionPreference
    try {
        # Windows PowerShell reports native stderr as error records; preserve it as log text.
        $ErrorActionPreference = 'Continue'
        & $Program @Arguments 2>&1 | ForEach-Object { Write-Message $_.ToString() }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
        Pop-Location
    }
    if ($code -ne 0) { throw "命令退出码为 $code。详细记录：$script:logPath" }
}

function Get-GitText([string[]]$Arguments, [string]$Directory) {
    Push-Location -LiteralPath $Directory
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = @(& $script:git @Arguments 2>&1)
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
        Pop-Location
    }
    if ($code -ne 0) { throw ('Git 未完成：' + (($lines | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine)) }
    return (($lines | ForEach-Object { $_.ToString() }) -join "`n").Trim()
}

function Save-Json([string]$Path, $Value) {
    $target = Assert-OwnedPath $Path
    $temporary = $target + '.new'
    [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $temporary -Destination $target -Force
}

function Read-Json([string]$Path) {
    if (Test-Path -LiteralPath $Path) { return (Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json) }
    return $null
}

function Get-NativeKey([string]$Source) {
    $paths = @('scripts/build_steam.py', 'native/steam/Cargo.toml', 'native/steam/tapline-bounds.patch')
    $nativeSources = Get-ChildItem -LiteralPath (Join-Path $Source 'native/steam/src') -File -Recurse | Sort-Object FullName
    $paths += @($nativeSources | ForEach-Object { $_.FullName.Substring($Source.Length + 1).Replace('\', '/') })
    $parts = foreach ($relative in $paths) {
        $file = Join-Path $Source $relative
        $relative + ':' + (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash
    }
    $parts += 'RUSTFLAGS:' + $env:RUSTFLAGS
    $bytes = [Text.Encoding]::UTF8.GetBytes(($parts -join "`n"))
    $hash = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($hash.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $hash.Dispose() }
}

function Test-NativeOutputs([string]$Source, $State, [string]$Key) {
    if (-not $State -or $State.key -ne $Key) { return $false }
    foreach ($entry in $State.outputs) {
        $file = Assert-OwnedPath (Join-Path $Source $entry.path)
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { return $false }
        if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $entry.sha256) { return $false }
    }
    return @($State.outputs).Count -eq 5
}

function Close-LocalApplication([string]$Versions) {
    foreach ($process in @(Get-Process -Name 'MangaFlow' -ErrorAction SilentlyContinue)) {
        $processPath = $null
        try { $processPath = $process.Path } catch { continue }
        if (-not $processPath -or -not $processPath.StartsWith($Versions + '\', [StringComparison]::OrdinalIgnoreCase)) { continue }
        Write-Message '正在正常关闭本机渠道的旧版本…'
        if (-not $process.CloseMainWindow()) { throw '请先关闭本机渠道的 MangaFlow 窗口，再双击更新。' }
        if (-not $process.WaitForExit(15000)) { throw '旧窗口尚未关闭；请关闭它后再更新。旧版本保持可用。' }
    }
}

function Start-LocalApplication([string]$Executable, [string]$Directory) {
    $applicationData = Assert-OwnedPath (Join-Path $rootPath 'userdata')
    $mangaData = Assert-OwnedPath (Join-Path $applicationData 'MangaFlow')
    if (-not (Test-Path -LiteralPath $mangaData)) {
        $null = New-Item -ItemType Directory -Path $applicationData -Force
        $oldData = Join-Path $env:LOCALAPPDATA 'MangaFlow'
        if (Test-Path -LiteralPath $oldData -PathType Container) {
            Copy-Item -LiteralPath $oldData -Destination $mangaData -Recurse
        }
    }
    $previousData = $env:LOCALAPPDATA
    try {
        $env:LOCALAPPDATA = $applicationData
        Start-Process -FilePath $Executable -WorkingDirectory $Directory
    } finally { $env:LOCALAPPDATA = $previousData }
}

function Assert-NoOtherChannel([string]$Versions) {
    foreach ($process in @(Get-Process -Name 'MangaFlow' -ErrorAction SilentlyContinue)) {
        $processPath = $null
        try { $processPath = $process.Path } catch { }
        if (-not $processPath -or -not $processPath.StartsWith($Versions + '\', [StringComparison]::OrdinalIgnoreCase)) {
            throw '请先关闭其他渠道的 MangaFlow 窗口，再启动本机版本；本脚本不会关闭它。'
        }
    }
}

try {
    if (-not $env:OS -or $env:OS -ne 'Windows_NT') { throw '此入口仅用于 Windows 本机渠道。' }
    if ($rootPath -eq [IO.Path]::GetPathRoot($rootPath).TrimEnd('\')) { throw '不能把磁盘根目录用作本机构建目录。' }
    $null = Assert-OwnedPath $rootPath
    $owner = Join-Path $rootPath $ownerName
    if (Test-Path -LiteralPath $rootPath) {
        if (-not (Test-Path -LiteralPath $owner)) {
            if (@(Get-ChildItem -LiteralPath $rootPath -Force).Count -gt 0) { throw "目录已有其他文件，未接管：$rootPath" }
        } elseif ((Get-Content -LiteralPath $owner -Raw).Trim() -ne $ownerValue) { throw '本机渠道目录身份不匹配。' }
    } else { $null = New-Item -ItemType Directory -Path $rootPath }
    [IO.File]::WriteAllText($owner, $ownerValue)
    try { $lock = [IO.File]::Open((Join-Path $rootPath 'update.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
    catch { throw '已有本机更新正在运行，请等待那个窗口完成。' }
    $logs = Assert-OwnedPath (Join-Path $rootPath 'logs')
    $null = New-Item -ItemType Directory -Path $logs -Force
    $script:logPath = Join-Path $logs ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $PID + '.log')
    Write-Message 'MangaFlow 本机渠道：拉取代码 → 增量构建 → 替换 → 启动'
    Write-Message "本机构建目录：$rootPath"
    Write-Message '首次需要下载依赖；之后保留源码、Gradle 与 Rust 缓存。'
    # Resolve installed tools before isolating Rust caches; mise shims otherwise
    # try to install the global Rust selection into this new local cache.
    $javaCommand = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        Join-Path $env:JAVA_HOME 'bin\java.exe'
    } else { (Get-Command java -ErrorAction Stop).Source }
    $pythonCommand = (Get-Command python -ErrorAction Stop).Source
    if ($pythonCommand -match '[\\/]mise[\\/]shims[\\/]') {
        $miseCommand = (Get-Command mise -ErrorAction Stop).Source
        $pythonCommand = (& $miseCommand which python | Select-Object -Last 1).Trim()
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $pythonCommand -PathType Leaf)) { throw '无法定位现有 Python。' }
    }
    $rustupCommand = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.cargo\bin\rustup.exe'
    if (-not (Test-Path -LiteralPath $rustupCommand -PathType Leaf)) { $rustupCommand = (Get-Command rustup -ErrorAction Stop).Source }
    $env:Path = ([IO.Path]::GetDirectoryName($javaCommand) + ';' + [IO.Path]::GetDirectoryName($pythonCommand) + ';' + [IO.Path]::GetDirectoryName($rustupCommand) + ';' + $env:Path)
    $cacheRoot = Assert-OwnedPath (Join-Path $rootPath 'caches')
    $temporary = Assert-OwnedPath (Join-Path $rootPath 'temp')
    foreach ($directory in @($cacheRoot, $temporary)) { $null = New-Item -ItemType Directory -Path $directory -Force }
    $env:GRADLE_USER_HOME = Join-Path $cacheRoot 'gradle'
    $env:CARGO_HOME = Join-Path $cacheRoot 'cargo'
    $env:RUSTUP_HOME = Join-Path $cacheRoot 'rustup'
    $env:ANDROID_USER_HOME = Join-Path $cacheRoot 'android-user'
    $env:TEMP = $temporary
    $env:TMP = $temporary
    # Keep Java's selector socket outside MSIX-virtualized user directories too.
    if ($env:JAVA_TOOL_OPTIONS -notmatch 'jdk\.net\.unixdomain\.tmpdir=') {
        $socketDirectory = Assert-OwnedPath (Join-Path $temporary 'sockets')
        $null = New-Item -ItemType Directory -Path $socketDirectory -Force
        $env:JAVA_TOOL_OPTIONS = ($env:JAVA_TOOL_OPTIONS + ' -Djdk.net.unixdomain.tmpdir="' + $socketDirectory + '"').Trim()
    }
    $script:git = (Get-Command git -ErrorAction Stop).Source
    $source = Assert-OwnedPath (Join-Path $rootPath 'source')
    if (-not (Test-Path -LiteralPath (Join-Path $source '.git'))) {
        if ((Test-Path -LiteralPath $source) -and @(Get-ChildItem -LiteralPath $source -Force).Count -gt 0) { throw 'source 目录已有其他文件，未覆盖。' }
        Invoke-Checked $script:git @('-c', 'core.autocrlf=false', 'clone', '--filter=blob:none', '--depth=1', '--single-branch', '--branch', 'main', '--no-tags', $repository, $source) $rootPath
    }
    $origin = Get-GitText @('remote', 'get-url', 'origin') $source
    if ($origin -ne $repository) { throw '本机源码的 origin 不匹配，未更新。' }
    Invoke-Checked $script:git @('config', '--local', 'core.autocrlf', 'false') $source
    if (Get-GitText @('status', '--porcelain', '--untracked-files=all') $source) { throw '本机独立源码目录存在手动修改，未覆盖；请保留处理后再更新。' }
    Invoke-Checked $script:git @('fetch', '--depth=1', '--no-tags', 'origin', 'main') $source
    # Detached checkout can advance a shallow cache without reset or forced overwrites.
    Invoke-Checked $script:git @('checkout', '--detach', 'FETCH_HEAD') $source
    $commit = Get-GitText @('rev-parse', 'HEAD') $source
    if ($commit -notmatch '^[0-9a-f]{40}$') { throw '未取得有效源码版本。' }
    $versions = Assert-OwnedPath (Join-Path $rootPath 'versions')
    $null = New-Item -ItemType Directory -Path $versions -Force
    $currentPath = Join-Path $rootPath 'current.json'
    $current = Read-Json $currentPath
    if ($current -and $current.commit -eq $commit -and -not $Rebuild -and -not $BuildOnly) {
        $existingImage = Assert-OwnedPath (Join-Path $versions $current.directory)
        $existingExe = Join-Path $existingImage 'MangaFlow.exe'
        if (Test-Path -LiteralPath $existingExe -PathType Leaf) {
            Write-Message '源码未变化，直接复用已经部署的本机版本。'
            if (-not $NoLaunch) {
                Assert-NoOtherChannel $versions
                $running = @(Get-Process -Name MangaFlow -ErrorAction SilentlyContinue | Where-Object { try { $_.Path -eq $existingExe } catch { $false } })
                if ($running.Count -eq 0) { Start-LocalApplication $existingExe $existingImage }
            }
            Write-Message "程序：$existingExe"
            exit 0
        }
    }
    $java = $javaCommand
    Invoke-Checked $java @('-version') $source
    $localSdk = Assert-OwnedPath (Join-Path $rootPath 'android-sdk')
    $sdkProperties = Assert-OwnedPath (Join-Path $source 'local.properties')
    if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT -and
        -not (Test-Path -LiteralPath $sdkProperties) -and (Test-Path -LiteralPath (Join-Path $localSdk 'platforms'))) {
        [IO.File]::WriteAllText($sdkProperties, ('sdk.dir=' + $localSdk.Replace('\', '/') + [Environment]::NewLine), (New-Object Text.UTF8Encoding($false)))
    }
    if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT -and -not (Test-Path -LiteralPath (Join-Path $source 'local.properties'))) {
        Write-Message '未设置 Android SDK 路径；先由桌面构建判断是否需要，缺件会保留在日志里。'
    }
    $nativeKey = Get-NativeKey $source
    $nativeStatePath = Join-Path $rootPath 'native-state.json'
    $nativeState = Read-Json $nativeStatePath
    if ($Rebuild -or -not (Test-NativeOutputs $source $nativeState $nativeKey)) {
        $python = (Get-Command python -ErrorAction Stop).Source
        $rustup = (Get-Command rustup -ErrorAction Stop).Source
        $steamScript = Get-Content -LiteralPath (Join-Path $source 'scripts/build_steam.py') -Raw -Encoding UTF8
        $pinMatch = [regex]::Match($steamScript, '(?m)^TAPLINE_SHA = "([0-9a-f]{40})"$')
        $rustMatch = [regex]::Match($steamScript, '(?m)^RUST = "([0-9.]+)"$')
        if (-not $pinMatch.Success -or -not $rustMatch.Success) { throw 'Steam 构建脚本的固定版本格式已变化，停止猜测。' }
        $rustVersion = $rustMatch.Groups[1].Value
        $installed = @(& $rustup toolchain list 2>&1) -join "`n"
        if ($LASTEXITCODE -ne 0 -or $installed -notmatch ('(?m)^' + [regex]::Escape($rustVersion) + '-')) {
            throw "缺少固定 Rust 工具链，请先运行：rustup toolchain install $rustVersion --profile minimal --target x86_64-pc-windows-msvc"
        }
        $dependency = Assert-OwnedPath (Join-Path $source 'native/steam/tapline')
        if (Test-Path -LiteralPath $dependency) {
            $archives = Assert-OwnedPath (Join-Path $rootPath 'previous-native-sources')
            $null = New-Item -ItemType Directory -Path $archives -Force
            $previousDependency = Assert-OwnedPath (Join-Path $archives ([Guid]::NewGuid().ToString('N')))
            # Preserve the previous patched source; do not discard manual or generated changes.
            $null = Assert-OwnedPath $dependency
            Move-Item -LiteralPath $dependency -Destination $previousDependency
        }
        Invoke-Checked $script:git @('init', $dependency) $source
        Invoke-Checked $script:git @('remote', 'add', 'origin', $dependencyRepository) $dependency
        Invoke-Checked $script:git @('fetch', '--filter=blob:none', '--depth=1', 'origin', $pinMatch.Groups[1].Value) $dependency
        Invoke-Checked $script:git @('-c', 'core.autocrlf=false', 'checkout', '--detach', $pinMatch.Groups[1].Value) $dependency
        Write-Message 'Steam 本机组件有变化，使用已有 Cargo 缓存增量构建…'
        Invoke-Checked $python @('scripts/build_steam.py', '--platform', 'windows') $source
        $outputs = @(
            'shared/src/desktopMain/resources/steam/windows-x64/mangaflow_steam.dll',
            'shared/src/desktopMain/resources/steam/windows-x64/Tapline-MPL-2.0.txt',
            'shared/src/desktopMain/resources/steam/windows-x64/Steam-Component-ThirdPartyNotices.txt',
            'native/steam/build/build-provenance.json',
            'native/steam/build/Cargo.lock'
        ) | ForEach-Object {
            @{ path = $_; sha256 = (Get-FileHash -LiteralPath (Join-Path $source $_) -Algorithm SHA256).Hash }
        }
        Save-Json $nativeStatePath @{ key = $nativeKey; outputs = @($outputs) }
    } else { Write-Message 'Steam 本机组件未变化，复用已核对的 DLL 和许可证。' }
    $version = '0.1.0-local.' + $commit.Substring(0, 12)
    Write-Message '正在增量构建桌面程序（不生成 EXE/MSI 安装器）…'
    Invoke-Checked (Join-Path $source 'gradlew.bat') @('--daemon', '--build-cache', '--parallel', '--max-workers=2', '--console=plain', "-PmangaVersionName=$version", ':desktopApp:createDistributable') $source
    $image = Join-Path $source 'desktopApp/build/compose/binaries/main/app/MangaFlow'
    foreach ($required in @('MangaFlow.exe', 'app/MangaFlow.cfg', 'runtime/bin/java.dll')) {
        if (-not (Test-Path -LiteralPath (Join-Path $image $required) -PathType Leaf)) { throw "构建产物不完整：$required" }
    }
    if ($BuildOnly) { Write-Message "构建完成：$image"; exit 0 }
    if (@(Get-ChildItem -LiteralPath $image -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count -gt 0) {
        throw '构建产物含目录或文件链接，未复制。'
    }
    $directoryName = $commit.Substring(0, 12) + '-' + (Get-Date -Format 'yyyyMMddHHmmss')
    $destination = Assert-OwnedPath (Join-Path $versions $directoryName)
    $null = New-Item -ItemType Directory -Path $destination
    Write-Message '正在部署到独立本机程序目录…'
    & robocopy.exe $image $destination /E /COPY:DAT /DCOPY:DAT /R:1 /W:1 /NFL /NDL /NP /NJH /NJS | ForEach-Object { Write-Message $_.ToString() }
    if ($LASTEXITCODE -ge 8) { throw '复制新版本未完成；原程序入口仍保留。' }
    $executable = Join-Path $destination 'MangaFlow.exe'
    if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) { throw '复制后未找到 MangaFlow.exe，未替换入口。' }
    if (-not $NoLaunch) { Assert-NoOtherChannel $versions }
    Close-LocalApplication $versions
    if ($current) { Save-Json (Join-Path $rootPath 'previous.json') $current }
    Save-Json $currentPath @{ commit = $commit; directory = $directoryName; version = $version; updatedAt = [DateTime]::UtcNow.ToString('o') }
    if (-not $NoLaunch) { Start-LocalApplication $executable $destination }
    Write-Message "本机更新完成：$version"
    Write-Message "程序：$executable"
    Write-Message "日志：$script:logPath"
} catch {
    Write-Message ('本机更新停止：' + $_.Exception.Message)
    if ($script:logPath) { Write-Host "日志：$script:logPath" }
    exit 1
} finally {
    if ($lock) { $lock.Dispose() }
}
