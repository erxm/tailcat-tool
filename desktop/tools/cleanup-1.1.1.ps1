#Requires -Version 5.1
<#
    Cleanup for the broken TailcatManager 1.1.1 registration (MSI error 2318).

    Background
    ----------
    1.1.1 installed itself into %LOCALAPPDATA%\TailcatManager, which was ALSO the
    app's data directory and the redirected Gradle build directory at that time.
    Its MSI file table therefore ended up listing build artifacts; those files are
    long gone, so `msiexec /x` fails in InstallValidate:

        Error 2318: File does not exist: ...\TailcatManager\build\tailcat-tool\...

    That unremovable product blocks every newer installer (RemoveExistingProducts),
    so 1.1.5 and later cannot install on this machine.

    1.1.6 and later use a different upgradeUuid and install into
    %LOCALAPPDATA%\Programs\TailcatManager, so they do NOT need this script to
    install. What this script does is cosmetic/hygiene:

      1. back up the HKLM uninstall key to a .reg file
      2. delete the stale uninstall registration
      3. delete the old program files that live in the data directory
         (KEEPS configuration.json, secrets.dat, instance.lock, logs\, jna\)
      4. delete shortcuts that point at the old exe

    The script is idempotent: running it twice is harmless.

    Usage: right-click -> "Run with PowerShell" (as Administrator) or
           powershell -ExecutionPolicy Bypass -File cleanup-1.1.1.ps1
#>

$ErrorActionPreference = 'Stop'

$Guid          = '{2040E1B4-64CF-3744-94BA-2E0B28A81882}'
$UninstallKey  = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\$Guid"
$DataDir       = Join-Path $env:LOCALAPPDATA 'TailcatManager'
$BackupFile    = Join-Path $env:TEMP 'tc-1.1.1-regbackup.reg'

# Never touched by this script - this is the user's real data.
$Keep          = @('configuration.json', 'secrets.dat', 'instance.lock', 'logs', 'jna')
# Old program files/directories that 1.1.1 put into the data directory.
$Remove        = @('TailcatManager.exe', 'app', 'runtime', 'build', 'build-tools', 'wix314')

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    (New-Object Security.Principal.WindowsPrincipal $id).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
}

if (-not (Test-Admin)) {
    Write-Host 'This script must run as Administrator.' -ForegroundColor Red
    Write-Host 'Right-click the file and choose "Run as Administrator", or start an'
    Write-Host 'elevated PowerShell and run: powershell -File "<path>\cleanup-1.1.1.ps1"'
    exit 1
}

$proc = Get-Process -Name 'TailcatManager' -ErrorAction SilentlyContinue
if ($proc) {
    Write-Host 'TailcatManager is running. Close it first (including the tray icon).' -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host 'Will do the following:' -ForegroundColor Cyan
Write-Host "  1. backup $UninstallKey"
Write-Host "     -> $BackupFile"
Write-Host "  2. delete that uninstall registration"
if (Test-Path $DataDir) {
    Write-Host "  3. delete old program files under $DataDir :"
    foreach ($n in $Remove) {
        $p = Join-Path $DataDir $n
        if (Test-Path $p) { Write-Host "       - $p" }
    }
    Write-Host '     will KEEP:'
    foreach ($n in $Keep) {
        $p = Join-Path $DataDir $n
        if (Test-Path $p) { Write-Host "       + $p" }
    }
}
Write-Host '  4. delete shortcuts that point at the old exe'
Write-Host ''
$answer = Read-Host 'Type YES to continue'
if ($answer -ne 'YES') {
    Write-Host 'Aborted, nothing changed.'
    exit 0
}

# --- 1. backup -------------------------------------------------------------
if (Test-Path $UninstallKey) {
    & reg.exe export "HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\$Guid" $BackupFile /y | Out-Null
    Write-Host "Backed up registration -> $BackupFile"
} else {
    Write-Host 'Registration already gone, nothing to back up.'
}

# --- 2. remove registration -------------------------------------------------
if (Test-Path $UninstallKey) {
    Remove-Item -Path $UninstallKey -Recurse -Force
    Write-Host 'Removed uninstall registration.'
} else {
    Write-Host 'Registration already gone.'
}

# --- 3. remove old program files, keep data --------------------------------
if (Test-Path $DataDir) {
    foreach ($n in $Remove) {
        $p = Join-Path $DataDir $n
        if (-not (Test-Path $p)) { continue }
        try {
            Remove-Item -Path $p -Recurse -Force
            Write-Host "Removed $p"
        } catch {
            Write-Host "Could not remove $p : $($_.Exception.Message)" -ForegroundColor Yellow
        }
    }
    Write-Host 'Kept (user data):'
    foreach ($n in $Keep) {
        $p = Join-Path $DataDir $n
        if (Test-Path $p) { Write-Host "  + $p" }
    }
}

# --- 4. shortcuts ----------------------------------------------------------
$shortcutDirs = @(
    (Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Tailcat Manager'),
    (Join-Path $env:USERPROFILE 'Desktop'),
    (Join-Path $env:PUBLIC 'Desktop')
)
foreach ($dir in $shortcutDirs) {
    if (-not (Test-Path $dir)) { continue }
    Get-ChildItem -Path $dir -Filter '*Tailcat*.lnk' -ErrorAction SilentlyContinue | ForEach-Object {
        try {
            Remove-Item -Path $_.FullName -Force
            Write-Host "Removed shortcut $($_.FullName)"
        } catch {
            Write-Host "Could not remove $($_.FullName)" -ForegroundColor Yellow
        }
    }
    if ((Split-Path $dir -Leaf) -eq 'Tailcat Manager' -and (Get-ChildItem $dir -ErrorAction SilentlyContinue).Count -eq 0) {
        Remove-Item -Path $dir -Force -ErrorAction SilentlyContinue
    }
}

Write-Host ''
Write-Host 'Done. Install TailcatManager 1.1.6 or later now.' -ForegroundColor Green
Write-Host "User data in $DataDir was not touched."
