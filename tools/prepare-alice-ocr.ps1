# Downloads the Russian OCR language model from the official Tesseract project.
# The model remains local and is not committed to GitHub.
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dir = Join-Path $repo 'app/src/main/assets/tessdata'
$file = Join-Path $dir 'rus.traineddata'
New-Item -ItemType Directory -Path $dir -Force | Out-Null
if ((Test-Path $file) -and (Get-Item $file).Length -gt 2000000) {
    Write-Host 'RUS OCR MODEL: READY'
    Get-FileHash -Path $file -Algorithm SHA256 | Select-Object Hash
    exit 0
}
$temp = "$file.partial"
if (Test-Path $temp) { Remove-Item $temp -Force }
try {
    $source = 'https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/rus.traineddata'
    Invoke-WebRequest -Uri $source -OutFile $temp -TimeoutSec 90
    if ((Get-Item $temp).Length -lt 2000000) { throw 'Downloaded OCR model is unexpectedly small' }
    Move-Item -Path $temp -Destination $file -Force
    Write-Host 'RUS OCR MODEL: DOWNLOADED'
    Get-FileHash -Path $file -Algorithm SHA256 | Select-Object Hash
} finally {
    if (Test-Path $temp) { Remove-Item $temp -Force }
}
