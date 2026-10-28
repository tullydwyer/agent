$ErrorActionPreference = 'Stop'
$destination = Join-Path (Split-Path -Parent $PSScriptRoot) '.tools\keyword'
New-Item -ItemType Directory -Force -Path $destination | Out-Null
function Get-VerifiedFile($Url, $Name, $Sha256) {
    $path = Join-Path $destination $Name
    if (-not (Test-Path -LiteralPath $path)) { Invoke-WebRequest -Uri $Url -OutFile $path }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $Sha256) {
        throw "Checksum mismatch for $Name. Remove this file and retry."
    }
}
Get-VerifiedFile 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.14/sherpa-onnx-1.12.14.aar' 'sherpa-onnx-1.12.14.aar' '5A629A899888CB2760E245D9D5340858B15591AEE7FA13644CF57199FF2829B9'
Get-VerifiedFile 'https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2' 'model.tar.bz2' 'F170013B4716E41B62B9BFD809687C207CEF798EF9BC6534D524E17AF9B6561A'
& tar -xjf (Join-Path $destination 'model.tar.bz2') -C $destination
if ($LASTEXITCODE -ne 0) { throw 'Model extraction failed.' }
$source = Join-Path $destination 'sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01'
$assets = Join-Path $destination 'assets\keyword'
New-Item -ItemType Directory -Force -Path $assets | Out-Null
@('encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx', 'decoder-epoch-12-avg-2-chunk-16-left-64.onnx', 'joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx', 'tokens.txt', 'bpe.model', 'README.md') | ForEach-Object {
    Copy-Item -LiteralPath (Join-Path $source $_) -Destination $assets
}
# A known tokenization from the upstream example; this spike detects "hello world".
[IO.File]::WriteAllText((Join-Path $assets 'keywords.txt'), "▁HE LL O ▁WORLD`n", [Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '..\docs\third-party\sherpa-onnx-LICENSE.txt') -Destination (Join-Path $assets 'LICENSE.txt')
@('onnxruntime-LICENSE.txt', 'onnxruntime-ThirdPartyNotices.txt') | ForEach-Object {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot "..\docs\third-party\$_") -Destination $assets
}
$testAssets = Join-Path $destination 'test-assets'
New-Item -ItemType Directory -Force -Path $testAssets | Out-Null
Copy-Item -LiteralPath (Join-Path $source 'test_wavs\0.wav') -Destination (Join-Path $testAssets 'keyword.wav')
Write-Output 'Pinned keyword engine/model prepared. See docs/PHASE0.md for licenses and trial steps.'
