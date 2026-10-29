#Requires -Version 5.1
<#
    Cleanup for stale TailcatManager registrations left by 1.1.1 - 1.1.5.

    Background
    ----------
    1.1.1 - 1.1.5 installed the program into %LOCALAPPDATA%\TailcatManager, which
    was ALSO the app's data directory (and, from 1.1.1 on, the redirected Gradle
    build directory). Their MSI file tables therefore listed files that no longer
    exist, so `msiexec /x` fails during InstallValidate:

        Error 2318: File does not exist: ...\TailcatManager\build\tailcat-tool\...

    Such a product cannot be uninstalled, and it used to block newer installers
    (RemoveExistingProducts of the same upgradeUuid).

    1.1.6 and later use a NEW upgradeUuid and install into
    %LOCALAPPDATA%\TailcatManagerApp, so they install fine even with the broken
    entry present - this script does NOT have to run before installing.
    It only cleans up afterwards:

      1. back up + delete every stale "TailcatManager" registration
         (any version whose InstallLocation is the data directory)
      2. delete the old program files that live in the data directory:
         TailcatManager.exe, app\, runtime\, build\
         KEEPS configuration.json, secrets.dat, instance.lock, logs\, jna\,
         and keeps build-tools\ (that is the WiX toolset jpackage uses to build
         the installer - deleting it forces a re-download next build)
      3. delete shortcuts whose target is the OLD exe inside the data directory
         (shortcuts to %LOCALAPPDATA%\TailcatManagerApp are left alone)

    The script is idempotent: running it twice is harmless.

    Usage: right-click -> "Run as Administrator", or in an elevated PowerShell:
           powershell -File "<path>\cleanup-old-versions.ps1"
#>

$ErrorActionPreference = 'Stop'

$DataDir    = Join-Path $env:LOCALAPPDATA 'TailcatManager'
$OldExe     = Join-Path $DataDir 'TailcatManager.exe'
$Keep       = @('configuration.json', 'secrets.dat', 'instance.lock', 'logs', 'jna', 'build-tools')
$Remove     = @('TailcatManager.exe', 'app', 'runtime', 'build')

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    (New-Object Security.Principal.WindowsPrincipal $id).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
}

if (-not (Test-Admin)) {
    Write-Host 'This script must run as Administrator.' -ForegroundColor Red
    Write-Host 'Right-click the file and choose "Run as Administrator", or start an'
    Write-Host 'elevated PowerShell and run: powershell -File "<path>\cleanup-old-versions.ps1"'
    exit 1
}

$proc = Get-Process -Name 'TailcatManager' -ErrorAction SilentlyContinue
if ($proc) {
    Write-Host 'TailcatManager is running. Close it first (including the tray icon).' -ForegroundColor Red
    exit 1
}

# Collect stale registrations: any TailcatManager entry installed into the data dir.
$roots = @('HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall',
           'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall',
           'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall')
$stale = @()
foreach ($root in $roots) {
    if (-not (Test-Path $root)) { continue }
    Get-ChildItem $root -ErrorAction SilentlyContinue | ForEach-Object {
        $p = Get-ItemProperty $_.PSPath -ErrorAction SilentlyContinue
        if ($p.DisplayName -ne 'TailcatManager') { return }
        $loc = "$($p.InstallLocation)".TrimEnd('\')
        if (-not $loc) { return }
        if ($loc -ieq $DataDir.TrimEnd('\')) {
            $stale += [pscustomobject]@{
                Key     = "Registry::$($_.Name)"
                Root    = $_.PSPath
                Version = $p.DisplayVersion
                Uninst  = $p.UninstallString
            }
        }
    }
}

Write-Host ''
Write-Host 'Will do the following:' -ForegroundColor Cyan
if ($stale.Count -eq 0) {
    Write-Host '  1. no stale registration found (nothing to back up / delete)'
} else {
    Write-Host '  1. back up then delete these stale registrations:'
    foreach ($s in $stale) {
        Write-Host ("       - TailcatManager $($s.Version)   $($s.Root)")
    }
    Write-Host "     backups -> $env:TEMP\tc-stale-uninstall-<version>.reg"
}
if (Test-Path $DataDir) {
    Write-Host "  2. delete old program files under $DataDir :"
    foreach ($n in $Remove) {
        $q = Join-Path $DataDir $n
        if (Test-Path $q) { Write-Host "       - $q" }
    }
    Write-Host '     will KEEP:'
    foreach ($n in $Keep) {
        $q = Join-Path $DataDir $n
        if (Test-Path $q) { Write-Host "       + $q" }
    }
}
Write-Host '  3. delete shortcuts that point at the OLD exe in the data directory'
Write-Host ''
$answer = Read-Host 'Type YES to continue'
if ($answer -ne 'YES') {
    Write-Host 'Aborted, nothing changed.'
    exit 0
}

# --- 1. registrations ------------------------------------------------------
foreach ($s in $stale) {
    $backup = Join-Path $env:TEMP ("tc-stale-uninstall-{0}.reg" -f $s.Version)
    $regPath = ($s.Root -replace '^Microsoft\.PowerShell\.Core\\Registry::', '').Replace('HKEY_LOCAL_MACHINE', 'HKLM').Replace('HKEY_CURRENT_USER', 'HKCU')
    & reg.exe export $regPath $backup /y | Out-Null
    Write-Host "Backed up $($s.Version) -> $backup"
    Remove-Item -Path $s.Root -Recurse -Force
    Write-Host "Removed registration for $($s.Version)."
}

# --- 2. old program files, keep data ---------------------------------------
if (Test-Path $DataDir) {
    foreach ($n in $Remove) {
        $q = Join-Path $DataDir $n
        if (-not (Test-Path $q)) { continue }
        try {
            Remove-Item -Path $q -Recurse -Force
            Write-Host "Removed $q"
        } catch {
            Write-Host "Could not remove $q : $($_.Exception.Message)" -ForegroundColor Yellow
        }
    }
    Write-Host 'Kept (user data):'
    foreach ($n in $Keep) {
        $q = Join-Path $DataDir $n
        if (Test-Path $q) { Write-Host "  + $q" }
    }
}

# --- 3. only shortcuts that target the OLD exe ------------------------------
if (Test-Path $OldExe) { Write-Host 'Old exe still present, skipping shortcut pass.' }
$sh = New-Object -ComObject WScript.Shell
$shortcutDirs = @(
    (Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Tailcat Manager'),
    (Join-Path $env:USERPROFILE 'Desktop'),
    (Join-Path $env:PUBLIC 'Desktop')
)
foreach ($dir in $shortcutDirs) {
    if (-not (Test-Path $dir)) { continue }
    Get-ChildItem -Path $dir -Filter '*Tailcat*.lnk' -ErrorAction SilentlyContinue | ForEach-Object {
        $target = $null
        try { $target = $sh.CreateShortcut($_.FullName).TargetPath } catch { }
        if ($target -and ($target -ieq $OldExe)) {
            try {
                Remove-Item -Path $_.FullName -Force
                Write-Host "Removed shortcut $($_.FullName)"
            } catch {
                Write-Host "Could not remove $($_.FullName)" -ForegroundColor Yellow
            }
        } elseif ($target) {
            Write-Host "Kept shortcut $($_.Name) -> $target"
        }
    }
    if ((Split-Path $dir -Leaf) -eq 'Tailcat Manager' -and (Get-ChildItem $dir -ErrorAction SilentlyContinue).Count -eq 0) {
        Remove-Item -Path $dir -Force -ErrorAction SilentlyContinue
    }
}

Write-Host ''
Write-Host 'Done.' -ForegroundColor Green
Write-Host "User data in $DataDir was not touched."
