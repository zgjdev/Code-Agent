param(
    [string]$JarPath,
    [string]$LicensePath,
    [string]$OutputPath
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'codeagent-install-common.ps1')
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$stage = $null
try {
    [xml]$pom = [IO.File]::ReadAllText((Join-Path $repository 'pom.xml'))
    $version = [string]$pom.project.version
    if ($version -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$') { throw 'Unsupported release version format.' }
    $defaultJar = -not $JarPath
    if (-not $JarPath) { $JarPath = Join-Path $repository "target/codeagent-$version.jar" }
    if (-not $LicensePath) { $LicensePath = Join-Path $repository 'LICENSE' }
    if (-not $OutputPath) { $OutputPath = Join-Path $repository "target/codeagent-$version-windows.zip" }
    $JarPath = [IO.Path]::GetFullPath($JarPath)
    $LicensePath = [IO.Path]::GetFullPath($LicensePath)
    $OutputPath = [IO.Path]::GetFullPath($OutputPath)
    if (-not (Test-Path -LiteralPath $LicensePath -PathType Leaf)) {
        throw 'A maintainer-provided license is required. Supply -LicensePath; this script never chooses a license.'
    }
    if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw 'Build the application with mvn clean package first, or supply -JarPath.' }
    Test-CodeAgentJar $JarPath
    $archive = [IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName -match '(?i)(?:^|/)\.env[^/]*$|\.java$|(?:^|/)\.git(?:/|$)|^(?:history|logs|snapshots)/') {
                throw 'The application JAR contains source, environment, or runtime data files and cannot be distributed.'
            }
        }
        $reader = New-Object IO.StreamReader($archive.GetEntry('META-INF/MANIFEST.MF').Open())
        try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($manifest -cnotmatch ('(?m)^Implementation-Version:\s*' + [regex]::Escape($version) + '\s*$')) {
            throw 'The JAR implementation version does not match pom.xml. Rebuild the current source.'
        }
    } finally { $archive.Dispose() }
    if ($defaultJar) {
        $jarTime = (Get-Item -LiteralPath $JarPath).LastWriteTimeUtc
        $newerSources = @(Get-ChildItem -LiteralPath (Join-Path $repository 'src/main') -File -Recurse | Where-Object { $_.LastWriteTimeUtc -gt $jarTime })
        if ($newerSources.Count -gt 0 -or (Get-Item -LiteralPath (Join-Path $repository 'pom.xml')).LastWriteTimeUtc -gt $jarTime) {
            throw 'The application JAR is older than current sources. Run mvn clean package first.'
        }
    }
    $parent = [IO.Path]::GetDirectoryName($OutputPath)
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    $stage = Join-Path $parent ('.codeagent-package-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $stage | Out-Null
    Copy-Item -LiteralPath $JarPath -Destination (Join-Path $stage 'codeagent.jar')
    Copy-Item -LiteralPath $LicensePath -Destination (Join-Path $stage 'LICENSE')
    foreach ($name in @('install-codeagent.ps1','codeagent-install-common.ps1','codeagent.cmd.template')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $stage $name)
    }
    $instructions = @'
CodeAgent for Windows

1. Install Java 17 or later.
2. Extract this complete ZIP to a local folder.
3. In PowerShell, run:
   powershell -NoProfile -ExecutionPolicy Bypass -File .\install-codeagent.ps1
   If Java is not detected, append: -JavaHome "C:\Path\To\jdk-17"
4. Open a new terminal, enter your project directory, and run: codeagent
5. Follow the first-run model setup. To change it later, run: codeagent config

You do not need Maven, Git, source code, or an .env file.
The installer only updates your user PATH and its own application files.
Existing model settings, sessions, and models are preserved during upgrades.
If an update fails because the application is running, close it and retry.

API keys are stored locally with access restricted to your user account.
They are not encrypted by this release. Never share your config.json.

Manual uninstall: close CodeAgent, remove the installation bin directory
from your user PATH, and remove only bin\codeagent.cmd and lib\codeagent.jar.
Retain config.json, history, models and other user data unless you decide
to remove them yourself.

The supplied SHA256SUMS verifies package consistency; it is not a publisher signature.
'@
    [IO.File]::WriteAllText((Join-Path $stage 'README.txt'),$instructions,(New-Object Text.UTF8Encoding($false)))
    $lines = foreach ($file in (Get-ChildItem -LiteralPath $stage -File | Sort-Object Name)) {
        (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLowerInvariant() + '  ' + $file.Name
    }
    [IO.File]::WriteAllLines((Join-Path $stage 'SHA256SUMS'),$lines,(New-Object Text.UTF8Encoding($false)))
    $pending = Join-Path $parent ('.codeagent-archive-' + [Guid]::NewGuid().ToString('N') + '.zip')
    try {
        [IO.Compression.ZipFile]::CreateFromDirectory($stage,$pending,[IO.Compression.CompressionLevel]::Optimal,$false)
        if (Test-Path -LiteralPath $OutputPath) {
            $backup = $pending + '.previous'
            [IO.File]::Replace($pending,$OutputPath,$backup)
            Remove-Item -LiteralPath $backup -Force
        } else { [IO.File]::Move($pending,$OutputPath) }
    } finally { if (Test-Path -LiteralPath $pending) { Remove-Item -LiteralPath $pending -Force } }
    Write-Output ('Created Windows distribution: ' + $OutputPath)
} catch {
    Write-Error ('Packaging failed: ' + $_.Exception.Message) -ErrorAction Continue
    exit 1
} finally {
    if ($stage -and (Test-Path -LiteralPath $stage)) {
        $stagePath = [IO.Path]::GetFullPath($stage)
        $parentPath = [IO.Path]::GetFullPath([IO.Path]::GetDirectoryName($OutputPath)).TrimEnd('\') + '\'
        if (-not $stagePath.StartsWith($parentPath,[StringComparison]::OrdinalIgnoreCase) -or
                [IO.Path]::GetFileName($stagePath) -notmatch '^\.codeagent-package-[0-9a-f]{32}$') { throw 'Unexpected packaging staging directory.' }
        foreach ($file in @(Get-ChildItem -LiteralPath $stagePath -Force)) {
            if ($file.PSIsContainer -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Unexpected entry inside packaging staging.' }
            Remove-Item -LiteralPath $file.FullName -Force
        }
        Remove-Item -LiteralPath $stagePath -Force
    }
}
