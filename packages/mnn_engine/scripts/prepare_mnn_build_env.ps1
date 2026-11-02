param(
    [string]$Distro = "Ubuntu-22.04"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$version = "3.22.1"
$archiveName = "cmake-$version-linux-x86_64.tar.gz"
$expectedSha256 = "73565c72355c6652e9db149249af36bcab44d9d478c5546fd926e69ad6b43640"
$downloadUrl = "https://github.com/Kitware/CMake/releases/download/v$version/$archiveName"
$downloadDir = Join-Path $env:LOCALAPPDATA "mnn_engine\downloads"
$archivePath = Join-Path $downloadDir $archiveName
$scriptPath = Join-Path $PSScriptRoot "prepare_mnn_build_env.sh"

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "PowerShell 7 or newer is required."
}

if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
    throw "wsl.exe was not found."
}

$distros = (& wsl.exe --list --quiet) -replace "`0", ""
if ($distros -notcontains $Distro) {
    throw "WSL distribution '$Distro' was not found."
}

New-Item -ItemType Directory -Force -Path $downloadDir | Out-Null
$downloadRequired = -not (Test-Path -LiteralPath $archivePath)
if (-not $downloadRequired) {
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath).Hash.ToLowerInvariant()
    $downloadRequired = $actualHash -ne $expectedSha256
}

if ($downloadRequired) {
    $temporaryPath = "$archivePath.download"
    Invoke-WebRequest -UseBasicParsing -Uri $downloadUrl -OutFile $temporaryPath
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $temporaryPath).Hash.ToLowerInvariant()
    if ($actualHash -ne $expectedSha256) {
        throw "CMake archive SHA-256 mismatch. Expected $expectedSha256, got $actualHash."
    }
    Move-Item -Force -LiteralPath $temporaryPath -Destination $archivePath
}

function Convert-ToWslPath([string]$WindowsPath) {
    $fullPath = [System.IO.Path]::GetFullPath($WindowsPath)
    if ($fullPath -notmatch '^([A-Za-z]):\\(.*)$') {
        throw "Only drive-letter Windows paths are supported: $fullPath"
    }
    $drive = $Matches[1].ToLowerInvariant()
    $tail = $Matches[2].Replace('\', '/')
    return "/mnt/$drive/$tail"
}

$wslScriptPath = Convert-ToWslPath $scriptPath
$wslArchivePath = Convert-ToWslPath $archivePath

& wsl.exe -d $Distro -- bash $wslScriptPath $wslArchivePath $expectedSha256
if ($LASTEXITCODE -ne 0) {
    throw "WSL CMake environment preparation failed with exit code $LASTEXITCODE."
}
