param([Parameter(Mandatory=$true)][string]$Repository, [Parameter(Mandatory=$true)][string]$JavaHome, [switch]$CheckPackage)
$ErrorActionPreference = 'Stop'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('codeagent-launcher-' + [Guid]::NewGuid().ToString('N'))
$crossDriveWorkspace = $null
New-Item -ItemType Directory -Path $testRoot | Out-Null
function Assert-True($Value, [string]$Message) { if (-not $Value) { throw $Message } }
function Run-Install([string]$Jar, [string]$Root, [bool]$Success = $true) {
    $oldPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Repository 'scripts/install-codeagent.ps1') -JarPath $Jar -CodeAgentHome $Root -JavaHome $JavaHome -NoPathUpdate 2>&1
        $result = $LASTEXITCODE
    } finally { $ErrorActionPreference = $oldPreference }
    if ($Success) { Assert-True ($result -eq 0) ("Installation failed: " + ($output -join "`n")) }
    else { Assert-True ($result -ne 0) 'Invalid installation unexpectedly succeeded' }
}
function Probe-Launch([string]$Workspace, [string]$Launcher, [string]$Argument, [int]$ExitCode) {
    $probe = Join-Path $Workspace 'probe.txt'
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $env:ComSpec
    $info.Arguments = '/d /c codeagent "' + $Argument + '" ' + $ExitCode
    $info.WorkingDirectory = $Workspace
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.EnvironmentVariables['CODEAGENT_LAUNCH_PROBE'] = $probe
    $info.EnvironmentVariables['Path'] = [IO.Path]::GetDirectoryName($Launcher) + ';' + $env:Path
    $process = [Diagnostics.Process]::Start($info)
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    Assert-True ($process.WaitForExit(15000)) 'Launcher timed out'
    Assert-True ($process.ExitCode -eq $ExitCode) ("Wrong exit code: " + $stderr.Result + $stdout.Result)
    $lines = [IO.File]::ReadAllLines($probe, [Text.Encoding]::UTF8)
    Assert-True ($lines[0] -eq $Workspace) 'Launcher changed the working directory'
    Assert-True ($lines[1] -eq $Argument) 'Launcher changed the quoted argument'
    $process.Dispose()
}
try {
    $source = Join-Path $testRoot 'Main.java'
    [IO.File]::WriteAllText($source, @'
package com.codeagent.cli;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
public class Main {
    public static void main(String[] args) throws Exception {
        Path destination = Path.of(System.getenv("CODEAGENT_LAUNCH_PROBE"));
        Files.writeString(destination, System.getProperty("user.dir") + "\n" + args[0], StandardCharsets.UTF_8);
        System.exit(Integer.parseInt(args[1]));
    }
}
'@, (New-Object Text.UTF8Encoding($false)))
    $classes = Join-Path $testRoot 'classes'
    New-Item -ItemType Directory -Path $classes | Out-Null
    & (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -d $classes $source
    Assert-True ($LASTEXITCODE -eq 0) 'Fixture compilation failed'
    $jar = Join-Path $testRoot 'fixture.jar'
    $manifest = Join-Path $testRoot 'MANIFEST.MF'
    [xml]$pom = [IO.File]::ReadAllText((Join-Path $Repository 'pom.xml'))
    [IO.File]::WriteAllText($manifest, "Manifest-Version: 1.0`r`nImplementation-Version: $($pom.project.version)`r`n")
    & (Join-Path $JavaHome 'bin/jar.exe') --create --file $jar --main-class com.codeagent.cli.Main --manifest $manifest -C $classes .
    Assert-True ($LASTEXITCODE -eq 0) 'Fixture packaging failed'
    $root = Join-Path $testRoot 'installed with spaces & (brackets) ! %CODEAGENT_UNSET_FIXTURE%'
    New-Item -ItemType Directory -Path $root | Out-Null
    $config = Join-Path $root 'config.json'
    [IO.File]::WriteAllText($config, '{"fixture":"preserve-user-settings"}')
    $configHash = (Get-FileHash -LiteralPath $config).Hash
    $originalUserPath = [Environment]::GetEnvironmentVariable('Path','User')
    Run-Install $jar $root
    Run-Install $jar $root
    $orphanStage = Join-Path $root '.install-staging'
    New-Item -ItemType Directory -Path $orphanStage | Out-Null
    [IO.File]::WriteAllText((Join-Path $orphanStage 'codeagent.jar'), 'incomplete-copy')
    [IO.File]::WriteAllText((Join-Path $orphanStage 'transaction.pending'), '{partial-journal')
    Run-Install $jar $root
    Assert-True (-not (Test-Path -LiteralPath $orphanStage)) 'Pre-journal interruption was not recovered'
    New-Item -ItemType Directory -Path $orphanStage | Out-Null
    [IO.File]::WriteAllText((Join-Path $orphanStage 'old-codeagent.jar'), 'obsolete-backup')
    Run-Install $jar $root
    Assert-True (-not (Test-Path -LiteralPath $orphanStage)) 'Post-commit interruption was not recovered'
    New-Item -ItemType Directory -Path $orphanStage | Out-Null
    $unrelated = Join-Path $orphanStage 'keep-user-file.txt'
    [IO.File]::WriteAllText($unrelated, 'preserve')
    Run-Install $jar $root $false
    Assert-True ([IO.File]::ReadAllText($unrelated) -eq 'preserve') 'Orphan cleanup removed unrecognized data'
    Remove-Item -LiteralPath $unrelated -Force
    Remove-Item -LiteralPath $orphanStage -Force
    Assert-True ((Get-FileHash -LiteralPath $config).Hash -eq $configHash) 'Installer changed user configuration'
    $launcher = Join-Path $root 'bin/codeagent.cmd'
    Assert-True (Test-Path -LiteralPath $launcher) 'Launcher was not installed'
    foreach ($name in @('project one', ('project & (two) ! ' + [char]0x9879 + [char]0x76ee))) {
        $workspace = Join-Path $testRoot $name
        New-Item -ItemType Directory -Path $workspace | Out-Null
        Probe-Launch $workspace $launcher 'argument with spaces & (brackets) !' 7
    }
    if ([IO.Path]::GetPathRoot($Repository) -ne [IO.Path]::GetPathRoot($testRoot)) {
        $crossDriveWorkspace = Join-Path $Repository ('target/launcher-workspace-' + [Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $crossDriveWorkspace | Out-Null
        Probe-Launch $crossDriveWorkspace $launcher 'workspace on another drive' 8
    }
    $jarHash = (Get-FileHash -LiteralPath (Join-Path $root 'lib/codeagent.jar')).Hash
    Run-Install (Join-Path $testRoot 'missing.jar') $root $false
    $invalid = Join-Path $testRoot 'invalid.jar'
    [IO.File]::WriteAllText($invalid, 'not-a-jar')
    Run-Install $invalid $root $false
    $wrongMain = Join-Path $testRoot 'wrong-case-main.jar'
    & (Join-Path $JavaHome 'bin/jar.exe') --create --file $wrongMain --main-class com.CodeAgent.cli.Main -C $classes .
    Assert-True ($LASTEXITCODE -eq 0) 'Wrong-case manifest fixture failed'
    Run-Install $wrongMain $root $false
    Assert-True ((Get-FileHash -LiteralPath (Join-Path $root 'lib/codeagent.jar')).Hash -eq $jarHash) 'Failed update replaced the working JAR'
    $heldJar = [IO.File]::Open((Join-Path $root 'lib/codeagent.jar'),[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
    try { Run-Install $jar $root $false }
    finally { $heldJar.Dispose() }
    Run-Install $jar $root
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $root '.install-transaction.json'))) 'Recovery did not finish'
    Assert-True ((Get-FileHash -LiteralPath $config).Hash -eq $configHash) 'Recovery changed user configuration'
    . (Join-Path $Repository 'scripts/codeagent-install-common.ps1')
    foreach ($invalidRoot in @('relative-path', '\root-relative', 'C:drive-relative', "C:\invalid`npath", ('C:\invalid' + [char]0x85 + 'path'))) {
        $rejected = $false
        try { $null = Resolve-CodeAgentInstallRoot $invalidRoot } catch { $rejected = $true }
        Assert-True $rejected 'A non-absolute or control-character root was accepted'
    }
    Assert-True ((Resolve-CodeAgentInstallRoot $root) -eq $root) 'Explicit root changed'
    Assert-True ((Resolve-CodeAgentInstallRoot '~/fixture') -eq (Join-Path ([Environment]::GetFolderPath('UserProfile')) 'fixture')) 'Tilde root did not expand'
    $dotenvFixture = Join-Path $testRoot 'root.env'
    [IO.File]::WriteAllText($dotenvFixture, 'CODEAGENT_HOME=C:\bom-fixture', (New-Object Text.UTF8Encoding($true)))
    Assert-True (-not (Read-CodeAgentRootSetting $dotenvFixture).Found) 'BOM dotenv parsing disagrees with Java'
    [IO.File]::WriteAllText($dotenvFixture, 'codeagent_home=C:\case-insensitive-fixture')
    Assert-True (-not (Read-CodeAgentRootSetting $dotenvFixture).Found) 'Lowercase dotenv key disagrees with Java parsing'
    [IO.File]::WriteAllText($dotenvFixture, "CODEAGENT_HOME=C:\first`nCODEAGENT_HOME=C:\second")
    Assert-True ((Read-CodeAgentRootSetting $dotenvFixture).Value -eq 'C:\first') 'Duplicate dotenv key did not keep the first value'
    [IO.File]::WriteAllText($dotenvFixture, "CODEAGENT_HOME= `n")
    $emptySetting = Read-CodeAgentRootSetting $dotenvFixture
    Assert-True ($emptySetting.Found -and $emptySetting.Value -eq '') 'Empty project value lost its shadowing presence'
    $oldJava = Join-Path $testRoot 'java8'
    New-Item -ItemType Directory -Path (Join-Path $oldJava 'bin') | Out-Null
    Add-Type -TypeDefinition 'public class OldJavaFixture { public static void Main() { System.Console.Error.WriteLine("java version \"1.8.0\""); } }' -OutputAssembly (Join-Path $oldJava 'bin/java.exe') -OutputType ConsoleApplication
    $javaRejected = $false
    try { $null = Find-CodeAgentJava $oldJava } catch { $javaRejected = $true }
    Assert-True $javaRejected 'Java 8 was accepted'
    $javaRejected = $false
    try { $null = Find-CodeAgentJava (Join-Path $testRoot 'no-java') } catch { $javaRejected = $true }
    Assert-True $javaRejected 'An explicit missing Java home silently fell back'
    $otherCommand = Join-Path $testRoot 'other-bin'
    New-Item -ItemType Directory -Path $otherCommand | Out-Null
    [IO.File]::WriteAllText((Join-Path $otherCommand 'codeagent.cmd'), '@exit /b 0')
    $savedProcessPath = $env:Path
    try {
        $env:Path = $otherCommand + ';' + $env:Path
        Run-Install $jar $root $false
    } finally { $env:Path = $savedProcessPath }
    Assert-True ((Get-FileHash -LiteralPath (Join-Path $root 'lib/codeagent.jar')).Hash -eq $jarHash) 'Command conflict changed the installation'
    $junctionTarget = Join-Path $testRoot 'junction-target'
    New-Item -ItemType Directory -Path $junctionTarget | Out-Null
    $junction = Join-Path $testRoot 'junction'
    New-Item -ItemType Junction -Path $junction -Target $junctionTarget | Out-Null
    try {
        Run-Install $jar (Join-Path $junction 'installed') $false
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $junctionTarget 'installed'))) 'Installer followed a junction'
    } finally { Remove-Item -LiteralPath $junction -Force }
    $path = 'C:\Existing One;C:\Existing Two'
    $bin = Join-Path $root 'bin'
    $updated = Add-CodeAgentUserPath $path $bin
    Assert-True ($updated.StartsWith($path + ';')) 'PATH update changed existing entries'
    Assert-True ((Add-CodeAgentUserPath $updated $bin) -eq $updated) 'PATH update is not idempotent'
    $script:fixturePath = $path
    $readPath = { $script:fixturePath }
    $writePath = { param($Value) $script:fixturePath = $Value }
    Set-CodeAgentUserPath $path $updated $readPath $writePath
    Assert-True ($script:fixturePath -eq $updated) 'PATH adapter did not persist the expected value'
    $pathRejected = $false
    try { Set-CodeAgentUserPath $path 'incorrect' $readPath $writePath } catch { $pathRejected = $true }
    Assert-True ($pathRejected -and $script:fixturePath -eq $updated) 'Concurrent PATH change was overwritten'
    $pathRejected = $false
    try { Set-CodeAgentUserPath $updated 'incorrect' $readPath { param($Value) throw 'fixture-write-denied' } } catch { $pathRejected = $true }
    Assert-True ($pathRejected -and $script:fixturePath -eq $updated) 'PATH write failure was swallowed'
    Assert-True ([Environment]::GetEnvironmentVariable('Path','User') -eq $originalUserPath) 'Smoke test changed the real user PATH'
    if ($CheckPackage) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $license = Join-Path $testRoot 'fixture-license.txt'
        [IO.File]::WriteAllText($license, 'TEST FIXTURE ONLY - not a release license')
        $zip = Join-Path $testRoot 'distribution.zip'
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Repository 'scripts/package-distribution.ps1') -JarPath $jar -LicensePath $license -OutputPath $zip
        Assert-True ($LASTEXITCODE -eq 0) 'Distribution packaging failed'
        $zipHash = (Get-FileHash -LiteralPath $zip).Hash
        $privateResources = Join-Path $testRoot 'private-resources'
        New-Item -ItemType Directory -Path $privateResources | Out-Null
        [IO.File]::WriteAllText((Join-Path $privateResources '.env'), 'SYNTHETIC_FIXTURE=must-not-ship')
        $privateJar = Join-Path $testRoot 'private-fixture.jar'
        Copy-Item -LiteralPath $jar -Destination $privateJar
        & (Join-Path $JavaHome 'bin/jar.exe') --update --file $privateJar -C $privateResources .env
        Assert-True ($LASTEXITCODE -eq 0) 'Private resource fixture failed'
        $oldPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $null = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Repository 'scripts/package-distribution.ps1') -JarPath $privateJar -LicensePath $license -OutputPath $zip 2>&1
            $privateResult = $LASTEXITCODE
        } finally { $ErrorActionPreference = $oldPreference }
        Assert-True ($privateResult -ne 0 -and (Get-FileHash -LiteralPath $zip).Hash -eq $zipHash) 'JAR dotenv resource was shipped or old ZIP was replaced on failure'
        $archive = [IO.Compression.ZipFile]::OpenRead($zip)
        try {
            $names = @($archive.Entries | ForEach-Object { $_.FullName })
            $expected = @('codeagent.jar','install-codeagent.ps1','codeagent-install-common.ps1','codeagent.cmd.template','SHA256SUMS','LICENSE','README.txt')
            Assert-True ((($names | Sort-Object) -join '|') -eq (($expected | Sort-Object) -join '|')) 'Distribution whitelist mismatch'
        } finally { $archive.Dispose() }
        $extracted = Join-Path $testRoot 'downloaded package'
        [IO.Compression.ZipFile]::ExtractToDirectory($zip,$extracted)
        $downloadTemplate = Join-Path $extracted 'codeagent.cmd.template'
        $originalTemplate = [IO.File]::ReadAllBytes($downloadTemplate)
        [IO.File]::AppendAllText($downloadTemplate, 'damaged-template')
        $badInstall = Join-Path $testRoot 'damaged-install'
        $oldPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $null = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $extracted 'install-codeagent.ps1') -CodeAgentHome $badInstall -JavaHome $JavaHome -NoPathUpdate 2>&1
            $badResult = $LASTEXITCODE
        } finally { $ErrorActionPreference = $oldPreference }
        Assert-True ($badResult -ne 0 -and -not (Test-Path -LiteralPath $badInstall)) 'Damaged template checksum did not prevent installation'
        [IO.File]::WriteAllBytes($downloadTemplate,$originalTemplate)
        $downloadInstall = Join-Path $testRoot 'download-installed'
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $extracted 'install-codeagent.ps1') -CodeAgentHome $downloadInstall -JavaHome $JavaHome -NoPathUpdate
        Assert-True ($LASTEXITCODE -eq 0) 'Downloaded package installation failed'
        Assert-True (Test-Path -LiteralPath (Join-Path $downloadInstall 'bin/codeagent.cmd')) 'Downloaded package has no launcher'
        $workspace = Join-Path $testRoot 'download-workspace'
        New-Item -ItemType Directory -Path $workspace | Out-Null
        Probe-Launch $workspace (Join-Path $downloadInstall 'bin/codeagent.cmd') 'downloaded package' 9
        Write-Output 'PASS: ZIP whitelist, fixture license, checksums, install from extracted archive without repository files.'
    }
    Write-Output 'PASS: install/reinstall, user-config preservation, two working directories via PATH, special paths, arguments, exit code, invalid/missing JAR, Java 8/missing Java, command conflict, root/junction rejection, locked update recovery, PATH idempotence/isolation.'
} finally {
    if ($crossDriveWorkspace -and (Test-Path -LiteralPath $crossDriveWorkspace)) {
        $crossPath = [IO.Path]::GetFullPath($crossDriveWorkspace)
        $targetParent = [IO.Path]::GetFullPath((Join-Path $Repository 'target')).TrimEnd('\') + '\'
        if (-not $crossPath.StartsWith($targetParent,[StringComparison]::OrdinalIgnoreCase) -or
                [IO.Path]::GetFileName($crossPath) -notmatch '^launcher-workspace-[0-9a-f]{32}$') { throw 'Unsafe cross-drive fixture cleanup path' }
        foreach ($file in @(Get-ChildItem -LiteralPath $crossPath -Force)) {
            if ($file.PSIsContainer -or $file.Name -ne 'probe.txt' -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Unexpected cross-drive fixture file' }
            Remove-Item -LiteralPath $file.FullName -Force
        }
        Remove-Item -LiteralPath $crossPath -Force
    }
    $resolved = [IO.Path]::GetFullPath($testRoot)
    $temporary = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if ($resolved.StartsWith($temporary, [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolved)) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
