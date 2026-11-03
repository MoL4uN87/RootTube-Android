# Download official Tesseract fast data for Russian + English OCR.
# Models stay in local Android assets and are ignored by Git.
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dir = Join-Path $repo 'app/src/main/assets/tessdata'
New-Item -ItemType Directory -Path $dir -Force | Out-Null

foreach ($lang in @('rus', 'eng')) {
    $file = Join-Path $dir "$lang.traineddata"
    if ((Test-Path $file) -and (Get-Item $file).Length -gt 2000000) {
        Write-Host "$($lang.ToUpperInvariant()) OCR MODEL: READY"
        Get-FileHash -Path $file -Algorithm SHA256 | Select-Object -Property Path, Hash
        continue
    }
    $temp = "$file.partial"
    if (Test-Path $temp) { Remove-Item $temp -Force }
    try {
        $source = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/$lang.traineddata"
        Invoke-WebRequest -Uri $source -OutFile $temp -TimeoutSec 90
        if ((Get-Item $temp).Length -lt 2000000) {
            throw "Downloaded OCR model $lang is unexpectedly small"
        }
        Move-Item -Path $temp -Destination $file -Force
        Write-Host "$($lang.ToUpperInvariant()) OCR MODEL: DOWNLOADED"
        Get-FileHash -Path $file -Algorithm SHA256 | Select-Object -Property Path, Hash
    } finally {
        if (Test-Path $temp) { Remove-Item $temp -Force }
    }
}
