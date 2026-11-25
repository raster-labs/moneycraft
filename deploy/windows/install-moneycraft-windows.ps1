#!/usr/bin/env pwsh
#Requires -Version 5.1
<#
.SYNOPSIS
    Workstation installer and private-LAN TLS CA fingerprint enrollment script for MoneyCraft Desktop on Windows.
.DESCRIPTION
    Verifies installer SHA-256 integrity, optionally validates and enrolls a private-LAN CA PEM certificate
    against an out-of-band administrator SHA-256 DER fingerprint (S-08 / ITER110-026 — never trust-on-first-use),
    writes the per-user workstation host configuration, and installs or uninstalls the MSI/EXE package.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $false)]
    [string]$InstallerPath,

    [Parameter(Mandatory = $false)]
    [string]$ExpectedInstallerSha256,

    [Parameter(Mandatory = $false)]
    [string]$HostOrigin = "https://localhost:8443",

    [Parameter(Mandatory = $false)]
    [string]$CaPemPath,

    [Parameter(Mandatory = $false)]
    [string]$ExpectedCaSha256,

    [Parameter(Mandatory = $false)]
    [string]$TrustProfileId = "WORKSTATION_PROVISIONED_CA",

    [Parameter(Mandatory = $false)]
    [string]$AdministratorReference = "workstation-admin",

    [switch]$Silent,

    [switch]$Uninstall
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$UserHome = [Environment]::GetFolderPath("UserProfile")
$AppConfigDir = Join-Path $UserHome ".moneycraft-desktop"
$PropsFile = Join-Path $AppConfigDir "workstation-host.properties"

if ($Uninstall) {
    if ($InstallerPath -and (Test-Path $InstallerPath) -and $InstallerPath.EndsWith(".msi")) {
        $MsiUninstallArgs = @("/x", "`"$InstallerPath`"", "/norestart")
        if ($Silent) {
            $MsiUninstallArgs += "/qn"
        }
        $Proc = Start-Process -FilePath "msiexec.exe" -ArgumentList $MsiUninstallArgs -Wait -PassThru
        if ($Proc.ExitCode -ne 0) {
            throw "MSI uninstall failed with exit code $($Proc.ExitCode)"
        }
    }
    if (Test-Path $PropsFile) {
        Remove-Item -Force $PropsFile
    }
    Write-Host "MoneyCraft workstation enrollment removed."
    exit 0
}

$ParsedUri = [System.Uri]$HostOrigin
if ($ParsedUri.Scheme -ne "https") {
    throw "MC-TLS-CANONICAL-HTTPS-REQUIRED: HostOrigin must use https:// ($HostOrigin)"
}

New-Item -ItemType Directory -Force -Path $AppConfigDir | Out-Null

$ProvisionedCaDest = ""
$NormalizedCaSha = ""
if ($CaPemPath) {
    if (-not (Test-Path $CaPemPath)) {
        throw "Private-LAN CA PEM file not found at $CaPemPath"
    }
    if (-not $ExpectedCaSha256) {
        throw "MC-TLS-CA-FINGERPRINT-REQUIRED: ExpectedCaSha256 is mandatory when enrolling a private-LAN CA PEM."
    }

    $Cert = [System.Security.Cryptography.X509Certificates.X509Certificate2]::CreateFromPemFile($CaPemPath)
    $Sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $ActualBytes = $Sha256.ComputeHash($Cert.RawData)
    }
    finally {
        $Sha256.Dispose()
        $Cert.Dispose()
    }
    $ActualHex = ([System.BitConverter]::ToString($ActualBytes) -replace "-", "").ToLowerInvariant()
    $NormalizedCaSha = (($ExpectedCaSha256 -replace ":", "") -replace "\s+", "").ToLowerInvariant()
    if ($ActualHex -ne $NormalizedCaSha) {
        throw "MC-TLS-CA-FINGERPRINT-MISMATCH: CA DER SHA-256 ($ActualHex) did not match expected ($NormalizedCaSha)."
    }

    $ProvisionedCaDest = Join-Path $AppConfigDir "enrolled-host-ca.pem"
    Copy-Item -Force $CaPemPath $ProvisionedCaDest
}

$EscapedCaPath = $ProvisionedCaDest -replace "\\", "/"
$PropsContent = @(
    "hostOrigin=$($ParsedUri.GetLeftPart([System.UriPartial]::Authority))"
    "trustProfileId=$TrustProfileId"
    "administratorReference=$AdministratorReference"
)
if ($EscapedCaPath) {
    $PropsContent += "caPemPath=$EscapedCaPath"
    $PropsContent += "caExpectedSha256=$NormalizedCaSha"
}
Set-Content -Path $PropsFile -Value ($PropsContent -join "`n") -Encoding utf8

if ($InstallerPath) {
    if (-not (Test-Path $InstallerPath)) {
        throw "Installer package not found at $InstallerPath"
    }
    if ($ExpectedInstallerSha256) {
        $ActualInstallerHash = (Get-FileHash -Algorithm SHA256 -Path $InstallerPath).Hash.ToLowerInvariant()
        $ExpectedInstallerNorm = $ExpectedInstallerSha256.Trim().ToLowerInvariant()
        if ($ActualInstallerHash -ne $ExpectedInstallerNorm) {
            throw "Installer SHA-256 mismatch: actual=$ActualInstallerHash expected=$ExpectedInstallerNorm"
        }
    }

    if ($InstallerPath.EndsWith(".msi")) {
        $MsiArgs = @("/i", "`"$InstallerPath`"", "/norestart")
        if ($Silent) {
            $MsiArgs += "/qn"
        }
        $InstallProc = Start-Process -FilePath "msiexec.exe" -ArgumentList $MsiArgs -Wait -PassThru
        if ($InstallProc.ExitCode -ne 0) {
            throw "MSI installation failed with exit code $($InstallProc.ExitCode)"
        }
    }
    elseif ($InstallerPath.EndsWith(".exe")) {
        $ExeArgs = @()
        if ($Silent) {
            $ExeArgs += "/quiet"
        }
        $InstallProc = Start-Process -FilePath $InstallerPath -ArgumentList $ExeArgs -Wait -PassThru
        if ($InstallProc.ExitCode -ne 0) {
            throw "EXE installation failed with exit code $($InstallProc.ExitCode)"
        }
    }
    else {
        throw "Unsupported installer extension for $InstallerPath (expected .msi or .exe)"
    }
}

Write-Host "MoneyCraft Desktop workstation enrollment complete for $($ParsedUri.Authority)."
