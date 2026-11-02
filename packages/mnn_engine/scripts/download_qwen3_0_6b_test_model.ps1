param(
    [string]$Destination = (Join-Path (Split-Path -Parent $PSScriptRoot) ".test-models\Qwen3-0.6B-MNN")
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "PowerShell 7 or newer is required."
}
$revision = "34dfccda1187ded6e07ea06426da576b0b793c6b"
$baseUrl = "https://huggingface.co/taobao-mnn/Qwen3-0.6B-MNN/resolve/$revision"
$files = @(
    @{ Name = ".gitattributes"; Size = 1642L; Sha256 = $null },
    @{ Name = "README.md"; Size = 1110L; Sha256 = $null },
    @{ Name = "config.json"; Size = 403L; Sha256 = $null },
    @{ Name = "llm.mnn"; Size = 461520L; Sha256 = "d426c65a5159c938ccc237cdfbd982137f276804f27b414ca0ecf3fc0a660f8c" },
    @{ Name = "llm.mnn.weight"; Size = 450810338L; Sha256 = "953afb7e0165818add34a7a6caf0af5d0ed9428da102eb22c9b98ee9da292e9f" },
    @{ Name = "llm_config.json"; Size = 4880L; Sha256 = $null },
    @{ Name = "tokenizer.txt"; Size = 3193569L; Sha256 = $null }
)

$destinationPath = [System.IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Force -Path $destinationPath | Out-Null

function Test-ModelFile([string]$Path, [long]$ExpectedSize, [string]$ExpectedSha256) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $false
    }
    if ((Get-Item -LiteralPath $Path).Length -ne $ExpectedSize) {
        return $false
    }
    if (-not [string]::IsNullOrEmpty($ExpectedSha256)) {
        return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -eq $ExpectedSha256
    }
    return $true
}

$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.UseProxy = $true
$handler.Proxy = [System.Net.WebRequest]::DefaultWebProxy
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromMinutes(30)

function Invoke-ResumableDownload([string]$Url, [string]$Path, [long]$ExpectedSize) {
    $attempt = 0
    while ($attempt -lt 4) {
        $attempt++
        $existingLength = if (Test-Path -LiteralPath $Path -PathType Leaf) {
            (Get-Item -LiteralPath $Path).Length
        } else {
            0L
        }
        if ($existingLength -gt $ExpectedSize) {
            Remove-Item -LiteralPath $Path -Force
            $existingLength = 0L
        }

        $request = [System.Net.Http.HttpRequestMessage]::new(
            [System.Net.Http.HttpMethod]::Get,
            $Url
        )
        if ($existingLength -gt 0) {
            $request.Headers.Range = [System.Net.Http.Headers.RangeHeaderValue]::new($existingLength, $null)
        }

        $response = $null
        $inputStream = $null
        $outputStream = $null
        try {
            $response = $client.Send(
                $request,
                [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead
            )
            $response.EnsureSuccessStatusCode() | Out-Null
            $append = $existingLength -gt 0 -and
                $response.StatusCode -eq [System.Net.HttpStatusCode]::PartialContent
            if (-not $append) {
                $existingLength = 0L
            }
            $mode = if ($append) {
                [System.IO.FileMode]::Append
            } else {
                [System.IO.FileMode]::Create
            }
            $inputStream = $response.Content.ReadAsStream()
            $outputStream = [System.IO.File]::Open(
                $Path,
                $mode,
                [System.IO.FileAccess]::Write,
                [System.IO.FileShare]::None
            )
            $buffer = [byte[]]::new(1024 * 1024)
            $downloaded = $existingLength
            while (($read = $inputStream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                $outputStream.Write($buffer, 0, $read)
                $downloaded += $read
                $percent = [Math]::Min(100, [int](100 * $downloaded / $ExpectedSize))
                Write-Progress -Activity "Downloading $(Split-Path -Leaf $Path)" -Status "$downloaded / $ExpectedSize bytes" -PercentComplete $percent
            }
            Write-Progress -Activity "Downloading $(Split-Path -Leaf $Path)" -Completed
            return
        } catch {
            if ($attempt -ge 4) {
                throw
            }
            Write-Warning "Download attempt $attempt failed: $($_.Exception.Message). Retrying..."
            Start-Sleep -Seconds 2
        } finally {
            if ($null -ne $outputStream) { $outputStream.Dispose() }
            if ($null -ne $inputStream) { $inputStream.Dispose() }
            if ($null -ne $response) { $response.Dispose() }
            $request.Dispose()
        }
    }
}

foreach ($file in $files) {
    $target = Join-Path $destinationPath $file.Name
    if (Test-ModelFile $target $file.Size $file.Sha256) {
        Write-Output "Verified: $($file.Name)"
        continue
    }

    $partial = "$target.download"
    $url = "$baseUrl/$([Uri]::EscapeDataString($file.Name))?download=true"
    Write-Output "Downloading: $($file.Name)"
    Invoke-ResumableDownload $url $partial $file.Size
    if (-not (Test-ModelFile $partial $file.Size $file.Sha256)) {
        throw "Downloaded file failed size or SHA-256 verification: $($file.Name)"
    }
    Move-Item -Force -LiteralPath $partial -Destination $target
}

$client.Dispose()
$handler.Dispose()
$totalSize = ($files | Measure-Object -Property Size -Sum).Sum
Write-Output "Qwen3-0.6B-MNN is ready at $destinationPath ($totalSize bytes, revision $revision)."
