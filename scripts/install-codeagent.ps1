param(
    [string]$JarPath = (Join-Path $PSScriptRoot 'codeagent.jar'),
    [string]$CodeAgentHome,
    [string]$JavaHome,
    [switch]$NoPathUpdate
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'codeagent-install-common.ps1')
$lock = $null
$transaction = $null
$journal = $null
$root = $null
$createdStaging = $false
try {
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { throw 'This installer supports Windows only.' }
    $source = [IO.Path]::GetFullPath($JarPath)
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw 'CodeAgent JAR was not found. Extract the complete installation package or specify -JarPath.' }
    Test-CodeAgentJar $source
    $sourceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $source).Hash
    $checksums = Join-Path $PSScriptRoot 'SHA256SUMS'
    if (Test-Path -LiteralPath $checksums -PathType Leaf) {
        $checksumLines = [IO.File]::ReadAllLines($checksums)
        foreach ($name in @('codeagent.jar','install-codeagent.ps1','codeagent-install-common.ps1','codeagent.cmd.template','LICENSE','README.txt')) {
            $matching = @($checksumLines | Where-Object { $_ -cmatch ('^([0-9a-fA-F]{64})\s+' + [regex]::Escape($name) + '$') })
            $file = if ($name -eq 'codeagent.jar') { $source } else { Join-Path $PSScriptRoot $name }
            if ($matching.Count -ne 1 -or -not (Test-Path -LiteralPath $file -PathType Leaf) -or
                    ($matching[0] -split '\s+')[0] -ne (Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash) { throw ('Package checksum verification failed: ' + $name) }
        }
    }
    $java = Find-CodeAgentJava $JavaHome
    $root = Resolve-CodeAgentInstallRoot $CodeAgentHome
    Assert-CodeAgentInstallPath $root $root
    $bin = Join-Path $root 'bin'
    $lib = Join-Path $root 'lib'
    $targetJar = Join-Path $lib 'codeagent.jar'
    $targetLauncher = Join-Path $bin 'codeagent.cmd'
    foreach ($path in @($bin,$lib,$targetJar,$targetLauncher,(Join-Path $root '.install.lock'))) { Assert-CodeAgentInstallPath $root $path }
    foreach ($command in @(Get-Command codeagent -All -ErrorAction SilentlyContinue)) {
        if ($command.Source -and [IO.Path]::GetFullPath($command.Source) -ne $targetLauncher) {
            throw ('Another codeagent command already exists: ' + $command.Source + '. Resolve the command conflict before installing.')
        }
    }
    New-Item -ItemType Directory -Force -Path $root | Out-Null
    $lock = [IO.File]::Open((Join-Path $root '.install.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    $journal = Join-Path $root '.install-transaction.json'
    $stage = Join-Path $root '.install-staging'
    Assert-CodeAgentInstallPath $root $journal
    Assert-CodeAgentInstallPath $root $stage
    if (Test-Path -LiteralPath $journal) {
        $previous = [IO.File]::ReadAllText($journal) | ConvertFrom-Json
        if ($previous.version -ne 1) { throw 'Unknown installation transaction. Manual recovery is required.' }
        Restore-CodeAgentInstall $root $previous
        Remove-Item -LiteralPath $journal -Force
        Remove-CodeAgentInstallStaging $root $stage
    }
    # No target is changed before the journal exists. After it is removed, the commit is complete.
    # Therefore journal-free staging at either interruption boundary contains disposable installer assets only.
    if (Test-Path -LiteralPath $stage) { Remove-CodeAgentInstallStaging $root $stage }
    New-Item -ItemType Directory -Force -Path $stage,$bin,$lib | Out-Null
    $createdStaging = $true
    $oldPath = [Environment]::GetEnvironmentVariable('Path','User')
    $newPath = if ($NoPathUpdate) { $oldPath } else { Add-CodeAgentUserPath $oldPath $bin }
    $transaction = [ordered]@{version=1; hadJar=(Test-Path -LiteralPath $targetJar); hadLauncher=(Test-Path -LiteralPath $targetLauncher); pathChanged=($oldPath -ne $newPath); oldPath=$oldPath; newPath=$newPath}
    if ($transaction.hadJar) { Copy-Item -LiteralPath $targetJar -Destination (Join-Path $stage 'old-codeagent.jar') }
    if ($transaction.hadLauncher) { Copy-Item -LiteralPath $targetLauncher -Destination (Join-Path $stage 'old-codeagent.cmd') }
    $newJar = Join-Path $stage 'codeagent.jar'
    Copy-Item -LiteralPath $source -Destination $newJar
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $newJar).Hash -ne $sourceHash) { throw 'Staged JAR checksum verification failed.' }
    $template = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'codeagent.cmd.template'))
    $launcherText = $template.Replace('@JAVA_EXECUTABLE@', $java.Replace('%','%%')).Replace("`r`n","`n").Replace("`n","`r`n")
    $encoding = [Text.Encoding]::GetEncoding([Globalization.CultureInfo]::CurrentCulture.TextInfo.OEMCodePage)
    if ($encoding.GetString($encoding.GetBytes($launcherText)) -ne $launcherText) { throw 'Java path cannot be represented by this Windows command encoding. Install Java in a compatible path.' }
    $newLauncher = Join-Path $stage 'codeagent.cmd'
    [IO.File]::WriteAllText($newLauncher, $launcherText, $encoding)
    # Publish a complete durable journal atomically before the first target mutation.
    $pendingJournal = Join-Path $stage 'transaction.pending'
    $journalBytes = (New-Object Text.UTF8Encoding($false)).GetBytes(($transaction | ConvertTo-Json))
    $journalStream = [IO.File]::Open($pendingJournal,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try { $journalStream.Write($journalBytes,0,$journalBytes.Length); $journalStream.Flush($true) }
    finally { $journalStream.Dispose() }
    [IO.File]::Move($pendingJournal,$journal)
    if ($transaction.hadJar) { [IO.File]::Replace($newJar,$targetJar,(Join-Path $stage 'replaced-codeagent.jar')) } else { [IO.File]::Move($newJar,$targetJar) }
    if ($transaction.hadLauncher) { [IO.File]::Replace($newLauncher,$targetLauncher,(Join-Path $stage 'replaced-codeagent.cmd')) } else { [IO.File]::Move($newLauncher,$targetLauncher) }
    if ($transaction.pathChanged) {
        Set-CodeAgentUserPath $oldPath $newPath
    }
    Remove-Item -LiteralPath $journal -Force
    $transaction = $null
    Remove-CodeAgentInstallStaging $root $stage
    Write-Output 'CodeAgent installed successfully. No model credentials were changed.'
    if ($NoPathUpdate) { Write-Output 'PATH was not changed. Invoke bin\codeagent.cmd from the installation directory.' }
    else { Write-Output 'Open a new terminal and run: codeagent' }
} catch {
    if ($transaction -and $journal -and (Test-Path -LiteralPath $journal)) {
        try {
            Restore-CodeAgentInstall $root $transaction
            Remove-Item -LiteralPath $journal -Force
            Remove-CodeAgentInstallStaging $root (Join-Path $root '.install-staging')
        } catch { Write-Error 'Installation recovery could not finish. Close running CodeAgent instances and run the installer again.' -ErrorAction Continue }
    } elseif ($createdStaging) {
        try { Remove-CodeAgentInstallStaging $root (Join-Path $root '.install-staging') }
        catch { Write-Error 'Installation staging cleanup could not finish; inspect the installation directory.' -ErrorAction Continue }
    }
    Write-Error ('CodeAgent installation failed: ' + $_.Exception.Message) -ErrorAction Continue
    exit 1
} finally { if ($lock) { $lock.Dispose() } }
