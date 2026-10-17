Set-StrictMode -Version Latest

function Read-CodeAgentRootSetting([string]$File) {
    if (Test-Path -LiteralPath $File -PathType Leaf) {
        $reader = $null
        try {
            # Java's UTF-8 BufferedReader does not strip a BOM or autodetect UTF-16.
            $reader = [IO.StreamReader]::new($File, [Text.UTF8Encoding]::new($false,$true), $false)
            while ($null -ne ($line = $reader.ReadLine())) {
                if ($line -cmatch '^[\x00-\x20]*CODEAGENT_HOME[\x00-\x20]*=(.*)$') {
                    return @{Found=$true; Value=$Matches[1].Trim([char[]](0..32))}
                }
            }
        } catch { } # Match Java's absent/unreadable optional dotenv fallback.
        finally { if ($reader) { $reader.Dispose() } }
    }
    return @{Found=$false; Value=''}
}

function Test-CodeAgentBlank([string]$Value) {
    return $null -eq $Value -or $Value -match '^[\x09-\x0d\x1c-\x20\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]*$'
}

function Resolve-CodeAgentInstallRoot([string]$Override) {
    $profileDirectory = [Environment]::GetFolderPath('UserProfile')
    $value = $Override
    if (Test-CodeAgentBlank $value) { $value = $env:CODEAGENT_HOME }
    if (Test-CodeAgentBlank $value) {
        $project = Read-CodeAgentRootSetting (Join-Path (Get-Location).Path '.env')
        $user = Read-CodeAgentRootSetting (Join-Path $profileDirectory '.env')
        if ($project.Found) { $value = $project.Value }
        elseif ($user.Found) { $value = $user.Value }
    }
    if (Test-CodeAgentBlank $value) { $value = Join-Path $profileDirectory '.codeagent' }
    $value = $value.Trim([char[]](0..32))
    if ($value -eq '~') { $value = $profileDirectory }
    elseif ($value.StartsWith('~/') -or $value.StartsWith('~\')) { $value = Join-Path $profileDirectory $value.Substring(2) }
    if ($value -match '[\x00-\x1f\x7f-\x9f]' -or $value -notmatch '^(?:[A-Za-z]:[\\/]|[\\/]{2}[^\\/]+[\\/][^\\/]+(?:[\\/]|$))') {
        throw 'CODEAGENT_HOME must be a fully qualified Windows path or ~/ path.'
    }
    $root = [IO.Path]::GetFullPath($value).TrimEnd('\', '/')
    if ($root -match '^[A-Za-z]:$') { throw 'A drive root cannot be the installation directory.' }
    return $root
}

function Assert-CodeAgentInstallPath([string]$Root, [string]$Target) {
    $rootPath = [IO.Path]::GetFullPath($Root).TrimEnd('\')
    $targetPath = [IO.Path]::GetFullPath($Target)
    if ($targetPath -ne $rootPath -and -not $targetPath.StartsWith($rootPath + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Installation path escaped CODEAGENT_HOME.'
    }
    $cursor = $targetPath
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw 'Installation paths must not traverse symbolic links or junctions.'
            }
        }
        $parent = [IO.Path]::GetDirectoryName($cursor)
        if ($parent -eq $cursor) { break }
        $cursor = $parent
    }
}

function Normalize-CodeAgentPathEntry([string]$Entry) {
    try { return [IO.Path]::GetFullPath([Environment]::ExpandEnvironmentVariables($Entry.Trim().Trim('"'))).TrimEnd('\', '/') }
    catch { return $Entry.Trim().Trim('"').TrimEnd('\', '/') }
}

function Add-CodeAgentUserPath([string]$CurrentPath, [string]$BinDirectory) {
    $normalized = Normalize-CodeAgentPathEntry $BinDirectory
    foreach ($entry in ($CurrentPath -split ';')) {
        if ($entry -and (Normalize-CodeAgentPathEntry $entry) -eq $normalized) { return $CurrentPath }
    }
    if ([string]::IsNullOrEmpty($CurrentPath)) { return $BinDirectory }
    if ($CurrentPath.EndsWith(';')) { return $CurrentPath + $BinDirectory }
    return $CurrentPath + ';' + $BinDirectory
}

function Set-CodeAgentUserPath([string]$Expected, [string]$Value,
        [scriptblock]$ReadPath = { [Environment]::GetEnvironmentVariable('Path','User') },
        [scriptblock]$WritePath = { param($NewValue) [Environment]::SetEnvironmentVariable('Path',$NewValue,'User') }) {
    if ((& $ReadPath) -ne $Expected) { throw 'User PATH changed during installation. Retry without overwriting the concurrent change.' }
    & $WritePath $Value
}

function Find-CodeAgentJava([string]$ExplicitJavaHome) {
    $candidates = New-Object 'Collections.Generic.List[string]'
    if ($ExplicitJavaHome) { $candidates.Add((Join-Path $ExplicitJavaHome 'bin/java.exe')) }
    else {
        if ($env:JAVA_HOME) { $candidates.Add((Join-Path $env:JAVA_HOME 'bin/java.exe')) }
        foreach ($command in @(Get-Command java -CommandType Application -All -ErrorAction SilentlyContinue)) {
            $candidates.Add($command.Source)
        }
    }
    foreach ($candidate in $candidates) {
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
        $info = New-Object Diagnostics.ProcessStartInfo
        $info.FileName = $candidate
        $info.Arguments = '-version'
        $info.UseShellExecute = $false
        $info.CreateNoWindow = $true
        $info.RedirectStandardError = $true
        $info.RedirectStandardOutput = $true
        $process = $null
        try {
            $process = [Diagnostics.Process]::Start($info)
            $errorTask = $process.StandardError.ReadToEndAsync()
            $outputTask = $process.StandardOutput.ReadToEndAsync()
            if (-not $process.WaitForExit(10000)) { $process.Kill(); continue }
            $version = $errorTask.Result + $outputTask.Result
            if ($process.ExitCode -eq 0 -and $version -match 'version\s+"(?:1\.)?(\d+)' -and [int]$Matches[1] -ge 17) {
                return [IO.Path]::GetFullPath($candidate)
            }
        } catch { }
        finally { if ($process) { $process.Dispose() } }
    }
    throw 'Java 17 or later is required. Install it or provide -JavaHome with a valid JDK/JRE directory.'
}

function Test-CodeAgentJar([string]$JarPath) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = $null
    try {
        $archive = [IO.Compression.ZipFile]::OpenRead($JarPath)
        $entry = $archive.GetEntry('META-INF/MANIFEST.MF')
        if (-not $entry) { throw 'missing manifest' }
        $reader = New-Object IO.StreamReader($entry.Open())
        try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($manifest -cnotmatch '(?m)^Main-Class:\s*com\.codeagent\.cli\.Main\s*$' -or
                -not $archive.GetEntry('com/codeagent/cli/Main.class')) { throw 'incorrect entry point' }
    } catch { throw 'The selected JAR is not a runnable CodeAgent application.' }
    finally { if ($archive) { $archive.Dispose() } }
}

function Remove-CodeAgentInstallStaging([string]$Root, [string]$Staging) {
    Assert-CodeAgentInstallPath $Root $Staging
    if ([IO.Path]::GetFileName($Staging) -ne '.install-staging') { throw 'Unexpected staging directory.' }
    if (Test-Path -LiteralPath $Staging) {
        $items = @(Get-ChildItem -LiteralPath $Staging -Force)
        $ownedNames = @('codeagent.jar','codeagent.cmd','old-codeagent.jar','old-codeagent.cmd','replaced-codeagent.jar','replaced-codeagent.cmd','transaction.pending')
        foreach ($item in $items) {
            Assert-CodeAgentInstallPath $Root $item.FullName
            if ($item.PSIsContainer -or $item.Name -cnotin $ownedNames) { throw 'Unrecognized entry inside installation staging; inspect it before retrying.' }
        }
        foreach ($item in $items) {
            Remove-Item -LiteralPath $item.FullName -Force
        }
        Remove-Item -LiteralPath $Staging -Force
    }
}

function Restore-CodeAgentInstall([string]$Root, $Transaction) {
    $stage = Join-Path $Root '.install-staging'
    foreach ($asset in @(@{Name='codeagent.jar'; Directory='lib'; Existed=$Transaction.hadJar},
                          @{Name='codeagent.cmd'; Directory='bin'; Existed=$Transaction.hadLauncher})) {
        $target = Join-Path (Join-Path $Root $asset.Directory) $asset.Name
        $backup = Join-Path $stage ('old-' + $asset.Name)
        Assert-CodeAgentInstallPath $Root $target
        Assert-CodeAgentInstallPath $Root $backup
        if ($asset.Existed) {
            if (-not (Test-Path -LiteralPath $backup -PathType Leaf)) { throw 'Installation recovery backup is missing.' }
            [IO.File]::Copy($backup, $target, $true)
        } elseif (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Force }
    }
    if ($Transaction.pathChanged) {
        $current = [Environment]::GetEnvironmentVariable('Path', 'User')
        if ($current -eq $Transaction.newPath) { Set-CodeAgentUserPath $Transaction.newPath $Transaction.oldPath }
        elseif ($current -ne $Transaction.oldPath) { throw 'User PATH changed during installation; automatic rollback refused.' }
    }
}
