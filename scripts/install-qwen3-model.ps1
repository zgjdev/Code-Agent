param(
    [string]$SourceDirectory,
    [string]$Destination = (Join-Path $env:USERPROFILE '.codeagent\models\qwen3-embedding-0.6b\c25a394dd583836952667c12f008335071b3f43d')
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$revision = 'c25a394dd583836952667c12f008335071b3f43d'
$artifacts = @(
    @{name='model.onnx'; remote='onnx/model.onnx'; sha='bf27b2f3f9ef9c32ca337d75b361fa99439deaeaefe82e4701b2dbd8439197cc'},
    @{name='model.onnx_data'; remote='onnx/model.onnx_data'; sha='f0a61604465929a27e68aa6217c8c89ec6186572f0209fdb7711adda48a9b9a9'},
    @{name='tokenizer.json'; remote='tokenizer.json'; sha='def76fb086971c7867b829c23a26261e38d9d74e02139253b38aeb9df8b4b50a'}
)
$modelDestination = [IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Force -Path $modelDestination | Out-Null
foreach ($artifact in $artifacts) {
    $target = Join-Path $modelDestination $artifact.name
    if ((Test-Path -LiteralPath $target) -and (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash -eq $artifact.sha) {
        Write-Output "Verified $($artifact.name)"; continue
    }
    $pending = $target + '.pending'
    if ($SourceDirectory) {
        $source = Join-Path ([IO.Path]::GetFullPath($SourceDirectory)) $artifact.name
        if ((Get-FileHash -Algorithm SHA256 -LiteralPath $source).Hash -ne $artifact.sha) { throw "Source checksum mismatch: $($artifact.name)" }
        Copy-Item -LiteralPath $source -Destination $pending
    } else {
        $url = "https://huggingface.co/onnx-community/Qwen3-Embedding-0.6B-ONNX/resolve/$revision/$($artifact.remote)"
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $pending -TimeoutSec 1800
    }
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $pending).Hash -ne $artifact.sha) { throw "Downloaded checksum mismatch: $($artifact.name)" }
    Move-Item -LiteralPath $pending -Destination $target -Force
    Write-Output "Installed $($artifact.name)"
}
Write-Output "Qwen FP32 1024 installed: $modelDestination"
