param(
    [string]$SourceDirectory,
    [string]$CodeAgentHome,
    [switch]$ResolveOnly
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$revision = 'c25a394dd583836952667c12f008335071b3f43d'
$artifacts = @(
    @{name='model.onnx'; remote='onnx/model.onnx'; sha='bf27b2f3f9ef9c32ca337d75b361fa99439deaeaefe82e4701b2dbd8439197cc'},
    @{name='model.onnx_data'; remote='onnx/model.onnx_data'; sha='f0a61604465929a27e68aa6217c8c89ec6186572f0209fdb7711adda48a9b9a9'},
    @{name='tokenizer.json'; remote='tokenizer.json'; sha='def76fb086971c7867b829c23a26261e38d9d74e02139253b38aeb9df8b4b50a'}
)
# Same root precedence as the CLI (the parameter corresponds to the Java property override).
$profileDirectory = [Environment]::GetFolderPath('UserProfile')
function Read-CodeAgentHome([string]$file) {
    if (Test-Path -LiteralPath $file -PathType Leaf) {
        foreach ($line in [IO.File]::ReadAllLines($file)) {
            if ($line -match '^\s*CODEAGENT_HOME\s*=(.*)$') {
                return @{ Found = $true; Value = $Matches[1].Trim() }
            }
        }
    }
    return @{ Found = $false; Value = '' }
}
if ([string]::IsNullOrWhiteSpace($CodeAgentHome)) {
    $CodeAgentHome = $env:CODEAGENT_HOME
    if ([string]::IsNullOrWhiteSpace($CodeAgentHome)) {
        $projectValue = Read-CodeAgentHome (Join-Path (Get-Location).Path '.env')
        $userValue = Read-CodeAgentHome (Join-Path $profileDirectory '.env')
        if ($projectValue.Found) { $CodeAgentHome = $projectValue.Value }
        elseif ($userValue.Found) { $CodeAgentHome = $userValue.Value }
    }
}
if ([string]::IsNullOrWhiteSpace($CodeAgentHome)) {
    $CodeAgentHome = Join-Path $profileDirectory '.codeagent'
} else {
    $CodeAgentHome = $CodeAgentHome.Trim()
    if ($CodeAgentHome -eq '~') { $CodeAgentHome = $profileDirectory }
    elseif ($CodeAgentHome.StartsWith('~/') -or $CodeAgentHome.StartsWith('~\')) {
        $CodeAgentHome = Join-Path $profileDirectory $CodeAgentHome.Substring(2)
    }
}
$windowsPaths = [IO.Path]::DirectorySeparatorChar -eq '\'
if (-not [IO.Path]::IsPathRooted($CodeAgentHome) -or
    ($windowsPaths -and $CodeAgentHome -notmatch '^(?:[A-Za-z]:[\\/]|[\\/]{2}[^\\/]+[\\/][^\\/]+(?:[\\/]|$))')) {
    throw 'CODEAGENT_HOME must be a fully qualified absolute path or ~/ path.'
}
$dataRoot = [IO.Path]::GetFullPath($CodeAgentHome)
$modelDestination = Join-Path (Join-Path (Join-Path $dataRoot 'models') 'qwen3-embedding-0.6b') $revision
if ($ResolveOnly) { Write-Output $modelDestination; return }
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
