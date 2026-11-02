param(
    [string]$Distro = "Ubuntu-22.04",
    [switch]$IncludeHexagon
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "PowerShell 7 or newer is required."
}

$pluginRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "build_mnn_android.sh"

function Convert-ToWslPath([string]$WindowsPath) {
    $fullPath = [System.IO.Path]::GetFullPath($WindowsPath)
    if ($fullPath -notmatch '^([A-Za-z]):\\(.*)$') {
        throw "Only drive-letter Windows paths are supported: $fullPath"
    }
    $drive = $Matches[1].ToLowerInvariant()
    $tail = $Matches[2].Replace('\', '/')
    return "/mnt/$drive/$tail"
}

$wslPluginRoot = Convert-ToWslPath $pluginRoot
$wslScriptPath = Convert-ToWslPath $scriptPath

$hexagonEnabled = if ($IncludeHexagon) { "ON" } else { "OFF" }
& wsl.exe -d $Distro -- env "MNN_HEXAGON=$hexagonEnabled" bash $wslScriptPath $wslPluginRoot
if ($LASTEXITCODE -ne 0) {
    throw "MNN Android build failed with exit code $LASTEXITCODE."
}
