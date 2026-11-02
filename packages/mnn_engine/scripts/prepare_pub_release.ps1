param(
    [string]$OutputRoot = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "PowerShell 7 or newer is required."
}

$pluginRoot = Split-Path -Parent $PSScriptRoot
$status = & git -C $pluginRoot status --porcelain=v1 --untracked-files=all
if ($LASTEXITCODE -ne 0) {
    throw "Unable to read the mnn_engine Git status."
}
if ($status) {
    throw "The repository must be clean before creating a pub.dev release staging directory."
}

$pubspecPath = Join-Path $pluginRoot "pubspec.yaml"
$pubspec = Get-Content -Raw -LiteralPath $pubspecPath
$versionMatch = [regex]::Match($pubspec, '(?m)^version:\s*([^\s]+)\s*$')
if (-not $versionMatch.Success) {
    throw "Unable to read the version from $pubspecPath."
}
$version = $versionMatch.Groups[1].Value

if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path ([System.IO.Path]::GetTempPath()) "mnn_engine-pub"
}
$resolvedOutputRoot = [System.IO.Path]::GetFullPath($OutputRoot)
New-Item -ItemType Directory -Force -Path $resolvedOutputRoot | Out-Null

$releaseId = "mnn_engine-$version-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
$stagingPath = Join-Path $resolvedOutputRoot $releaseId
$archivePath = Join-Path $resolvedOutputRoot "$releaseId.zip"
if (Test-Path -LiteralPath $stagingPath -PathType Any) {
    throw "Staging path already exists: $stagingPath"
}
if (Test-Path -LiteralPath $archivePath -PathType Any) {
    throw "Archive path already exists: $archivePath"
}

& git -C $pluginRoot archive --format=zip "--output=$archivePath" HEAD
if ($LASTEXITCODE -ne 0) {
    throw "git archive failed with exit code $LASTEXITCODE."
}

Expand-Archive -LiteralPath $archivePath -DestinationPath $stagingPath

$requiredFiles = @(
    "pubspec.yaml",
    "README.md",
    "LICENSE",
    "android/src/main/jniLibs/arm64-v8a/libMNN.so",
    "android/src/main/jniLibs/arm64-v8a/libmnn_engine_jni.so",
    "native/android-arm64-v8a.json"
)
foreach ($relativePath in $requiredFiles) {
    $candidate = Join-Path $stagingPath $relativePath
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "Release staging directory is missing $relativePath."
    }
}

Write-Output "pub.dev release staging directory created from committed HEAD:"
Write-Output "  Package: $stagingPath"
Write-Output "  Archive: $archivePath"
Write-Output ""
Write-Output "Next commands:"
Write-Output "  dart pub publish --dry-run -C `"$stagingPath`""
Write-Output "  dart pub publish -C `"$stagingPath`""
