#!/usr/bin/env pwsh
#Requires -Version 7.0
<#
.SYNOPSIS
    Builds the standalone Windows x64 .msi and .exe installers for the MoneyCraft 1.0.0 Personal Desktop Client.
.DESCRIPTION
    Compiles moneycraft-desktop under Java 25 LTS, produces a stripped custom jlink runtime image
    (excluding all JDBC, server WAR, and embedded database dependencies), packages Windows .msi and .exe
    installers with Start Menu and Desktop shortcuts via jpackage, and emits SHA-256 digests (UM-110-03).
#>
[CmdletBinding()]
param(
    [string]$AppVersion = "1.0.0",
    [string]$Architecture = "x64",
    [switch]$SkipMavenTests
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "../..")
$DesktopDir = Join-Path $RepoRoot "desktop"
$TargetDir = Join-Path $DesktopDir "target"
$JPackageInputDir = Join-Path $TargetDir "jpackage-input"
$RuntimeImageDir = Join-Path $TargetDir "runtime-image"
$InstallerDir = Join-Path $TargetDir "installer"

if (-not $env:JAVA_HOME) {
    throw "JAVA_HOME must point to a Java 25 LTS JDK installation."
}

$JLinkBin = Join-Path $env:JAVA_HOME "bin/jlink"
$JPackageBin = Join-Path $env:JAVA_HOME "bin/jpackage"
if ($IsWindows) {
    $JLinkBin = "$JLinkBin.exe"
    $JPackageBin = "$JPackageBin.exe"
}

if (-not (Test-Path $JLinkBin)) {
    throw "jlink binary not found at $JLinkBin"
}
if (-not (Test-Path $JPackageBin)) {
    throw "jpackage binary not found at $JPackageBin"
}

Push-Location $RepoRoot
try {
    $MvnArgs = @("-B", "clean", "package", "-pl", "desktop")
    if ($SkipMavenTests) {
        $MvnArgs += "-DskipTests"
    }
    Write-Host "[1/5] Building moneycraft-desktop module..."
    & mvn @MvnArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Maven package build failed with exit code $LASTEXITCODE"
    }

    if (Test-Path $RuntimeImageDir) {
        Remove-Item -Recurse -Force $RuntimeImageDir
    }
    if (Test-Path $InstallerDir) {
        Remove-Item -Recurse -Force $InstallerDir
    }
    New-Item -ItemType Directory -Force -Path $InstallerDir | Out-Null

    $Modules = "java.base,java.desktop,java.net.http,jdk.httpserver,java.naming,java.security.jgss,jdk.crypto.ec"
    Write-Host "[2/5] Creating stripped Java 25 LTS jlink runtime image ($Modules)..."
    & $JLinkBin `
        --add-modules $Modules `
        --strip-debug `
        --no-man-pages `
        --no-header-files `
        --output $RuntimeImageDir
    if ($LASTEXITCODE -ne 0) {
        throw "jlink failed with exit code $LASTEXITCODE"
    }

    $UpgradeUuid = "8f4e3c2b-9a10-4d7c-b512-6e11a8f01100"
    $CommonArgs = @(
        "--name", "MoneyCraft",
        "--app-version", $AppVersion,
        "--vendor", "Raster Images",
        "--description", "MoneyCraft 1.0.0 Personal Accounting Desktop Client",
        "--input", $JPackageInputDir,
        "--main-jar", "moneycraft-desktop.jar",
        "--main-class", "com.raster.moneycraft.desktop.MoneyCraftDesktopMain",
        "--runtime-image", $RuntimeImageDir,
        "--dest", $InstallerDir,
        "--win-menu",
        "--win-menu-group", "MoneyCraft",
        "--win-shortcut",
        "--win-dir-chooser",
        "--win-upgrade-uuid", $UpgradeUuid
    )

    Write-Host "[3/5] Packaging Windows .msi installer via jpackage..."
    & $JPackageBin --type msi @CommonArgs
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage MSI build failed with exit code $LASTEXITCODE"
    }

    Write-Host "[4/5] Packaging Windows .exe setup installer via jpackage..."
    & $JPackageBin --type exe @CommonArgs
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage EXE build failed with exit code $LASTEXITCODE"
    }

    $GeneratedMsi = Get-ChildItem -Path $InstallerDir -Filter "*.msi" | Select-Object -First 1
    $GeneratedExe = Get-ChildItem -Path $InstallerDir -Filter "*.exe" | Select-Object -First 1
    if (-not $GeneratedMsi -or -not $GeneratedExe) {
        throw "Expected both .msi and .exe installers in $InstallerDir"
    }

    $CanonicalMsi = Join-Path $InstallerDir "MoneyCraft-Desktop-$AppVersion-$Architecture.msi"
    $CanonicalExe = Join-Path $InstallerDir "MoneyCraft-Desktop-$AppVersion-$Architecture.exe"
    Move-Item -Force $GeneratedMsi.FullName $CanonicalMsi
    Move-Item -Force $GeneratedExe.FullName $CanonicalExe

    Write-Host "[5/5] Writing SHA256SUMS.txt..."
    $SumsPath = Join-Path $InstallerDir "SHA256SUMS.txt"
    $HashLines = @()
    foreach ($Artifact in @($CanonicalMsi, $CanonicalExe)) {
        $Digest = (Get-FileHash -Algorithm SHA256 -Path $Artifact).Hash.ToLowerInvariant()
        $FileName = Split-Path -Leaf $Artifact
        $HashLines += "$Digest  $FileName"
    }
    Set-Content -Path $SumsPath -Value ($HashLines -join "`n") -Encoding utf8NoBOM
    Write-Host "Installers ready in ${InstallerDir}:"
    Get-Content $SumsPath | ForEach-Object { Write-Host "  $_" }
}
finally {
    Pop-Location
}
