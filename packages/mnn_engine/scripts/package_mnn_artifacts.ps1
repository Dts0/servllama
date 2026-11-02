param(
    [string]$Distro = "Ubuntu-22.04",
    [string]$PluginRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

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

function Convert-ToWslPath([string]$WindowsPath) {
    $fullPath = [System.IO.Path]::GetFullPath($WindowsPath)
    if ($fullPath -notmatch '^([A-Za-z]):\\(.*)$') {
        throw "Only drive-letter Windows paths are supported: $fullPath"
    }
    $drive = $Matches[1].ToLowerInvariant()
    $tail = $Matches[2].Replace('\', '/')
    return "/mnt/$drive/$tail"
}

$scriptPath = Join-Path $PSScriptRoot "package_mnn_artifacts.sh"
$wslScriptPath = Convert-ToWslPath $scriptPath
$wslPluginRoot = Convert-ToWslPath $PluginRoot

& wsl.exe -d $Distro -- bash $wslScriptPath $wslPluginRoot
if ($LASTEXITCODE -ne 0) {
    throw "MNN Android artifact packaging failed with exit code $LASTEXITCODE."
}
